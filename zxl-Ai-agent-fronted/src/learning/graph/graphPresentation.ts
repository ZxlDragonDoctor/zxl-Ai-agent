export interface Point { x: number; y: number }
export interface SceneEdge { from: string; to: string }

export function incidentSelection(id: string, edges: Array<SceneEdge & { id: string }>) {
  const nodeIds = new Set([id]), edgeIds = new Set<string>()
  for (const edge of edges) {
    if (edge.from !== id && edge.to !== id) continue
    nodeIds.add(edge.from); nodeIds.add(edge.to); edgeIds.add(edge.id)
  }
  return { nodeIds, edgeIds }
}

/** A click opens this branch in both views, preserving other explored branches. */
export function revealSelection(id: string, edges: SceneEdge[], expanded: Set<string>, collapsed: Set<string>, dismissed: Set<string>) {
  const result = { expanded: new Set(expanded), collapsed: new Set(collapsed), dismissed: new Set(dismissed) }
  result.expanded.add(id); result.collapsed.delete(id); result.dismissed.delete(id)
  for (const edge of edges) {
    if (edge.from === id || edge.to === id) { result.dismissed.delete(edge.from); result.dismissed.delete(edge.to) }
  }
  return result
}

/** Explore only reachable expansions; collapsing one branch preserves shared neighbors. */
export function discoverScene(root: string, hops: number, edges: SceneEdge[], expanded: Set<string>, collapsed: Set<string>): Set<string> {
  const adjacent = new Map<string, Set<string>>()
  for (const edge of edges) {
    for (const [a, b] of [[edge.from, edge.to], [edge.to, edge.from]]) {
      if (!adjacent.has(a!)) adjacent.set(a!, new Set())
      adjacent.get(a!)!.add(b!)
    }
  }
  const visible = new Set([root])
  let frontier = new Set([root])
  for (let depth = 0; depth < hops; depth++) {
    const next = new Set<string>()
    for (const id of frontier) {
      if (collapsed.has(id)) continue
      for (const neighbor of adjacent.get(id) ?? []) {
        if (!visible.has(neighbor)) next.add(neighbor)
        visible.add(neighbor)
      }
    }
    frontier = next
  }
  let changed = true
  while (changed) {
    changed = false
    for (const id of expanded) {
      if (!visible.has(id) || collapsed.has(id)) continue
      for (const neighbor of adjacent.get(id) ?? []) {
        if (!visible.has(neighbor)) { visible.add(neighbor); changed = true }
      }
    }
  }
  return visible
}

/** Arrows end outside circles; relationship captions remain upright in either direction. */
export function edgeGeometry(source: Point, target: Point, sourceRadius: number, targetRadius: number, bend = 0) {
  const dx = target.x - source.x, dy = target.y - source.y
  const distance = Math.hypot(dx, dy)
  const ux = distance < .001 ? 1 : dx / distance, uy = distance < .001 ? 0 : dy / distance
  // A gravity-like sag, plus sideways slack for near-vertical edges, keeps every rope curved.
  const sag = distance < .001 ? 0 : Math.min(130, Math.max(24, distance * .24))
  const control = {
    x: (source.x + target.x) / 2 - uy * bend + (Math.abs(dx) < Math.abs(dy) * .35 ? sag * .65 : 0),
    y: (source.y + target.y) / 2 + ux * bend + sag,
  }
  const startLength = Math.hypot(control.x - source.x, control.y - source.y) || 1
  const endLength = Math.hypot(target.x - control.x, target.y - control.y) || 1
  const sr = Math.min(sourceRadius + 2, distance / 3), tr = Math.min(targetRadius + 7, distance / 3)
  const start = { x: source.x + (control.x - source.x) / startLength * sr, y: source.y + (control.y - source.y) / startLength * sr }
  const end = { x: target.x - (target.x - control.x) / endLength * tr, y: target.y - (target.y - control.y) / endLength * tr }
  let angle = Math.atan2(end.y - start.y, end.x - start.x) * 180 / Math.PI
  if (angle > 90) angle -= 180
  if (angle < -90) angle += 180
  return {
    path: `M ${start.x} ${start.y} Q ${control.x} ${control.y} ${end.x} ${end.y}`,
    x: (start.x + 2 * control.x + end.x) / 4,
    y: (start.y + 2 * control.y + end.y) / 4,
    angle,
  }
}

export function captionLines(text: string, chinese: boolean, maxChars?: number): string[] {
  const limit = maxChars ?? (chinese ? 6 : 12)
  const tokens = chinese ? Array.from(text) : text.split(/\s+/).flatMap(word => word.match(new RegExp(`.{1,${limit}}`, 'g')) ?? [])
  const lines: string[] = []
  let line = ''
  for (const token of tokens) {
    const next = line ? line + (chinese ? '' : ' ') + token : token
    if (next.length > limit && line) { lines.push(line); line = token } else line = next
  }
  if (line) lines.push(line)
  if (lines.length > 4) return [...lines.slice(0, 3), lines[3]!.slice(0, limit - 1) + '…']
  return lines
}
