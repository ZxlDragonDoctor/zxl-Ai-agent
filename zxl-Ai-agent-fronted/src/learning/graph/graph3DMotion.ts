import type { SpatialNode } from './graph3DModel'

interface RestPoint { x: number; y: number; z: number; phase: number }

/** A bounded, low-energy force over physics copies; links follow the same coordinates. */
export function createSpatialMotion(clock = () => performance.now()) {
  let nodes: SpatialNode[] = [], enabled = true, selected = ''
  let neighbors = new Set<string>()
  const rests = new Map<string, RestPoint>()
  const finite = (value?: number) => Number.isFinite(value) ? value! : 0
  const point = (node: SpatialNode): RestPoint => {
    let hash = 0
    for (const char of node.id) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
    return { x: finite(node.x), y: finite(node.y), z: finite(node.z), phase: (hash % 628) / 100 }
  }
  const force = (alpha: number) => {
    if (!enabled) return
    const seconds = clock() / 1000
    for (const node of nodes) {
      // Let the graph library own dragged/pinned coordinates.
      if (node.fx != null || node.fy != null || node.fz != null) continue
      const rest = rests.get(node.id)!
      const x = finite(node.x), y = finite(node.y), z = finite(node.z)
      // Follow a hot layout as it settles; keep a fixed home in the cool layout.
      if (alpha > .12) {
        rest.x += (x - rest.x) * .08
        rest.y += (y - rest.y) * .08
        rest.z += (z - rest.z) * .08
      }
      const amplitude = node.id === selected ? 1.5 : neighbors.has(node.id) ? 7 : 3.5
      const targets = {
        x: rest.x + Math.sin(seconds * .72 + rest.phase) * amplitude,
        y: rest.y + Math.sin(seconds * .58 + rest.phase * 1.7) * amplitude,
        z: rest.z + Math.cos(seconds * .63 + rest.phase * 1.3) * amplitude * .65,
      }
      for (const axis of ['x', 'y', 'z'] as const) {
        const velocity = `v${axis}` as 'vx' | 'vy' | 'vz'
        const acceleration = Math.max(-.35, Math.min(.35, (targets[axis] - finite(node[axis])) * .025))
        node[velocity] = finite(node[velocity]) + acceleration
      }
    }
  }
  force.initialize = (current: SpatialNode[]) => {
    nodes = current
    const ids = new Set(current.map(node => node.id))
    for (const id of rests.keys()) if (!ids.has(id)) rests.delete(id)
    for (const node of nodes) if (!rests.has(node.id)) rests.set(node.id, point(node))
  }
  return {
    force,
    setEnabled(value: boolean) { enabled = value },
    emphasize(id: string, ids: Set<string>) { selected = id; neighbors = ids },
    reanchor(id: string) { const node = nodes.find(item => item.id === id); if (node) rests.set(id, point(node)) },
    pulse(id: string, ids: Set<string>) {
      if (!enabled) return
      const center = nodes.find(node => node.id === id)
      if (!center) return
      for (const node of nodes) {
        if (node.id === id || !ids.has(node.id) || node.fx != null || node.fy != null || node.fz != null) continue
        const rest = rests.get(node.id) ?? point(node)
        let dx = finite(node.x) - finite(center.x), dy = finite(node.y) - finite(center.y), dz = finite(node.z) - finite(center.z)
        if (Math.hypot(dx, dy, dz) < .001) { dx = Math.cos(rest.phase); dy = Math.sin(rest.phase); dz = .5 }
        const length = Math.hypot(dx, dy, dz)
        node.vx = Math.max(-3, Math.min(3, finite(node.vx) + dx / length * 2.4))
        node.vy = Math.max(-3, Math.min(3, finite(node.vy) + dy / length * 2.4))
        node.vz = Math.max(-3, Math.min(3, finite(node.vz) + dz / length * 2.4))
      }
    },
  }
}
