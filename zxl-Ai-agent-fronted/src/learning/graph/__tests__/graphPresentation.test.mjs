import { test } from 'node:test'
import assert from 'node:assert/strict'
import { discoverScene, edgeGeometry, captionLines } from '../graphPresentation.ts'

const edges = [{ from: 'A', to: 'B' }, { from: 'A', to: 'C' }, { from: 'B', to: 'D' }, { from: 'C', to: 'D' }, { from: 'D', to: 'E' }]
test('collapse preserves shared neighbors and removes unreachable expanded branches', () => {
  assert.deepEqual([...discoverScene('A', 1, edges, new Set(['B', 'C', 'D']), new Set(['B']))].sort(), ['A','B','C','D','E'])
  assert.deepEqual([...discoverScene('A', 1, edges, new Set(['B','D']), new Set(['B']))].sort(), ['A','B','C'])
  assert.deepEqual([...discoverScene('A', 1, edges, new Set(), new Set(['A']))], ['A'])
})
test('arrows stop at node borders and labels remain upright', () => {
  const forward = edgeGeometry({x:0,y:0}, {x:200,y:0}, 30, 30)
  assert.match(forward.path, /^M 32 0 Q 100 0 163 0$/)
  for (const target of [{x:-200,y:90},{x:-200,y:-90},{x:0,y:0}]) {
    const result = edgeGeometry({x:0,y:0}, target, 30,30,25)
    assert.ok(Math.abs(result.angle) <= 90)
    assert.doesNotMatch(result.path, /NaN|Infinity/)
  }
})
test('captions keep Chinese and English text within four visible lines', () => {
  assert.deepEqual(captionLines('真实工具调用', true), ['真实工具调用'])
  assert.ok(captionLines('An exceptionally long engineering learning caption with more words', false).length <= 4)
})
