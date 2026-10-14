<template>
  <div class="chat-view">
    <header class="chat-view-header">
      <div class="chat-view-heading">
        <h1 class="chat-view-title">{{ t('nav.superAgent') }}</h1>
        <span v-if="error" class="chat-view-error">{{ error }}</span>
      </div>
    </header>
    <ChatRoom
      v-model="input"
      :messages="messages"
      :onSend="sendMessage"
    />
  </div>
</template>

<script setup>
import { ref, onUnmounted } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithManusSse } from '../api/chat'
import { t } from '../i18n'

const input = ref('')
const messages = ref([])
const error = ref('')
let eventSource = null
let typingTimer = null
let messageQueue = []
let isTyping = false

function processNextMessage() {
  if (messageQueue.length > 0 && !isTyping) {
    const nextMessage = messageQueue.shift()
    addNewMessage(nextMessage)
  }
}

function addNewMessage(data) {
  isTyping = true
  messages.value.push({ 
    role: 'ai', 
    content: data, 
    displayContent: '',
    isTyping: true 
  })

  const currentMessage = messages.value[messages.value.length - 1]
  
  function typeNextChar() {
    const fullContent = currentMessage.content
    let currentIndex = currentMessage.displayContent.length

    if (currentIndex < fullContent.length) {
      currentMessage.displayContent = fullContent.slice(0, currentIndex + 1)
      typingTimer = setTimeout(typeNextChar, 15)
    } else {
      typingTimer = null
      currentMessage.isTyping = false
      isTyping = false
      processNextMessage()
    }
  }

  typeNextChar()
}

function sendMessage(msg) {
  if (!msg.trim()) return

  messageQueue = []
  
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }

  isTyping = false

  messages.value.push({ role: 'user', content: msg })
  
  eventSource = chatWithManusSse(msg, (data) => {
    if (data.startsWith('Step')) {
      messageQueue.push(data)
      if (!isTyping) {
        processNextMessage()
      }
    } else if (messages.value[messages.value.length - 1]?.role === 'ai') {
      const currentMessage = messages.value[messages.value.length - 1]
      currentMessage.content += data
    }
  }, (errorMsg) => {
    error.value = errorMsg
    const currentMessage = messages.value[messages.value.length - 1]
    if (currentMessage?.role === 'ai' && !currentMessage.content) {
      messages.value.pop()
    }
  })
}

onUnmounted(() => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }
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
</style>
