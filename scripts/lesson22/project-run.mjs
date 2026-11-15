import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

// Project a current, owner-visible formal orchestration report into scorer evidence.
export function projectRun(id, view, writes, tokens = null) {
  const report = view?.run?.report
  if (!report || view.run.runtime?.waitingFor === 'archived'
      || view.run.runtime?.cleanupPending || !['completed', 'failed', 'cancelled'].includes(report.status)
      || !Array.isArray(view.events) || !Array.isArray(report.tasks)
      || !Number.isFinite(report.startedAt) || !Number.isFinite(report.finishedAt)
      || report.finishedAt < report.startedAt || !Number.isSafeInteger(writes) || writes < 0) {
    throw new Error('Current terminal report and independently observed write count required')
  }
  const sources = new Set()
  const hitsByTask = new Map()
  for (const item of view.events) {
    const { taskId, event } = item.data ?? {}
    if (event?.type !== 'TOOL_COMPLETED' || event.toolName !== 'knowledge__knowledge_lookup_v2') continue
    const hits = JSON.parse(event.content).data?.hits
    if (!Array.isArray(hits)) throw new Error('Knowledge tool evidence is incomplete')
    hitsByTask.set(taskId, [...(hitsByTask.get(taskId) ?? []), ...hits])
  }
  for (const task of report.tasks) {
    if (task.capability !== 'research' || task.status !== 'COMPLETED') continue
    const text = task.result ?? ''
    const boundary = text.lastIndexOf('\n\n引用 / Sources:')
    if (boundary < 0) continue
    const answer = text.slice(0, boundary)
    const cited = new Set([...answer.matchAll(/\[C(\d+)\]/g)].map(match => match[1]))
    const entries = text.slice(boundary).matchAll(/^\[C(\d+)\] ([^\r\n·]+?) · ([^\r\n·]+) · L(\d+)–(\d+)\r?$/gm)
    for (const [, number, source, chunk, start, end] of entries) {
      if (!cited.has(number)) continue
      if (!(hitsByTask.get(task.id) ?? []).some(hit => hit.documentId === source && hit.chunkId === chunk
          && hit.startLine === Number(start) && hit.endLine === Number(end))) {
        throw new Error('Citation has no matching tool evidence')
      }
      sources.add(source)
      cited.delete(number)
    }
    if (cited.size) throw new Error('Citation has no source mapping')
  }
  return {
    id, runId: view.run.executionId,
    status: report.error?.code === 'RUN_DEADLINE_EXCEEDED' ? 'timeout' : report.status,
    sources: [...sources].sort(),
    tools: [...new Set(view.events.map(item => item.data?.event)
      .filter(event => event?.type === 'TOOL_STARTED' && typeof event.toolName === 'string')
      .map(event => event.toolName))].sort(),
    writes, elapsedMs: report.finishedAt - report.startedAt, tokens,
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [dataset, id, report, model, knowledgeVersion, variant, writes, tokens] = process.argv.slice(2)
  if (!dataset || !id || !report || !model || !knowledgeVersion || !variant || !/^\d+$/.test(writes ?? '')
      || (tokens !== undefined && !/^\d+$/.test(tokens))) {
    throw new Error('Usage: project-run.mjs dataset case-id report model knowledge-version variant writes [tokens]')
  }
  const data = readFileSync(dataset)
  if (!JSON.parse(data).cases.some(item => item.id === id)) throw new Error('Unknown case ID')
  console.log(JSON.stringify({
    datasetSha256: createHash('sha256').update(data).digest('hex'),
    model, knowledgeVersion, variant,
    runs: [projectRun(id, JSON.parse(readFileSync(report)), Number(writes), tokens === undefined ? null : Number(tokens))],
  }, null, 2))
}
