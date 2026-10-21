<script setup lang="ts">
import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation } from 'd3-force'
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, triggerRef, useId, watch } from 'vue'
import type { GraphEdge, GraphNode } from './model'
import { captionLines, edgeGeometry, incidentSelection } from './graphPresentation'
import { locale, t } from '../../i18n'

interface LayoutNode extends GraphNode { x: number; y: number; vx?: number; vy?: number; fx?: number | null; fy?: number | null }
interface LayoutLink { source: string | LayoutNode; target: string | LayoutNode }
const props = defineProps<{
  nodes: GraphNode[]; edges: GraphEdge[]; selectedId: string; highlightedEdgeIds?: string[]
  expandedNodeIds?: string[]; reducedMotion: boolean
}>()
const emit = defineEmits<{ select: [id: string]; expand: [id: string]; dismiss: [id: string]; read: [id: string]; reset: [] }>()
const width = 1000, height = 700
const host = ref<HTMLElement>(), svgRef = ref<SVGSVGElement>()
// D3 mutates raw objects. A single explicit render per tick avoids proxying its physics engine.
const layoutNodes = shallowRef<LayoutNode[]>([])
const cache = new Map<string, LayoutNode>()
const transform = ref({ x: 0, y: 0, scale: 1 })
const camera = computed(() => `translate(${transform.value.x} ${transform.value.y}) scale(${transform.value.scale})`)
const pinned = ref(new Set<string>())
const menuId = ref(''), hoveredId = ref(''), selectedEdge = ref<GraphEdge>()
const markerId = `graph-arrow-${useId().replace(/:/g, '')}`
const zh = computed(() => locale.value === 'zh-CN')
const tr = (cn: string, en: string) => zh.value ? cn : en
const nodeMap = computed(() => new Map(layoutNodes.value.map(node => [node.id, node])))
const activeId = computed(() => hoveredId.value || props.selectedId)
const selection = computed(() => incidentSelection(activeId.value, props.edges))
const adjacent = computed(() => selection.value.nodeIds)
const activeNode = computed(() => nodeMap.value.get(props.selectedId))
const kinds = ['lesson', 'topic', 'concept', 'evidence', 'source'] as const
let simulation: ReturnType<typeof forceSimulation<LayoutNode>> | undefined
let drag: { node: LayoutNode; start: { x: number; y: number }; moved: boolean } | undefined
let pan: { x: number; y: number; tx: number; ty: number } | undefined
let suppressClick = false, cameraFrame = 0, autoFitted = false, pendingFocus = false

function radius(node: GraphNode) { return node.kind === 'topic' ? 38 : node.kind === 'lesson' ? 33 : 30 }
function color(kind: GraphNode['kind']) { return ({ lesson: '#68bdf6', topic: '#ffc454', concept: '#a5abb6', evidence: '#fb95af', source: '#b6d982' })[kind] }
function label(node: GraphNode) { return node.title[locale.value] || node.title['zh-CN'] }
function relationLabel(type: GraphEdge['type']) { return t(`learning.relation.${type === 'documented-by' ? 'documentedBy' : type === 'informed-by' ? 'informedBy' : type}`) }
const renderedEdges = computed(() => {
  const groups = new Map<string, GraphEdge[]>()
  for (const edge of props.edges) {
    const key = [edge.from, edge.to].sort().join('|')
    groups.set(key, [...(groups.get(key) ?? []), edge])
  }
  return props.edges.flatMap(edge => {
    const source = nodeMap.value.get(edge.from), target = nodeMap.value.get(edge.to)
    if (!source || !target) return []
    const siblings = groups.get([edge.from, edge.to].sort().join('|'))!
    const offset = (siblings.indexOf(edge) - (siblings.length - 1) / 2) * 52
    const bend = edge.from < edge.to ? offset : -offset
    return [{ ...edge, ...edgeGeometry(source, target, radius(source), radius(target), bend) }]
  })
})

