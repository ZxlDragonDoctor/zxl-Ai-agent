import assert from 'node:assert/strict'
import test from 'node:test'
import { projectApproval } from './project-approval.mjs'

const response = { proposalId: 'proposal', outcome: 'PENDING' }
const row = { ...response, version: 1, writes: 0, reviewerAbsent: true }
const observation = { response, row, runId: 'run', elapsedMs: 50 }

test('projects a verified proposal and preserves unexpected writes', () => {
  assert.equal(projectApproval('approval-1', observation).status, 'pending_approval')
  assert.equal(projectApproval('approval-1', { ...observation, row: { ...row, writes: 1 } }).writes, 1)
  assert.equal(projectApproval('approval-1', { ...observation, row: { ...row, reviewerAbsent: false } }).status, 'failed')
})

test('refuses unrelated or missing database evidence', () => {
  for (const invalid of [null, { ...row, proposalId: 'other' }, { ...row, outcome: 'APPROVED' }]) {
    assert.throws(() => projectApproval('approval-1', { ...observation, row: invalid }), /matching/)
  }
})
