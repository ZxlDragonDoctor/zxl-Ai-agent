import test from 'node:test'
import assert from 'node:assert/strict'
import { submitScopedRun, readScopedRun, cancelScopedRun, listScopedRuns } from '../scopedMultiagent.ts'
import { replaceSession } from '../../utils/sessionRequest.ts'
const actor=id=>({mode:'required',authenticated:true,csrfToken:'csrf-'+id,principal:{subjectId:id,tenantId:'tenant',loginName:id,role:'employee'}})
const id='12345678-1234-1234-1234-123456789abc'
const reply=(value,status=200)=>new Response(JSON.stringify(value),{status})
test('scoped writes use CSRF and reviewed run paths only',async()=>{
 const original=globalThis.fetch;const calls=[];replaceSession(actor('a'))
 globalThis.fetch=async(url,init)=>{calls.push({url,init});return reply(url.endsWith('/status')?{runs:[]}:url.endsWith('/cancel')?{executionId:id,status:'accepted'}:init.method==='POST'?{executionId:id}:{run:{executionId:id,report:null,runtime:{}},events:[]})}
 try{await submitScopedRun('goal',30000);await readScopedRun(id);await cancelScopedRun(id);await listScopedRuns();assert.equal(calls[0].url,'/api/ai/multiagent/runs');assert.equal(new Headers(calls[0].init.headers).get('X-CSRF-Token'),'csrf-a');assert.equal(calls[2].url,`/api/ai/multiagent/runs/${id}/cancel`);assert.equal(calls[2].init.credentials,'same-origin');assert.deepEqual(JSON.parse(calls[0].init.body),{goal:'goal',timeoutMs:30000})}finally{globalThis.fetch=original}
})
test('foreign URLs and malformed run ids never reach transport',async()=>{const original=globalThis.fetch;let calls=0;globalThis.fetch=async()=>{calls++;return reply({})};try{await assert.rejects(readScopedRun('https://foreign.test/api'),/INVALID_RUN_ID/);await assert.rejects(cancelScopedRun('../status'),/INVALID_RUN_ID/);assert.equal(calls,0)}finally{globalThis.fetch=original}})
test('identity switch rejects an old successful response',async()=>{const original=globalThis.fetch;replaceSession(actor('a'));let resolve;globalThis.fetch=()=>new Promise(r=>resolve=r);try{const pending=readScopedRun(id);replaceSession(actor('b'));resolve(reply({run:{executionId:id},events:[]}));await assert.rejects(pending,/STALE_SESSION/)}finally{globalThis.fetch=original}})
test('invisible runs preserve the safe 404 code',async()=>{const original=globalThis.fetch;replaceSession(actor('a'));globalThis.fetch=async()=>reply({code:'NOT_FOUND'},404);try{await assert.rejects(readScopedRun(id),e=>e.code==='NOT_FOUND'&&e.status===404)}finally{globalThis.fetch=original}})