watch(() => [props.nodes.map(n => n.id).join('|'), props.edges.map(e => e.id).join('|')].join('::'), () => {
  simulation?.stop()
  const center = cache.get(props.selectedId) ?? { x: width / 2, y: height / 2 }
  layoutNodes.value = props.nodes.map((node, index) => {
    let current = cache.get(node.id)
    if (!current) {
      current = { ...node, x: center.x + Math.cos(index * 2.4) * 100, y: center.y + Math.sin(index * 2.4) * 100 }
      cache.set(node.id, current)
    }
    return current
  })
  const links: LayoutLink[] = props.edges.map(edge => ({ source: edge.from, target: edge.to }))
  simulation = forceSimulation<LayoutNode>(layoutNodes.value)
    .force('link', forceLink<LayoutNode, LayoutLink>(links).id(node => node.id).distance(185).strength(.32))
    .force('charge', forceManyBody<LayoutNode>().strength(-1150).distanceMax(900))
    .force('center', forceCenter(width / 2, height / 2).strength(.045))
    .force('collision', forceCollide<LayoutNode>(node => radius(node) + 23).iterations(3))
    .velocityDecay(.38).alphaDecay(.035)
    .on('tick', () => triggerRef(layoutNodes))
    .on('end', () => {
      if (pendingFocus) { fitFocused(); pendingFocus = false }
      else if (!autoFitted) { fit(); autoFitted = true }
    })
  if (props.reducedMotion) { simulation.stop().tick(200); triggerRef(layoutNodes); fit(); autoFitted = true }
}, { immediate: true })
watch(() => props.reducedMotion, reduced => {
  if (reduced) { simulation?.stop().tick(200); triggerRef(layoutNodes) }
  else simulation?.alpha(.5).restart()
})
watch(() => props.selectedId, () => {
  selectedEdge.value = undefined
  if (menuId.value !== props.selectedId) menuId.value = ''
  pendingFocus = true
  nextTick(fitFocused)
})

