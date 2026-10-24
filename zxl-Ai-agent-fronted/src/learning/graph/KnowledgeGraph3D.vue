<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AdditiveBlending, BufferGeometry, CanvasTexture, Color, Float32BufferAttribute, Group, Mesh, MeshBasicMaterial, Points, PointsMaterial, SphereGeometry, Sprite, SpriteMaterial, Vector3 } from 'three'
import type { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { CSS2DObject, CSS2DRenderer } from 'three/addons/renderers/CSS2DRenderer.js'
import type { PerspectiveCamera } from 'three'
import type { ConfigOptions, ForceGraph3DInstance } from '3d-force-graph'
import type { GraphEdge, GraphNode } from './model'
import { createSpatialGraph, neighborhoodDestination, spatialNodeRadius, spatialLinkCurve } from './graph3DModel'
import { incidentSelection } from './graphPresentation'
import { createSpatialMotion, spatialMotionPolicy } from './graph3DMotion'
import { createFocusSettler } from './graph3DFocus'
import type { SpatialLink, SpatialNode } from './graph3DModel'
import { locale, t } from '../../i18n'

const props = defineProps<{ nodes: GraphNode[]; edges: GraphEdge[]; selectedId: string; highlightedEdgeIds?: string[]; reducedMotion: boolean; reading?: boolean }>()
const emit = defineEmits<{ select: [id: string]; read: [id: string]; browse: []; unavailable: []; close: []; anchor: [point: { x: number; y: number; width: number; height: number; visible: boolean }] }>()
const host = ref<HTMLElement>(), viewport = ref<HTMLElement>()
const ready = ref(false), failed = ref(false), paused = ref(false), showLabels = ref(false)
const hovered = ref(''), focused = ref(props.reading ? props.selectedId : ''), selectedEdge = ref<GraphEdge>()
const tr = (cn: string, en: string) => locale.value === 'zh-CN' ? cn : en
const title = (node: GraphNode) => node.title[locale.value] || node.title['zh-CN']
const currentNode = computed(() => props.nodes.find(node => node.id === props.selectedId))
const motion = computed(() => !paused.value && !props.reducedMotion)
const motionPolicy = computed(() => spatialMotionPolicy(motion.value, !!props.reading))
const activeId = computed(() => props.reading ? props.selectedId : hovered.value)
const selection = computed(() => incidentSelection(activeId.value, props.edges))
const neighborIds = computed(() => selection.value.nodeIds)
const colors = { lesson: '#5bbdff', topic: '#ffcd7b', concept: '#ac98ff', evidence: '#51d9b5', source: '#ff91b9' }
interface Visual { group: Group; sphere: Mesh<SphereGeometry, MeshBasicMaterial>; halo: Sprite; caption: CSS2DObject; captionLocale: string; emphasis: number; phase: number }
const visuals = new Map<string, Visual>()
const sphereGeometry = new SphereGeometry(1, 18, 12)
let graph: ForceGraph3DInstance<SpatialNode, SpatialLink> | undefined
let glowTexture: CanvasTexture | undefined, stars: Points | undefined
let resizeObserver: ResizeObserver | undefined, intersectionObserver: IntersectionObserver | undefined
let resumeTimer: ReturnType<typeof setTimeout> | undefined, fitTimer: ReturnType<typeof setTimeout> | undefined
let destroyed = false, intersecting = true, orbitHeld = false, fitted = false
let controls: OrbitControls | undefined
let renderCanvas: HTMLCanvasElement | undefined
let labelsRenderer: CSS2DRenderer | undefined
let focusFrame = 0
let lastFrameUpdate = 0
let lastAnchor = ''
const projected = new Vector3()
const white = new Color('#ffffff')
const ambientMotion = createSpatialMotion()

function relationship(type: GraphEdge['type']) { return t(`learning.relation.${type === 'documented-by' ? 'documentedBy' : type === 'informed-by' ? 'informedBy' : type}`) }
function tooltip(text: string) {
  const div = document.createElement('div')
  div.textContent = text
  Object.assign(div.style, { padding: '8px 11px', color: '#e3edf9', background: '#111c30ed', border: '1px solid #36465d', borderRadius: '6px', fontSize: '12px', maxWidth: '300px', whiteSpace: 'normal' })
  return div
}
function glow() {
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 128
  const ctx = canvas.getContext('2d')!
  const gradient = ctx.createRadialGradient(64, 64, 0, 64, 64, 64)
  gradient.addColorStop(0, '#ffffff'); gradient.addColorStop(.15, '#ffffffce'); gradient.addColorStop(.42, '#ffffff30'); gradient.addColorStop(1, '#ffffff00')
  ctx.fillStyle = gradient; ctx.fillRect(0, 0, 128, 128)
  return new CanvasTexture(canvas)
}
function setCaption(visual: Visual, node: SpatialNode) {
  visual.caption.element.textContent = title(node)
  visual.captionLocale = locale.value
}
function visualFor(node: SpatialNode): Visual {
  const old = visuals.get(node.id)
  if (old) { if (old.captionLocale !== locale.value) setCaption(old, node); styleVisual(node, old); return old }
  const group = new Group()
  const sphere = new Mesh(sphereGeometry, new MeshBasicMaterial({ color: colors[node.kind], transparent: true }))
  const halo = new Sprite(new SpriteMaterial({ map: glowTexture, color: colors[node.kind], transparent: true, blending: AdditiveBlending, depthWrite: false }))
  const element = document.createElement('div')
  element.className = 'knowledge-node-label'
  const caption = new CSS2DObject(element)
  caption.center.set(.5, 1)
  group.add(sphere, halo, caption)
  let hash = 0
  for (const char of node.id) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  const result = { group, sphere, halo, caption, captionLocale: locale.value, emphasis: 1, phase: (hash % 628) / 100 }
  visuals.set(node.id, result); setCaption(result, node); styleVisual(node, result)
  return result
}
function styleVisual(node: SpatialNode, visual: Visual) {
  const active = activeId.value, neighbors = neighborIds.value
  const emphasized = node.id === active
  const dimmed = !!active && !neighbors.has(node.id) && node.id !== active
  const related = !!active && neighbors.has(node.id) && !emphasized
  const radius = spatialNodeRadius(node) * (emphasized ? 1.5 : related ? 1.2 : 1)
  visual.emphasis = dimmed ? .06 : emphasized ? .95 : related ? .72 : .4
  visual.sphere.scale.setScalar(radius * (emphasized ? .65 : .45))
  visual.sphere.material.color.set(emphasized ? '#ffffff' : colors[node.kind]).lerp(white, related ? .55 : .15)
  visual.sphere.material.opacity = dimmed ? .1 : 1
  visual.halo.scale.setScalar(radius * (emphasized ? 5 : 4))
  ;(visual.halo.material as SpriteMaterial).opacity = visual.emphasis
  visual.caption.position.set(0, radius + 5, 0)
  visual.caption.visible = !dimmed && (showLabels.value || emphasized || neighbors.has(node.id) || node.kind === 'topic')
  visual.caption.element.classList.toggle('knowledge-node-label--selected', emphasized)
}
// Reuse the graph render loop: no extra timer/RAF that keeps running offscreen.
function updateFrame() {
  if (!graph || destroyed) return
  const now = performance.now(), seconds = now / 1000
  for (const visual of visuals.values()) {
    const pulse = motion.value ? .92 + .05 * Math.sin(seconds * .7 + visual.phase) + .03 * Math.sin(seconds * 1.37 + visual.phase * 2) : 1
    ;(visual.halo.material as SpriteMaterial).opacity = visual.emphasis * pulse
  }
  if (now - lastFrameUpdate < 80) return
  lastFrameUpdate = now
  // Keep captions sparse and resolve screen-space collisions, prioritizing the selected star.
  const width = graph.width(), height = graph.height(), occupied: Array<{ x: number; y: number; w: number; h: number }> = []
  const nodes = [...graph.graphData().nodes].sort((a, b) => Number(b.id === activeId.value) - Number(a.id === activeId.value) || Number(b.kind === 'topic') - Number(a.kind === 'topic') || b.degree - a.degree)
  let neighborLabels = 0
  for (const node of nodes) {
    const visual = visuals.get(node.id)
    if (!visual || !visual.caption.visible) continue
    projected.set(node.x ?? 0, node.y ?? 0, node.z ?? 0).project(graph.camera())
    const x = (projected.x + 1) * width / 2, y = (1 - projected.y) * height / 2
    const w = visual.caption.element.offsetWidth || 140, h = visual.caption.element.offsetHeight || 22
    const selected = node.id === activeId.value
    const overlaps = occupied.some(box => Math.abs(x - box.x) < (w + box.w) / 2 + 12 && Math.abs(y - box.y) < (h + box.h) / 2 + 16)
    const capped = !showLabels.value && !selected && node.kind !== 'topic' && neighborLabels >= 5
    const visible = projected.z > -1 && projected.z < 1 && x > 12 && x < width - 12 && y > 110 && y < height - 50 && !capped && (!overlaps || selected)
    visual.caption.element.style.visibility = visible ? 'visible' : 'hidden'
    if (visible) { occupied.push({ x, y, w, h }); if (node.kind !== 'topic' && !selected) neighborLabels++ }
  }
  if (props.reading) {
    const node = graph.graphData().nodes.find(item => item.id === props.selectedId)
    if (node) {
      projected.set(node.x ?? 0, node.y ?? 0, node.z ?? 0).project(graph.camera())
      const point = { x: (projected.x + 1) * width / 2, y: (1 - projected.y) * height / 2, width, height, visible: projected.z > -1 && projected.z < 1 }
      const key = `${node.id}:${Math.round(point.x)}:${Math.round(point.y)}:${width}:${height}:${point.visible}`
      if (key !== lastAnchor) { lastAnchor = key; emit('anchor', point) }
    }
  }
}
function refreshAppearance() {
  if (!graph) return
  const active = activeId.value
  ambientMotion.emphasize(active, neighborIds.value)
  for (const node of graph.graphData().nodes) {
    const visual = visuals.get(node.id)
    if (!visual) continue
    styleVisual(node, visual)
  }
  graph.linkColor(link => {
    if (active && (link.from === active || link.to === active)) return '#83e8ff'
    if (!active && props.highlightedEdgeIds?.includes(link.id)) return '#cbaa70'
    return active ? '#0d1523' : '#30445d'
  }).linkOpacity(active ? .8 : .38).linkWidth(link => active && (link.from === active || link.to === active) ? .7 : 0)
    .linkDirectionalParticles(link => !motion.value ? 0 : active && (link.from === active || link.to === active) ? 2 : 0)
}
function buildStars() {
  const geometry = new BufferGeometry(), positions = new Float32Array(650 * 3)
  let seed = 1537
  const random = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296 }
  for (let i = 0; i < 650; i++) {
    const theta = random() * Math.PI * 2, z = random() * 2 - 1, radius = 900 + random() * 800, ring = Math.sqrt(1 - z * z)
    positions.set([Math.cos(theta) * ring * radius, Math.sin(theta) * ring * radius, z * radius], i * 3)
  }
  geometry.setAttribute('position', new Float32BufferAttribute(positions, 3))
  return new Points(geometry, new PointsMaterial({ map: glowTexture, color: '#b1c8e4', size: 2.2, transparent: true, opacity: .35, depthWrite: false }))
}
function updateGraph() {
  if (!graph) return
  const data = createSpatialGraph(props.nodes, props.edges, graph.graphData().nodes)
  ambientMotion.setEnabled(motion.value)
  graph.warmupTicks(motion.value ? (fitted ? 0 : 60) : 180).cooldownTicks(motionPolicy.value.cooldownTicks).graphData(data)
  refreshAppearance()
  if (focused.value) scheduleFocus(focused.value)
  if (!fitted && props.nodes.length) {
    if (fitTimer) clearTimeout(fitTimer)
    fitTimer = setTimeout(() => { if (!destroyed) { fit(); fitted = true } }, props.reducedMotion ? 0 : 900)
  }
}
function syncMotion() {
  if (!graph) return
  const camera = graph.camera() as PerspectiveCamera
  if (props.reading) camera.setViewOffset(graph.width(), graph.height(), graph.width() >= 680 ? graph.width() * .18 : 0, graph.width() < 680 ? graph.height() * .16 : 0, graph.width(), graph.height())
  else camera.clearViewOffset()
  if (controls) controls.autoRotate = motionPolicy.value.autoOrbit && !orbitHeld && !hovered.value
  ambientMotion.setEnabled(motion.value)
  graph.cooldownTicks(motionPolicy.value.cooldownTicks).cooldownTime(motionPolicy.value.cooldownTime).d3AlphaMin(motionPolicy.value.alphaMin)
  refreshAppearance()
}
function holdOrbit() {
  orbitHeld = true
  if (resumeTimer) clearTimeout(resumeTimer)
  if (controls) controls.autoRotate = false
}
function releaseOrbit() {
  if (resumeTimer) clearTimeout(resumeTimer)
  resumeTimer = setTimeout(() => { orbitHeld = false; syncMotion() }, 4000)
}
function flyTo(id: string) {
  if (!graph) return
  const node = graph.graphData().nodes.find(item => item.id === id)
  if (!node) return
  if (fitTimer) clearTimeout(fitTimer)
  fitted = true
  holdOrbit(); releaseOrbit()
  const ids = incidentSelection(id, props.edges).nodeIds
  const neighbors = graph.graphData().nodes.filter(item => ids.has(item.id))
  const camera = graph.camera() as PerspectiveCamera
  const destination = neighborhoodDestination(node, neighbors, graph.cameraPosition(), camera.fov, camera.aspect)
  graph.cameraPosition(destination.position, destination.lookAt, props.reducedMotion ? 0 : 450)
}
function scheduleFocus(id: string) {
  cancelAnimationFrame(focusFrame)
  if (!id || !props.nodes.some(node => node.id === id)) return
  const visibleIds = new Set(props.nodes.map(node => node.id))
  const expectedIds = [...incidentSelection(id, props.edges).nodeIds].filter(nodeId => visibleIds.has(nodeId))
  const settled = createFocusSettler(id, expectedIds, performance.now())
  const frame = () => {
    if (destroyed || !graph || focused.value !== id) return
    if (!settled(graph.graphData().nodes, performance.now())) { focusFrame = requestAnimationFrame(frame); return }
    flyTo(id)
  }
  focusFrame = requestAnimationFrame(frame)
}
function activateNode(id: string) {
  host.value?.focus({ preventScroll: true })
  focused.value = id; selectedEdge.value = undefined
  emit('select', id)
  if (motion.value) {
    ambientMotion.pulse(id, incidentSelection(id, props.edges).nodeIds)
    graph?.d3ReheatSimulation()
  }
  scheduleFocus(id); refreshAppearance()
}
function updateResolution() {
  if (!graph) return
  const ratio = Math.min(3, Math.max(1, window.devicePixelRatio || 1))
  graph.renderer().setPixelRatio(ratio)
  graph.postProcessingComposer().setPixelRatio(ratio)
}
function fit() { holdOrbit(); releaseOrbit(); graph?.zoomToFit(props.reducedMotion ? 0 : 850, 85) }
function reheat() { emit('close'); focused.value = ''; selectedEdge.value = undefined; fitted = false; graph?.d3ReheatSimulation(); updateGraph() }
function visibilityChanged() {
  if (!graph) return
  if (document.hidden || !intersecting) graph.pauseAnimation()
  else { graph.resumeAnimation(); syncMotion() }
}
function contextLost(event: Event) {
  event.preventDefault()
  if (!destroyed) { failed.value = true; emit('unavailable') }
}
async function fullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await (host.value?.closest('.learning-graph-stage') ?? host.value)?.requestFullscreen()
  } catch { /* The embedded viewer may disable fullscreen. */ }
}
function nextNode(direction: number) {
  const index = props.nodes.findIndex(node => node.id === props.selectedId)
  const node = props.nodes[(index + direction + props.nodes.length) % props.nodes.length]
  if (node) activateNode(node.id)
}

