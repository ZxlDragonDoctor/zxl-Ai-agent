import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createFitCamera } from '../graphFitCamera.ts'
import { forceSimulation, forceManyBody, forceCenter } from 'd3-force'
import { discoverScene, edgeGeometry, captionLines, revealSelection, incidentSelection } from '../graphPresentation.ts'

const edges = [{ from: 'A', to: 'B' }, { from: 'A', to: 'C' }, { from: 'B', to: 'D' }, { from: 'C', to: 'D' }, { from: 'D', to: 'E' }]
test('fit tracks fresh world bounds while the initial force layout spreads', () => {
  const nodes=Array.from({length:40},(_,i)=>({id:String(i),x:500+Math.cos(i*2.4)*100,y:350+Math.sin(i*2.4)*100}))
  const camera=createFitCamera(1000,700);camera.request('all')
  const initial=camera.target(nodes,new Set())
  const simulation=forceSimulation(nodes).force('charge',forceManyBody().strength(-1150)).force('center',forceCenter(500,350)).stop()
  let staleClips=false
  for(let i=0;i<200;i++){
    simulation.tick()
    const current=camera.target(nodes,new Set())
    for(const node of nodes){
      const x=node.x*current.scale+current.x,y=node.y*current.scale+current.y
      assert.ok(x>=0&&x<=1000&&y>=0&&y<=700,'each later layout frame remains in view')
      if(node.x*initial.scale+initial.x<0||node.x*initial.scale+initial.x>1000||node.y*initial.scale+initial.y<0||node.y*initial.scale+initial.y>700)staleClips=true
    }
  }
  assert.ok(staleClips,'the old one-shot target must reproduce the clipping')
})
test('manual camera ownership survives ticks and simulation end until another explicit fit', () => {
  const camera=createFitCamera(1000,700),nodes=[{id:'A',x:0,y:0},{id:'B',x:1500,y:1000}]
  for(const gesture of ['pan','zoom','drag']){
    camera.request('all');assert.ok(camera.target(nodes,new Set()))
    camera.cancel();nodes[0].x-=100
    assert.equal(camera.target(nodes,new Set()),undefined,gesture+' owns the camera during ticks')
    assert.equal(camera.target(nodes,new Set()),undefined,gesture+' still owns it at layout end')
  }
  camera.request('all');assert.ok(camera.target(nodes,new Set()))
})
test('focus follows only the selected neighborhood and explicit all replaces focus intent', () => {
  const camera=createFitCamera(1000,700),nodes=[{id:'A',x:100,y:100},{id:'B',x:6000,y:6000}]
  camera.request('focus');const focus=camera.target(nodes,new Set(['A']))
  assert.equal(100*focus.scale+focus.x,500)
  camera.request('all');assert.ok(camera.target(nodes,new Set(['A'])).scale<focus.scale)
  assert.equal(camera.target([],new Set()),undefined)
})
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
