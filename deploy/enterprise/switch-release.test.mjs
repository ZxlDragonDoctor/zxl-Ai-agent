import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {switchRelease} from './switch-release.mjs';

const image = c => 'sha256:' + c.repeat(64);
const current = {releaseId:'release-a', javaImage:image('a'), frontendImage:image('b'), conversationFormat:'uuid-json-v1', schemaModules:['security','knowledge']};
const candidate = {...current, releaseId:'release-b', javaImage:image('c'), frontendImage:image('d')};
function fixture(t, fail = '') {
  const root = mkdtempSync(join(tmpdir(),'agent-release-'));
  t.after(() => rmSync(root,{recursive:true,force:true}));
  mkdirSync(join(root,'target')); writeFileSync(join(root,'private.env'),'not-a-real-secret');
  writeFileSync(join(root,'compose.json'),'{}');
  writeFileSync(join(root,'a.json'),JSON.stringify(current)); writeFileSync(join(root,'b.json'),JSON.stringify(candidate));
  const calls = []; let switched = false;
  const options = {root, project:'exercise', envFile:join(root,'private.env'), composeFiles:[join(root,'compose.json')],
    origin:'http://127.0.0.1:5218', current:join(root,'a.json'), candidate:join(root,'b.json'), output:'target/switch.json', attempts:2, delayMs:0};
  const run = async args => {
    calls.push(args);
    if(args[0]==='image') return {code:0,stdout:args.at(-1)};
    if(args[0]==='inspect') {
      const service=args.at(-1).replace('container-','');
      const release=switched?candidate:current;
      if(args.includes('{{json .NetworkSettings.Ports}}'))return {code:0,stdout:JSON.stringify({'80/tcp':[{HostIp:'127.0.0.1',HostPort:fail==='port-drift'?'5216':'5218'}]})};
      if(args.includes('{{.State.Running}}'))return {code:0,stdout:'true'};
      return {code:0,stdout:fail==='current-drift'?image('e'):(service==='frontend'?release.frontendImage:release.javaImage)};
    }
    if(args.includes('ps'))return {code:0,stdout:'container-'+args.at(-1)};
    if(args.includes('config'))return {code:0,stdout:JSON.stringify({services:Object.fromEntries(['main','worker','mcp'].map(s=>[s,{environment:{AGENT_DB_SCHEMA_MODE:fail==='migrate-mode'?'migrate':'validate'},...(s==='main'?{ports:[{host_ip:'127.0.0.1',published:'5218',target:80}]}:{})}]))})};
    if(args.includes('run'))return {code:fail==='schema'?1:0};
    if(args.includes('stop'))return {code:fail==='stop'?1:0};
    if(args.includes('exec'))return {code:fail==='readiness'?1:0};
    if(args.includes('up')&&args.at(-1)==='main')switched=true;
    return {code:0};
  };
  return {root,options,calls,deps:{run,probe:async()=>fail!=='host',sleep:async()=>{}}};
}
test('candidate schema is checked before stopping, recreating main then sidecars without PG changes',async t=>{
  const f=fixture(t);const result=await switchRelease(f.options,f.deps);
  assert.equal(result.status,'ready');
  const check=f.calls.findIndex(a=>a.includes('run')), stop=f.calls.findIndex(a=>a.includes('stop'));
  assert.ok(check>=0&&check<stop);assert.ok(f.calls[check].includes('security'));
  assert.ok(f.calls[check].includes('knowledge'));assert.ok(f.calls[check].includes('--entrypoint'));
  const ups=f.calls.filter(a=>a.includes('up'));
  assert.equal(ups.length,2);assert.equal(ups[0].at(-1),'main');
  assert.deepEqual(ups[1].slice(-3),['worker','mcp','frontend']);
  assert.ok(ups.every(a=>a.includes('--force-recreate')&&a.includes('--no-deps')));
  assert.ok(!f.calls.some(a=>a.includes('down')||a.includes('pg')||a.includes('migrate')));
  const report=readFileSync(join(f.root,'target/switch.json'),'utf8');
  assert.ok(!report.includes('not-a-real-secret'));assert.equal(JSON.parse(report).status,'ready');
});
for(const [failure,code] of [['schema','SCHEMA_CHECK_FAILED'],['current-drift','CURRENT_RELEASE_DRIFT'],['migrate-mode','READ_ONLY_SCHEMA_MODE_REQUIRED']]) {
  test(failure+' refuses before stopping live services',async t=>{
    const f=fixture(t,failure);await assert.rejects(switchRelease(f.options,f.deps),new RegExp(code));
    assert.ok(!f.calls.some(a=>a.includes('stop')||a.includes('up')));
  });
}
test('readiness timeout leaves failed evidence without automatic fallback or DB mutation',async t=>{
  const f=fixture(t,'readiness');await assert.rejects(switchRelease(f.options,f.deps),/MAIN_READINESS_TIMEOUT/);
  assert.equal(f.calls.filter(a=>a.includes('exec')).length,2);
  assert.equal(f.calls.filter(a=>a.includes('up')).length,1);
  assert.equal(JSON.parse(readFileSync(join(f.root,'target/switch.json'))).status,'failed');
});
test('bad manifests, unpinned images, format mismatch and no-op fail before Docker',async t=>{
  for(const change of [{javaImage:'local:latest'},{conversationFormat:'other'},{schemaModules:['security']},{...current},{releaseId:'../bad'}]) {
    const f=fixture(t);writeFileSync(join(f.root,'b.json'),JSON.stringify({...candidate,...change}));
    await assert.rejects(switchRelease(f.options,f.deps));assert.equal(f.calls.length,0);
  }
});
test('existing output and outside output cannot overwrite evidence',async t=>{
  const f=fixture(t);writeFileSync(join(f.root,'target/switch.json'),'keep');
  await assert.rejects(switchRelease(f.options,f.deps),/OUTPUT_EXISTS/);assert.equal(f.calls.length,0);
  await assert.rejects(switchRelease({...f.options,output:'../switch.json'},f.deps));
});
test('host timeout records failure and cannot claim business acceptance',async t=>{
  const f=fixture(t,'host');await assert.rejects(switchRelease(f.options,f.deps),/FRONTEND_READINESS_TIMEOUT/);
  const report=JSON.parse(readFileSync(join(f.root,'target/switch.json')));assert.equal(report.status,'failed');
  assert.equal(report.code,'FRONTEND_READINESS_TIMEOUT');
});
test('origin must belong to both configured and actual selected project port',async t=>{
  const f=fixture(t);await assert.rejects(switchRelease({...f.options,origin:'http://127.0.0.1:5216'},f.deps),/PUBLISHED_ORIGIN_MISMATCH/);
  assert.ok(!f.calls.some(a=>a.includes('stop')));
  const g=fixture(t,'port-drift');await assert.rejects(switchRelease(g.options,g.deps),/PUBLISHED_ORIGIN_MISMATCH/);
  assert.ok(!g.calls.some(a=>a.includes('stop')));
});
