<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { locale } from '../i18n'
import { mcpRequest, readMcpResult } from '../api/mcp'
import type { McpStatus } from '../api/mcp'
import McpToolEvidence from './McpToolEvidence.vue'
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const status = ref<McpStatus | null>(null), error = ref(''), loading = ref(false), querying = ref(false), payload = ref('')
const query = ref('VPN报错401，应该如何处理？'), topK = ref(3), budgetMs = ref(10000), mode = ref('hybrid')
let controller: AbortController | null = null, requestId = 0, disposed = false
const state = computed(() => ({ READY: tr('服务已就绪', 'Ready'), UNAVAILABLE: tr('服务不可用', 'Unavailable'), CLOSED: tr('连接已关闭', 'Closed'), CONNECTING: tr('正在连接', 'Connecting'), DISCOVERING: tr('发现工具中', 'Discovering'), NEW: tr('尚未连接', 'Not connected') }[(status.value?.state || '').toUpperCase()] || status.value?.state || tr('正在读取状态', 'Loading status')))
async function refresh(rediscover = true) {
  loading.value = true; error.value = ''
  try {
    const raw = await mcpRequest(rediscover ? '/refresh' : '/status', rediscover ? {} : undefined) as McpStatus
    if (!raw || typeof raw.state !== 'string' || !Array.isArray(raw.tools) || raw.tools.length > 20) throw new Error('MCP_INVALID_STATUS')
    if (!disposed) status.value = raw
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : 'MCP_UNAVAILABLE' }
  finally { if (!disposed) loading.value = false }
}
async function lookup() {
  const attempt = ++requestId; controller = new AbortController(); querying.value = true; payload.value = ''; error.value = ''
  try {
    const raw = await mcpRequest('/lookup', { query: query.value, topK: topK.value, mode: mode.value, budgetMs: budgetMs.value }, controller.signal)
    const value = JSON.stringify(raw)
    if (!readMcpResult(value)) throw new Error('MCP_INVALID_RESULT')
    if (attempt === requestId && !disposed) payload.value = value
  } catch (cause) { if (attempt === requestId && !disposed) error.value = cause instanceof Error ? cause.message : 'MCP_UNAVAILABLE' }
  finally { if (attempt === requestId && !disposed) { querying.value = false; controller = null } }
}
function cancel() { ++requestId; controller?.abort(); controller = null; querying.value = false; payload.value = ''; error.value = tr('已停止接收；远端任务是否停止以服务端记录为准。', 'Receiving stopped; remote cancellation is subject to the server record.') }
onMounted(() => refresh(false))
onUnmounted(() => { disposed = true; ++requestId; controller?.abort() })
</script>
<template>
  <details class="mcp-panel">
    <summary><span class="status-dot" :class="{ ready: status?.state?.toUpperCase() === 'READY' }" aria-hidden="true"></span><strong>{{ tr('MCP 只读知识工具', 'MCP read-only knowledge tools') }}</strong><span role="status">{{ state }}</span></summary>
    <div class="panel-body">
      <header><p>{{ tr('服务提供工具，Java 校验权限与预算；模型只能调用下方允许的工具。', 'The service exposes tools; Java checks scope and budgets. The model can call only the listed tools.') }}</p><button :disabled="loading || querying" @click="refresh(true)">{{ loading ? tr('发现中…', 'Discovering…') : tr('刷新工具发现', 'Refresh discovery') }}</button></header>
      <p v-if="status?.lastError" class="error">{{ status.lastError }}</p>
      <p v-if="status" class="metadata">{{ status.serverId }} · {{ status.protocolVersion || '—' }} · {{ tr('运行', 'Active') }} {{ status.active ?? 0 }} / {{ tr('等待', 'Queued') }} {{ status.queued ?? 0 }}</p>
      <p v-if="status && !status.tools.length">{{ tr('尚无可用工具；不能将此状态视为查询成功。', 'No available tools. This does not mean a lookup succeeded.') }}</p>
      <details v-for="tool in status?.tools || []" :key="tool.exposedName" class="tool-schema"><summary><code>{{ tool.exposedName }}</code> · {{ tr('只读', 'Read only') }}</summary><p>{{ tool.serverId }} / {{ tool.originalName }} · {{ tool.schemaVersion }}</p><p>SHA-256 {{ tool.schemaSha256 }}</p><pre>{{ JSON.stringify(tool.definition, null, 2) }}</pre></details>
      <details class="experiment"><summary>{{ tr('只读协议实验（不调用模型）', 'Read-only protocol lab (no model call)') }}</summary><p>{{ tr('验证发现与调用；真实模型选择工具请使用下方对话。当前教学身份由服务端固定。', 'Verify discovery and calls. Use the conversation below for real model tool selection. Teaching identity is fixed by the server.') }}</p>
        <p>{{ tr('合法范围：查询最多 2000 字符，topK 1–5，预算 100–10000 毫秒。', 'Allowed range: query up to 2000 characters, topK 1–5, budget 100–10000 ms.') }}</p><form @submit.prevent="lookup"><label>{{ tr('查询', 'Query') }}<input v-model="query" :disabled="querying" maxlength="2000" /></label><div class="fields"><label>topK<input v-model.number="topK" type="number" min="1" max="5" :disabled="querying" /></label><label>{{ tr('检索模式', 'Retrieval mode') }}<select v-model="mode" :disabled="querying"><option value="hybrid">hybrid</option><option value="vector">vector</option><option value="keyword">keyword</option></select></label><label>{{ tr('预算（毫秒）', 'Budget (ms)') }}<input v-model.number="budgetMs" type="number" min="100" max="10000" :disabled="querying" /></label></div><div class="actions"><button type="submit" :disabled="querying || loading">{{ querying ? tr('查询中…', 'Looking up…') : tr('执行只读查询', 'Run read-only lookup') }}</button><button v-if="querying" type="button" @click="cancel">{{ tr('停止接收查询', 'Stop receiving lookup') }}</button></div></form>
        <McpToolEvidence v-if="payload" :payload="payload" />
      </details>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </div>
  </details>
