export async function formalSession(origin, tenantId, loginName, password, role) {
  const base = new URL(origin)
  if (base.protocol !== 'http:' || base.hostname !== '127.0.0.1' || !base.port
      || base.href !== base.origin + '/' || !/^lesson(?:19-browser|22-[a-z0-9-]+)$/.test(tenantId ?? '')
      || !loginName || !password) throw new Error('An explicit local lesson fixture login is required')
  const cookies = new Map()
  let csrf
  async function request(path, body) {
    const response = await fetch(base.origin + '/api' + path, {
      redirect: 'error', signal: AbortSignal.timeout(60_000),
      method: body === undefined ? 'GET' : 'POST',
      headers: { Cookie: [...cookies.values()].join('; '), Origin: base.origin,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!response.ok) {
      const failure = await response.json().catch(() => null)
      const code = typeof failure?.code === 'string' && /^[A-Z][A-Z0-9_]{0,79}$/.test(failure.code)
        ? ' ' + failure.code : ''
      throw new Error('Formal fixture request failed: HTTP ' + response.status + code)
    }
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0]
      cookies.set(pair.split('=', 1)[0], pair)
    }
    return response.json()
  }
  csrf = (await request('/auth/status')).csrfToken
  const session = await request('/auth/login', { tenantId, loginName, password })
  if (session.principal?.tenantId !== tenantId || session.principal?.role !== role || !session.csrfToken) {
    throw new Error('Expected fixture session role: ' + role)
  }
  csrf = session.csrfToken
  return request
}