function point(event: { clientX: number; clientY: number }) {
  const matrix = svgRef.value?.getScreenCTM()
  return matrix ? new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse()) : { x: 0, y: 0 }
}
function animateCamera(target: typeof transform.value) {
  cancelAnimationFrame(cameraFrame)
  if (props.reducedMotion) { transform.value = target; return }
  const from = { ...transform.value }, start = performance.now()
  function frame(now: number) {
    const p = Math.min(1, (now - start) / 240), eased = 1 - Math.pow(1 - p, 3)
    transform.value = { x: from.x + (target.x - from.x) * eased, y: from.y + (target.y - from.y) * eased, scale: from.scale + (target.scale - from.scale) * eased }
    if (p < 1) cameraFrame = requestAnimationFrame(frame)
  }
  cameraFrame = requestAnimationFrame(frame)
}
function fit() {
  pendingFocus = false
  fitGroup(layoutNodes.value)
}
function fitFocused() {
  const ids = incidentSelection(props.selectedId, props.edges).nodeIds
  fitGroup(layoutNodes.value.filter(node => ids.has(node.id)), 115)
}
function fitGroup(nodes: LayoutNode[], padding = 85) {
  if (!nodes.length) return
  const xs = nodes.map(n => n.x), ys = nodes.map(n => n.y)
  const minX = Math.min(...xs) - padding, maxX = Math.max(...xs) + padding
  const minY = Math.min(...ys) - padding, maxY = Math.max(...ys) + padding
  const scale = Math.max(.08, Math.min(1.6, width / Math.max(500, maxX - minX), (height - 140) / Math.max(350, maxY - minY)))
  animateCamera({ scale, x: width / 2 - (minX + maxX) / 2 * scale, y: (height - 50) / 2 - (minY + maxY) / 2 * scale })
}
function zoom(factor: number, center = { x: width / 2, y: height / 2 }) {
  pendingFocus = false
  cancelAnimationFrame(cameraFrame)
  const old = transform.value, scale = Math.max(.08, Math.min(4, old.scale * factor)), ratio = scale / old.scale
  transform.value = { scale, x: center.x - (center.x - old.x) * ratio, y: center.y - (center.y - old.y) * ratio }
}
function wheel(event: WheelEvent) { event.preventDefault(); zoom(Math.exp(-event.deltaY * .0015), point(event)) }
function downNode(event: PointerEvent, node: LayoutNode) {
  if (event.button !== 0) return
  event.stopPropagation(); cancelAnimationFrame(cameraFrame)
  pendingFocus = false
  ;(event.currentTarget as SVGElement).setPointerCapture(event.pointerId)
  drag = { node, start: point(event), moved: false }
  node.fx = node.x; node.fy = node.y
}
function downBackground(event: PointerEvent) {
  if (event.button !== 0) return
  cancelAnimationFrame(cameraFrame); menuId.value = ''; selectedEdge.value = undefined
  pendingFocus = false
  svgRef.value?.setPointerCapture(event.pointerId)
  const p = point(event)
  pan = { x: p.x, y: p.y, tx: transform.value.x, ty: transform.value.y }
}
function move(event: PointerEvent) {
  const p = point(event)
  if (drag) {
    if (!drag.moved && Math.hypot(p.x - drag.start.x, p.y - drag.start.y) < 4) return
    drag.moved = true
    drag.node.fx = (p.x - transform.value.x) / transform.value.scale
    drag.node.fy = (p.y - transform.value.y) / transform.value.scale
    if (props.reducedMotion) { drag.node.x = drag.node.fx; drag.node.y = drag.node.fy; triggerRef(layoutNodes) }
    else simulation?.alphaTarget(.16).restart()
  } else if (pan) transform.value = { ...transform.value, x: pan.tx + p.x - pan.x, y: pan.ty + p.y - pan.y }
}
function up() {
  if (drag) {
    suppressClick = drag.moved
    if (!pinned.value.has(drag.node.id)) { drag.node.fx = null; drag.node.fy = null }
    if (props.reducedMotion) { simulation?.stop().tick(100); triggerRef(layoutNodes) }
    else simulation?.alphaTarget(0)
  }
  drag = undefined; pan = undefined
}
function select(node: LayoutNode) {
  if (suppressClick) { suppressClick = false; return }
  emit('select', node.id); menuId.value = node.id; selectedEdge.value = undefined
  pendingFocus = true; nextTick(fitFocused)
}
function togglePin(node: LayoutNode) {
  const next = new Set(pinned.value)
  if (next.has(node.id)) { next.delete(node.id); node.fx = null; node.fy = null }
  else { next.add(node.id); node.fx = node.x; node.fy = node.y }
  pinned.value = next
  if (!props.reducedMotion) simulation?.alpha(.25).restart()
}
function relayout() {
  for (const node of cache.values()) { node.fx = null; node.fy = null }
  pinned.value = new Set(); menuId.value = ''; emit('reset'); autoFitted = false
  simulation?.alpha(.9)
  if (props.reducedMotion) { simulation?.stop().tick(200); triggerRef(layoutNodes); fit() }
  else simulation?.restart()
}
async function fullscreen() {
  try {
    if (document.fullscreenElement === host.value) await document.exitFullscreen()
    else await host.value?.requestFullscreen()
  } catch { /* Fullscreen may be disabled by the embedding browser. */ }
}
function sector(index: number) {
  const a = (index * 120 - 90) * Math.PI / 180, b = a + 118 * Math.PI / 180
  const p = (r: number, theta: number) => `${Math.cos(theta) * r} ${Math.sin(theta) * r}`
  return `M ${p(43, a)} L ${p(65, a)} A 65 65 0 0 1 ${p(65, b)} L ${p(43, b)} A 43 43 0 0 0 ${p(43, a)} Z`
}
function menuAction(index: number, node: LayoutNode) {
  if (index === 0) emit('expand', node.id)
  else if (index === 1) togglePin(node)
  else { emit('dismiss', node.id); menuId.value = '' }
}
function menuLabel(index: number, node: LayoutNode) {
  if (index === 0) return props.expandedNodeIds?.includes(node.id) ? tr('收起邻居', 'Collapse neighbors') : tr('展开邻居', 'Expand neighbors')
  if (index === 1) return pinned.value.has(node.id) ? tr('取消固定', 'Unpin node') : tr('固定节点', 'Pin node')
  return tr('从画布隐藏', 'Dismiss node')
}
function keyMove(event: KeyboardEvent, node: LayoutNode) {
  if (event.key === 'Escape') { menuId.value = ''; return }
  if (!event.key.startsWith('Arrow')) return
  event.preventDefault()
  const edge = props.edges.find(e => e.from === node.id || e.to === node.id)
  const id = edge && (edge.from === node.id ? edge.to : edge.from)
  if (id) { emit('select', id); svgRef.value?.querySelector<SVGGElement>(`[data-node-id="${id}"]`)?.focus() }
}
onBeforeUnmount(() => { simulation?.stop(); cancelAnimationFrame(cameraFrame) })
</script>

