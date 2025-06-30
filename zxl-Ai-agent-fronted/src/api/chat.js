const BASE_URL = 'http://localhost:8123/api'

export function chatWithLoveAppSse(message, chatId, onMessage) {
  const url = `${BASE_URL}/ai/love_app/chat/sse?message=${encodeURIComponent(message)}&chatId=${chatId}`
  const eventSource = new EventSource(url)

  eventSource.onmessage = (event) => {
    onMessage(event.data)
  }

  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    eventSource.close()
  }

  return eventSource
}

export function chatWithManusSse(message, onMessage) {
  const url = `${BASE_URL}/ai/manus/chat?message=${encodeURIComponent(message)}`
  const eventSource = new EventSource(url)

  eventSource.onmessage = (event) => {
    onMessage(event.data)
  }

  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    eventSource.close()
  }

  return eventSource
} 