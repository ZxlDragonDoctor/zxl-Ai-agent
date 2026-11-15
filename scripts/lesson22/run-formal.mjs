import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { pathToFileURL } from 'node:url'
import { projectRun } from './project-run.mjs'
import { formalSession } from './formal-session.mjs'

export async function runCases(cases, {
  submit, detail, countWrites, wait = delay, now = Date.now, promptMode = 'research-directed',
}) {
  if (!['original', 'research-directed'].includes(promptMode)) throw new Error('Unknown prompt mode')
  const runs = []
  for (const item of cases) {
    const goal = promptMode === 'research-directed' && ['grounding', 'abstention'].includes(item.category)
      ? 'Use one research task to look up the following employee knowledge question.\n' + item.input.prompt
      : item.input.prompt
    const { executionId } = await submit(goal, item.input.budgetMs)
    if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(executionId)) throw new Error('Invalid execution ID')
    const deadline = now() + item.input.budgetMs + 30_000
    while (now() < deadline) {
      const view = await detail(executionId)
      if (view.run?.executionId !== executionId) throw new Error('Run detail does not match submission')
      if (!view.run.runtime?.cleanupPending && view.run.report?.finishedAt > 0) {
        runs.push(projectRun(item.id, view, await countWrites(view.run.report)))
        break
      }
      await wait(500)
    }
    if (runs.at(-1)?.id !== item.id) throw new Error('No terminal report for ' + item.id)
  }
  return runs
}

async function main() {
  const [datasetPath, split, model, knowledgeVersion, variant, ...ids] = process.argv.slice(2)
  const env = process.env
  const promptMode = env.LESSON22_PROMPT_MODE ?? 'research-directed'
  const base = new URL(env.LESSON22_ORIGIN)
  const tenant = env.LESSON22_TENANT
  if (base.protocol !== 'http:' || base.hostname !== '127.0.0.1' || !base.port
      || base.href !== base.origin + '/' || !/^lesson(?:19-browser|22-[a-z0-9-]+)$/.test(tenant ?? '')
      || !env.LESSON22_LOGIN || !env.LESSON22_PASSWORD || !env.LESSON22_PSQL || !env.PGPORT
      || !model || !knowledgeVersion || !variant || !ids.length || !['dev', 'holdout'].includes(split)) {
    throw new Error('A local acceptance stack, explicit fixture credentials, psql and pinned case IDs are required')
  }
  const bytes = readFileSync(datasetPath)
  const dataset = JSON.parse(bytes)
  const cases = ids.map(id => dataset.cases.find(item => item.id === id && item.split === split))
  if (new Set(ids).size !== ids.length || cases.some(item => !item || item.input.persona !== 'employee'
      || !(item.category === 'grounding' || (item.category === 'budget' && item.input.scenario === 'model-wait')
        || (item.category === 'abstention' && item.input.scenario === 'no-result' && /^lesson22-/.test(tenant))))) {
    throw new Error('Select grounding, model-wait or an abstention case in a dedicated lesson22 tenant')
  }
  const request = await formalSession(base.origin, tenant, env.LESSON22_LOGIN, env.LESSON22_PASSWORD, 'employee')
  function countWrites(report) {
    if (!Number.isSafeInteger(report.startedAt) || !Number.isSafeInteger(report.finishedAt)) {
      throw new Error('Invalid report timestamps')
    }
    const sql = `SELECT count(*) FROM lesson17.approved_ticket WHERE tenant_id='${tenant}' AND created_at
      BETWEEN to_timestamp(${report.startedAt}/1000.0) AND to_timestamp(${report.finishedAt}/1000.0)`
    const count = execFileSync(env.LESSON22_PSQL, ['-XAt', '-w', '-h', '127.0.0.1', '-c', sql],
      { encoding: 'utf8', timeout: 10_000 }).trim()
    if (!/^\d+$/.test(count)) throw new Error('Invalid database write count')
    return Number(count)
  }
  const runs = await runCases(cases, {
    submit: (goal, timeoutMs) => request('/ai/multiagent/runs', { goal, timeoutMs }),
    detail: id => request('/ai/multiagent/runs/' + id), countWrites, promptMode,
  })
  console.log(JSON.stringify({ datasetSha256: createHash('sha256').update(bytes).digest('hex'),
    model, knowledgeVersion, variant, promptMode, runs }, null, 2))
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main()
