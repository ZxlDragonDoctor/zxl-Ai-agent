import {readFileSync} from 'node:fs'
import {createHash} from 'node:crypto'
import {pathToFileURL} from 'node:url'

export function projectControlled(o) {
 const phases={'budget-2':'tool-wait','budget-3':'worker-queue','budget-4':'planner-wait','budget-5':'synthesis-wait'}
 const faults={'tool-failure-2':['malformed-reply','MCP_INVALID_RESPONSE'],'tool-failure-3':['mcp-unavailable','MCP_NETWORK_FAILURE','MCP_PROTOCOL_FAILURE','MCP_DEADLINE_EXCEEDED'],'tool-failure-4':['response-limit','MCP_INVALID_RESPONSE'],'tool-failure-5':['retrieval-timeout','MCP_DEADLINE_EXCEEDED']}
 if(o?.model!=='controlled-java-fixture'||!Number.isSafeInteger(o.beforeWrites)||o.beforeWrites<0||!Number.isSafeInteger(o.afterWrites)||o.afterWrites<o.beforeWrites||!Number.isFinite(o.elapsedMs)||o.elapsedMs<0)throw Error('Measured controlled evidence required')
 const result={id:o.id,sources:[],tools:[],writes:o.afterWrites-o.beforeWrites,elapsedMs:o.elapsedMs,tokens:null}
 if(phases[o.id]) {
  if((o.id==='budget-2'?(o.countsScope!=='fixture-tenant'||o.clockMode!=='system-monotonic'||!o.events?.some(e=>e.type==='TOOL_STARTED'&&e.toolName==='knowledge__knowledge_lookup_v2')||o.lookupEntered!==true):(o.countsScope!=='database-global'||!o.runId||o.clockMode!=='controlled'))||o.phaseConfirmed!==true||o.reachedPhase!==phases[o.id]||o.cleanupConfirmed!==true||o.activeTasks!==0||o.outcomeStatus!=='failed'||!['RUN_DEADLINE_EXCEEDED',...(o.id==='budget-2'?['RESEARCH_DEADLINE']:[])].includes(o.errorCode))throw Error('Verified deadline phase and cleanup required')
  return {...result,...(o.runId?{runId:o.runId}:{}),tools:[...new Set((o.events??[]).filter(e=>e.type==='TOOL_STARTED').map(e=>e.toolName))],status:'timeout',reachedPhase:o.reachedPhase,cleanupConfirmed:true}
 }
 const fault=faults[o.id]
 if(!fault||o.faultScenario!==fault[0]||!fault.slice(1).includes(o.toolError)||o.discovered!==true||o.faultTriggered!==true||o.typedAdapterInjection!==false||!Array.isArray(o.events))throw Error('Verified dependency fault required')
 const tool='knowledge__knowledge_lookup_v2'
 const failed=o.events.find(e=>e.type==='TOOL_FAILED'&&e.toolName===tool)
 if(!o.events.some(e=>e.type==='TOOL_STARTED'&&e.toolName===tool)||!failed)throw Error('Actual tool events required')
 const body=JSON.parse(failed.content),code=body.code==='MCP_TOOL_ERROR'?body.data?.remoteCode:body.code
 if(body.success!==false||code!==o.toolError)throw Error('Matching failed tool event required')
 return {...result,status:'failed',tools:[...new Set(o.events.filter(e=>e.type==='TOOL_STARTED').map(e=>e.toolName))],toolErrors:[code],faultScenario:o.faultScenario}
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const [datasetPath,split,knowledgeVersion,...files]=process.argv.slice(2)
 if(!['dev','holdout'].includes(split)||!knowledgeVersion||!files.length)throw Error('Dataset, split, pinned fixture version and observations required')
 const bytes=readFileSync(datasetPath),dataset=JSON.parse(bytes),runs=files.map(file=>projectControlled(JSON.parse(readFileSync(file))))
 if(new Set(runs.map(r=>r.id)).size!==runs.length||runs.some(r=>!dataset.cases.some(c=>c.id===r.id&&c.split===split)))throw Error('Unique cases in the selected split required')
 console.log(JSON.stringify({datasetSha256:createHash('sha256').update(bytes).digest('hex'),model:'controlled-java-fixture',knowledgeVersion,variant:'controlled-phases-and-faults',runs},null,2))
}
