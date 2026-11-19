<script setup lang="ts">
import { computed, ref } from 'vue'
import bundled from '../../../docs/images/lesson22-live-2026-11-15/report.json'
import accessDev from '../../../docs/images/lesson22-access-batch-2026-11-18/dev-report.json'
import accessHoldout from '../../../docs/images/lesson22-access-batch-2026-11-18/holdout-report.json'
import approvalHoldout from '../../../docs/images/lesson22-device-batch-2026-11-18/holdout-report.json'
import keyboardApproval from '../../../docs/images/lesson22-hardware-2026-11-18/report.json'
import emptyDev from '../../../docs/images/lesson22-empty-batch-2026-11-18/report.json'
import controlledDev from '../../../docs/images/lesson22-controlled-2026-11-19/dev-report.json'
import controlledHoldout from '../../../docs/images/lesson22-controlled-2026-11-19/holdout-report.json'

defineProps<{ zh: boolean }>()

type Row = { id: string; errors: string[] }
type Report = { datasetVersion: string; datasetSha256: string; split: string; model: string;
  knowledgeVersion: string; variant: string; results: Row[] }

const comparison = ref<{ baseline: Report; candidate: Report } | null>(null)
const singleReport = ref<Report | null>(null)
const invalid = ref(false)
const savedId = ref('')
const savedReports = [
  { id: 'access-dev', zh: '权限 · 开发集', en: 'Access · Development', report: accessDev },
  { id: 'access-holdout', zh: '权限 · 保留集', en: 'Access · Holdout', report: accessHoldout },
  { id: 'keyboard-approval', zh: '键盘草稿 · 待审批', en: 'Keyboard draft · Pending approval', report: keyboardApproval },
  { id: 'approval-holdout', zh: '门禁卡草稿 · 待审批', en: 'Badge draft · Pending approval', report: approvalHoldout },
  { id: 'empty-dev', zh: '空库拒答 · 失败记录', en: 'Empty knowledge · Recorded failures', report: emptyDev },
  { id: 'controlled-dev', zh: '可控机制测试 · 开发集', en: 'Controlled integration · Development', report: controlledDev },
  { id: 'controlled-holdout', zh: '可控机制测试 · 保留集', en: 'Controlled integration · Holdout', report: controlledHoldout },
]
let importVersion = 0
const pairedRows = computed(() => {
  const reports = comparison.value
  if (!reports) return []
  const beforeById = new Map(reports.baseline.results.map(row => [row.id, row]))
  return reports.candidate.results.map(after => ({ before: beforeById.get(after.id)!, after }))
})
const pairedCount = computed(() => pairedRows.value.filter(({ before, after }) => measured(before) && measured(after)).length)

function readReport(value: Partial<Report>): Report {
  const keys = ['datasetVersion', 'datasetSha256', 'split', 'model', 'knowledgeVersion', 'variant'] as const
  if (!value || keys.some(key => typeof value[key] !== 'string' || !value[key]?.trim())
      || !Array.isArray(value.results) || !value.results.length
      || value.results.length > 500
      || value.results.some(row => !row || typeof row.id !== 'string' || !row.id.trim()
        || !Array.isArray(row.errors) || row.errors.some(error => typeof error !== 'string'))
      || new Set(value.results.map(row => row.id)).size !== value.results.length) {
    throw new Error('Invalid evaluation report')
  }
  return value as Report
}

function loadSavedReport() {
  ++importVersion
  invalid.value = false
  comparison.value = null
  const selected = savedReports.find(item => item.id === savedId.value)
  singleReport.value = selected ? readReport(selected.report) : null
}

async function importReport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  input.value = ''
  const version = ++importVersion
  savedId.value = ''
  invalid.value = false
  comparison.value = null
  singleReport.value = null
  try {
    if (file.size > 1024 * 1024) throw new Error('Report too large')
    const text = await file.text()
    const payload = JSON.parse(text)
    if (Array.isArray(payload?.results)) {
      const report = readReport(payload)
      if (version === importVersion) singleReport.value = report
      return
    }
    const { baseline: before, candidate: after } = payload
    const baseline = readReport(before)
    const candidate = readReport(after)
    const identity = ['datasetVersion', 'datasetSha256', 'split', 'model', 'knowledgeVersion'] as const
    const ids = new Set(baseline.results.map(row => row.id))
    if (identity.some(key => baseline[key] !== candidate[key])
        || candidate.results.length !== ids.size || candidate.results.some(row => !ids.has(row.id))) {
      throw new Error('Comparison inputs differ')
    }
    if (version === importVersion) comparison.value = { baseline, candidate }
  } catch {
    if (version === importVersion) invalid.value = true
  }
}

