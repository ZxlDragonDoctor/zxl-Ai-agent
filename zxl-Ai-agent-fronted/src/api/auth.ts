import { assertSessionGeneration, replaceSession, requireSuccessfulResponse, sessionGeneration, sessionState, SessionRequestError } from '../utils/sessionRequest.ts'
import type { AuthStatus } from '../utils/sessionRequest.ts'
import { browserSessionTransport, createSessionSync } from '../utils/sessionSync.ts'
import type { SessionSignalTransport } from '../utils/sessionSync.ts'
let crossTab: ReturnType<typeof createSessionSync> | null = null
/** AuthGate owns the listener lifetime; tests can supply a deterministic transport. */
export function startAuthSessionSync(transport: SessionSignalTransport | null = browserSessionTransport()): () => void {
  crossTab?.close()
  if (!transport) { crossTab = null; return () => undefined }
  const instance = createSessionSync({ sender: crypto.randomUUID(), transport,
    invalidate: () => replaceSession(null, null, true), refresh: refreshAuth })
  crossTab = instance
  return () => { instance.close(); if (crossTab === instance) crossTab = null }
}
export function readAuthStatus(raw: unknown): AuthStatus {
  if (!raw || typeof raw !== 'object') throw new SessionRequestError('INVALID_AUTH_RESPONSE')
  const value = raw as AuthStatus
  if (!['required','legacy-demo'].includes(value.mode) || typeof value.authenticated !== 'boolean' || !(value.csrfToken === null || typeof value.csrfToken === 'string')) throw new SessionRequestError('INVALID_AUTH_RESPONSE')
  if (value.mode === 'legacy-demo' && (value.authenticated || value.principal)) throw new SessionRequestError('INVALID_AUTH_RESPONSE')
  if (value.mode === 'required' && value.authenticated && (!value.csrfToken || !value.principal || !['subjectId','tenantId','loginName','role'].every(key => typeof value.principal?.[key as keyof typeof value.principal] === 'string' && value.principal[key as keyof typeof value.principal].length > 0))) throw new SessionRequestError('INVALID_AUTH_RESPONSE')
  return { mode:value.mode, authenticated:value.authenticated, csrfToken:value.csrfToken, ...(value.authenticated ? {principal:value.principal} : {}) }
}
async function authRequest(path: string, generation: number, body?: unknown, csrf?: string | null): Promise<AuthStatus> {
  const response = await fetch(`/api/auth/${path}`, { method: body === undefined ? 'GET' : 'POST', credentials:'same-origin', redirect:'error', headers: body === undefined ? {} : { 'Content-Type':'application/json', ...(csrf ? {'X-CSRF-Token':csrf} : {}) }, ...(body === undefined ? {} : {body:JSON.stringify(body)}) })
  await requireSuccessfulResponse(response, generation, false)
  if (path === 'logout') return { mode:'required', authenticated:false, csrfToken:null }
  const raw = await response.json(); assertSessionGeneration(generation)
  return readAuthStatus(raw)
}
export async function refreshAuth(): Promise<void> {
  const generation = sessionGeneration()
  try { replaceSession(await authRequest('status', generation)) }
  catch (error) {
    if (generation === sessionGeneration()) replaceSession(null, error instanceof SessionRequestError ? error.code : 'AUTH_CONNECTION_ERROR')
    throw error
  }
}
export async function login(credentials: {loginName:string; password:string; tenantId:string}): Promise<void> {
  const csrf = sessionState().status?.csrfToken
  if (!csrf) throw new SessionRequestError('CSRF_REQUIRED')
  replaceSession(null, null, true)
  crossTab?.publish('invalidate')
  const generation=sessionGeneration()
  try { replaceSession(await authRequest('login', generation, credentials, csrf)); crossTab?.publish('refresh') }
  catch(error) { if (generation===sessionGeneration()) { await refreshAuth().catch(()=>undefined); crossTab?.publish('refresh') }; throw error }
}
export async function logout(): Promise<void> {
  const csrf=sessionState().status?.csrfToken
  replaceSession(null, null, true)
  crossTab?.publish('invalidate')
  const generation=sessionGeneration()
  try { await authRequest('logout', generation, {}, csrf); await refreshAuth(); crossTab?.publish('refresh') }
  catch(error) { if(generation===sessionGeneration()) replaceSession(null,error instanceof SessionRequestError ? error.code : 'AUTH_CONNECTION_ERROR'); throw error }
}
