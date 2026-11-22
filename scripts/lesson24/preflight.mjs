import {pathToFileURL} from 'node:url';
/** Checks an explicit environment-only deployment contract. Never reads files or returns values. */
export function checkEnvironment(env,role){
 if(!['main','worker','mcp'].includes(role))throw new Error('ROLE_INVALID');
 const issues=[];const add=(variable,code)=>issues.push({variable,code});
 const required=name=>{if(typeof env[name]!=='string'||!env[name].trim()){add(name,'REQUIRED');return false;}return true;};
 for(const name of ['LESSON12_DB_URL','LESSON12_DB_USER','LESSON12_DB_PASSWORD'])required(name);
 if(env.LESSON12_DB_URL)try{const raw=env.LESSON12_DB_URL;if(!raw.startsWith('jdbc:postgresql://'))throw Error();const u=new URL(raw.slice(5));if(!u.hostname||u.username||u.password||u.hash||u.pathname.length<2)throw Error();}catch{add('LESSON12_DB_URL','POSTGRES_REQUIRED');}
 const tokenName=role==='main'&&env.AGENT_SECURITY_INTERNAL_SERVICE_TOKEN!==undefined?'AGENT_SECURITY_INTERNAL_SERVICE_TOKEN':'LESSON17_INTERNAL_SERVICE_TOKEN';
 if(required(tokenName)&&(!/^[A-Za-z0-9_-]{43,256}$/.test(env[tokenName])||new Set(env[tokenName]).size<12))add(tokenName,'INTERNAL_TOKEN_INVALID');
 const loopback=(name,fallback)=>{try{const raw=env[name]??fallback;const u=new URL(raw);if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||!/^http:\/\/127\.0\.0\.1:[0-9]+\/?$/.test(raw)||Number(u.port)<1||Number(u.port)>65535)throw Error();}catch{add(name,'LOOPBACK_ORIGIN_REQUIRED');}};
 const provider=(prefix)=>{for(const suffix of ['ENDPOINT','API_KEY','MODEL','REVISION'])required(prefix+suffix);if(env[prefix+'ENDPOINT'])try{const u=new URL(env[prefix+'ENDPOINT']);if(u.username||u.password||u.search||!(u.protocol==='https:'||(u.protocol==='http:'&&u.hostname==='127.0.0.1')))throw Error();}catch{add(prefix+'ENDPOINT','PROVIDER_ENDPOINT_INVALID');}for(const [suffix,max] of [['MODEL',45],['REVISION',32]])if(env[prefix+suffix]&&!new RegExp('^[a-zA-Z0-9._-]{1,'+max+'}$').test(env[prefix+suffix]))add(prefix+suffix,'MODEL_IDENTITY_INVALID');};
 if(role==='main'){
  if(env.AGENT_SECURITY_MODE!=='required')add('AGENT_SECURITY_MODE','FORMAL_MODE_REQUIRED');required('SPRING_AI_DASHSCOPE_API_KEY');
  loopback('AGENT_SECURITY_KNOWLEDGE_WORKER_ORIGIN','http://127.0.0.1:18132');loopback('AGENT_SECURITY_MCP_ORIGIN','http://127.0.0.1:18135');
  for(const raw of (env.AGENT_SECURITY_ALLOWED_ORIGINS??'').split(',').map(s=>s.trim()).filter(Boolean))try{const u=new URL(raw);if(raw.length>300||!['http:','https:'].includes(u.protocol)||u.username||u.password||u.search||u.hash||!/^https?:\/\/[^/?#]+$/.test(raw))throw Error();}catch{add('AGENT_SECURITY_ALLOWED_ORIGINS','BROWSER_ORIGIN_INVALID');}
  if((env.SPRING_PROFILES_ACTIVE??'').split(',').map(x=>x.trim()).some(x=>['lesson8-demo','lesson9-demo','lesson17-worker'].includes(x)))add('SPRING_PROFILES_ACTIVE','FORMAL_PROFILE_CONFLICT');
 }
 if(role==='mcp')loopback('LESSON17_KNOWLEDGE_WORKER_ORIGIN','http://127.0.0.1:18132');
 if(role==='worker'){
  if(!['demo','http'].includes(env.LESSON12_EMBEDDING_MODE))add('LESSON12_EMBEDDING_MODE','EXPLICIT_MODE_REQUIRED');
  if(env.LESSON12_EMBEDDING_MODE==='http'){provider('LESSON12_EMBEDDING_');if(required('LESSON12_EMBEDDING_DIMENSIONS')&&(!/^[0-9]+$/.test(env.LESSON12_EMBEDDING_DIMENSIONS)||Number(env.LESSON12_EMBEDDING_DIMENSIONS)<1||Number(env.LESSON12_EMBEDDING_DIMENSIONS)>4096))add('LESSON12_EMBEDDING_DIMENSIONS','DIMENSIONS_INVALID');}
  if(!['rule','http'].includes(env.LESSON13_RERANK_MODE))add('LESSON13_RERANK_MODE','EXPLICIT_MODE_REQUIRED');
  if(env.LESSON13_RERANK_MODE==='http')provider('LESSON13_RERANK_');
 }
 return issues;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const args=process.argv.slice(2);if(args.length!==2||args[0]!=='--role'||!['main','worker','mcp'].includes(args[1])){console.error('Usage: node scripts/lesson24/preflight.mjs --role main|worker|mcp');process.exitCode=2;}else{const issues=checkEnvironment(process.env,args[1]);console.log(JSON.stringify({role:args[1],scope:'environment-only; no network or deployment verification',ok:issues.length===0,issues},null,2));process.exitCode=issues.length?1:0;}
}
