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
