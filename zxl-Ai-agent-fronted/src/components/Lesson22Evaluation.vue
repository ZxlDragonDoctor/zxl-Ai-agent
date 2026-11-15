<script setup lang="ts">
import { computed, ref } from 'vue'
import bundled from '../../../docs/images/lesson22-live-2026-11-15/report.json'

defineProps<{ zh: boolean }>()

type Row = { id: string; errors: string[] }
type Report = { datasetVersion: string; datasetSha256: string; split: string; model: string;
  knowledgeVersion: string; variant: string; results: Row[] }

const comparison = ref<{ baseline: Report; candidate: Report } | null>(null)
const singleReport = ref<Report | null>(null)
const invalid = ref(false)
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

async function importReport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  input.value = ''
  const version = ++importVersion
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
      {{ zh ? '导入评分报告或对照 JSON' : 'Import evaluation report or comparison JSON' }}
      <input type="file" accept=".json,application/json" @change="importReport">
    </label>
    <p v-if="invalid" role="alert">{{ zh ? '文件格式无效，或两份报告的评估条件不一致。' : 'Invalid report file or mismatched evaluation conditions.' }}</p>
    <template v-if="singleReport">
      <p role="status">
        {{ singleReport.variant }} · {{ singleReport.split }} ·
        {{ reportSummary(singleReport, zh) }}
      </p>
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
[role="alert"] { color: #992b25; }
</style>
