<script setup lang="ts">
import { ref } from 'vue'
import bundled from '../../../docs/images/lesson22-live-2026-11-15/report.json'

defineProps<{ zh: boolean }>()

type Row = { id: string; errors: string[] }
type Report = { datasetVersion: string; datasetSha256: string; split: string; model: string;
  knowledgeVersion: string; variant: string; results: Row[] }

const comparison = ref<{ baseline: Report; candidate: Report } | null>(null)
const invalid = ref(false)
let importVersion = 0

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
  try {
    if (file.size > 1024 * 1024) throw new Error('Report too large')
    const text = await file.text()
    const { baseline: before, candidate: after } = JSON.parse(text)
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

function passed(report: Report) {
  return report.results.filter(row => !row.errors.length).length
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
      {{ zh ? '导入评分对照 JSON' : 'Import evaluation comparison JSON' }}
      <input type="file" accept=".json,application/json" @change="importReport">
    </label>
    <p v-if="invalid" role="alert">{{ zh ? '文件格式无效，或两份报告的评估条件不一致。' : 'Invalid report file or mismatched evaluation conditions.' }}</p>
    <template v-if="comparison">
      <p role="status">
        {{ zh ? '基线' : 'Baseline' }} {{ passed(comparison.baseline) }}/{{ comparison.baseline.results.length }}
        → {{ zh ? '候选' : 'Candidate' }} {{ passed(comparison.candidate) }}/{{ comparison.candidate.results.length }}
      </p>
      <ul>
        <li v-for="row in comparison.candidate.results" :key="row.id">
          <code>{{ row.id }}</code> · {{ row.errors.length ? row.errors.join(', ') : zh ? '通过' : 'Passed' }}
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
