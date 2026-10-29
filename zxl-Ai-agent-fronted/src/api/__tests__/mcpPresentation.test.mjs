import test from 'node:test'
import assert from 'node:assert/strict'
import { mcpConnectionPresentation } from '../../utils/mcpPresentation.ts'
test('not discovered is not a proved service outage', () => {
  const view=mcpConnectionPresentation({state:'UNAVAILABLE',lastError:'NOT_DISCOVERED'})
  assert.match(view.title.zh,/尚未发现/)
  assert.equal(view.showError,false)
})
test('real network failure remains visible', () => {
  const view=mcpConnectionPresentation({state:'UNAVAILABLE',lastError:'MCP_NETWORK_FAILURE'})
  assert.match(view.title.zh,/不可用/)
  assert.equal(view.showError,true)
})
test('ready and pending states do not invent connection errors', () => {
  assert.match(mcpConnectionPresentation({state:'READY'}).title.en,/Ready/)
  assert.equal(mcpConnectionPresentation(null).showError,false)
})
