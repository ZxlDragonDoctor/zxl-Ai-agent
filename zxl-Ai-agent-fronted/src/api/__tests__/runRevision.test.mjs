import test from 'node:test'
import assert from 'node:assert/strict'
import { createRunRevision, shouldPollRun } from '../../utils/runRevision.ts'
test('terminal runs continue polling until actual cleanup finishes',()=>{
 assert.equal(shouldPollRun('cancelled',true),true)
 assert.equal(shouldPollRun('failed',true),true)
 assert.equal(shouldPollRun('completed',true),true)
 assert.equal(shouldPollRun('cancelled',false),false)
 assert.equal(shouldPollRun('completed',false),false)
 assert.equal(shouldPollRun('running',false),true)
})
test('cancel invalidates an in-flight poll before its response can overwrite cancelled state',async()=>{
 const revision=createRunRevision();let state='running',releaseOld
 const old=revision.current();const response=new Promise(resolve=>releaseOld=resolve)
 const poll=response.then(value=>{if(revision.isCurrent(old))state=value})
 const cancelled=revision.next();if(revision.isCurrent(cancelled))state='cancelled'
 releaseOld('running');await poll;assert.equal(state,'cancelled')
})
test('selection or identity invalidation also rejects prior completion and error handlers',()=>{
 const revision=createRunRevision();const cancel=revision.next();revision.next()
 assert.equal(revision.isCurrent(cancel),false);assert.equal(revision.isCurrent(revision.current()),true)
})
