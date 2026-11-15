import assert from 'node:assert/strict'
import test from 'node:test'
import { projectRun } from './project-run.mjs'

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
