<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, toRef } from 'vue'
import { locale } from '../i18n'
import { readSkillCatalogue, skillsRequest, createSkillServiceReader } from '../api/skills'
import type { SkillCatalogue } from '../api/skills'
import { useSkillPanelExpansion } from '../utils/skillPanelState'
const props = defineProps<{ modelValue: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [string] }>()
const expanded = useSkillPanelExpansion(toRef(props, 'disabled'), () => { void refreshService(false) })
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const catalogue = ref<SkillCatalogue | null>(null), error = ref(''), loading = ref(false)
let disposed = false
const controller = new AbortController()
const serviceReader = createSkillServiceReader()
const service = ref<{ state: string; toolCount: number } | null>(null), serviceLoading = ref(false), serviceError = ref('')
const serviceLabel = computed(() => ({ READY: tr('MCP 服务已就绪', 'MCP service ready'), UNAVAILABLE: tr('MCP 服务不可用', 'MCP service unavailable'), CONNECTING: tr('MCP 正在连接', 'MCP connecting'), CLOSED: tr('MCP 连接已关闭', 'MCP connection closed'), NEW: tr('MCP 尚未连接', 'MCP not connected') }[service.value?.state || ''] || tr('MCP 状态待确认', 'MCP status unknown')))
async function refreshService(rediscover = false) {
  serviceLoading.value = true; serviceError.value = ''
  try { const value = await serviceReader.read(rediscover); if (!disposed && value) service.value = value }
  catch (cause) { if (!disposed) { service.value = null; serviceError.value = cause instanceof Error ? cause.message : 'MCP_UNAVAILABLE' } }
  finally { if (!disposed) serviceLoading.value = false }
}
const selected = computed(() => catalogue.value?.versions.find(v => v.definition.version === (props.modelValue || catalogue.value?.defaultVersion)))
const valid = computed(() => !!selected.value?.enabled && selected.value.definition.state === 'ACTIVE')
async function refresh() {
  loading.value = true; error.value = ''
  try {
    const value = readSkillCatalogue(await skillsRequest(controller.signal))
    if (!value) throw new Error('SKILL_INVALID_CATALOGUE')
    if (!disposed) catalogue.value = value
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : 'SKILL_UNAVAILABLE' }
  finally { if (!disposed) loading.value = false }
}
const stepLabel = (s: string) => ({ CLARIFY: tr('澄清问题', 'Clarify'), LOOKUP: tr('查询资料', 'Look up'), VERIFY_ADVISE: tr('核验证据与建议', 'Verify and advise'), DRAFT: tr('形成待审批草稿', 'Prepare approval draft') }[s] || s)
onMounted(() => { void refresh(); void refreshService(false) })
onUnmounted(() => { disposed = true; controller.abort(); serviceReader.close() })
</script>
<template>
  <details class="skill-panel" :open="expanded" @toggle="expanded = ($event.target as HTMLDetailsElement).open">
    <summary><strong>{{ tr('IT 工单分析 · Skill', 'IT ticket analysis · Skill') }}</strong><span>{{ selected?.definition.version || '—' }} · {{ valid ? tr('策略已启用', 'Policy enabled') : tr('策略状态待确认', 'Policy unavailable') }} · {{ serviceLabel }}</span></summary>
    <div class="skill-body">
      <div class="selection"><label>{{ tr('下一次请求使用', 'Use for the next request') }}<select :value="modelValue" :disabled="disabled || loading" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)"><option value="">{{ tr('服务端默认版本', 'Server default version') }}{{ catalogue ? ` (${catalogue.defaultVersion})` : '' }}</option><option v-for="v in catalogue?.versions || []" :key="v.definition.version" :value="v.definition.version">{{ v.definition.version }} · {{ v.enabled && v.definition.state === 'ACTIVE' ? tr('已启用', 'Enabled') : tr('已停用', 'Disabled') }}</option></select></label><button :disabled="loading || disabled" @click="refresh">{{ loading ? tr('读取中…', 'Loading…') : tr('刷新策略列表', 'Refresh policies') }}</button></div>
      <p class="boundary">{{ tr('仅分析与生成草稿，尚未创建工单。切换只影响新请求，历史版本保持原样。', 'Analysis and drafts only; no ticket is created. Switching affects new requests; history retains its original version.') }}</p>
      <div class="selection service"><p role="status">{{ serviceLabel }}<span v-if="service"> · {{ tr('已发现工具', 'Discovered tools') }} {{ service.toolCount }}</span></p><button :disabled="serviceLoading || disabled" @click="refreshService(true)">{{ serviceLoading ? tr('连接中…', 'Connecting…') : tr('刷新工具发现', 'Refresh tool discovery') }}</button></div>
      <p v-if="serviceError" class="error" role="alert">{{ serviceError }}</p>
      <p v-if="error" class="error" role="alert">{{ tr('策略列表读取失败', 'Cannot read policy catalogue') }} · {{ error }}</p>
      <p v-else-if="catalogue && !valid" class="error" role="alert">{{ tr('所选版本不存在或已停用；服务端将拒绝执行。', 'The selected version is missing or disabled; the server will refuse execution.') }}</p>
      <details v-if="selected" class="definition"><summary>{{ tr('查看版本、步骤与只读工具范围', 'Inspect version, steps and read-only tools') }}</summary><p>{{ selected.definition.description }}</p><ol><li v-for="s in selected.definition.steps" :key="s">{{ stepLabel(s) }}</li></ol><p>{{ tr('预算上限', 'Budget limit') }} {{ selected.definition.budgetMs }} ms · {{ selected.definition.outputSchemaVersion }}</p><ul><li v-for="tool in selected.definition.tools" :key="tool.exposedName"><code>{{ tool.exposedName }}</code><p>{{ tool.serverId }} / {{ tool.originalName }} · {{ tool.schemaVersion }} · {{ tr('只读', 'Read only') }}</p><small>SHA-256 {{ tool.schemaSha256 }}</small></li></ul><small>{{ tr('定义摘要', 'Definition digest') }} {{ selected.definition.sha256 }}</small><p>{{ tr('发布、启停与默认版本由可信运维配置管理；本页不提供管理写入。', 'Publication, availability and defaults are managed by trusted operations configuration. This page is read only.') }}</p></details>
    </div>
  </details>
