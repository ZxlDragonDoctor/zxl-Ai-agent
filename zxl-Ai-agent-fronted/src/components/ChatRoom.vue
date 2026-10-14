<template>
  <div class="chat-room">
    <div class="chat-history" ref="historyRef">
      <div v-for="(msg, idx) in messages" :key="idx" :class="['msg', msg.role]">
        <div class="msg-avatar">
          <span v-if="msg.role === 'ai'">✦</span>
          <span v-else>●</span>
        </div>
        <div class="msg-bubble" :class="msg.role">
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
    <div class="chat-bar">
      <input
        :value="modelValue"
        @input="e => emit('update:modelValue', (e.target as HTMLInputElement).value)"
        @keyup.enter="send"
        :placeholder="t('chat.inputPlaceholder')"
        class="chat-input"
      />
      <button @click="send" class="btn-send" :aria-label="t('chat.send')" :title="t('chat.send')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { t } from '../i18n'

const props = defineProps<{
  modelValue: string
  messages: any[]
  onSend: (msg: string) => void
}>()

const emit = defineEmits(['update:modelValue'])
const historyRef = ref<HTMLElement | null>(null)

function send() {
  if (props.modelValue?.trim()) {
    props.onSend(props.modelValue)
    emit('update:modelValue', '')
  }
}

watch(() => props.messages.length, () => {
  setTimeout(() => {
    if (historyRef.value) {
      historyRef.value.scrollTop = historyRef.value.scrollHeight
    }
  }, 50)
})
</script>

<style scoped>
.chat-room {
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: 16px;
  height: calc(100vh - 180px);
  overflow: hidden;
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  padding: 8px 4px 8px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.msg {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  max-width: 80%;
}

.msg.user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
  background: var(--color-bg-alt);
  color: var(--color-text-tertiary);
  border: 1px solid var(--color-border-light);
}

.msg-bubble {
  padding: 10px 16px;
  border-radius: var(--radius-md);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.msg-bubble.user {
  background: var(--color-text);
  color: var(--color-text-inverse);
}

.msg-bubble.ai {
  background: var(--color-bg-alt);
  color: var(--color-text);
  border: 1px solid var(--color-border-light);
}

.chat-bar {
  display: flex;
  padding: 12px 16px;
  gap: 8px;
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
}

.chat-input {
  flex: 1;
  padding: 8px 4px;
  border: none;
  background: transparent;
  color: var(--color-text);
  font-size: 14px;
  outline: none;
}

.chat-input::placeholder {
  color: var(--color-text-tertiary);
}

.btn-send {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-text);
  color: var(--color-text-inverse);
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.btn-send:hover {
  background: var(--color-primary);
}

.typing-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.cursor {
  display: inline-block;
  width: 2px;
  height: 1.1em;
  background: var(--color-primary);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 0.8s step-start infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}

.chat-history::-webkit-scrollbar { width: 4px; }
.chat-history::-webkit-scrollbar-track { background: transparent; }
.chat-history::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 2px; }

@media (max-width: 720px) {
  .chat-room { height: calc(100vh - 150px); }
  .msg { max-width: 88%; }
}
</style>
