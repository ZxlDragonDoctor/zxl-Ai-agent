import assert from 'node:assert/strict'
import test from 'node:test'
import { projectRun } from './project-run.mjs'

test('requires successful empty retrieval and the explicit outcome to score abstention', () => {
  const task = { id: 'a', capability: 'research', status: 'COMPLETED',
    result: '当前有效知识中没有找到匹配资料；未生成缺乏证据的结论。 / No matching current evidence.' }
  const event = { taskId: 'a', event: { type: 'TOOL_COMPLETED', toolName: 'knowledge__knowledge_lookup_v2',
    content: JSON.stringify({ success: true, data: { hits: [], degradations: [] } }) } }
  const report = { status: 'completed', startedAt: 1, finishedAt: 2, tasks: [task] }
  const view = { run: { executionId: 'run', runtime: {}, report }, events: [{ data: event }] }
  assert.equal(projectRun('case', view, 0).status, 'abstained')
  report.tasks.push({ ...task, id: 'b', result: 'unsupported answer' })
  assert.equal(projectRun('case', view, 0).status, 'completed')
  report.tasks.pop()
  view.events = []
  assert.equal(projectRun('case', view, 0).status, 'completed')
  view.events = [{ data: event }]
  const healthy = event.event.content
  event.event.content = JSON.stringify({ success: true, data: { hits: [], degradations: ['VECTOR_CHANNEL_FAILED'] } })
  assert.equal(projectRun('case', view, 0).status, 'completed')
  event.event.content = healthy
  report.status = 'failed'
  assert.equal(projectRun('case', view, 0).status, 'failed')
})

test('only counts referenced research sources and started tools', () => {
  const result = 'Answer [C1]\n\n引用 / Sources:\n[C1] verified · chunk-a · L1–2\n[C2] uncited · chunk-b · L3–4'
  const view = {
    run: { executionId: 'run', runtime: {}, report: { status: 'completed', startedAt: 10, finishedAt: 20,
      tasks: [{ id: 'research-task', capability: 'research', status: 'COMPLETED', result },
        { capability: 'code', status: 'COMPLETED', result: result.replace('verified', 'untrusted') }] } },
    events: [{ data: { event: { type: 'TOOL_REQUESTED', toolName: 'requested' } } },
      { data: { event: { type: 'TOOL_STARTED', toolName: 'started' } } },
      { data: { taskId: 'research-task', event: { type: 'TOOL_COMPLETED', toolName: 'knowledge__knowledge_lookup_v2',
        content: JSON.stringify({ data: { hits: [{ documentId: 'verified', chunkId: 'chunk-a', startLine: 1, endLine: 2 }] } }) } } }],
  }
  const projected = projectRun('case', view, 0)
  assert.deepEqual(projected.sources, ['verified'])
  assert.deepEqual(projected.tools, ['started'])
  assert.equal(projected.tokens, null)
  assert.throws(() => projectRun('case', view), /write count/)
  const evidence = view.events.pop()
  assert.throws(() => projectRun('case', view, 0), /no matching tool evidence/)
  view.events.push(evidence)
  view.run.runtime.waitingFor = 'archived'
  assert.throws(() => projectRun('case', view, 0), /Current terminal/)
})
