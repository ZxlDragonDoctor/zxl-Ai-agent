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
  storage: string; embeddingMode: string; dimensions: number
  postgresVersion: string; vectorVersion: string; tenantId: string
}
export interface KnowledgeHit extends KnowledgeChunk {
  documentId: string; revisionId: string; distance: number
}
export interface KnowledgeSearch { query: string; embeddingMode: string; hits: KnowledgeHit[] }

export class KnowledgeApiError extends Error {
  code: string
  constructor(code: string, message: string) { super(message); this.code = code }
}

// Same-origin production gateway; the development proxy reaches the isolated lesson worker.
const KNOWLEDGE_BASE = '/api/ai/learning/knowledge'
export async function knowledgeRequest<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response
  try { response = await fetch(`${KNOWLEDGE_BASE}${path}`, options) }
  catch { throw new KnowledgeApiError('NETWORK_ERROR', 'Knowledge service is unavailable') }
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
