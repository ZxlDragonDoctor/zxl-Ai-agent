import type { AgentChatMessage, AgentStreamEvent, ExecutionStep, RunStatus, ToolStatus } from '../types/agent'
import type { ConversationDetail } from '../types/conversation'
import { readSkillEvent } from '../api/skills.ts'

function stepFor(reply: AgentChatMessage, number: number): ExecutionStep {
  const flow = reply.flow!
  let step = flow.steps.find(item => item.number === number)
  if (!step) {
    flow.steps.push({ number, reasoning: '', status: 'running', tools: [] })
    step = flow.steps[flow.steps.length - 1]!
  }
  return step
}

export function finishFlow(reply: AgentChatMessage, status: RunStatus, detail?: string) {
  const flow = reply.flow!
  flow.status = status
  flow.finishedAt = Date.now()
  flow.error = detail
  reply.isTyping = false
  for (const step of flow.steps) {
    if (step.status === 'running') step.status = status === 'error' ? 'error' : status === 'interrupted' ? 'interrupted' : 'completed'
    for (const tool of step.tools) {
      if (tool.status === 'running' || tool.status === 'requested') tool.status = 'interrupted'
    }
  }
}

/** 同一归并函数处理实时事件与存储的工具事件，刷新后仍能查看调用证据。 */
export function applyAgentEvent(reply: AgentChatMessage, event: AgentStreamEvent) {
  const flow = reply.flow!
  if (event.runId) reply.runId = event.runId
  if (event.type === 'RUN_STARTED') { flow.phase = 'thinking'; return }
  if (event.type === 'KNOWLEDGE_RESULT') { reply.knowledgePayload = event.content || ''; return }
  if (['SKILL_SELECTED', 'SKILL_STEP', 'SKILL_TERMINAL'].includes(event.type)) {
    const skill = flow.skill ||= { steps: [] }
    if (event.type === 'SKILL_SELECTED') {
      const value = readSkillEvent('SKILL_SELECTED', event.content || '')
      if (value && (!skill.snapshot || JSON.stringify(skill.snapshot) === JSON.stringify(value))) skill.snapshot ||= value
      else skill.invalidEvent = true
    } else if (event.type === 'SKILL_STEP') {
      const value = readSkillEvent('SKILL_STEP', event.content || '')
      if (value) {
        const index = skill.steps.findIndex(s => s.step === value.step)
        if (index < 0) skill.steps.push(value); else skill.steps[index] = value
      } else skill.invalidEvent = true
    } else {
      const value = readSkillEvent('SKILL_TERMINAL', event.content || '')
      if (value && (!skill.snapshot || JSON.stringify(skill.snapshot) === JSON.stringify(value.snapshot))) { skill.result = value; skill.snapshot ||= value.snapshot }
      else skill.invalidEvent = true
    }
    return
  }
  if (event.type === 'RUN_COMPLETED' || event.type === 'RUN_FAILED') {
    flow.endReason = event.endReason || undefined
    const status = event.status === 'limit' ? 'limit' : event.status === 'terminated' ? 'terminated'
      : event.status === 'interrupted' ? 'interrupted' : event.type === 'RUN_FAILED' ? 'error' : 'completed'
    finishFlow(reply, status, event.content || undefined)
    return
  }
  const step = stepFor(reply, event.step)
  if (event.type === 'STEP_STARTED') { flow.phase = 'thinking'; return }
  if (event.type === 'THINKING_DELTA') { step.reasoning += event.content || ''; flow.phase = 'thinking' }
  else if (event.type === 'ANSWER_DELTA') {
    if (reply.answerStep !== event.step && reply.content) reply.content += '\n\n'
    reply.answerStep = event.step
    reply.content += event.content || ''
    reply.displayContent = reply.content
    flow.phase = 'answering'
  } else if (event.type.startsWith('TOOL_') && event.callId && event.toolName) {
    let tool = step.tools.find(item => item.id === event.callId)
    if (!tool) {
      step.tools.push({ id: event.callId, name: event.toolName, arguments: '', status: 'requested', result: '' })
      tool = step.tools[step.tools.length - 1]!
    }
    const states: Record<string, ToolStatus> = {
      TOOL_REQUESTED: 'requested', TOOL_STARTED: 'running', TOOL_COMPLETED: 'completed', TOOL_FAILED: 'error',
    }
    tool.status = states[event.type] || tool.status
    if (event.arguments != null) tool.arguments = event.arguments
    if (event.content != null) tool.result = event.content
    if (event.durationMs != null) tool.durationMs = event.durationMs
    flow.phase = tool.status === 'running' || tool.status === 'requested' ? 'tools' : 'observing'
  } else if (event.type === 'STEP_COMPLETED') step.status = 'completed'
}

export function restoreChat(detail: ConversationDetail): AgentChatMessage[] {
  const messages: AgentChatMessage[] = []
  for (const run of detail.runs) {
    const records = detail.messages.filter(message => message.runId === run.runId)
    for (const user of records.filter(message => message.role === 'user')) {
      messages.push({ role: 'user', content: user.content, runId: run.runId })
    }
    const answer = run.answer || records.filter(message => message.role === 'assistant' && !message.toolCalls.length)
      .map(message => message.content).join('\n\n')
    const reply: AgentChatMessage = {
      role: 'ai', content: '', runId: run.runId, isTyping: false,
      flow: { status: 'running', phase: 'connecting', steps: [], startedAt: Date.parse(run.startedAt), restored: true },
    }
    for (const event of run.events) applyAgentEvent(reply, event)
    reply.content = answer
    reply.displayContent = answer
    reply.flow!.endReason = run.endReason || undefined
    if (run.status !== 'running') {
      finishFlow(reply, run.status, run.error || undefined)
      reply.flow!.finishedAt = run.finishedAt ? Date.parse(run.finishedAt) : Date.now()
    }
    messages.push(reply)
  }
  return messages
}
