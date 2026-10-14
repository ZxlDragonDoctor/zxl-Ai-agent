<template>
  <div class="chat-view">
    <header class="chat-view-header">
      <div class="chat-view-heading">
        <h1 class="chat-view-title">{{ t('nav.loveMaster') }}</h1>
        <span class="chat-view-id">{{ t('chat.session') }}: {{ chatId }}</span>
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
import { ref, onMounted, onUnmounted } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithLoveAppSse } from '../api/chat'
import { t } from '../i18n'

const chatId = ref('')
const input = ref('')
const messages = ref([])
let eventSource = null
let typingTimer = null

function generateChatId() {
  return 'chat_' + Math.random().toString(36).substr(2, 9)
}

function sendMessage(msg) {
  if (!msg.trim()) return

  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }

  messages.value.push({ role: 'user', content: msg })
  
  messages.value.push({ 
    role: 'ai', 
    content: '', 
    displayContent: '',
    isTyping: true 
  })

  eventSource = chatWithLoveAppSse(msg, chatId.value, (data) => {
    const currentMessage = messages.value[messages.value.length - 1]
    if (currentMessage?.role === 'ai') {
      currentMessage.content += data
      
      if (typingTimer) return

      function typeNextChar() {
        const fullContent = currentMessage.content
        let currentIndex = currentMessage.displayContent.length

        if (currentIndex < fullContent.length) {
          currentMessage.displayContent = fullContent.slice(0, currentIndex + 1)
          typingTimer = setTimeout(typeNextChar, 30)
        } else {
          typingTimer = null
          if (currentMessage.displayContent.length === currentMessage.content.length) {
            currentMessage.isTyping = false
          }
        }
      }

      typeNextChar()
    }
  })
}

onMounted(() => {
  chatId.value = generateChatId()
})

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
  color: var(--color-accent);
}

.chat-view-id {
  font-size: 12.5px;
  color: var(--color-text-tertiary);
}
</style>
