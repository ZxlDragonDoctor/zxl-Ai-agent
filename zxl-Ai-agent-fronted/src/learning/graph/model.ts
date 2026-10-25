import rawCatalogue from './catalogue.json'

export type NodeKind = 'lesson' | 'topic' | 'concept' | 'source' | 'evidence'
export type RelationType = 'contains' | 'prerequisite' | 'related' | 'implements' | 'documented-by' | 'documents' | 'informed-by'
export type LearningState = 'notStarted' | 'inProgress' | 'mastered'
export type LocalizedText = Record<'zh-CN' | 'en', string>

export interface GraphNode {
  id: string
  kind: NodeKind
  title: LocalizedText
  summary: LocalizedText
  stage?: string
  number?: number
  state?: 'completed' | 'in-progress' | 'planned'
  minutes?: number
  sections?: Record<string, LocalizedText | Record<'zh-CN' | 'en', string[]>>
  content?: LocalizedText
  paths?: string[]
  path?: string
}

export interface GraphEdge {
  id: string
  from: string
  to: string
  type: RelationType
  reason: LocalizedText
}

export interface GraphCatalogue {
  schemaVersion: number
  springBootAnalogies: Record<string, LocalizedText>
  nodes: GraphNode[]
  edges: GraphEdge[]
}

export interface LearningRecord {
  state: LearningState
  note: string
  scrollTop: number
  updatedAt: string
}

export interface LearningProgress {
  schemaVersion: 1
  records: Record<string, LearningRecord>
}

const knownKinds = new Set<NodeKind>(['lesson', 'topic', 'concept', 'source', 'evidence'])
const knownRelations = new Set<RelationType>(['contains', 'prerequisite', 'related', 'implements', 'documented-by', 'documents', 'informed-by'])
const progressKey = 'zxl-agent-learning-progress'

function isText(value: unknown): value is LocalizedText {
  return !!value && typeof value === 'object'
    && typeof (value as LocalizedText)['zh-CN'] === 'string'
    && typeof (value as LocalizedText).en === 'string'
}

function hasLocalizedList(value: unknown): value is Record<'zh-CN' | 'en', string[]> {
  if (!value || typeof value !== 'object') return false
  const list = value as Record<'zh-CN' | 'en', unknown>
  return Array.isArray(list['zh-CN']) && list['zh-CN'].length > 0
    && list['zh-CN'].every(item => typeof item === 'string' && item.trim())
    && Array.isArray(list.en) && list.en.length > 0
    && list.en.every(item => typeof item === 'string' && item.trim())
}

export function validateCatalogue(value: unknown): GraphCatalogue {
  if (!value || typeof value !== 'object') throw new Error('知识图谱数据必须是 JSON 对象。')
  const candidate = value as GraphCatalogue
  if (candidate.schemaVersion !== 1 || !Array.isArray(candidate.nodes) || !Array.isArray(candidate.edges)
    || !candidate.springBootAnalogies || typeof candidate.springBootAnalogies !== 'object') {
    throw new Error('不支持的知识图谱结构版本。')
  }
  const ids = new Set<string>()
  for (const node of candidate.nodes) {
    if (!node || typeof node.id !== 'string' || !node.id.trim() || ids.has(node.id)) throw new Error('图谱存在空 ID 或重复 ID。')
    if (!knownKinds.has(node.kind) || !isText(node.title) || !node.title['zh-CN'].trim() || !isText(node.summary)) {
      throw new Error(`图谱节点 ${node.id} 缺少类型、双语标题或说明。`)
    }
    if (node.kind === 'lesson') {
      if (!Number.isInteger(node.number) || (node.number ?? 0) < 1) throw new Error(`课程 ${node.id} 缺少稳定课次。`)
      const analogy = candidate.springBootAnalogies[node.id]
      if (!isText(analogy) || !analogy['zh-CN'].trim() || !analogy.en.trim()) throw new Error(`课程 ${node.id} 缺少 Spring Boot 类比。`)
      for (const section of ['concept', 'project', 'practice', 'acceptance']) {
        const content = node.sections?.[section]
        if (!isText(content) || !content['zh-CN'].trim() || !content.en.trim()) throw new Error(`课程 ${node.id} 缺少双语 ${section} 内容。`)
      }
      if (!hasLocalizedList(node.sections?.interview)) throw new Error(`课程 ${node.id} 缺少双语面试问题。`)
    }
    if (node.kind === 'concept' && (!isText(node.content) || !node.content['zh-CN'].trim() || !node.content.en.trim())) {
      throw new Error(`概念 ${node.id} 缺少双语知识正文。`)
    }
    if (node.kind === 'evidence' && (!node.path || !node.path.trim())) throw new Error(`证据节点 ${node.id} 缺少仓库路径。`)
    ids.add(node.id)
  }
  const edgeIds = new Set<string>()
  for (const edge of candidate.edges) {
    if (!edge || typeof edge.id !== 'string' || edgeIds.has(edge.id)) throw new Error('图谱存在空关系 ID 或重复关系 ID。')
    if (!ids.has(edge.from) || !ids.has(edge.to)) throw new Error(`关系 ${edge.id} 指向了不存在的节点。`)
    if (!knownRelations.has(edge.type) || !isText(edge.reason)) throw new Error(`关系 ${edge.id} 缺少受支持的类型或说明。`)
    edgeIds.add(edge.id)
  }
  assertAcyclicPrerequisites(candidate)
  return candidate
}

