import { knowledgeRequest } from './knowledge.ts'

export interface RetrievalOptions {
  query: string
  mode: 'vector' | 'keyword' | 'hybrid'
  topK: number
  candidateLimit: number
  rerank: boolean
  rewrite: boolean
  budgetMs: number
}
export interface RetrievalHit {
  chunkId: string; documentId: string; revisionId: string; generation: number
  text: string; startLine: number; endLine: number; startUtf16: number; endUtf16: number
  distance: number | null; vectorRank: number | null; keywordRank: number | null
  fusionScore: number; rerankScore: number | null; channels: string[]
}
export interface RetrievalResult {
  traceId: string; query: string; rewrittenQuery: string | null
  mode: string; embeddingMode: string; reranker: string; elapsedMs: number
  stages: { name: string; status: string; elapsedMs: number; candidateCount: number }[]
  degradations: string[]; shortageReason: string | null; hits: RetrievalHit[]
}
export const retrieveKnowledge = (options: RetrievalOptions) => knowledgeRequest<RetrievalResult>('/retrieval', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(options),
})
