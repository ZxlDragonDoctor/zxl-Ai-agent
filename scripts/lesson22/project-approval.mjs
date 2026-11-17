export function projectApproval(id, observation) {
  const { response, row, runId, elapsedMs } = observation
  if (!row || !response || response.proposalId !== row.proposalId || !runId
      || !Number.isSafeInteger(row.writes) || row.writes < 0
      || !Number.isFinite(elapsedMs) || elapsedMs < 0 || row.outcome !== response.outcome
      || !Number.isSafeInteger(row.version) || row.version < 1) {
    throw new Error('A matching API proposal and PostgreSQL observation are required')
  }
  return {
    id, runId, proposalId: row.proposalId,
    status: row.outcome === 'PENDING' && row.version === 1 && row.reviewerAbsent
      ? 'pending_approval' : 'failed',
    sources: [], tools: [], writes: row.writes, elapsedMs, tokens: null,
  }
}
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'


if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [file, id, observation, model, knowledgeVersion, variant] = process.argv.slice(2)
  const bytes = readFileSync(file)
  if (!JSON.parse(bytes).cases.some(item => item.id === id && item.category === 'approval')
      || !model || !knowledgeVersion || !variant) throw new Error('Approval case and pinned metadata required')
  console.log(JSON.stringify({
    datasetSha256: createHash('sha256').update(bytes).digest('hex'),
    model, knowledgeVersion, variant,
    runs: [projectApproval(id, JSON.parse(readFileSync(observation)))],
  }, null, 2))
}
