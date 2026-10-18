<template>
  <details class="execution-flow" :class="flow.status" :open="flow.status === 'running'">
    <summary class="flow-summary">
      <span class="flow-symbol" :class="{ spinning: flow.status === 'running' }" aria-hidden="true">✦</span>
      <span class="flow-heading">{{ t('flow.title') }}</span>
      <span class="flow-state" role="status">{{ statusLabel }}</span>
      <span class="flow-duration">{{ duration }}</span>
      <svg class="flow-chevron" aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m6 9 6 6 6-6"/></svg>
      <span v-if="toolNames.length" class="flow-tools-summary">
        <span v-for="name in toolNames" :key="name" class="tool-chip">{{ name }}</span>
      </span>
    </summary>

    <div class="flow-body">
      <p v-if="flow.restored" class="flow-waiting">{{ t('flow.restoredNote') }}</p>
      <p v-if="!flow.steps.length" class="flow-waiting">{{ t('flow.connecting') }}</p>
      <section v-for="step in flow.steps" :key="step.number" class="execution-step">
        <div class="step-heading">
          <span class="step-marker" :class="step.status" aria-hidden="true"></span>
          <span>{{ t('flow.step') }} {{ step.number }}</span>
          <span v-if="step.status === 'running'" class="step-live">{{ t('flow.updating') }}</span>
        </div>
        <div v-if="step.reasoning" class="reasoning-text">{{ step.reasoning }}<span v-if="step.status === 'running' && flow.phase === 'thinking'" class="reasoning-cursor" aria-hidden="true"></span></div>
        <p v-else-if="step.status === 'running' && !step.tools.length" class="flow-waiting">{{ t('flow.waitingModel') }}</p>

        <div v-for="tool in step.tools" :key="tool.id" class="tool-card" :class="tool.status">
          <div class="tool-heading">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m8 8-4 4 4 4m8-8 4 4-4 4m-3-10-2 20"/></svg>
            <code class="tool-name">{{ tool.name }}</code>
            <span class="tool-status"><span v-if="tool.status === 'running'" class="tool-spinner" aria-hidden="true"></span>{{ t(`flow.tool.${tool.status}`) }}</span>
            <span v-if="tool.durationMs != null" class="tool-duration">{{ tool.durationMs }} ms</span>
          </div>
          <details class="tool-details">
            <summary>{{ t('flow.details') }}</summary>
            <div class="tool-detail-content">
              <div class="detail-label">{{ t('flow.arguments') }}</div>
              <dl v-if="argumentEntries(tool.arguments).length" class="argument-list">
                <template v-for="entry in argumentEntries(tool.arguments)" :key="entry.key">
                  <dt>{{ entry.key }}</dt><dd>{{ entry.value }}</dd>
                </template>
              </dl>
              <p v-else class="empty-arguments">{{ tool.arguments && tool.arguments !== '{}' ? tool.arguments : t('flow.noArguments') }}</p>
              <template v-if="tool.result">
                <div class="detail-label">{{ t('flow.result') }}</div>
                <template v-if="resultEnvelope(tool.result)">
                  <div class="result-summary" :class="{ failed: !resultEnvelope(tool.result)!.success }">
                    <code>{{ resultEnvelope(tool.result)!.code }}</code>
                    <span>{{ resultEnvelope(tool.result)!.message }}</span>
                  </div>
                  <dl v-if="resultFields(resultEnvelope(tool.result)!.data).length" class="result-fields">
                    <template v-for="field in resultFields(resultEnvelope(tool.result)!.data)" :key="field.key">
                      <dt>{{ fieldLabel(field.key) }}</dt><dd>{{ field.value }}</dd>
                    </template>
                  </dl>
                  <p v-else class="empty-arguments">{{ t('flow.noResultData') }}</p>
                  <p v-if="resultEnvelope(tool.result)!.displayTruncated" class="empty-arguments">{{ t('flow.resultTruncated') }}</p>
                </template>
                <pre v-else class="tool-result">{{ formatResult(tool.result) }}</pre>
              </template>
            </div>
          </details>
        </div>
      </section>
      <p v-if="flow.error" class="flow-error">{{ flow.error }}</p>
      <p v-if="flow.endReason" class="end-reason">{{ t('flow.endReason') }}：{{ t(`flow.reason.${flow.endReason}`) }}</p>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { t } from '../i18n'