function measured(row: Row) {
  return !row.errors.includes('missing-evidence')
}

function reportSummary(report: Report, zh: boolean) {
  const observed = report.results.filter(measured)
  const passed = observed.filter(row => !row.errors.length).length
  return zh
    ? `实测 ${observed.length}/${report.results.length} · 通过 ${passed} · 失败 ${observed.length - passed}`
    : `Measured ${observed.length}/${report.results.length} · Passed ${passed} · Failed ${observed.length - passed}`
}

function rowResult(row: Row, zh: boolean) {
  if (!measured(row)) return zh ? '缺少证据' : 'Missing evidence'
  if (!row.errors.length) return zh ? '通过' : 'Passed'
  return (zh ? '失败 · ' : 'Failed · ') + row.errors.join(', ')
}
</script>

<template>
  <section class="evaluation">
    <h3>{{ zh ? '评估报告' : 'Evaluation report' }}</h3>
    <p>{{ zh ? '真实知识查询冒烟' : 'Live knowledge smoke test' }} · {{ bundled.passed }}/{{ bundled.total }} {{ zh ? '通过' : 'passed' }}</p>
    <ul>
      <li v-for="row in bundled.results" :key="row.id">
        <code>{{ row.id }}</code> · {{ row.elapsedMs }} ms · {{ row.tokens }} token
      </li>
    </ul>
    <label>
      {{ zh ? '已保存的验收报告' : 'Saved acceptance reports' }}
      <select v-model="savedId" :aria-label="zh ? '已保存的验收报告' : 'Saved acceptance reports'" @change="loadSavedReport">
        <option value="">{{ zh ? '选择一份报告' : 'Choose a report' }}</option>
        <option v-for="item in savedReports" :key="item.id" :value="item.id">{{ zh ? item.zh : item.en }}</option>
      </select>
    </label>
    <p v-if="savedId" class="evidence-note">{{ zh ? '历史验收快照；不同场景分开记录，不代表整套课程或当前服务全部通过。权限报告没有新增模型调用。' : 'Historical acceptance snapshots, recorded separately by scenario. They do not certify the entire course or current service. Access checks made no new model calls.' }}</p>
    <label>
      {{ zh ? '导入评分报告或对照 JSON' : 'Import evaluation report or comparison JSON' }}
      <input type="file" accept=".json,application/json" @change="importReport">
    </label>
    <p v-if="invalid" role="alert">{{ zh ? '文件格式无效，或两份报告的评估条件不一致。' : 'Invalid report file or mismatched evaluation conditions.' }}</p>
    <template v-if="singleReport">
      <p role="status">
        {{ singleReport.variant }} · {{ singleReport.split }} ·
        {{ reportSummary(singleReport, zh) }}
      </p>
      <p class="evidence-note">{{ zh ? '模型标识 / 知识版本' : 'Model identifier / Knowledge version' }}: {{ singleReport.model }} / {{ singleReport.knowledgeVersion }}</p>
      <ul>
        <li v-for="row in singleReport.results" :key="row.id">
          <code>{{ row.id }}</code> · {{ rowResult(row, zh) }}
        </li>
      </ul>
    </template>
    <template v-if="comparison">
      <div role="status">
        <p>{{ zh ? '基线' : 'Baseline' }} · {{ reportSummary(comparison.baseline, zh) }}</p>
        <p>{{ zh ? '候选' : 'Candidate' }} · {{ reportSummary(comparison.candidate, zh) }}</p>
        <p>{{ zh ? '双方均有实测证据' : 'Measured in both' }} {{ pairedCount }}/{{ pairedRows.length }}</p>
      </div>
      <ul>
        <li v-for="{ before, after } in pairedRows" :key="after.id">
          <code>{{ after.id }}</code> · {{ rowResult(before, zh) }} → {{ rowResult(after, zh) }}
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.evaluation { border: 1px solid #dbe4ef; border-radius: 8px; margin: 14px 0; padding: 12px; color: #294d70; font-size: 13px; }
h3, p { margin: 0 0 8px; }
ul { margin: 0 0 10px; padding-left: 18px; overflow-wrap: anywhere; }
label { display: block; font-weight: 600; }
input { display: block; margin: 6px 0 10px; max-width: 100%; }
select { display: block; width: 100%; margin: 6px 0 10px; padding: 8px; border: 1px solid #b9cbdc; border-radius: 6px; color: inherit; background: #fff; font: inherit; }
.evidence-note { font-size: 12px; line-height: 1.6; color: #52697e; }
[role="alert"] { color: #992b25; }
</style>
