interface Position { id: string; x: number; y: number }

/** Camera intent persists across layout ticks. A manual gesture cancels it until an explicit fit/selection. */
export function createFitCamera(width: number, height: number) {
  let intent: 'all' | 'focus' | undefined
  return {
    request(mode: 'all' | 'focus') { intent = mode },
    cancel() { intent = undefined },
    target(nodes: Position[], focused: ReadonlySet<string>) {
      if (!intent) return
      const group = (intent === 'focus' ? nodes.filter(node => focused.has(node.id)) : nodes)
        .filter(node => Number.isFinite(node.x) && Number.isFinite(node.y))
      if (!group.length) return
      const padding = intent === 'focus' ? 115 : 85
      const minX = Math.min(...group.map(node => node.x)) - padding, maxX = Math.max(...group.map(node => node.x)) + padding
      const minY = Math.min(...group.map(node => node.y)) - padding, maxY = Math.max(...group.map(node => node.y)) + padding
      const scale = Math.max(.08, Math.min(1.6, width / Math.max(500, maxX - minX), (height - 140) / Math.max(350, maxY - minY)))
      return { scale, x: width / 2 - (minX + maxX) / 2 * scale, y: (height - 50) / 2 - (minY + maxY) / 2 * scale }
    },
  }
}
