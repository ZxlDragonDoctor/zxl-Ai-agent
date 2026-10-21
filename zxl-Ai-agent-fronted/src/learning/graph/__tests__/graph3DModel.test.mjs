import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSpatialGraph, focusDestination, spatialNodeRadius } from '../graph3DModel.ts'

test('physics mutates copies and degrees count unique valid neighbors', () => {
  const nodes = [Object.freeze({ id:'A', kind:'lesson' }), Object.freeze({ id:'B', kind:'concept' })]
  const edges = [Object.freeze({id:'ab',from:'A',to:'B'}), Object.freeze({id:'ba',from:'B',to:'A'}), Object.freeze({id:'missing',from:'B',to:'C'})]
  const graph = createSpatialGraph(nodes, edges, [{ id:'A',x:12,y:Infinity,z:6, degree:0 }])
  assert.equal(graph.nodes[0].degree, 1)
  assert.equal(graph.nodes[0].x, 12)
  assert.equal(graph.nodes[0].y, undefined)
  graph.nodes[0].x = 99; graph.links[0].source = graph.nodes[0]
  assert.equal(nodes[0].x, undefined)
  assert.equal(edges[0].source, undefined)
  assert.equal(graph.links.length, 2)
})
test('camera approaches a node at a bounded distance even for degenerate coordinates', () => {
  for (const [node, camera] of [[{}, {x:0,y:0,z:0}], [{x:9,y:3,z:-7},{x:9,y:3,z:-7}], [{x:NaN,y:Infinity,z:2},{x:0,y:0,z:500}]]) {
    const { position, lookAt } = focusDestination(node, camera, 110)
    assert.ok(Object.values(position).every(Number.isFinite))
    assert.ok(Math.abs(Math.hypot(position.x-lookAt.x, position.y-lookAt.y, position.z-lookAt.z)-110) < .0001)
  }
})
test('node radius conveys degree without unbounded growth', () => {
  assert.ok(spatialNodeRadius({kind:'lesson',degree:20}) > spatialNodeRadius({kind:'lesson',degree:1}))
  assert.equal(spatialNodeRadius({kind:'lesson',degree:99999}), spatialNodeRadius({kind:'lesson',degree:25}))
})
