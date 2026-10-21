<script setup lang="ts">
import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY } from 'd3-force'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { GraphEdge, GraphNode } from './model'
import { locale, t } from '../../i18n'

interface LayoutNode extends GraphNode { x?: number; y?: number; vx?: number; vy?: number; fx?: number | null; fy?: number | null }
interface LayoutLink { id: string; source: string | LayoutNode; target: string | LayoutNode; type: GraphEdge['type']; reason: GraphEdge['reason'] }

const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
  selectedId: string
  highlightedEdgeIds?: string[]
  reducedMotion: boolean
}>()
const emit = defineEmits<{ select: [id: string] }>()

const width = 1000
const height = 700
const stageRef = ref<SVGGElement>()
const layoutNodes = ref<LayoutNode[]>([])
const layoutLinks = ref<LayoutLink[]>([])
const transform = ref({ x: 0, y: 0, scale: 1 })
const transformText = computed(() => `translate(${transform.value.x} ${transform.value.y}) scale(${transform.value.scale})`)
const nodeById = computed(() => new Map(layoutNodes.value.map(node => [node.id, node])))
const localeKey = computed(() => locale.value)
let simulation: ReturnType<typeof forceSimulation<LayoutNode>> | undefined
let dragNode: LayoutNode | null = null
let panStart: { x: number; y: number; transformX: number; transformY: number } | null = null

watch(() => [props.nodes.map(node => node.id).join('|'), props.edges.map(edge => edge.id).join('|')].join('::'), () => {
  simulation?.stop()
  const current = new Map(layoutNodes.value.map(node => [node.id, node]))
  layoutNodes.value = props.nodes.map((node, index) => {
    const old = current.get(node.id)
    return { ...node, x: old?.x ?? width / 2 + Math.cos(index * 2.4) * 190, y: old?.y ?? height / 2 + Math.sin(index * 2.4) * 150 }
  })
  layoutLinks.value = props.edges.map(edge => ({ id: edge.id, source: edge.from, target: edge.to, type: edge.type, reason: edge.reason }))
  simulation = forceSimulation(layoutNodes.value)
    .force('link', forceLink<LayoutNode, LayoutLink>(layoutLinks.value).id(node => node.id)
      .distance(link => link.type === 'prerequisite' ? 112 : 145).strength(link => link.type === 'prerequisite' ? 0.72 : 0.38))
    .force('charge', forceManyBody<LayoutNode>().strength(-230).distanceMax(420))
    .force('center', forceCenter(width / 2, height / 2))
    .force('x', forceX<LayoutNode>(width / 2).strength(0.012))
    .force('y', forceY<LayoutNode>(height / 2).strength(0.012))
    .force('collision', forceCollide<LayoutNode>(node => radius(node) + 15).iterations(2))
    .alphaDecay(props.reducedMotion ? 0.13 : 0.025)
  if (props.reducedMotion) simulation.stop().tick(180)
}, { immediate: true })

watch(() => props.reducedMotion, reduced => {
  if (!simulation) return
  if (reduced) simulation.stop().tick(180)
  else simulation.alpha(0.35).restart()
})

function radius(node: GraphNode): number {
  return node.kind === 'topic' ? 26 : node.kind === 'lesson' ? 18 : node.kind === 'source' ? 16 : node.kind === 'concept' ? 14 : 12
}

function color(kind: GraphNode['kind']): string {
  return ({ lesson: '#75d6bd', topic: '#f3ba70', concept: '#89a8ff', evidence: '#d7a0ff', source: '#ff8b85' })[kind]
}

function label(node: GraphNode): string { return node.title[localeKey.value] || node.title['zh-CN'] }

function linkPoint(link: LayoutLink, side: 'source' | 'target'): LayoutNode {
  const value = link[side]
  return typeof value === 'string' ? nodeById.value.get(value)! : value
}

function linkLabel(type: GraphEdge['type']): string {
  const labels: Record<GraphEdge['type'], string> = {
    contains: 'learning.relation.contains', prerequisite: 'learning.relation.prerequisite', related: 'learning.relation.related',
    implements: 'learning.relation.implements', 'documented-by': 'learning.relation.documentedBy',
    documents: 'learning.relation.documents', 'informed-by': 'learning.relation.informedBy',
  }
  return t(labels[type])
}

function selectNode(node: LayoutNode): void {
  emit('select', node.id)
}

function onNodePointerDown(event: PointerEvent, node: LayoutNode): void {
  if (event.button !== 0) return
  event.stopPropagation()
  const svg = (event.currentTarget as SVGElement).ownerSVGElement
  if (!svg) return
  svg.setPointerCapture(event.pointerId)
  dragNode = node
  const p = localPoint(event, svg)
  dragNode.fx = (p.x - transform.value.x) / transform.value.scale
  dragNode.fy = (p.y - transform.value.y) / transform.value.scale
  simulation?.alphaTarget(0.24).restart()
  selectNode(node)
}

