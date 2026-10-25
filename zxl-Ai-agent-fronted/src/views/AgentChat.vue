<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AgentExecutionFlow from '../components/AgentExecutionFlow.vue'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import KnowledgeAnswerCard from '../components/KnowledgeAnswerCard.vue'
import FactMemoryPanel from '../components/FactMemoryPanel.vue'
import McpToolsPanel from '../components/McpToolsPanel.vue'
import SkillPanel from '../components/SkillPanel.vue'
import { useAgentConversations } from '../composables/useAgentConversations'
import { locale, t } from '../i18n'

const { input, messages, error, running, loading, busy, selectedId, selectedType, newType, conversations,
  agents, working, agentName, openConversation, newConversation, removeConversation, sendMessage,
  stopReceiving, refreshCurrent } = useAgentConversations()
const title = computed(() => conversations.value.find(item => item.id === selectedId.value)?.title || t('conversation.newTitle'))
const activeType = computed(() => selectedId.value ? selectedType.value : newType.value)
const skillVersion = ref('')
watch(selectedId, () => { skillVersion.value = '' })
function sendSelected(message: string) {
  return sendMessage(message, activeType.value === 'it-ticket-skill'
    ? { skillId: 'it-ticket-analysis', ...(skillVersion.value ? { skillVersion: skillVersion.value } : {}) } : undefined)
}
const examplePrompt = computed(() => activeType.value === 'enterprise-knowledge'
  ? (locale.value === 'zh-CN' ? 'VPN报错401，应该如何处理？' : 'How should I handle VPN error 401?')
  : activeType.value === 'mcp-research' ? (locale.value === 'zh-CN' ? '请调用知识查询工具，查找 VPN401 的处理方法，并注明资料来源。' : 'Use the knowledge lookup tool to find VPN401 troubleshooting steps and cite the sources.')
  : activeType.value === 'it-ticket-skill' ? (locale.value === 'zh-CN' ? 'VPN报错401，无法连接公司网络，请按IT工单分析策略给出有依据的处理建议和待审批草稿。' : 'VPN error 401 prevents access to the company network. Analyze the IT issue and prepare an evidence-backed draft for approval.')
  : t(activeType.value === 'love-assistant' ? 'conversation.exampleLove' : 'conversation.exampleMemory'))
</script>

<template>
  <div class="conversation-shell">
    <ConversationSidebar v-model:new-type="newType" :conversations="conversations" :agents="agents"
      :selected-id="selectedId" :disabled="working" :agent-name="agentName"
      @select="openConversation" @create="newConversation" @remove="removeConversation" />
    <section class="chat-view" :aria-label="t('conversation.chat')" :aria-busy="loading">
      <header class="chat-view-header">
        <div class="chat-view-heading">
          <p class="agent-label"><span aria-hidden="true">✦</span>{{ agentName(activeType) }}</p>
          <h1 class="chat-view-title">{{ title }}</h1>
        </div>
        <div class="header-actions">
          <button v-if="running" class="text-button" @click="stopReceiving">{{ t('flow.stop') }}</button>
          <button v-else class="text-button" :disabled="loading" @click="refreshCurrent">{{ t('conversation.reload') }}</button>
        </div>
      </header>
      <p v-if="error" class="chat-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="session-notice" role="status">{{ t('conversation.loading') }}</p>
      <p v-else-if="busy && !running" class="session-notice" role="status">{{ t('conversation.busyNote') }}</p>
      <FactMemoryPanel v-if="activeType === 'enterprise-knowledge'" :key="selectedId || 'draft'" :conversation-id="selectedId" />
      <McpToolsPanel v-if="activeType === 'mcp-research'" :key="selectedId || 'draft'" />
      <SkillPanel v-if="activeType === 'it-ticket-skill'" :key="selectedId || 'draft'" v-model="skillVersion" :disabled="working || busy" />
      <ChatRoom :key="selectedId || 'draft'" v-model="input" :messages="messages" :on-send="sendSelected" :disabled="working || busy || !agents.length">
        <template #empty>
          <div class="conversation-welcome">
            <span class="welcome-symbol" aria-hidden="true">✦</span>
            <h2>{{ t('conversation.welcome') }}</h2>
            <p>{{ t('conversation.welcomeDescription') }}</p>
            <div class="example-prompts">
              <button @click="input = examplePrompt">
                {{ examplePrompt }}<span aria-hidden="true">↗</span>
              </button>
              <button v-if="activeType === 'manus'" @click="input = t('conversation.exampleTime')">{{ t('conversation.exampleTime') }}<span aria-hidden="true">↗</span></button>
            </div>
          </div>
        </template>
        <template #assistant-message="{ message }">
          <AgentExecutionFlow v-if="message.flow" :flow="message.flow" />
          <div v-if="message.content || message.flow?.status !== 'running'" class="agent-answer">
            {{ message.content || t('flow.emptyReply') }}<span v-if="message.isTyping" class="answer-cursor" aria-hidden="true"></span>
          </div>
          <KnowledgeAnswerCard v-if="message.knowledgePayload" :payload="message.knowledgePayload" />
        </template>
      </ChatRoom>
    </section>
  </div>
