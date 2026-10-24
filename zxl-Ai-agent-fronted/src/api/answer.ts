import { KnowledgeApiError, knowledgeRequest } from './knowledge.ts'

export type DemoProfile = 'alice' | 'bob'
export interface Evidence {
  id: string; snapshotId: string; documentId: string; revisionId: string; chunkId: string; generation: number
  sourceName?: string; startUtf16: number; endUtf16: number; startLine?: number; endLine?: number
  text: string; sha256?: string; truncated?: boolean
}
export interface KnowledgeAnswer {
  traceId: string; snapshotId: string; status: string; answer: string
  claims: { text: string; evidenceIds: string[]; support: string; reason: string }[]
  evidence: Evidence[]; reason?: string; missingInformation?: string | string[]
  generatorIdentity?: string; judgeIdentity?: string; elapsedMs?: number; removedClaims?: number
  memory?: unknown
}
const statuses = new Set(['ANSWERED', 'PARTIAL', 'NEEDS_CLARIFICATION', 'INSUFFICIENT_EVIDENCE', 'SERVICE_FAILURE'])
/** Public stored events are data. Invalid/mismatched snapshots never render as trusted citations. */
export function readKnowledgeAnswer(content: string): KnowledgeAnswer | null {
  if (content.length > 262144) return null
  try {
    const value = JSON.parse(content)
    if (!value || !statuses.has(value.status) || typeof value.traceId !== 'string' || typeof value.snapshotId !== 'string'
      || typeof value.answer !== 'string' || !Array.isArray(value.claims) || !Array.isArray(value.evidence)
      || value.evidence.length > 20 || value.claims.length > 8) return null
    if (['ANSWERED', 'PARTIAL'].includes(value.status) !== (value.claims.length > 0)) return null
    const ids = new Set<string>()
    for (const e of value.evidence) {
      if (!e || typeof e.id !== 'string' || !/^E[1-9]\d*$/.test(e.id) || ids.has(e.id) || e.snapshotId !== value.snapshotId
        || typeof e.documentId !== 'string' || typeof e.revisionId !== 'string' || typeof e.chunkId !== 'string'
        || typeof e.text !== 'string' || !Number.isInteger(e.generation) || e.generation < 1 || !Number.isInteger(e.startUtf16) || !Number.isInteger(e.endUtf16)
        || e.startUtf16 < 0 || e.endUtf16 <= e.startUtf16 || e.endUtf16 - e.startUtf16 !== e.text.length) return null
      ids.add(e.id)
    }
    for (const c of value.claims) if (!c || typeof c.text !== 'string' || !c.text.trim() || c.support !== 'SUPPORTED' || !Array.isArray(c.evidenceIds)
      || c.evidenceIds.length < 1 || c.evidenceIds.length > 3 || new Set(c.evidenceIds).size !== c.evidenceIds.length
      || c.evidenceIds.some((id: unknown) => typeof id !== 'string' || !ids.has(id))) return null
    return value as KnowledgeAnswer
  } catch { return null }
}
const json = (body: unknown, method = 'POST') => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const askKnowledge = async (body: { question: string; conversationId: string; mode: 'demo' | 'real'; budgetMs: number; profile: DemoProfile }): Promise<KnowledgeAnswer> => {
  let response: Response
  try { response = await fetch('/api/ai/learning/knowledge/answers', json(body)) }
  catch { throw new KnowledgeApiError('NETWORK_ERROR', 'Knowledge service is unavailable') }
  const raw = await response.json().catch(() => null)
  const result = raw ? readKnowledgeAnswer(JSON.stringify(raw)) : null
  // A business failure is rendered as a failure card, never as a successful empty answer.
  if (result && (response.ok || response.status === 503 && result.status === 'SERVICE_FAILURE')) return result
  throw new KnowledgeApiError(raw?.code || 'INVALID_ANSWER_RESPONSE', raw?.message || 'Invalid answer response')
}
export interface Fact {
  id: string; factKey: string; value: string; sourceText: string; sessionId: string; messageId: string
  state: string; version: number; createdAt: string; expiresAt: string; deletedAt?: string | null
}
export function factIsLive(fact: { expiresAt: unknown; deletedAt?: unknown }, now: number): boolean {
  return !fact.deletedAt && typeof fact.expiresAt === 'string' && /T.*(?:Z|[+-]\d\d:\d\d)$/.test(fact.expiresAt) && Date.parse(fact.expiresAt) > now
}
export const listFacts = async (profile: DemoProfile) => {
  const facts = await knowledgeRequest<Fact[]>(`/memories?${new URLSearchParams({ profile })}`)
  if (!Array.isArray(facts) || facts.some(f => typeof f.expiresAt !== 'string' || !Number.isFinite(Date.parse(f.expiresAt)))) throw new Error('Invalid memory timestamp contract; refresh after the worker is updated')
  return facts
}
export const proposeFact = (body: { profile: DemoProfile; sessionId: string; messageId: string; userText: string; ttlSeconds: number }) => knowledgeRequest<Fact>('/memories/proposals', json(body))
export const actOnFact = (id: string, action: 'confirm' | 'reject', body: { profile: DemoProfile; version: number }) => knowledgeRequest<Fact>(`/memories/${encodeURIComponent(id)}/${action}`, json(body))
export const correctFact = (id: string, body: { profile: DemoProfile; version: number; userText: string; sessionId: string; messageId: string; ttlSeconds: number }) => knowledgeRequest<Fact>(`/memories/${encodeURIComponent(id)}`, json(body, 'PATCH'))
export const deleteFact = (id: string, profile: DemoProfile, version: number) => knowledgeRequest<Fact>(`/memories/${encodeURIComponent(id)}?${new URLSearchParams({ profile, version: String(version) })}`, { method: 'DELETE' })
export const recallFacts = (profile: DemoProfile) => knowledgeRequest<{ facts: Fact[]; usedChars: number; droppedCount: number }>(`/memories/recall?${new URLSearchParams({ profile, maxChars: '800' })}`)
