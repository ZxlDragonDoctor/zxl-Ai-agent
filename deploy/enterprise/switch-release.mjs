import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,existsSync,lstatSync,realpathSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';

const fail=code=>{throw Error(code);};
const services=['main','worker','mcp','frontend'];
const imageId=/^sha256:[a-f0-9]{64}$/;
const mainProbe='exec 3<>/dev/tcp/127.0.0.1/18301; printf "GET /api/auth/status HTTP/1.1\\r\\nHost: 127.0.0.1:18301\\r\\nConnection: close\\r\\n\\r\\n" >&3; IFS= read -r -t 2 status <&3; [[ "$status" == "HTTP/1.1 200 "* ]]';
const runDefault=async args=>{
  try{return {code:0,stdout:execFileSync('docker',args,{encoding:'utf8',stdio:['ignore','pipe','ignore'],timeout:120000,maxBuffer:5*1024*1024})};}
  catch{return {code:1};}
};
const probeDefault=async origin=>{
  try{const r=await fetch(origin+'/api/auth/status',{redirect:'error',signal:AbortSignal.timeout(3000)});await r.body?.cancel();return r.status===200;}
  catch{return false;}
};
function manifest(path){
  let value;try{value=JSON.parse(readFileSync(path,'utf8'));}catch{fail('RELEASE_MANIFEST_INVALID');}
  if(!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,80}$/.test(value?.releaseId??'')||!imageId.test(value.javaImage??'')||!imageId.test(value.frontendImage??'')
    ||value.conversationFormat!=='uuid-json-v1'||JSON.stringify(value.schemaModules)!==JSON.stringify(['security','knowledge'])
    ||Object.keys(value).sort().join(',')!=='conversationFormat,frontendImage,javaImage,releaseId,schemaModules')fail('RELEASE_MANIFEST_INVALID');
  return value;
}
/** Explicit release transition for one stopped-writer maintenance window. Never modifies PG or volumes. */
export async function switchRelease(o,{run=runDefault,probe=probeDefault,sleep=ms=>new Promise(r=>setTimeout(r,ms))}={}){
  if(!/^[a-z0-9][a-z0-9_-]{0,62}$/.test(o?.project??'')||!o.envFile||!existsSync(o.envFile)||!o.composeFiles?.length
    ||o.composeFiles.some(p=>!existsSync(p)))fail('EXPLICIT_PROJECT_FILES_REQUIRED');
  if(!/^http:\/\/127\.0\.0\.1:[0-9]+$/.test(o.origin??'')||Number(new URL(o.origin).port)<1||Number(new URL(o.origin).port)>65535)fail('LOOPBACK_ORIGIN_REQUIRED');
  const attempts=o.attempts??30,delayMs=o.delayMs??1000;
  if(!Number.isInteger(attempts)||attempts<1||attempts>120||!Number.isInteger(delayMs)||delayMs<0||delayMs>5000)fail('BOUNDED_CHECK_REQUIRED');
  const current=manifest(o.current),candidate=manifest(o.candidate);
  if(current.releaseId===candidate.releaseId||(current.javaImage===candidate.javaImage&&current.frontendImage===candidate.frontendImage))fail('RELEASE_CHANGE_REQUIRED');
  const root=realpathSync(o.root??process.cwd()),dir=join(root,'target');
  if(!/^target\/[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}\.json$/.test(o.output??'')||!existsSync(dir)||lstatSync(dir).isSymbolicLink()||realpathSync(dir)!==resolve(root,'target'))fail('OUTPUT_PATH_INVALID');
  const output=join(root,o.output),override=output+'.compose.json';
  if(existsSync(output)||existsSync(override))fail('OUTPUT_EXISTS');
  const base=['compose','--project-name',o.project,'--env-file',resolve(o.envFile),...o.composeFiles.flatMap(p=>['-f',resolve(p)])];
  const record={status:'checking',phase:'preflight',project:o.project,current,candidate,override,scope:'local immutable image transition; no migration/data rollback; readiness only, authenticated business acceptance remains required'};
  const save=()=>writeFileSync(output,JSON.stringify(record,null,2)+'\n');
  writeFileSync(output,JSON.stringify(record,null,2)+'\n',{flag:'wx',mode:0o600});
  const command=async(args,code)=>{const r=await run(args);if(r.code!==0)fail(code);return (r.stdout??'').trim();};
  const inspectRunning=async release=>{
    for(const service of services){
      const id=await command([...base,'ps','-q',service],'SERVICE_INSPECTION_FAILED');
      if(!id||id.includes('\n'))fail('SINGLE_RUNNING_SERVICE_REQUIRED');
      if(await command(['inspect','--format','{{.State.Running}}',id],'SERVICE_INSPECTION_FAILED')!=='true')fail('SERVICE_NOT_RUNNING');
      const actual=await command(['inspect','--format','{{.Image}}',id],'SERVICE_INSPECTION_FAILED');
      if(actual!==(service==='frontend'?release.frontendImage:release.javaImage))fail('CURRENT_RELEASE_DRIFT');
      if(service==='main'){
        let ports;try{ports=JSON.parse(await command(['inspect','--format','{{json .NetworkSettings.Ports}}',id],'SERVICE_INSPECTION_FAILED'));}catch{fail('PUBLISHED_ORIGIN_MISMATCH');}
        if(!ports?.['80/tcp']?.some(p=>p.HostIp==='127.0.0.1'&&String(p.HostPort)===new URL(o.origin).port))fail('PUBLISHED_ORIGIN_MISMATCH');
      }
    }
  };
  const wait=async(check,code)=>{for(let i=0;i<attempts;i++){if(await check())return;if(i+1<attempts)await sleep(delayMs);}fail(code);};
  try{
    for(const id of new Set([candidate.javaImage,candidate.frontendImage])){
      if(await command(['image','inspect','--format','{{.Id}}',id],'CANDIDATE_IMAGE_UNAVAILABLE')!==id)fail('CANDIDATE_IMAGE_MISMATCH');
    }
    await inspectRunning(current);
    let config;try{config=JSON.parse(await command([...base,'config','--format','json'],'COMPOSE_VALIDATION_FAILED'));}catch{fail('COMPOSE_VALIDATION_FAILED');}
    if(['main','worker','mcp'].some(s=>config.services?.[s]?.environment?.AGENT_DB_SCHEMA_MODE!=='validate'))fail('READ_ONLY_SCHEMA_MODE_REQUIRED');
    if(!config.services.main.ports?.some(p=>p.host_ip==='127.0.0.1'&&Number(p.target)===80&&String(p.published)===new URL(o.origin).port))fail('PUBLISHED_ORIGIN_MISMATCH');
    writeFileSync(override,JSON.stringify({services:{main:{image:candidate.javaImage},worker:{image:candidate.javaImage},mcp:{image:candidate.javaImage},frontend:{image:candidate.frontendImage}}},null,2),{flag:'wx',mode:0o600});
    const next=[...base,'-f',override];
    await command([...next,'run','--rm','--no-deps','-T','--entrypoint','java','main','-Dloader.main=com.zxl.zxlaiagent.persistence.SchemaVerifier','-cp','/opt/agent/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher',...candidate.schemaModules],'SCHEMA_CHECK_FAILED');
    record.status='switching';record.phase='stopping-applications';record.schemaCheck='passed';save();
    await command([...base,'stop','frontend','mcp','worker','main'],'APPLICATION_STOP_FAILED');
    record.phase='starting-main';save();
    await command([...next,'up','-d','--no-build','--no-deps','--force-recreate','main'],'MAIN_START_FAILED');
    await wait(async()=> (await run([...next,'exec','-T','main','bash','-c',mainProbe])).code===0,'MAIN_READINESS_TIMEOUT');
    record.phase='starting-sidecars';save();
    await command([...next,'up','-d','--no-build','--no-deps','--force-recreate','worker','mcp','frontend'],'SIDECAR_START_FAILED');
    await wait(()=>probe(o.origin),'FRONTEND_READINESS_TIMEOUT');
    await inspectRunning(candidate);
    record.status='ready';record.phase='verified-runtime';save();return record;
  }catch(e){record.status='failed';record.code=/^[A-Z_]+$/.test(e.message)?e.message:'RELEASE_SWITCH_FAILED';save();throw Error(record.code);}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const args=process.argv.slice(2),o={composeFiles:[]};let valid=args.length%2===0;
  const keys={'--project':'project','--env-file':'envFile','--origin':'origin','--current':'current','--candidate':'candidate','--output':'output'};
  for(let i=0;i<args.length;i+=2){if(args[i]==='--compose')o.composeFiles.push(args[i+1]);else if(keys[args[i]]&&!o[keys[args[i]]])o[keys[args[i]]]=args[i+1];else valid=false;}
  if(!valid){console.error('RELEASE_OPTIONS_INVALID');process.exitCode=1;}
  else switchRelease(o).then(r=>console.log(JSON.stringify(r))).catch(e=>{console.error(/^[A-Z_]+$/.test(e.message)?e.message:'RELEASE_SWITCH_FAILED');process.exitCode=1;});
}
