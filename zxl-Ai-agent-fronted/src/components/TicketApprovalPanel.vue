<script setup lang="ts">
import { ref } from 'vue'
import { locale } from '../i18n'
import { executeTicketApproval, inspectTicketApproval, reviewTicketApproval, validProposalId } from '../api/ticketApprovals'
import type { ApprovalDetails, TicketReceipt } from '../api/ticketApprovals'

const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const id = ref('')
const approval = ref<ApprovalDetails | null>(null)
const receipt = ref<TicketReceipt | null>(null)
const busy = ref(false)
const error = ref('')

async function act(operation: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await operation()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : 'REQUEST_FAILED'
  } finally {
    busy.value = false
  }
}
function load() {
  const proposalId = id.value.trim()
  if (!validProposalId(proposalId)) return
  return act(async () => {
    approval.value = null
    receipt.value = null
    approval.value = await inspectTicketApproval(proposalId)
  })
}
function review(decision: 'APPROVED' | 'REJECTED') {
  const current = approval.value
  if (!current || current.outcome !== 'PENDING') return
  return act(async () => {
    const result = await reviewTicketApproval(current, decision)
    approval.value = { ...current, outcome: result.outcome, version: result.version }
  })
}
function execute() {
  const current = approval.value
  if (!current || current.outcome !== 'APPROVED') return
  return act(async () => { receipt.value = await executeTicketApproval(current) })
}
</script>

<template>
  <details class="review">
    <summary>{{ tr('工单审批', 'Ticket approvals') }}</summary>
    <form @submit.prevent="load">
      <label>{{ tr('审批编号', 'Approval ID') }}
        <input v-model.trim="id" maxlength="36" required autocomplete="off" spellcheck="false" :disabled="busy"
          @input="approval = null; receipt = null; error = ''">
      </label>
      <button type="submit" :disabled="busy || !validProposalId(id)">{{ tr('查看草稿', 'Load draft') }}</button>
    </form>
    <p v-if="busy" role="status">{{ tr('正在处理…', 'Working…') }}</p>
    <p v-if="error" role="alert">
      {{ error === 'APPROVAL_EXPIRED' ? tr('审批已过期，无法创建工单。', 'Approval expired. A new ticket cannot be created.') : tr('操作失败', 'Operation failed') }}
      <code>{{ error }}</code>
    </p>
    <section v-if="approval" class="draft" :aria-label="tr('待审批草稿', 'Draft for review')">
      <h2>{{ approval.title }}</h2>
      <p class="description">{{ approval.description }}</p>
      <p class="state" role="status">
        {{ { PENDING: tr('待审批', 'Pending approval'), APPROVED: tr('已批准', 'Approved'), REJECTED: tr('已驳回', 'Rejected') }[approval.outcome] }}
        · {{ tr('有效期至', 'Expires at') }} {{ new Date(approval.expiresAt).toLocaleString(locale) }}
      </p>
      <div v-if="approval.outcome === 'PENDING'" class="actions">
        <button type="button" :disabled="busy" @click="review('APPROVED')">{{ tr('批准草稿', 'Approve draft') }}</button>
        <button type="button" :disabled="busy" @click="review('REJECTED')">{{ tr('驳回草稿', 'Reject draft') }}</button>
      </div>
      <button v-else-if="approval.outcome === 'APPROVED'" type="button" :disabled="busy" @click="execute">
        {{ receipt ? tr('核对执行回执', 'Verify receipt') : tr('创建工单或获取已有回执', 'Create ticket or retrieve receipt') }}
      </button>
      <p v-if="receipt" class="receipt" role="status">{{ tr('工单已创建', 'Ticket created') }} · <code>{{ receipt.id }}</code></p>
    </section>
  </details>
</template>

<style scoped>
.review{border:1px solid var(--color-border);border-radius:10px;padding:10px 13px;background:var(--color-bg-alt);font-size:13px}
summary{cursor:pointer;font-weight:650;color:var(--color-primary)}
form,.actions{display:flex;flex-wrap:wrap;align-items:end;gap:8px;margin-top:10px}label{display:grid;gap:4px;width:min(100%,21rem)}input,button{border:1px solid var(--color-border);border-radius:7px;padding:6px 9px;color:var(--color-text)}input{width:100%;font:inherit}button{border-color:var(--color-primary);color:var(--color-primary);font-weight:600}button:disabled{opacity:.6}
.draft{margin-top:10px;padding:9px 12px;border-left:3px solid var(--color-primary);background:var(--color-bg);overflow-wrap:anywhere}
h2{font-size:16px;margin:0 0 5px}.description{white-space:pre-wrap}.state,.receipt{margin:8px 0;color:var(--color-primary)}p[role=alert]{color:var(--color-error)}button:focus-visible,input:focus-visible,summary:focus-visible{outline:2px solid var(--color-primary);outline-offset:2px}
</style>
