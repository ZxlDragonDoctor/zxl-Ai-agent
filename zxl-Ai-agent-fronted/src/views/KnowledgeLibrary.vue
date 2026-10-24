<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { locale } from '../i18n'
import { embeddingPresentation } from '../utils/knowledgePresentation'
import RetrievalLab from '../components/RetrievalLab.vue'
import RetrievalEvaluation from '../components/RetrievalEvaluation.vue'
import {
  KnowledgeApiError, deleteKnowledgeDocument, getKnowledgeDocument, getKnowledgeStatus,
  importKnowledgeDocument, listKnowledgeDocuments, searchKnowledge,
} from '../api/knowledge'
import type { KnowledgeDetail, KnowledgeDocument, KnowledgeSearch, KnowledgeStatus } from '../api/knowledge'

const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const status = ref<KnowledgeStatus | null>(null)
const embeddingView = computed(() => embeddingPresentation(status.value?.embeddingMode))
const documents = ref<KnowledgeDocument[]>([])
const detail = ref<KnowledgeDetail | null>(null)
const result = ref<KnowledgeSearch | null>(null)
const busy = ref('')
const error = ref('')
const notice = ref('')
const documentId = ref('vpn-guide')
const sourceName = ref('企业 VPN 故障处理手册.md')
const content = ref('')
const query = ref('VPN 连接失败')
const limit = ref(5)
// Results belong to one exact query and limit, not the next edited question.
watch([query, limit], () => { result.value = null })
const detailTab = ref<'raw' | 'chunks'>('chunks')
const deleteTarget = ref<KnowledgeDocument | null>(null)
const deleteDialog = ref<HTMLElement>()
let returnFocus: HTMLElement | null = null
watch(deleteTarget, async target => {
  if (target) {
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    deleteDialog.value?.focus()
  } else { returnFocus?.focus(); returnFocus = null }
})
function keepDialogFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = [...(deleteDialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || [])]
  const first = buttons[0]; const last = buttons[buttons.length - 1]
  if (!first || !last) { event.preventDefault(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === deleteDialog.value)) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === deleteDialog.value)) { event.preventDefault(); first.focus() }
}
onBeforeUnmount(() => { returnFocus = null })
const activeDocuments = computed(() => documents.value.filter(doc => doc.status === 'ACTIVE').length)

