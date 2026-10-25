import test from 'node:test'
import assert from 'node:assert/strict'
import { readMcpResult, validateMcpSource, mcpRequest, mcpDiagnosticCode } from '../mcp.ts'

const hit = { chunkId: 'c', documentId: 'd', revisionId: 'r', generation: 2, text: 'VPN401', startUtf16: 0, endUtf16: 6, startLine: 1, endLine: 1 }
test('protocol failure preserves safe business diagnosis without trusting arbitrary remote text', () => {
  assert.equal(mcpDiagnosticCode({ code: 'MCP_TOOL_ERROR', data: { remoteCode: 'MCP_REMOTE_BUSINESS_FAILURE' } }), 'MCP_REMOTE_BUSINESS_FAILURE')
  assert.equal(mcpDiagnosticCode({ code: 'MCP_TOOL_ERROR', data: { remoteCode: 'secret-do-not-echo' } }), 'MCP_TOOL_ERROR')
})
test('MCP results distinguish success-empty, failure and malformed or oversized evidence', () => {
  const result = { success: true, code: 'OK', message: 'ok', data: { hits: [hit] } }
  assert.equal(readMcpResult(JSON.stringify(result)).data.hits.length, 1)
  assert.equal(readMcpResult(JSON.stringify({ ...result, data: { hits: [] } })).success, true)
  assert.equal(readMcpResult(JSON.stringify({ success: false, code: 'REMOTE_UNAVAILABLE', message: 'down', data: null })).success, false)
  assert.equal(readMcpResult('{}'), null)
  assert.equal(readMcpResult('x'.repeat(262145)), null)
  assert.equal(readMcpResult(JSON.stringify({ ...result, data: { hits: [{ ...hit, endUtf16: 88 }] } })), null)
  assert.equal(readMcpResult(JSON.stringify({ ...result, data: { hits: [{ ...hit, generation: 0 }] } })), null)
  assert.equal(readMcpResult(JSON.stringify({ ...result, data: { hits: Array(21).fill(hit) } })), null)
})
test('MCP source is rechecked against live revision generation exact chunk and raw span', () => {
  const detail = { document: { documentId: 'd', status: 'ACTIVE', activeRevision: 'r', version: 2 }, rawText: 'VPN401', chunks: [hit] }
  assert.equal(validateMcpSource(hit, detail), true)
  assert.equal(validateMcpSource(hit, { ...detail, document: { ...detail.document, version: 3 } }), false)
  assert.equal(validateMcpSource(hit, { ...detail, rawText: 'VPN402' }), false)
  assert.equal(validateMcpSource(hit, { ...detail, chunks: [{ ...hit, startUtf16: 1 }] }), false)
  assert.equal(validateMcpSource(hit, { ...detail, chunks: [{ ...hit, startLine: 2 }] }), false)
  assert.equal(validateMcpSource(hit, { ...detail, chunks: [{ ...hit, endLine: 2 }] }), false)
  assert.equal(validateMcpSource(hit, { ...detail, document: { ...detail.document, status: 'DELETED' } }), false)
  assert.equal(validateMcpSource(hit, { ...detail, document: { ...detail.document, documentId: 'another' } }), false)
})
test('MCP gateway retains HTTP business failures and sends no caller identity', async () => {
  const original = globalThis.fetch; let url, options
  globalThis.fetch = async (u, o) => { url = u; options = o; return { ok: false, status: 503, json: async () => ({ success: false, code: 'REMOTE_UNAVAILABLE', message: 'down', data: null }) } }
  try {
    const value = await mcpRequest('/lookup', { query: 'VPN401', topK: 3, mode: 'hybrid', budgetMs: 5000 })
    assert.equal(value.code, 'REMOTE_UNAVAILABLE')
    assert.equal(url, '/api/ai/learning/mcp/lookup')
    assert.equal('tenantId' in JSON.parse(options.body), false)
  } finally { globalThis.fetch = original }
})
test('MCP gateway distinguishes network cancellation malformed response and HTTP protocol failures', async () => {
  const original = globalThis.fetch
  try {
    globalThis.fetch = async () => { throw new Error('socket') }
    await assert.rejects(mcpRequest('/status'), /MCP_NETWORK_UNAVAILABLE/)
    const controller = new AbortController(); controller.abort()
    await assert.rejects(mcpRequest('/lookup', {}, controller.signal), /CANCELLED/)
    globalThis.fetch = async () => ({ ok: true, status: 200, json: async () => null })
    await assert.rejects(mcpRequest('/status'), /MCP_INVALID_RESPONSE/)
    globalThis.fetch = async () => ({ ok: false, status: 502, json: async () => ({ unexpected: true }) })
    await assert.rejects(mcpRequest('/status'), /MCP_HTTP_502/)
  } finally { globalThis.fetch = original }
})
