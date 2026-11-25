import {createHash} from 'node:crypto';
const digest=v=>createHash('sha256').update(JSON.stringify(v)).digest('hex');
const terminal=new Set(['completed','failed','cancelled','interrupted']);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** Read-only terminal comparison input. No provider/model requests or cancellation. */
export async function snapshotRun({origin,cookie,runId}={}){
 if(!/^http:\/\/127\.0\.0\.1:[0-9]{1,5}$/.test(origin??'')||!uuid.test(runId??'')||typeof cookie!=='string'||!cookie||cookie.length>8192||/[\r\n]/.test(cookie))throw Error('RECOVERY_CONFIG_INVALID');
 try{if(!new URL(origin).port)throw Error();}catch{throw Error('RECOVERY_CONFIG_INVALID');}
 const abort=new AbortController(),timer=setTimeout(()=>abort.abort(),5000);
 try{
  const r=await fetch(origin+'/api/ai/multiagent/runs/'+runId,{headers:{Cookie:cookie},redirect:'manual',signal:abort.signal});
  if(r.status!==200)throw Error('RECOVERY_HTTP_FAILED');
  const reader=r.body?.getReader();if(!reader)throw Error('RECOVERY_REPORT_INVALID');let bytes=0;const parts=[];
  while(true){const {done,value}=await reader.read();if(done)break;bytes+=value.length;if(bytes>131072){await reader.cancel();throw Error('RECOVERY_REPORT_INVALID');}parts.push(Buffer.from(value));}
  let report;try{report=JSON.parse(Buffer.concat(parts).toString('utf8'))?.run?.report;}catch{throw Error('RECOVERY_REPORT_INVALID');}
  if(report?.executionId!==runId||!terminal.has(report?.status)||!Number.isSafeInteger(report?.sequence)||report.sequence<0||(report.result!=null&&typeof report.result!=='string')||(report.error?.code!=null&&typeof report.error.code!=='string'))throw Error('RECOVERY_REPORT_INVALID');
  return {runId,status:report.status,sequence:report.sequence,resultSha256:digest(report.result??null),errorCode:report.error?.code==null?null:digest(report.error.code)};
 }catch(e){throw Error(['RECOVERY_HTTP_FAILED','RECOVERY_REPORT_INVALID'].includes(e.message)?e.message:'RECOVERY_TRANSPORT_FAILED');}finally{clearTimeout(timer);}
}
export function compareRecovery(before,after){
 const sameRun=uuid.test(before?.runId??'')&&before.runId===after?.runId;
 const terminalPreserved=terminal.has(before?.status)&&before.status===after?.status;
 const resultPreserved=/^[a-f0-9]{64}$/.test(before?.resultSha256??'')&&before.resultSha256===after?.resultSha256;
 const errorPreserved=before?.errorCode===after?.errorCode;
 const sequenceNotRegressed=Number.isSafeInteger(before?.sequence)&&before.sequence>=0&&Number.isSafeInteger(after?.sequence)&&after.sequence>=before.sequence;
 return {sameRun,terminalPreserved,resultPreserved,errorPreserved,sequenceNotRegressed,passed:sameRun&&terminalPreserved&&resultPreserved&&errorPreserved&&sequenceNotRegressed};
}
