<template>
  <div class="chat-view">
    <header class="chat-view-header">
      <div class="chat-view-heading">
        <h1 class="chat-view-title">{{ t('nav.superAgent') }}</h1>
        <span v-if="error" class="chat-view-error">{{ error }}</span>
      </div>
      <button v-if="running" class="stop-output" @click="stopReceiving">{{ t('flow.stop') }}</button>
    </header>
    <ChatRoom
      v-model="input"
      :messages="messages"
      :onSend="sendMessage"
      :disabled="running"
    >
      <template #assistant-message="{ message }">
        <AgentExecutionFlow v-if="message.flow" :flow="message.flow" />
        <div v-if="message.content || message.flow?.status !== 'running'" class="agent-answer">
          {{ message.content || t('flow.emptyReply') }}<span v-if="message.isTyping" class="answer-cursor" aria-hidden="true"></span>
        </div>
      </template>
    </ChatRoom>
  </div>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import AgentExecutionFlow from '../components/AgentExecutionFlow.vue'
import { chatWithManusSse } from '../api/chat'
import { t } from '../i18n'
import type { AgentChatMessage, AgentStreamEvent, ExecutionStep, RunStatus, ToolStatus } from '../types/agent'

const input = ref('')
const messages = ref<AgentChatMessage[]>([])
const error = ref('')
const running = ref(false)
let eventSource: EventSource | null = null
let activeReply: AgentChatMessage | null = null

function getStep(reply: AgentChatMessage, number: number): ExecutionStep {
  const flow = reply.flow!
  let step = flow.steps.find(item => item.number === number)
  if (!step) {
    flow.steps.push({ number, reasoning: '', status: 'running', tools: [] })
    step = flow.steps[flow.steps.length - 1]!
  }
  return step
}

function finishReply(reply: AgentChatMessage, status: RunStatus, detail?: string) {
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
  if (activeReply === reply) {
    running.value = false
    activeReply = null
    eventSource?.close()
    eventSource = null
  }
}

function sendMessage(message: string) {
  if (!message.trim() || running.value) return
  error.value = ''
  messages.value.push({ role: 'user', content: message })
  messages.value.push({
    role: 'ai', content: '', displayContent: '', isTyping: true,
    flow: { status: 'running', phase: 'connecting', steps: [], startedAt: Date.now() },
  })
  const reply = messages.value[messages.value.length - 1]!
  activeReply = reply
  running.value = true
  let structured = false
  let lastAnswerStep = 0

  function onEvent(event: AgentStreamEvent) {
    if (activeReply !== reply) return
    structured = true
    const flow = reply.flow!
    if (event.type === 'RUN_STARTED') { flow.phase = 'thinking'; return }
    if (event.type === 'RUN_COMPLETED') {
      flow.endReason = event.endReason || undefined
      const status = event.status === 'limit' ? 'limit' : event.status === 'terminated' ? 'terminated' : 'completed'
      finishReply(reply, status, event.content || undefined)
      return
    }
    if (event.type === 'RUN_FAILED') {
      flow.endReason = event.endReason || undefined
      finishReply(reply, 'error', event.content || t('flow.connectionError'))
      return
    }

    const step = getStep(reply, event.step)
    if (event.type === 'STEP_STARTED') { flow.phase = 'thinking'; return }
    if (event.type === 'THINKING_DELTA') {
      step.reasoning += event.content || ''
      flow.phase = 'thinking'
    } else if (event.type === 'ANSWER_DELTA') {
      if (event.step !== lastAnswerStep && reply.content) reply.content += '\n\n'
      lastAnswerStep = event.step
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
    } else if (event.type === 'STEP_COMPLETED') {
      step.status = 'completed'
    }
  }

  eventSource = chatWithManusSse(message, (data) => {
    if (!structured && activeReply === reply) {
      reply.content += data + '\n'
      reply.displayContent = reply.content
    }
  }, (reason) => {
    if (activeReply !== reply) return
    error.value = t(reason === 'invalid_event' ? 'flow.invalidEvent' : 'flow.connectionError')
    finishReply(reply, 'error', error.value)
  }, onEvent)
}

function stopReceiving() {
  if (!activeReply) return
  const reply = activeReply
  reply.flow!.endReason = 'OUTPUT_INTERRUPTED'
  finishReply(reply, 'interrupted')
  if (!reply.content) reply.content = t('flow.stoppedNote')
}

onUnmounted(() => {
  eventSource?.close()
  eventSource = null
  activeReply = null
})
</script>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 100px);
  gap: 20px;
}

.chat-view-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat-view-heading {
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.chat-view-title {
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--color-primary);
}

.chat-view-error {
  font-size: 13px;
  color: var(--color-error);
}

.stop-output { flex-shrink: 0; padding: 7px 12px; border: 1px solid var(--color-border); border-radius: 8px; color: var(--color-text-secondary); font-size: 12px; }
.stop-output:hover { border-color: var(--color-text-secondary); color: var(--color-text); }
.stop-output:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 3px; }
.agent-answer { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.answer-cursor { display: inline-block; width: 4px; height: 13px; margin-left: 4px; vertical-align: middle; background: var(--color-primary); animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: .2; } }
@media (prefers-reduced-motion: reduce) { .answer-cursor { animation: none; } }
</style>
