<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { locale } from '../i18n'
import { readMcpResult, validateMcpSource, mcpDiagnosticCode } from '../api/mcp'
import type { McpHit } from '../api/mcp'
import { getKnowledgeDocument } from '../api/knowledge'
const props = defineProps<{ payload: string }>()
const result = computed(() => readMcpResult(props.payload))
const source = ref<McpHit | null>(null), busy = ref(false), error = ref('')
let generation = 0
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
watch(() => props.payload, () => { generation++; source.value = null; error.value = ''; busy.value = false })
async function open(hit: McpHit) {
  const attempt = ++generation; busy.value = true; source.value = null; error.value = ''
  try {
    const detail = await getKnowledgeDocument(hit.documentId)
    if (!validateMcpSource(hit, detail)) throw new Error(tr('来源已删除、换版或原文不匹配，请重新检索。', 'Source deleted, revised or mismatched. Run a new lookup.'))
    if (attempt === generation) source.value = hit
  } catch (cause) { if (attempt === generation) error.value = cause instanceof Error ? cause.message : 'SOURCE_UNAVAILABLE' }
  finally { if (attempt === generation) busy.value = false }
}
</script>
<template>
  <section v-if="result" class="mcp-evidence" :aria-label="tr('MCP 检索结果', 'MCP lookup result')">
    <p class="outcome" :class="{ failure: !result.success }"><code>{{ result.code }}</code> {{ result.message }}</p>
    <p v-if="!result.success && mcpDiagnosticCode(result) !== result.code" class="failure"><code>{{ mcpDiagnosticCode(result) }}</code> · {{ tr('知识存储或检索未完成，请检查服务后重试。', 'Storage or retrieval did not complete. Check the service and retry.') }}</p>
    <p v-if="result.success && !result.data?.hits?.length">{{ tr('查询成功，当前范围内没有匹配资料。', 'Lookup succeeded; no matching current evidence in this scope.') }}</p>
    <div v-for="(hit, index) in result.data?.hits || []" :key="hit.chunkId" class="hit"><button :disabled="busy" @click="open(hit)">{{ tr('查看原文', 'Read source') }} {{ index + 1 }} · {{ hit.documentId }}</button><p>{{ hit.text }}</p><small>{{ tr('行', 'Lines') }} {{ hit.startLine }}–{{ hit.endLine }} · v{{ hit.generation }}</small></div>
    <p v-if="error" role="alert" class="failure">{{ error }}</p>
    <div v-if="source" class="source" role="region" :aria-label="tr('MCP 当前引用原文', 'MCP current source')"><header><strong>{{ source.documentId }}</strong><button @click="source = null">{{ tr('关闭原文', 'Close source') }}</button></header><small>{{ source.revisionId }} · UTF-16 {{ source.startUtf16 }}–{{ source.endUtf16 }}</small><pre>{{ source.text }}</pre></div>
    <details v-if="result.success && result.data"><summary>{{ tr('检索记录', 'Lookup record') }}</summary><small>trace {{ result.data.traceId || '—' }} · {{ result.data.elapsedMs ?? '—' }} ms</small><pre>{{ JSON.stringify({ stages: result.data.stages, degradations: result.data.degradations }, null, 2) }}</pre></details>
  </section>
  <p v-else role="alert" class="failure">{{ tr('工具结果格式无效或超过展示范围，未将内容视为有效引用。', 'Tool result is invalid or exceeds display limits. It is not trusted as evidence.') }}</p>
</template>
<style scoped>
.mcp-evidence{font-size:12px;line-height:1.8;overflow-wrap:anywhere}.outcome{color:var(--color-success)}.failure{color:var(--color-error)}.hit{padding:10px 0;border-bottom:1px solid var(--color-border-light)}.hit p{max-height:160px;overflow:auto;white-space:pre-wrap;margin:8px 0}button{border:1px solid var(--color-border);padding:5px 9px;border-radius:6px;color:var(--color-primary);background:var(--color-bg)}button:disabled{opacity:.5}button:focus-visible,summary:focus-visible{outline:2px solid var(--color-primary);outline-offset:3px}.source{padding:12px;background:var(--color-bg-alt);border-radius:8px;margin:12px 0}.source header{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}small{color:var(--color-text-secondary)}pre{white-space:pre-wrap;overflow-wrap:anywhere}details{margin-top:10px}summary{cursor:pointer}
</style>