function showError(reason: unknown) {
  const code = reason instanceof KnowledgeApiError ? reason.code : 'UNKNOWN_ERROR'
  error.value = `${tr('操作未成功', 'Request failed')} · ${code}${reason instanceof Error ? ` — ${reason.message}` : ''}`
}
async function run(action: string, work: () => Promise<void>) {
  if (busy.value) return
  busy.value = action; error.value = ''; notice.value = ''
  try { await work() } catch (reason) { showError(reason) } finally { busy.value = '' }
}
async function refreshData() {
  // Both failures remain visible; a database failure cannot turn into an empty successful list.
  status.value = null
  documents.value = []; detail.value = null; result.value = null
  const [freshStatus, freshDocuments] = await Promise.all([getKnowledgeStatus(), listKnowledgeDocuments()])
  status.value = freshStatus; documents.value = freshDocuments
}
function refresh() { return run('refresh', refreshData) }
function fillExample() {
  documentId.value = 'vpn-guide'; sourceName.value = '企业 VPN 故障处理手册.md'
  content.value = '# 企业 VPN 故障处理手册\n\n## 适用范围\n这是一份教学用合成资料，不包含企业真实账号、密码和内网地址。\n\n## VPN 连接失败\n先确认本地网络可用，再检查 VPN 客户端版本。错误码 VPN-401 表示登录凭据过期，请通过企业统一身份平台重新登录。\n\n## 工单升级\n连续三次连接失败时提交网络支持工单。工单应包含发生时间、客户端版本和脱敏错误码；不要上传密码、验证码或访问令牌。\n'
  notice.value = tr('已填入合成示例，点击“导入 / 发布版本”后才会写入数据库。', 'Synthetic example loaded. Import it to persist a version.')
}
function publish() {
  return run('import', async () => {
    const imported = await importKnowledgeDocument({ documentId: documentId.value.trim(), sourceName: sourceName.value.trim(), content: content.value })
    await refreshData()
    detail.value = await getKnowledgeDocument(imported.document.documentId)
    result.value = null
    notice.value = imported.outcome === 'UNCHANGED'
      ? tr('原文未变化，复用当前版本；未重复创建分块。', 'Unchanged content: the current version was reused without duplicate chunks.')
      : tr('新版本已发布，文档与分块已从服务端重新读取。', 'Version published. The document and chunks were reloaded from the service.')
  })
}
function openDocument(id: string) {
  return run('detail', async () => { detail.value = await getKnowledgeDocument(id); detailTab.value = 'chunks' })
}
function editDocument() {
  if (!detail.value) return
  documentId.value = detail.value.document.documentId
  sourceName.value = detail.value.document.sourceName
  content.value = detail.value.rawText
  notice.value = tr('原文已载入编辑区。修改后发布新版本；相同原文重导入将去重。', 'Source loaded into the editor. Publish changes as a new version; unchanged content is deduplicated.')
}
function removeDocument() {
  if (!deleteTarget.value) return
  const id = deleteTarget.value.documentId
  return run('delete', async () => {
    await deleteKnowledgeDocument(id)
    await refreshData()
    if (detail.value?.document.documentId === id) detail.value = null
    result.value = null; deleteTarget.value = null
    notice.value = tr('删除已确认，旧检索结果已清空。可重新检索检查。', 'Deletion confirmed. Previous search results were cleared; search again to check.')
  })
}
function search() {
  return run('search', async () => {
    result.value = null
    result.value = await searchKnowledge(query.value.trim(), Number(limit.value))
  })
}
onMounted(refresh)
</script>

