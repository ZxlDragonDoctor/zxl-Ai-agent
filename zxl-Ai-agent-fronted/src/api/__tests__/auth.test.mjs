import test from 'node:test'
import assert from 'node:assert/strict'
import { readAuthStatus, refreshAuth, login, logout, startAuthSessionSync } from '../auth.ts'
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

test('cross-tab signals invalidate synchronously, ignore reordered messages and dispose subscription', async () => {
  const { createSessionSync } = await import('../../utils/sessionSync.ts')
  let receive; let closed=false; const sent=[]; const applied=[]
  const sync=createSessionSync({sender:'tab-a',transport:{send:value=>sent.push(value),listen:callback=>{receive=callback;return()=>{closed=true}}},invalidate:()=>applied.push('invalidated'),refresh:async()=>{applied.push('refreshed')}})
  receive({version:1,sender:'tab-b',sequence:2,action:'refresh'})
  assert.deepEqual(applied,['invalidated','refreshed'])
  receive({version:1,sender:'tab-b',sequence:1,action:'invalidate'})
  receive({version:1,sender:'tab-b',sequence:2,action:'refresh'})
  receive({version:1,sender:'tab-a',sequence:9,action:'refresh'})
  receive({version:1,sender:'tab-b',sequence:3,action:'refresh',principal:'forged'})
  assert.equal(applied.length,2)
  sync.publish('invalidate'); sync.publish('refresh')
  assert.deepEqual(sent,[{version:1,sender:'tab-a',sequence:1,action:'invalidate'},{version:1,sender:'tab-a',sequence:2,action:'refresh'}])
  sync.close(); assert.equal(closed,true)
  receive({version:1,sender:'tab-b',sequence:4,action:'refresh'});sync.publish('refresh')
  assert.equal(applied.length,2);assert.equal(sent.length,2)
})

test('remote identity change discards late body before refreshing the shared cookie identity', async () => {
  const { createSessionSync } = await import('../../utils/sessionSync.ts')
  replaceSession(actor('alice'));const original=globalThis.fetch;let receive;let resolveStatus
  globalThis.fetch=async url=>url==='/api/private'?reply({private:'alice'}):new Promise(resolve=>{resolveStatus=resolve})
  try {
    const old=await sessionFetch('/api/private')
    const sync=createSessionSync({sender:'tab-a',transport:{send:()=>{},listen:callback=>{receive=callback;return()=>{}}},invalidate:()=>replaceSession(null,null,true),refresh:refreshAuth})
    receive({version:1,sender:'tab-b',sequence:1,action:'refresh'})
    assert.equal(sessionState().status,null)
    await assert.rejects(old.json(),/STALE_SESSION/)
    resolveStatus(reply(actor('bob')))
    await new Promise(resolve=>setImmediate(resolve))
    assert.equal(sessionState().status.principal.subjectId,'bob');sync.close()
  } finally {globalThis.fetch=original}
})

test('login and logout notify other tabs without publishing credentials or identity', async () => {
  const original=globalThis.fetch;const sent=[]
  const stop=startAuthSessionSync({send:value=>sent.push(value),listen:()=>()=>{}})
  replaceSession(anonymous)
  globalThis.fetch=async url=>reply(url.endsWith('/login')?actor('alice'):anonymous)
  try {
    await login({loginName:'alice',password:'secret-test-password',tenantId:'tenant-a'})
    await logout()
    assert.deepEqual(sent.map(value=>value.action),['invalidate','refresh','invalidate','refresh'])
    assert.deepEqual(sent.map(value=>value.sequence),[1,2,3,4])
    for(const value of sent) assert.deepEqual(Object.keys(value).sort(),['action','sender','sequence','version'])
    assert.doesNotMatch(JSON.stringify(sent),/alice|secret-test-password|tenant-a|csrf/)
  } finally {stop();globalThis.fetch=original}
})

