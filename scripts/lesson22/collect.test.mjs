import assert from 'node:assert/strict'
import test from 'node:test'
import { collect } from './collect.mjs'

const dataset = { cases: [{ id: 'a', split: 'dev' }, { id: 'b', split: 'dev' }, { id: 'c', split: 'holdout' }] }
const info = { datasetSha256: 'pinned', model: 'model', knowledgeVersion: 'knowledge', variant: 'baseline' }
const evidence = (id, runId = id) => ({ ...info, runs: [{ id, runId }] })

test('collects one split in dataset order and preserves missing rows for the scorer', () => {
  assert.deepEqual(collect(dataset, 'dev', 'pinned', [evidence('b'), evidence('a')]).runs.map(r => r.id), ['a', 'b'])
  assert.equal(collect(dataset, 'dev', 'pinned', [evidence('b')]).runs.length, 1)
})

test('rejects another split, mixed evidence and reusing a run', () => {
  assert.throws(() => collect(dataset, 'dev', 'pinned', [evidence('c')]), /selected split/)
  assert.throws(() => collect(dataset, 'dev', 'pinned', [evidence('a'), { ...evidence('b'), variant: 'candidate' }]), /variant/)
  assert.throws(() => collect(dataset, 'dev', 'pinned', [evidence('a'), evidence('b', 'a')]), /Duplicate run/)
})
