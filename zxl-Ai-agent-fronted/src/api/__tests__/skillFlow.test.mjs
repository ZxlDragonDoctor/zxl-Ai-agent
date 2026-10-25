import test from 'node:test'
import assert from 'node:assert/strict'
import { applyAgentEvent, restoreChat } from '../../utils/agentFlow.ts'
const definition = { skillId: 'it-ticket-analysis', version: 'v1', description: 'IT', steps: ['CLARIFY', 'LOOKUP', 'VERIFY_ADVISE', 'DRAFT'], tools: [{ serverId: 'knowledge', originalName: 'knowledge_lookup_v1', exposedName: 'knowledge__knowledge_lookup_v1', schemaVersion: 'knowledge_lookup_v1', schemaSha256: 'a'.repeat(64), readOnly: true }], outputSchemaVersion: 'ticket-draft-v1', budgetMs: 30000, state: 'ACTIVE', sha256: 'b'.repeat(64) }
const snapshot = { runId: 'run', definition, definitionSha256: definition.sha256, toolSnapshotSha256: 'c'.repeat(64), executorVersion: 'skill-executor-v1', policyVersion: 'v1', selectedAt: '2026-10-25T00:00:00Z' }
const event = (type, content) => ({ type, content: JSON.stringify(content), step: 1, runId: 'run' })
const reply = () => ({ role: 'ai', content: '', flow: { status: 'running', phase: 'connecting', steps: [], startedAt: 0 } })
test('skill public events update a separate immutable run snapshot and step states', () => {
  const r = reply(); applyAgentEvent(r, event('SKILL_SELECTED', snapshot))
  applyAgentEvent(r, event('SKILL_STEP', { step: 'CLARIFY', status: 'RUNNING', code: 'OK', elapsedMs: 0 }))
  applyAgentEvent(r, event('SKILL_STEP', { step: 'CLARIFY', status: 'NEEDS_INPUT', code: 'MISSING_ERROR_CODE', elapsedMs: 1 }))
  assert.equal(r.flow.skill.snapshot.definition.version, 'v1'); assert.equal(r.flow.skill.steps.length, 1)
  assert.equal(r.flow.skill.steps[0].status, 'NEEDS_INPUT'); assert.equal(r.content, '')
  assert.equal(r.flow.steps.length, 0)
})
test('history restores actual old skill snapshots and does not share subsequent run state', () => {
  const runs = ['v1', 'v2'].map(version => ({ runId: 'run', startedAt: snapshot.selectedAt, answer: '', status: 'completed', events: [event('SKILL_SELECTED', { ...snapshot, definition: { ...definition, version } })] }))
  const restored = restoreChat({ runs, messages: [] })
  assert.equal(restored[0].flow.skill.snapshot.definition.version, 'v1')
  assert.equal(restored[1].flow.skill.snapshot.definition.version, 'v2')
})
test('malformed skill evidence is flagged instead of appearing as completed policy execution', () => {
  const r = reply(); applyAgentEvent(r, { type: 'SKILL_TERMINAL', step: 1, content: '{}' })
  assert.equal(r.flow.skill.invalidEvent, true); assert.equal(r.flow.skill.result, undefined)
})
test('a selected version cannot be replaced by a mismatched snapshot even with a copied digest', () => {
  const r = reply(); applyAgentEvent(r, event('SKILL_SELECTED', snapshot))
  applyAgentEvent(r, event('SKILL_SELECTED', { ...snapshot, definition: { ...definition, version: 'v2' } }))
  assert.equal(r.flow.skill.snapshot.definition.version, 'v1'); assert.equal(r.flow.skill.invalidEvent, true)
})
