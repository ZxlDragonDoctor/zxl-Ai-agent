<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import KnowledgeGraphCanvas from '../learning/graph/KnowledgeGraphCanvas.vue'
import { discoverScene, revealSelection } from '../learning/graph/graphPresentation'
import {
  catalogue, createRecord, exportGraph, exportLearningProgress, filterNodes, findShortestPath,
  getNeighbors, importLearningProgress, loadProgress, nodeById, saveProgress,
} from '../learning/graph/model'
import type { GraphNode, LearningProgress, LearningState, NodeKind, RelationType } from '../learning/graph/model'
import { locale, t } from '../i18n'
const KnowledgeGraph3D = defineAsyncComponent(() => import('../learning/graph/KnowledgeGraph3D.vue'))

const route = useRoute()
const router = useRouter()
const progress = ref<LearningProgress>(loadProgress())
const query = ref('')
const kindFilter = ref<NodeKind | 'all'>('all')
const stageFilter = ref<string | 'all'>('all')
const learningFilter = ref<LearningState | 'all'>('all')
const relationFilter = ref<RelationType | 'all'>('all')
const viewMode = ref<'all' | 'one' | 'two'>('all')
const graphDimension = ref<'3d' | '2d'>('3d')
const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)')
const reducedMotion = ref(motionPreference.matches)
const catalogueOpen = ref(false)
const readerOpen = ref(false)
const importPicker = ref<HTMLInputElement>()
const reader = ref<HTMLElement>()
const graphStage = ref<HTMLElement>()
const stageSize = ref({ width: 1000, height: 650 })
const detailAnchor = ref<{ x: number; y: number; width: number; height: number; visible: boolean }>()
let stageObserver: ResizeObserver | undefined
const readerPlacement = computed(() => {
  const { width, height } = stageSize.value
  const compact = width < 680
  const cardWidth = Math.min(390, width - 24)
  const cardHeight = Math.min(compact ? height * .5 : 560, height - (compact ? 110 : 160))
  const anchor = detailAnchor.value
  const x = anchor?.visible ? anchor.x : width * .48
  const y = anchor?.visible ? anchor.y : height * .4
  const left = compact ? 12 : Math.max(12, Math.min(width - cardWidth - 12, x + cardWidth + 36 < width ? x + 30 : x - cardWidth - 30))
  const top = compact ? height - cardHeight - 48 : Math.max(100, Math.min(height - cardHeight - 48, y - 72))
  return { left: `${left}px`, top: `${top}px`, width: `${cardWidth}px`, height: `${Math.max(200, cardHeight)}px` }
})
const detailConnector = computed(() => {
  const point = detailAnchor.value
  if (!point?.visible || stageSize.value.width < 680 || graphDimension.value !== '3d') return ''
  const left = parseFloat(readerPlacement.value.left), top = parseFloat(readerPlacement.value.top)
  const right = left + parseFloat(readerPlacement.value.width)
  const x = point.x < left ? left : right
  const y = Math.max(top + 28, Math.min(top + parseFloat(readerPlacement.value.height) - 28, point.y))
  return `M ${point.x} ${point.y} Q ${(point.x + x) / 2} ${point.y - 12} ${x} ${y}`
})
const transferMessage = ref('')
const selectedId = ref(typeof route.query.node === 'string' && nodeById.has(route.query.node) ? route.query.node : 'lesson-08')
const sceneRootId = ref(selectedId.value)
const expandedIds = ref(new Set<string>())
const collapsedIds = ref(new Set<string>())
const dismissedIds = ref(new Set<string>())
const localeKey = computed(() => locale.value)
const selectedNode = computed(() => nodeById.get(selectedId.value) ?? catalogue.nodes[0])
const allLessons = computed(() => catalogue.nodes.filter(node => node.kind === 'lesson'))
const stages = computed(() => catalogue.nodes.filter(node => node.kind === 'topic'))
const kindOptions: Array<NodeKind | 'all'> = ['all', 'lesson', 'topic', 'concept', 'evidence', 'source']
const relationOptions: Array<RelationType | 'all'> = ['all', 'prerequisite', 'contains', 'related', 'implements', 'documented-by', 'documents', 'informed-by']
const selectedProgress = computed(() => progress.value.records[selectedId.value] ?? createRecord())

function stateFor(id: string): LearningState { return progress.value.records[id]?.state ?? 'notStarted' }
function title(node: GraphNode): string { return node.title[localeKey.value] || node.title['zh-CN'] }
function summary(node: GraphNode): string { return node.summary[localeKey.value] || node.summary['zh-CN'] }
function localized(value?: Record<'zh-CN' | 'en', string>): string { return value?.[localeKey.value] || value?.['zh-CN'] || '' }
function sectionText(key: string): string {
  const value = selectedNode.value.sections?.[key]
  if (!value || Array.isArray(value)) return ''
  return localized(value as Record<'zh-CN' | 'en', string>)
}
function interviewQuestions(): string[] {
  const value = selectedNode.value.sections?.interview
  if (!value || !('zh-CN' in value)) return []
  const localizedQuestions = (value as Record<'zh-CN' | 'en', string[]>)[localeKey.value]
  return localizedQuestions?.length ? localizedQuestions : (value as Record<'zh-CN' | 'en', string[]>)['zh-CN']
}

function lessonNumber(node: GraphNode): string { return t('learning.lessonNumber').replace('{number}', String(node.number ?? '')) }
function graphCounts(): string { return t('learning.graphCounts').replace('{nodes}', String(catalogue.nodes.length)).replace('{edges}', String(catalogue.edges.length)) }
function onStateChange(event: Event): void { setLearningState((event.target as HTMLSelectElement).value as LearningState) }
function onNoteInput(event: Event): void { setNote((event.target as HTMLTextAreaElement).value) }

const filteredList = computed(() => filterNodes({ query: query.value, kind: kindFilter.value, stage: stageFilter.value,
  learningState: learningFilter.value, stateFor }))
