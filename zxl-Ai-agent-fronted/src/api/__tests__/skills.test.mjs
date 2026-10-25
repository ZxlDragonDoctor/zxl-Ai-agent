import test from 'node:test'
import assert from 'node:assert/strict'
import { readSkillCatalogue, readSkillEvent, skillsRequest, createSkillServiceReader } from '../skills.ts'

const definition = { skillId: 'it-ticket-analysis', version: 'v1', description: 'Analyze IT tickets', steps: ['CLARIFY', 'LOOKUP', 'VERIFY_ADVISE', 'DRAFT'], tools: [{ serverId: 'knowledge', originalName: 'knowledge_lookup_v1', exposedName: 'knowledge__knowledge_lookup_v1', schemaVersion: 'knowledge_lookup_v1', schemaSha256: 'a'.repeat(64), readOnly: true }], outputSchemaVersion: 'ticket-draft-v1', budgetMs: 30000, state: 'ACTIVE', sha256: 'b'.repeat(64) }
const snapshot = { runId: 'run-one', definition, definitionSha256: definition.sha256, toolSnapshotSha256: 'c'.repeat(64), executorVersion: 'skill-executor-v1', policyVersion: 'teaching-v1', selectedAt: '2026-10-25T00:00:00Z' }
test('catalogue preserves actual version availability and refuses malformed tool contracts', () => {
  const value = { defaultVersion: 'v1', versions: [{ definition, enabled: true, isDefault: true }], administrationEnabled: false }
  assert.equal(readSkillCatalogue(value).versions[0].definition.version, 'v1')
  assert.equal(readSkillCatalogue({ ...value, versions: [{ ...value.versions[0], definition: { ...definition, tools: [{ ...definition.tools[0], readOnly: false }] } }] }), null)
  assert.equal(readSkillCatalogue({ ...value, versions: [...value.versions, ...value.versions] }), null)
  assert.equal(readSkillCatalogue({ ...value, defaultVersion: 'missing' }), null)
})
test('selected snapshot is parsed independently from live catalogue and validates digest consistency', () => {
  assert.equal(readSkillEvent('SKILL_SELECTED', JSON.stringify({ ...snapshot, selectedAt: 1792886400000 })).selectedAt, 1792886400000)
  assert.equal(readSkillEvent('SKILL_SELECTED', JSON.stringify(snapshot)).definition.version, 'v1')
  assert.equal(readSkillEvent('SKILL_SELECTED', JSON.stringify({ ...snapshot, definitionSha256: 'd'.repeat(64) })), null)
  assert.equal(readSkillEvent('SKILL_SELECTED', '{}'), null)
  assert.equal(readSkillEvent('SKILL_SELECTED', 'x'.repeat(262145)), null)
})
test('step parser distinguishes running needs-input and failed terminal states', () => {
  for (const status of ['RUNNING', 'SUCCEEDED', 'NEEDS_INPUT', 'FAILED', 'CANCELLED']) assert.equal(readSkillEvent('SKILL_STEP', JSON.stringify({ step: 'CLARIFY', status, elapsedMs: 1, code: 'OK' })).status, status)
  assert.equal(readSkillEvent('SKILL_STEP', JSON.stringify({ step: 'TERMINAL', status: 'SUCCEEDED', elapsedMs: 1, code: 'OK' })), null)
  assert.equal(readSkillEvent('SKILL_STEP', JSON.stringify({ step: 'CLARIFY', status: 'SUCCEEDED', elapsedMs: -1, code: 'OK' })), null)
})
test('draft is only a human approval draft and must match the saved version schema', () => {
  const result = { snapshot, status: 'SUCCEEDED', advice: 'Advice [C1]', draft: { schemaVersion: 'ticket-draft-v1', title: 'VPN401', problem: 'VPN error', advice: 'Advice [C1]', priority: null, created: false, requiresApproval: true }, steps: [], code: 'OK' }
  assert.equal(readSkillEvent('SKILL_TERMINAL', JSON.stringify(result)).draft.created, false)
  assert.equal(readSkillEvent('SKILL_TERMINAL', JSON.stringify({ ...result, draft: { ...result.draft, created: true } })), null)
  assert.equal(readSkillEvent('SKILL_TERMINAL', JSON.stringify({ ...result, draft: { ...result.draft, schemaVersion: 'ticket-draft-v2' } })), null)
  assert.equal(readSkillEvent('SKILL_TERMINAL', JSON.stringify({ ...result, status: 'NO_EVIDENCE', draft: null })).status, 'NO_EVIDENCE')
})
test('read-only catalogue API sends no mutation or caller identity and reports safe failures', async () => {
  const original = globalThis.fetch; let url, options
  try {
    globalThis.fetch = async (u, o) => { url = u; options = o; return { ok: true, json: async () => ({ value: 1 }) } }
    await skillsRequest()
    assert.equal(url, '/api/ai/learning/skills'); assert.equal(options.method, 'GET'); assert.equal(options.body, undefined)
    globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ code: 'secret-content' }) })
    await assert.rejects(skillsRequest(), /SKILL_HTTP_503/)
    globalThis.fetch = async () => { throw new Error('private socket') }
    await assert.rejects(skillsRequest(), /SKILL_NETWORK_UNAVAILABLE/)
  } finally { globalThis.fetch = original }
})
test('service status reads are distinct from explicit discovery and closed or stale reads cannot publish', async () => {
  const requests = [], pending = []
  const reader = createSkillServiceReader((path, body, signal) => { requests.push({ path, body, signal }); return new Promise(resolve => pending.push(resolve)) })
  const first = reader.read(false); const second = reader.read(true)
  assert.equal(requests[0].path, '/status'); assert.equal(requests[0].body, undefined)
  assert.equal(requests[1].path, '/refresh'); assert.deepEqual(requests[1].body, {})
  assert.equal(requests[0].signal.aborted, true)
  pending[1]({ state: 'READY', toolCount: 1 }); assert.equal((await second).state, 'READY')
  pending[0]({ state: 'UNAVAILABLE', toolCount: 0 }); assert.equal(await first, null)
  const closing = reader.read(false); reader.close(); assert.equal(requests[2].signal.aborted, true)
  pending[2]({ state: 'READY', toolCount: 1 }); assert.equal(await closing, null)
})
