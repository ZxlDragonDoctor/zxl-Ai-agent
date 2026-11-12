<script setup lang="ts">
import { ref } from 'vue'
import { locale } from '../i18n'
import { submitTicketDraft } from '../api/ticketApprovals'

const props = defineProps<{ conversationId: string; runId: string }>()
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const proposal = ref<Awaited<ReturnType<typeof submitTicketDraft>> | null>(null)
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    proposal.value = await submitTicketDraft(props.conversationId, props.runId)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : 'REQUEST_FAILED'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="handoff" :aria-label="tr('提交工单草稿', 'Submit ticket draft')">
    <button v-if="!proposal" type="button" :disabled="busy" @click="submit">
      {{ busy ? tr('正在提交…', 'Submitting…') : tr('提交草稿审批', 'Submit draft for approval') }}
    </button>
    <div v-else role="status">
      <strong>{{ { PENDING: tr('待审批', 'Pending approval'), APPROVED: tr('已批准', 'Approved'), REJECTED: tr('已驳回', 'Rejected') }[proposal.outcome] }}</strong>
      <label>{{ tr('审批编号，可复制给审批员', 'Copy this approval ID for the reviewer') }}
        <input readonly :value="proposal.proposalId" @focus="($event.target as HTMLInputElement).select()">
      </label>
    </div>
    <p v-if="error" role="alert">
      {{ error === 'TICKET_DRAFT_UNAVAILABLE' ? tr('草稿尚不可提交，请刷新会话后重试。', 'The draft is unavailable. Refresh this conversation and try again.') : tr('提交失败', 'Submission failed') }}
      <code>{{ error }}</code>
    </p>
  </section>
</template>

<style scoped>
.handoff{margin:12px 0;border-left:3px solid var(--color-primary);padding:8px 12px;background:var(--color-bg-alt);border-radius:0 8px 8px 0}
button{padding:7px 12px;border:1px solid var(--color-primary);border-radius:7px;color:var(--color-primary);font-weight:600}
button:disabled{opacity:.6}label{display:block;margin-top:5px;font-size:12px}input{display:block;width:min(100%,22rem);margin-top:4px;padding:5px 7px;border:1px solid var(--color-border);border-radius:5px;font:inherit;color:var(--color-text)}
p[role=alert]{color:var(--color-error);margin:5px 0}button:focus-visible,input:focus-visible{outline:2px solid var(--color-primary);outline-offset:2px}
</style>