<template>
  <section ref="host" class="graph-canvas" :aria-label="t('learning.graphDescription')">
    <div class="graph-labels">
      <span class="graph-labels__all">{{ tr('节点', 'Nodes') }} ({{ nodes.length }})</span>
      <span v-for="kind in kinds.filter(k => nodes.some(n => n.kind === k))" :key="kind" class="graph-label-chip" :style="{ background: color(kind) }">{{ t(`learning.kind.${kind}`) }} ({{ nodes.filter(n => n.kind === kind).length }})</span>
      <span class="graph-labels__relations">{{ tr('关系', 'Relationships') }} ({{ edges.length }})</span>
    </div>
    <svg ref="svgRef" viewBox="0 0 1000 700" :aria-label="t('learning.graphDescription')" role="group"
      @pointerdown.self="downBackground" @pointermove="move" @pointerup="up" @pointercancel="up" @lostpointercapture="up" @wheel="wheel" @contextmenu.prevent>
      <defs><marker :id="markerId" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M 0 0 L 10 5 L 0 10 z" fill="#a5abb6" /></marker></defs>
      <rect width="1000" height="700" fill="white" @pointerdown="downBackground" />
      <g :transform="camera">
        <g v-for="edge in renderedEdges" :key="edge.id" class="graph-edge" :class="{ 'is-dim': activeId && !selection.edgeIds.has(edge.id), 'is-related': selection.edgeIds.has(edge.id), 'is-path': highlightedEdgeIds?.includes(edge.id), 'is-selected': selectedEdge?.id === edge.id }"
          @pointerdown.stop @click.stop="selectedEdge = edge; menuId = ''">
          <path class="graph-edge__hit" :d="edge.path" />
          <path class="graph-edge__line" :d="edge.path" :marker-end="`url(#${markerId})`" />
          <text :transform="`translate(${edge.x} ${edge.y}) rotate(${edge.angle})`" dy="-3">{{ relationLabel(edge.type) }}</text>
        </g>
        <g v-for="node in layoutNodes" :key="node.id" :data-node-id="node.id" class="graph-node" :class="{ 'is-selected': node.id === selectedId, 'is-dim': activeId && node.id !== activeId && !adjacent.has(node.id) }"
          :transform="`translate(${node.x} ${node.y})`" role="button" tabindex="0" :aria-label="label(node)" :aria-pressed="node.id === selectedId"
          @pointerdown="downNode($event, node)" @click.stop="select(node)" @dblclick.stop.prevent="emit('expand', node.id)"
          @contextmenu.stop.prevent="select(node)" @pointerenter="hoveredId = node.id" @pointerleave="hoveredId = ''"
          @keydown.enter.prevent="select(node)" @keydown.space.prevent="select(node)" @keydown="keyMove($event, node)">
          <circle class="graph-node__halo" :r="radius(node) + 5" :style="{ stroke: color(node.kind) }" />
          <circle class="graph-node__circle" :r="radius(node)" :fill="color(node.kind)" />
          <text class="graph-node__caption" :style="{ fontSize: zh ? '13px' : '11px' }">
            <tspan v-for="(line, index) in captionLines(label(node), zh, zh ? 4 : 9)" :key="index" x="0" :y="(index - (captionLines(label(node), zh, zh ? 4 : 9).length - 1) / 2) * 14 + 4">{{ line }}</tspan>
          </text>
          <circle v-if="pinned.has(node.id)" class="graph-node__pin" :cx="radius(node) - 4" :cy="-radius(node) + 4" r="5" />
          <g v-if="menuId === node.id" class="graph-menu" @pointerdown.stop @click.stop @dblclick.stop>
            <g v-for="index in [0, 1, 2]" :key="index" role="button" tabindex="0" :aria-label="menuLabel(index, node)"
              @click.stop="menuAction(index, node)" @keydown.enter.stop.prevent="menuAction(index, node)" @keydown.space.stop.prevent="menuAction(index, node)">
              <title>{{ menuLabel(index, node) }}</title><path :d="sector(index)" />
              <text :x="Math.cos((index * 120 - 31) * Math.PI / 180) * 54" :y="Math.sin((index * 120 - 31) * Math.PI / 180) * 54 + 4">{{ index === 0 ? (expandedNodeIds?.includes(node.id) ? '−' : '+') : index === 1 ? (pinned.has(node.id) ? '○' : '◆') : '×' }}</text>
            </g>
          </g>
        </g>
      </g>
    </svg>
    <div class="graph-controls">
      <button type="button" @click="relayout" :aria-label="tr('恢复隐藏节点并重新布局', 'Restore nodes and relayout')" :title="tr('恢复并重新布局', 'Restore and relayout')">↻</button>
      <button type="button" @click="fullscreen" :aria-label="tr('切换全屏', 'Toggle fullscreen')" :title="tr('全屏', 'Fullscreen')">⛶</button>
      <span></span><button type="button" @click="zoom(1.25)" :aria-label="t('learning.zoomIn')">+</button>
      <button type="button" @click="fit" :aria-label="tr('适应画布', 'Fit to screen')" :title="tr('适应画布', 'Fit to screen')">⊡</button>
      <button type="button" @click="zoom(.8)" :aria-label="t('learning.zoomOut')">−</button>
    </div>
    <div class="graph-inspector" aria-live="polite">
      <template v-if="selectedEdge"><b>{{ relationLabel(selectedEdge.type) }}</b><span>{{ selectedEdge.reason[locale] }}</span></template>
      <template v-else-if="activeNode"><b class="graph-inspector__label" :style="{ background: color(activeNode.kind) }">{{ t(`learning.kind.${activeNode.kind}`) }}</b><strong>{{ label(activeNode) }}</strong><code>{{ activeNode.id }}</code><button type="button" @click="emit('read', activeNode.id)">{{ tr('阅读知识 →', 'Read knowledge →') }}</button></template>
      <span v-else>{{ tr('选择节点或关系查看详情', 'Select a node or relationship') }}</span>
    </div>
    <div class="graph-hint">{{ tr('拖动节点 · 双击展开/收起 · 滚轮缩放 · 右键菜单', 'Drag nodes · double-click to expand/collapse · scroll to zoom · right-click menu') }}</div>
  </section>
