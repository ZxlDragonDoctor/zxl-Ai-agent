<script setup lang="ts">
import { computed, ref } from 'vue'
import { locale, t } from '../i18n'
import type { AgentDefinition, ConversationSummary } from '../types/conversation'

const props = defineProps<{
  conversations: ConversationSummary[]; agents: AgentDefinition[]; selectedId: string | null
  newType: string; disabled: boolean; agentName: (id: string) => string
}>()
const emit = defineEmits<{
  select: [id: string]; create: []; remove: [id: string]; 'update:newType': [type: string]
}>()
const search = ref('')
const deleteId = ref<string | null>(null)
const filtered = computed(() => props.conversations.filter(item => item.title.toLocaleLowerCase().includes(search.value.toLocaleLowerCase())))
const definition = computed(() => props.agents.find(agent => agent.id === props.newType))
const description = computed(() => definition.value ? (locale.value === 'en' ? definition.value.descriptionEn : definition.value.description) : '')
function date(value: string) { return new Intl.DateTimeFormat(locale.value === 'en' ? 'en-US' : 'zh-CN', { month: 'short', day: 'numeric' }).format(new Date(value)) }
</script>

<template>
  <aside class="conversation-sidebar" :aria-label="t('conversation.history')">
    <div class="sidebar-heading"><span class="sidebar-symbol" aria-hidden="true">✦</span><h2>{{ t('conversation.workspace') }}</h2></div>
    <label class="field-label" for="new-agent-type">{{ t('conversation.newType') }}</label>
    <select id="new-agent-type" :value="newType" :disabled="disabled" @change="emit('update:newType', ($event.target as HTMLSelectElement).value)">
      <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agentName(agent.id) }}</option>
    </select>
    <p class="agent-description">{{ description }}</p>
    <button class="new-conversation" :disabled="disabled || !agents.length" @click="search = ''; deleteId = null; emit('create')">
      <span aria-hidden="true">＋</span>{{ t('conversation.new') }}
    </button>
    <div class="history-heading"><h3>{{ t('conversation.history') }}</h3><span>{{ conversations.length }}</span></div>
    <input v-model="search" type="search" class="history-search" :aria-label="t('conversation.search')" :placeholder="t('conversation.search')" />
    <div class="conversation-list">
      <p v-if="!filtered.length" class="list-empty">{{ search ? t('conversation.noMatch') : t('conversation.noHistory') }}</p>
      <div v-for="item in filtered" :key="item.id" class="conversation-row" :class="{ selected: selectedId === item.id }">
        <button class="conversation-select" :disabled="disabled" :aria-current="selectedId === item.id ? 'true' : undefined" @click="deleteId = null; emit('select', item.id)">
          <span class="conversation-title">{{ item.title }}</span>
          <span class="conversation-meta"><span>{{ agentName(item.agentType) }}</span><time :datetime="item.updatedAt">{{ date(item.updatedAt) }}</time></span>
          <span v-if="item.busy" class="busy-label">{{ t('action.running') }}</span>
        </button>
        <button class="delete-button" :disabled="disabled || item.busy" :aria-label="`${t('conversation.delete')}：${item.title}`" @click="deleteId = deleteId === item.id ? null : item.id">×</button>
        <div v-if="deleteId === item.id" class="delete-confirm" role="group" :aria-label="t('conversation.deleteConfirm')">
          <p>{{ t('conversation.deleteConfirm') }}</p>
          <button :disabled="disabled" @click="emit('remove', item.id); deleteId = null">{{ t('conversation.confirmDelete') }}</button>
          <button @click="deleteId = null">{{ t('conversation.cancel') }}</button>
        </div>
      </div>
    </div>
    <p class="sidebar-note">{{ t('conversation.persistenceNote') }}</p>
  </aside>
</template>

<style scoped>
.conversation-sidebar { display: flex; flex-direction: column; min-height: 0; padding: 22px 16px; border: 1px solid var(--color-border-light); border-radius: 16px; background: var(--color-bg-alt); }
.sidebar-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 24px; }
.sidebar-heading h2 { font-size: 15px; font-weight: 600; }
.sidebar-symbol { color: var(--color-primary); }
.field-label, .agent-description, .sidebar-note { font-size: 11px; line-height: 1.65; color: var(--color-text-secondary); }
.field-label { margin-bottom: 6px; }
select, .history-search { width: 100%; padding: 9px 10px; background: var(--color-surface); color: var(--color-text); border: 1px solid var(--color-border); border-radius: 8px; font-size: 12px; }
.agent-description { min-height: 36px; margin-top: 8px; }
.new-conversation { display: flex; justify-content: center; align-items: center; gap: 8px; padding: 10px; margin: 12px 0 26px; background: var(--color-text); color: var(--color-text-inverse); border-radius: 8px; font-size: 13px; }
.new-conversation:hover { background: var(--color-primary); }
.history-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 11px; color: var(--color-text-secondary); }
.history-heading h3 { font-size: 11px; font-weight: 500; }
.history-search { margin-bottom: 12px; background: transparent; }
.conversation-list { flex: 1; min-height: 0; overflow-y: auto; }
.conversation-row { position: relative; margin-bottom: 6px; border-radius: 8px; border: 1px solid transparent; }
.conversation-row.selected { background: var(--color-surface); border-color: var(--color-border); }
.conversation-select { display: block; width: 100%; text-align: left; padding: 11px 24px 11px 10px; border-radius: 8px; }
.conversation-select:hover { background: var(--color-surface); }
.conversation-title { display: block; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conversation-meta { display: flex; gap: 5px; justify-content: space-between; margin-top: 7px; font-size: 10px; color: var(--color-text-secondary); }
.busy-label { display: block; margin-top: 5px; font-size: 10px; color: var(--color-primary); }
.delete-button { position: absolute; top: 8px; right: 4px; width: 24px; height: 24px; color: var(--color-text-secondary); }
.delete-button:hover { color: var(--color-error); }
.delete-confirm { padding: 10px; border-top: 1px solid var(--color-border); font-size: 11px; line-height: 1.6; }
.delete-confirm button { margin: 8px 10px 0 0; color: var(--color-error); }
.delete-confirm button:last-child { color: var(--color-text-secondary); }
.sidebar-note { padding-top: 16px; margin-top: auto; font-size: 10px; }
.list-empty { padding: 12px 4px; font-size: 12px; color: var(--color-text-secondary); }
button:disabled, select:disabled { opacity: .45; cursor: not-allowed; }
button:focus-visible, select:focus-visible, input:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
@media (max-width: 680px) {
  .conversation-sidebar { padding: 12px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
  .sidebar-heading, .agent-description, .sidebar-note, .history-heading, .history-search { display: none; }
  .field-label { grid-column: 1 / -1; margin: 0; }
  .new-conversation { margin: 0; padding: 8px 12px; }
  .conversation-list { display: flex; gap: 6px; grid-column: 1 / -1; overflow-x: auto; overflow-y: visible; }
  .conversation-row { min-width: 180px; max-width: 230px; margin: 0; }
  .list-empty { padding: 0; }
}
</style>
