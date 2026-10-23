import { test } from 'node:test'
import assert from 'node:assert/strict'
import { discoverScene, edgeGeometry, captionLines, revealSelection, incidentSelection } from '../graphPresentation.ts'

const edges = [{ from: 'A', to: 'B' }, { from: 'A', to: 'C' }, { from: 'B', to: 'D' }, { from: 'C', to: 'D' }, { from: 'D', to: 'E' }]
test('hop neighborhoods use shortest distance and deduplicate cyclic and shared paths', () => {
  const cyclic = [...edges, {from:'B',to:'C'}, {from:'D',to:'A'}]
  assert.deepEqual([...discoverScene('A',1,cyclic,new Set(),new Set())].sort(), ['A','B','C','D'])
  assert.deepEqual([...discoverScene('A',2,cyclic,new Set(),new Set())].sort(), ['A','B','C','D','E'])
})
test('incoming relationships count as neighbors and disconnected nodes stay hidden', () => {
  const relationships = [{from:'B',to:'A'},{from:'C',to:'B'},{from:'X',to:'Y'}]
  assert.deepEqual([...discoverScene('A',1,relationships,new Set(),new Set())].sort(), ['A','B'])
  assert.deepEqual([...discoverScene('A',2,relationships,new Set(),new Set())].sort(), ['A','B','C'])
})
test('expanded cycles terminate without duplicating node identity', () => {
  const cycle = [{from:'A',to:'B'},{from:'B',to:'C'},{from:'C',to:'A'}]
  assert.deepEqual([...discoverScene('A',1,cycle,new Set(['A','B','C']),new Set())].sort(), ['A','B','C'])
})
test('collapse preserves shared neighbors and removes unreachable expanded branches', () => {
  assert.deepEqual([...discoverScene('A', 1, edges, new Set(['B', 'C', 'D']), new Set(['B']))].sort(), ['A','B','C','D','E'])
  assert.deepEqual([...discoverScene('A', 1, edges, new Set(['B','D']), new Set(['B']))].sort(), ['A','B','C'])
  assert.deepEqual([...discoverScene('A', 1, edges, new Set(), new Set(['A']))], ['A'])
})
test('arrows stop at node borders and labels remain upright', () => {
  const forward = edgeGeometry({x:0,y:0}, {x:200,y:0}, 30, 30)
  const points = forward.path.match(/-?\d+(?:\.\d+)?/g).map(Number)
  assert.ok(points[3] > 20, 'a single relationship must sag rather than remain straight')
  assert.ok(Math.abs(Math.hypot(points[0], points[1]) - 32) < .001)
  assert.ok(Math.abs(Math.hypot(200-points[4], points[5]) - 37) < .001)
  for (const target of [{x:-200,y:90},{x:-200,y:-90},{x:0,y:0}]) {
    const result = edgeGeometry({x:0,y:0}, target, 30,30,25)
    assert.ok(Math.abs(result.angle) <= 90)
    assert.doesNotMatch(result.path, /NaN|Infinity/)
  }
})
test('single selection reveals both incoming and outgoing neighbors without mutating other branches', () => {
  const expanded = new Set(['C']), collapsed = new Set(['B']), dismissed = new Set(['D','E'])
  const result = revealSelection('B', edges, expanded, collapsed, dismissed)
  assert.ok(result.expanded.has('B'))
  assert.ok(result.expanded.has('C'))
  assert.ok(!result.collapsed.has('B'))
  assert.ok(!result.dismissed.has('D'))
  assert.ok(result.dismissed.has('E'))
  assert.ok(!expanded.has('B') && collapsed.has('B') && dismissed.has('D'))
  assert.ok(discoverScene('A',1,edges,result.expanded,result.collapsed).has('D'))
})
test('highlight includes only incident edges, not edges between adjacent nodes', () => {
  const selection = incidentSelection('A', [{id:'ab',from:'A',to:'B'},{id:'ca',from:'C',to:'A'},{id:'bc',from:'B',to:'C'}])
  assert.deepEqual([...selection.nodeIds].sort(), ['A','B','C'])
  assert.deepEqual([...selection.edgeIds].sort(), ['ab','ca'])
})
test('vertical and diagonal single links also keep visible slack', () => {
  for (const target of [{x:0,y:200},{x:200,y:200},{x:200,y:-200}]) {
    const values = edgeGeometry({x:0,y:0},target,30,30).path.match(/-?\d+(?:\.\d+)?/g).map(Number)
    assert.ok(Math.abs(values[2]*target.y-values[3]*target.x) > 100)
  }
})
test('captions keep Chinese and English text within four visible lines', () => {
  assert.deepEqual(captionLines('真实工具调用', true), ['真实工具调用'])
  assert.ok(captionLines('An exceptionally long engineering learning caption with more words', false).length <= 4)
  assert.ok(captionLines('FileChatBasedMemory', false).every(line => line.length <= 12))
  assert.ok(captionLines('真实工具调用', true, 4).every(line => line.length <= 4))
  assert.ok(captionLines('FileChatBasedMemory', false, 9).every(line => line.length <= 9))
})
