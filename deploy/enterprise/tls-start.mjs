import {spawn} from 'node:child_process';import {readFileSync,lstatSync,realpathSync,accessSync,constants} from 'node:fs';import {X509Certificate,createPrivateKey,createPublicKey} from 'node:crypto';import {pathToFileURL} from 'node:url';import {join} from 'node:path';

function file(path){try{if(typeof path!=='string'||!path||!lstatSync(path).isFile()||lstatSync(path).isSymbolicLink())throw Error();accessSync(path,constants.R_OK);return realpathSync(path);}catch{throw Error('TLS_FILE_REQUIRED');}}
export function validateCertificatePair(certBytes,keyBytes,caBytes){
 let cert;try{cert=new X509Certificate(certBytes);}catch{throw Error('TLS_CERTIFICATE_INVALID');}
 const now=Date.now(),from=Date.parse(cert.validFrom),until=Date.parse(cert.validTo);
 if(!Number.isFinite(from)||!Number.isFinite(until)||now<from||now>until)throw Error('TLS_CERTIFICATE_NOT_CURRENT');
 if(!cert.checkIP('127.0.0.1')||!cert.checkHost('localhost',{subject:'never',wildcards:false}))throw Error('TLS_SAN_REQUIRED');
 let key;try{key=createPrivateKey(keyBytes);}catch{throw Error('TLS_PRIVATE_KEY_INVALID');}
 const expected=cert.publicKey.export({type:'spki',format:'der'}),actual=createPublicKey(key).export({type:'spki',format:'der'});
 if(!expected.equals(actual))throw Error('TLS_KEY_MISMATCH');
 if(caBytes){let ca;try{ca=new X509Certificate(caBytes);}catch{throw Error('TLS_CA_INVALID');}
  if(!ca.ca||now<Date.parse(ca.validFrom)||now>Date.parse(ca.validTo)||!cert.checkIssued(ca)||!cert.verify(ca.publicKey))throw Error('TLS_ISSUER_NOT_VERIFIED');}
}
const run=(args,env)=>new Promise(resolve=>{let child;try{child=spawn('docker',args,{env,stdio:'ignore',shell:false});}catch{resolve(1);return;}const timer=setTimeout(()=>child.kill(),120000);child.once('error',()=>{clearTimeout(timer);resolve(1);});child.once('close',code=>{clearTimeout(timer);resolve(code===0?0:1);});});
export async function startTls(options,{validatePair=validateCertificatePair,runner=run,environment=process.env}={}){
 const {project,envFile,composeFiles,tlsDir,caFile}=options??{};
 if(!/^[a-z0-9][a-z0-9_-]{0,62}$/.test(project??'')||typeof envFile!=='string'||!envFile||!Array.isArray(composeFiles)||!composeFiles.length||composeFiles.some(p=>typeof p!=='string'||!p))throw Error('EXPLICIT_PROJECT_FILES_REQUIRED');
 let envPath,composes;try{envPath=file(envFile);composes=composeFiles.map(file);}catch{throw Error('EXPLICIT_PROJECT_FILES_REQUIRED');}
 let directory;try{if(typeof tlsDir!=='string'||!tlsDir||!lstatSync(tlsDir).isDirectory()||lstatSync(tlsDir).isSymbolicLink())throw Error();directory=realpathSync(tlsDir);}catch{throw Error('TLS_DIRECTORY_REQUIRED');}
 const certPath=file(join(directory,'cert.pem')),keyPath=file(join(directory,'key.pem')),caPath=caFile===undefined?undefined:file(caFile);
 try{validatePair(readFileSync(certPath),readFileSync(keyPath),caPath?readFileSync(caPath):undefined);}catch(error){const allowed=new Set(['TLS_CERTIFICATE_INVALID','TLS_CERTIFICATE_NOT_CURRENT','TLS_SAN_REQUIRED','TLS_PRIVATE_KEY_INVALID','TLS_KEY_MISMATCH','TLS_CA_INVALID','TLS_ISSUER_NOT_VERIFIED']);throw Error(allowed.has(error.message)?error.message:'TLS_CERTIFICATE_CHECK_FAILED');}
 const args=['compose','--project-name',project,'--env-file',envPath,...composes.flatMap(p=>['-f',p]),'up','-d','--no-build'];
 let code;try{code=await runner(args,{...environment,TLS_CERT_DIR:directory});}catch{throw Error('TLS_COMPOSE_START_FAILED');}if(code!==0)throw Error('TLS_COMPOSE_START_FAILED');
 return {status:'started',readinessVerified:false,scope:'validated local certificate files and requested Compose start; actual TLS, identity and streaming readiness still require verification'};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){const args=process.argv.slice(2),options={composeFiles:[]};let valid=args.length%2===0;const names={'--project':'project','--env-file':'envFile','--tls-dir':'tlsDir','--ca-file':'caFile'};for(let i=0;i<args.length;i+=2){if(args[i]==='--compose')options.composeFiles.push(args[i+1]);else if(names[args[i]]&&!options[names[args[i]]])options[names[args[i]]]=args[i+1];else valid=false;}if(!valid){console.error('EXPLICIT_PROJECT_FILES_REQUIRED');process.exitCode=1;}else startTls(options).then(r=>console.log(JSON.stringify(r))).catch(e=>{console.error(/^[A-Z_]+$/.test(e.message)?e.message:'TLS_START_FAILED');process.exitCode=1;});}