const relationTypes = computed(() => relationFilter.value === 'all' ? undefined : new Set([relationFilter.value]))
const visibleNodes = computed(() => {
  const baseIds = new Set(filteredList.value.map(node => node.id))
  if (viewMode.value === 'all') return filteredList.value.filter(node => !dismissedIds.value.has(node.id))
  const hops = viewMode.value === 'one' ? 1 : 2
  const allowedEdges = catalogue.edges.filter(edge => (!relationTypes.value || relationTypes.value.has(edge.type))
    && !dismissedIds.value.has(edge.from) && !dismissedIds.value.has(edge.to)
    && baseIds.has(edge.from) && baseIds.has(edge.to))
  const neighborhood = discoverScene(sceneRootId.value, hops, allowedEdges, expandedIds.value, collapsedIds.value)
  return catalogue.nodes.filter(node => neighborhood.has(node.id) && !dismissedIds.value.has(node.id)
    && (baseIds.has(node.id) || node.id === sceneRootId.value))
})
const expandedNodeIds = computed(() => {
  const ids = new Set(expandedIds.value)
  if (viewMode.value !== 'all' && !collapsedIds.value.has(sceneRootId.value)) {
    ids.add(sceneRootId.value)
    if (viewMode.value === 'two') {
      for (const edge of getNeighbors(sceneRootId.value, 'both', relationTypes.value)) {
        const neighbor = edge.from === sceneRootId.value ? edge.to : edge.from
        if (!collapsedIds.value.has(neighbor)) ids.add(neighbor)
      }
    }
  }
  return [...ids]
})
const visibleIds = computed(() => new Set(visibleNodes.value.map(node => node.id)))
const visibleEdges = computed(() => catalogue.edges.filter(edge => visibleIds.value.has(edge.from) && visibleIds.value.has(edge.to)
  && (!relationTypes.value || relationTypes.value.has(edge.type))))
const selectedRelations = computed(() => getNeighbors(selectedId.value).map(edge => ({
  edge,
  other: nodeById.get(edge.from === selectedId.value ? edge.to : edge.from),
  outgoing: edge.from === selectedId.value,
})).filter(item => item.other))
const completedCount = computed(() => allLessons.value.filter(node => stateFor(node.id) === 'mastered').length)
const nextLesson = computed(() => {
  if (Object.keys(progress.value.records).length === 0) return nodeById.get('lesson-08')
  const inProgress = allLessons.value.find(node => stateFor(node.id) === 'inProgress')
  if (inProgress) return inProgress
  const firstReady = allLessons.value.find(node => stateFor(node.id) !== 'mastered'
    && catalogue.edges.filter(edge => edge.type === 'prerequisite' && edge.to === node.id).every(edge => stateFor(edge.from) === 'mastered'))
  return firstReady ?? nodeById.get('lesson-08')
})
const pathToNext = computed(() => nextLesson.value ? findShortestPath(selectedId.value, nextLesson.value.id) ?? [] : [])
const pathNodeIds = computed(() => {
  const ids = [selectedId.value]
  let current = selectedId.value
  for (const edge of pathToNext.value) {
    current = edge.from === current ? edge.to : edge.from
    ids.push(current)
  }
  return ids
})
const highlightedEdgeIds = computed(() => pathToNext.value.map(edge => edge.id))

watch(progress, value => saveProgress(value), { deep: true })
watch(() => route.query.node, value => {
  if (typeof value === 'string' && nodeById.has(value) && value !== selectedId.value) chooseNode(value)
})
watch(viewMode, () => { expandedIds.value = new Set(); collapsedIds.value = new Set(); dismissedIds.value = new Set() })
watch(selectedId, async value => {
  if (route.query.node !== value) router.replace({ query: { ...route.query, node: value } })
  await nextTick()
  if (reader.value) reader.value.scrollTop = progress.value.records[value]?.scrollTop ?? 0
})
watch(readerOpen, async open => {
  if (!open) return
  await nextTick()
  if (reader.value) reader.value.scrollTop = progress.value.records[selectedId.value]?.scrollTop ?? 0
})
watch([query, kindFilter, stageFilter, learningFilter], () => {
  if (query.value.trim()) {
    const match = filteredList.value[0]
    if (match) chooseNode(match.id)
  }
})

let scrollSaveTimer: ReturnType<typeof setTimeout> | undefined
function onReadScroll(event: Event): void {
  const node = selectedNode.value
  const scrollTop = (event.target as HTMLElement).scrollTop
  const existing = progress.value.records[node.id] ?? createRecord()
  progress.value.records[node.id] = { ...existing, scrollTop, updatedAt: new Date().toISOString() }
  if (scrollSaveTimer) clearTimeout(scrollSaveTimer)
  scrollSaveTimer = setTimeout(() => saveProgress(progress.value), 300)
}

