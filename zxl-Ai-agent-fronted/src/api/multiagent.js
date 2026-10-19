import { API_BASE as BASE_URL } from './base'

export function orchestrateGoal(goal, context = {}) {
  return fetch(`${BASE_URL}/ai/multiagent/orchestrate`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ goal, context })
  }).then(res => res.json())
}

export function orchestrateGoalStream(goal, context, onEvent, onError) {
  const controller = new AbortController()
  let finished = false
  const connection = { close() { finished = true; controller.abort() } }
  const fail = () => {
    if (finished) return
    connection.close()
    onError('connection_interrupted')
  }
  function dispatch(frame) {
    let type = 'message'
    const lines = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) type = line.slice(6).trim()
      if (line.startsWith('data:')) lines.push(line.slice(5).replace(/^ /, ''))
    }
    if (!lines.length) return
    const raw = lines.join('\n')
    const data = type === 'report' || type === 'error' ? JSON.parse(raw) : raw
    onEvent({ type, data })
    if (type === 'complete' || type === 'error') connection.close()
  }
  void (async () => {
    try {
      const response = await fetch(`${BASE_URL}/ai/multiagent/orchestrate/stream`, {
        method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ goal, context })
      })
      if (!response.ok || !response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
        fail(); return
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
      if (!finished) fail()
    } catch { if (!controller.signal.aborted) fail() }
  })()
  return connection
}

export function getSystemStatus() {
  return fetch(`${BASE_URL}/ai/multiagent/status`).then(res => res.json())
}

export function listAgents() {
  return fetch(`${BASE_URL}/ai/multiagent/agents`).then(res => res.json())
}

export function getRecentEvents(limit = 50) {
  return fetch(`${BASE_URL}/ai/multiagent/events?limit=${limit}`).then(res => res.json())
}

export function runAgentHarness(agentId, task) {
  return fetch(`${BASE_URL}/ai/multiagent/harness/${agentId}?task=${encodeURIComponent(task)}`)
    .then(res => res.json())
}

export function runBenchmark() {
  return fetch(`${BASE_URL}/ai/multiagent/harness/benchmark`).then(res => res.json())
}
