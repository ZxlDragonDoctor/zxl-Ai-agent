import type { AgentStreamEvent } from '../types/agent'

export function chatWithLoveAppSse(
  message: string, chatId: string, onMessage: (data: string) => void
): EventSource

export function chatWithManusSse(
  message: string,
  onMessage: (data: string) => void,
  onError?: (reason: string) => void,
  onEvent?: (event: AgentStreamEvent) => void,
  conversationId?: string,
  skillSelection?: { skillId: string; skillVersion?: string }
): { close(): void }

export { chatWithManusSse as chatWithAgentSse }