function assertAcyclicPrerequisites(catalogue: GraphCatalogue): void {
  const prerequisites = new Map<string, string[]>()
  for (const edge of catalogue.edges.filter(item => item.type === 'prerequisite')) {
    prerequisites.set(edge.from, [...(prerequisites.get(edge.from) ?? []), edge.to])
  }
  const visiting = new Set<string>()
  const visited = new Set<string>()
  const visit = (id: string) => {
    if (visiting.has(id)) throw new Error('课程先修关系必须无环。')
    if (visited.has(id)) return
    visiting.add(id)
    for (const next of prerequisites.get(id) ?? []) visit(next)
    visiting.delete(id)
    visited.add(id)
  }
  for (const id of prerequisites.keys()) visit(id)
}

export const catalogue = validateCatalogue(rawCatalogue)
export const nodeById = new Map(catalogue.nodes.map(node => [node.id, node]))

export function getNeighbors(id: string, directions: 'both' | 'out' | 'in' = 'both', types?: Set<RelationType>): GraphEdge[] {
  return catalogue.edges.filter(edge => {
    if (types && !types.has(edge.type)) return false
    return directions === 'out' ? edge.from === id : directions === 'in' ? edge.to === id : edge.from === id || edge.to === id
  })
}

export function getNeighborhood(id: string, hops = 1, types?: Set<RelationType>): Set<string> {
  const visited = new Set([id])
  let frontier = new Set([id])
  for (let step = 0; step < Math.max(0, Math.min(3, hops)); step++) {
    const next = new Set<string>()
    for (const current of frontier) {
      for (const edge of getNeighbors(current, 'both', types)) {
        const other = edge.from === current ? edge.to : edge.from
        if (!visited.has(other)) next.add(other)
      }
    }
    next.forEach(nodeId => visited.add(nodeId))
    frontier = next
  }
  return visited
}

export function findShortestPath(fromId: string, toId: string, types?: Set<RelationType>): GraphEdge[] | undefined {
  if (!nodeById.has(fromId) || !nodeById.has(toId)) return undefined
  if (fromId === toId) return []
  const queue: Array<{ id: string; path: GraphEdge[] }> = [{ id: fromId, path: [] }]
  const visited = new Set([fromId])
  while (queue.length) {
    const current = queue.shift()!
    for (const edge of getNeighbors(current.id, 'both', types)) {
      const next = edge.from === current.id ? edge.to : edge.from
      if (visited.has(next)) continue
      const path = [...current.path, edge]
      if (next === toId) return path
      visited.add(next)
      queue.push({ id: next, path })
    }
  }
  return undefined
}

