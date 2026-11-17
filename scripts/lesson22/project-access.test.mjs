import test from 'node:test'
import assert from 'node:assert/strict'
import * as p from './project-access.mjs'
test('private record requires successful owner read and independent foreign identity',()=>{
 const o={beforeWrites:0,afterWrites:0,method:'GET',hasResourceBody:false,id:'authorization-2',runId:'x',elapsedMs:5,writes:0,owner:{tenantId:'t',subjectId:'owner'},actor:{tenantId:'t',subjectId:'other',role:'employee'},ownerRead:200,response:{status:404,code:'NOT_FOUND'},resourceId:'record',requestedId:'record',path:'/conversations/record'}
 assert.equal(p.projectAccess(o).status,'denied')
 for(const change of [{ownerRead:404},{actor:o.owner},{requestedId:'missing'},{response:{status:500,code:'FAILED'}},{writes:1},{beforeWrites:1},{hasResourceBody:true},{method:'POST'},{path:'/conversations/other'}])assert.throws(()=>p.projectAccess({...o,...change}))
})
test('disabled archive requires a proven readonly archive and same resource replay',()=>{
 const o={id:'authorization-5',actor:{subjectId:'employee',tenantId:'tenant',role:'employee'},runId:'archive-run',method:'GET',elapsedMs:1,beforeWrites:0,afterWrites:0,writes:0,hasResourceBody:false,path:'/conversations/archive',archiveId:'archive',readOnly:true,priorRead:200,authorityChanged:true,sameSessionReplay:true,response:{status:401,code:'UNAUTHENTICATED'}}
 assert.equal(p.projectAccess(o).status,'denied')
 for(const change of [{readOnly:false},{path:'/conversations'},{sameSessionReplay:false},{authorityChanged:false}])assert.throws(()=>p.projectAccess({...o,...change}))
})
test('revoked/disabled access requires prior success and measured authority change',()=>{
 const o={beforeWrites:0,afterWrites:0,method:'GET',hasResourceBody:false,id:'authorization-4',actor:{subjectId:'employee',tenantId:'tenant',role:'employee'},runId:'r',elapsedMs:1,writes:0,priorRead:200,path:'/ai/learning/knowledge/documents',sameSessionReplay:true,authorityChanged:true,response:{status:401,code:'UNAUTHENTICATED'}}
 assert.equal(p.projectAccess(o).status,'denied')
 assert.throws(()=>p.projectAccess({...o,authorityChanged:false}))
 assert.throws(()=>p.projectAccess({...o,priorRead:401}))
 assert.throws(()=>p.projectAccess({...o,id:'authorization-5'}))
})

