import {execFileSync} from 'node:child_process';import {existsSync} from 'node:fs';import {resolve} from 'node:path';import {pathToFileURL} from 'node:url';
// HTTP, not merely a TCP connection; the tested Temurin baseline includes bash.
const mainProbe='exec 3<>/dev/tcp/127.0.0.1/18301; printf "GET /api/auth/status HTTP/1.1\\r\\nHost: 127.0.0.1:18301\\r\\nConnection: close\\r\\n\\r\\n" >&3; IFS= read -r -t 2 status <&3; [[ "$status" == "HTTP/1.1 200 "* ]]';
const runner=async args=>{try{execFileSync('docker',args,{stdio:['ignore','ignore','ignore'],timeout:15000});return 0;}catch{return 1;}};
const hostProbe=async origin=>{try{const r=await fetch(origin+'/api/auth/status',{redirect:'error',signal:AbortSignal.timeout(3000)});const ok=r.status===200;await r.body?.cancel();return ok;}catch{return false;}};
/** Only the explicitly selected Compose project is mutated. No down, volume deletion or database restart. */
export async function restartApps(options,{runner:run=runner,hostProbe:probe=hostProbe,sleep=ms=>new Promise(r=>setTimeout(r,ms)),exists=existsSync}={}){
 const {project,envFile,composeFiles,origin,attempts=30,delayMs=1000}=options??{};
 if(!/^[a-z0-9][a-z0-9_-]{0,62}$/.test(project??'')||typeof envFile!=='string'||!envFile||!exists(envFile)||!Array.isArray(composeFiles)||!composeFiles.length||composeFiles.some(x=>typeof x!=='string'||!x||!exists(x)))throw Error('EXPLICIT_PROJECT_FILES_REQUIRED');
 if(!/^http:\/\/127\.0\.0\.1:[0-9]+$/.test(origin??'')||!Number.isInteger(attempts)||attempts<1||attempts>120||!Number.isInteger(delayMs)||delayMs<0||delayMs>5000)throw Error('BOUNDED_LOOPBACK_CHECK_REQUIRED');
 const url=new URL(origin);if(Number(url.port)<1||Number(url.port)>65535)throw Error('BOUNDED_LOOPBACK_CHECK_REQUIRED');
 const base=['compose','--project-name',project,'--env-file',resolve(envFile),...composeFiles.flatMap(p=>['-f',resolve(p)])];
 const command=async(tail,code)=>{if(await run([...base,...tail])!==0)throw Error(code);};
 const wait=async(check,code)=>{for(let i=0;i<attempts;i++){if(await check())return;if(i+1<attempts)await sleep(delayMs);}throw Error(code);};
 await command(['stop','frontend','mcp','worker'],'SIDECAR_STOP_FAILED');
 await command(['restart','main'],'MAIN_RESTART_FAILED');
 await wait(async()=>await run([...base,'exec','-T','main','bash','-c',mainProbe])===0,'MAIN_READINESS_TIMEOUT');
 await command(['up','-d','--no-build','--no-deps','worker','mcp','frontend'],'SIDECAR_START_FAILED');
 await wait(()=>probe(origin),'FRONTEND_READINESS_TIMEOUT');
 return {ok:true,scope:'ordered single-host group restart; main and proxy HTTP only; tool discovery and business checks remain required'};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const args=process.argv.slice(2),options={composeFiles:[]};let valid=args.length%2===0;
 for(let i=0;i<args.length;i+=2){if(args[i]==='--compose')options.composeFiles.push(args[i+1]);else if(args[i]==='--project'&&!options.project)options.project=args[i+1];else if(args[i]==='--env-file'&&!options.envFile)options.envFile=args[i+1];else if(args[i]==='--origin'&&!options.origin)options.origin=args[i+1];else valid=false;}
 if(!valid){console.error('EXPLICIT_PROJECT_FILES_REQUIRED');process.exitCode=1;}else restartApps(options).then(r=>console.log(JSON.stringify(r))).catch(e=>{console.error(/^[A-Z_]+$/.test(e.message)?e.message:'ORDERED_RESTART_FAILED');process.exitCode=1;});
}
