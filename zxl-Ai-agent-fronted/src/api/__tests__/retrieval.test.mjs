import test from 'node:test'
import assert from 'node:assert/strict'
import { retrieveKnowledge } from '../retrieval.ts'

test('retrieval posts bounded options without client tenant or raw filter expressions', async () => {
  const previous = globalThis.fetch
  let request
  globalThis.fetch = async (url, options) => {
    request = { url, options }
    return { ok: true, json: async () => ({ traceId: 'trace-1', hits: [] }) }
  }
  try {
    const result = await retrieveKnowledge({ query: 'VPN报错809', mode: 'hybrid', topK: 3, candidateLimit: 20, rerank: true, rewrite: false, budgetMs: 5000 })
    assert.equal(request.url, '/api/ai/learning/knowledge/retrieval')
    assert.equal(request.options.method, 'POST')
    assert.equal(JSON.parse(request.options.body).query, 'VPN报错809')
    assert.equal('tenant' in JSON.parse(request.options.body), false)
    assert.equal(result.traceId, 'trace-1')
  } finally { globalThis.fetch = previous }
})

test('retrieval failure remains an explicit failure, not empty successful hits', async () => {
  const previous = globalThis.fetch
  globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ code: 'RETRIEVAL_FAILED', message: 'All channels failed' }) })
  try { await assert.rejects(retrieveKnowledge({ query: '809', mode: 'hybrid', topK: 3, candidateLimit: 20, rerank: false, rewrite: false, budgetMs: 5000 }), error => error.code === 'RETRIEVAL_FAILED') }
  finally { globalThis.fetch = previous }
})