</template>

<style scoped>
.conversation-shell { display: grid; grid-template-columns: 228px minmax(0, 1fr); gap: 28px; height: calc(100dvh - 160px); min-height: 480px; }
.chat-view { display: flex; flex-direction: column; min-height: 0; min-width: 0; gap: 14px; }
.chat-view-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 6px 0 14px; border-bottom: 1px solid var(--color-border-light); }
.chat-view-heading { min-width: 0; }
.header-actions { flex-shrink: 0; }
.agent-label { display: flex; gap: 6px; color: var(--color-primary); font-size: 11px; margin-bottom: 5px; }
.chat-view-title { font-family: var(--font-display); font-size: 22px; line-height: 1.4; font-weight: 600; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.text-button { flex-shrink: 0; white-space: nowrap; padding: 8px 11px; border: 1px solid var(--color-border); border-radius: 8px; color: var(--color-text-secondary); font-size: 11px; }
.text-button:hover { border-color: var(--color-primary); color: var(--color-primary); }
.chat-error, .session-notice { font-size: 12px; line-height: 1.7; padding: 10px 12px; border-radius: 8px; background: var(--color-bg-alt); }
.chat-error { color: var(--color-error); }
.session-notice { color: var(--color-text-secondary); }
.agent-answer { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.answer-cursor { display: inline-block; width: 4px; height: 13px; margin-left: 4px; vertical-align: middle; background: var(--color-primary); animation: blink 1s infinite; }
.conversation-welcome { margin: auto; padding: 24px 12px 36px; max-width: 480px; }
.welcome-symbol { font-size: 34px; color: var(--color-primary); }
.conversation-welcome h2 { font-size: 25px; font-weight: 500; letter-spacing: -.02em; margin: 14px 0; }
.conversation-welcome p { font-size: 13px; line-height: 1.9; color: var(--color-text-secondary); }
.example-prompts { display: grid; gap: 10px; margin-top: 28px; }
.example-prompts button { display: flex; justify-content: space-between; gap: 20px; padding: 14px; text-align: left; border: 1px solid var(--color-border); border-radius: 10px; font-size: 12px; }
.example-prompts button:hover { background: var(--color-bg-alt); border-color: var(--color-primary); }
.example-prompts span { color: var(--color-primary); }
button:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 3px; }
button:disabled { opacity: .45; cursor: not-allowed; }
:deep(.chat-room) { height: auto; min-height: 0; }
:deep(.msg) { max-width: 94%; }
:deep(.msg-bubble.has-flow) { width: 100%; min-width: 0; }
:deep(.msg.ai:has(.has-flow)) { width: 100%; }
@keyframes blink { 50% { opacity: .2; } }
@media (max-width: 950px) { .conversation-shell { grid-template-columns: 190px minmax(0, 1fr); gap: 18px; } .chat-view-title { font-size: 19px; } }
@media (max-width: 680px) { .conversation-shell { display: flex; flex-direction: column; height: calc(100dvh - 150px); min-height: 620px; gap: 16px; } .chat-view { flex: 1; } .conversation-welcome { padding: 18px 6px; } .conversation-welcome h2 { font-size: 22px; } }
@media (prefers-reduced-motion: reduce) { .answer-cursor { animation: none; } }
</style>
