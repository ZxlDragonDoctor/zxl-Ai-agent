import {readFileSync,writeFileSync} from 'node:fs'
import {execFileSync} from 'node:child_process'
import {randomUUID,createHash} from 'node:crypto'
import assert from 'node:assert/strict'
import {projectAccess} from './project-access.mjs'
const e=process.env,t=e.LESSON22_ACCESS_TENANT,base=e.LESSON22_ORIGIN
if(!/^lesson22-access-[a-z0-9-]+$/.test(t??'')||!/^http:\/\/127\.0\.0\.1:\d+$/.test(base??'')||!e.LESSON22_ACCESS_PASSWORD||!e.LESSON22_PSQL)throw Error('Explicit local fixture required')
function sql(query){return execFileSync(e.LESSON22_PSQL,['-XAt','-w','-h','127.0.0.1','-c',query],{encoding:'utf8',timeout:10000}).trim()}
async function session(who,tenant=t){
 const jar=new Map();let csrf
 async function request(path,body,update=true){
  const r=await fetch(base+'/api'+path,{method:body===undefined?'GET':'POST',headers:{Cookie:[...jar.values()].join('; '),Origin:base,...(body===undefined?{}:{'Content-Type':'application/json','X-CSRF-Token':csrf})},body:body===undefined?undefined:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(60000)})
  if(update)for(const c of r.headers.getSetCookie()){const v=c.split(';')[0];jar.set(v.split('=')[0],v)}
  const text=await r.text();return {status:r.status,body:text?JSON.parse(text):null}
 }
 csrf=(await request('/auth/status')).body.csrfToken
 const logged=await request('/auth/login',{loginName:t+'-'+who,password:e.LESSON22_ACCESS_PASSWORD,tenantId:tenant});assert.equal(logged.status,200);csrf=logged.body.csrfToken
 return {request,principal:logged.body.principal}
}

const fixture=JSON.parse(readFileSync(process.argv[2] ?? 'target/lesson22-access-archive/archive.json'));
assert.equal(fixture.tenantId,t);assert.equal(fixture.subjectId,t+'-archive');assert.match(fixture.archiveId,/^[a-f0-9-]{36}$/);
const actor=await session('archive');const path='/conversations/'+fixture.archiveId;
const prior=await actor.request(path);assert.equal(prior.status,200);assert.equal(prior.body.readOnly,true);
const beforeWrites=Number(sql(`SELECT count(*) FROM lesson17.approved_ticket WHERE tenant_id='${t}'`));
sql(`BEGIN; UPDATE lesson17.authorization_epoch SET version=version+1 WHERE tenant_id='${t}' AND subject_id='${t}-archive'; UPDATE lesson17.membership SET status='DISABLED',version=version+1 WHERE tenant_id='${t}' AND subject_id='${t}-archive'; COMMIT;`);
const changed=sql(`SELECT status FROM lesson17.membership WHERE tenant_id='${t}' AND subject_id='${t}-archive'`)==='DISABLED';
const started=Date.now(),denied=await actor.request(path,undefined,false);
const afterWrites=Number(sql(`SELECT count(*) FROM lesson17.approved_ticket WHERE tenant_id='${t}'`));
const o={id:'authorization-5',runId:randomUUID(),method:'GET',actor:actor.principal,path,archiveId:fixture.archiveId,readOnly:prior.body.readOnly,priorRead:prior.status,authorityChanged:changed,sameSessionReplay:true,hasResourceBody:Object.keys(denied.body??{}).some(k=>!['code','message','timestamp','path','status','error'].includes(k)),response:{status:denied.status,code:denied.body.code},beforeWrites,afterWrites,writes:afterWrites-beforeWrites,elapsedMs:Date.now()-started};
projectAccess(o);writeFileSync('target/lesson22-archive-observation.json',JSON.stringify(o,null,2));console.log('PASS archive read200 readonly then membership disabled401');
