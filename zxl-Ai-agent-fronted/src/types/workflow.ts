export type WorkflowError = { code: string; path: string; message: string }
export type DependencyResult = { taskId: string; description: string; agentId: string; output: string }
export type TaskContext = { originalGoal: string; userInput: Record<string, unknown>; dependencyResults: DependencyResult[] }
export type WorkflowTask = {
  id: string; description: string; capability: string; status: string
  assignedAgentId?: string; dependencies: string[]; executionContext?: TaskContext
  result?: string; error?: string; errorCode?: string; errorPath?: string
  modelCalls: number; startedAt: number; completedAt: number
}
export type WorkflowReport = {
  executionId: string; planId?: string; sequence: number; goal: string
  status: 'planning' | 'running' | 'completed' | 'failed'
  startedAt: number; finishedAt: number; tasks: WorkflowTask[]
  planningModelCalls: number; workerModelCalls: number; synthesisModelCalls: number
  result?: string; error?: WorkflowError
}
export type WorkflowEvent =
  | { type: 'report'; data: WorkflowReport }
  | { type: 'complete'; data: string }
  | { type: 'error'; data: WorkflowError }
