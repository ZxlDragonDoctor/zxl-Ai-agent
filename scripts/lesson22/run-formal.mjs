import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { pathToFileURL } from 'node:url'
import { projectRun } from './project-run.mjs'

export async function runCases(cases, { submit, detail, countWrites, wait = delay, now = Date.now }) {
  const runs = []
  for (const item of cases) {
    const { executionId } = await submit(item.input.prompt, item.input.budgetMs)
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
      || !(item.category === 'grounding' || (item.category === 'budget' && item.input.scenario === 'model-wait')))) {
    throw new Error('This runner supports explicit read-only grounding and model-wait cases in one split')
  }
  const cookies = new Map()
  let csrf
  async function request(path, body) {
    const response = await fetch(base.origin + '/api' + path, {
      redirect: 'error', signal: AbortSignal.timeout(10_000),
      method: body === undefined ? 'GET' : 'POST',
      headers: { Cookie: [...cookies.values()].join('; '), Origin: base.origin,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!response.ok) throw new Error('Formal evaluation request failed: HTTP ' + response.status)
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0]
      cookies.set(pair.split('=', 1)[0], pair)
    }
    return response.json()
  }
  csrf = (await request('/auth/status')).csrfToken
  const session = await request('/auth/login', {
    tenantId: tenant, loginName: env.LESSON22_LOGIN, password: env.LESSON22_PASSWORD,
  })
  if (session.principal?.tenantId !== tenant || session.principal?.role !== 'employee') {
    throw new Error('Expected fixture employee session')
  }
  csrf = session.csrfToken
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
    detail: id => request('/ai/multiagent/runs/' + id), countWrites,
  })
  console.log(JSON.stringify({ datasetSha256: createHash('sha256').update(bytes).digest('hex'),
    model, knowledgeVersion, variant, runs }, null, 2))
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main()
