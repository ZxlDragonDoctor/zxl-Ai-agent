import { requireSuccessfulResponse, sessionFetch, sessionGeneration } from '../utils/sessionRequest'

type Outcome = 'PENDING' | 'APPROVED' | 'REJECTED'
type Decision = Exclude<Outcome, 'PENDING'>
interface Submitted { proposalId: string; outcome: Outcome; expiresAt: string }
interface Binding { version: number; operationId: string; fingerprint: string }
export interface ApprovalDetails extends Submitted, Binding { title: string; description: string }
interface Review { proposalId: string; outcome: Decision; version: number }
export interface TicketReceipt { id: string; operationId: string; title: string; description: string }

const base = '/api/ai/learning'
export const validProposalId = (id: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)

async function request<T>(path: string, body?: object): Promise<T> {
  const generation = sessionGeneration()
  const response = await sessionFetch(base + path, body === undefined ? {} : {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  await requireSuccessfulResponse(response, generation)
  return response.json()
}

const approvalPath = (id: string) => '/ticket-approvals/' + encodeURIComponent(id)
const binding = ({ version, operationId, fingerprint }: ApprovalDetails): Binding => ({ version, operationId, fingerprint })

export const submitTicketDraft = (conversationId: string, runId: string) =>
  request<Submitted>('/ticket-proposals', { conversationId, runId })
export const inspectTicketApproval = (id: string) => request<ApprovalDetails>(approvalPath(id))
export const reviewTicketApproval = (approval: ApprovalDetails, decision: Decision) =>
  request<Review>(approvalPath(approval.proposalId) + '/review', { ...binding(approval), decision })
export const executeTicketApproval = (approval: ApprovalDetails) =>
  request<TicketReceipt>(approvalPath(approval.proposalId) + '/execute', binding(approval))
