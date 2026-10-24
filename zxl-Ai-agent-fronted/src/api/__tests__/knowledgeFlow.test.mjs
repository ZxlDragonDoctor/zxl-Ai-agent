import test from 'node:test'
import assert from 'node:assert/strict'
import { applyAgentEvent, restoreChat } from '../../utils/agentFlow.ts'
test('structured knowledge result is kept separately from answer text and private reasoning', () => {
  const reply = { role: 'ai', content: '', flow: { status: 'running', phase: 'connecting', steps: [], startedAt: 0 } }
  applyAgentEvent(reply, { type: 'KNOWLEDGE_RESULT', step: 1, content: '{"status":"ANSWERED"}' })
  assert.equal(reply.knowledgePayload, '{"status":"ANSWERED"}')
  assert.equal(reply.content, '')
  assert.equal(reply.flow.steps.length, 0)
})
test('refreshing conversation restores citation metadata from persisted public events', () => {
  const [reply] = restoreChat({ runs: [{ runId: 'run', startedAt: '2026-10-25T00:00:00Z', finishedAt: '2026-10-25T00:00:01Z', answer: '检查网络 [E1]', status: 'completed', endReason: 'ANSWER_READY', events: [{ type: 'KNOWLEDGE_RESULT', step: 1, content: '{"snapshotId":"s1"}' }] }], messages: [] })
  assert.equal(reply.knowledgePayload, '{"snapshotId":"s1"}')
  assert.equal(reply.content, '检查网络 [E1]')
  assert.equal(reply.flow.restored, true)
})
