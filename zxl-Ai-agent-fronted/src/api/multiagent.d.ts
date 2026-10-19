import type { WorkflowEvent } from '../types/workflow'
type AgentInfo = { id: string; type: string; state: string }
type AgentEvent = { timestamp: number | string; type: string; agentId: string }
type BenchmarkReport = {
  successRate?: number
  avgResponseTimeMs?: number
  p95ResponseTimeMs?: number
}
type BenchmarkResult = { agentId: string; report: BenchmarkReport }
type SystemStatus = {
  activePlans?: number
  pendingTasks?: number
  observer?: { totalTasksAcrossAgents?: number }
}

export function orchestrateGoal(goal: string, context?: Record<string, unknown>): Promise<{ result?: string; error?: string; code?: string; path?: string }>
export function orchestrateGoalStream(goal: string, context: Record<string, unknown>, onEvent: (event: WorkflowEvent) => void, onError: (reason: string) => void): { close(): void }
export function getSystemStatus(): Promise<SystemStatus>
export function listAgents(): Promise<{ agents?: AgentInfo[] }>
export function getRecentEvents(limit?: number): Promise<{ events?: AgentEvent[] }>
export function runBenchmark(): Promise<{ benchmarkResults?: BenchmarkResult[] }>
