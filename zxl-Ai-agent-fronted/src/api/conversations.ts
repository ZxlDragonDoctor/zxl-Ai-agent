import { API_BASE } from './base'
import type { AgentDefinition, ConversationDetail, ConversationSummary } from '../types/conversation'

export class ConversationApiError extends Error {
  constructor(public code: string) { super(code) }
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response
  try { response = await fetch(`${API_BASE}${path}`, options) }
  catch { throw new ConversationApiError('NETWORK_ERROR') }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ConversationApiError(body?.code || 'REQUEST_FAILED')
  }
  if (response.status === 204) return undefined as T
  try { return await response.json() as T }
  catch { throw new ConversationApiError('INVALID_RESPONSE') }
}

export const listAgents = () => request<AgentDefinition[]>('/agent-types')
export const listConversations = () => request<ConversationSummary[]>('/conversations')
export const getConversation = (id: string) => request<ConversationDetail>(`/conversations/${encodeURIComponent(id)}`)
export const createConversation = (agentType: string, title?: string) => request<ConversationDetail>('/conversations', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title, agentType }),
})
export const deleteConversation = (id: string) => request<void>(`/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' })
