import { sessionFetch, SessionRequestError, sessionState } from '../utils/sessionRequest.ts'
export interface KnowledgeDocument {
  documentId: string
  sourceName: string
  status: string
  activeRevision: string | null
  version: number
  chunkCount: number
  errorCode: string | null
}
export interface KnowledgeChunk {
  chunkId: string
  ordinal: number
  text: string
  startUtf16: number
  endUtf16: number
  startLine: number
  endLine: number
  titlePath?: string
}
export interface KnowledgeDetail { document: KnowledgeDocument; rawText: string; chunks: KnowledgeChunk[] }
export interface KnowledgeStatus {
  storage: string; embeddingMode?: string; dimensions?: number; available?: boolean; scope?: string
  postgresVersion?: string; vectorVersion?: string; tenantId?: string
}
export interface KnowledgeHit extends KnowledgeChunk {
  documentId: string; revisionId: string; distance: number
}
export interface KnowledgeSearch { query: string; embeddingMode: string; hits: KnowledgeHit[] }

export class KnowledgeApiError extends Error {
  code: string
  constructor(code: string, message: string) { super(message); this.code = code }
}

// Same-origin main gateway authenticates each call before contacting the isolated worker.
const KNOWLEDGE_BASE = '/api/ai/learning/knowledge'
/** Formal identities come only from the authenticated gateway, never classroom selectors. */
export function knowledgeBusinessRequest(path: string, options?: RequestInit): { path: string; options?: RequestInit } {
  if (sessionState().status?.mode !== 'required') return {path, options}
  const url = new URL(path, 'http://knowledge.invalid')
  for (const key of ['profile','tenantId','userId']) url.searchParams.delete(key)
  if (typeof options?.body !== 'string') return {path:url.pathname + url.search, options}
  const body = JSON.parse(options.body)
  if (body && typeof body === 'object' && !Array.isArray(body)) {
    for (const key of ['profile','tenantId','userId']) delete body[key]
  }
  return {path:url.pathname + url.search, options:{...options, body:JSON.stringify(body)}}
}
export async function knowledgeRequest<T>(path: string, options?: RequestInit): Promise<T> {
  const business = knowledgeBusinessRequest(path, options)
  let response: Response
  try { response = await sessionFetch(`${KNOWLEDGE_BASE}${business.path}`, business.options) }
  catch (error) { if (error instanceof SessionRequestError) throw error; throw new KnowledgeApiError('NETWORK_ERROR', 'Knowledge service is unavailable') }
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new KnowledgeApiError(body?.code || 'REQUEST_FAILED', body?.message || `HTTP ${response.status}`)
  if (body === null) throw new KnowledgeApiError('INVALID_RESPONSE', 'The service did not return JSON')
  return body as T
}
export const getKnowledgeStatus = () => knowledgeRequest<KnowledgeStatus>('/status')
export const listKnowledgeDocuments = () => knowledgeRequest<KnowledgeDocument[]>('/documents')
export const getKnowledgeDocument = (id: string) => knowledgeRequest<KnowledgeDetail>(`/documents/${encodeURIComponent(id)}`)
export const importKnowledgeDocument = (input: { documentId: string; sourceName: string; content: string }) => knowledgeRequest<{ outcome: string; document: KnowledgeDocument }>('/documents', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input),
})
export const deleteKnowledgeDocument = (id: string) => knowledgeRequest<KnowledgeDocument>(`/documents/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const searchKnowledge = (query: string, limit: number) => knowledgeRequest<KnowledgeSearch>(`/search?${new URLSearchParams({ q: query, limit: String(limit) })}`)
