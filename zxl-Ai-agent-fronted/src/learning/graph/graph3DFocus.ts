interface FocusPoint { id: string; x?: number; y?: number; z?: number }

/** Finite coordinates are not evidence that a reheated layout has settled. */
export function createFocusSettler(selectedId: string, expectedIds: Iterable<string>, startedAt: number) {
  const expected = new Set(expectedIds)
  expected.add(selectedId)
  let stableSince: number | undefined
  let reference = new Map<string, { x: number; y: number; z: number }>()
  return (nodes: FocusPoint[], now: number): boolean => {
    const current = new Map(nodes.filter(node => expected.has(node.id)).map(node => [node.id, node]))
    const valid = [...expected].every(id => {
      const node = current.get(id)
      return node !== undefined && [node.x, node.y, node.z].every(Number.isFinite)
    })
    if (!valid) { stableSince = undefined; reference.clear(); return false }
    const changed = [...current].some(([id, node]) => {
      const previous = reference.get(id)
      return !previous || Math.hypot(node.x! - previous.x, node.y! - previous.y, node.z! - previous.z) > 1.5
    })
    if (changed) {
      stableSince = now
      reference = new Map([...current].map(([id, node]) => [id, { x: node.x!, y: node.y!, z: node.z! }]))
    }
    // Natural ambient sway must not block focus forever. The deadline only applies
    // after every expected node has real coordinates; an empty graph never qualifies.
    return now - startedAt >= 2400 || (now - startedAt >= 450 && stableSince !== undefined && now - stableSince >= 250)
  }
}