<template>
  <section class="knowledge-page" :aria-busy="!!busy">
    <nav class="knowledge-shortcuts" :aria-label="tr('知识库功能入口', 'Knowledge tools')"><a href="#retrieval-lab-title">{{ tr('进入检索实验室 ↓', 'Open retrieval lab ↓') }}</a><a href="#evaluation-title">{{ tr('查看固定集评测 ↓', 'Open fixed-set evaluation ↓') }}</a></nav>
    <header class="page-heading">
      <div><p class="eyebrow">{{ tr('第十二课 · 企业知识接入实验室', 'LESSON 12 · KNOWLEDGE INGESTION LAB') }}</p>
        <h1>{{ tr('让知识有来源、有版本', 'Knowledge with provenance') }}</h1>
        <p class="subtitle">{{ tr('导入原文 → 发布有效版本 → 查看分块 → 检索来源，观察真实数据库中的变化。', 'Import source → publish an active version → inspect chunks → retrieve with provenance.') }}</p>
      </div>
      <button type="button" :disabled="!!busy" @click="refresh">{{ busy === 'refresh' ? tr('连接中…', 'Connecting…') : tr('刷新连接与文档', 'Refresh connection & documents') }}</button>
    </header>

    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>

    <section class="storage-strip" :class="{ connected: status }" :aria-label="tr('数据库状态', 'Database status')">
      <div><span class="status-dot"></span><strong>{{ status?.storage || tr('数据库尚未确认连接', 'Database connection unconfirmed') }}</strong><small>{{ status ? `PostgreSQL ${status.postgresVersion} · pgvector ${status.vectorVersion}` : tr('连接失败时不会自动回退内存', 'No automatic in-memory fallback') }}</small></div>
      <div><span class="metric">{{ status ? activeDocuments : '—' }}</span><small>{{ tr('有效文档', 'Active documents') }}</small></div>
      <div><strong>{{ tr(embeddingView.title.zh, embeddingView.title.en) }}</strong><small>{{ status ? `${status.embeddingMode} · ${status.dimensions}D` : tr('等待服务端返回模型与维度', 'Waiting for the service model and dimensions') }}</small></div>
      <div><strong>{{ status?.tenantId || 'demo-enterprise' }}</strong><small>{{ tr('固定教学租户 · 尚非权限隔离界面', 'Fixed teaching tenant · not an authorization UI') }}</small></div>
    </section>

    <div class="workspace">
      <section class="panel editor">
        <div class="panel-heading"><div><span class="step">01</span><h2>{{ tr('导入与更新', 'Import & update') }}</h2></div><button class="text-button" type="button" :disabled="!!busy" @click="fillExample">{{ tr('填入 VPN 示例', 'Load VPN example') }}</button></div>
        <form @submit.prevent="publish">
          <label for="document-id">{{ tr('文档 ID（更新时保持不变）', 'Document ID (keep it for updates)') }}</label>
          <input id="document-id" v-model="documentId" required maxlength="128" :disabled="!!busy" autocomplete="off" />
          <label for="source-name">{{ tr('来源名称', 'Source name') }}</label>
          <input id="source-name" v-model="sourceName" required maxlength="240" :disabled="!!busy" />
          <label for="source-content">{{ tr('Markdown 原文', 'Markdown source') }}</label>
          <textarea id="source-content" v-model="content" required :disabled="!!busy" rows="12" :placeholder="tr('粘贴教学资料。不填写服务器文件路径，不上传密码或密钥。', 'Paste teaching material. Never enter server paths, passwords or secrets.')"></textarea>
          <p class="hint">{{ tr('同一 ID + 相同原文：去重；原文变化：发布新版本。导入失败时以服务端结果为准。', 'Same ID + same source: deduplicate. Changed source: publish a version. Failures remain visible.') }}</p>
          <button class="primary" type="submit" :disabled="!!busy || !documentId.trim() || !sourceName.trim() || !content.trim()">{{ busy === 'import' ? tr('正在导入…', 'Importing…') : tr('导入 / 发布版本', 'Import / publish version') }}</button>
        </form>
      </section>

      <section class="panel document-panel">
        <div class="panel-heading"><div><span class="step">02</span><h2>{{ tr('文档清单', 'Document inventory') }}</h2></div><span class="count">{{ status ? documents.length : '—' }}</span></div>
        <p v-if="!status" class="empty">{{ tr('连接数据库后读取文档清单。连接错误显示在页面上方。', 'Connect to load the inventory. Any connection error appears above.') }}</p>
        <p v-else-if="!documents.length" class="empty">{{ tr('还没有资料。先填入示例，再导入第一份文档。', 'No documents yet. Load the example and import your first source.') }}</p>
        <div v-else class="document-list">
          <article v-for="doc in documents" :key="doc.documentId" class="document-row" :class="{ selected: detail?.document.documentId === doc.documentId }">
            <div class="doc-top"><strong>{{ doc.sourceName }}</strong><span class="badge" :class="{ muted: doc.status !== 'ACTIVE' }">{{ doc.status }}</span></div>
            <code>{{ doc.documentId }}</code>
            <p class="doc-meta">{{ tr('版本', 'Version') }} {{ doc.version }} · {{ doc.chunkCount }} {{ tr('个分块', 'chunks') }}</p>
            <p v-if="doc.errorCode" class="error-code">{{ doc.errorCode }}</p>
            <div class="actions"><button type="button" :disabled="!!busy" @click="openDocument(doc.documentId)">{{ tr('原文与分块', 'Source & chunks') }}</button><button v-if="doc.status !== 'DELETED'" type="button" class="danger-text" :disabled="!!busy" @click="deleteTarget = doc">{{ tr('删除', 'Delete') }}</button></div>
          </article>
        </div>
      </section>
    </div>

    <section v-if="detail" class="panel detail-panel">
      <div class="panel-heading"><div><span class="step">↳</span><h2>{{ detail.document.sourceName }}</h2></div><div class="actions"><button type="button" :disabled="!!busy || detail.document.status === 'DELETED'" @click="editDocument">{{ tr('载入编辑 / 重导入', 'Edit / reimport') }}</button><button type="button" @click="detail = null">{{ tr('关闭详情', 'Close details') }}</button></div></div>
      <p class="hint"><code>{{ detail.document.documentId }}</code> · {{ tr('当前有效修订', 'Active revision') }} <code>{{ detail.document.activeRevision || '—' }}</code></p>
      <div class="tabs" :aria-label="tr('文档内容切换', 'Document content')"><button type="button" :aria-pressed="detailTab === 'chunks'" @click="detailTab = 'chunks'">{{ tr('分块与位置', 'Chunks & positions') }} ({{ detail.chunks.length }})</button><button type="button" :aria-pressed="detailTab === 'raw'" @click="detailTab = 'raw'">{{ tr('完整原文', 'Full source') }}</button></div>
      <pre v-if="detailTab === 'raw'" class="source-block">{{ detail.rawText || tr('无有效原文', 'No active source') }}</pre>
      <div v-else class="chunk-grid"><article v-for="chunk in detail.chunks" :key="chunk.chunkId" class="chunk"><p class="chunk-heading">#{{ chunk.ordinal }} · {{ tr('行', 'Lines') }} {{ chunk.startLine }}–{{ chunk.endLine }}</p><p v-if="chunk.titlePath" class="hint">{{ chunk.titlePath }}</p><pre>{{ chunk.text }}</pre><small>UTF-16 [{{ chunk.startUtf16 }}, {{ chunk.endUtf16 }}) · {{ chunk.chunkId }}</small></article><p v-if="!detail.chunks.length" class="empty">{{ tr('当前没有有效分块。', 'No active chunks.') }}</p></div>
    </section>

    <section class="panel retrieval-panel">
      <div class="panel-heading"><div><span class="step">03</span><h2>{{ tr('只读检索与来源核对', 'Read-only retrieval & provenance') }}</h2></div><span class="badge muted">{{ tr('不调用聊天模型', 'No chat model call') }}</span></div>
      <p class="hint retrieval-warning">{{ tr(embeddingView.warning.zh, embeddingView.warning.en) }}</p>
      <form class="search-form" @submit.prevent="search"><label class="search-input" for="search-query"><span>{{ tr('检索问题', 'Search query') }}</span><input id="search-query" v-model="query" required :disabled="!!busy" /></label><label for="search-limit">Top K<select id="search-limit" v-model="limit" :disabled="!!busy"><option :value="1">1</option><option :value="3">3</option><option :value="5">5</option><option :value="10">10</option></select></label><button class="primary" type="submit" :disabled="!!busy || !query.trim()">{{ busy === 'search' ? tr('检索中…', 'Searching…') : tr('检索有效版本', 'Search active versions') }}</button></form>
      <div v-if="result" class="search-results" aria-live="polite"><p class="result-summary">{{ result.hits.length }} {{ tr('条来源片段', 'source chunks') }} · {{ result.embeddingMode }}</p><p v-if="!result.hits.length" class="empty">{{ tr('没有有效片段。请先导入资料，或检查资料是否已删除。', 'No active chunks. Import a source or check whether it was deleted.') }}</p><article v-for="(hit, index) in result.hits" :key="hit.chunkId" class="search-hit"><div class="doc-top"><strong>#{{ index + 1 }} · {{ hit.documentId }}</strong><span>{{ tr('距离', 'Distance') }} {{ Number(hit.distance).toFixed(4) }}</span></div><pre>{{ hit.text }}</pre><div class="hit-footer"><small>{{ tr('行', 'Lines') }} {{ hit.startLine }}–{{ hit.endLine }} · {{ tr('修订', 'Revision') }} {{ hit.revisionId }}</small><button type="button" :disabled="!!busy" @click="openDocument(hit.documentId)">{{ tr('核对原文', 'Inspect source') }} ↗</button></div></article></div>
    </section>

    <RetrievalLab />
    <RetrievalEvaluation />

    <div v-if="deleteTarget" class="modal-backdrop" @keydown.esc="!busy && (deleteTarget = null)" @keydown="keepDialogFocus">
      <section ref="deleteDialog" class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title" tabindex="-1"><h2 id="delete-title">{{ tr('删除这份知识资料？', 'Delete this source?') }}</h2><p><strong>{{ deleteTarget.sourceName }}</strong></p><p>{{ tr('服务端确认删除后，该文档的旧分块不应再被检索。删除失败会保留此窗口和错误提示。', 'After server confirmation, old chunks must no longer be retrieved. On failure this dialog stays open with the error.') }}</p><p v-if="error" class="message error" role="alert">{{ error }}</p><div class="actions"><button type="button" :disabled="!!busy" @click="deleteTarget = null">{{ tr('取消', 'Cancel') }}</button><button class="danger" type="button" :disabled="!!busy" @click="removeDocument">{{ busy === 'delete' ? tr('正在删除…', 'Deleting…') : tr('确认删除', 'Confirm deletion') }}</button></div></section>
    </div>
  </section>
