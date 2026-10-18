import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { chatWithAgentSse } from '../api/chat'
import { ConversationApiError, createConversation, deleteConversation, getConversation, listAgents, listConversations } from '../api/conversations'
import { locale, t } from '../i18n'
import { applyAgentEvent, finishFlow, restoreChat } from '../utils/agentFlow'
import type { AgentChatMessage, AgentStreamEvent } from '../types/agent'
import type { AgentDefinition, ConversationDetail, ConversationSummary } from '../types/conversation'

const STORAGE_KEY = 'zxl_agent_conversation'

export function useAgentConversations() {
  const route = useRoute()
  const input = ref('')
  const messages = ref<AgentChatMessage[]>([])
  const error = ref('')
  const running = ref(false)
  const loading = ref(false)
  const busy = ref(false)
  const selectedId = ref<string | null>(null)
  const selectedType = ref('manus')
  const newType = ref('manus')
  const conversations = ref<ConversationSummary[]>([])
  const agents = ref<AgentDefinition[]>([])
  const working = computed(() => running.value || loading.value)
  let connection: { close(): void } | null = null
  let activeReply: AgentChatMessage | null = null
  let generation = 0
  let disposed = false
  let polling = false
  let timer: ReturnType<typeof setInterval> | undefined

  function remember(id: string | null) {
    try { if (id) localStorage.setItem(STORAGE_KEY, id); else localStorage.removeItem(STORAGE_KEY) } catch { /* 会话仍保存在服务端。 */ }
  }
  function errorText(code: string) {
    const key = `conversation.error.${code}`
    return t(key) === key ? t('conversation.error.REQUEST_FAILED') : t(key)
  }
  function showError(cause: unknown) {
    error.value = errorText(cause instanceof ConversationApiError ? cause.code : 'REQUEST_FAILED')
  }
  function agentName(id: string) {
    const agent = agents.value.find(item => item.id === id)
    return agent ? (locale.value === 'en' ? agent.nameEn : agent.name) : id
  }
  function applyDetail(detail: ConversationDetail) {
    selectedId.value = detail.id
    selectedType.value = detail.agentType
    messages.value = restoreChat(detail)
    busy.value = detail.busy
    remember(detail.id)
  }
  async function refreshList() {
    const items = await listConversations()
    if (!disposed) conversations.value = items
  }
  async function openConversation(id: string) {
    if (running.value) return
    const token = ++generation
    loading.value = true
    error.value = ''
    try {
      const detail = await getConversation(id)
      if (token === generation && !disposed) { applyDetail(detail); input.value = '' }
    } catch (cause) { if (token === generation && !disposed) showError(cause) }
    finally { if (token === generation && !disposed) loading.value = false }
  }
  async function newConversation() {
    if (working.value || !agents.value.length) return
    loading.value = true
    error.value = ''
    const token = ++generation
    try {
      const detail = await createConversation(newType.value)
      if (token !== generation || disposed) return
      applyDetail(detail)
      input.value = ''
      await refreshList()
    } catch (cause) { if (!disposed) showError(cause) }
    finally { if (!disposed) loading.value = false }
  }
  async function removeConversation(id: string) {
    if (working.value) return
    loading.value = true
    error.value = ''
    try {
      await deleteConversation(id)
      await refreshList()
      if (selectedId.value === id) {
        ++generation
        selectedId.value = null
        messages.value = []
        busy.value = false
        remember(null)
      }
    } catch (cause) { if (!disposed) showError(cause) }
    finally { if (!disposed) loading.value = false }
  }
  async function pollCurrent() {
    if (polling || running.value || loading.value || !busy.value || !selectedId.value) return
    polling = true
    const id = selectedId.value
    const token = generation
    try {
      const detail = await getConversation(id)
      if (!disposed && token === generation && id === selectedId.value && !running.value && !loading.value) {
        applyDetail(detail)
        if (!detail.busy) await refreshList()
      }
    } catch (cause) { if (!disposed && token === generation) showError(cause) }
    finally { polling = false }
  }
  watch(busy, value => {
    clearInterval(timer)
    if (value) timer = setInterval(() => { void pollCurrent() }, 2500)
  })

  async function sendMessage(message: string) {
    if (!message.trim() || working.value || busy.value) return
    if (!selectedId.value) {
      await newConversation()
      if (!selectedId.value) { input.value = message; return }
    }
    const id = selectedId.value!
    error.value = ''
    messages.value.push({ role: 'user', content: message })
    messages.value.push({ role: 'ai', content: '', isTyping: true,
      flow: { status: 'running', phase: 'connecting', steps: [], startedAt: Date.now() } })
    const reply = messages.value[messages.value.length - 1]!
    activeReply = reply
    running.value = true
    function closeReply() {
      connection?.close()
      connection = null
      activeReply = null
      running.value = false
    }
    function onEvent(event: AgentStreamEvent) {
      if (activeReply !== reply || disposed) return
      if (event.conversationId) remember(event.conversationId)
      applyAgentEvent(reply, event)
      if (event.type === 'RUN_COMPLETED' || event.type === 'RUN_FAILED') {
        closeReply()
        busy.value = false
        void refreshList().catch(showError)
      }
    }
    connection = chatWithAgentSse(message, () => {}, reason => {
      if (activeReply !== reply || disposed) return
      const message = reason === 'invalid_event' ? t('flow.invalidEvent')
        : reason === 'connection_interrupted' ? t('flow.connectionError') : errorText(reason)
      error.value = message
      finishFlow(reply, 'error', message)
      closeReply()
      busy.value = true
      void pollCurrent()
    }, onEvent, id)
  }

  function stopReceiving() {
    if (!activeReply) return
    activeReply.flow!.endReason = 'OUTPUT_INTERRUPTED'
    finishFlow(activeReply, 'interrupted', t('flow.stoppedNote'))
    connection?.close()
    connection = null
    activeReply = null
    running.value = false
    busy.value = true
    void pollCurrent()
  }

  async function initialize() {
    if (working.value) return
    loading.value = true
    error.value = ''
    const token = ++generation
    try {
      const [catalog, items] = await Promise.all([listAgents(), listConversations()])
      if (disposed || token !== generation) return
      agents.value = catalog
      conversations.value = items
      const requestedType = typeof route.query.agentType === 'string' ? route.query.agentType : null
      if (requestedType && catalog.some(agent => agent.id === requestedType)) {
        newType.value = requestedType
        selectedType.value = requestedType
      }
      let saved: string | null = null
      try { saved = localStorage.getItem(STORAGE_KEY) } catch { /* 可从列表手动恢复。 */ }
      const eligible = requestedType && catalog.some(agent => agent.id === requestedType)
        ? items.filter(item => item.agentType === requestedType) : items
      const id = eligible.find(item => item.id === saved)?.id || eligible[0]?.id
      if (id) {
        const detail = await getConversation(id)
        if (!disposed && token === generation) applyDetail(detail)
      }
    } catch (cause) { if (!disposed) showError(cause) }
    finally { if (!disposed && token === generation) loading.value = false }
  }
  watch(() => route.query.agentType, type => {
    if (!running.value && typeof type === 'string' && agents.value.some(agent => agent.id === type)) {
      ++generation
      newType.value = type
      selectedType.value = type
      selectedId.value = null
      messages.value = []
      busy.value = false
      loading.value = false
      remember(null)
    }
  })
  onMounted(initialize)
  onUnmounted(() => { disposed = true; ++generation; connection?.close(); activeReply = null; clearInterval(timer) })

  return { input, messages, error, running, loading, busy, selectedId, selectedType, newType, conversations,
    agents, working, agentName, openConversation, newConversation, removeConversation, sendMessage,
    stopReceiving, initialize, refreshCurrent: () => selectedId.value ? openConversation(selectedId.value) : initialize() }
}
