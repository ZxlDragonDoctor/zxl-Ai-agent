<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AdditiveBlending, BufferGeometry, CanvasTexture, Float32BufferAttribute, Group, Mesh, MeshBasicMaterial, Points, PointsMaterial, SphereGeometry, Sprite, SpriteMaterial } from 'three'
import type { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import type { ConfigOptions, ForceGraph3DInstance } from '3d-force-graph'
import type { GraphEdge, GraphNode } from './model'
import { createSpatialGraph, focusDestination, spatialNodeRadius } from './graph3DModel'
import type { SpatialLink, SpatialNode } from './graph3DModel'
import { locale, t } from '../../i18n'

const props = defineProps<{ nodes: GraphNode[]; edges: GraphEdge[]; selectedId: string; highlightedEdgeIds?: string[]; reducedMotion: boolean }>()
const emit = defineEmits<{ select: [id: string]; read: [id: string]; browse: []; unavailable: [] }>()
const host = ref<HTMLElement>(), viewport = ref<HTMLElement>()
const ready = ref(false), failed = ref(false), paused = ref(false), showLabels = ref(false)
const hovered = ref(''), focused = ref(''), selectedEdge = ref<GraphEdge>()
const tr = (cn: string, en: string) => locale.value === 'zh-CN' ? cn : en
const title = (node: GraphNode) => node.title[locale.value] || node.title['zh-CN']
const currentNode = computed(() => props.nodes.find(node => node.id === props.selectedId))
const motion = computed(() => !paused.value && !props.reducedMotion)
const activeId = computed(() => hovered.value || focused.value)
const neighborIds = computed(() => new Set(props.edges.filter(edge => edge.from === activeId.value || edge.to === activeId.value).flatMap(edge => [edge.from, edge.to])))
const colors = { lesson: '#5bbdff', topic: '#ffcd7b', concept: '#ac98ff', evidence: '#51d9b5', source: '#ff91b9' }
interface Visual { group: Group; sphere: Mesh<SphereGeometry, MeshBasicMaterial>; halo: Sprite; caption: Sprite; captionLocale: string }
const visuals = new Map<string, Visual>()
const sphereGeometry = new SphereGeometry(1, 18, 12)
let graph: ForceGraph3DInstance<SpatialNode, SpatialLink> | undefined
let glowTexture: CanvasTexture | undefined, stars: Points | undefined
let resizeObserver: ResizeObserver | undefined, intersectionObserver: IntersectionObserver | undefined
let resumeTimer: ReturnType<typeof setTimeout> | undefined, fitTimer: ReturnType<typeof setTimeout> | undefined
let destroyed = false, intersecting = true, orbitHeld = false, fitted = false
let controls: OrbitControls | undefined
let renderCanvas: HTMLCanvasElement | undefined

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
function captionTexture(text: string) {
  const canvas = document.createElement('canvas'), ctx = canvas.getContext('2d')!
  ctx.font = '500 28px "Microsoft YaHei", "Segoe UI", sans-serif'
  canvas.width = Math.min(1536, Math.ceil(ctx.measureText(text).width) + 32); canvas.height = 64
  ctx.font = '500 28px "Microsoft YaHei", "Segoe UI", sans-serif'
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillStyle = '#e3edff'
  ctx.shadowColor = '#020817'; ctx.shadowBlur = 8
  ctx.fillText(text, canvas.width / 2, 32, canvas.width - 20)
  return { texture: new CanvasTexture(canvas), width: canvas.width / 14, height: canvas.height / 14 }
}
function setCaption(visual: Visual, node: SpatialNode) {
  const result = captionTexture(title(node))
  const material = visual.caption.material as SpriteMaterial
  material.map?.dispose(); material.map = result.texture; material.needsUpdate = true
  visual.caption.scale.set(result.width, result.height, 1)
  visual.captionLocale = locale.value
}
function visualFor(node: SpatialNode): Visual {
  const old = visuals.get(node.id)
  if (old) { if (old.captionLocale !== locale.value) setCaption(old, node); return old }
  const group = new Group()
  const sphere = new Mesh(sphereGeometry, new MeshBasicMaterial({ color: colors[node.kind], transparent: true }))
  const halo = new Sprite(new SpriteMaterial({ map: glowTexture, color: colors[node.kind], transparent: true, blending: AdditiveBlending, depthWrite: false }))
  const caption = new Sprite(new SpriteMaterial({ transparent: true, depthWrite: false, depthTest: false }))
  group.add(sphere, halo, caption)
  const result = { group, sphere, halo, caption, captionLocale: locale.value }
  visuals.set(node.id, result); setCaption(result, node)
  return result
}
function refreshAppearance() {
  if (!graph) return
  const active = activeId.value, neighbors = neighborIds.value
  for (const node of graph.graphData().nodes) {
    const visual = visuals.get(node.id)
    if (!visual) continue
    const selected = node.id === props.selectedId, emphasized = node.id === active || selected
    const dimmed = !!active && !neighbors.has(node.id) && node.id !== active
    const radius = spatialNodeRadius(node) * (emphasized ? 1.18 : 1)
    visual.sphere.scale.setScalar(radius)
    visual.sphere.material.opacity = dimmed ? .18 : 1
    ;(visual.halo.material as SpriteMaterial).opacity = dimmed ? .08 : emphasized ? .85 : .38
    visual.halo.scale.setScalar(radius * (emphasized ? 9 : 7))
    visual.caption.position.set(0, radius + 6, 0)
    visual.caption.visible = !dimmed && (showLabels.value || emphasized || node.kind === 'topic')
  }
  graph.linkColor(link => {
    if (active && (link.from === active || link.to === active)) return '#83e8ff'
    if (props.highlightedEdgeIds?.includes(link.id)) return '#cbaa70'
    return active ? '#142039' : '#38516d'
  }).linkWidth(link => active && (link.from === active || link.to === active) ? .65 : 0)
    .linkDirectionalParticles(link => !motion.value ? 0 : active ? (link.from === active || link.to === active ? 3 : 0) : 1)
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
  return new Points(geometry, new PointsMaterial({ color: '#8ea6cd', size: 1.7, transparent: true, opacity: .5, depthWrite: false }))
}
function updateGraph() {
  if (!graph) return
  const data = createSpatialGraph(props.nodes, props.edges, graph.graphData().nodes)
  graph.warmupTicks(props.reducedMotion ? 180 : 30).cooldownTicks(motion.value ? Infinity : 0).graphData(data)
  refreshAppearance()
  if (!fitted && props.nodes.length) {
    if (fitTimer) clearTimeout(fitTimer)
    fitTimer = setTimeout(() => { if (!destroyed) { fit(); fitted = true } }, props.reducedMotion ? 0 : 900)
  }
}
function syncMotion() {
  if (!graph) return
  if (controls) controls.autoRotate = motion.value && !orbitHeld && !hovered.value
  graph.cooldownTicks(motion.value ? Infinity : 0)
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
  const destination = focusDestination(node, graph.cameraPosition(), 95 + spatialNodeRadius(node) * 7)
  graph.cameraPosition(destination.position, destination.lookAt, props.reducedMotion ? 0 : 1150)
}
function fit() { holdOrbit(); releaseOrbit(); graph?.zoomToFit(props.reducedMotion ? 0 : 850, 85) }
function reheat() { focused.value = ''; selectedEdge.value = undefined; fitted = false; graph?.d3ReheatSimulation(); updateGraph() }
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
    if (document.fullscreenElement === host.value) await document.exitFullscreen()
    else await host.value?.requestFullscreen()
  } catch { /* The embedded viewer may disable fullscreen. */ }
}
function nextNode(direction: number) {
  const index = props.nodes.findIndex(node => node.id === props.selectedId)
  const node = props.nodes[(index + direction + props.nodes.length) % props.nodes.length]
  if (node) { focused.value = node.id; emit('select', node.id); flyTo(node.id); refreshAppearance() }
}

