<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { locale } from '../i18n'
import { actOnFact, askKnowledge, correctFact, deleteFact, factIsLive, listFacts, proposeFact, recallFacts } from '../api/answer'
import type { DemoProfile, Fact } from '../api/answer'
import KnowledgeAnswerCard from './KnowledgeAnswerCard.vue'
const props = defineProps<{ conversationId: string | null }>()
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const profile = ref<DemoProfile>('alice'), facts = ref<Fact[]>([]), text = ref('我偏好中文回复'), ttl = ref(3600)
const busy = ref(false), error = ref(''), notice = ref(''), editing = ref<Fact | null>(null), correction = ref('')
const recalled = ref<Awaited<ReturnType<typeof recallFacts>> | null>(null), preview = ref(''), previewQuestion = ref('VPN报错401，应该如何处理？')
const mode = ref<'demo' | 'real'>('demo'), now = ref(Date.now())
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(timer))
const live = (f: Fact) => factIsLive(f, now.value)
const confirmedCount = computed(() => facts.value.filter(f => f.state === 'CONFIRMED' && live(f)).length)
async function load() { facts.value = await listFacts(profile.value) }
async function run(action: () => Promise<unknown>, success?: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''; recalled.value = null; preview.value = ''
  try { await action(); await load(); if (success) notice.value = success }
  catch (cause) { error.value = cause instanceof Error ? cause.message : tr('操作未完成', 'Operation failed'); await load().catch(() => {}) }
  finally { busy.value = false }
}
function propose() { return run(() => proposeFact({ profile: profile.value, sessionId: props.conversationId || 'memory-demo', messageId: crypto.randomUUID(), userText: text.value, ttlSeconds: ttl.value }), tr('提议已保存，确认前不会进入上下文。', 'Proposal saved. It will not enter context until confirmed.')) }
function act(f: Fact, action: 'confirm' | 'reject') { return run(() => actOnFact(f.id, action, { profile: profile.value, version: f.version }), tr('状态已更新', 'State updated')) }
function remove(f: Fact) { return run(() => deleteFact(f.id, profile.value, f.version), tr('已从业务召回移除；历史消息另行保留。', 'Removed from recall. Historical messages are retained separately.')) }
async function saveCorrection() {
  if (!editing.value) return
  const f = editing.value
  await run(() => correctFact(f.id, { profile: profile.value, version: f.version, userText: correction.value, sessionId: props.conversationId || 'memory-demo', messageId: crypto.randomUUID(), ttlSeconds: ttl.value }), tr('更正进入待确认，旧值已退出召回。', 'Correction awaits confirmation; the old value is no longer recalled.'))
  if (!error.value) editing.value = null
}
async function recall() { await run(async () => { recalled.value = await recallFacts(profile.value) }) }
async function testContext() { await run(async () => { preview.value = JSON.stringify(await askKnowledge({ question: previewQuestion.value, conversationId: props.conversationId || 'memory-preview', mode: mode.value, budgetMs: 30000, profile: profile.value })) }) }
watch(profile, () => { editing.value = null; void run(load) })
watch([mode, previewQuestion], () => { preview.value = ''; error.value = '' })
onMounted(() => { void run(load) })
</script>
<template>
  <details class="memory-panel">
    <summary>{{ tr('确认事实记忆', 'Confirmed fact memory') }} <span>{{ confirmedCount }} {{ tr('条有效', 'active') }}</span></summary>
    <div class="memory-body" :aria-busy="busy">
      <p class="boundary">{{ tr('真实PG · 仅白名单偏好 · 先确认再召回。Alice / Bob 是固定演示身份，不代表生产登录授权。主聊天使用 Alice；下方可比较两个用户的上下文。', 'Real PG · Allowlisted preferences · Confirm before recall. Alice / Bob are fixed demo identities, not production authentication. Main chat uses Alice; compare both profiles below.') }}</p>
      <div class="toolbar"><label>{{ tr('演示用户', 'Demo profile') }} <select v-model="profile" :disabled="busy"><option value="alice">Alice</option><option value="bob">Bob</option></select></label><button :disabled="busy" @click="run(load)">{{ tr('刷新记忆', 'Refresh memories') }}</button><button :disabled="busy" @click="recall">{{ tr('查看实际召回', 'Inspect recall') }}</button></div>
      <form @submit.prevent="propose"><label class="field">{{ tr('明确的用户原话', 'Explicit user statement') }}<input v-model="text" maxlength="500" :disabled="busy" :placeholder="tr('我偏好中文回复', 'I prefer replies in Chinese')"></label><div class="toolbar"><label>{{ tr('有效期', 'Lifetime') }} <select v-model="ttl" :disabled="busy"><option :value="5">{{ tr('5秒（到期演示）', '5 seconds (expiry demo)') }}</option><option :value="3600">{{ tr('1小时', '1 hour') }}</option><option :value="86400">{{ tr('1天', '1 day') }}</option></select></label><button class="primary" :disabled="busy || !text.trim()">{{ tr('提议保存', 'Propose memory') }}</button></div></form>
      <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p><p v-if="busy" role="status">{{ tr('正在处理…', 'Working…') }}</p>
      <div v-if="recalled" class="recall" role="region" :aria-label="tr('实际召回结果', 'Actual recall result')"><strong>{{ tr('实际进入记忆预算', 'Selected for memory budget') }}</strong><p>{{ recalled.facts.length }} {{ tr('条', 'facts') }} · {{ recalled.usedChars }} / 800 {{ tr('字符', 'characters') }} · {{ tr('丢弃', 'Dropped') }} {{ recalled.droppedCount }}</p><p v-for="f in recalled.facts" :key="f.id">{{ f.factKey }} = {{ f.value }} · v{{ f.version }}</p></div>
      <p v-if="!facts.length && !busy">{{ tr('此用户尚无记忆。', 'No memories for this profile.') }}</p>
      <article v-for="f in facts" :key="f.id" class="fact"><div class="toolbar"><strong>{{ f.factKey }}：{{ f.value }}</strong><span class="state">{{ !live(f) && !f.deletedAt ? tr('已到期', 'Expired') : f.state }} · v{{ f.version }}</span></div><p class="quote">“{{ f.sourceText }}”</p><small>{{ tr('到期', 'Expires') }} {{ new Date(f.expiresAt).toLocaleString() }} · {{ tr('会话来源', 'Session source') }} {{ f.sessionId }}</small><div class="toolbar actions"><button v-if="f.state === 'PENDING' && live(f)" :disabled="busy" @click="act(f, 'confirm')">{{ tr('确认保存', 'Confirm') }}</button><button v-if="f.state === 'PENDING' && live(f)" :disabled="busy" @click="act(f, 'reject')">{{ tr('拒绝提议', 'Reject') }}</button><button v-if="f.state === 'CONFIRMED' && live(f)" :disabled="busy" @click="act(f, 'confirm')">{{ tr('再次确认（幂等）', 'Confirm again (idempotent)') }}</button><button v-if="f.state === 'CONFIRMED' && live(f)" :disabled="busy" @click="editing = f; correction = f.sourceText">{{ tr('更正', 'Correct') }}</button><button v-if="!f.deletedAt && f.state !== 'DELETED'" :disabled="busy" @click="remove(f)">{{ tr('删除记忆', 'Delete memory') }}</button></div></article>
      <form v-if="editing" class="correction" @submit.prevent="saveCorrection"><label class="field">{{ tr('更正后的用户原话', 'Corrected statement') }}<input v-model="correction" maxlength="500" :disabled="busy"></label><div class="toolbar"><button :disabled="busy || !correction.trim()">{{ tr('提交更正待确认', 'Submit correction for confirmation') }}</button><button type="button" :disabled="busy" @click="editing = null">{{ tr('取消更正', 'Cancel correction') }}</button></div></form>
      <form class="context-preview" @submit.prevent="testContext"><h3>{{ tr('核对本次回答的记忆上下文', 'Check memory used in this answer') }}</h3><label class="field">{{ tr('测试问题', 'Test question') }}<input v-model="previewQuestion" maxlength="2000" :disabled="busy"></label><div class="toolbar"><label>{{ tr('模型模式', 'Model mode') }} <select v-model="mode" :disabled="busy"><option value="demo">{{ tr('规则演示（非语义）', 'Rule demo (not semantic)') }}</option><option value="real">{{ tr('真实生成与支持性检查', 'Live generation and support check') }}</option></select></label><button :disabled="busy || !previewQuestion.trim()">{{ tr('试用此用户上下文', 'Try this profile context') }}</button></div></form>
      <KnowledgeAnswerCard v-if="preview" :payload="preview" />
    </div>
  </details>