</template>
<style scoped>
.mcp-panel{border:1px solid var(--color-border);border-radius:10px;background:var(--color-surface);font-size:12px;max-height:42vh;overflow:auto;flex-shrink:0}.mcp-panel>summary{display:flex;align-items:center;gap:9px;padding:12px;cursor:pointer;flex-wrap:wrap}.mcp-panel>summary>span:last-child{margin-left:auto;font-size:11px;color:var(--color-text-secondary)}.status-dot{width:7px;height:7px;border-radius:50%;background:var(--color-warning)}.status-dot.ready{background:var(--color-success)}.panel-body{padding:0 14px 14px;line-height:1.8}.panel-body>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.panel-body>header p{max-width:520px;color:var(--color-text-secondary)}.metadata{font-size:11px;color:var(--color-text-secondary);margin:10px 0;overflow-wrap:anywhere}button{padding:6px 10px;border:1px solid var(--color-border);border-radius:6px;color:var(--color-primary);white-space:nowrap;background:var(--color-bg)}button:disabled{opacity:.5}.tool-schema,.experiment{padding:10px 0;border-top:1px solid var(--color-border-light)}summary{cursor:pointer}summary:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid var(--color-primary);outline-offset:3px}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:200px;overflow:auto;font-size:11px}.error{color:var(--color-error);overflow-wrap:anywhere}label{display:grid;gap:4px;margin:9px 0;color:var(--color-text-secondary)}input,select{padding:8px;border:1px solid var(--color-border);border-radius:6px;min-width:0;width:100%;background:var(--color-bg);color:var(--color-text)}.fields{display:grid;grid-template-columns:80px 1fr 1fr;gap:12px}.actions{display:flex;gap:8px;margin:10px 0}@media(max-width:680px){.panel-body>header{display:block}.fields{grid-template-columns:1fr 1fr}.fields label:last-child{grid-column:1/-1}.mcp-panel{max-height:45vh}}
</style>
