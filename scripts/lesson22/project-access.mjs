export function projectAccess(o) {
 const {id,response}=o
 if(!o.runId||!o.actor?.subjectId||!o.actor?.tenantId||o.actor.role!=='employee'||!Number.isFinite(o.elapsedMs)||o.elapsedMs<0||o.writes!==0||o.beforeWrites!==0||o.afterWrites!==0||o.method!=='GET'||o.hasResourceBody!==false)throw Error('Measured access evidence required')
 if(id==='authorization-1'||id==='authorization-2'){
  if(o.ownerRead!==200||!o.resourceId||o.resourceId!==o.requestedId||!o.owner?.subjectId||!o.actor?.subjectId||!o.owner.tenantId||!o.actor.tenantId
   ||o.actor.role!=='employee'||response?.status!==404||response.code!=='NOT_FOUND'
   ||(id==='authorization-2'&&(o.actor.subjectId===o.owner.subjectId||o.actor.tenantId!==o.owner.tenantId))
   ||(id==='authorization-1'&&o.actor.tenantId===o.owner.tenantId)
   ||o.path!==(id==='authorization-1'?'/ai/learning/knowledge/documents/':'/conversations/')+o.resourceId)throw Error('Verified foreign-resource denial required')
 }else if(id==='authorization-4'){
  if(o.priorRead!==200||o.authorityChanged!==true||o.sameSessionReplay!==true||o.path!=='/ai/learning/knowledge/documents'||response?.status!==401||response.code!=='UNAUTHENTICATED')throw Error('Verified revoked access required')
 }else if(id==='authorization-5'){
  if(o.priorRead!==200||o.readOnly!==true||!o.archiveId||o.path!=='/conversations/'+o.archiveId||o.authorityChanged!==true||o.sameSessionReplay!==true||response?.status!==401||response.code!=='UNAUTHENTICATED')throw Error('Verified archive denial required')
 }else throw Error('Unsupported access case')
 return {id,runId:o.runId,status:'denied',sources:[],tools:[],writes:o.writes,elapsedMs:o.elapsedMs,tokens:null}
}

