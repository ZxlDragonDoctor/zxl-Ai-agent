import { test } from 'node:test'
import assert from 'node:assert/strict'
import * as motion from '../graph3DMotion.ts'

const node = (id, x = 0, y = 0, z = 0) => ({ id, kind: 'concept', degree: 0, x, y, z, vx: 0, vy: 0, vz: 0 })
const speed = n => Math.hypot(n.vx, n.vy, n.vz)

test('reading holds only the orbit and keeps an unlimited physics lifetime', () => {
  const policy = motion.spatialMotionPolicy?.(true, true)
  assert.equal(policy?.cooldownTicks, Infinity, 'opening the reader must not stop the force engine')
  assert.equal(policy?.cooldownTime, Infinity, 'the engine must not stop after twelve seconds')
  assert.equal(policy?.alphaMin, 0, 'a cooled layout must still tick the ambient force')
  assert.equal(policy?.autoOrbit, false)
})

test('pause and reduced-motion inputs stop physics independently of reading', () => {
  for (const reading of [true, false]) {
    const policy = motion.spatialMotionPolicy?.(false, reading)
    assert.equal(policy?.cooldownTicks, 0)
    assert.equal(policy?.autoOrbit, false)
  }
  assert.equal(motion.spatialMotionPolicy?.(true, false)?.autoOrbit, true)
})

test('ambient motion remains active at zero alpha and after twelve seconds', () => {
  let now = 15_000
  const controller = motion.createSpatialMotion(() => now), a = node('A')
  controller.force.initialize([a]); controller.force(0)
  assert.ok(speed(a) > .001)
  const before = [a.vx, a.vy, a.vz]
  a.vx = a.vy = a.vz = 0; now = 45_000; controller.force(0)
  assert.ok(speed(a) > .001)
  assert.notDeepEqual([a.vx, a.vy, a.vz], before)
})

test('disabled force and impulses leave positions and velocities untouched', () => {
  const controller = motion.createSpatialMotion(() => 1000), a = node('A'), b = node('B', 10)
  controller.force.initialize([a, b]); controller.setEnabled(false)
  const before = structuredClone([a, b])
  controller.force(0); controller.pulse('A', new Set(['A', 'B']))
  assert.deepEqual([a, b], before)
})

test('fixed zero coordinates are respected by force and click impulses', () => {
  const controller = motion.createSpatialMotion(() => 1000), a = node('A'), b = { ...node('B', 10), fx: 0 }
  controller.force.initialize([a, b]); const before = structuredClone(b)
  controller.force(0); controller.pulse('A', new Set(['A', 'B']))
  assert.deepEqual(b, before)
})

test('click impulse affects only direct neighbors, points outwards, and is capped', () => {
  const controller = motion.createSpatialMotion(() => 0), a = node('A'), b = node('B', 10), c = node('C', -10)
  controller.force.initialize([a, b, c])
  for (let i = 0; i < 50; i++) controller.pulse('A', new Set(['A', 'B']))
  assert.equal(speed(a), 0); assert.equal(speed(c), 0)
  assert.ok(b.vx > 0 && b.vx <= 3)
  assert.ok([b.vx, b.vy, b.vz].every(v => Number.isFinite(v) && Math.abs(v) <= 3))
})

test('coincident nodes receive finite bounded impulse directions', () => {
  const controller = motion.createSpatialMotion(() => 0), a = node('A'), b = node('B')
  controller.force.initialize([a, b]); controller.pulse('A', new Set(['A', 'B']))
  assert.ok(speed(b) > 0)
  assert.ok([b.vx, b.vy, b.vz].every(v => Number.isFinite(v) && Math.abs(v) <= 3))
})

test('far positions cannot cause unbounded acceleration', () => {
  const controller = motion.createSpatialMotion(() => 1000), a = node('A')
  controller.force.initialize([a]); a.x = 1e8; a.y = -1e8; a.z = 1e8
  controller.force(0)
  assert.ok([a.vx, a.vy, a.vz].every(v => Number.isFinite(v) && Math.abs(v) <= .35))
})

test('drag reanchoring releases the old home position', () => {
  const controller = motion.createSpatialMotion(() => 0), a = node('A')
  controller.force.initialize([a]); a.x = 1000; controller.reanchor('A'); controller.force(0)
  assert.ok(Math.abs(a.vx) < .1, 'a moved star must not be pulled toward its old origin')
})

test('removed nodes are untouched and reintroduced IDs get fresh rest positions', () => {
  const controller = motion.createSpatialMotion(() => 0), old = node('A'), replacement = node('A', 500)
  controller.force.initialize([old]); controller.force.initialize([]); controller.force(0)
  assert.equal(speed(old), 0)
  controller.force.initialize([replacement]); controller.force(0)
  assert.ok(Math.abs(replacement.vx) < .1)
})

test('cold integration stays local and keeps moving over a minute', () => {
  let now = 0
  const controller = motion.createSpatialMotion(() => now), a = node('A'), b = node('B', 100)
  controller.force.initialize([a, b]); controller.emphasize('A', new Set(['A', 'B']))
  let earlier
  for (let frame = 0; frame < 3600; frame++) {
    now = frame * 1000 / 60; controller.force(0)
    for (const n of [a, b]) for (const axis of ['x', 'y', 'z']) {
      n[`v${axis}`] *= .7; n[axis] += n[`v${axis}`]
    }
    if (frame === 1800) earlier = [a.x, a.y, a.z, b.x, b.y, b.z]
    assert.ok(Math.hypot(a.x, a.y, a.z) < 5)
    assert.ok(Math.hypot(b.x - 100, b.y, b.z) < 15)
  }
  assert.notDeepEqual([a.x, a.y, a.z, b.x, b.y, b.z], earlier)
})
