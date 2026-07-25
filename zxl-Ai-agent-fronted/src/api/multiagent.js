import { chatWithLoveAppSse, chatWithManusSse } from '../api/chat'

const BASE_URL = process.env.NODE_ENV === 'production'
  ? '/api'
  : 'http://localhost:8123/api'

export function orchestrateGoal(goal) {
  return fetch(`${BASE_URL}/ai/multiagent/orchestrate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ goal })
  }).then(res => res.json())
}

export function orchestrateGoalStream(goal, onEvent, onError) {
  const url = `${BASE_URL}/ai/multiagent/orchestrate/stream?goal=${encodeURIComponent(goal)}`
  const eventSource = new EventSource(url)

  eventSource.addEventListener('planning', (event) => {
    onEvent({ type: 'planning', data: event.data })
  })

  eventSource.addEventListener('complete', (event) => {
    onEvent({ type: 'complete', data: event.data })
    eventSource.close()
  })

  eventSource.addEventListener('error', (event) => {
    onEvent({ type: 'error', data: event.data })
    eventSource.close()
  })

  eventSource.onerror = (error) => {
    if (onError) onError(error)
    eventSource.close()
  }

  return eventSource
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
