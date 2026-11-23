import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,openSync,closeSync,existsSync,lstatSync,realpathSync,mkdirSync,statSync} from 'node:fs';
import {resolve,join} from 'node:path';import {createHash,randomUUID} from 'node:crypto';import {pathToFileURL} from 'node:url';
const MAX=128*1024*1024,uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\.json$/;
const fail=code=>{throw Error(code)},sha=b=>createHash('sha256').update(b).digest('hex');
const text=b=>b.toString('ascii').replace(/\0.*$/s,'');
function octal(b){const s=b.toString('ascii').replace(/\0/g,' ').trim();if(!/^[0-7]+$/.test(s))fail('TAR_NUMERIC_INVALID');return parseInt(s,8);}
/** Strict bounded USTAR subset for this repository's root UUID.json files. Never extracts. */
export function validateConversationTar(bytes){
 if(!Buffer.isBuffer(bytes)||bytes.length>MAX||bytes.length%512)fail('TAR_SIZE_INVALID');let offset=0,root=false;const files=[],seen=new Set();
 while(offset+512<=bytes.length){const h=bytes.subarray(offset,offset+512);if(h.every(x=>x===0)){if(!root)fail('TAR_ROOT_REQUIRED');if(bytes.length-offset<1024||!bytes.subarray(offset).every(x=>x===0))fail('TAR_TRAILER_INVALID');return files;}
  if(text(h.subarray(257,263))!=='ustar'||h[262]!==0||text(h.subarray(345,500))||text(h.subarray(157,257)))fail('TAR_FORMAT_UNSUPPORTED');
  const expected=octal(h.subarray(148,156));let sum=0;for(let i=0;i<512;i++)sum+=i>=148&&i<156?32:h[i];if(sum!==expected)fail('TAR_CHECKSUM_INVALID');
  const size=octal(h.subarray(124,136)),uid=octal(h.subarray(108,116)),gid=octal(h.subarray(116,124)),mode=octal(h.subarray(100,108)),name=text(h.subarray(0,100)),type=h[156];
  if(uid!==10001||gid!==10001||mode>0o777)fail('TAR_OWNER_MODE_INVALID');
  if(type===53&&name==='./'){if(root||offset!==0||size!==0||(mode&0o300)!==0o300)fail('TAR_DIRECTORY_INVALID');root=true;offset+=512;continue;}
  if(!root)fail('TAR_ROOT_REQUIRED');
  const normalized=name.startsWith('./')?name.slice(2):name;
  if(!uuid.test(normalized)||(type!==48&&type!==0)||seen.has(normalized)||size<1||size>8*1024*1024||files.length>=10000)fail('TAR_ENTRY_INVALID');
  const end=offset+512+size,padded=offset+512+Math.ceil(size/512)*512;if(padded>bytes.length)fail('TAR_TRUNCATED');
  const data=bytes.subarray(offset+512,end);if(!bytes.subarray(end,padded).every(x=>x===0))fail('TAR_PADDING_INVALID');
  let json;try{json=JSON.parse(data.toString('utf8'));}catch{fail('CONVERSATION_JSON_INVALID');}if(json?.id!==normalized.slice(0,-5))fail('CONVERSATION_ID_MISMATCH');
  seen.add(normalized);files.push({name:normalized,size,uid,gid,sha256:sha(data)});offset=padded;
 }fail('TAR_TRAILER_MISSING');
}
const run=(args,io={})=>{try{const out=execFileSync('docker',args,{stdio:[io.stdinFd??'ignore',io.stdoutFd??'pipe','ignore'],encoding:io.stdoutFd===undefined?'utf8':undefined,timeout:600000,maxBuffer:65536});return {code:0,stdout:out??''};}catch{return {code:1};}};
function name(value){if(!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/.test(value??''))fail('VOLUME_NAME_INVALID');return value;}
function image(value){if(!/^[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}$/.test(value??''))fail('PINNED_HELPER_REQUIRED');return value;}
function local(root,file){if(!/^target\/[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}\.tar$/.test(file??''))fail('ARCHIVE_PATH_INVALID');root=realpathSync(root??process.cwd());const dir=join(root,'target');if(existsSync(dir)&&lstatSync(dir).isSymbolicLink())fail('TARGET_LINK_INVALID');mkdirSync(dir,{recursive:true});if(realpathSync(dir)!==resolve(root,'target'))fail('TARGET_LINK_INVALID');return join(root,file);}
async function quiet(volume,execute){const r=await execute(['ps','-q','--filter','volume='+volume]);if(r.code!==0||r.stdout.trim())fail('VOLUME_IN_USE');}
async function inspect(volume,execute){const r=await execute(['volume','inspect',volume]);if(r.code!==0)fail('VOLUME_NOT_FOUND');let v;try{v=JSON.parse(r.stdout)[0];}catch{fail('VOLUME_INSPECT_INVALID');}if(v.Name!==volume||v.Driver!=='local'||Object.keys(v.Options??{}).length)fail('PLAIN_LOCAL_VOLUME_REQUIRED');return v;}
const state=(path,record,flag)=>writeFileSync(path,JSON.stringify(record,null,2)+'\n',flag?{flag}:undefined);
export async function backupVolume(o,{run:execute=run}={}){
 const source=name(o.source),helper=image(o.image),path=local(o.root,o.output),manifest=path+'.manifest.json';if(existsSync(path)||existsSync(manifest))fail('OUTPUT_EXISTS');await inspect(source,execute);await quiet(source,execute);
 const record={status:'running',source,image:helper,scope:'quiesced conversation files only; pair with PG ownership snapshot; not live application-consistent backup'};state(manifest,record,'wx');let fd;
 try{fd=openSync(path,'wx',0o600);const r=await execute(['run','--rm','--network','none','--mount',`type=volume,src=${source},dst=/volume,readonly`,helper,'tar','--format=ustar','--numeric-owner','-cf','-','-C','/volume','.'],{stdoutFd:fd});closeSync(fd);fd=undefined;if(r.code!==0)fail('VOLUME_DUMP_FAILED');if(statSync(path).size>MAX)fail('TAR_SIZE_INVALID');const bytes=readFileSync(path);record.files=validateConversationTar(bytes);record.sha256=sha(bytes);record.status='complete';state(manifest,record);return record;}catch(e){if(fd!==undefined)closeSync(fd);record.status='failed';record.code=/^[A-Z_]+$/.test(e.message)?e.message:'VOLUME_DUMP_FAILED';state(manifest,record);throw Error(record.code);}
}
export async function restoreVolume(o,{run:execute=run}={}){
 const target=name(o.target),path=local(o.root,o.archive);if(lstatSync(path).isSymbolicLink())fail('ARCHIVE_LINK_INVALID');const m=JSON.parse(readFileSync(path+'.manifest.json','utf8'));if(m.status!=='complete')fail('ARCHIVE_NOT_COMPLETE');name(m.source);image(m.image);if(target===m.source)fail('SOURCE_TARGET_SAME');if(statSync(path).size>MAX)fail('TAR_SIZE_INVALID');const bytes=readFileSync(path);if(sha(bytes)!==m.sha256)fail('DIGEST_MISMATCH');const files=validateConversationTar(bytes);if(JSON.stringify(files)!==JSON.stringify(m.files))fail('FILE_MANIFEST_MISMATCH');await quiet(target,execute);
 const list=await execute(['volume','ls','--format','{{.Name}}']);if(list.code!==0)fail('VOLUME_LIST_FAILED');if(list.stdout.split(/\r?\n/).includes(target))fail('TARGET_VOLUME_EXISTS');
 const report=path+'.restore-'+target+'.json',record={status:'running',source:m.source,target,sha256:m.sha256,files,scope:'new local volume only; validated trusted USTAR; preserve uid/gid10001; pair with restored PG ownership'};state(report,record,'wx');let fd;
 try{const token=randomUUID();const created=await execute(['volume','create','--driver','local','--label','zxl.agent.restore-token='+token,target]);if(created.code!==0)fail('VOLUME_CREATE_FAILED');const targetInfo=await inspect(target,execute);if(targetInfo.Labels?.['zxl.agent.restore-token']!==token)fail('TARGET_CREATION_COLLISION');await quiet(target,execute);
  const empty=await execute(['run','--rm','--network','none','--mount',`type=volume,src=${target},dst=/volume`,m.image,'find','/volume','-mindepth','1','-maxdepth','1','-print']);if(empty.code!==0||empty.stdout.trim())fail('TARGET_NOT_EMPTY');
  fd=openSync(path,'r');const r=await execute(['run','--rm','-i','--network','none','--mount',`type=volume,src=${target},dst=/volume`,m.image,'tar','--numeric-owner','--same-owner','-xpf','-','-C','/volume'],{stdinFd:fd});closeSync(fd);fd=undefined;if(r.code!==0)fail('VOLUME_RESTORE_FAILED');record.status='complete';state(report,record);return record;
 }catch(e){if(fd!==undefined)closeSync(fd);record.status='failed';record.code=/^[A-Z_]+$/.test(e.message)?e.message:'VOLUME_RESTORE_FAILED';state(report,record);throw Error(record.code);}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){const[action,...args]=process.argv.slice(2),o={};let valid=args.length%2===0;const allowed=action==='backup'?['source','image','output']:['archive','target'];for(let i=0;i<args.length;i+=2){const key=args[i].replace(/^--/,'');if(!args[i].startsWith('--')||!allowed.includes(key)||o[key])valid=false;o[key]=args[i+1];}if(!valid||!['backup','restore'].includes(action)){console.error('VOLUME_OPTIONS_INVALID');process.exitCode=2;}else(action==='backup'?backupVolume(o):restoreVolume(o)).then(r=>console.log(JSON.stringify(r))).catch(e=>{console.error(/^[A-Z_]+$/.test(e.message)?e.message:'VOLUME_OPERATION_FAILED');process.exitCode=1;});}
