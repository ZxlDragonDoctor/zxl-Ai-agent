<template>
  <div class="chat-room">
    <div class="chat-history glass">
      <div v-for="(msg, idx) in messages" :key="idx" :class="['message', msg.role]">
        <div class="avatar">
          <span v-if="msg.role === 'ai'">🤖</span>
          <span v-else>👤</span>
        </div>
        <div class="msg-content glass">
          <template v-if="msg.role === 'user'">
            {{ msg.content }}
          </template>
          <template v-else>
            <span :class="{ 'typing-text': msg.isTyping }">{{ msg.displayContent }}</span>
            <span v-if="msg.isTyping" class="cursor"></span>
          </template>
        </div>
      </div>
    </div>
    <div class="chat-input glass">
      <input
        :value="modelValue"
        @input="e => emit('update:modelValue', e.target.value)"
        @keyup.enter="send"
        placeholder="请输入消息..."
        class="input-field"
      />
      <button @click="send" class="send-btn glow">
        <span>发送</span>
        <span class="icon">✨</span>
      </button>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  modelValue: String,
  messages: {
    type: Array,
    default: () => []
  },
  onSend: Function
})

const emit = defineEmits(['update:modelValue'])

function send() {
  if (props.modelValue?.trim()) {
    props.onSend(props.modelValue)
    emit('update:modelValue', '')
  }
}
</script>

<style scoped>
.chat-room {
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: clamp(10px, 2vw, 20px);
  width: 100%;
  max-width: var(--max-width-desktop);
  margin: 0 auto;
  height: calc(100vh - clamp(80px, 10vh, 120px));
  padding: clamp(10px, 2vw, 20px);
  overflow: hidden;
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  padding: clamp(20px, 3vw, 30px);
  display: flex;
  flex-direction: column;
  gap: clamp(10px, 2vw, 20px);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  margin-right: 6px;
}

.message {
  display: flex;
  align-items: flex-start;
  gap: clamp(8px, 1.5vw, 15px);
  max-width: min(80%, 800px);
  transition: all 0.3s ease;
}

.message:hover {
  transform: translateY(-2px);
}

.message.user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.avatar {
  width: clamp(32px, 5vw, 40px);
  height: clamp(32px, 5vw, 40px);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: clamp(1.2rem, 2vw, 1.5rem);
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(5px);
  flex-shrink: 0;
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.2);
  border: 2px solid rgba(255, 255, 255, 0.1);
}

.msg-content {
  padding: clamp(8px, 1.5vw, 12px) clamp(12px, 2vw, 20px);
  border-radius: clamp(12px, 2vw, 20px);
  position: relative;
  word-break: break-word;
  line-height: 1.6;
  font-size: clamp(14px, 1vw, 16px);
}

.user .msg-content {
  background: linear-gradient(135deg, var(--primary-color), var(--secondary-color));
  color: white;
  box-shadow: 0 4px 15px rgba(255, 105, 180, 0.3);
}

.ai .msg-content {
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(10px);
  box-shadow: 0 4px 15px rgba(255, 255, 255, 0.1);
}

.chat-input {
  display: flex;
  padding: clamp(10px, 2vw, 15px) clamp(15px, 2.5vw, 20px);
  gap: clamp(10px, 1.5vw, 15px);
  border-radius: clamp(15px, 2.5vw, 25px);
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
}

.input-field {
  flex: 1;
  padding: clamp(8px, 1.5vw, 12px) clamp(15px, 2vw, 20px);
  border: none;
  border-radius: clamp(12px, 2vw, 20px);
  background: rgba(255, 255, 255, 0.1);
  color: white;
  font-size: clamp(14px, 1vw, 16px);
  transition: all 0.3s ease;
}

.input-field::placeholder {
  color: rgba(255, 255, 255, 0.6);
}

.input-field:focus {
  outline: none;
  background: rgba(255, 255, 255, 0.15);
  box-shadow: 0 0 15px rgba(255, 255, 255, 0.1);
}

.send-btn {
  padding: clamp(8px, 1.5vw, 12px) clamp(20px, 2.5vw, 25px);
  border: none;
  border-radius: clamp(12px, 2vw, 20px);
  background: linear-gradient(45deg, var(--primary-color), var(--secondary-color));
  color: white;
  font-size: clamp(14px, 1vw, 16px);
  cursor: pointer;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  gap: 8px;
  box-shadow: 0 4px 15px rgba(255, 105, 180, 0.3);
  white-space: nowrap;
}

.send-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(255, 105, 180, 0.4);
}

.send-btn .icon {
  font-size: 1.2em;
}

/* 自定义滚动条 */
.chat-history::-webkit-scrollbar {
  width: 6px;
}

.chat-history::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 3px;
}

.chat-history::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.2);
  border-radius: 3px;
}

.chat-history::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.3);
}

/* 平板适配 */
@media (max-width: 1024px) {
  .chat-room {
    height: calc(100vh - 100px);
  }
  
  .message {
    max-width: 85%;
  }
}

/* 手机适配 */
@media (max-width: 768px) {
  .chat-room {
    height: calc(100vh - 80px);
    padding: 10px;
  }
  
  .chat-history {
    padding: 15px;
  }
  
  .message {
    max-width: 90%;
  }
  
  .send-btn span:not(.icon) {
    display: none;
  }
  
  .send-btn {
    padding: 12px;
    aspect-ratio: 1;
  }
  
  .send-btn .icon {
    margin: 0;
  }
}

/* 横屏模式适配 */
@media (orientation: landscape) and (max-height: 600px) {
  .chat-room {
    height: calc(100vh - 60px);
  }
  
  .chat-history {
    padding: 10px;
  }
  
  .message {
    max-width: 70%;
  }
}

.typing-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.cursor {
  display: inline-block;
  width: 3px;
  height: 1.2em;
  background-color: currentColor;
  margin-left: 2px;
  vertical-align: middle;
  animation: cursor-blink 0.8s step-start infinite;
}

@keyframes cursor-blink {
  50% {
    opacity: 0;
  }
}
</style> 