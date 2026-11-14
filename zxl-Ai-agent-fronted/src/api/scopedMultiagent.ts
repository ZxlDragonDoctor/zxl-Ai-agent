import { sessionFetch, sessionGeneration, requireSuccessfulResponse, assertSessionGeneration, SessionRequestError } from '../utils/sessionRequest.ts'
export interface ScopedTask { id?:string; description?:string; status:string; result?:string; failure?:{code?:string;message?:string}; dependencies?:string[]; modelCalls?:number }
export interface ScopedReport { executionId:string; goal:string; status:string; sequence:number; result?:string; error?:{code?:string;message?:string}; tasks:ScopedTask[]; planningTask?:ScopedTask; synthesisTask?:ScopedTask }
export interface ScopedEvent { id:string; type:string; timestamp:number; agentId:string; data?:{taskId?:string; executionId?:string; event?:{type:string;toolName?:string;status?:string;content?:string;arguments?:string}} }
export interface ScopedRun { run:{executionId:string;report:ScopedReport|null;runtime:{deadlineAt?:number;cancelRequested?:boolean;activeTasks?:number;activeModelCalls?:number;cleanupPending?:boolean;waitingFor?:string}};events:ScopedEvent[] }
const base='/api/ai/multiagent'
const runPath=(id:string)=>{if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id))throw new SessionRequestError('INVALID_RUN_ID');return `${base}/runs/${id}`}
async function request<T>(path:string,method='GET',body?:unknown):Promise<T>{const generation=sessionGeneration();const response=await sessionFetch(path,{method,...(body===undefined?{}:{headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})});await requireSuccessfulResponse(response,generation);const value=await response.json();assertSessionGeneration(generation);return value as T}
export async function submitScopedRun(goal:string,timeoutMs:number){return request<{executionId:string}>(`${base}/runs`,'POST',{goal,timeoutMs})}
export async function readScopedRun(id:string){return request<ScopedRun>(runPath(id))}
export async function cancelScopedRun(id:string){return request<{executionId:string;status:string}>(runPath(id)+'/cancel','POST')}
export async function listScopedRuns(){return request<{runs:ScopedRun[]}>(base+'/status')}
export interface DurableRunEvent {
  sequence: number
  status: string
  taskCount: number
  result: string | null
  errorCode: string | null
}
export async function readDurableRunEvents(id: string, after: number) {
  return request<{ lastSequence: number; events: DurableRunEvent[] }>(runPath(id) + '/events?after=' + after)
}
export const durableRunStreamUrl = (id: string, after: number) => runPath(id) + "/events/stream?after=" + after