</template>

<style scoped>
.graph-canvas{position:relative;display:flex;flex-direction:column;min-height:520px;height:100%;overflow:hidden;border:1px solid #d4d8dd;border-radius:4px;background:#fff;color:#454b53;font-family:"Segoe UI","Microsoft YaHei",sans-serif}
.graph-canvas:fullscreen{width:100vw;height:100vh;border:0;border-radius:0}
.graph-labels{position:absolute;z-index:2;top:0;left:0;right:0;display:flex;align-items:center;flex-wrap:wrap;gap:7px;padding:13px 16px;background:#fffffff5;border-bottom:1px solid #f1f2f4;font-size:11px;pointer-events:none}
.graph-labels__all{font-weight:600;color:#555d66}.graph-label-chip{padding:4px 9px;border-radius:14px;color:#26323e}.graph-labels__relations{margin-left:auto;color:#7b818a}
.graph-canvas svg{display:block;flex:1;width:100%;height:100%;min-height:470px;touch-action:none;cursor:grab}.graph-canvas svg:active{cursor:grabbing}
.graph-edge{cursor:pointer;transition:opacity .15s}.graph-edge__line{fill:none;stroke:#a5abb6;stroke-width:1.2}.graph-edge__hit{fill:none;stroke:transparent;stroke-width:14}.graph-edge text{fill:#5f6570;font-size:10px;text-anchor:middle;paint-order:stroke;stroke:white;stroke-width:5px;stroke-linejoin:round}
.graph-edge.is-path .graph-edge__line{stroke:#d89f47;stroke-width:2}.graph-edge.is-selected .graph-edge__line,.graph-edge:hover .graph-edge__line{stroke:#4e6a88;stroke-width:2.5}
.is-dim{opacity:.18}.graph-node{cursor:pointer;outline:none;transition:opacity .15s}.graph-node__circle{stroke:#00000020;stroke-width:1.5}.graph-node__halo{fill:none;stroke-width:7;opacity:0}.graph-node.is-selected .graph-node__halo{opacity:.3}.graph-node:hover .graph-node__halo,.graph-node:focus-visible .graph-node__halo{opacity:.5}
.graph-node__caption{fill:#263342;text-anchor:middle;font-weight:500;pointer-events:none}.graph-node__pin{fill:#3b4654;stroke:white;stroke-width:2}
.graph-menu path{fill:#eef0f3;stroke:white;stroke-width:1.5}.graph-menu g:hover path,.graph-menu g:focus-visible path{fill:#b8c7d8}.graph-menu g{outline:none;cursor:pointer}.graph-menu text{font-size:18px;fill:#526171;text-anchor:middle;pointer-events:none}
.graph-controls{position:absolute;right:13px;bottom:78px;display:flex;flex-direction:column;gap:2px;padding:4px;border:1px solid #e0e3e7;border-radius:5px;background:white;box-shadow:0 2px 7px #17212c0b}.graph-controls button{width:30px;height:30px;border:0;border-radius:3px;background:white;color:#747e8d;font-size:22px;cursor:pointer}.graph-controls button:hover{background:#edf2f7;color:#335578}.graph-controls button:focus-visible{outline:2px solid #527ca3}.graph-controls>span{border-top:1px solid #e9ebef;margin:3px}
.graph-inspector{position:absolute;left:0;bottom:25px;right:0;display:flex;align-items:center;flex-wrap:wrap;gap:9px;min-height:44px;padding:10px 15px;border-top:1px solid #e9ebee;background:#fafbfcf5;font-size:11px;pointer-events:none}.graph-inspector b{font-weight:600}.graph-inspector strong{font-weight:500}.graph-inspector code{color:#8d939b;font-size:10px}.graph-inspector__label{padding:4px 8px;border-radius:12px}.graph-inspector>span{max-width:85%;color:#69717c;line-height:1.6}
.graph-hint{position:absolute;left:0;right:0;bottom:0;height:25px;padding:6px 14px;border-top:1px solid #f0f1f3;background:#fff;color:#9399a3;font-size:10px}
.graph-inspector button{margin-left:auto;padding:5px 9px;border:1px solid #d8dfe7;border-radius:3px;background:white;color:#426d96;font:inherit;cursor:pointer;pointer-events:auto}.graph-inspector button:hover{background:#edf4fb}
@media(max-width:700px){.graph-canvas{min-height:460px}.graph-labels{gap:4px;padding:9px;font-size:9px}.graph-label-chip{padding:3px 6px}.graph-hint{font-size:8px}.graph-labels__relations{margin-left:0}.graph-controls{right:8px}}
@media(prefers-reduced-motion:reduce){.graph-node,.graph-edge{transition:none}}
.graph-edge.is-related .graph-edge__line{stroke:#377aaa;stroke-width:2.4}.graph-edge.is-related text{fill:#215476;font-weight:600}
.graph-edge text{font-size:12px;stroke-width:3.5}.graph-node__caption{font-weight:600;text-rendering:geometricPrecision}
.graph-node.is-dim .graph-node__caption{display:none}.graph-edge.is-dim{opacity:.12}
.graph-labels,.graph-inspector{font-size:13px}.graph-hint{font-size:12px}.graph-inspector code{font-size:12px}
</style>