watch(() => [props.nodes.map(node => node.id).join('|'), props.edges.map(edge => edge.id).join('|')].join('::'), updateGraph)
watch(() => props.selectedId, id => { focused.value = id; selectedEdge.value = undefined; flyTo(id); refreshAppearance() })
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
    graph = new Constructor(viewport.value, { controlType: 'orbit', rendererConfig: { antialias: true, alpha: false } })
    graph.backgroundColor('#050914').showNavInfo(false)
      .width(viewport.value.clientWidth).height(viewport.value.clientHeight)
      .nodeThreeObject(node => visualFor(node).group).nodeLabel(node => tooltip(title(node)))
      .linkOpacity(.65).linkDirectionalParticleWidth(1.2).linkDirectionalParticleSpeed(.004)
      .linkDirectionalParticleColor(() => '#99edff').linkLabel(link => tooltip(relationship(link.type)))
      .d3AlphaDecay(.018).d3VelocityDecay(.35).cooldownTime(12000)
      .onNodeClick(node => { focused.value = node.id; selectedEdge.value = undefined; emit('select', node.id); flyTo(node.id); refreshAppearance() })
      .onNodeHover(node => { hovered.value = node?.id ?? ''; if (controls) controls.autoRotate = motion.value && !orbitHeld && !node })
      .onLinkClick(link => { selectedEdge.value = link; holdOrbit(); releaseOrbit() })
      .onBackgroundClick(() => { focused.value = ''; hovered.value = ''; selectedEdge.value = undefined; refreshAppearance() })
    graph.renderer().setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    renderCanvas = graph.renderer().domElement
    renderCanvas.addEventListener('webglcontextlost', contextLost)
    graph.d3Force('charge')?.strength(-85)
    graph.d3Force('link')?.distance(65).strength(.35)
    stars = buildStars(); graph.scene().add(stars)
    controls = graph.controls() as OrbitControls
    controls.autoRotateSpeed = .4; controls.enableDamping = true; controls.dampingFactor = .065
    controls.minDistance = 25; controls.maxDistance = 2000
    controls.addEventListener('start', holdOrbit); controls.addEventListener('end', releaseOrbit)
    updateGraph(); syncMotion()
    resizeObserver = new ResizeObserver(() => {
      if (!graph || !viewport.value) return
      const { clientWidth, clientHeight } = viewport.value
      if (clientWidth > 0 && clientHeight > 0) graph.width(clientWidth).height(clientHeight)
    })
    resizeObserver.observe(viewport.value)
    intersectionObserver = new IntersectionObserver(entries => { intersecting = entries[0]?.isIntersecting ?? false; visibilityChanged() })
    intersectionObserver.observe(host.value!)
    document.addEventListener('visibilitychange', visibilityChanged)
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
  renderCanvas?.removeEventListener('webglcontextlost', contextLost)
  controls?.removeEventListener('start', holdOrbit); controls?.removeEventListener('end', releaseOrbit)
  const renderer = graph?.renderer()
  graph?.pauseAnimation(); graph?._destructor(); graph = undefined
  renderer?.forceContextLoss()
  for (const visual of visuals.values()) {
    visual.sphere.material.dispose()
    ;(visual.halo.material as SpriteMaterial).dispose()
    const material = visual.caption.material as SpriteMaterial
    material.map?.dispose(); material.dispose()
  }
  visuals.clear(); sphereGeometry.dispose(); glowTexture?.dispose()
  stars?.geometry.dispose(); (stars?.material as PointsMaterial | undefined)?.dispose()
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
    <div v-else-if="currentNode" class="graph-3d__detail" aria-live="polite">
      <small :style="{ color: colors[currentNode.kind] }">{{ t(`learning.kind.${currentNode.kind}`) }}</small>
      <h3>{{ title(currentNode) }}</h3><p>{{ currentNode.summary[locale] }}</p>
      <button type="button" @click="emit('read', currentNode.id)">{{ tr('阅读知识', 'Read knowledge') }} <span>↗</span></button>
    </div>
    <div class="graph-3d__footer">
      <span>{{ tr('拖动旋转 · 右键平移 · 滚轮缩放 · 点击节点飞近', 'Drag to orbit · right-drag to pan · scroll to zoom · click to fly closer') }}</span>
      <button type="button" @click="emit('browse')">{{ tr('打开知识列表', 'Open knowledge list') }} →</button>
    </div>
    <div v-if="ready && !nodes.length" class="graph-3d__loading">{{ t('learning.noResults') }}</div>
  </section>