function chooseNode(id: string): void {
  if (!nodeById.has(id)) return
  if (id !== selectedId.value) detailAnchor.value = undefined
  if (!visibleIds.value.has(id)) {
    sceneRootId.value = id
    expandedIds.value = new Set(); collapsedIds.value = new Set(); dismissedIds.value = new Set()
  }
  selectedId.value = id
  readerOpen.value = true
  const revealed = revealSelection(id, catalogue.edges, expandedIds.value, collapsedIds.value, dismissedIds.value)
  expandedIds.value = revealed.expanded; collapsedIds.value = revealed.collapsed; dismissedIds.value = revealed.dismissed
}
function toggleExpansion(id: string): void {
  if (viewMode.value === 'all') { sceneRootId.value = id; viewMode.value = 'one'; selectedId.value = id; return }
  const expanded = new Set(expandedIds.value), collapsed = new Set(collapsedIds.value)
  if (expandedNodeIds.value.includes(id)) { expanded.delete(id); collapsed.add(id) }
  else { expanded.add(id); collapsed.delete(id) }
  expandedIds.value = expanded; collapsedIds.value = collapsed
}
function dismissNode(id: string): void {
  dismissedIds.value = new Set([...dismissedIds.value, id])
}
function restoreScene(): void { dismissedIds.value = new Set() }
function openReader(id: string): void { chooseNode(id); readerOpen.value = true }
function focusSelected(): void {
  sceneRootId.value = selectedId.value
  expandedIds.value = new Set(); collapsedIds.value = new Set(); dismissedIds.value = new Set()
  if (viewMode.value === 'all') viewMode.value = 'one'
}
function fallBackTo2D(): void {
  graphDimension.value = '2d'
  transferMessage.value = locale.value === 'zh-CN' ? '当前环境无法创建 3D 画布，已切换到 2D，课程仍可阅读。' : '3D rendering is unavailable here. Switched to 2D; lessons remain accessible.'
}
function updateMotionPreference(event: MediaQueryListEvent): void { reducedMotion.value = event.matches }
motionPreference.addEventListener('change', updateMotionPreference)
onBeforeUnmount(() => { stageObserver?.disconnect(); motionPreference.removeEventListener('change', updateMotionPreference); if (scrollSaveTimer) clearTimeout(scrollSaveTimer) })
function setLearningState(state: LearningState): void {
  const existing = progress.value.records[selectedId.value] ?? createRecord()
  progress.value.records[selectedId.value] = { ...existing, state, updatedAt: new Date().toISOString() }
}
function setNote(value: string): void {
  const existing = progress.value.records[selectedId.value] ?? createRecord()
  progress.value.records[selectedId.value] = { ...existing, note: value.slice(0, 12000), updatedAt: new Date().toISOString() }
}
function copyPath(path: string): void {
  navigator.clipboard?.writeText(path).then(() => {
    transferMessage.value = t('learning.pathCopied')
    setTimeout(() => transferMessage.value = '', 1800)
  }).catch(() => { transferMessage.value = path })
}
function download(filename: string, text: string): void {
  const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}
function exportCatalogue(): void { download('agent-learning-graph.json', exportGraph()) }
function exportProgress(): void { download('agent-learning-progress.json', exportLearningProgress(progress.value)) }
async function importProgressFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    const imported = importLearningProgress(await file.text())
    progress.value = imported
    saveProgress(imported)
    transferMessage.value = t('learning.progressImported')
  } catch (error) {
    transferMessage.value = error instanceof Error ? error.message : t('learning.importFailed')
  } finally {
    input.value = ''
    setTimeout(() => transferMessage.value = '', 3500)
  }
}
function relationLabel(type: RelationType): string {
  return t(`learning.relation.${type === 'documented-by' ? 'documentedBy' : type === 'informed-by' ? 'informedBy' : type}`)
}
function kindLabel(kind: NodeKind): string { return t(`learning.kind.${kind}`) }
function stateLabel(state: LearningState): string { return t(`learning.progress.${state}`) }
function projectStateLabel(state?: GraphNode['state']): string {
  return state ? t(`learning.projectState.${state}`) : t('learning.projectState.notBuilt')
}
function stageTitle(id: string): string { const node = nodeById.get(id); return node ? title(node) : id }
function recommendationExplanation(): string {
  if (Object.keys(progress.value.records).length === 0) return t('learning.firstVisitRecommendation')
  return t('learning.recommendationExplain')
}

onMounted(() => {
  stageObserver = new ResizeObserver(entries => {
    const size = entries[0]?.contentRect
    if (size) stageSize.value = { width: size.width, height: size.height }
  })
  if (graphStage.value) stageObserver.observe(graphStage.value)
  const routeId = typeof route.query.node === 'string' && nodeById.has(route.query.node) ? route.query.node : selectedId.value
  selectedId.value = routeId
  if (reader.value) reader.value.scrollTop = progress.value.records[routeId]?.scrollTop ?? 0
})
</script>

