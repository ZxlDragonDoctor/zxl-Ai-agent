import test from 'node:test'
import assert from 'node:assert/strict'
import { readAuthStatus, refreshAuth, login, logout } from '../auth.ts'
import { sessionFetch, sessionState, sessionGeneration, replaceSession } from '../../utils/sessionRequest.ts'
const anonymous = { mode: 'required', authenticated: false, csrfToken: 'anonymous-csrf' }
const actor = id => ({ mode: 'required', authenticated: true, csrfToken: `csrf-${id}`, principal: { subjectId:id, tenantId:'tenant-a', loginName:id, role:'employee' } })
const reply = (body, status = 200) => new Response(JSON.stringify(body), { status, headers:{'Content-Type':'application/json'} })
test('auth contract rejects missing authenticated principal, CSRF and guessed legacy mode', () => {
  assert.equal(readAuthStatus(actor('alice')).principal.subjectId, 'alice')
  assert.throws(() => readAuthStatus({ ...actor('alice'), csrfToken:null }), /INVALID_AUTH_RESPONSE/)
  assert.throws(() => readAuthStatus({ ...actor('alice'), principal:undefined }), /INVALID_AUTH_RESPONSE/)
  assert.throws(() => readAuthStatus({}), /INVALID_AUTH_RESPONSE/)
  assert.throws(() => readAuthStatus({mode:'legacy-demo', authenticated:true, csrfToken:null}), /INVALID_AUTH_RESPONSE/)
})
test('same origin API writes carry cookie and CSRF; external requests strip them', async () => {
  replaceSession(actor('alice')); const original = globalThis.fetch; const calls=[]
  globalThis.fetch = async (url, init) => { calls.push({url,init}); return reply({ok:true}) }
  try {
    await sessionFetch('/api/conversations', { method:'POST' })
    assert.equal(calls[0].init.credentials, 'same-origin')
    assert.equal(new Headers(calls[0].init.headers).get('X-CSRF-Token'), 'csrf-alice')
    await sessionFetch('https://external.example/api', { credentials:'include', headers:{'X-CSRF-Token':'secret'} })
    assert.equal(calls[1].init.credentials, 'omit')
    assert.equal(new Headers(calls[1].init.headers).has('X-CSRF-Token'), false)
  } finally {globalThis.fetch=original}
})
test('switching principal rejects late headers, late body and old 401 without invalidating new actor', async () => {
  const original=globalThis.fetch
  try {
    replaceSession(actor('alice')); let resolve; globalThis.fetch=()=>new Promise(r=>resolve=r)
    const old=sessionFetch('/api/conversations'); replaceSession(actor('bob')); resolve(reply({},401))
    await assert.rejects(old, /STALE_SESSION/); assert.equal(sessionState().status.principal.subjectId,'bob')
    globalThis.fetch=async()=>reply({private:'alice'}); const response=await sessionFetch('/api/conversations')
    replaceSession(actor('charlie')); await assert.rejects(response.json(), /STALE_SESSION/)
  } finally {globalThis.fetch=original}
})
test('401 expires identity while 403 and 503 retain authenticated identity and stable codes', async () => {
  const original=globalThis.fetch
  try {
    for(const status of [403,503]) {
      replaceSession(actor('alice')); globalThis.fetch=async()=>reply({code: status===403?'FORBIDDEN':'DEPENDENCY_UNAVAILABLE',message:'safe'},status)
      const response = await sessionFetch('/api/test'); assert.equal(response.status,status); assert.equal(typeof (await response.json()).code,'string')
      assert.equal(sessionState().status.authenticated,true)
    }
    globalThis.fetch=async()=>reply({code:'UNAUTHENTICATED'},401)
    await assert.rejects(sessionFetch('/api/test'), /UNAUTHENTICATED/)
    assert.equal(sessionState().status.authenticated,false)
  } finally {globalThis.fetch=original}
})
test('login uses anonymous csrf; logout invalidates immediately and reloads fresh anonymous status', async () => {
  const original=globalThis.fetch; const calls=[]
  globalThis.fetch=async(url,init)=>{calls.push({url,init}); return reply(url.endsWith('/login')?actor('alice'):anonymous)}
  try {
    await refreshAuth(); await login({loginName:'alice',password:'example-input',tenantId:'tenant-a'})
    assert.equal(new Headers(calls[1].init.headers).get('X-CSRF-Token'),'anonymous-csrf')
    const generation=sessionGeneration(); const pending=logout()
    assert.ok(sessionGeneration()>generation); assert.equal(sessionState().status,null)
    await pending; assert.equal(sessionState().status.authenticated,false)
    assert.equal(calls.at(-1).url,'/api/auth/status')
  } finally {globalThis.fetch=original}
})

