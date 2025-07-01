<template>
  <div class="chat-container">
    <div class="chat-header glass">
      <div class="header-content">
        <h2 class="neon-text">AI 恋爱大师</h2>
        <div class="chat-id">聊天室ID: {{ chatId }}</div>
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
import { ref, onMounted, onUnmounted } from 'vue'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithLoveAppSse } from '../api/chat'

const chatId = ref('')
const input = ref('')
const messages = ref([])
let eventSource = null
let typingTimer = null

function generateChatId() {
  return 'chat_' + Math.random().toString(36).substr(2, 9)
}

function sendMessage(msg) {
  // 防止空消息
  if (!msg.trim()) return

  // 关闭现有连接和打字定时器
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }

  // 添加用户消息
  messages.value.push({ role: 'user', content: msg })
  
  // 添加AI消息占位
  messages.value.push({ 
    role: 'ai', 
    content: '', 
    displayContent: '',
    isTyping: true 
  })

  // 创建新连接
  eventSource = chatWithLoveAppSse(msg, chatId.value, (data) => {
    const currentMessage = messages.value[messages.value.length - 1]
    if (currentMessage?.role === 'ai') {
      // 更新完整内容
      currentMessage.content += data
      
      // 如果正在打字，不启动新的打字效果
      if (typingTimer) return

      // 模拟打字效果
      function typeNextChar() {
        const fullContent = currentMessage.content
        let currentIndex = currentMessage.displayContent.length

        if (currentIndex < fullContent.length) {
          currentMessage.displayContent = fullContent.slice(0, currentIndex + 1)
          typingTimer = setTimeout(typeNextChar, 30) // 加快打字速度到30ms
        } else {
          typingTimer = null
          // 只有在没有新的文本碎片时才设置isTyping为false
          if (currentMessage.displayContent.length === currentMessage.content.length) {
            currentMessage.isTyping = false
          }
        }
      }

      // 开始打字效果
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

h2 {
  margin: 0;
  font-size: 2em;
  font-weight: 600;
  background: linear-gradient(45deg, #ff69b4, #ff1493);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  text-shadow: 0 0 10px rgba(255, 20, 147, 0.5);
  letter-spacing: 1px;
}

.chat-id {
  font-size: 0.9em;
  opacity: 0.8;
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