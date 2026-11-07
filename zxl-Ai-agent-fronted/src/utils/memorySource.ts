import type { StoredMessage } from '../types/conversation'

const preferences = new Map([
  ['我偏好中文回复', 'reply_language'], ['我偏好英文回复', 'reply_language'],
  ['我偏好简洁回复', 'reply_detail'], ['我偏好详细回复', 'reply_detail'],
  ['我偏好列表回复', 'reply_format'], ['我偏好段落回复', 'reply_format'],
  ['I prefer Chinese replies', 'reply_language'], ['I prefer English replies', 'reply_language'],
  ['I prefer concise replies', 'reply_detail'], ['I prefer detailed replies', 'reply_detail'],
  ['I prefer bullet lists', 'reply_format'], ['I prefer paragraphs', 'reply_format'],
])

/** This is a picker filter. The worker still verifies ownership, role and the exact message text. */
export function storedPreferenceSources(messages: readonly StoredMessage[], correction?: { messageId: string; factKey: string }) {
  return messages.filter(message => message.role === 'user' && (correction
    ? message.messageId !== correction.messageId && preferences.get(message.content) === correction.factKey
    : preferences.has(message.content)))
    .map(message => ({ messageId: message.messageId, userText: message.content })).reverse()
}
