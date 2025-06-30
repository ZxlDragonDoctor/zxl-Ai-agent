<template>
  <div class="chat-container">
    <div class="chat-header glass">
      <div class="header-content">
        <h2 class="neon-text">AI 超级智能体</h2>
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
let eventSource = null

function sendMessage(msg) {
  // 防止空消息
  if (!msg.trim()) return

  // 关闭现有连接
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }

  messages.value.push({ role: 'user', content: msg })
  
  // 创建新连接
  eventSource = chatWithManusSse(msg, (data) => {
    if (messages.value[messages.value.length - 1]?.role === 'ai') {
      messages.value[messages.value.length - 1].content += data
    } else {
      messages.value.push({ role: 'ai', content: data })
    }
  })
}

onUnmounted(() => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
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