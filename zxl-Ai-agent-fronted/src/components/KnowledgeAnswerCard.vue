<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { locale } from '../i18n'
import { getKnowledgeDocument } from '../api/knowledge'
import { readKnowledgeAnswer } from '../api/answer'
import type { Evidence } from '../api/answer'
const props = defineProps<{ payload: string }>()
const result = computed(() => readKnowledgeAnswer(props.payload))
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const source = ref<Evidence | null>(null), error = ref(''), busy = ref(false)
watch(() => props.payload, () => { source.value = null; error.value = '' })
const statusName = (s: string) => ({ ANSWERED: tr('有依据回答', 'Evidence-backed answer'), PARTIAL: tr('部分回答', 'Partial answer'), NEEDS_CLARIFICATION: tr('需要澄清', 'Clarification needed'), INSUFFICIENT_EVIDENCE: tr('资料不足', 'Insufficient evidence'), SERVICE_FAILURE: tr('服务未完成', 'Service failure') }[s] || s)
const reasonName = (s: string) => ({ NO_SUPPORTED_CLAIMS: tr('现有资料无法支持本次问题的结论。', 'Current evidence does not support a conclusion for this question.'), NO_CURRENT_EVIDENCE: tr('没有找到当前可用的资料，请补充相关文档。', 'No current evidence was found. Add relevant documents.'), SUPPORTED_CURRENT_EVIDENCE: tr('以下结论已核对引用和当前资料。', 'The claims below were checked against current evidence.'), UNSUPPORTED_CLAIMS_REMOVED: tr('已移除无法被资料支持的结论，仅保留下方有依据的部分。', 'Unsupported claims were removed; supported portions remain below.'), MISSING_ERROR_CODE: tr('请补充错误码或产品版本。', 'Please provide an error code or product version.'), MISSING_SUBQUESTION_EVIDENCE: tr('部分问题有依据，仍缺少下列信息。', 'Some parts are supported; the information listed below is still missing.'), QUARANTINED_UNTRUSTED_SOURCE: tr('部分资料包含覆盖规则的指令，已隔离；仅使用其余有效证据。', 'Sources containing rule-override instructions were quarantined; only other valid evidence was used.'), EVIDENCE_CHANGED_DURING_ANSWER: tr('资料在回答期间变化，请重新提问。', 'Evidence changed during this answer. Ask again.'), RETRIEVAL_GENERATION_OR_VALIDATION_FAILED: tr('检索、生成或校验未完成，请稍后重试。', 'Retrieval, generation or validation did not complete. Please retry.'), REAL_MODEL_NOT_CONFIGURED: tr('真实生成与检查模型尚未配置。', 'Live generation and checking models are not configured.') }[s] || s)
async function open(e: Evidence) {
  busy.value = true; source.value = null; error.value = ''
  try {
    const doc = await getKnowledgeDocument(e.documentId)
    if (doc.document.activeRevision !== e.revisionId || doc.document.version !== e.generation || !doc.chunks.some(c => c.chunkId === e.chunkId)
      || doc.rawText.slice(e.startUtf16, e.endUtf16) !== e.text) throw new Error(tr('来源已删除或换版，请重新提问获取当前证据。', 'Source deleted or revised. Ask again for current evidence.'))
    source.value = e
  } catch (cause) { error.value = cause instanceof Error ? cause.message : tr('无法读取当前来源', 'Cannot read current source') }
  finally { busy.value = false }
}
</script>
<template>
  <section v-if="result" class="knowledge-answer" :aria-label="tr('回答依据与校验', 'Answer evidence and checks')">
    <header><span class="status" :class="result.status.toLowerCase()">{{ statusName(result.status) }}</span><small>{{ result.elapsedMs ?? '—' }} ms</small></header>
    <p v-if="result.reason" class="reason">{{ reasonName(result.reason) }}</p>
    <div v-for="(claim, index) in result.claims" :key="index" class="claim">
      <p>{{ claim.text }}</p><div class="citations"><button v-for="id in claim.evidenceIds" :key="id" :disabled="busy" @click="open(result.evidence.find(e => e.id === id)!)">[{{ id }}] {{ tr('查看原文', 'Read source') }}</button><span>{{ tr('原文支持', 'Supported by evidence') }}</span></div>
      <small v-if="claim.reason">{{ claim.reason }}</small>
    </div>
    <p v-for="item in (Array.isArray(result.missingInformation) ? result.missingInformation : result.missingInformation ? [result.missingInformation] : [])" :key="item" class="reason">{{ item }}</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div v-if="source" class="source" role="region" :aria-label="tr('引用原文', 'Citation source')">
      <header><strong>[{{ source.id }}] {{ source.sourceName || source.documentId }}</strong><button @click="source = null">{{ tr('关闭原文', 'Close source') }}</button></header>
      <small>{{ tr('版本', 'Revision') }} {{ source.revisionId }} · {{ tr('行', 'Lines') }} {{ source.startLine }}–{{ source.endLine }} · UTF-16 {{ source.startUtf16 }}–{{ source.endUtf16 }}</small><pre>{{ source.text }}</pre>
    </div>
    <details><summary>{{ tr('执行与上下文记录', 'Execution and context record') }}</summary><p>{{ tr('生成器', 'Generator') }}：{{ result.generatorIdentity }}<br>{{ tr('支持性检查器', 'Support checker') }}：{{ result.judgeIdentity }}</p><p>trace {{ result.traceId }} · snapshot {{ result.snapshotId }}</p><pre>{{ JSON.stringify(result.memory, null, 2) }}</pre><p>{{ tr('历史回答保留当时内容；打开引用时重新核对当前版本。', 'Historical answers retain their original content; opening sources rechecks the current revision.') }}</p></details>
  </section>
  <p v-else role="alert">{{ tr('回答证据格式无效，未展示为有效引用。', 'Invalid evidence format; citations were not trusted.') }}</p>
</template>
<style scoped>
.knowledge-answer{margin-top:16px;padding:18px;border:1px solid var(--color-border);border-radius:12px;background:var(--color-bg-alt);font-size:12px;line-height:1.8}.knowledge-answer header,.citations{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}.status{padding:3px 9px;border-radius:6px;background:#e4f1eb;color:#245c49;font-weight:600}.service_failure,.insufficient_evidence{background:#fff0de;color:#80511b}.claim{margin:14px 0;padding-top:12px;border-top:1px solid var(--color-border-light)}.claim p{margin-bottom:6px}.citations{justify-content:flex-start}.citations span,small{color:var(--color-text-secondary);font-size:11px}button{border:1px solid var(--color-border);border-radius:6px;padding:4px 9px;color:var(--color-primary);background:var(--color-bg)}button:focus-visible,summary:focus-visible{outline:2px solid var(--color-primary);outline-offset:3px}button:disabled{opacity:.5}.source{padding:14px;background:var(--color-bg);border-radius:8px;margin-top:14px}.source pre,details pre{white-space:pre-wrap;overflow-wrap:anywhere}.reason{margin:10px 0;color:var(--color-text-secondary)}.error{color:var(--color-error)}details{margin-top:14px;overflow-wrap:anywhere}summary{cursor:pointer;color:var(--color-text-secondary)}
</style>
