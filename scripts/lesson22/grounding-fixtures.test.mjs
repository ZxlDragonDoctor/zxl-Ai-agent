import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('each grounding case has one publishable document matching its citation contract', () => {
  const read = name => JSON.parse(readFileSync(new URL('../../docs/learning/examples/' + name, import.meta.url)))
  const fixtures = read('lesson22-grounding-docs.json')
  const grounding = read('lesson22-cases.json').cases.filter(item => item.category === 'grounding')
  assert.equal(fixtures.length, grounding.length)
  const expectedIds = grounding.flatMap(item => {
    assert.equal(item.expected.requiredSources.length, 1)
    assert.deepEqual(item.expected.allowedSources, item.expected.requiredSources)
    return item.expected.requiredSources
  })
  assert.deepEqual(fixtures.map(item => item.documentId).sort(), expectedIds.sort())
  for (const document of fixtures) {
    assert.ok(document.sourceName.endsWith('.md'))
    assert.ok(document.content.startsWith('# ') && document.content.length > 80)
  }
})
