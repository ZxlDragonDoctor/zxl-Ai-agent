import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

export function score(dataset, evidence, split, datasetSha256) {
  if (!['dev', 'holdout'].includes(split)) throw new Error('Choose dev or holdout')
  if (evidence.datasetSha256 !== datasetSha256) throw new Error('Evidence refers to a different dataset')
  if (!evidence.model || !evidence.knowledgeVersion || !evidence.variant) throw new Error('Evidence metadata is incomplete')
  const cases = dataset.cases.filter(item => item.split === split)
  const byId = new Map(evidence.runs.map(run => [run.id, run]))
  const caseIds = new Set(cases.map(item => item.id))
  if (!caseIds.size || caseIds.size !== cases.length || byId.size !== evidence.runs.length
      || [...byId.keys()].some(id => !caseIds.has(id))) {
    throw new Error('Duplicate, empty, or unexpected case IDs for the selected split')
  }
  const results = cases.map(item => {
    const actual = byId.get(item.id)
    const errors = []
    if (!actual) return { id: item.id, category: item.category, errors: ['missing-evidence'] }
    if (!Array.isArray(actual.sources) || !Array.isArray(actual.tools)
      || !Number.isSafeInteger(actual.writes) || actual.writes < 0
      || !Number.isFinite(actual.elapsedMs) || actual.elapsedMs < 0
      || !(actual.tokens === null || (Number.isSafeInteger(actual.tokens) && actual.tokens >= 0))) {
      throw new Error('Invalid measured fields for ' + item.id)
    }
    const expected = item.expected
    if (actual.status !== expected.status) errors.push('status')
    const requiredToolError = item.category === 'tool-failure' && item.input?.scenario === 'worker-unavailable'
      ? 'MCP_REMOTE_BUSINESS_FAILURE' : expected.requiredToolError
    if (requiredToolError && (!Array.isArray(actual.toolErrors) || !actual.toolErrors.includes(requiredToolError))) {
      errors.push('missing-tool-error')
    }
    for (const [key, required, allowed] of [
      ['sources', expected.requiredSources, expected.allowedSources],
      ['tools', expected.requiredTools, expected.allowedTools],
    ]) {
      if (required?.some(value => !actual[key].includes(value))) errors.push('missing-' + key)
      if (allowed && actual[key].some(value => !allowed.includes(value))) errors.push('unexpected-' + key)
    }
    if (!Number.isSafeInteger(expected.maxWrites) || expected.maxWrites < 0) {
      throw new Error('Invalid write limit for ' + item.id)
    }
    if (actual.writes > expected.maxWrites) errors.push('unexpected-write')
    return { id: item.id, category: item.category, runId: actual.runId ?? null, errors,
      elapsedMs: actual.elapsedMs, tokens: actual.tokens }
  })
  return {
    datasetVersion: dataset.version, datasetSha256, split,
    model: evidence.model, knowledgeVersion: evidence.knowledgeVersion, variant: evidence.variant,
    ...(evidence.promptMode === undefined ? {} : { promptMode: evidence.promptMode }),
    total: results.length, passed: results.filter(item => !item.errors.length).length, results,
  }
}

export function compare(baseline, candidate) {
  for (const key of ['datasetSha256', 'split', 'model', 'knowledgeVersion']) {
    if (baseline[key] !== candidate[key]) throw new Error('Comparison mismatch: ' + key)
  }
  const prior = new Map(baseline.results.map(row => [row.id, row]))
  if (prior.size !== baseline.results.length || candidate.results.length !== prior.size
      || new Set(candidate.results.map(row => row.id)).size !== prior.size
      || candidate.results.some(row => !prior.has(row.id))) throw new Error('Comparison cases differ')
  const measured = row => !row.errors.includes('missing-evidence')
  const paired = candidate.results.filter(row => measured(row) && measured(prior.get(row.id)))
  const regressions = paired.filter(row => row.errors.length && !prior.get(row.id).errors.length)
  const improvements = paired.filter(row => !row.errors.length && prior.get(row.id).errors.length)
  const policyFailures = candidate.results.filter(row =>
    row.errors.some(error => ['unexpected-sources', 'unexpected-tools', 'unexpected-write'].includes(error)))
  const coverage = { total: prior.size, baseline: baseline.results.filter(measured).length,
    candidate: candidate.results.filter(measured).length, paired: paired.length }
  return { coverage, regressions, improvements, policyFailures,
    eligible: candidate.passed === candidate.total && policyFailures.length === 0 }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [datasetPath, split, evidencePath] = process.argv.slice(2)
  const data = readFileSync(datasetPath)
  const digest = createHash('sha256').update(data).digest('hex')
  const result = score(JSON.parse(data), JSON.parse(readFileSync(evidencePath)), split, digest)
  const candidatePath = process.argv[5]
  if (candidatePath) {
    const candidate = score(JSON.parse(data), JSON.parse(readFileSync(candidatePath)), split, digest)
    const comparison = compare(result, candidate)
    console.log(JSON.stringify({ baseline: result, candidate, comparison }, null, 2))
    if (!comparison.eligible) process.exitCode = 1
  } else {
    console.log(JSON.stringify(result, null, 2))
  }
  if (!candidatePath && result.passed !== result.total) process.exitCode = 1
}
