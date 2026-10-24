export type RunStatus = 'running' | 'completed' | 'error' | 'interrupted' | 'limit' | 'terminated'
export type AgentEndReason = 'ANSWER_READY' | 'STEP_LIMIT' | 'TERMINATED_BY_TOOL' | 'TOOL_RETURNED'
  | 'MODEL_ERROR' | 'TOOL_ERROR' | 'EXECUTION_ERROR' | 'OUTPUT_INTERRUPTED' | 'PERSISTENCE_ERROR'

export interface ToolResult {
  success: boolean
  code: string
  message: string
  data: unknown
  displayTruncated?: boolean
}
export type ToolStatus = 'requested' | 'running' | 'completed' | 'error' | 'interrupted'

export interface AgentStreamEvent {
  type: string
  step: number
  callId?: string | null
  toolName?: string | null
  arguments?: string | null
  content?: string | null
  status?: string | null
  durationMs?: number | null
  timestamp: number
  endReason?: AgentEndReason | null
  conversationId?: string | null
  runId?: string | null
}

export interface ToolInvocation {
  id: string
  name: string
  arguments: string
  status: ToolStatus
  result: string
  durationMs?: number
}

export interface ExecutionStep {
  number: number
  reasoning: string
  status: 'running' | 'completed' | 'error' | 'interrupted'
  tools: ToolInvocation[]
}

export interface AgentFlow {
  status: RunStatus
  phase: 'connecting' | 'thinking' | 'answering' | 'tools' | 'observing'
  steps: ExecutionStep[]
  startedAt: number
  finishedAt?: number
  error?: string
  endReason?: AgentEndReason
  restored?: boolean
}

export interface AgentChatMessage {
  role: 'user' | 'ai'
  content: string
  displayContent?: string
  isTyping?: boolean
  flow?: AgentFlow
  runId?: string
  answerStep?: number
  knowledgePayload?: string
}
