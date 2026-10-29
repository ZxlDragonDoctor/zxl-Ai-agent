import { sessionFetch, SessionRequestError } from '../utils/sessionRequest.ts'
import { mcpRequest } from './mcp.ts'

export interface SkillTool { serverId: string; originalName: string; exposedName: string; schemaVersion: string; schemaSha256: string; readOnly: true }
export interface SkillDefinition { skillId: string; version: string; description: string; steps: string[]; tools: SkillTool[]; outputSchemaVersion: string; budgetMs: number; state: 'ACTIVE' | 'DISABLED'; sha256: string }
export interface SkillView { definition: SkillDefinition; enabled: boolean; isDefault: boolean }
export interface SkillCatalogue { defaultVersion: string; versions: SkillView[]; administrationEnabled: false }
export interface SkillSnapshot { runId: string; definition: SkillDefinition; definitionSha256: string; toolSnapshotSha256: string; executorVersion: string; policyVersion: string; selectedAt: number | string }
export interface SkillStep { step: string; status: string; elapsedMs: number; code: string }
export interface SkillDraft { schemaVersion: string; title: string; problem: string; advice: string; priority: string | null; created: false; requiresApproval: true }
export interface SkillResult { snapshot: SkillSnapshot; status: string; advice: string; draft: SkillDraft | null; steps: SkillStep[]; code: string }
export interface SkillRun { snapshot?: SkillSnapshot; steps: SkillStep[]; result?: SkillResult; invalidEvent?: boolean }
const stepNames = ['CLARIFY', 'LOOKUP', 'VERIFY_ADVISE', 'DRAFT']
const text = (value: unknown, max = 2000): value is string => typeof value === 'string' && value.length > 0 && value.length <= max
const digest = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
function definition(value: any): value is SkillDefinition {
  return !!value && text(value.skillId, 80) && text(value.version, 40) && text(value.description, 4000)
    && Array.isArray(value.steps) && value.steps.length === 4 && value.steps.every((s: unknown, i: number) => s === stepNames[i])
    && Array.isArray(value.tools) && value.tools.length > 0 && value.tools.length <= 8 && value.tools.every((t: any) => t && ['serverId', 'originalName', 'exposedName', 'schemaVersion'].every(k => text(t[k], 200)) && digest(t.schemaSha256) && t.readOnly === true)
    && ['ticket-draft-v1', 'ticket-draft-v2'].includes(value.outputSchemaVersion) && Number.isInteger(value.budgetMs) && value.budgetMs > 0 && value.budgetMs <= 30000
    && ['ACTIVE', 'DISABLED'].includes(value.state) && digest(value.sha256)
}
function snapshot(value: any): value is SkillSnapshot {
  return !!value && text(value.runId, 100) && definition(value.definition) && value.definitionSha256 === value.definition.sha256 && digest(value.toolSnapshotSha256)
    && text(value.executorVersion, 100) && text(value.policyVersion, 100)
    && (Number.isSafeInteger(value.selectedAt) && value.selectedAt > 0 || text(value.selectedAt, 100) && Number.isFinite(Date.parse(value.selectedAt)))
}
function step(value: any): value is SkillStep {
  return !!value && stepNames.includes(value.step) && ['RUNNING', 'SUCCEEDED', 'NEEDS_INPUT', 'FAILED', 'CANCELLED'].includes(value.status)
    && Number.isFinite(value.elapsedMs) && value.elapsedMs >= 0 && text(value.code, 100)
}
export function readSkillCatalogue(value: any): SkillCatalogue | null {
  if (!value || !text(value.defaultVersion, 40) || value.administrationEnabled !== false || !Array.isArray(value.versions) || value.versions.length > 16 || !value.versions.length) return null
  if (!value.versions.every((v: any) => v && definition(v.definition) && typeof v.enabled === 'boolean' && typeof v.isDefault === 'boolean')) return null
  const keys = value.versions.map((v: SkillView) => `${v.definition.skillId}:${v.definition.version}`)
  if (new Set(keys).size !== keys.length || !value.versions.some((v: SkillView) => v.isDefault && v.definition.version === value.defaultVersion)) return null
  return value as SkillCatalogue
}
export function readSkillEvent(type: 'SKILL_SELECTED', content: string): SkillSnapshot | null
export function readSkillEvent(type: 'SKILL_STEP', content: string): SkillStep | null
export function readSkillEvent(type: 'SKILL_TERMINAL', content: string): SkillResult | null
export function readSkillEvent(type: string, content: string): SkillSnapshot | SkillStep | SkillResult | null
export function readSkillEvent(type: string, content: string): SkillSnapshot | SkillStep | SkillResult | null {
  if (content.length > 262144) return null
  try {
    const v = JSON.parse(content)
    if (type === 'SKILL_SELECTED') return snapshot(v) ? v : null
    if (type === 'SKILL_STEP') return step(v) ? v : null
    if (type !== 'SKILL_TERMINAL' || !snapshot(v?.snapshot) || !['NEEDS_INPUT', 'SUCCEEDED', 'NO_EVIDENCE', 'DENIED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'CAPACITY'].includes(v.status)
      || typeof v.advice !== 'string' || v.advice.length > 20000 || !text(v.code, 100) || !Array.isArray(v.steps) || v.steps.length > 4 || !v.steps.every(step)) return null
    if (v.draft !== null) {
      const d = v.draft
      if (!d || d.schemaVersion !== v.snapshot.definition.outputSchemaVersion || !text(d.title, 1000) || !text(d.problem, 8000) || !text(d.advice, 20000) || d.created !== false || d.requiresApproval !== true
        || (d.schemaVersion === 'ticket-draft-v1' ? d.priority !== null : d.priority !== 'NORMAL')) return null
    }
    return v as SkillResult
  } catch { return null }
}
export async function skillsRequest(signal?: AbortSignal): Promise<unknown> {
  let response: Response
  try { response = await sessionFetch('/api/ai/learning/skills', { method: 'GET', signal }) }
  catch (error) { if (error instanceof SessionRequestError) throw error; throw new Error(signal?.aborted ? 'CANCELLED' : 'SKILL_NETWORK_UNAVAILABLE') }
  if (!response.ok) throw new Error(`SKILL_HTTP_${response.status}`)
  return response.json().catch(() => { throw new Error('SKILL_INVALID_RESPONSE') })
}
/** A superseded status response cannot overwrite the current service state. Reading never reconnects. */
export function createSkillServiceReader(request = mcpRequest) {
  let generation = 0, disposed = false, controller: AbortController | null = null
  return {
    async read(rediscover = false): Promise<{ state: string; toolCount: number } | null> {
      const token = ++generation; controller?.abort(); controller = new AbortController()
      try {
        const raw: any = await request(rediscover ? '/refresh' : '/status', rediscover ? {} : undefined, controller.signal)
        if (disposed || token !== generation) return null
        if (!raw || !['READY', 'UNAVAILABLE', 'CONNECTING', 'CLOSED', 'NEW'].includes(raw.state) || !Number.isInteger(raw.toolCount) || raw.toolCount < 0 || raw.toolCount > 20) throw new Error('MCP_INVALID_STATUS')
        return { state: raw.state, toolCount: raw.toolCount }
      } catch (cause) { if (disposed || token !== generation) return null; throw cause }
    },
    close() { disposed = true; ++generation; controller?.abort() },
  }
}
