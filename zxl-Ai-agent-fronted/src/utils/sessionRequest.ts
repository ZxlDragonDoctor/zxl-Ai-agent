export interface AuthPrincipal { subjectId: string; tenantId: string; loginName: string; role: string }
export interface AuthStatus { mode: 'required' | 'legacy-demo'; authenticated: boolean; principal?: AuthPrincipal; csrfToken: string | null }
export interface SessionState { status: AuthStatus | null; error: string | null; generation: number }
let state: SessionState = { status: null, error: null, generation: 0 }
const listeners = new Set<() => void>()
export const sessionState = (): SessionState => state
export const sessionGeneration = () => state.generation
export const subscribeSession = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
export class SessionRequestError extends Error {
  code: string
  status: number
  constructor(code: string, status = 0) { super(code); this.name = 'SessionRequestError'; this.code = code; this.status = status }
}
const identity = (status: AuthStatus | null) => status ? JSON.stringify([status.mode, status.authenticated, status.principal?.subjectId, status.principal?.tenantId, status.principal?.role]) : 'unknown'
export function replaceSession(status: AuthStatus | null, error: string | null = null, force = false) {
  state = { status, error, generation: state.generation + (force || identity(status) !== identity(state.status) ? 1 : 0) }
  listeners.forEach(listener => listener())
}
export function assertSessionGeneration(generation: number) {
  if (generation !== state.generation) throw new SessionRequestError('STALE_SESSION')
}
export async function requireSuccessfulResponse(response: Response, generation: number, expireIdentity = true): Promise<Response> {
  assertSessionGeneration(generation)
  if (response.ok) return response
  const body = await response.json().catch(() => null)
  assertSessionGeneration(generation)
  const code = typeof body?.code === 'string' ? body.code : ({401:'UNAUTHENTICATED',403:'FORBIDDEN',404:'NOT_FOUND',503:'DEPENDENCY_UNAVAILABLE'} as Record<number,string>)[response.status] || 'REQUEST_FAILED'
  if (response.status === 401 && expireIdentity) replaceSession({ mode:'required', authenticated:false, csrfToken:null }, code, true)
  throw new SessionRequestError(code, response.status)
}
/** Credentials and CSRF only leave this module for our exact origin and /api boundary. */
export async function sessionFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const generation = sessionGeneration()
  const origin = typeof location === 'undefined' ? 'http://localhost' : location.origin
  const request = typeof Request !== 'undefined' && input instanceof Request ? input : null
  const url = new URL(request ? request.url : String(input), origin)
  const trusted = url.origin === origin && !url.username && !url.password && (url.pathname === '/api' || url.pathname.startsWith('/api/'))
  const headers = new Headers(init.headers || request?.headers)
  headers.delete('X-CSRF-Token')
  const method = (init.method || request?.method || 'GET').toUpperCase()
  const csrf = state.status?.csrfToken
  if (trusted && csrf && !['GET','HEAD','OPTIONS'].includes(method)) headers.set('X-CSRF-Token', csrf)
  const response = await fetch(input, { ...init, headers, credentials: trusted ? 'same-origin' : 'omit', redirect: trusted ? 'error' : init.redirect })
  if (!trusted) return response
  assertSessionGeneration(generation)
  if (response.status === 401) await requireSuccessfulResponse(response, generation)
  // Body parsing is asynchronous too. Recheck after it, before the caller can apply results.
  for (const method of ['json','text','blob','arrayBuffer','formData'] as const) {
    const read = response[method]?.bind(response)
    if (read) Object.defineProperty(response, method, { value: async () => { assertSessionGeneration(generation); const body = await read(); assertSessionGeneration(generation); return body } })
  }
  return response
}

/** Preserve legacy history; authenticated resume pointers are scoped to their owner. */
export function sessionStorageKey(base: string): string {
  const principal = state.status?.principal
  return state.status?.mode === 'required' && principal
    ? `${base}:${encodeURIComponent(principal.tenantId)}:${encodeURIComponent(principal.subjectId)}`
    : base
}
