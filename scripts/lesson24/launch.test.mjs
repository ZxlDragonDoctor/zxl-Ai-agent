import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {EventEmitter} from 'node:events';
import {buildLaunch,launch} from './launch.mjs';
const token='abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-';
const env={LESSON12_DB_URL:'jdbc:postgresql://127.0.0.1:15493/postgres',LESSON12_DB_USER:'fixture',LESSON12_DB_PASSWORD:'private-db-secret',LESSON17_INTERNAL_SERVICE_TOKEN:token,AGENT_SECURITY_MODE:'required',SPRING_AI_DASHSCOPE_API_KEY:'private-model-secret',LESSON12_EMBEDDING_MODE:'http',LESSON12_EMBEDDING_API_KEY:'private-embedding-secret',LESSON12_EMBEDDING_ENDPOINT:'https://provider.example/embeddings',LESSON12_EMBEDDING_MODEL:'embed-v1',LESSON12_EMBEDDING_REVISION:'fixture-v1',LESSON12_EMBEDDING_DIMENSIONS:'1024',LESSON13_RERANK_MODE:'rule'};
const directory=mkdtempSync(join(tmpdir(),'lesson24-launch-'));
const jar=join(directory,'app with spaces.jar');writeFileSync(jar,'fixture: command verification only');
test.after(()=>rmSync(directory,{recursive:true,force:true}));
test('each role selects explicit fatjar loader entry and only main enterprise profile',()=>{
 for(const [role,entry] of Object.entries({main:'com.zxl.enterprise.EnterpriseAgentApplication',worker:'com.zxl.zxlaiagent.knowledge.scoped.FormalKnowledgeWorker',mcp:'com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpWorker'})){
  const command=buildLaunch(role,jar,env);assert.equal(command.executable,'java');assert.deepEqual(command.args,['-Dloader.main='+entry,'-cp',jar,'org.springframework.boot.loader.launch.PropertiesLauncher',...(role==='main'?['--spring.profiles.active=enterprise']:[])]);
  assert.ok(!JSON.stringify(command).includes('private-model-secret'));
 }
});
test('invalid role, missing jar or failed preflight never spawn',async()=>{
 let spawned=0;const spawnProcess=()=>{spawned++;throw Error('unexpected');};
 for(const args of [['other',jar,env],['main',join(directory,'missing.jar'),env],['main',jar,{}]])await assert.rejects(launch(...args,{spawnProcess}));
 assert.equal(spawned,0);
});
test('spawn receives unchanged environment, safe argument array and child exit status',async()=>{
 const child=new EventEmitter();let options;
 const result=launch('worker',jar,env,{spawnProcess:(executable,args,opts)=>{assert.equal(executable,'java');assert.equal(args[2],jar);options=opts;queueMicrotask(()=>child.emit('exit',7,null));return child;}});
 assert.equal(await result,7);assert.equal(options.env,env);assert.equal(options.shell,false);assert.equal(options.stdio,'inherit');
});
test('spawn failure exposes fixed error code without raw environment or OS error',async()=>{
 const child=new EventEmitter();const result=launch('main',jar,env,{spawnProcess:()=>{queueMicrotask(()=>child.emit('error',Error('private-model-secret')));return child;}});
 await assert.rejects(result,error=>error.message==='JAVA_START_FAILED'&&!JSON.stringify(error).includes('private-model-secret'));
});
