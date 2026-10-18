import type { AgentEndReason, AgentStreamEvent, RunStatus } from './agent'

export interface AgentDefinition {
  id: string
  name: string
  nameEn: string
  description: string
  descriptionEn: string
}

export interface ConversationSummary {
  id: string
  title: string
  updatedAt: string
  messageCount: number
  busy: boolean
  agentType: string
}

export interface StoredMessage {
  messageId: string
  runId: string
  role: 'user' | 'assistant' | 'tool'
  content: string
  toolCalls: { id: string; name: string; arguments: string }[]
  toolCallId: string | null
  toolName: string | null
  createdAt: string
}

export interface StoredRun {
  runId: string
  startedAt: string
  finishedAt: string | null
  endReason: AgentEndReason | null
  status: RunStatus
  answer: string
  error: string | null
  events: AgentStreamEvent[]
}

export interface ConversationDetail extends ConversationSummary {
  version: number
  createdAt: string
  messages: StoredMessage[]
  runs: StoredRun[]
}
