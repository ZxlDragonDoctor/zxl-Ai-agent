import test from 'node:test'
import assert from 'node:assert/strict'
import { projectControlled } from './project-controlled.mjs'

test('budget projection requires observed phase, terminal deadline and completed cleanup', () => {
 const o={id:'budget-3',model:'controlled-java-fixture',runId:'run',clockMode:'controlled',countsScope:'database-global',beforeWrites:1,afterWrites:1,reachedPhase:'worker-queue',phaseConfirmed:true,errorCode:'RUN_DEADLINE_EXCEEDED',outcomeStatus:'failed',cleanupConfirmed:true,activeTasks:0,elapsedMs:10}
 assert.equal(projectControlled(o).status,'timeout')
 for(const change of [{phaseConfirmed:false},{cleanupConfirmed:false},{activeTasks:1},{afterWrites:0},{reachedPhase:'model-wait'},{countsScope:undefined}])assert.throws(()=>projectControlled({...o,...change}))
})
test('fault projection requires actual started and failed events and excludes typed adapter injection', () => {
 const tool='knowledge__knowledge_lookup_v2'
 const o={id:'tool-failure-4',model:'controlled-java-fixture',beforeWrites:0,afterWrites:0,faultScenario:'response-limit',discovered:true,faultTriggered:true,typedAdapterInjection:false,toolError:'MCP_INVALID_RESPONSE',elapsedMs:10,events:[{type:'TOOL_STARTED',toolName:tool},{type:'TOOL_FAILED',toolName:tool,content:JSON.stringify({success:false,code:'MCP_INVALID_RESPONSE'})}]}
 assert.deepEqual(projectControlled(o).toolErrors,['MCP_INVALID_RESPONSE'])
 for(const change of [{events:[]},{typedAdapterInjection:true},{toolError:'MCP_NETWORK_FAILURE'},{faultTriggered:false}])assert.throws(()=>projectControlled({...o,...change}))
})

test('research tool wait uses actual events without inventing an orchestration run ID', () => {
 const o={id:'budget-2',model:'controlled-java-fixture',lookupEntered:true,clockMode:'system-monotonic',countsScope:'fixture-tenant',beforeWrites:0,afterWrites:0,reachedPhase:'tool-wait',phaseConfirmed:true,errorCode:'RESEARCH_DEADLINE',outcomeStatus:'failed',cleanupConfirmed:true,activeTasks:0,elapsedMs:1001,events:[{type:'TOOL_STARTED',toolName:'knowledge__knowledge_lookup_v2'}]}
 assert.equal(projectControlled(o).runId,undefined)
 assert.equal(projectControlled(o).status,'timeout')
 assert.throws(()=>projectControlled({...o,events:[]}))
})
