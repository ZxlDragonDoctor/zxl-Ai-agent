import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSpatialGraph, focusDestination, spatialNodeRadius, neighborhoodDestination, spatialLinkCurve } from '../graph3DModel.ts'

test('repeated graph updates retain live physics identity without mutating catalogue records', () => {
  const catalogue = [Object.freeze({id:'A',kind:'lesson'}), Object.freeze({id:'B',kind:'concept'})]
  const first = createSpatialGraph(catalogue, [])
  first.nodes[0].x = 15
  for (let i = 0; i < 12; i++) {
    const next = createSpatialGraph(catalogue, [], first.nodes)
    assert.equal(next.nodes[0], first.nodes[0], 'stable IDs must retain their scene-bound physics objects')
    assert.equal(next.nodes[0].x, 15)
    assert.notEqual(next.nodes[0], catalogue[0])
  }
})

test('real Three scene retains cached node meshes across neighborhood and full-graph updates', async () => {
  // Only the scene/physics layer: no browser, WebGL context, network, or local page access.
  const { Group, Mesh, SphereGeometry, MeshBasicMaterial } = await import('three')
  globalThis.window ??= {}
  const { default: ThreeForceGraph } = await import('three-forcegraph')
  const visuals = new Map()
  const scene = new ThreeForceGraph().nodeThreeObject(node => {
    if (!visuals.has(node.id)) visuals.set(node.id, new Group().add(new Mesh(new SphereGeometry(1), new MeshBasicMaterial())))
    return visuals.get(node.id)
  })
  const catalogue = [{id:'A',kind:'lesson'},{id:'B',kind:'concept'}]
  let data = createSpatialGraph(catalogue, [])
  const update = data => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('scene update did not finish')), 2000)
    scene.onFinishUpdate(() => { clearTimeout(timer); resolve() }).graphData(data)
  })
  try {
    await update(data)
    for (let i = 0; i < 6; i++) {
      data = createSpatialGraph(i % 2 ? catalogue : catalogue.slice(0, 1), [], data.nodes)
      await update(data)
      for (const node of data.nodes) assert.equal(visuals.get(node.id).parent, scene, `star ${node.id} was detached on update ${i}`)
    }
  } finally { await update({nodes:[],links:[]}) }
})

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
test('selection camera contains distant neighbors and accounts for portrait viewports', () => {
  const node={x:0,y:0,z:0}, camera={x:0,y:0,z:500}, neighbors=[{x:210,y:0,z:0}]
  const wide=neighborhoodDestination(node,neighbors,camera,50,1.6)
  const narrow=neighborhoodDestination(node,neighbors,camera,50,.6)
  assert.ok(wide.position.z > 500)
  assert.ok(narrow.position.z > wide.position.z)
  assert.deepEqual(wide.lookAt,node)
  assert.ok(Object.values(neighborhoodDestination({},[{}],{x:0,y:0,z:0},50,0).position).every(Number.isFinite))
})
test('3D relationships always curve in a stable plane', () => {
  const a=spatialLinkCurve('edge-A'), b=spatialLinkCurve('edge-B')
  assert.ok(a.curvature >= .12 && a.curvature <= .24)
  assert.deepEqual(a,spatialLinkCurve('edge-A'))
  assert.notEqual(a.rotation,b.rotation)
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