test('logout accepts an empty 204 then obtains a fresh anonymous CSRF token', async () => {
  replaceSession(actor('alice')); const original=globalThis.fetch
  globalThis.fetch=async url=>url.endsWith('/logout') ? new Response(null,{status:204}) : reply(anonymous)
  try { await logout(); assert.equal(sessionState().status.csrfToken,'anonymous-csrf') }
  finally { globalThis.fetch=original }
})
test('failed status never silently enables demo and late login cannot restore a logged out actor', async () => {
  const original=globalThis.fetch
  try {
    globalThis.fetch=async()=>reply({},503)
    await assert.rejects(refreshAuth()); assert.equal(sessionState().status,null)
    replaceSession(anonymous); let resolve
    globalThis.fetch=()=>new Promise(r=>resolve=r)
    const pending=login({loginName:'alice',password:'input',tenantId:'tenant-a'})
    replaceSession(anonymous,null,true); resolve(reply(actor('alice')))
    await assert.rejects(pending,/STALE_SESSION/); assert.equal(sessionState().status.authenticated,false)
  } finally {globalThis.fetch=original}
})
test('required knowledge calls omit caller identity while demo requests retain teaching profiles', async () => {
  const { askKnowledge, proposeFact, recallFacts } = await import('../answer.ts')
  const original=globalThis.fetch; const calls=[]
  const answer={traceId:'t',snapshotId:'s',status:'INSUFFICIENT_EVIDENCE',answer:'No evidence',claims:[],evidence:[]}
  globalThis.fetch=async(url,init)=>{calls.push({url,init}); return reply(answer)}
  try {
    replaceSession(actor('alice'))
    await askKnowledge({question:'q',conversationId:'c',mode:'demo',profile:'bob',budgetMs:1000,tenantId:'forged',userId:'forged'})
    await proposeFact({profile:'bob',sessionId:'c',messageId:'m',userText:'I prefer English',ttlSeconds:3600})
    await recallFacts('bob')
    for (const call of calls) {
      const body=JSON.parse(call.init?.body || '{}')
      assert.equal('profile' in body,false); assert.equal('tenantId' in body,false); assert.equal('userId' in body,false)
      assert.equal(new URL(call.url,'http://localhost').searchParams.has('profile'),false)
    }
    replaceSession({mode:'legacy-demo',authenticated:false,csrfToken:null})
    await recallFacts('bob'); assert.match(calls.at(-1).url,/profile=bob/)
  } finally {globalThis.fetch=original}
})
test('conversation resume keys separate subjects and tenants while retaining the old demo key', async () => {
  const { sessionStorageKey } = await import('../../utils/sessionRequest.ts')
  replaceSession(actor('alice')); const alice=sessionStorageKey('zxl_agent_conversation')
  replaceSession(actor('bob')); assert.notEqual(sessionStorageKey('zxl_agent_conversation'),alice)
  replaceSession({...actor('alice'),principal:{...actor('alice').principal,tenantId:'other'}})
  assert.notEqual(sessionStorageKey('zxl_agent_conversation'),alice)
  replaceSession({mode:'legacy-demo',authenticated:false,csrfToken:null})
  assert.equal(sessionStorageKey('zxl_agent_conversation'),'zxl_agent_conversation')
})
test('a rejected login refreshes anonymous CSRF so credentials can be corrected immediately', async () => {
  replaceSession(anonymous); const original=globalThis.fetch
  globalThis.fetch=async url=>url.endsWith('/login') ? reply({code:'UNAUTHENTICATED'},401) : reply({...anonymous,csrfToken:'retry-csrf'})
  try {
    await assert.rejects(login({loginName:'alice',password:'wrong',tenantId:'tenant-a'}),/UNAUTHENTICATED/)
    assert.equal(sessionState().status.csrfToken,'retry-csrf')
  } finally {globalThis.fetch=original}
})