import type { AgentFlow, ToolResult } from '../types/agent'

const props = defineProps<{ flow: AgentFlow }>()
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined

watch(() => props.flow.status, (status) => {
  clearInterval(timer)
  if (status === 'running') timer = setInterval(() => { now.value = Date.now() }, 500)
}, { immediate: true })
onUnmounted(() => clearInterval(timer))

const duration = computed(() => `${Math.max(0, ((props.flow.finishedAt ?? now.value) - props.flow.startedAt) / 1000).toFixed(1)} s`)
const toolNames = computed(() => [...new Set(props.flow.steps.flatMap(step => step.tools.map(tool => tool.name)))])
const statusLabel = computed(() => {
  if (props.flow.status !== 'running') {
    if (props.flow.endReason) return t(`flow.reason.${props.flow.endReason}`)
    return t(`flow.run.${props.flow.status}`)
  }
  return t(`flow.phase.${props.flow.phase}`)
})

function readable(value: unknown): string {
  if (value == null) return '—'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

function resultEnvelope(value: string): ToolResult | null {
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed.success === 'boolean' && typeof parsed.code === 'string'
      && typeof parsed.message === 'string' && 'data' in parsed ? parsed : null
  } catch { return null }
}

function resultFields(value: unknown, prefix = ''): { key: string; value: string }[] {
  if (value == null) return []
  if (typeof value !== 'object') return [{ key: prefix || 'value', value: readable(value) }]
  return Object.entries(value).flatMap(([key, item]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return item != null && typeof item === 'object' ? resultFields(item, path) : [{ key: path, value: readable(item) }]
  })
}

function fieldLabel(key: string): string {
  const label = t(`flow.field.${key}`)
  return label === `flow.field.${key}` ? key : label
}

function argumentEntries(value: string): { key: string; value: string }[] {
  try {
    const parsed = JSON.parse(value)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
    return Object.entries(parsed).map(([key, item]) => ({ key, value: readable(item) }))
  } catch { return [] }
}

function formatResult(value: string): string {
  try {
    const parsed = JSON.parse(value)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.entries(parsed).map(([key, item]) => `${key}: ${readable(item)}`).join('\n')
    }
    return readable(parsed)
  } catch { return value }
}
</script>

