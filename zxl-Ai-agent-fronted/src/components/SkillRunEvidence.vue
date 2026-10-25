<script setup lang="ts">
import { computed } from 'vue'
import { locale } from '../i18n'
import type { SkillRun } from '../api/skills'
const props = defineProps<{ run: SkillRun; receivingEnded?: boolean }>()
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const snapshot = computed(() => props.run.snapshot || props.run.result?.snapshot)
const selectedAt = computed(() => snapshot.value ? new Date(snapshot.value.selectedAt).toLocaleString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US') : '—')
const status = (s: string) => ({ RUNNING: tr('执行中', 'Running'), SUCCEEDED: tr('已完成', 'Succeeded'), NEEDS_INPUT: tr('需要补充信息', 'More information needed'), FAILED: tr('执行失败', 'Failed'), CANCELLED: tr('已取消', 'Cancelled'), NO_EVIDENCE: tr('无有效依据', 'No valid evidence'), DENIED: tr('策略拒绝', 'Policy denied'), TIMED_OUT: tr('已超时', 'Timed out'), CAPACITY: tr('服务繁忙', 'Capacity reached') }[s] || s)
const stepLabel = (s: string) => ({ CLARIFY: tr('澄清问题', 'Clarify'), LOOKUP: tr('查询资料', 'Look up'), VERIFY_ADVISE: tr('核验证据与建议', 'Verify and advise'), DRAFT: tr('形成草稿', 'Prepare draft') }[s] || s)
const steps = computed(() => props.run.result?.steps || props.run.steps)
</script>
<template>
  <section class="skill-run" :aria-label="tr('本次 Skill 执行记录', 'Skill execution record')">
    <header><strong>{{ tr('本次业务策略', 'Policy for this run') }} · {{ snapshot?.definition.version || '—' }}</strong><span role="status">{{ run.result ? status(run.result.status) : receivingEnded ? tr('接收已结束，服务端结果待核对', 'Receiving ended; server result not confirmed') : tr('正在执行', 'Running') }}</span></header>
    <p v-if="run.invalidEvent" class="failure" role="alert">{{ tr('部分策略事件格式不受支持，未将其作为成功证据。', 'Some policy events have an unsupported format and are not treated as success evidence.') }}</p>
    <ol class="steps"><li v-for="item in steps" :key="item.step" :class="{ failure: ['FAILED', 'CANCELLED'].includes(item.status) }"><span>{{ stepLabel(item.step) }}</span><span>{{ status(item.status) }} · {{ item.elapsedMs }} ms</span><code v-if="item.code !== 'OK'">{{ item.code }}</code></li></ol>
    <p v-if="run.result && run.result.code !== 'OK'" class="reason">{{ tr('公开诊断', 'Public diagnosis') }} · <code>{{ run.result.code }}</code></p>
    <details v-if="snapshot" class="snapshot"><summary>{{ tr('查看本次固定版本与工具快照', 'Inspect the fixed version and tool snapshot') }}</summary><dl><dt>Skill</dt><dd>{{ snapshot.definition.skillId }} / {{ snapshot.definition.version }}</dd><dt>{{ tr('选择时间', 'Selected at') }}</dt><dd>{{ selectedAt }}</dd><dt>{{ tr('执行器 / 策略', 'Executor / policy') }}</dt><dd>{{ snapshot.executorVersion }} / {{ snapshot.policyVersion }}</dd><dt>{{ tr('定义摘要', 'Definition digest') }}</dt><dd>{{ snapshot.definitionSha256 }}</dd><dt>{{ tr('工具快照摘要', 'Tool snapshot digest') }}</dt><dd>{{ snapshot.toolSnapshotSha256 }}</dd><dt>{{ tr('只读工具', 'Read-only tools') }}</dt><dd>{{ snapshot.definition.tools.map(t => t.exposedName).join(', ') }}</dd></dl></details>
    <details v-if="run.result?.draft" class="draft" open><summary>{{ tr('工单草稿 · 尚未创建 · 需要人工审批', 'Ticket draft · Not created · Human approval required') }}</summary><dl><dt>{{ tr('标题', 'Title') }}</dt><dd>{{ run.result.draft.title }}</dd><dt>{{ tr('问题', 'Problem') }}</dt><dd>{{ run.result.draft.problem }}</dd><dt>{{ tr('处理建议', 'Advice') }}</dt><dd>{{ run.result.draft.advice }}</dd><template v-if="run.result.draft.priority"><dt>{{ tr('优先级', 'Priority') }}</dt><dd>{{ run.result.draft.priority }}</dd></template><dt>{{ tr('输出版本', 'Output version') }}</dt><dd>{{ run.result.draft.schemaVersion }}</dd></dl></details>
  </section>
</template>
<style scoped>
.skill-run{border:1px solid var(--color-border);border-radius:9px;padding:12px;margin-bottom:14px;background:var(--color-bg);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.skill-run header{display:flex;flex-wrap:wrap;gap:8px;justify-content:space-between;color:var(--color-text)}.skill-run header>span{color:var(--color-primary);font-size:11px}.steps{list-style:none;padding:0;margin:10px 0}.steps li{display:flex;flex-wrap:wrap;justify-content:space-between;gap:4px 12px;border-bottom:1px solid var(--color-border-light);padding:5px 0}.steps code{width:100%;font-size:10px}.failure{color:var(--color-error)}.reason{color:var(--color-text-secondary)}details{padding-top:8px}summary{cursor:pointer;color:var(--color-primary)}summary:focus-visible{outline:2px solid var(--color-primary);outline-offset:3px}dl{display:grid;grid-template-columns:110px minmax(0,1fr);gap:6px 10px;padding-top:8px}dt{color:var(--color-text-tertiary)}dd{margin:0;white-space:pre-wrap;color:var(--color-text)}.draft{border-top:1px solid var(--color-border-light);margin-top:9px}.draft>summary{font-weight:600}@media(max-width:680px){dl{grid-template-columns:1fr;gap:2px}dd{margin-bottom:8px}.steps li{display:block}.steps li>span{display:block}}
</style>