watch(() => [props.nodes.map(node => node.id).join('|'), props.edges.map(edge => edge.id).join('|')].join('::'), updateGraph)
watch(() => props.selectedId, id => { focused.value = id; selectedEdge.value = undefined; scheduleFocus(id); refreshAppearance() })
watch(() => props.reading, value => { lastAnchor = ''; focused.value = value ? props.selectedId : ''; syncMotion() })
watch([motion, () => props.reducedMotion], () => {
  if (props.reducedMotion) updateGraph()
  else if (motion.value) graph?.d3ReheatSimulation()
  syncMotion()
})
watch([hovered, showLabels, () => props.highlightedEdgeIds?.join('|')], refreshAppearance)
watch(locale, () => {
  if (!graph) return
  for (const node of graph.graphData().nodes) { const visual = visuals.get(node.id); if (visual) setCaption(visual, node) }
  graph.nodeLabel(node => tooltip(title(node))).linkLabel(link => tooltip(relationship(link.type)))
})

onMounted(async () => {
  try {
    const module = await import('3d-force-graph')
    if (destroyed || !viewport.value) return
    type Constructor = new (el: HTMLElement, options?: ConfigOptions) => ForceGraph3DInstance<SpatialNode, SpatialLink>
    const Constructor = module.default as unknown as Constructor
    glowTexture = glow()
    labelsRenderer = new CSS2DRenderer()
    labelsRenderer.domElement.className = 'knowledge-label-layer'
    const renderLabels = labelsRenderer.render.bind(labelsRenderer)
    labelsRenderer.render = (scene, camera) => { renderLabels(scene, camera); updateFrame() }
    graph = new Constructor(viewport.value, { controlType: 'orbit', extraRenderers: [labelsRenderer], rendererConfig: { antialias: true, alpha: false } })
    graph.backgroundColor('#050914').showNavInfo(false)
      .width(viewport.value.clientWidth).height(viewport.value.clientHeight)
      .nodeThreeObject(node => visualFor(node).group).nodeLabel(node => tooltip(title(node)))
      .linkOpacity(.65).linkDirectionalParticleWidth(1.2).linkDirectionalParticleSpeed(.004)
      .linkCurvature(link => spatialLinkCurve(link.id).curvature).linkCurveRotation(link => spatialLinkCurve(link.id).rotation)
      .linkDirectionalParticleColor(() => '#99edff').linkLabel(link => tooltip(relationship(link.type)))
      .d3AlphaMin(motionPolicy.value.alphaMin).d3AlphaDecay(.025).d3VelocityDecay(.3).cooldownTime(motionPolicy.value.cooldownTime)
      .onNodeClick(node => activateNode(node.id))
      .onNodeDrag(() => holdOrbit())
      .onNodeDragEnd(node => { ambientMotion.reanchor(node.id); if (motion.value) { ambientMotion.pulse(node.id, incidentSelection(node.id, props.edges).nodeIds); graph?.d3ReheatSimulation() }; releaseOrbit() })
      .onNodeHover(node => { hovered.value = node?.id ?? ''; if (controls) controls.autoRotate = motionPolicy.value.autoOrbit && !orbitHeld && !node })
      .onLinkClick(link => { selectedEdge.value = link; emit('close'); holdOrbit(); releaseOrbit() })
      .onBackgroundClick(() => { focused.value = ''; hovered.value = ''; selectedEdge.value = undefined; emit('close'); refreshAppearance() })
    updateResolution()
    renderCanvas = graph.renderer().domElement
    renderCanvas.addEventListener('webglcontextlost', contextLost)
    graph.d3Force('charge')?.strength(-85)
    graph.d3Force('link')?.distance(65).strength(.35)
    graph.d3Force('ambient-motion', ambientMotion.force)
    stars = buildStars(); graph.scene().add(stars)
    controls = graph.controls() as OrbitControls
    controls.autoRotateSpeed = .4; controls.enableDamping = true; controls.dampingFactor = .065
    controls.minDistance = 25; controls.maxDistance = 2000
    controls.addEventListener('start', holdOrbit); controls.addEventListener('end', releaseOrbit)
    updateGraph(); syncMotion()
    resizeObserver = new ResizeObserver(() => {
      if (!graph || !viewport.value) return
      const { clientWidth, clientHeight } = viewport.value
      if (clientWidth > 0 && clientHeight > 0) { updateResolution(); graph.width(clientWidth).height(clientHeight); syncMotion() }
    })
    resizeObserver.observe(viewport.value)
    intersectionObserver = new IntersectionObserver(entries => { intersecting = entries[0]?.isIntersecting ?? false; visibilityChanged() })
    intersectionObserver.observe(host.value!)
    document.addEventListener('visibilitychange', visibilityChanged)
    window.addEventListener('resize', updateResolution)
    visibilityChanged(); ready.value = true
  } catch {
    if (!destroyed) { failed.value = true; emit('unavailable') }
  }
})
onBeforeUnmount(() => {
  destroyed = true
  if (resumeTimer) clearTimeout(resumeTimer)
  if (fitTimer) clearTimeout(fitTimer)
  resizeObserver?.disconnect(); intersectionObserver?.disconnect()
  document.removeEventListener('visibilitychange', visibilityChanged)
  window.removeEventListener('resize', updateResolution)
  cancelAnimationFrame(focusFrame)
  renderCanvas?.removeEventListener('webglcontextlost', contextLost)
  controls?.removeEventListener('start', holdOrbit); controls?.removeEventListener('end', releaseOrbit)
  const renderer = graph?.renderer()
  graph?.pauseAnimation(); graph?._destructor(); graph = undefined
  renderer?.forceContextLoss()
  for (const visual of visuals.values()) {
    visual.sphere.material.dispose()
    ;(visual.halo.material as SpriteMaterial).dispose()
    visual.caption.element.remove()
  }
  visuals.clear(); sphereGeometry.dispose(); glowTexture?.dispose()
  stars?.geometry.dispose(); (stars?.material as PointsMaterial | undefined)?.dispose()
  labelsRenderer?.domElement.remove()
})
</script>

