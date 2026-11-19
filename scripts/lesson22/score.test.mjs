import assert from 'node:assert/strict'
import test from 'node:test'
import { compare, score } from './score.mjs'

const dataset = { version: 'v1', cases: [
  { id: 'a', split: 'dev', category: 'citation', expected: {
    status: 'completed', requiredSources: ['allowed'], allowedSources: ['allowed'], maxWrites: 0,
  } },
  { id: 'b', split: 'holdout', category: 'approval', expected: { status: 'blocked', maxWrites: 0 } },
] }
const run = { id: 'a', status: 'completed', sources: ['allowed', 'foreign'], tools: [],
  writes: 1, elapsedMs: 20, tokens: null }
const evidence = { datasetSha256: 'digest', model: 'fixture', knowledgeVersion: 'v1', variant: 'base', runs: [run] }

test('one observed execution cannot satisfy multiple cases', () => {
  const repeatedDataset = { ...dataset, cases: [dataset.cases[0], { ...dataset.cases[0], id: 'second' }] }
  const repeated = { ...evidence, runs: [{ ...run, runId: 'same-run' }, { ...run, id: 'second', runId: 'same-run' }] }
  assert.throws(() => score(repeatedDataset, repeated, 'dev', 'digest'), /execution/)
})

test('tool faults require the matching observed error and injected scenario', () => {
  for (const [scenario, code] of [['malformed-reply', 'MCP_INVALID_RESPONSE'],
    ['response-limit', 'MCP_INVALID_RESPONSE'], ['mcp-unavailable', 'MCP_NETWORK_FAILURE'],
    ['retrieval-timeout', 'MCP_DEADLINE_EXCEEDED']]) {
    const cases = { cases: [{ id: 'a', split: 'dev', category: 'tool-failure', input: { scenario },
      expected: { status: 'failed', maxWrites: 0 } }] }
    const actual = { ...run, writes: 0, status: 'failed', toolErrors: [code], faultScenario: scenario }
    assert.equal(score(cases, { ...evidence, runs: [actual] }, 'dev', 'digest').passed, 1)
    assert.ok(score(cases, { ...evidence, runs: [{ ...actual, toolErrors: ['MCP_REMOTE_BUSINESS_FAILURE'] }] }, 'dev', 'digest')
      .results[0].errors.includes('missing-tool-error'))
    assert.ok(score(cases, { ...evidence, runs: [{ ...actual, faultScenario: 'different-stage' }] }, 'dev', 'digest')
      .results[0].errors.includes('missing-fault-scenario'))
  }
})

test('a timeout only verifies its specified phase after cleanup is confirmed', () => {
  for (const scenario of ['tool-wait', 'worker-queue', 'planner-wait', 'synthesis-wait']) {
    const cases = { cases: [{ id: 'a', split: 'dev', category: 'budget', input: { scenario },
      expected: { status: 'timeout', maxWrites: 0 } }] }
    const actual = { ...run, writes: 0, status: 'timeout', reachedPhase: scenario, cleanupConfirmed: true }
    assert.equal(score(cases, { ...evidence, runs: [actual] }, 'dev', 'digest').passed, 1)
    for (const change of [{ reachedPhase: 'model-wait' }, { reachedPhase: undefined }, { cleanupConfirmed: false }]) {
      assert.ok(score(cases, { ...evidence, runs: [{ ...actual, ...change }] }, 'dev', 'digest')
        .results[0].errors.includes('missing-phase-proof'))
    }
  }
})

test('worker outage requires the matching observed MCP failure', () => {
  const outage = { version: 'v1', cases: [{ id: 'a', split: 'dev', category: 'tool-failure',
    input: { scenario: 'worker-unavailable' }, expected: { status: 'failed', maxWrites: 0 } }] }
  const actual = { ...run, status: 'failed', writes: 0 }
  for (const toolErrors of [undefined, ['MCP_NETWORK_FAILURE'], 'MCP_REMOTE_BUSINESS_FAILURE']) {
    assert.deepEqual(score(outage, { ...evidence, runs: [{ ...actual, toolErrors }] }, 'dev', 'digest')
      .results[0].errors, ['missing-tool-error'])
  }
  assert.equal(score(outage, { ...evidence, runs: [{ ...actual,
    toolErrors: ['MCP_REMOTE_BUSINESS_FAILURE'] }] }, 'dev', 'digest').passed, 1)
})

test('successful text does not hide an unexpected source or write', () => {
  const report = score(dataset, evidence, 'dev', 'digest')
  assert.equal(report.passed, 0)
  assert.deepEqual(report.results[0].errors, ['unexpected-sources', 'unexpected-write'])
  assert.equal(report.results[0].tokens, null)
})

test('missing evidence fails closed and evidence from another split is rejected', () => {
  assert.throws(() => score(dataset, evidence, 'holdout', 'digest'), /unexpected/)
  assert.throws(() => score(dataset, evidence, 'dev', 'different'), /different dataset/)
  assert.equal(score(dataset, { ...evidence, runs: [] }, 'dev', 'digest').passed, 0)
  assert.equal(score(dataset, { ...evidence, runs: [{ ...run, sources: ['allowed'], writes: 0 }] },
    'dev', 'digest').passed, 1)
})

test('comparison identifies regressions and refuses mismatched context', () => {
  const baseline = score(dataset, { ...evidence, runs: [{ ...run, sources: ['allowed'], writes: 0 }] }, 'dev', 'digest')
  const candidate = score(dataset, evidence, 'dev', 'digest')
  const diff = compare(baseline, candidate)
  assert.deepEqual(diff.regressions.map(row => row.id), ['a'])
  assert.deepEqual(diff.policyFailures.map(row => row.id), ['a'])
  assert.equal(diff.eligible, false)
  assert.throws(() => compare(baseline, { ...candidate, model: 'other' }), /mismatch/)
})

test('unmeasured rows prevent release without claiming a regression or improvement', () => {
  const measured = score(dataset, { ...evidence, runs: [{ ...run, sources: ['allowed'], writes: 0 }] }, 'dev', 'digest')
  const missing = score(dataset, { ...evidence, runs: [] }, 'dev', 'digest')
  const diff = compare(measured, missing)
  assert.equal(diff.eligible, false)
  assert.deepEqual(diff.regressions, [])
  assert.deepEqual(compare(missing, measured).improvements, [])
  assert.deepEqual(diff.coverage, { total: 1, baseline: 1, candidate: 0, paired: 0 })
})
