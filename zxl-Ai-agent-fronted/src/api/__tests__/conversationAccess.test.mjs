import test from 'node:test'
import assert from 'node:assert/strict'
import { restoreChat } from '../../utils/agentFlow.ts'
import { assertConversationWritable, isReadOnlyConversation, conversationBusy } from '../../utils/conversationAccess.ts'
test('read-only archives reject writes before invoking any transport',()=>{
 let requests=0
 const write=conversation=>{assertConversationWritable(conversation);requests++}
 assert.throws(()=>write({readOnly:true}),error=>error.code==='CONVERSATION_READ_ONLY')
 assert.equal(requests,0)
 write({readOnly:false});write({});write(undefined);assert.equal(requests,3)
})
test('legacy responses remain writable; archives never start busy recovery polling',()=>{
 assert.equal(isReadOnlyConversation({}),false)
 assert.equal(isReadOnlyConversation({readOnly:true}),true)
 assert.equal(conversationBusy({readOnly:true,busy:true}),false)
 assert.equal(conversationBusy({readOnly:true,busy:false}),false)
 assert.equal(conversationBusy({busy:true}),true)
})
test('an unfinished archive displays frozen evidence without inventing a finish time or a live run', () => {
  const run = { runId: 'old', startedAt: '2026-01-01T00:00:00Z', status: 'running', events: [
    { type: 'TOOL_COMPLETED', step: 1, callId: 'evidence', toolName: 'oldLookup', content: 'saved result' },
    { type: 'TOOL_STARTED', step: 2, callId: 'unfinished', toolName: 'oldWorker' },
  ] }
  const detail = { readOnly: true, runs: [run], messages: [] }
  const [reply] = restoreChat(detail)
  assert.equal(reply.flow.archivedIncomplete, true)
  assert.equal(reply.flow.status, 'interrupted')
  assert.equal(reply.flow.finishedAt, undefined)
  assert.equal(reply.flow.steps[0].tools[0].result, 'saved result')
  assert.equal(reply.flow.steps[1].tools[0].status, 'interrupted')
  assert.equal(run.status, 'running')
  assert.equal(restoreChat({ ...detail, readOnly: false })[0].flow.status, 'running')
})
