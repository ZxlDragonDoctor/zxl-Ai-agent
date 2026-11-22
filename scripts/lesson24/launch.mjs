import {statSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {spawn} from 'node:child_process';
import {checkEnvironment} from './preflight.mjs';

const entries={main:'com.zxl.enterprise.EnterpriseAgentApplication',worker:'com.zxl.zxlaiagent.knowledge.scoped.FormalKnowledgeWorker',mcp:'com.zxl.zxlaiagent.knowledge.mcp.FormalKnowledgeMcpWorker'};
/** Builds arguments only. No secret values are inserted into command arguments or returned metadata. */
export function buildLaunch(role,jar,env){
 if(!Object.hasOwn(entries,role))throw Error('ROLE_INVALID');
 if(typeof jar!=='string'||!jar.trim())throw Error('JAR_REQUIRED');
 const path=resolve(jar);
 try{if(!path.endsWith('.jar')||!statSync(path).isFile())throw Error();}catch{throw Error('JAR_NOT_FOUND');}
 if(!env||typeof env!=='object')throw Error('ENVIRONMENT_REQUIRED');
 const issues=checkEnvironment(env,role);
 if(issues.length){const error=Error('DEPLOYMENT_PREFLIGHT_FAILED');error.issues=issues;throw error;}
 return {executable:'java',args:['-Dloader.main='+entries[role],'-cp',path,'org.springframework.boot.loader.launch.PropertiesLauncher',...(role==='main'?['--spring.profiles.active=enterprise']:[])]};
}
/** Inherits only the supplied environment; no .env, local YAML, credential file, or shell expansion. */
export function launch(role,jar,env,{spawnProcess=spawn}={}){
 return new Promise((resolveExit,reject)=>{
  let command;try{command=buildLaunch(role,jar,env);}catch(error){reject(error);return;}
  let child;try{child=spawnProcess(command.executable,command.args,{env,stdio:'inherit',shell:false});}catch{reject(Error('JAVA_START_FAILED'));return;}
  child.once('error',()=>reject(Error('JAVA_START_FAILED')));
  child.once('exit',(code)=>resolveExit(Number.isInteger(code)?code:1));
 });
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const args=process.argv.slice(2);
 if(args.length!==4||args[0]!=='--role'||args[2]!=='--jar'){
  console.error('Usage: node scripts/lesson24/launch.mjs --role main|worker|mcp --jar <fatjar>');process.exitCode=2;
 }else{
  launch(args[1],args[3],process.env).then(code=>{process.exitCode=code;}).catch(error=>{
   const codes=new Set(['ROLE_INVALID','JAR_REQUIRED','JAR_NOT_FOUND','ENVIRONMENT_REQUIRED','DEPLOYMENT_PREFLIGHT_FAILED','JAVA_START_FAILED']);
   console.error(JSON.stringify({code:codes.has(error.message)?error.message:'LAUNCH_FAILED',...(error.issues?{issues:error.issues}:{})}));process.exitCode=1;
  });
 }
}