</template>

<style scoped>
.knowledge-shortcuts{display:flex;gap:18px;flex-wrap:wrap;margin:8px 0 20px}.knowledge-shortcuts a{font-size:12px;color:#377764;text-underline-offset:4px}

.knowledge-page{--ink:#243c36;--green:#377764;--line:#e2e8e1;color:var(--ink);max-width:1200px;margin:0 auto}.page-heading{display:flex;justify-content:space-between;align-items:center;gap:24px;margin:22px 0 28px}.eyebrow{font-size:12px;letter-spacing:.12em;color:var(--green);font-weight:700;margin:0 0 12px}h1{font-size:32px;letter-spacing:-.04em;margin:0 0 12px;font-weight:650}.subtitle{font-size:14px;color:#68766f;max-width:720px;line-height:1.8;margin:0}button{border:1px solid var(--line);border-radius:8px;padding:9px 13px;background:#fff;color:var(--ink);font-size:12px;cursor:pointer;line-height:1.4}button:hover:not(:disabled){border-color:var(--green);background:#f2f8f4}button:disabled{opacity:.5;cursor:wait}button:focus-visible,input:focus-visible,textarea:focus-visible,select:focus-visible{outline:3px solid #7dafa0;outline-offset:2px}.primary{background:var(--green);color:#fff;border-color:var(--green)}.primary:hover:not(:disabled){background:#285e4f;color:white}.storage-strip{border:1px solid var(--line);background:#f0f4ef;display:grid;grid-template-columns:1.5fr .6fr 1.3fr 1.2fr;border-radius:12px;padding:20px;gap:20px;margin-bottom:24px}.storage-strip>div{position:relative;display:flex;flex-direction:column;gap:7px;font-size:12px}.storage-strip>div:first-child{padding-left:17px}.storage-strip small{font-size:10px;color:#6a7971;overflow-wrap:anywhere;line-height:1.6}.status-dot{position:absolute;left:0;top:4px;width:7px;height:7px;background:#ad7867;border-radius:100%}.connected .status-dot{background:#3b9476;box-shadow:0 0 0 4px #3b947616}.metric{font-size:24px;line-height:1;font-weight:650}.workspace{display:grid;grid-template-columns:1.04fr 1fr;gap:24px}.panel{background:#fffdf9;border:1px solid var(--line);padding:24px;border-radius:12px;margin-bottom:24px;min-width:0}.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:20px}.panel-heading>div:first-child{display:flex;align-items:center;gap:10px;min-width:0}.panel-heading h2{font-size:17px;font-weight:600;margin:0;overflow-wrap:anywhere}.step{color:#6d9b86;font-size:12px;font-weight:700}.text-button{border:0;padding:4px;color:var(--green);background:none}.count{font-size:12px;border-radius:20px;background:#edf1eb;padding:4px 9px}label{display:block;font-size:12px;font-weight:600;margin-bottom:7px}input,textarea,select{display:block;width:100%;border:1px solid #dbe2db;border-radius:7px;padding:10px 12px;background:#fff;color:var(--ink);font:inherit;font-size:13px;margin-bottom:15px;box-sizing:border-box}textarea{resize:vertical;line-height:1.7;min-height:210px}.hint{color:#6c7971;line-height:1.7;font-size:11px;margin:10px 0 16px}.message{padding:13px 16px;border-radius:8px;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.error{color:#954d3c;background:#fff0e9;border:1px solid #e9cabc}.success{color:#32644f;background:#edf7ee;border:1px solid #cfe3d0}.empty{padding:36px 16px;text-align:center;font-size:13px;color:#7b867f;line-height:1.8}.document-list{max-height:530px;overflow:auto}.document-row{padding:17px 14px;border:1px solid var(--line);border-radius:9px;margin-bottom:12px}.document-row.selected{border-color:#75a28a;background:#f3f8f1}.doc-top{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:8px;font-size:13px}.doc-top strong{overflow-wrap:anywhere}.badge{font-size:10px;letter-spacing:.02em;background:#e5f3e9;color:#347254;padding:4px 7px;border-radius:5px;white-space:nowrap}.badge.muted{background:#eef0eb;color:#6d786f}.doc-meta{font-size:11px;color:#6a7971;margin:9px 0 12px}code{font-size:10px;color:#697b73;overflow-wrap:anywhere}.actions{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.danger-text{color:#a75643;border-color:transparent;background:none}.error-code{color:#a75643;font-size:11px}.tabs{display:flex;gap:7px;margin:14px 0}.tabs [aria-pressed=true]{color:var(--green);background:#e8f1e8;border-color:#a5c3ae}.source-block,.chunk pre,.search-hit pre{white-space:pre-wrap;overflow-wrap:anywhere;font-family:inherit;font-size:13px;line-height:1.8;margin:0}.source-block{padding:20px;background:#f5f5ef;border-radius:8px;max-height:400px;overflow:auto}.chunk-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;max-height:500px;overflow:auto}.chunk{padding:17px;border:1px solid var(--line);background:#f8f9f4;border-radius:8px}.chunk-heading{font-size:11px;color:var(--green);font-weight:600;margin:0 0 10px}.chunk small{display:block;margin-top:14px;font-size:9px;color:#829087;overflow-wrap:anywhere}.retrieval-warning{background:#f6f2e8;color:#7d6948;padding:11px 13px;border-radius:6px}.search-form{display:flex;align-items:end;gap:12px}.search-input{flex:1}.search-form label{margin:0}.search-form input,.search-form select{margin:7px 0 0}.search-form button{min-height:39px}.search-results{margin-top:22px}.result-summary{font-size:11px;color:#77867d}.search-hit{border-top:1px solid var(--line);padding:20px 0}.search-hit .doc-top>span{font-size:11px;color:#78867d}.hit-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:16px}.hit-footer small{font-size:10px;color:#7a887f;overflow-wrap:anywhere}.modal-backdrop{position:fixed;inset:0;background:#162d2680;z-index:200;display:grid;place-items:center;padding:20px}.confirm-dialog{max-width:470px;width:100%;padding:28px;background:#fffdf9;border-radius:14px;box-shadow:0 24px 80px #142a2633}.confirm-dialog h2{font-size:20px;margin:0 0 20px}.confirm-dialog p{font-size:13px;line-height:1.8}.confirm-dialog .actions{justify-content:flex-end;margin-top:24px}.danger{background:#a45b47;color:#fff;border-color:#a45b47}@media(max-width:900px){.storage-strip{grid-template-columns:1fr 1fr}.page-heading{align-items:start}.workspace{gap:16px}.panel{padding:18px}}@media(max-width:680px){.page-heading{flex-direction:column;gap:16px}h1{font-size:26px}.storage-strip{gap:18px 12px;padding:17px}.workspace{grid-template-columns:1fr;gap:0}.panel-heading{align-items:start;flex-wrap:wrap}.chunk-grid{grid-template-columns:1fr}.search-form{flex-wrap:wrap}.search-input{flex-basis:100%}.search-form>button{flex:1}.hit-footer{align-items:start;flex-direction:column}.panel-heading h2{font-size:15px}}
</style>
