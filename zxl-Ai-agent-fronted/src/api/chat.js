import { API_BASE as BASE_URL } from './base'

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

export function chatWithManusSse(message, onMessage, onError, onEvent, conversationId) {
  const controller = new AbortController()
  let finished = false
  const connection = { close() { finished = true; controller.abort() } }
  const fail = reason => {
    if (finished) return
    connection.close()
    onError?.(reason)
  }

  function dispatch(frame) {
    let name = 'message'
    const data = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (!data.length) return
    if (name !== 'agent') { onMessage(data.join('\n')); return }
    let payload
    try { payload = JSON.parse(data.join('\n')) }
    catch { fail('invalid_event'); return }
    if (!payload || typeof payload.type !== 'string') { fail('invalid_event'); return }
    const terminal = payload.type === 'RUN_COMPLETED' || payload.type === 'RUN_FAILED'
    if (payload.type === 'RUN_FAILED' && !onEvent) {
      if (onError) onError(payload.content || 'run_failed')
      else onMessage(`执行错误: ${payload.content || '执行失败'}`)
    }
    onEvent?.(payload)
    if (terminal) connection.close()
  }

  void (async () => {
    try {
      // POST avoids putting chat contents into a URL and exposes structured HTTP errors such as busy.
      const response = await fetch(`${BASE_URL}/ai/agent/chat`, {
        method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ message, conversationId }),
      })
      if (!response.ok) {
        const body = await response.json().catch(() => null)
        fail(body?.code || 'REQUEST_FAILED')
        return
      }
      if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
        fail('invalid_event'); return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      try {
        while (!finished) {
          const { value, done } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let boundary
          while (!finished && (boundary = /\r?\n\r?\n/.exec(buffer))) {
            const frame = buffer.slice(0, boundary.index)
            buffer = buffer.slice(boundary.index + boundary[0].length)
            dispatch(frame)
          }
        }
      } finally { reader.releaseLock() }
      if (!finished) fail('connection_interrupted')
    } catch {
      if (!controller.signal.aborted) fail('connection_interrupted')
    }
  })()
  return connection
}

export const chatWithAgentSse = chatWithManusSse