export function filterNodes(options: {
  query?: string
  kind?: NodeKind | 'all'
  stage?: string | 'all'
  stateFor?: (nodeId: string) => LearningState
  learningState?: LearningState | 'all'
}): GraphNode[] {
  const query = options.query?.trim().toLocaleLowerCase() ?? ''
  return catalogue.nodes.filter(node => {
    if (options.kind && options.kind !== 'all' && node.kind !== options.kind) return false
    if (options.stage && options.stage !== 'all' && node.stage !== options.stage && node.id !== options.stage) return false
    if (options.learningState && options.learningState !== 'all' && node.kind === 'lesson'
      && options.stateFor?.(node.id) !== options.learningState) return false
    const sectionText = Object.values(node.sections ?? {}).flatMap(value => {
      const localized = value as Record<'zh-CN' | 'en', string | string[]>
      return [localized['zh-CN'], localized.en].flatMap(part => Array.isArray(part) ? part : [part])
    })
    const searchable = [node.title['zh-CN'], node.title.en, node.summary['zh-CN'], node.summary.en, node.id,
      ...(node.content ? Object.values(node.content) : []), ...(node.paths ?? []), node.path ?? '', ...sectionText]
    if (query && !searchable
      .some(value => value.toLocaleLowerCase().includes(query))) return false
    return true
  })
}

export function recommendedLesson(progress: LearningProgress): GraphNode | undefined {
  const lessons = catalogue.nodes.filter(node => node.kind === 'lesson')
  const stateFor = (id: string) => progress.records[id]?.state ?? 'notStarted'
  const unfinished = lessons.filter(node => stateFor(node.id) !== 'mastered')
  return unfinished.find(node => catalogue.edges
    .filter(edge => edge.type === 'prerequisite' && edge.to === node.id)
    .every(edge => stateFor(edge.from) === 'mastered'))
    ?? unfinished.find(node => node.id === 'lesson-08')
    ?? unfinished[0]
}

function emptyProgress(): LearningProgress {
  return { schemaVersion: 1, records: {} }
}

export function loadProgress(): LearningProgress {
  try {
    const raw = localStorage.getItem(progressKey)
    if (!raw) return emptyProgress()
    const parsed = JSON.parse(raw) as Partial<LearningProgress>
    if (parsed.schemaVersion !== 1 || !parsed.records || typeof parsed.records !== 'object') return emptyProgress()
    const knownIds = new Set(catalogue.nodes.map(node => node.id))
    const records: LearningProgress['records'] = {}
    for (const [id, value] of Object.entries(parsed.records)) {
      if (!knownIds.has(id) || !value || !['notStarted', 'inProgress', 'mastered'].includes(value.state)) continue
      records[id] = {
        state: value.state,
        note: typeof value.note === 'string' ? value.note.slice(0, 12000) : '',
        scrollTop: Number.isFinite(value.scrollTop) ? Math.max(0, value.scrollTop) : 0,
        updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : '',
      }
    }
    return { schemaVersion: 1, records }
  } catch {
    return emptyProgress()
  }
}

export function saveProgress(progress: LearningProgress): void {
  try { localStorage.setItem(progressKey, JSON.stringify(progress)) } catch { /* Current-page progress remains usable. */ }
}

export function createRecord(state: LearningState = 'notStarted'): LearningRecord {
  return { state, note: '', scrollTop: 0, updatedAt: new Date().toISOString() }
}

export function exportGraph(): string {
  return JSON.stringify(catalogue, null, 2)
}

export function exportLearningProgress(progress: LearningProgress): string {
  return JSON.stringify(progress, null, 2)
}

export function importLearningProgress(raw: string, mode: 'merge' | 'replace' = 'merge'): LearningProgress {
  if (!['merge', 'replace'].includes(mode)) throw new Error('不支持的进度导入方式。')
  const parsed = JSON.parse(raw) as Partial<LearningProgress>
  if (parsed.schemaVersion !== 1 || !parsed.records || typeof parsed.records !== 'object') {
    throw new Error('进度文件格式不正确或版本不支持。')
  }
  const safe = mode === 'replace' ? emptyProgress() : loadProgress()
  const knownIds = new Set(catalogue.nodes.map(node => node.id))
  for (const [id, value] of Object.entries(parsed.records)) {
    if (!knownIds.has(id) || !value || !['notStarted', 'inProgress', 'mastered'].includes(value.state)) continue
    safe.records[id] = {
      state: value.state,
      note: typeof value.note === 'string' ? value.note.slice(0, 12000) : '',
      scrollTop: Number.isFinite(value.scrollTop) ? Math.max(0, value.scrollTop) : 0,
      updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : new Date().toISOString(),
    }
  }
  return safe
}
