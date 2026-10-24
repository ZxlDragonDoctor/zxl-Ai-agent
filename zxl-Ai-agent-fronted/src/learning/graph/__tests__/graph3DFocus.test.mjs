import test from 'node:test'
import assert from 'node:assert/strict'
import { createFocusSettler } from '../graph3DFocus.ts'

test('empty or incomplete graph never counts as a ready finite neighborhood', () => {
  const settle = createFocusSettler('A', ['A', 'B'], 0)
  assert.equal(settle([], 5000), false)
  assert.equal(settle([{ id: 'B', x: 0, y: 0, z: 0 }], 6000), false)
  assert.equal(settle([{ id: 'A', x: 0, y: 0, z: 0 }], 7000), false)
})

test('finite initial positions do not focus before the returning graph settles', () => {
  const settle = createFocusSettler('A', ['A'], 0)
  assert.equal(settle([{ id: 'A', x: 0, y: 0, z: 0 }], 16), false)
  assert.equal(settle([{ id: 'A', x: 240, y: 0, z: 0 }], 100), false)
  assert.equal(settle([{ id: 'A', x: 500, y: 0, z: 0 }], 400), false)
  assert.equal(settle([{ id: 'A', x: 500.2, y: 0, z: 0 }], 500), false)
  assert.equal(settle([{ id: 'A', x: 500.3, y: 0, z: 0 }], 700), true)
})

test('bounded ambient sway may focus, but unbounded layout motion resets stability', () => {
  const settle = createFocusSettler('A', ['A', 'B'], 0)
  const nodes = x => [{ id: 'A', x, y: 0, z: 0 }, { id: 'B', x: x + 60, y: 0, z: 0 }]
  assert.equal(settle(nodes(0), 0), false)
  assert.equal(settle(nodes(.3), 100), false)
  assert.equal(settle(nodes(.6), 450), true)
})

test('deadline permits finite moving layouts only, never NaN coordinates', () => {
  const settle = createFocusSettler('A', ['A'], 0)
  assert.equal(settle([{ id: 'A', x: NaN, y: 0, z: 0 }], 4000), false)
  assert.equal(settle([{ id: 'A', x: 110, y: 0, z: 0 }], 4016), true)
})
