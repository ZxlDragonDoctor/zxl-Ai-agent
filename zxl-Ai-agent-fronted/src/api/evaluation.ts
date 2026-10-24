import { knowledgeRequest } from './knowledge.ts'
export interface EvaluationStatus {
  datasetVersion: string; questionCount: number; splits: { tuning: number; holdout: number }
  snapshotReady: boolean; modelIdentity: string; storage: string; boundary: string
  realAvailable: boolean; snapshots: { demoReady: boolean; realReady: boolean }
}
export interface EvaluationMetrics {
  recall: number | null; mrr: number; ndcg: number | null
  noAnswerCount: number; noAnswerEmptyRate: number | null; forbiddenCount: number
  p50Ms: number; p95Ms: number; errorCount: number; evaluatedCount: number
}
export interface EvaluatedQuery {
  queryId: string; question: string; split: string; ranking: string[]
  score: { recall: number | null; mrr: number; ndcg: number | null; noAnswer: boolean; empty: boolean; forbiddenCount: number } | null
  elapsedMs: number; error: string | null; degradations: string[]; reranker: string
}
export interface EvaluationStrategy { mode: string; split: string; metrics: EvaluationMetrics; queries: EvaluatedQuery[] }
export interface EvaluationReport {
  datasetVersion: string; snapshotId: string; modelIdentity: string; realSemantic: boolean
  queryCount: number; complete: boolean; boundary: string; config: { topK: number; split: string; budgetMs: number }
  embeddingCalls: number; embeddingCacheHits: number; elapsedMs: number; strategies: EvaluationStrategy[]
  rerankerIdentity: string; realReranker: boolean; rerankerCalls: number
  dataset: unknown
}
export const getEvaluationStatus = () => knowledgeRequest<EvaluationStatus>('/evaluation/status')
export const seedEvaluation = (real: boolean) => knowledgeRequest<EvaluationStatus>('/evaluation/seed', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ real }) })
export const runEvaluation = (split: string, topK: number, real: boolean, budgetMs = 60000) => knowledgeRequest<EvaluationReport>('/evaluation/run', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ split, topK, real, budgetMs }) })
