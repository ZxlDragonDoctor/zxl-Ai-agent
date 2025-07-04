<template>
  <div class="chat-container">
    <div class="chat-header glass">
      <div class="header-content">
        <h2 class="neon-text">AI 超级智能体</h2>
        <div v-if="error" class="error-message">{{ error }}</div>
      </div>
      <router-link to="/" class="back-btn glass">
        <span class="icon">🏠</span>
        返回首页
      </router-link>
    </div>
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

const input = ref('')
const messages = ref([])
const error = ref('')
let eventSource = null
let typingTimer = null
let currentStep = ''
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
      // 处理队列中的下一条消息
      processNextMessage()
    }
  }

  // 开始打字效果
  typeNextChar()
}

function sendMessage(msg) {
  // 防止空消息
  if (!msg.trim()) return

  // 清空消息队列
  messageQueue = []
  
  // 关闭现有连接和打字定时器
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }

  // 重置打字状态
  isTyping = false

  // 添加用户消息
  messages.value.push({ role: 'user', content: msg })
  
  // 创建新连接
  eventSource = chatWithManusSse(msg, (data) => {
    // 检查是否是新的步骤
    if (data.startsWith('Step')) {
      // 将新步骤添加到消息队列
      messageQueue.push(data)
      // 如果当前没有正在打字的消息，开始处理
      if (!isTyping) {
        processNextMessage()
      }
    } else if (messages.value[messages.value.length - 1]?.role === 'ai') {
      // 如果是当前步骤的后续内容，直接追加
      const currentMessage = messages.value[messages.value.length - 1]
      currentMessage.content += data
    }
  }, (errorMsg) => {
    error.value = errorMsg
    const currentMessage = messages.value[messages.value.length - 1]
    if (currentMessage?.role === 'ai' && !currentMessage.content) {
      // 如果是空消息，则移除
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
.chat-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 20px;
  max-width: 1600px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
}

.chat-header {
  padding: 20px 30px;
  border-radius: 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(13, 20, 43, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}

.header-content {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.error-message {
  color: #ff4444;
  font-size: 0.9em;
  margin-top: 5px;
}

h2 {
  margin: 0;
  font-size: 2em;
  font-weight: 600;
  background: linear-gradient(45deg, #00ffff, #2196f3);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  text-shadow: 0 0 10px rgba(33, 150, 243, 0.5);
  letter-spacing: 1px;
}

.back-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  text-decoration: none;
  transition: all 0.3s ease;
  border: 1px solid rgba(255, 255, 255, 0.2);
  font-size: 0.9em;
}

.back-btn:hover {
  background: rgba(255, 255, 255, 0.2);
  transform: translateY(-2px);
  box-shadow: 0 5px 15px rgba(255, 255, 255, 0.1);
}

.icon {
  font-size: 1.2em;
}

@media (max-width: 768px) {
  .chat-container {
    padding: 10px;
  }
  
  .chat-header {
    padding: 15px;
    flex-direction: column;
    gap: 15px;
    text-align: center;
  }
  
  h2 {
    font-size: 1.5em;
  }
  
  .back-btn {
    width: 100%;
    justify-content: center;
  }
}
</style> 