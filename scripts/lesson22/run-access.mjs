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
const observations=[]
const beforeWrites=Number(sql(`SELECT count(*) FROM lesson17.approved_ticket WHERE tenant_id='${t}'`));assert.equal(beforeWrites,0)
function record(id,data,start){
 const afterWrites=Number(sql(`SELECT count(*) FROM lesson17.approved_ticket WHERE tenant_id='${t}'`));
 const hasResourceBody=Object.keys(data.responseBody??{}).some(k=>!['code','message','timestamp','path','status','error'].includes(k));
 const {responseBody,...safe}=data;
 const o={id,runId:randomUUID(),elapsedMs:Date.now()-start,beforeWrites,afterWrites,method:'GET',hasResourceBody,writes:afterWrites-beforeWrites,...safe};
 projectAccess(o);observations.push(o)
}
const owner=await session('owner'),other=await session('other'),foreign=await session('foreign',t+'-foreign')
const c=await owner.request('/conversations',{title:'Private support record for access evaluation',agentType:'it-ticket-skill'});assert.equal(c.status,201)
const cid=c.body.id
assert.match(cid,/^[a-f0-9-]{36}$/)
const ownerRead=await owner.request('/conversations/'+cid);assert.equal(ownerRead.status,200)
let start=Date.now();let denied=await other.request('/conversations/'+cid)
record('authorization-2',{owner:owner.principal,actor:other.principal,resourceId:cid,requestedId:cid,path:'/conversations/'+cid,ownerRead:ownerRead.status,responseBody:denied.body,response:{status:denied.status,code:denied.body.code}},start)
const maintainer=await session('maintainer')
const published=await maintainer.request('/ai/learning/knowledge/documents',{documentId:'procurement-private',sourceName:'Procurement handbook.md',content:'# Procurement\nOnly this workspace may read this procurement handbook. Purchase requests require manager review.'});assert.equal(published.status,200)
const doc=await owner.request('/ai/learning/knowledge/documents/procurement-private');assert.equal(doc.status,200)
start=Date.now();denied=await foreign.request('/ai/learning/knowledge/documents/procurement-private')
record('authorization-1',{owner:owner.principal,actor:foreign.principal,resourceId:'procurement-private',requestedId:'procurement-private',path:'/ai/learning/knowledge/documents/procurement-private',ownerRead:doc.status,responseBody:denied.body,response:{status:denied.status,code:denied.body.code}},start)
for(const [who,id,path] of [['revoked','authorization-4','/ai/learning/knowledge/documents']]){
 const s=await session(who);const prior=await s.request(path);assert.equal(prior.status,200)
 let changed
  const logout=await s.request('/auth/logout',{},false);assert.equal(logout.status,204)
  changed=sql(`SELECT count(*) FROM lesson17.auth_session WHERE tenant_id='${t}' AND subject_id='${t}-revoked' AND revoked_at IS NOT NULL`)==='1'
 start=Date.now();denied=await s.request(path,undefined,false)
 record(id,{actor:s.principal,path,priorRead:prior.status,authorityChanged:changed,sameSessionReplay:true,responseBody:denied.body,response:{status:denied.status,code:denied.body.code}},start)
}
const bytes=readFileSync('docs/learning/examples/lesson22-cases.json')
const evidence={datasetSha256:createHash('sha256').update(bytes).digest('hex'),model:'qwen-plus',knowledgeVersion:'lesson22-access-v1',variant:'access-boundary',runs:observations.map(projectAccess)}
writeFileSync('target/lesson22-access-observations.json',JSON.stringify(observations,null,2));writeFileSync('target/lesson22-access-evidence.json',JSON.stringify(evidence,null,2));console.log('PASS',evidence.runs.map(o=>o.id).join(','))