function localPoint(event: PointerEvent, svg: SVGSVGElement): { x: number; y: number } {
  const rect = svg.getBoundingClientRect()
  return { x: (event.clientX - rect.left) * width / rect.width, y: (event.clientY - rect.top) * height / rect.height }
}

function onBackgroundPointerDown(event: PointerEvent): void {
  if (event.button !== 0 || dragNode) return
  const svg = event.currentTarget as SVGSVGElement
  svg.setPointerCapture(event.pointerId)
  const point = localPoint(event, svg)
  panStart = { x: point.x, y: point.y, transformX: transform.value.x, transformY: transform.value.y }
}

function onPointerMove(event: PointerEvent): void {
  const svg = event.currentTarget as SVGSVGElement
  const point = localPoint(event, svg)
  if (dragNode) {
    dragNode.fx = (point.x - transform.value.x) / transform.value.scale
    dragNode.fy = (point.y - transform.value.y) / transform.value.scale
  } else if (panStart) {
    transform.value = { ...transform.value, x: panStart.transformX + point.x - panStart.x, y: panStart.transformY + point.y - panStart.y }
  }
}

function onPointerUp(): void {
  if (dragNode) { dragNode.fx = null; dragNode.fy = null; dragNode = null; simulation?.alphaTarget(0) }
  panStart = null
}

function onWheel(event: WheelEvent): void {
  event.preventDefault()
  const scale = Math.max(0.48, Math.min(2.4, transform.value.scale * (event.deltaY < 0 ? 1.1 : 0.9)))
  transform.value = { ...transform.value, scale }
}

function zoom(direction: -1 | 1): void {
  const scale = Math.max(0.48, Math.min(2.4, transform.value.scale * (direction > 0 ? 1.16 : 0.86)))
  transform.value = { ...transform.value, scale }
}

function moveSelection(event: KeyboardEvent, node: GraphNode): void {
  if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return
  event.preventDefault()
  const edges = props.edges.filter(edge => edge.from === node.id || edge.to === node.id)
  const candidates = edges.map(edge => edge.from === node.id ? edge.to : edge.from)
  const next = candidates.find(id => nodeById.value.has(id))
  if (next) {
    emit('select', next)
    requestAnimationFrame(() => document.getElementById(`graph-node-${next}`)?.focus())
  }
}

onBeforeUnmount(() => simulation?.stop())
</script>

<template>
  <section class="graph-canvas" :aria-label="t('learning.graphDescription')">
    <div class="graph-canvas__controls">
      <span class="graph-canvas__hint">{{ t('learning.dragHint') }}</span>
      <button type="button" :aria-label="t('learning.zoomOut')" @click="zoom(-1)">−</button>
      <span class="graph-canvas__zoom">{{ Math.round(transform.scale * 100) }}%</span>
      <button type="button" :aria-label="t('learning.zoomIn')" @click="zoom(1)">+</button>
      <button type="button" :aria-label="t('learning.resetView')" @click="transform = { x: 0, y: 0, scale: 1 }">{{ t('learning.reset') }}</button>
    </div>
    <svg viewBox="0 0 1000 700" role="group" :aria-label="t('learning.graphDescription')"
      @pointerdown.self="onBackgroundPointerDown" @pointermove="onPointerMove" @pointerup="onPointerUp" @pointercancel="onPointerUp" @wheel="onWheel">
      <defs>
        <pattern id="learning-grid" width="32" height="32" patternUnits="userSpaceOnUse">
          <circle cx="1" cy="1" r="1" fill="#627084" fill-opacity=".26" />
        </pattern>
        <marker id="learning-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#68798d" />
        </marker>
      </defs>
      <rect class="graph-canvas__background" width="1000" height="700" fill="url(#learning-grid)" @pointerdown="onBackgroundPointerDown" />
      <g ref="stageRef" :transform="transformText" class="graph-canvas__stage">
        <g v-for="edge in layoutLinks" :key="edge.id" class="graph-edge"
          :class="[`graph-edge--${edge.type}`, { 'graph-edge--path': props.highlightedEdgeIds?.includes(edge.id) }]">
          <line :x1="linkPoint(edge,'source').x ?? 0" :y1="linkPoint(edge,'source').y ?? 0"
            :x2="linkPoint(edge,'target').x ?? 0" :y2="linkPoint(edge,'target').y ?? 0" marker-end="url(#learning-arrow)" />
          <text :x="((linkPoint(edge,'source').x ?? 0) + (linkPoint(edge,'target').x ?? 0)) / 2"
            :y="((linkPoint(edge,'source').y ?? 0) + (linkPoint(edge,'target').y ?? 0)) / 2 - 5">{{ linkLabel(edge.type) }}</text>
        </g>
        <g v-for="node in layoutNodes" :key="node.id" :id="`graph-node-${node.id}`" class="graph-node"
          :class="[`graph-node--${node.kind}`, { 'is-selected': node.id === selectedId }]" role="button" tabindex="0"
          :aria-label="`${label(node)}. ${node.summary[localeKey] || node.summary['zh-CN']}`" :aria-pressed="node.id === selectedId"
          :transform="`translate(${node.x ?? width / 2},${node.y ?? height / 2})`"
          @pointerdown="onNodePointerDown($event, node)" @click.stop="selectNode(node)"
          @keydown.enter.prevent="selectNode(node)" @keydown.space.prevent="selectNode(node)" @keydown="moveSelection($event, node)">
          <circle :r="radius(node) + 6" class="graph-node__halo" />
          <circle :r="radius(node)" :fill="color(node.kind)" class="graph-node__dot" />
          <circle v-if="node.kind === 'lesson' && node.state === 'completed'" :r="radius(node) + 2" class="graph-node__completed" />
          <text :y="radius(node) + 19">{{ node.number ? `${String(node.number).padStart(2, '0')} · ` : '' }}{{ label(node) }}</text>
        </g>
      </g>
    </svg>
    <div class="graph-canvas__legend" aria-label="legend">
      <span v-for="kind in (['lesson','topic','concept','evidence','source'] as const)" :key="kind">
        <i :style="{ background: color(kind) }"></i>{{ t(`learning.kind.${kind}`) }}
      </span>
    </div>
  </section>
