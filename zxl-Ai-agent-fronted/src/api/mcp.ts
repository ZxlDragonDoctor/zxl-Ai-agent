import { sessionFetch, SessionRequestError } from '../utils/sessionRequest.ts'
import type { KnowledgeDetail } from './knowledge.ts'

export interface McpHit {
  chunkId: string; documentId: string; revisionId: string; generation: number; text: string
  startUtf16: number; endUtf16: number; startLine: number; endLine: number
}
export interface McpResult { success: boolean; code: string; message: string; data: { hits: McpHit[]; remoteCode?: string; traceId?: string; elapsedMs?: number; stages?: unknown; degradations?: unknown } | null }
export interface McpStatus { state: string; serverId: string; endpoint: string; protocolVersion: string; lastError?: string; toolCount: number; active: number; queued: number; tools: { serverId: string; originalName: string; exposedName: string; schemaVersion: string; schemaSha256: string; definition: unknown }[] }
export function mcpDiagnosticCode(result: { code: string; data?: { remoteCode?: unknown } | null }): string {
  const code = result.data?.remoteCode
  return typeof code === 'string' && ['MCP_INVALID_ARGUMENT', 'MCP_NOT_AUTHORIZED', 'MCP_DEADLINE_EXCEEDED', 'MCP_CANCELLED', 'MCP_CAPACITY', 'MCP_REMOTE_BUSINESS_FAILURE', 'MCP_INVALID_RESPONSE'].includes(code) ? code : result.code
}

export function readMcpResult(content: string): McpResult | null {
  if (content.length > 262144) return null
  try {
    const r = JSON.parse(content)
    if (!r || typeof r.success !== 'boolean' || typeof r.code !== 'string' || typeof r.message !== 'string' || !('data' in r)) return null
    if (r.success) {
      if (!r.data || !Array.isArray(r.data.hits) || r.data.hits.length > 20) return null
      for (const h of r.data.hits) if (!h || ['chunkId', 'documentId', 'revisionId', 'text'].some(k => typeof h[k] !== 'string')
        || !Number.isInteger(h.generation) || h.generation < 1 || !Number.isInteger(h.startUtf16) || h.startUtf16 < 0
        || !Number.isInteger(h.endUtf16) || h.endUtf16 <= h.startUtf16 || h.endUtf16 - h.startUtf16 !== h.text.length
        || !Number.isInteger(h.startLine) || !Number.isInteger(h.endLine) || h.startLine < 1 || h.endLine < h.startLine) return null
    } else if (r.data !== null && typeof r.data !== 'object') return null
    return r as McpResult
  } catch { return null }
}
export function validateMcpSource(hit: McpHit, detail: KnowledgeDetail): boolean {
  return detail.document.documentId === hit.documentId && detail.document.status === 'ACTIVE' && detail.document.activeRevision === hit.revisionId && detail.document.version === hit.generation
    && detail.rawText.slice(hit.startUtf16, hit.endUtf16) === hit.text
    && detail.chunks.some(c => c.chunkId === hit.chunkId && c.text === hit.text && c.startUtf16 === hit.startUtf16 && c.endUtf16 === hit.endUtf16
      && c.startLine === hit.startLine && c.endLine === hit.endLine)
}
export async function mcpRequest(path: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
  let response: Response
  try { response = await sessionFetch(`/api/ai/learning/mcp${path}`, { method: body === undefined ? 'GET' : 'POST', ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }), signal }) }
  catch (cause) { if (cause instanceof SessionRequestError) throw cause; if (signal?.aborted) throw new Error('CANCELLED'); throw new Error('MCP_NETWORK_UNAVAILABLE') }
  const value = await response.json().catch(() => null)
  if (!response.ok && !readMcpResult(JSON.stringify(value))) throw new Error(value?.code || `MCP_HTTP_${response.status}`)
  if (value === null) throw new Error('MCP_INVALID_RESPONSE')
  return value
}