<template>
  <main class="learning-page">
    <header class="learning-header">
      <div>
        <div class="learning-eyebrow"><span class="learning-eyebrow__dot"></span>{{ t('learning.eyebrow') }}</div>
        <h1>{{ t('learning.title') }}</h1>
        <p>{{ t('learning.subtitle') }}</p>
      </div>
      <div class="learning-header__actions">
        <button class="learning-action" type="button" :aria-expanded="catalogueOpen" @click="catalogueOpen = !catalogueOpen">{{ locale === 'zh-CN' ? '课程目录 / 筛选' : 'Lessons / filters' }}</button>
        <button class="learning-action" type="button" :aria-expanded="readerOpen" @click="readerOpen = !readerOpen">{{ locale === 'zh-CN' ? '所选知识' : 'Selected knowledge' }}</button>
        <div class="learning-progress-chip"><strong>{{ completedCount }}/{{ allLessons.length }}</strong><span>{{ t('learning.masteredLessons') }}</span></div>
        <button class="learning-action" type="button" @click="exportCatalogue">{{ t('learning.exportGraph') }}</button>
        <button class="learning-action" type="button" @click="exportProgress">{{ t('learning.exportProgress') }}</button>
        <button class="learning-action learning-action--quiet" type="button" @click="importPicker?.click()">{{ t('learning.importProgress') }}</button>
        <input ref="importPicker" class="visually-hidden" type="file" accept="application/json,.json" @change="importProgressFile">
      </div>
    </header>

    <div v-if="transferMessage" class="learning-toast" role="status">{{ transferMessage }}</div>

    <div class="learning-workspace" :class="{ 'has-catalogue': catalogueOpen }">
      <aside v-show="catalogueOpen" class="learning-sidebar" :aria-label="t('learning.filters')">
        <label class="learning-search">
          <span>{{ t('learning.search') }}</span>
          <input v-model="query" type="search" :placeholder="t('learning.searchHint')">
        </label>
        <div class="learning-filter-grid">
          <label><span>{{ t('learning.entityType') }}</span><select v-model="kindFilter">
            <option v-for="kind in kindOptions" :key="kind" :value="kind">{{ kind === 'all' ? t('learning.allKinds') : kindLabel(kind) }}</option>
          </select></label>
          <label><span>{{ t('learning.stage') }}</span><select v-model="stageFilter">
            <option value="all">{{ t('learning.allStages') }}</option>
            <option v-for="stage in stages" :key="stage.id" :value="stage.id">{{ title(stage) }}</option>
          </select></label>
          <label><span>{{ t('learning.myProgress') }}</span><select v-model="learningFilter">
            <option value="all">{{ t('learning.allProgress') }}</option>
            <option value="notStarted">{{ stateLabel('notStarted') }}</option>
            <option value="inProgress">{{ stateLabel('inProgress') }}</option>
            <option value="mastered">{{ stateLabel('mastered') }}</option>
          </select></label>
          <label><span>{{ t('learning.relationType') }}</span><select v-model="relationFilter">
            <option value="all">{{ t('learning.allRelations') }}</option>
            <option v-for="type in relationOptions.filter(value => value !== 'all')" :key="type" :value="type">{{ relationLabel(type) }}</option>
          </select></label>
        </div>

        <section class="learning-next" aria-label="recommended lesson">
          <div class="learning-next__top"><span>{{ t('learning.nextSuggestion') }}</span><span class="learning-next__spark">✦</span></div>
          <button v-if="nextLesson" type="button" @click="chooseNode(nextLesson.id)">
            <small>{{ lessonNumber(nextLesson) }}</small>
            <strong>{{ title(nextLesson) }}</strong>
            <span>{{ t('learning.openLesson') }} <b>↗</b></span>
          </button>
          <p>{{ recommendationExplanation() }}</p>
        </section>

        <div class="learning-list-heading"><span>{{ t('learning.entities') }} <b>{{ filteredList.length }}</b></span><span>{{ t('learning.keyboardHint') }}</span></div>
        <nav class="learning-node-list" :aria-label="t('learning.entityList')">
          <button v-for="node in filteredList" :key="node.id" type="button" class="learning-list-row"
            :class="{ 'learning-list-row--selected': node.id === selectedId }" :aria-current="node.id === selectedId ? 'true' : undefined"
            @click="chooseNode(node.id)">
            <i :class="`kind-dot kind-dot--${node.kind}`"></i>
            <span class="learning-list-row__copy"><strong>{{ node.number ? `${String(node.number).padStart(2,'0')} · ` : '' }}{{ title(node) }}</strong><small>{{ kindLabel(node.kind) }}<template v-if="node.stage"> · {{ stageTitle(node.stage) }}</template></small></span>
            <span v-if="node.kind === 'lesson'" class="learning-list-state" :class="`learning-list-state--${stateFor(node.id)}`">{{ stateLabel(stateFor(node.id)) }}</span>
          </button>
          <p v-if="!filteredList.length" class="learning-empty">{{ t('learning.noResults') }}</p>
        </nav>
      </aside>

      <section class="learning-map-column">
        <div class="learning-map-toolbar">
          <div><strong>{{ t('learning.relationshipMap') }}</strong><span>{{ visibleNodes.length }} {{ t('learning.visibleEntities') }} · {{ visibleEdges.length }} {{ t('learning.visibleRelations') }}</span></div>
          <div class="learning-view-switch" role="group" :aria-label="t('learning.graphRange')">
            <button type="button" :class="{ active: graphDimension === '3d' }" @click="graphDimension = '3d'">3D {{ locale === 'zh-CN' ? '星图' : 'space' }}</button>
            <button type="button" :class="{ active: graphDimension === '2d' }" @click="graphDimension = '2d'">2D</button>
            <button type="button" @click="focusSelected">{{ locale === 'zh-CN' ? '以所选为中心' : 'Focus selected' }}</button>
            <button type="button" :class="{ active: viewMode === 'one' }" @click="viewMode = 'one'">1{{ t('learning.hop') }}</button>
            <button type="button" :class="{ active: viewMode === 'two' }" @click="viewMode = 'two'">2{{ t('learning.hop') }}</button>
            <button type="button" :class="{ active: viewMode === 'all' }" @click="viewMode = 'all'">{{ t('learning.fullGraph') }}</button>
          </div>
        </div>
        <div ref="graphStage" class="learning-graph-stage" @keydown.esc="readerOpen = false">
        <KnowledgeGraph3D v-if="graphDimension === '3d'" :nodes="visibleNodes" :edges="visibleEdges" :selected-id="selectedId"
          :highlighted-edge-ids="highlightedEdgeIds" :reduced-motion="reducedMotion" :reading="readerOpen"
          @anchor="detailAnchor = $event" @close="readerOpen = false" @select="chooseNode" @read="openReader" @browse="catalogueOpen = true" @unavailable="fallBackTo2D" />
        <KnowledgeGraphCanvas v-else :nodes="visibleNodes" :edges="visibleEdges" :selected-id="selectedId" :highlighted-edge-ids="highlightedEdgeIds"
          :expanded-node-ids="expandedNodeIds" :reduced-motion="reducedMotion" @select="chooseNode" @expand="toggleExpansion" @dismiss="dismissNode" @reset="restoreScene" @read="openReader" />
        <svg v-if="readerOpen && detailConnector" class="learning-detail-connector" :viewBox="`0 0 ${stageSize.width} ${stageSize.height}`" aria-hidden="true"><path :d="detailConnector"/><circle :cx="detailAnchor?.x" :cy="detailAnchor?.y" r="7"/></svg>
      <aside v-if="readerOpen" class="learning-reader learning-reader--floating" :style="readerPlacement" role="region" :aria-label="t('learning.reader')" @pointerdown.stop @wheel.stop>
        <div class="learning-reader__bar"><span>✦ {{ locale === 'zh-CN' ? '正在探索' : 'EXPLORING' }} · {{ kindLabel(selectedNode.kind) }}</span><button type="button" :aria-label="locale === 'zh-CN' ? '关闭详情' : 'Close details'" @click="readerOpen = false">×</button></div>
        <div ref="reader" class="learning-reader__scroll" @scroll.passive="onReadScroll">
          <div class="learning-reader__eyebrow"><span>{{ kindLabel(selectedNode.kind) }}</span><span v-if="selectedNode.number">{{ lessonNumber(selectedNode) }}</span></div>
          <h2>{{ title(selectedNode) }}</h2>
          <p class="learning-reader__summary">{{ summary(selectedNode) }}</p>
          <section v-if="pathNodeIds.length > 1" class="learning-path-route">
            <div><strong>{{ t('learning.pathToNext') }}</strong><small>{{ lessonNumber(nextLesson!) }}</small></div>
            <div class="learning-path-route__steps">
              <template v-for="(id, index) in pathNodeIds" :key="`${id}-${index}`">
                <button type="button" :class="{ 'learning-path-route__current': id === selectedId }" @click="chooseNode(id)">{{ title(nodeById.get(id)!) }}</button>
                <span v-if="index < pathNodeIds.length - 1" aria-hidden="true">→</span>
              </template>
            </div>
          </section>
          <div v-else-if="selectedId === nextLesson?.id" class="learning-path-reached">{{ t('learning.pathReached') }}</div>

          <template v-if="selectedNode.kind === 'lesson'">
            <div class="learning-status-row">
              <span class="project-status" :class="`project-status--${selectedNode.state ?? 'planned'}`">{{ t('learning.projectStatus') }}: {{ projectStateLabel(selectedNode.state) }}</span>
              <span class="reading-time">{{ selectedNode.minutes }} {{ t('learning.minutesReference') }}</span>
            </div>
            <div class="learning-own-status">
              <label>{{ t('learning.myStatus') }}</label>
              <select :value="selectedProgress.state" @change="onStateChange">
                <option value="notStarted">{{ stateLabel('notStarted') }}</option>
                <option value="inProgress">{{ stateLabel('inProgress') }}</option>
                <option value="mastered">{{ stateLabel('mastered') }}</option>
              </select>
            </div>
            <article class="learning-article">
              <section v-for="section in ['concept','project','practice','acceptance']" :key="section" class="learning-article__section">
                <h3>{{ t(`learning.section.${section}`) }}</h3>
                <p>{{ sectionText(section) }}</p>
              </section>
              <section class="learning-article__section">
                <h3>{{ t('learning.section.springBoot') }}</h3>
                <p>{{ localized(catalogue.springBootAnalogies[selectedNode.id]) }}</p>
              </section>
              <section v-if="interviewQuestions().length" class="learning-article__section learning-interview">
                <h3>{{ t('learning.section.interview') }}</h3>
                <ol><li v-for="question in interviewQuestions()" :key="question">{{ question }}</li></ol>
              </section>
              <section v-if="selectedNode.paths?.length" class="learning-article__section learning-paths">
                <h3>{{ t('learning.codeAndDocs') }}</h3>
                <button v-for="path in selectedNode.paths" :key="path" type="button" class="source-path" @click="copyPath(path)">
                  <code>{{ path }}</code><span>{{ t('learning.copyPath') }}</span>
                </button>
              </section>
            </article>
            <section class="learning-note">
              <label :for="`note-${selectedNode.id}`">{{ t('learning.myNotes') }}</label>
              <textarea :id="`note-${selectedNode.id}`" :value="selectedProgress.note" :placeholder="t('learning.noteHint')" rows="4" maxlength="12000" @input="onNoteInput"></textarea>
            </section>
          </template>
          <template v-else>
            <section class="learning-article__section learning-concept-body">
              <h3>{{ t('learning.knowledgeContent') }}</h3>
              <p>{{ localized(selectedNode.content) || summary(selectedNode) }}</p>
            </section>
            <section v-if="selectedNode.path" class="learning-article__section learning-paths">
              <h3>{{ t('learning.sourceLocation') }}</h3>
              <button type="button" class="source-path" @click="copyPath(selectedNode.path)"><code>{{ selectedNode.path }}</code><span>{{ t('learning.copyPath') }}</span></button>
            </section>
          </template>

          <section v-if="selectedRelations.length" class="learning-relations">
            <div class="learning-section-title"><h3>{{ t('learning.connections') }}</h3><span>{{ selectedRelations.length }}</span></div>
            <button v-for="item in selectedRelations" :key="item.edge.id" type="button" class="learning-relation-row" @click="chooseNode(item.other!.id)">
              <span class="relation-type">{{ relationLabel(item.edge.type) }}</span>
              <strong>{{ item.outgoing ? '→' : '←' }} {{ title(item.other!) }}</strong>
              <small>{{ localized(item.edge.reason) }}</small>
            </button>
          </section>
          <div class="learning-reader__tail">{{ transferMessage }}</div>
        </div>
      </aside>
        </div>
        <div class="learning-map-footer">
          <span>{{ graphDimension === '3d' ? (locale === 'zh-CN' ? '节点大小代表连接数 · 颜色区分知识类型' : 'Node size reflects connections · color represents knowledge type') : (locale === 'zh-CN' ? '颜色区分知识类型' : 'Color represents knowledge type') }}</span>
          <label class="learning-motion"><input v-model="reducedMotion" type="checkbox">{{ t('learning.reduceMotion') }}</label>
        </div>
      </section>
    </div>

    <footer class="learning-footnote">
      <span>{{ t('learning.progressLocalNote') }}</span>
      <span>{{ graphCounts() }}</span>
    </footer>
  </main>
