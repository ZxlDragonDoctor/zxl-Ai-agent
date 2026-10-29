import test from 'node:test'
import assert from 'node:assert/strict'
import { selectAvailableAgentType } from '../../utils/agentSelection.ts'
test('formal directory never keeps an unavailable legacy default', () => {
  assert.equal(selectAvailableAgentType([{id:'enterprise-knowledge'},{id:'love-assistant'}], 'manus'), 'enterprise-knowledge')
})
test('explicit available selection takes precedence and legacy selection remains compatible', () => {
  const catalog=[{id:'manus'},{id:'mcp-research'}]
  assert.equal(selectAvailableAgentType(catalog,'manus','mcp-research'),'mcp-research')
  assert.equal(selectAvailableAgentType(catalog,'manus','missing'),'manus')
})
test('empty directory has no executable default', () => {
  assert.equal(selectAvailableAgentType([], 'manus'), '')
})