test('failed logout leaves all notified tabs invalidated instead of restoring their previous actor', async () => {
  const original=globalThis.fetch;const sent=[]
  const stop=startAuthSessionSync({send:value=>sent.push(value),listen:()=>()=>{}})
  replaceSession(actor('alice'));globalThis.fetch=async()=>reply({code:'DEPENDENCY_UNAVAILABLE'},503)
  try {
    await assert.rejects(logout(),/DEPENDENCY_UNAVAILABLE/)
    assert.equal(sessionState().status,null)
    assert.deepEqual(sent.map(value=>value.action),['invalidate'])
  } finally {stop();globalThis.fetch=original}
})

test('a newer invalidate blocks a pending remote refresh and never echoes a received signal', async () => {
  const original=globalThis.fetch;const sent=[];let receive;let resolve
  replaceSession(actor('alice'));globalThis.fetch=()=>new Promise(done=>{resolve=done})
  const stop=startAuthSessionSync({send:value=>sent.push(value),listen:callback=>{receive=callback;return()=>{}}})
  try {
    receive({version:1,sender:'other-tab',sequence:1,action:'refresh'})
    receive({version:1,sender:'other-tab',sequence:2,action:'invalidate'})
    resolve(reply(actor('bob')));await new Promise(done=>setImmediate(done))
    assert.equal(sessionState().status,null);assert.equal(sent.length,0)
    stop();receive({version:1,sender:'other-tab',sequence:3,action:'refresh'})
    assert.equal(sessionState().status,null)
  } finally {stop();globalThis.fetch=original}
})

test('browser transport uses exactly one BroadcastChannel and releases it on unsubscribe', async () => {
  const { browserSessionTransport } = await import('../../utils/sessionSync.ts')
  const original=globalThis.window;let channel;const sent=[];const received=[]
  class Channel {
    constructor(name){this.name=name;channel=this}
    postMessage(value){sent.push(value)}
    addEventListener(name,listener){this.listener=listener}
    removeEventListener(name,listener){assert.equal(this.listener,listener);this.listener=null}
    close(){this.closed=true}
  }
  globalThis.window={BroadcastChannel:Channel,localStorage:{setItem:()=>assert.fail('must not duplicate BroadcastChannel via storage')}}
  try {
    const transport=browserSessionTransport();const stop=transport.listen(value=>received.push(value))
    const signal={version:1,sender:'tab-a',sequence:1,action:'invalidate'}
    transport.send(signal);channel.listener({data:signal})
    assert.deepEqual(sent,[signal]);assert.deepEqual(received,[signal]);assert.equal(channel.name,'zxl-auth-session-v1')
    stop();assert.equal(channel.closed,true);assert.equal(channel.listener,null)
  } finally {if(original===undefined)delete globalThis.window;else globalThis.window=original}
})

test('storage fallback accepts only its own localStorage event and never reads persisted notifications at startup', async () => {
  const { browserSessionTransport } = await import('../../utils/sessionSync.ts')
  const original=globalThis.window;let listener;const sent=[];const received=[]
  const storage={setItem:(key,value)=>sent.push([key,JSON.parse(value)]),getItem:()=>assert.fail('stale persisted message must not be replayed')}
  globalThis.window={localStorage:storage,addEventListener:(name,callback)=>{assert.equal(name,'storage');listener=callback},removeEventListener:(name,callback)=>{assert.equal(callback,listener);listener=null}}
  try {
    const transport=browserSessionTransport();const stop=transport.listen(value=>received.push(value))
    const signal={version:1,sender:'tab-a',sequence:1,action:'refresh'}
    transport.send(signal)
    listener({storageArea:storage,key:'unrelated',newValue:JSON.stringify(signal)})
    listener({storageArea:{},key:'zxl-auth-session-v1',newValue:JSON.stringify(signal)})
    listener({storageArea:storage,key:'zxl-auth-session-v1',newValue:'broken'})
    listener({storageArea:storage,key:'zxl-auth-session-v1',newValue:JSON.stringify(signal)})
    assert.deepEqual(received,[signal]);assert.deepEqual(sent,[['zxl-auth-session-v1',signal]])
    stop();assert.equal(listener,null)
  } finally {if(original===undefined)delete globalThis.window;else globalThis.window=original}
})