</template>
<style scoped>
.memory-panel{flex-shrink:0;border:1px solid var(--color-border);border-radius:10px;background:var(--color-bg);font-size:12px;line-height:1.7}.memory-panel>summary{padding:11px 14px;cursor:pointer;color:var(--color-primary);font-weight:600}.memory-panel>summary span{float:right;font-weight:400;color:var(--color-text-secondary)}.memory-body{padding:0 16px 16px;max-height:46vh;overflow:auto}.boundary{color:var(--color-text-secondary);margin-bottom:12px}.toolbar{display:flex;gap:9px;align-items:center;flex-wrap:wrap}.field{display:grid;gap:5px;margin:12px 0}.field input{width:100%;min-width:0;padding:9px 11px;border:1px solid var(--color-border);border-radius:7px;color:var(--color-text);background:var(--color-bg)}select,button{padding:6px 9px;border:1px solid var(--color-border);border-radius:6px;background:var(--color-bg);color:var(--color-primary)}button:disabled{opacity:.45;cursor:not-allowed}.primary{background:var(--color-primary);color:white}.fact,.recall,.correction,.context-preview{margin-top:12px;padding:12px;border:1px solid var(--color-border-light);border-radius:8px}.fact .toolbar{justify-content:space-between}.fact .actions{justify-content:flex-start;margin-top:9px}.quote{margin:6px 0}.state,small{font-size:11px;color:var(--color-text-secondary);overflow-wrap:anywhere}.error{color:var(--color-error)}h3{font-size:13px}button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible{outline:2px solid var(--color-primary);outline-offset:2px}
</style>
