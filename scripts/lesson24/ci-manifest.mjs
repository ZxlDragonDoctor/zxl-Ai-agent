import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,existsSync,lstatSync,realpathSync,mkdirSync,readdirSync,createReadStream} from 'node:fs';
import {resolve,join,relative,sep} from 'node:path';import {createHash} from 'node:crypto';import {pathToFileURL} from 'node:url';
const fail=code=>{throw Error(code);};
function regular(path,directory=false){let s;try{s=lstatSync(path);}catch{fail('ARTIFACT_REQUIRED');}if(s.isSymbolicLink())fail('ARTIFACT_LINK_INVALID');if(directory?!s.isDirectory():!s.isFile())fail('ARTIFACT_TYPE_INVALID');return s;}
async function describe(root,path){const size=regular(path).size,h=createHash('sha256');for await(const bytes of createReadStream(path))h.update(bytes);return {path:relative(root,path).split(sep).join('/'),bytes:size,sha256:h.digest('hex')};}
function counts(xml){
 const root=xml.match(/^\s*(?:<\?xml[^?]*\?>\s*)?<testsuite\b([^>]*)>/);if(!root||!/<\/testsuite>\s*$/.test(xml))fail('REPORT_XML_INVALID');
 const result={};for(const key of ['tests','failures','errors','skipped']){const matches=[...root[1].matchAll(new RegExp('(?:^|\\s)'+key+'\\s*=\\s*(["\'])([0-9]+)\\1','g'))];if(matches.length!==1)fail('REPORT_COUNTER_INVALID');const n=Number(matches[0][2]);if(!Number.isSafeInteger(n))fail('REPORT_COUNTER_INVALID');result[key]=n;}
 if(result.failures+result.errors+result.skipped>result.tests)fail('REPORT_COUNTER_INVALID');return result;
}
/** Read existing build artifacts and report metadata only; never builds, packages or reads environment values. */
export async function createManifest({root=process.cwd(),kind,commit,output}={}){
 if(!['java','frontend'].includes(kind)||!/^[a-f0-9]{40}$/.test(commit??''))fail('EXPLICIT_KIND_COMMIT_REQUIRED');
 if(!/^target\/[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}\.json$/.test(output??''))fail('OUTPUT_PATH_INVALID');
 root=realpathSync(root);const dir=join(root,'target');if(existsSync(dir)&&(!lstatSync(dir).isDirectory()||lstatSync(dir).isSymbolicLink()||realpathSync(dir)!==resolve(root,'target')))fail('OUTPUT_PATH_INVALID');
 const destination=join(root,output);if(existsSync(destination))fail('OUTPUT_EXISTS');
 const git=args=>{try{return execFileSync('git',args,{cwd:root,encoding:'utf8',stdio:['ignore','pipe','ignore']}).trim();}catch{fail('GIT_CHECK_FAILED');}};
 if(realpathSync(git(['rev-parse','--show-toplevel']))!==root)fail('REPOSITORY_ROOT_REQUIRED');
 if(git(['rev-parse','HEAD'])!==commit)fail('COMMIT_MISMATCH');
 for(const args of [['diff','--quiet'],['diff','--cached','--quiet']])try{execFileSync('git',args,{cwd:root,stdio:'ignore'});}catch{fail('TRACKED_SOURCE_DIRTY');}
 const record={kind,commit,scope:'Checksums of existing artifacts and reported test counts only; not packaging, secret scanning, coverage, business, browser or model acceptance',artifacts:[]};
 if(kind==='java'){
  record.artifacts.push(await describe(root,join(dir,'zxl-Ai-agent-0.0.1-SNAPSHOT.jar')));
  const reportsDir=join(dir,'surefire-reports');regular(reportsDir,true);const names=readdirSync(reportsDir).filter(n=>/^TEST-.*\.xml$/.test(n)).sort();if(!names.length)fail('REPORTS_REQUIRED');
  const sum={tests:0,failures:0,errors:0,skipped:0};record.reports=[];
  for(const name of names){const path=join(reportsDir,name);regular(path);const c=counts(readFileSync(path,'utf8'));for(const key of Object.keys(sum)){sum[key]+=c[key];if(!Number.isSafeInteger(sum[key]))fail('REPORT_COUNTER_INVALID');}record.reports.push({...await describe(root,path),...c});}
  if(!sum.tests)fail('TESTS_REQUIRED');if(sum.failures||sum.errors)fail('TEST_FAILURES_REPORTED');record.tests={...sum,executed:sum.tests-sum.skipped};
 }else{
  const frontend=join(root,'zxl-Ai-agent-fronted'),dist=join(frontend,'dist');regular(frontend,true);regular(dist,true);regular(join(dist,'index.html'));
  const visit=async path=>{const s=lstatSync(path);if(s.isSymbolicLink())fail('ARTIFACT_LINK_INVALID');if(s.isDirectory()){for(const name of readdirSync(path).sort())await visit(join(path,name));}else{record.artifacts.push(await describe(root,path));}};await visit(dist);
 }
 mkdirSync(dir,{recursive:true});writeFileSync(destination,JSON.stringify(record,null,2)+'\n',{flag:'wx',mode:0o600});return record;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){const args=process.argv.slice(2),o={};let valid=args.length===6;for(let i=0;i<args.length;i+=2){const key=args[i].slice(2);if(!args[i].startsWith('--')||!['kind','commit','output'].includes(key)||o[key]!==undefined)valid=false;o[key]=args[i+1];}if(!valid){console.error('EXPLICIT_KIND_COMMIT_OUTPUT_REQUIRED');process.exitCode=2;}else createManifest(o).then(()=>console.log('CI_MANIFEST_WRITTEN')).catch(e=>{console.error(/^[A-Z_]+$/.test(e.message)?e.message:'CI_MANIFEST_FAILED');process.exitCode=1;});}
