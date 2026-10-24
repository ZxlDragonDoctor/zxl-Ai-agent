import test from 'node:test'
import assert from 'node:assert/strict'
import { embeddingPresentation } from '../../utils/knowledgePresentation.ts'

test('unconfirmed connection never claims demo or live embedding is active', () => {
  const view = embeddingPresentation(null)
  assert.equal(view.kind, 'unconfirmed')
  assert.match(view.title.zh, /待确认/)
  assert.doesNotMatch(view.title.en, /demo|live/i)
})

test('deterministic identity explicitly states non-semantic demo scope', () => {
  for (const identity of ['deterministic-demo-v1', 'deterministic-demo-v1 (not semantic)']) {
    const view = embeddingPresentation(identity)
    assert.equal(view.kind, 'demo')
    assert.match(view.title.zh, /非语义/)
    assert.match(view.warning.en, /do not demonstrate.*semantic/i)
  }
})

test('HTTP identity is live configured mode with explicit quality boundary', () => {
  const view = embeddingPresentation('http:text-embedding-v3:deployment-1:1024:abcdef123456')
  assert.equal(view.kind, 'http')
  assert.match(view.title.zh, /真实服务/)
  assert.match(view.warning.zh, /不代表.*质量/)
  assert.doesNotMatch(view.title.zh, /演示|非语义/)
})

test('unknown provider identity is not silently interpreted as semantic or demo', () => {
  const view = embeddingPresentation('future-provider-v1')
  assert.equal(view.kind, 'unconfirmed')
  assert.match(view.warning.zh, /核对/)
})