</template>

<style scoped>
.graph-3d{position:relative;min-height:520px;height:100%;overflow:hidden;border:1px solid #23324a;border-radius:10px;background:#050914;color:#dce7f8;font-family:"Segoe UI","Microsoft YaHei",sans-serif;outline:none}
.graph-3d:focus-visible{outline:2px solid #58bbfd;outline-offset:3px}.graph-3d:fullscreen{width:100vw;height:100vh;border:0;border-radius:0}
.graph-3d__viewport{position:absolute;inset:0}.graph-3d__viewport :deep(canvas){display:block;outline:none}
.graph-3d__heading{position:absolute;left:22px;top:20px;display:flex;flex-direction:column;gap:8px;pointer-events:none}
.graph-3d__eyebrow{display:flex;align-items:center;gap:8px;color:#b7c9e5;font-size:11px;letter-spacing:.12em}.graph-3d__eyebrow i{width:5px;height:5px;border-radius:50%;background:#61d8ff;box-shadow:0 0 12px #63e0ff}
.graph-3d__counts{padding-left:13px;color:#637b9d;font-size:10px}.graph-3d__counts b{padding:0 5px;font-weight:400;color:#3b5477}
.graph-3d__controls{position:absolute;top:17px;right:17px;display:flex;align-items:center;gap:5px;padding:5px;border:1px solid #293951a8;border-radius:8px;background:#101a2bb8;backdrop-filter:blur(12px)}
.graph-3d__controls button{padding:7px 10px;border:0;border-radius:5px;background:transparent;color:#91a7c6;font:inherit;font-size:10px;cursor:pointer}.graph-3d__controls button:hover,.graph-3d__controls button[aria-pressed=true]{background:#26374d;color:#d9edff}.graph-3d__controls button:disabled{opacity:.4;cursor:default}
.graph-3d button:focus-visible{outline:2px solid #68ceff;outline-offset:2px}
.graph-3d__legend{position:absolute;left:22px;top:84px;display:flex;gap:15px;pointer-events:none}.graph-3d__legend span{display:flex;align-items:center;gap:6px;color:#798fae;font-size:9px}.graph-3d__legend i{width:5px;height:5px;border-radius:50%}
.graph-3d__detail{position:absolute;left:20px;bottom:61px;width:min(290px,calc(100% - 40px));padding:16px 18px;border:1px solid #2c3f5b88;border-radius:10px;background:linear-gradient(120deg,#142037df,#0a1325dd);box-shadow:0 10px 40px #0002;backdrop-filter:blur(15px)}
.graph-3d__detail small{color:#91b5d5;font-size:9px;letter-spacing:.1em}.graph-3d__detail h3{margin:7px 0 6px;font-size:16px;line-height:1.4;color:#dceaff;font-weight:500}.graph-3d__detail p{display:-webkit-box;overflow:hidden;-webkit-line-clamp:2;-webkit-box-orient:vertical;margin:0;color:#8299b9;font-size:11px;line-height:1.7}
.graph-3d__detail button{display:flex;justify-content:space-between;align-items:center;width:100%;margin:14px 0 0;padding:8px 0 0;border:0;border-top:1px solid #2a3a5288;background:none;color:#83c8f6;font:inherit;font-size:10px;cursor:pointer}.graph-3d__detail button span{font-size:16px}
.graph-3d__footer{position:absolute;bottom:0;left:0;right:0;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:13px 20px;background:linear-gradient(0deg,#080e1de8,#080e1d80);border-top:1px solid #1c2a4080;color:#617b9e;font-size:10px}
.graph-3d__footer button{flex-shrink:0;padding:3px 0;border:0;background:none;color:#91b8df;font:inherit;font-size:10px;cursor:pointer}.graph-3d__loading{position:absolute;inset:0;display:grid;place-items:center;pointer-events:none;color:#a6bad6;font-size:13px}
@media(max-width:800px){.graph-3d__controls{top:67px;left:18px;right:auto}.graph-3d__legend{top:123px;gap:10px}.graph-3d__detail{bottom:72px}.graph-3d__footer{align-items:flex-start;font-size:8px}.graph-3d__footer>span{max-width:65%;line-height:1.5}}
</style>