</template>
<style scoped>
.skill-panel{flex-shrink:0;border:1px solid var(--color-border);border-radius:10px;background:var(--color-surface);font-size:12px;max-height:37vh;overflow:auto}.skill-panel>summary{padding:12px;display:flex;gap:12px;align-items:center;flex-wrap:wrap;cursor:pointer}.skill-panel>summary span{margin-left:auto;color:var(--color-primary);font-size:11px}.skill-body{padding:0 14px 12px;line-height:1.8}.selection{display:flex;align-items:end;gap:12px}label{display:grid;gap:4px;flex:1;min-width:0;color:var(--color-text-secondary)}select,button{padding:7px 10px;border:1px solid var(--color-border);border-radius:6px;background:var(--color-bg);color:var(--color-text);font:inherit}button{color:var(--color-primary);cursor:pointer;white-space:nowrap}button:disabled{opacity:.5;cursor:wait}.boundary{margin-top:10px;color:var(--color-text-secondary)}.definition{border-top:1px solid var(--color-border-light);margin-top:10px;padding-top:8px}.definition summary{cursor:pointer}.definition ol,.definition ul{padding-left:20px}.definition li{margin:6px 0}.definition p{margin:6px 0}.definition code,.definition small{overflow-wrap:anywhere}.definition small{color:var(--color-text-tertiary)}.error{color:var(--color-error);overflow-wrap:anywhere}summary:focus-visible,button:focus-visible,select:focus-visible{outline:2px solid var(--color-primary);outline-offset:3px}@media(max-width:680px){.selection{align-items:stretch;flex-direction:column}.skill-panel{max-height:32vh}.skill-panel>summary span{margin-left:0}}
</style>