<template>
  <section ref="host" class="graph-canvas graph-3d" :aria-label="tr('可旋转的三维知识网络', 'Rotatable 3D knowledge network')"
    tabindex="0" @keydown.left.self.prevent="nextNode(-1)" @keydown.right.self.prevent="nextNode(1)" @keydown.enter.self.prevent="currentNode && emit('read', currentNode.id)">
    <div ref="viewport" class="graph-3d__viewport" aria-hidden="true"></div>
    <div v-if="!ready" class="graph-3d__loading" role="status">{{ failed ? tr('3D 渲染不可用，正在切换视图…', '3D unavailable, switching view…') : tr('正在展开知识星图…', 'Unfolding the knowledge constellation…') }}</div>
    <div class="graph-3d__heading">
      <span class="graph-3d__eyebrow"><i></i>{{ tr('三维知识网络', 'KNOWLEDGE CONSTELLATION') }}</span>
      <span class="graph-3d__counts">{{ nodes.length }} {{ tr('节点', 'nodes') }} <b>·</b> {{ edges.length }} {{ tr('连接', 'connections') }}</span>
      <span class="graph-3d__hint">{{ reading && currentNode ? `${tr('正在探索', 'Exploring')} · ${title(currentNode)}` : tr('点击一颗星，直接阅读知识与关联', 'Click a star to read its knowledge and connections') }}</span>
    </div>
    <div class="graph-3d__controls">
      <button type="button" :aria-pressed="motion" :disabled="reducedMotion" @click="paused = !paused">{{ motion ? 'Ⅱ' : '▷' }} {{ reducedMotion ? tr('静态模式', 'Static mode') : motion ? tr('暂停动态', 'Pause motion') : tr('继续动态', 'Resume motion') }}</button>
      <button type="button" :aria-pressed="showLabels" @click="showLabels = !showLabels">{{ tr('标签', 'Labels') }}</button>
      <button type="button" @click="fit">{{ tr('全景', 'Fit all') }}</button>
      <button type="button" @click="reheat">{{ tr('重排', 'Relayout') }}</button>
      <button type="button" @click="fullscreen" :aria-label="tr('切换全屏', 'Toggle fullscreen')">⛶</button>
    </div>
    <div class="graph-3d__legend">
      <span v-for="kind in (['lesson','topic','concept','evidence','source'] as const)" :key="kind"><i :style="{ background: colors[kind], boxShadow: `0 0 8px ${colors[kind]}70` }"></i>{{ t(`learning.kind.${kind}`) }}</span>
    </div>
    <div v-if="selectedEdge" class="graph-3d__detail" aria-live="polite">
      <small>{{ tr('知识关系', 'RELATIONSHIP') }}</small><h3>{{ relationship(selectedEdge.type) }}</h3><p>{{ selectedEdge.reason[locale] }}</p>
    </div>
    <div class="graph-3d__footer">
      <span>{{ reading ? tr('视角保持 · 关联星点持续摇曳 · 空白处关闭详情', 'Camera held · connected stars keep swaying · click empty space to close') : tr('拖动旋转 · 拖动星点牵动连线 · 点击探索', 'Drag to orbit · drag a star to move its links · click to explore') }}</span>
      <button type="button" @click="emit('browse')">{{ tr('打开知识列表', 'Open knowledge list') }} →</button>
    </div>
    <div v-if="ready && !nodes.length" class="graph-3d__loading">{{ t('learning.noResults') }}</div>
  </section>
