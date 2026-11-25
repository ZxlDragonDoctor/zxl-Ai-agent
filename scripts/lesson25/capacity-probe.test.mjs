import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {runProbe,percentiles} from './capacity-probe.mjs';
async function fixture(action,run){
 const server=createServer(action);await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try{await run(`http://127.0.0.1:${server.address().port}`);}finally{server.closeAllConnections();await new Promise(r=>server.close(r));}
}
const id=n=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const options=origin=>({origin,cookie:'SESSION=private-cookie',csrf:'private-csrf',goal:'private goal',requests:4,concurrency:2,httpTimeoutMs:500,observeTimeoutMs:1000,runBudgetMs:500,pollIntervalMs:100});
const json=(res,status,value)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(value));};
test('nearest rank percentiles distinguish empty samples and never interpolate',()=>{
 assert.deepEqual(percentiles([]),{samples:0,p50:null,p95:null,p99:null});
 assert.deepEqual(percentiles([100,1,2,3]),{samples:4,p50:2,p95:100,p99:100});
});
test('real HTTP closed-loop load is bounded, awaits terminal reports and hides payloads',async()=>{
 let submitted=0,active=0,peak=0;const read=new Map();
 await fixture((req,res)=>{
  assert.equal(req.headers.cookie,'SESSION=private-cookie');
  if(req.method==='POST'){
   assert.equal(req.headers['x-csrf-token'],'private-csrf');active++;peak=Math.max(peak,active);submitted++;
   return json(res,202,{executionId:id(submitted)});
  }
  const count=(read.get(req.url)||0)+1;read.set(req.url,count);
  const status=count===1?'running':'completed';if(status==='completed')active--;
  json(res,200,{run:{report:{status,result:'private answer'},runtime:{cleanupPending:false}}});
 },async origin=>{
  const result=await runProbe(options(origin));assert.equal(submitted,4);assert.equal(peak,2);
  assert.equal(result.counts.completed,4);assert.equal(result.latency.submission.samples,4);assert.equal(result.latency.terminal.completed.samples,4);
  assert.ok(result.samples.every(x=>x.observedMs>=x.submissionMs));
  const text=JSON.stringify(result);for(const secret of ['private-cookie','private-csrf','private goal','private answer',origin])assert.ok(!text.includes(secret));
 });
});
test('HTTP429 and asynchronous capacity and queue failures remain distinct',async()=>{
 let n=0;await fixture((req,res)=>{
  if(req.method==='POST'){n++;return n===1?json(res,429,{code:'AGENT_CAPACITY_EXCEEDED'}):json(res,202,{executionId:id(n)});}
  const number=Number(req.url.split('-').at(-1));json(res,200,{run:{report:{status:'failed',error:{code:number===2?'AGENT_CAPACITY_EXCEEDED':'CAPACITY_QUEUE_TIMEOUT'}},runtime:{cleanupPending:number===2}}});
 },async origin=>{
  const r=await runProbe({...options(origin),requests:3,concurrency:1});assert.equal(r.counts.httpRejected,1);assert.equal(r.counts.capacityFailed,1);assert.equal(r.counts.queueTimedOut,1);
  assert.equal(r.samples.filter(x=>x.cleanupPending).length,1);assert.equal(r.latency.terminal.completed.samples,0);
 });
});
test('observation timeout attempts one cancellation without claiming terminal or cleanup',async()=>{
 let cancels=0;await fixture((req,res)=>{
  if(req.url.endsWith('/cancel')){cancels++;return json(res,200,{status:'cancelled'});}
  if(req.method==='POST')return json(res,202,{executionId:id(1)});
  json(res,200,{run:{report:{status:'running'},runtime:{cleanupPending:true}}});
 },async origin=>{
  const r=await runProbe({...options(origin),requests:1,concurrency:1,observeTimeoutMs:150});assert.equal(cancels,1);assert.equal(r.counts.observationTimedOut,1);
  assert.equal(r.samples[0].cancelAcknowledged,true);assert.equal(r.samples[0].cleanupPending,true);assert.equal(r.latency.terminal.completed.samples,0);
 });
});
test('redirects do not forward credentials and untrusted error codes are suppressed',async()=>{
 let redirected=0;await fixture((req,res)=>{if(req.url==='/leak'){redirected++;return res.end('private');}res.writeHead(307,{Location:'/leak'});res.end();},async origin=>{
  const r=await runProbe({...options(origin),requests:1,concurrency:1});assert.equal(redirected,0);assert.equal(r.counts.httpFailed,1);
 });
 await fixture((req,res)=>json(res,500,{code:'PRIVATE_SECRET'}),async origin=>{const r=await runProbe({...options(origin),requests:1,concurrency:1});assert.ok(!JSON.stringify(r).includes('PRIVATE_SECRET'));});
});
test('HTTP transport timeout is bounded and malformed successful responses cannot be accepted',async()=>{
 await fixture(()=>{},async origin=>{const r=await runProbe({...options(origin),requests:1,concurrency:1,httpTimeoutMs:100});assert.equal(r.counts.transportFailed,1);assert.equal(r.counts.completed,0);});
 await fixture((req,res)=>json(res,202,{executionId:'../../private'}),async origin=>{const r=await runProbe({...options(origin),requests:1,concurrency:1});assert.equal(r.counts.protocolFailed,1);});
});
test('oversized bodies and responses stalled after headers are bounded without payload output',async()=>{
 await fixture((req,res)=>json(res,202,{executionId:id(1),private:'x'.repeat(140000)}),async origin=>{const r=await runProbe({...options(origin),requests:1,concurrency:1});assert.equal(r.counts.protocolFailed,1);assert.ok(JSON.stringify(r).length<4000);});
 await fixture((req,res)=>{res.writeHead(202,{'Content-Type':'application/json'});res.write('{');},async origin=>{const r=await runProbe({...options(origin),requests:1,concurrency:1,httpTimeoutMs:100});assert.equal(r.counts.transportFailed,1);});
});
test('invalid load, credentials and non-loopback origins fail before network work',async()=>{
 for(const patch of [{origin:'https://example.com'},{origin:'http://localhost:3000'},{origin:'http://127.0.0.1:3000/path'},{requests:17},{concurrency:5},{concurrency:0},{requests:0},{cookie:'x\r\ny'},{csrf:''},{goal:''},{httpTimeoutMs:0},{observeTimeoutMs:120001}])
  await assert.rejects(runProbe({...options('http://127.0.0.1:3000'),...patch}),/PROBE_CONFIG_INVALID/);
});
