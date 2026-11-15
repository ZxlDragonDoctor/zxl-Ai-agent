import assert from 'node:assert/strict'
import test from 'node:test'
import { runCases } from './run-formal.mjs'

const executionId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const item = { id: 'budget-case', input: { prompt: 'Example', budgetMs: 1000 } }

test('waits for cleanup and observes the write count before projecting a timeout', async () => {
  let reads = 0
  let writesRead = false
  const result = await runCases([item], {
    submit: async (goal, budget) => {
      assert.equal(goal, item.input.prompt)
      assert.equal(budget, 1000)
      return { executionId }
    },
    detail: async () => ({ run: { executionId, runtime: { cleanupPending: ++reads === 1 },
      report: { status: 'failed', error: { code: 'RUN_DEADLINE_EXCEEDED' },
        tasks: [], startedAt: 1000, finishedAt: 2010 } }, events: [] }),
    countWrites: async () => { assert.equal(reads, 2); writesRead = true; return 0 },
    wait: async () => {},
  })
  assert.equal(writesRead, true)
  assert.equal(result[0].status, 'timeout')
  assert.equal(result[0].elapsedMs, 1010)
})

test('never scores a foreign report as the submitted run', async () => {
  await assert.rejects(runCases([item], {
    submit: async () => ({ executionId }),
    detail: async () => ({ run: { executionId: 'different' } }),
    countWrites: async () => assert.fail('must not count'),
  }), /does not match/)
})