</template>

<style scoped>
.graph-3d{position:relative;min-height:500px;height:100%;overflow:hidden;border:1px solid #24354c;border-radius:12px;background:#050914;color:#dce7f8;font-family:"Segoe UI","Microsoft YaHei",sans-serif;outline:none}
.graph-3d:focus-visible{outline:2px solid #58bbfd;outline-offset:3px}
.graph-3d__viewport{position:absolute;inset:0;z-index:0}.graph-3d__viewport :deep(canvas){display:block;outline:none}
.graph-3d__heading{position:absolute;z-index:2;left:22px;top:20px;display:flex;flex-direction:column;gap:7px;pointer-events:none;max-width:45%}
.graph-3d__eyebrow{display:flex;align-items:center;gap:8px;color:#d7e7fc;font-size:11px;font-weight:600;letter-spacing:.12em}
.graph-3d__eyebrow i{width:5px;height:5px;border-radius:50%;background:#b6edff;box-shadow:0 0 12px #63e0ff}
.graph-3d__counts{padding-left:13px;color:#8da8c7;font-size:11px}.graph-3d__counts b{padding:0 5px;font-weight:400;color:#486185}
.graph-3d__hint{margin-top:5px;color:#9bb7d3;font-size:12px;line-height:1.6;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.graph-3d__controls{position:absolute;z-index:3;top:17px;right:17px;display:flex;align-items:center;gap:3px;padding:4px;border:1px solid #293951a8;border-radius:9px;background:#0d1727e8}
.graph-3d__controls button{padding:7px 9px;border:0;border-radius:5px;background:transparent;color:#a9bfd9;font:inherit;font-size:11px;cursor:pointer}
.graph-3d__controls button:hover,.graph-3d__controls button[aria-pressed=true]{background:#26374d;color:#eef8ff}.graph-3d__controls button:disabled{opacity:.4;cursor:default}
.graph-3d button:focus-visible{outline:2px solid #68ceff;outline-offset:2px}
.graph-3d__legend{position:absolute;z-index:2;left:22px;bottom:65px;display:flex;flex-wrap:wrap;gap:13px;pointer-events:none}
.graph-3d__legend span{display:flex;align-items:center;gap:6px;color:#99b0ca;font-size:10px}.graph-3d__legend i{width:5px;height:5px;border-radius:50%}
.graph-3d__detail{position:absolute;z-index:4;left:20px;bottom:100px;width:min(290px,calc(100% - 80px));padding:18px;border:1px solid #36516c;border-radius:12px;background:#0d1b2cf5;box-shadow:0 16px 45px #0005}
.graph-3d__detail small{color:#8ebdd9;font-size:10px;letter-spacing:.1em}.graph-3d__detail h3{margin:8px 0;font-size:16px;color:#e3f1ff;font-weight:500}.graph-3d__detail p{margin:0;color:#bbcee4;font-size:13px;line-height:1.8}
.graph-3d__footer{position:absolute;z-index:2;bottom:0;left:0;right:0;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:13px 20px;background:#080f1ed9;border-top:1px solid #1c2a4080;color:#92aecb;font-size:11px}
.graph-3d__footer button{flex-shrink:0;padding:3px 0;border:0;background:none;color:#a5ccef;font:inherit;font-size:11px;cursor:pointer}
.graph-3d__loading{position:absolute;z-index:2;inset:0;display:grid;place-items:center;pointer-events:none;color:#a6bad6;font-size:13px}
/* Native text stays sharp at 11px; sparse captions never cover the stars with boxed cards. */
.graph-3d :deep(.knowledge-node-label){width:max-content;max-width:165px;padding:2px 5px;border:0;border-radius:3px;background:#05091480;color:#bdd2ea;font-size:11px;font-weight:400;line-height:1.4;text-align:center;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;pointer-events:none;user-select:none;text-shadow:0 1px 3px #000}
.graph-3d :deep(.knowledge-node-label--selected){color:#f2faff;font-size:12px;font-weight:600;background:#0b1e30c9}
@media(max-width:800px){.graph-3d__heading{max-width:calc(100% - 36px);left:18px;top:16px}.graph-3d__hint{max-width:32ch}.graph-3d__controls{top:105px;left:18px;right:auto}.graph-3d__legend{left:18px;gap:10px}.graph-3d__footer{align-items:flex-start;font-size:10px}.graph-3d__footer>span{max-width:68%;line-height:1.5}.graph-3d__footer button{font-size:10px}}
</style>
