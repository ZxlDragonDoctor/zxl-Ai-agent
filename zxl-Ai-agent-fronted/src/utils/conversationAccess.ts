interface ConversationAccess { readOnly?: boolean; busy?: boolean }
export class ConversationWriteError extends Error {
  readonly code='CONVERSATION_READ_ONLY'
  constructor(){super('CONVERSATION_READ_ONLY')}
}
export function isReadOnlyConversation(conversation?: ConversationAccess | null): boolean { return conversation?.readOnly===true }
export function assertConversationWritable(conversation?: ConversationAccess | null): void { if(isReadOnlyConversation(conversation))throw new ConversationWriteError() }
/** Archived running records are historical data, never a request to resume execution. */
export function conversationBusy(conversation: ConversationAccess): boolean { return !isReadOnlyConversation(conversation)&&conversation.busy===true }
