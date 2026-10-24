import test from 'node:test'
import assert from 'node:assert/strict'
import { readKnowledgeAnswer, askKnowledge, proposeFact, actOnFact, recallFacts, factIsLive } from '../answer.ts'

test('knowledge event is parsed only with bounded supported schema and IDs from its snapshot', () => {
  const input = { traceId: 't', snapshotId: 's', status: 'ANSWERED', answer: '检查网络', claims: [{ text: '检查网络', evidenceIds: ['E1'], support: 'SUPPORTED', reason: '原文' }], evidence: [{ id: 'E1', snapshotId: 's', documentId: 'vpn', revisionId: 'r', chunkId: 'c', generation: 1, text: '检查网络', startUtf16: 0, endUtf16: 4 }] }
  assert.equal(readKnowledgeAnswer(JSON.stringify(input)).status, 'ANSWERED')
  assert.equal(readKnowledgeAnswer('{oops'), null)
  assert.equal(readKnowledgeAnswer(JSON.stringify({ ...input, status: 'MADE_UP' })), null)
  assert.equal(readKnowledgeAnswer(JSON.stringify({ ...input, evidence: [{ ...input.evidence[0], snapshotId: 'other' }] })), null)
  assert.equal(readKnowledgeAnswer(JSON.stringify({ ...input, claims: [{ ...input.claims[0], support: 'INSUFFICIENT' }] })), null)
  assert.equal(readKnowledgeAnswer(JSON.stringify({ ...input, claims: [{ ...input.claims[0], evidenceIds: [] }] })), null)
  assert.equal(readKnowledgeAnswer(JSON.stringify({ ...input, claims: [{ ...input.claims[0], evidenceIds: ['E1', 'E1'] }] })), null)
  assert.equal(readKnowledgeAnswer(JSON.stringify({ ...input, evidence: [{ ...input.evidence[0], endUtf16: 999 }] })), null)
  assert.equal(readKnowledgeAnswer('x'.repeat(300000)), null)
})
test('answer and memory use isolated gateway, profile allowlist selector, explicit confirmation versions', async () => {
  const original = globalThis.fetch; const requests = []
  globalThis.fetch = async (url, options) => { requests.push({ url, options }); return { ok: true, json: async () => ({ traceId: 't', snapshotId: 's', status: 'INSUFFICIENT_EVIDENCE', answer: '没有资料', claims: [], evidence: [] }) } }
  try {
    await askKnowledge({ question: 'VPN809', conversationId: 'c', mode: 'demo', profile: 'alice', budgetMs: 10000 })
    await proposeFact({ profile: 'alice', sessionId: 'c', messageId: 'm', userText: '我偏好中文回复', ttlSeconds: 3600 })
    await actOnFact('id/1', 'confirm', { profile: 'alice', version: 1 })
    await recallFacts('bob')
    assert.equal(requests[0].url, '/api/ai/learning/knowledge/answers')
    assert.equal(JSON.parse(requests[1].options.body).userText, '我偏好中文回复')
    assert.equal(requests[2].url, '/api/ai/learning/knowledge/memories/id%2F1/confirm')
    assert.equal(JSON.parse(requests[2].options.body).version, 1)
    assert.match(requests[3].url, /profile=bob/)
    for (const r of requests) assert.equal('tenantId' in JSON.parse(r.options?.body || '{}'), false)
  } finally { globalThis.fetch = original }
})

test('HTTP 503 structured answer remains explicit SERVICE_FAILURE; malformed success is rejected', async () => {
  const original = globalThis.fetch
  const input = { question: '809', conversationId: 'c', mode: 'real', profile: 'alice', budgetMs: 30000 }
  try {
    globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ traceId: 't', snapshotId: 's', status: 'SERVICE_FAILURE', answer: '服务未完成', claims: [], evidence: [] }) })
    assert.equal((await askKnowledge(input)).status, 'SERVICE_FAILURE')
    globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => ({}) })
    await assert.rejects(askKnowledge(input), /Invalid answer response/)
  } finally { globalThis.fetch = original }
})

test('fact expiry uses explicit ISO instant and excludes the exact deadline or deleted records', () => {
  const time = Date.parse('2026-10-25T00:00:00Z')
  assert.equal(factIsLive({ expiresAt: '2026-10-25T00:00:01Z', deletedAt: null }, time), true)
  assert.equal(factIsLive({ expiresAt: '2026-10-25T00:00:00Z', deletedAt: null }, time), false)
  assert.equal(factIsLive({ expiresAt: '2026-10-25T00:00:01Z', deletedAt: '2026-10-24T23:59:59Z' }, time), false)
  assert.equal(factIsLive({ expiresAt: 1792886401, deletedAt: null }, time), false)
})
