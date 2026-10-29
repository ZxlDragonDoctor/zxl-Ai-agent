import test from 'node:test'
import assert from 'node:assert/strict'
import { embeddingPresentation, knowledgeStoragePresentation } from '../../utils/knowledgePresentation.ts'

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

test('redacted formal status never invents database versions or a demo tenant', () => {
  const view = knowledgeStoragePresentation({storage:'PostgreSQL + pgvector',scope:'authenticated',available:true}, 'workspace-a')
  assert.equal(view.workspace, 'workspace-a')
  assert.doesNotMatch(JSON.stringify(view), /undefined|demo-enterprise/)
  assert.match(view.databaseDetail.zh, /连接/)
  assert.match(view.scopeDetail.zh, /当前租户/)
})

test('formal scope ignores an untrusted tenant label from the storage response', () => {
  const view = knowledgeStoragePresentation({scope:'authenticated',tenantId:'foreign-workspace'})
  assert.notEqual(view.workspace, 'foreign-workspace')
  assert.doesNotMatch(JSON.stringify(view), /foreign-workspace/)
})

test('legacy status retains its explicit demo identity and known model dimensions', () => {
  const view = knowledgeStoragePresentation({tenantId:'demo-enterprise',postgresVersion:'16',vectorVersion:'0.8.6',embeddingMode:'deterministic-demo-v1',dimensions:8})
  assert.equal(view.workspace, 'demo-enterprise')
  assert.match(view.embeddingDetail, /8D/)
  assert.match(view.databaseDetail.en, /PostgreSQL 16/)
  assert.match(view.scopeDetail.zh, /课堂/)
})

test('missing connection metadata is explicitly unconfirmed', () => {
  const view = knowledgeStoragePresentation(null)
  assert.match(view.databaseDetail.zh, /尚未确认/)
  assert.equal(view.workspace, '—')
  assert.doesNotMatch(JSON.stringify(view), /undefined|demo-enterprise/)
})