</template>

<style scoped>
.learning-page{--ink:#17211f;--muted:#78827e;--line:#e6e9e5;--mint:#3e917e;--paper:#fffefa;max-width:none;padding:2px 0 14px;color:var(--ink)}
.learning-header{display:flex;justify-content:space-between;align-items:flex-end;gap:24px;margin:4px 0 22px}.learning-eyebrow{display:flex;align-items:center;gap:8px;color:var(--mint);font-size:11px;font-weight:700;letter-spacing:.13em;text-transform:uppercase}.learning-eyebrow__dot{width:7px;height:7px;border-radius:50%;background:#51a68f;box-shadow:0 0 0 4px #51a68f20}.learning-header h1{margin:9px 0 4px;font-family:var(--font-display);font-size:30px;letter-spacing:-.035em}.learning-header p{margin:0;color:var(--muted);font-size:13px}.learning-header__actions{display:flex;flex-wrap:wrap;align-items:center;justify-content:flex-end;gap:7px}.learning-progress-chip{display:flex;align-items:baseline;gap:7px;margin-right:5px;padding:8px 11px;border:1px solid #dce7e1;border-radius:9px;background:#f5faf7;color:var(--muted);font-size:11px}.learning-progress-chip strong{color:#317d6c;font-size:15px}.learning-action{padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:var(--paper);color:#46534e;font-size:11px;font-weight:600;cursor:pointer}.learning-action:hover{border-color:#9ac8ba;color:#347e6d}.learning-action--quiet{background:transparent}.visually-hidden{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}.learning-toast{position:fixed;z-index:20;top:75px;right:24px;padding:10px 14px;border:1px solid #c6e5d7;border-radius:8px;background:#f0faf4;color:#247258;box-shadow:0 8px 24px #19251d15;font-size:12px}
.learning-workspace{display:grid;grid-template-columns:258px minmax(420px,1fr) 350px;gap:14px;align-items:stretch;min-height:670px;height:calc(100vh - 218px);max-height:860px}.learning-sidebar,.learning-reader{min-height:0;border:1px solid var(--line);border-radius:14px;background:var(--paper)}.learning-sidebar{display:flex;flex-direction:column;padding:13px 11px 8px;overflow:hidden}.learning-search span,.learning-filter-grid label span{display:block;margin-bottom:6px;color:#68736e;font-size:10px;font-weight:700}.learning-search input,.learning-filter-grid select,.learning-own-status select{width:100%;height:35px;padding:0 10px;border:1px solid #e0e5e1;border-radius:7px;background:#fff;color:#34423c;font-size:11px;outline:none}.learning-search input:focus,.learning-filter-grid select:focus,.learning-own-status select:focus{border-color:#8bbdaf;box-shadow:0 0 0 3px #69ad961b}.learning-filter-grid{display:grid;grid-template-columns:1fr 1fr;gap:9px 7px;margin-top:12px}.learning-filter-grid label{min-width:0}.learning-filter-grid select{padding:0 6px}.learning-next{margin:13px 0 13px;padding:10px;border:1px solid #d9e9e0;border-radius:10px;background:linear-gradient(140deg,#f5faf7,#fdfaf3)}.learning-next__top{display:flex;justify-content:space-between;color:#567368;font-size:10px;font-weight:700}.learning-next__spark{color:#c3994d}.learning-next button{display:flex;flex-direction:column;align-items:flex-start;width:100%;margin-top:6px;padding:0;border:0;background:none;text-align:left;cursor:pointer}.learning-next button small{color:#7b8d84;font-size:9px}.learning-next button strong{margin:3px 0;color:#28443b;font-size:12px}.learning-next button span{color:#4b9782;font-size:10px}.learning-next button b{font-size:14px}.learning-next p{margin:7px 0 0;color:#87918c;font-size:9px;line-height:1.5}.learning-list-heading{display:flex;justify-content:space-between;align-items:center;margin:1px 3px 6px;color:#5f6d66;font-size:10px;font-weight:700}.learning-list-heading b{margin-left:3px;color:#2d826d}.learning-list-heading span:last-child{color:#a1aaa5;font-size:9px;font-weight:400}.learning-node-list{display:flex;flex-direction:column;gap:2px;overflow:auto;min-height:0;padding:2px 1px 10px;scrollbar-width:thin}.learning-list-row{display:flex;align-items:center;gap:8px;width:100%;min-height:45px;padding:6px 7px;border:1px solid transparent;border-radius:8px;background:transparent;text-align:left;cursor:pointer}.learning-list-row:hover{background:#f6f8f5}.learning-list-row--selected{border-color:#d9eae2;background:#f2f8f4}.kind-dot{flex:0 0 8px;width:8px;height:8px;border-radius:50%}.kind-dot--lesson{background:#4da992}.kind-dot--topic{background:#d39c50}.kind-dot--concept{background:#688de1}.kind-dot--evidence{background:#a778d0}.kind-dot--source{background:#d9756d}.learning-list-row__copy{display:flex;flex:1;flex-direction:column;min-width:0;gap:3px}.learning-list-row__copy strong{overflow:hidden;color:#34413c;font-size:10.5px;font-weight:600;text-overflow:ellipsis;white-space:nowrap}.learning-list-row__copy small{overflow:hidden;color:#929b96;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.learning-list-state{color:#8d9992;font-size:8px;white-space:nowrap}.learning-list-state--mastered{color:#3a927a}.learning-list-state--inProgress{color:#b58039}.learning-empty{padding:12px;color:#87918c;font-size:11px;text-align:center}
.learning-map-column{display:flex;flex-direction:column;min-width:0;min-height:0}.learning-map-toolbar{display:flex;justify-content:space-between;align-items:center;gap:10px;margin:0 3px 9px}.learning-map-toolbar>div:first-child{display:flex;flex-direction:column;gap:3px}.learning-map-toolbar strong{color:#31403a;font-size:13px}.learning-map-toolbar span{color:#849089;font-size:10px}.learning-view-switch{display:flex;gap:3px;padding:3px;border:1px solid #e4e8e4;border-radius:8px;background:#f7f8f5}.learning-view-switch button{padding:5px 7px;border:0;border-radius:5px;background:transparent;color:#79837e;font-size:9px;cursor:pointer}.learning-view-switch button.active{background:white;color:#337e6c;box-shadow:0 1px 3px #243a2d18}.learning-map-column :deep(.graph-canvas){flex:1;min-height:0}.learning-map-footer{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:8px 3px 0;color:#818c85;font-size:9px}.learning-map-footer>span{display:flex;align-items:center;gap:6px}.learning-status-ring{width:8px;height:8px;border:1px dashed #4eaa92;border-radius:50%}.learning-motion{display:flex;align-items:center;gap:5px;cursor:pointer}.learning-motion input{accent-color:#3f927d}
.learning-reader{overflow:hidden}.learning-reader__scroll{height:100%;overflow:auto;padding:18px 18px 20px;scrollbar-width:thin}.learning-reader__eyebrow{display:flex;gap:8px;align-items:center;color:#438b79;font-size:9px;font-weight:700;letter-spacing:.08em;text-transform:uppercase}.learning-reader__eyebrow span+span{padding-left:8px;border-left:1px solid #d8ded9;color:#9a8a69}.learning-reader h2{margin:10px 0 7px;color:#25332e;font-family:var(--font-display);font-size:21px;line-height:1.3;letter-spacing:-.025em}.learning-reader__summary{margin:0 0 12px;color:#6c7972;font-size:11px;line-height:1.7}.learning-status-row{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:8px 0;border-top:1px solid #edf0ec;border-bottom:1px solid #edf0ec}.project-status{padding:4px 7px;border-radius:5px;font-size:9px}.project-status--completed{background:#eaf6ef;color:#397d60}.project-status--in-progress{background:#fff4df;color:#9d7130}.project-status--planned{background:#f0f2ef;color:#849089}.reading-time{color:#949d98;font-size:9px}.learning-own-status{display:flex;justify-content:space-between;align-items:center;gap:8px;margin:10px 0 4px}.learning-own-status label,.learning-note label{color:#5e6c64;font-size:10px;font-weight:700}.learning-own-status select{width:auto;min-width:95px;height:29px;font-size:10px}.learning-article__section{padding:11px 0 10px;border-bottom:1px solid #eef0ed}.learning-article__section h3,.learning-section-title h3{margin:0 0 6px;color:#3f554b;font-size:11px;font-weight:700}.learning-article__section p,.learning-article__section li{margin:0;color:#596761;font-size:10.5px;line-height:1.72;white-space:pre-line}.learning-interview ol{display:flex;flex-direction:column;gap:7px;margin:0;padding-left:17px}.learning-paths{display:flex;flex-direction:column;gap:5px}.source-path{display:flex;justify-content:space-between;align-items:center;gap:7px;padding:6px 7px;border:1px solid #e7ebe7;border-radius:6px;background:#fbfcf9;text-align:left;cursor:pointer}.source-path:hover{border-color:#b8d8cc;background:#f4faf6}.source-path code{overflow-wrap:anywhere;color:#52675d;font-size:9px}.source-path span{flex:0 0 auto;color:#438b79;font-size:8px}.learning-note{display:flex;flex-direction:column;gap:7px;padding:12px 0}.learning-note textarea{resize:vertical;width:100%;min-height:74px;padding:8px;border:1px solid #e3e8e3;border-radius:7px;background:#fff;color:#405048;font:inherit;font-size:10px;line-height:1.5;outline:none}.learning-note textarea:focus{border-color:#8bbdaf;box-shadow:0 0 0 3px #69ad961b}.learning-concept-body p{line-height:1.85}.learning-relations{padding:12px 0 3px}.learning-section-title{display:flex;justify-content:space-between;align-items:center}.learning-section-title h3{margin:0 0 7px}.learning-section-title>span{color:#8b9790;font-size:9px}.learning-relation-row{display:flex;flex-direction:column;align-items:flex-start;gap:4px;width:100%;margin-top:6px;padding:8px;border:1px solid #e9ece8;border-radius:7px;background:#fff;text-align:left;cursor:pointer}.learning-relation-row:hover{border-color:#b7d9cd;background:#f7fbf8}.relation-type{color:#558d7d;font-size:8px;font-weight:700;text-transform:uppercase}.learning-relation-row strong{color:#394942;font-size:10px}.learning-relation-row small{color:#89948d;font-size:9px;line-height:1.5}.learning-reader__tail{min-height:1px;color:#438b79;font-size:9px}.learning-footnote{display:flex;justify-content:space-between;gap:16px;margin:10px 2px 0;color:#98a19b;font-size:9px}.learning-footnote span:last-child{white-space:nowrap}
.learning-path-route{margin:7px 0 11px;padding:9px;border:1px solid #ecdacb;border-radius:8px;background:#fff9f2}.learning-path-route>div:first-child{display:flex;justify-content:space-between;gap:8px;margin-bottom:7px}.learning-path-route strong{color:#a36f43;font-size:9px}.learning-path-route small{color:#a58f7b;font-size:8px}.learning-path-route__steps{display:flex;flex-wrap:wrap;align-items:center;gap:5px}.learning-path-route__steps button{padding:4px 5px;border:1px solid #f0dfcc;border-radius:5px;background:#fff;color:#765b40;font-size:8px;cursor:pointer}.learning-path-route__steps button:hover,.learning-path-route__steps button.learning-path-route__current{border-color:#d49d65;background:#fff2e2}.learning-path-route__steps span{color:#bf9670;font-size:10px}.learning-path-reached{margin:6px 0 11px;padding:8px;border-radius:7px;background:#edf7f0;color:#48826a;font-size:9px}
@media(max-width:1180px){.learning-workspace{grid-template-columns:230px minmax(340px,1fr) 310px;gap:10px}.learning-reader__scroll{padding:15px}.learning-header__actions{max-width:430px}}
@media(max-width:900px){.learning-header{align-items:flex-start;flex-direction:column}.learning-header__actions{justify-content:flex-start}.learning-workspace{height:auto;max-height:none;grid-template-columns:220px minmax(0,1fr);min-height:0}.learning-sidebar{height:650px}.learning-map-column{height:650px}.learning-reader{grid-column:1/-1;max-height:720px;min-height:450px}.learning-footnote{flex-direction:column;gap:5px}}
@media(max-width:620px){.learning-header h1{font-size:25px}.learning-header p{max-width:40ch;line-height:1.6}.learning-header__actions{gap:5px}.learning-progress-chip{width:100%}.learning-workspace{display:flex;flex-direction:column}.learning-sidebar{height:540px}.learning-map-column{height:520px}.learning-reader{height:620px;min-height:400px}.learning-filter-grid{grid-template-columns:1fr 1fr}.learning-map-toolbar{align-items:flex-start;flex-direction:column}.learning-footnote{font-size:8px}}
.learning-workspace{grid-template-columns:minmax(0,1fr);height:calc(100vh - 225px);max-height:none;min-height:620px;gap:10px}
.learning-workspace.has-catalogue{grid-template-columns:230px minmax(0,1fr)}
.learning-map-toolbar{padding:0 3px}.learning-reader,.learning-sidebar{border-radius:4px}
.learning-header h1{font-size:26px}.learning-header{margin-bottom:16px}
.learning-map-column :deep(.graph-canvas){min-height:500px}
@media(max-width:1000px){.learning-workspace{height:auto}.learning-map-column{height:680px}.learning-sidebar{height:680px}}
@media(max-width:620px){.learning-workspace{display:flex;min-height:0}.learning-workspace .learning-sidebar{height:420px}.learning-workspace .learning-map-column{height:600px}.learning-workspace .learning-reader{height:600px}.learning-map-column :deep(.graph-canvas){min-height:470px}}
.learning-page .learning-action,.learning-page .learning-map-toolbar span,.learning-page .learning-map-footer{font-size:12px}
.learning-page .learning-list-row__copy strong{font-size:13px}.learning-page .learning-list-row__copy small,.learning-page .learning-list-state{font-size:11px}
.learning-page .learning-search span,.learning-page .learning-filter-grid label span,.learning-page .learning-list-heading{font-size:12px}
.learning-page .learning-search input,.learning-page .learning-filter-grid select,.learning-page .learning-own-status select{font-size:14px;min-height:36px}
.learning-page .learning-reader__summary,.learning-page .learning-article__section p,.learning-page .learning-article__section li{font-size:14px;line-height:1.8}
.learning-page .learning-article__section h3,.learning-page .learning-section-title h3{font-size:15px}
.learning-page .learning-note textarea,.learning-page .learning-note label,.learning-page .learning-own-status label{font-size:13px}
.learning-page .source-path code,.learning-page .source-path span{font-size:12px}
.learning-page .learning-relation-row strong{font-size:13px}.learning-page .learning-relation-row small,.learning-page .relation-type{font-size:12px}
.learning-page .learning-view-switch button{font-size:12px}.learning-view-switch{flex-wrap:wrap}
/* A contextual reading window belongs to the map; opening it never resizes the graph. */
.learning-graph-stage{position:relative;flex:1;min-height:500px;isolation:isolate}
.learning-graph-stage>:deep(.graph-canvas){height:100%;min-height:100%}
.learning-graph-stage:fullscreen{width:100vw;height:100vh;background:#050914}
.learning-detail-connector{position:absolute;inset:0;width:100%;height:100%;z-index:6;pointer-events:none;overflow:hidden}
.learning-detail-connector path{fill:none;stroke:#a1d9ff;stroke-width:1;opacity:.7}.learning-detail-connector circle{fill:none;stroke:#a1d9ff;stroke-width:1;opacity:.6}
.learning-page .learning-reader--floating{position:absolute;z-index:8;min-height:0;max-height:none;grid-column:auto;display:flex;flex-direction:column;overflow:hidden;border:1px solid #a4c5df88;border-radius:14px;background:#f7f9fc;box-shadow:0 24px 70px #0007,0 0 0 1px #ffffff18;animation:reader-appear .18s ease-out}
.learning-reader__bar{display:flex;align-items:center;justify-content:space-between;flex-shrink:0;padding:10px 14px;border-bottom:1px solid #dbe5ee;background:#eaf0f7;color:#47657e;font-size:11px;font-weight:600;letter-spacing:.05em}
.learning-reader__bar button{display:grid;place-items:center;width:28px;height:28px;border:1px solid #cad9e7;border-radius:7px;background:#f9fcff;color:#37566e;font-size:20px;cursor:pointer}
.learning-reader__bar button:hover{background:#dce9f5}.learning-reader__bar button:focus-visible{outline:2px solid #287dc3;outline-offset:2px}
.learning-reader--floating .learning-reader__scroll{height:auto;min-height:0;flex:1;overscroll-behavior:contain;padding:17px 20px 24px}
.learning-reader--floating h2{font-size:21px;color:#1d3249}
.learning-reader--floating .learning-reader__eyebrow{color:#4c6c8c;font-size:11px}
.learning-reader--floating .learning-reader__summary{color:#536880;font-size:13px;line-height:1.8}
.learning-reader--floating .learning-article__section{border-color:#e0e7ef}
.learning-reader--floating .learning-article__section h3,.learning-reader--floating .learning-section-title h3{color:#294d70;font-size:13px}
.learning-reader--floating .learning-article__section p,.learning-reader--floating .learning-article__section li{color:#3b5068;font-size:13px;line-height:1.85}
.learning-reader--floating .learning-status-row{flex-wrap:wrap;border-color:#e0e7ef}
.learning-reader--floating .project-status,.learning-reader--floating .reading-time{font-size:11px}
.learning-reader--floating .source-path{width:100%;background:#eef3f9;border-color:#dbe4ef}
.learning-reader--floating .source-path code{font-size:11px;color:#3c5c7b}
.learning-reader--floating .learning-note textarea{box-sizing:border-box}
@keyframes reader-appear{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:translateY(0)}}
@media(prefers-reduced-motion:reduce){.learning-page .learning-reader--floating{animation:none}}
@media(max-width:620px){.learning-page .learning-workspace .learning-reader--floating{min-height:0}.learning-graph-stage{min-height:470px}}
</style>
