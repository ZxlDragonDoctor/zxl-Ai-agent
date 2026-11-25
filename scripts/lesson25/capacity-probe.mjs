import {performance} from 'node:perf_hooks';
import {pathToFileURL} from 'node:url';

export function percentiles(values){
 const sorted=[...values].sort((a,b)=>a-b),n=sorted.length;
 const at=p=>n?sorted[Math.ceil(p*n)-1]:null;
 return {samples:n,p50:at(.5),p95:at(.95),p99:at(.99)};
}
const outcomes=['completed','capacityFailed','queueTimedOut','cancelled','interrupted','failed','httpRejected','httpFailed','transportFailed','protocolFailed','observationTimedOut'];
const terminal=new Set(['completed','failed','cancelled','interrupted']);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const elapsed=start=>Math.round((performance.now()-start)*100)/100;
function validate(o){
 if(!o||!/^http:\/\/127\.0\.0\.1:[0-9]{1,5}$/.test(o.origin??''))throw Error('PROBE_CONFIG_INVALID');
 let u;try{u=new URL(o.origin);}catch{throw Error('PROBE_CONFIG_INVALID');}if(!u.port)throw Error('PROBE_CONFIG_INVALID');
 for(const key of ['cookie','csrf','goal'])if(typeof o[key]!=='string'||!o[key].trim()||o[key].length>(key==='goal'?12000:8192)||/[\r\n]/.test(o[key]))throw Error('PROBE_CONFIG_INVALID');
 for(const [key,min,max]of [['requests',1,16],['concurrency',1,4],['httpTimeoutMs',50,5000],['observeTimeoutMs',100,120000],['runBudgetMs',100,120000],['pollIntervalMs',100,2000]])
  if(!Number.isSafeInteger(o[key])||o[key]<min||o[key]>max)throw Error('PROBE_CONFIG_INVALID');
 if(o.concurrency>o.requests)throw Error('PROBE_CONFIG_INVALID');
}
/** Bounded local HTTP experiment; reports only timings, opaque run IDs and fixed classifications. */
export async function runProbe(options){
 validate(options);const o={...options};const began=performance.now();
 async function request(path,method='GET',body,timeout=o.httpTimeoutMs,observationLimited=false){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),Math.max(1,timeout));
  try{
   const r=await fetch(o.origin+path,{method,redirect:'manual',signal:controller.signal,headers:{Cookie:o.cookie,'X-CSRF-Token':o.csrf,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
   const reader=r.body?.getReader();let length=0;const parts=[];
   if(reader)while(true){const {value,done}=await reader.read();if(done)break;length+=value.length;if(length>131072){await reader.cancel();throw Error('PROBE_PROTOCOL');}parts.push(Buffer.from(value));}
   let value;try{value=JSON.parse(Buffer.concat(parts).toString('utf8'));}catch{value=null;}
   return {status:r.status,value};
  }catch(e){throw Error(e.message==='PROBE_PROTOCOL'?'PROBE_PROTOCOL':controller.signal.aborted&&observationLimited?'PROBE_OBSERVATION':'PROBE_TRANSPORT');}
  finally{clearTimeout(timer);}
 }
 const samples=[];let next=0;
 async function sample(index){
  const start=performance.now(),s={index,outcome:'transportFailed',submissionMs:null,observedMs:null,cleanupPending:null,cancelAcknowledged:false};let id=null,finished=false,deadline=null;
  try{
   const submitted=await request('/api/ai/multiagent/runs','POST',{goal:o.goal,timeoutMs:o.runBudgetMs});s.submissionMs=elapsed(start);s.httpStatus=submitted.status;
   if(submitted.status!==202){s.outcome=submitted.status===429?'httpRejected':'httpFailed';if(submitted.status===429&&submitted.value?.code==='AGENT_TENANT_CAPACITY_EXCEEDED')s.capacityDomain='tenant';return;}
   if(typeof submitted.value?.executionId!=='string'||!uuid.test(submitted.value.executionId)){s.outcome='protocolFailed';return;}
   id=submitted.value.executionId;s.executionId=id;
   deadline=performance.now()+o.observeTimeoutMs;
   while(performance.now()<deadline){
    const remaining=deadline-performance.now();
    const r=await request('/api/ai/multiagent/runs/'+id,'GET',undefined,Math.min(o.httpTimeoutMs,remaining),remaining<=o.httpTimeoutMs);
    if(r.status!==200){s.outcome=r.status===429?'httpRejected':'httpFailed';return;}
    const view=r.value?.run,report=view?.report;
    if(!view||!report||typeof report.status!=='string'){s.outcome='protocolFailed';return;}
    if(typeof view.runtime?.cleanupPending==='boolean')s.cleanupPending=view.runtime.cleanupPending;
    if(terminal.has(report.status)){
     finished=true;s.observedMs=elapsed(start);
     const code=report.error?.code;
     if(code==='AGENT_TENANT_CAPACITY_EXCEEDED')s.capacityDomain='tenant';
     s.outcome=report.status==='failed'?(['AGENT_CAPACITY_EXCEEDED','AGENT_TENANT_CAPACITY_EXCEEDED'].includes(code)?'capacityFailed':code==='CAPACITY_QUEUE_TIMEOUT'?'queueTimedOut':'failed'):report.status;
     return;
    }
    await sleep(Math.min(o.pollIntervalMs,Math.max(0,deadline-performance.now())));
   }
   s.outcome='observationTimedOut';
  }catch(e){s.outcome=e.message==='PROBE_PROTOCOL'?'protocolFailed':e.message==='PROBE_OBSERVATION'?'observationTimedOut':'transportFailed';}
  finally{
   if(s.submissionMs===null)s.submissionMs=elapsed(start);
   if(id&&!finished){try{const r=await request('/api/ai/multiagent/runs/'+id+'/cancel','POST');s.cancelAcknowledged=r.status===200&&['cancelled','already_finished'].includes(r.value?.status);}catch{}}
   s.totalObservationMs=elapsed(start);samples.push(s);
  }
 }
 await Promise.all(Array.from({length:o.concurrency},async()=>{while(next<o.requests){const index=next++;await sample(index);}}));
 samples.sort((a,b)=>a.index-b.index);const counts=Object.fromEntries(outcomes.map(k=>[k,0]));for(const s of samples)counts[s.outcome]++;
 return {schemaVersion:1,loadModel:'closed-loop',limits:{requests:o.requests,concurrency:o.concurrency,httpTimeoutMs:o.httpTimeoutMs,observeTimeoutMs:o.observeTimeoutMs,runBudgetMs:o.runBudgetMs,pollIntervalMs:o.pollIntervalMs},elapsedMs:elapsed(began),counts,latency:{submission:percentiles(samples.map(s=>s.submissionMs)),terminal:Object.fromEntries(['completed','capacityFailed','queueTimedOut','cancelled','interrupted','failed'].map(k=>[k,percentiles(samples.filter(s=>s.outcome===k).map(s=>s.observedMs))]))},samples,scope:'Bounded local closed-loop observation; not production capacity, open-arrival throughput, supplier SLA, provider cancellation or proof of zero resource ownership.'};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const raw=process.argv.slice(2),names={origin:'origin',requests:'requests',concurrency:'concurrency','http-timeout-ms':'httpTimeoutMs','observe-timeout-ms':'observeTimeoutMs','run-budget-ms':'runBudgetMs','poll-interval-ms':'pollIntervalMs'},o={cookie:process.env.LESSON25_COOKIE,csrf:process.env.LESSON25_CSRF,goal:process.env.LESSON25_GOAL,requests:4,concurrency:2,httpTimeoutMs:3000,observeTimeoutMs:60000,runBudgetMs:45000,pollIntervalMs:250};let valid=raw.length%2===0;const seen=new Set();
 for(let i=0;i<raw.length;i+=2){const key=names[raw[i].slice(2)];if(!raw[i].startsWith('--')||!key||seen.has(key)){valid=false;break;}seen.add(key);o[key]=key==='origin'?raw[i+1]:Number(raw[i+1]);}
 if(!valid){console.error('PROBE_CONFIG_INVALID');process.exitCode=2;}
 else runProbe(o).then(r=>{console.log(JSON.stringify(r,null,2));if(r.counts.transportFailed||r.counts.protocolFailed||r.counts.observationTimedOut)process.exitCode=1;}).catch(()=>{console.error('PROBE_CONFIG_INVALID');process.exitCode=2;});
}
