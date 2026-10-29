import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const source = (await readFile(new URL('../chat.js', import.meta.url), 'utf8')).replace("import { sessionFetch, sessionGeneration } from '../utils/sessionRequest.ts'", `import { sessionFetch, sessionGeneration } from '${new URL('../../utils/sessionRequest.ts', import.meta.url).href}'`).replace("import { API_BASE as BASE_URL } from './base'", "const BASE_URL = '/api'")
const { chatWithAgentSse } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
test('chat optional skill selection is captured in the new POST without changing legacy requests', async () => {
  const original = globalThis.fetch; const bodies = []
  globalThis.fetch = async (_url, options) => { bodies.push(JSON.parse(options.body)); return { ok: false, json: async () => ({ code: 'EXPECTED' }) } }
  try {
    chatWithAgentSse('old', () => {}, () => {}, undefined, 'conversation-one')
    chatWithAgentSse('new', () => {}, () => {}, undefined, 'conversation-two', { skillId: 'it-ticket-analysis', skillVersion: 'v2', tenantId: 'evil' })
    chatWithAgentSse('default', () => {}, () => {}, undefined, 'conversation-three', { skillId: 'it-ticket-analysis', skillVersion: '' })
    await new Promise(resolve => setImmediate(resolve))
    assert.deepEqual(bodies[0], { message: 'old', conversationId: 'conversation-one' })
    assert.deepEqual(bodies[1], { message: 'new', conversationId: 'conversation-two', skillId: 'it-ticket-analysis', skillVersion: 'v2' })
    assert.deepEqual(bodies[2], { message: 'default', conversationId: 'conversation-three', skillId: 'it-ticket-analysis' })
  } finally { globalThis.fetch = original }
})
