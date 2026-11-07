import test from 'node:test'
import assert from 'node:assert/strict'
import { storedPreferenceSources } from '../../utils/memorySource.ts'

test('formal proposals use only stored user messages with their exact ID and text', () => {
  const records = [
    { messageId: 'assistant-id', role: 'assistant', content: '我偏好中文回复' },
    { messageId: 'surrounding-text', role: 'user', content: ' 我偏好中文回复' },
    { messageId: 'format', role: 'user', content: '我偏好列表回复' },
    { messageId: 'older', role: 'user', content: '我偏好中文回复' },
    { messageId: 'newer', role: 'user', content: 'I prefer English replies' },
  ]
  assert.deepEqual(storedPreferenceSources(records), [
    { messageId: 'newer', userText: 'I prefer English replies' },
    { messageId: 'older', userText: '我偏好中文回复' },
    { messageId: 'format', userText: '我偏好列表回复' },
  ])
  assert.deepEqual(storedPreferenceSources(records, { messageId: 'newer', factKey: 'reply_language' }), [
    { messageId: 'older', userText: '我偏好中文回复' },
  ])
})
