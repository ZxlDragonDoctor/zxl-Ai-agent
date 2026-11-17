import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { formalSession } from './formal-session.mjs'
import { runCases } from './run-formal.mjs'
import { startTransportCut } from './transport-cut.mjs'

// Use a dedicated main process pointed at PROXY_PORT and a dedicated MCP backend.
const env = process.env
const [datasetPath, model, knowledgeVersion, variant] = process.argv.slice(2)
if (!model || !knowledgeVersion || !variant || !/^lesson22-[a-z0-9-]+$/.test(env.LESSON22_TENANT ?? '')
    || !env.LESSON22_PSQL || !env.PGPORT) throw new Error('Explicit dedicated fixture and pinned metadata required')
const bytes = readFileSync(datasetPath)
const item = JSON.parse(bytes).cases.find(item => item.id === 'tool-failure-3' && item.input.scenario === 'mcp-unavailable')
if (!item) throw new Error('Pinned MCP outage case required')
const proxy = await startTransportCut(Number(env.LESSON22_MCP_BACKEND_PORT), Number(env.LESSON22_MCP_PROXY_PORT))
try {
  const origin = env.LESSON22_ORIGIN
  const employee = await formalSession(origin, env.LESSON22_TENANT, env.LESSON22_LOGIN, env.LESSON22_PASSWORD, 'employee')
  const manager = await formalSession(origin, env.LESSON22_TENANT, env.LESSON22_MAINTAINER, env.LESSON22_MAINTAINER_PASSWORD, 'knowledge-maintainer')
  const discovery = await manager('/ai/learning/mcp/refresh', {})
  if (discovery.state !== 'READY' || discovery.toolCount !== 1
      || new URL(discovery.endpoint).port !== String(proxy.port) || !proxy.snapshot().forwarded) {
    throw new Error('Dedicated proxy discovery did not succeed')
  }
  proxy.cut()
  const runs = await runCases([item], {
    submit: (goal, timeoutMs) => employee('/ai/multiagent/runs', { goal, timeoutMs }),
    detail: id => employee('/ai/multiagent/runs/' + id),
    countWrites: report => {
      if (![report.startedAt, report.finishedAt].every(Number.isSafeInteger)) throw new Error('Invalid run times')
      const sql = `SELECT count(*) FROM lesson17.approved_ticket WHERE tenant_id='${env.LESSON22_TENANT}'
        AND created_at BETWEEN to_timestamp(${report.startedAt}/1000.0) AND to_timestamp(${report.finishedAt}/1000.0)`
      const count = execFileSync(env.LESSON22_PSQL, ['-XAt', '-w', '-h', '127.0.0.1', '-c', sql], { encoding: 'utf8', timeout: 10000 }).trim()
      if (!/^\d+$/.test(count)) throw new Error('Invalid write count')
      return Number(count)
    },
  })
  // Never accept a model failure before dispatch as an MCP-outage observation.
  if (!runs[0].tools.includes('knowledge__knowledge_lookup_v2')
      || !runs[0].toolErrors?.some(code => ['MCP_NETWORK_FAILURE', 'MCP_PROTOCOL_FAILURE', 'MCP_DEADLINE_EXCEEDED'].includes(code))) {
    throw new Error('The observed failure did not reach the cut MCP transport')
  }
  console.log(JSON.stringify({ datasetSha256: createHash('sha256').update(bytes).digest('hex'),
    model, knowledgeVersion, variant, promptMode: 'research-directed',
    fixture: { discovered: true, transport: proxy.snapshot() }, runs }, null, 2))
} finally { await proxy.close() }
