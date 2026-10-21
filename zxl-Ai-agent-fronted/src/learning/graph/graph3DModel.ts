import type { GraphEdge, GraphNode } from './model'

export interface SpatialNode extends GraphNode {
  degree: number
  x?: number; y?: number; z?: number
  vx?: number; vy?: number; vz?: number
  fx?: number | null; fy?: number | null; fz?: number | null
}
export interface SpatialLink extends GraphEdge { source: string | SpatialNode; target: string | SpatialNode }
export interface Position3D { x: number; y: number; z: number }
const positionKeys = ['x', 'y', 'z', 'vx', 'vy', 'vz', 'fx', 'fy', 'fz'] as const

/** Physics engines mutate nodes and link endpoints. Never give them the knowledge catalogue. */
export function createSpatialGraph(nodes: GraphNode[], edges: GraphEdge[], previous: SpatialNode[] = []) {
  const ids = new Set(nodes.map(node => node.id))
  const links = edges.filter(edge => ids.has(edge.from) && ids.has(edge.to))
  const degree = new Map<string, Set<string>>()
  for (const edge of links) {
    for (const [a, b] of [[edge.from, edge.to], [edge.to, edge.from]]) {
      if (!degree.has(a!)) degree.set(a!, new Set())
      degree.get(a!)!.add(b!)
    }
  }
  const oldNodes = new Map(previous.map(node => [node.id, node]))
  return {
    nodes: nodes.map(node => {
      const copy: SpatialNode = { ...node, degree: degree.get(node.id)?.size ?? 0 }
      const old = oldNodes.get(node.id)
      if (old) for (const key of positionKeys) {
        const value = old[key]
        if (typeof value === 'number' && Number.isFinite(value)) copy[key] = value
      }
      return copy
    }),
    links: links.map(edge => ({ ...edge, source: edge.from, target: edge.to } as SpatialLink)),
  }
}

/** Approach along the current viewing ray, including nodes at the origin or undefined coordinates. */
export function focusDestination(node: Partial<Position3D>, camera: Position3D, distance = 110) {
  const finite = (value: number | undefined) => Number.isFinite(value) ? value! : 0
  const lookAt = { x: finite(node.x), y: finite(node.y), z: finite(node.z) }
  const dx = finite(camera.x) - lookAt.x, dy = finite(camera.y) - lookAt.y, dz = finite(camera.z) - lookAt.z
  const length = Math.hypot(dx, dy, dz)
  const zoom = Math.max(35, Math.min(500, Number.isFinite(distance) ? distance : 110))
  const direction = length > .0001 ? { x: dx / length, y: dy / length, z: dz / length } : { x: 0, y: 0, z: 1 }
  return { lookAt, position: { x: lookAt.x + direction.x * zoom, y: lookAt.y + direction.y * zoom, z: lookAt.z + direction.z * zoom } }
}

export function spatialNodeRadius(node: Pick<SpatialNode, 'degree' | 'kind'>): number {
  return 2.5 + Math.sqrt(Math.min(25, Math.max(0, node.degree))) * .55 + (node.kind === 'topic' ? 1.2 : 0)
}