<style scoped>
.execution-flow { margin-bottom: 14px; color: var(--color-text-secondary); }
.flow-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; cursor: pointer; list-style: none; font-size: 12px; user-select: none; }
.flow-summary::-webkit-details-marker { display: none; }
.flow-summary:focus-visible, .tool-details summary:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 4px; border-radius: 4px; }
.flow-symbol { color: var(--color-primary); font-size: 16px; }
.flow-heading { color: var(--color-text); font-weight: 600; }
.flow-state { color: var(--color-text-secondary); }
.flow-duration { margin-left: auto; font-variant-numeric: tabular-nums; font-size: 11px; color: var(--color-text-tertiary); }
.flow-chevron { flex-shrink: 0; transition: transform 160ms; }
details[open] > .flow-summary .flow-chevron { transform: rotate(180deg); }
.flow-tools-summary { display: flex; flex-wrap: wrap; flex-basis: 100%; gap: 5px; padding-left: 24px; }
.tool-chip { padding: 1px 7px; border: 1px solid var(--color-border); border-radius: 5px; color: var(--color-primary-hover); background: var(--color-surface); font: 11px/1.7 ui-monospace, monospace; }
.flow-body { margin: 14px 0 0 7px; padding-left: 17px; border-left: 1px solid var(--color-border); }
.execution-step + .execution-step { margin-top: 18px; }
.step-heading { display: flex; align-items: center; gap: 7px; margin-bottom: 9px; font-size: 11px; font-weight: 600; }
.step-marker { width: 5px; height: 5px; border-radius: 50%; background: var(--color-success); }
.step-marker.running { background: var(--color-warning); box-shadow: 0 0 0 3px var(--color-warning-light); }
.step-marker.error { background: var(--color-error); }
.step-marker.interrupted { background: var(--color-text-tertiary); }
.step-live { font-weight: 400; color: var(--color-text-tertiary); }
.reasoning-text { font-size: 13px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; }
.reasoning-cursor { display: inline-block; width: 4px; height: 12px; margin-left: 3px; background: var(--color-primary); vertical-align: middle; animation: pulse 1s infinite; }
.flow-waiting { font-size: 12px; line-height: 1.8; color: var(--color-text-tertiary); }
.tool-card { margin-top: 11px; border: 1px solid var(--color-border); background: var(--color-surface); border-radius: 9px; overflow: hidden; }
.tool-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; padding: 10px 12px; font-size: 11px; }
.tool-heading > svg { color: var(--color-primary); flex-shrink: 0; }
.tool-name { color: var(--color-text); font-size: 12px; font-weight: 600; overflow-wrap: anywhere; }
.tool-status { display: inline-flex; align-items: center; gap: 5px; margin-left: auto; color: var(--color-success); font-size: 10px; }
.requested .tool-status, .running .tool-status { color: var(--color-warning); }
.error .tool-status, .flow-error, .execution-flow.error > .flow-summary .flow-state { color: var(--color-error); }
.interrupted .tool-status { color: var(--color-text-tertiary); }
.tool-duration { font-size: 10px; color: var(--color-text-tertiary); font-variant-numeric: tabular-nums; }
.tool-details { border-top: 1px solid var(--color-border-light); }
.tool-details > summary { padding: 7px 12px; cursor: pointer; font-size: 11px; }
.tool-detail-content { padding: 0 12px 12px; }
.detail-label { font-size: 10px; text-transform: uppercase; letter-spacing: .05em; margin: 10px 0 5px; color: var(--color-text-tertiary); }
.argument-list { display: grid; grid-template-columns: minmax(50px, 110px) minmax(0, 1fr); gap: 5px 10px; font-size: 12px; }
.argument-list dt { font-family: ui-monospace, monospace; color: var(--color-text-secondary); overflow-wrap: anywhere; }
.argument-list dd { margin: 0; color: var(--color-text); white-space: pre-wrap; overflow-wrap: anywhere; }
.tool-result { margin: 0; font: 12px/1.7 ui-monospace, monospace; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 260px; overflow-y: auto; color: var(--color-text); }
.empty-arguments { font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; }
.flow-error { font-size: 12px; margin-top: 12px; white-space: pre-wrap; overflow-wrap: anywhere; }
.result-summary { display: flex; align-items: baseline; gap: 9px; font-size: 12px; color: var(--color-success); }
.result-summary code { font-size: 10px; padding: 2px 5px; border: 1px solid currentColor; border-radius: 4px; }
.result-summary.failed { color: var(--color-error); }
.result-fields { display: grid; grid-template-columns: minmax(65px, 110px) minmax(0, 1fr); gap: 7px 12px; font-size: 12px; margin-top: 10px; max-height: 300px; overflow: auto; }
.result-fields dt { color: var(--color-text-tertiary); overflow-wrap: anywhere; }
.result-fields dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--color-text); }
.end-reason { margin-top: 14px; font-size: 11px; color: var(--color-text-tertiary); }
.spinning { animation: turn 3s linear infinite; }
.tool-spinner { width: 9px; height: 9px; border: 1px solid currentColor; border-right-color: transparent; border-radius: 50%; animation: turn .8s linear infinite; }
@keyframes turn { to { transform: rotate(360deg); } }
@keyframes pulse { 50% { opacity: .25; } }
@media (prefers-reduced-motion: reduce) { .spinning, .tool-spinner, .reasoning-cursor { animation: none; } }
</style>
