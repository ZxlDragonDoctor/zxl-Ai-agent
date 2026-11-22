import {execFileSync} from 'node:child_process';
import {mkdirSync,realpathSync,lstatSync,existsSync,writeFileSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';

/** Export only an immutable Git tree; never reads working files or configuration values. */
export function exportSource({root=process.cwd(),revision='HEAD',output,omitSubmodules=[]}={}) {
 const fail=code=>{throw new Error(code);};
 if(typeof output!=='string'||!/^target\/[A-Za-z0-9][A-Za-z0-9._-]*\.zip$/.test(output))fail('OUTPUT_MUST_BE_NEW_TARGET_ZIP');
 if(typeof revision!=='string'||!revision||revision.startsWith('-')||revision.includes('\0'))fail('REVISION_INVALID');
 const workspace=realpathSync(root);
 const git=(args,encoding='utf8')=>{try{return execFileSync('git',args,{cwd:workspace,encoding,stdio:['ignore','pipe','pipe'],maxBuffer:256*1024*1024});}catch{fail('GIT_EXPORT_FAILED');}};
 if(realpathSync(git(['rev-parse','--show-toplevel']).trim())!==workspace)fail('REPOSITORY_ROOT_REQUIRED');
 const commit=git(['rev-parse','--verify',revision+'^{commit}']).trim();
 if(!/^[a-f0-9]{40,64}$/.test(commit))fail('COMMIT_INVALID');
 const entries=git(['ls-tree','-rz','--full-tree',commit]).split('\0').filter(Boolean);
 if(!Array.isArray(omitSubmodules)||omitSubmodules.some(p=>typeof p!=='string'||!p||p.startsWith('/')||p.split('/').some(s=>!s||s==='.'||s==='..'))||new Set(omitSubmodules).size!==omitSubmodules.length)fail('OMITTED_SUBMODULE_INVALID');
 for(const path of omitSubmodules)if(!entries.some(e=>e.startsWith('160000 ')&&e.slice(e.indexOf('\t')+1)===path))fail('OMITTED_SUBMODULE_NOT_FOUND');
 const omitted=[];
 for(const entry of entries){
  const tab=entry.indexOf('\t'),path=entry.slice(tab+1),mode=entry.slice(0,6),base=path.split('/').at(-1);
  if(mode==='160000'&&omitSubmodules.includes(path)){omitted.push({path,commit:entry.slice(0,tab).split(' ')[2]});continue;}
  if(mode==='120000'||mode==='160000')fail('TRACKED_LINK_UNSUPPORTED');
  const environmentTemplate=/^\.env(?:\.[A-Za-z0-9_-]+)*\.(example|template)$/i.test(base);
  if(!environmentTemplate&&/^(application-local\.(yml|yaml|properties)|\.env(?:\..*)?|id_(rsa|dsa|ecdsa|ed25519)(?:\.pub)?|.*\.(pem|key|p12|pfx|jks))$/i.test(base))fail('TRACKED_SECRET_PATH');
  if(path.split('/').some(segment=>['target','node_modules','runtime-data','dist','.git'].includes(segment)))fail('TRACKED_GENERATED_PATH');
 }
 if(omitted.length!==omitSubmodules.length)fail('OMITTED_SUBMODULE_NOT_FOUND');
 const target=join(workspace,'target');
 if(existsSync(target)&&lstatSync(target).isSymbolicLink())fail('OUTPUT_LINK_REJECTED');
 mkdirSync(target,{recursive:true});
 if(realpathSync(target)!==resolve(workspace,'target'))fail('OUTPUT_ESCAPE_REJECTED');
 const destination=join(workspace,output),manifestPath=destination+'.manifest.json';
 if(existsSync(destination)||existsSync(manifestPath))fail('OUTPUT_ALREADY_EXISTS');
 const paths=entries.map(e=>e.slice(e.indexOf('\t')+1));
 const selected=[];
 const visit=prefix=>{
  if(omitSubmodules.includes(prefix))return;
  if(!omitSubmodules.some(p=>p.startsWith(prefix+'/'))){selected.push(prefix);return;}
  for(const child of new Set(paths.filter(p=>p.startsWith(prefix+'/')).map(p=>p.slice(prefix.length+1).split('/')[0])))visit(prefix+'/'+child);
 };
 for(const first of new Set(paths.map(p=>p.split('/')[0])))visit(first);
 if(!selected.length)fail('EMPTY_EXPORT');
 const bytes=git(['--literal-pathspecs','archive','--format=zip',commit,'--',...selected],null);
 const manifest={commit,sha256:createHash('sha256').update(bytes).digest('hex'),archive:output,trackedEntries:entries.length,omittedSubmodules:omitted,scope:'Git commit tree only; explicit omitted submodules listed; no working-tree files; not a build or deployment verification'};
 writeFileSync(destination,bytes,{flag:'wx'});
 writeFileSync(manifestPath,JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});
 return manifest;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const args=process.argv.slice(2);
 const options={omitSubmodules:[]};let valid=args.length%2===0;
 for(let i=0;i<args.length;i+=2){if(args[i]==='--omit-submodule')options.omitSubmodules.push(args[i+1]);else if(args[i]==='--output'&&!options.output)options.output=args[i+1];else if(args[i]==='--revision'&&!options.revision)options.revision=args[i+1];else valid=false;}
 if(!valid||!options.output){console.error('Usage: node scripts/lesson24/export-source.mjs --output target/new-name.zip [--revision HEAD] [--omit-submodule path]');process.exitCode=2;}
 else try{console.log(JSON.stringify(exportSource(options),null,2));}catch(error){console.error(/^[A-Z_]+$/.test(error.message)?error.message:'EXPORT_FAILED');process.exitCode=1;}
}
