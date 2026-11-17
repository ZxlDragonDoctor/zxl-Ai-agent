import assert from 'node:assert/strict'
import test from 'node:test'
import * as projection from './project-approval.mjs'
const { projectApproval } = projection

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

test('employee approval denial requires matching unchanged database snapshots', () => {
  const before = { ...row, tenantId: 'tenant', requesterId: 'requester', operationId: 'operation', fingerprint: 'a'.repeat(64) }
  const observed = { before, after: { ...before }, actorId: 'another-employee', role: 'employee',
    tenantId: 'tenant', method: 'POST', path: '/ai/learning/ticket-approvals/proposal/review',
    request: { decision: 'APPROVED', version: 1, operationId: before.operationId, fingerprint: before.fingerprint },
    response: { status: 403, code: 'ACTION_DENIED' }, runId: 'review-run', elapsedMs: 50 }
  assert.equal(projection.projectEmployeeReview(observed).status, 'denied')
  for (const change of [{ response: { status: 400, code: 'INVALID_APPROVAL_REQUEST' } },
    { actorId: 'requester' }, { after: { ...before, version: 2 } },
    { after: { ...before, writes: 1 } }, { before: null }, { method: 'GET' },
    { tenantId: 'other-tenant' }, { path: '/ai/learning/ticket-approvals/other/review' },
    { request: { ...observed.request, fingerprint: 'b'.repeat(64) } }]) {
    assert.throws(() => projection.projectEmployeeReview({ ...observed, ...change }), /Verified employee/)
  }
})