</template>

<style scoped>
.graph-canvas{position:relative;display:flex;flex-direction:column;min-height:540px;height:100%;overflow:hidden;border:1px solid #293544;border-radius:16px;background:#101821;color:#d9e3ed}
.graph-canvas__controls{position:absolute;z-index:2;top:14px;right:14px;display:flex;align-items:center;gap:7px;padding:6px;border:1px solid #344253;border-radius:10px;background:#18222ddd;backdrop-filter:blur(10px)}
.graph-canvas__controls button{width:30px;height:30px;border:1px solid #405063;border-radius:7px;background:#202c3a;color:#edf5fb;font-size:16px;cursor:pointer}
.graph-canvas__controls button:hover{border-color:#75d6bd;color:#75d6bd}.graph-canvas__controls button:focus-visible,.graph-node:focus-visible{outline:3px solid #f3ba70;outline-offset:3px}
.graph-canvas__zoom{min-width:38px;text-align:center;color:#adbdca;font-size:11px}.graph-canvas__hint{padding:0 8px;color:#93a2b2;font-size:11px}
.graph-canvas svg{display:block;width:100%;height:100%;min-height:480px;touch-action:none;cursor:grab}.graph-canvas svg:active{cursor:grabbing}.graph-canvas__background{cursor:grab}
.graph-edge line{stroke:#607084;stroke-width:1.3;stroke-opacity:.6}.graph-edge--prerequisite line{stroke:#f3ba70;stroke-width:1.7;stroke-opacity:.9}.graph-edge text{fill:#a5b2c0;font-size:9px;text-anchor:middle;paint-order:stroke;stroke:#101821;stroke-width:4px;stroke-linejoin:round;pointer-events:none}
.graph-edge--path line{stroke:#ff8b85;stroke-width:3.3;stroke-opacity:1}.graph-edge--path text{fill:#ffe5c5;font-weight:700}
.graph-node{cursor:pointer}.graph-node__halo{fill:transparent;stroke:transparent;stroke-width:1}.graph-node:hover .graph-node__halo,.graph-node.is-selected .graph-node__halo{fill:#ffffff0c;stroke:#b6cadb88}.graph-node.is-selected .graph-node__dot{stroke:#f7f6ee;stroke-width:3}.graph-node__dot{stroke:#101821;stroke-width:2}.graph-node__completed{fill:none;stroke:#75d6bd;stroke-width:1;stroke-dasharray:3 3}.graph-node text{fill:#e5edf4;font-size:11px;text-anchor:middle;paint-order:stroke;stroke:#101821;stroke-width:3px;stroke-linejoin:round;pointer-events:none}.graph-node--topic text{font-weight:700;font-size:12px}.graph-node--evidence text,.graph-node--source text{fill:#c7d0dc}
.graph-canvas__legend{position:absolute;left:15px;bottom:13px;display:flex;flex-wrap:wrap;gap:13px;padding:8px 10px;border:1px solid #344253;border-radius:9px;background:#17212ddf;color:#c5d0dc;font-size:10px}.graph-canvas__legend span{display:flex;align-items:center;gap:6px}.graph-canvas__legend i{width:8px;height:8px;border-radius:50%}
@media(max-width:700px){.graph-canvas{min-height:420px}.graph-canvas svg{min-height:360px}.graph-canvas__hint{display:none}.graph-canvas__legend{gap:8px;right:10px;left:10px;justify-content:center}}
</style>
