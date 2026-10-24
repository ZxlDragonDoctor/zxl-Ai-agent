import test from 'node:test'
import assert from 'node:assert/strict'
import {
  KnowledgeApiError, deleteKnowledgeDocument, getKnowledgeDocument, importKnowledgeDocument,
  knowledgeRequest, searchKnowledge,
} from '../knowledge.ts'

test('knowledge requests use same-origin isolated gateway and encode document identity', async () => {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return Response.json({ documentId: '企业/a b' })
  }
  try {
    await getKnowledgeDocument('企业/a b')
    await deleteKnowledgeDocument('企业/a b')
    assert.equal(calls[0].url, '/api/ai/learning/knowledge/documents/%E4%BC%81%E4%B8%9A%2Fa%20b')
    assert.equal(calls[1].options.method, 'DELETE')
  } finally { globalThis.fetch = original }
})

test('import sends original markdown as JSON without fetching local paths', async () => {
  const original = globalThis.fetch
  const payload = { documentId: 'vpn', sourceName: '手册.md', content: '# VPN\n\n原文 & <script>' }
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/ai/learning/knowledge/documents')
    assert.equal(options.method, 'POST')
    assert.equal(options.headers['Content-Type'], 'application/json')
    assert.deepEqual(JSON.parse(options.body), payload)
    return Response.json({ outcome: 'UNCHANGED', document: { documentId: 'vpn' } })
  }
  try { assert.equal((await importKnowledgeDocument(payload)).outcome, 'UNCHANGED') }
  finally { globalThis.fetch = original }
})

test('retrieval query and top K are encoded as separate parameters', async () => {
  const original = globalThis.fetch
  globalThis.fetch = async url => {
    const parsed = new URL(url, 'http://example.test')
    assert.equal(parsed.searchParams.get('q'), 'VPN & 登录?')
    assert.equal(parsed.searchParams.get('limit'), '3')
    return Response.json({ hits: [] })
  }
  try { assert.deepEqual((await searchKnowledge('VPN & 登录?', 3)).hits, []) }
  finally { globalThis.fetch = original }
})

test('database conflicts are surfaced without turning into a success value', async () => {
  const original = globalThis.fetch
  globalThis.fetch = async () => Response.json({ code: 'IMPORT_IN_PROGRESS', message: 'Concurrent update' }, { status: 409 })
  try {
    await assert.rejects(() => knowledgeRequest('/documents'), error => error instanceof KnowledgeApiError && error.code === 'IMPORT_IN_PROGRESS')
  } finally { globalThis.fetch = original }
})

test('network and invalid JSON errors stay explicit instead of falling back to mock data', async () => {
  const original = globalThis.fetch
  try {
    globalThis.fetch = async () => { throw new TypeError('offline') }
    await assert.rejects(() => knowledgeRequest('/status'), error => error.code === 'NETWORK_ERROR')
    globalThis.fetch = async () => new Response('<html>proxy error</html>', { status: 502 })
    await assert.rejects(() => knowledgeRequest('/status'), error => error.code === 'REQUEST_FAILED')
    globalThis.fetch = async () => new Response('not JSON', { status: 200 })
    await assert.rejects(() => knowledgeRequest('/status'), error => error.code === 'INVALID_RESPONSE')
  } finally { globalThis.fetch = original }
})
