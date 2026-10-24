<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { locale } from '../i18n'
import { getKnowledgeDocument } from '../api/knowledge'
import type { KnowledgeDetail } from '../api/knowledge'
import { retrieveKnowledge } from '../api/retrieval'
import type { RetrievalHit, RetrievalOptions, RetrievalResult } from '../api/retrieval'
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const options = ref<RetrievalOptions>({ query: 'VPN报错809，先检查什么？', mode: 'hybrid', topK: 3, candidateLimit: 20, rerank: false, rewrite: false, budgetMs: 5000 })
const result = ref<RetrievalResult | null>(null)
const source = ref<KnowledgeDetail | null>(null)
const selected = ref<RetrievalHit | null>(null)
const sourceReader = ref<HTMLElement>()
const busy = ref('')
const error = ref('')
const signature = computed(() => JSON.stringify(options.value))
watch(signature, () => { result.value = null; source.value = null; selected.value = null; error.value = '' })
const modeName = (mode: string) => mode === 'vector' ? tr('精确向量', 'Exact vector') : mode === 'keyword' ? tr('关键词子串', 'Keyword substring') : tr('RRF 混合', 'RRF hybrid')
const score = (value: number | null | undefined, digits = 4) => value == null ? '—' : Number(value).toFixed(digits)
async function search() {
  if (busy.value || !options.value.query.trim()) return
  busy.value = 'search'; error.value = ''; result.value = null; source.value = null
  try { result.value = await retrieveKnowledge({ ...options.value, query: options.value.query.trim() }) }
  catch (reason) { error.value = reason instanceof Error ? reason.message : tr('检索未成功', 'Retrieval failed') }
  finally { busy.value = '' }
}
function clear() { result.value = null; source.value = null; selected.value = null; error.value = '' }
async function inspect(hit: RetrievalHit) {
  busy.value = 'source'; error.value = ''; source.value = null; selected.value = hit
  try {
    const loaded = await getKnowledgeDocument(hit.documentId)
    if (loaded.document.activeRevision !== hit.revisionId || !loaded.chunks.some(chunk => chunk.chunkId === hit.chunkId)) throw new Error(tr('来源版本已变化，请重新检索。', 'Source version changed. Search again.'))
    source.value = loaded
    await nextTick()
    sourceReader.value?.scrollIntoView({ block: 'center', behavior: 'instant' })
  } catch (reason) { error.value = reason instanceof Error ? reason.message : tr('来源读取失败', 'Source unavailable') }
  finally { busy.value = '' }
}
</script>

<template>
  <section class="retrieval-lab" aria-labelledby="retrieval-lab-title" :aria-busy="!!busy">
    <header class="lab-heading"><div><p class="eyebrow">{{ tr('第十三课 · 检索实验室', 'LESSON 13 · RETRIEVAL LAB') }}</p><h2 id="retrieval-lab-title">{{ tr('看看证据是怎样被找到的', 'See how evidence is retrieved') }}</h2><p>{{ tr('同一份有效知识，比较不同路径。保留原问题，核对真实阶段与来源。', 'Compare retrieval paths over active knowledge. Keep the original question and inspect actual stages and sources.') }}</p></div><span class="pg-badge">PostgreSQL + pgvector</span></header>
    <div class="lab-workspace">
      <form class="query-panel" @submit.prevent="search">
        <label for="lab-query">{{ tr('原问题', 'Original question') }}</label><textarea id="lab-query" v-model="options.query" :disabled="!!busy" maxlength="2000" required rows="3" />
        <fieldset :disabled="!!busy"><legend>{{ tr('检索方案', 'Retrieval method') }}</legend><div class="mode-buttons"><button v-for="mode in (['vector', 'keyword', 'hybrid'] as const)" :key="mode" type="button" :aria-pressed="options.mode === mode" @click="options.mode = mode">{{ modeName(mode) }}</button></div></fieldset>
        <div class="option-grid"><label for="lab-k">Top K<select id="lab-k" v-model.number="options.topK" :disabled="!!busy"><option v-for="k in [1, 3, 5]" :key="k" :value="k">{{ k }}</option></select></label><label for="lab-candidates">{{ tr('每路候选上限', 'Candidates per channel') }}<select id="lab-candidates" v-model.number="options.candidateLimit" :disabled="!!busy"><option v-for="n in [5, 10, 20]" :key="n" :value="n">{{ n }}</option></select></label></div>
        <label for="lab-budget">{{ tr('总预算', 'Total budget') }}<select id="lab-budget" v-model.number="options.budgetMs" :disabled="!!busy"><option :value="100">100 ms</option><option :value="1000">1 s</option><option :value="5000">5 s</option><option :value="10000">10 s</option></select></label>
        <label class="check"><input v-model="options.rewrite" type="checkbox" :disabled="!!busy" />{{ tr('词汇补充改写（最多一次）', 'Glossary expansion (once)') }}</label><label class="check"><input v-model="options.rerank" type="checkbox" :disabled="!!busy" />{{ tr('启用重排', 'Enable reranking') }}</label>
        <p class="boundary">{{ tr('关键词为应用切词后的子串基线，非 BM25；本地规则重排不等于模型重排。实际方式以结果为准。', 'Keywords use a tokenized substring baseline, not BM25. Local rules are not a model reranker. Results show what actually ran.') }}</p>
        <div class="actions"><button type="submit" class="primary" :disabled="!!busy || !options.query.trim()">{{ busy === 'search' ? tr('正在检索…', 'Retrieving…') : tr('运行检索', 'Run retrieval') }}</button><button type="button" :disabled="!!busy" @click="clear">{{ tr('清空检索结果', 'Clear retrieval results') }}</button></div>
      </form>
      <div class="evidence-panel" aria-live="polite">
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div v-if="!result" class="waiting"><span>⌕</span><h3>{{ busy === 'search' ? tr('正在执行检索阶段', 'Retrieval stages are running') : error ? tr('本次检索未完成', 'Retrieval did not complete') : tr('从一个真实问题开始', 'Start with a real question') }}</h3><p>{{ tr('运行后显示候选、实际耗时及降级原因。修改条件会清除旧结果。', 'Run to inspect evidence, timing and fallback reasons. Editing options clears previous results.') }}</p></div>
        <template v-else>
          <div class="result-heading"><div><strong>{{ modeName(result.mode) }}</strong><small>{{ result.hits.length }} {{ tr('条证据', 'pieces of evidence') }}</small></div><strong class="duration">{{ score(result.elapsedMs, 0) }} <small>ms</small></strong></div>
          <dl class="request-meta"><div><dt>{{ tr('原问题', 'Original') }}</dt><dd>{{ result.query }}</dd></div><div v-if="result.rewrittenQuery"><dt>{{ tr('补充查询', 'Expanded query') }}</dt><dd>{{ result.rewrittenQuery }}</dd></div><div><dt>Embedding</dt><dd>{{ result.embeddingMode }}</dd></div><div><dt>{{ tr('重排器', 'Reranker') }}</dt><dd>{{ result.reranker || tr('未启用', 'Disabled') }}</dd></div></dl>
          <ol class="stage-list" aria-label="Retrieval stages"><li v-for="(stage, index) in result.stages" :key="`${stage.name}-${index}`"><span class="stage-number">{{ index + 1 }}</span><span><strong>{{ stage.name }}</strong><small>{{ stage.status }} · {{ stage.candidateCount }} {{ tr('候选', 'candidates') }}</small></span><small>{{ score(stage.elapsedMs, 0) }} ms</small></li></ol>
          <p v-for="reason in result.degradations" :key="reason" class="warning">{{ tr('明确降级', 'Explicit fallback') }} · {{ reason }}</p><p v-if="result.shortageReason" class="warning">{{ tr('候选不足', 'Candidate shortage') }} · {{ result.shortageReason }}</p>
          <p v-if="!result.hits.length" class="no-hits">{{ tr('查询成功，但没有当前可用的候选证据。', 'The query succeeded, but no currently usable evidence was found.') }}</p>
          <article v-for="(hit, index) in result.hits" :key="hit.chunkId" class="evidence-card"><header><span class="rank">{{ String(index + 1).padStart(2, '0') }}</span><div><strong>{{ hit.documentId }}</strong><small>{{ tr('版本', 'Generation') }} {{ hit.generation }} · {{ tr('行', 'Lines') }} {{ hit.startLine }}–{{ hit.endLine }}</small></div><div class="channels"><span v-for="channel in hit.channels" :key="channel">{{ channel }}</span></div></header><pre>{{ hit.text }}</pre><div class="scores"><span>{{ tr('向量名次', 'Vector rank') }} <b>{{ hit.vectorRank ?? '—' }}</b></span><span>{{ tr('关键词名次', 'Keyword rank') }} <b>{{ hit.keywordRank ?? '—' }}</b></span><span>RRF <b>{{ score(hit.fusionScore) }}</b></span><span>{{ tr('重排分', 'Rerank score') }} <b>{{ score(hit.rerankScore) }}</b></span></div><footer><small>{{ tr('余弦距离', 'Cosine distance') }} {{ score(hit.distance) }}</small><button type="button" :disabled="!!busy" @click="inspect(hit)">{{ tr('查看此证据原文', 'Inspect evidence source') }} ↗</button></footer></article>
          <p class="trace">Trace · {{ result.traceId }}</p>
        </template>
      </div>
    </div>
    <section v-if="source && selected" ref="sourceReader" class="source-reader" aria-label="Retrieval source"><header><div><p class="eyebrow">{{ tr('当前修订已核对', 'ACTIVE REVISION VERIFIED') }}</p><h3>{{ source.document.sourceName }}</h3></div><button type="button" @click="source = null; selected = null">{{ tr('关闭证据原文', 'Close evidence source') }}</button></header><p>{{ tr('命中行', 'Matched lines') }} {{ selected.startLine }}–{{ selected.endLine }} · {{ selected.revisionId }}</p><pre>{{ source.rawText }}</pre></section>
  </section>
</template>

<style scoped>
h2{scroll-margin-top:100px}

.retrieval-lab{--ink:#243c36;--green:#377764;--line:#dfe7df;color:var(--ink);margin:40px 0}.lab-heading{display:flex;justify-content:space-between;gap:24px;margin-bottom:22px}.eyebrow{color:var(--green);font-size:11px;letter-spacing:.12em;font-weight:700;margin:0 0 9px}.lab-heading h2{font-size:27px;letter-spacing:-.035em;margin:0 0 12px}.lab-heading p:not(.eyebrow){font-size:13px;line-height:1.8;color:#718077;max-width:720px}.pg-badge{border:1px solid #bdd3c4;border-radius:20px;padding:7px 12px;white-space:nowrap;color:var(--green);font-size:11px;height:fit-content}.lab-workspace{display:grid;grid-template-columns:320px minmax(0,1fr);border:1px solid var(--line);border-radius:14px;background:#fffdf9;overflow:hidden}.query-panel{background:#f3f6ef;padding:24px;border-right:1px solid var(--line)}label,legend{font-size:12px;font-weight:600}label:not(.check){display:block;margin-bottom:9px}textarea,select{box-sizing:border-box;width:100%;border:1px solid #d5e0d5;background:white;border-radius:7px;padding:10px;font:inherit;font-size:13px;color:var(--ink);margin:7px 0 15px}textarea{line-height:1.8;resize:vertical;min-height:105px}fieldset{padding:0;border:0;margin:0 0 20px;min-width:0}legend{margin-bottom:10px}.mode-buttons,.actions{display:flex;flex-wrap:wrap;gap:6px}button{border:1px solid var(--line);background:white;color:var(--ink);border-radius:7px;padding:9px 11px;font:inherit;font-size:11px;cursor:pointer}button:hover:not(:disabled){background:#edf5ed;border-color:#83ae96}button[aria-pressed=true],.primary{background:var(--green);color:white;border-color:var(--green)}.primary:hover:not(:disabled),button[aria-pressed=true]:hover:not(:disabled){background:#285e4f;color:white}button:disabled{opacity:.5;cursor:wait}button:focus-visible,select:focus-visible,textarea:focus-visible,input:focus-visible{outline:3px solid #7dafa0;outline-offset:2px}.option-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.check{display:flex;align-items:center;gap:9px;margin:15px 0}.check input{accent-color:var(--green);width:16px;height:16px}.boundary{font-size:11px;color:#768074;line-height:1.8;border-top:1px solid var(--line);padding-top:16px;margin-top:24px}.evidence-panel{padding:24px;min-width:0;min-height:450px}.waiting{padding:70px 24px;text-align:center;color:#78877e}.waiting>span{font-size:54px;color:#a4b9a9}.waiting h3{font-size:17px;color:#486154;font-weight:500}.waiting p{font-size:12px;line-height:1.8;max-width:320px;margin:auto}.result-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px}.result-heading>div{display:flex;gap:12px;align-items:center}.result-heading small{font-size:11px;color:#75877b}.duration{font-size:25px;font-weight:500;font-variant-numeric:tabular-nums}.request-meta{font-size:11px;line-height:1.7;margin:0 0 20px}.request-meta>div{display:grid;grid-template-columns:80px 1fr;margin:5px 0}.request-meta dt{color:#7e8d82}.request-meta dd{margin:0;overflow-wrap:anywhere}.stage-list{padding:0;list-style:none;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:0 0 20px}.stage-list li{display:flex;gap:8px;align-items:center;background:#f3f6f0;border-radius:7px;padding:10px}.stage-number{background:#e1ebdf;border-radius:50%;width:20px;height:20px;display:grid;place-items:center;font-size:10px;flex-shrink:0}.stage-list li>span:nth-child(2){flex:1;min-width:0}.stage-list strong{font-size:10px;overflow-wrap:anywhere}.stage-list small{display:block;font-size:9px;color:#78877b;line-height:1.6}.warning,.error{font-size:12px;line-height:1.7;padding:12px;border-radius:7px;overflow-wrap:anywhere}.warning{background:#f7f0df;color:#826c3e}.error{background:#fff0e9;color:#954d3c;border:1px solid #e9cabc}.no-hits{padding:28px;color:#718077;font-size:13px}.evidence-card{border-top:1px solid var(--line);padding:21px 0}.evidence-card header{display:flex;align-items:center;gap:12px}.rank{font-size:23px;font-weight:400;color:#83a28a;font-variant-numeric:tabular-nums}.evidence-card header>div:nth-child(2){flex:1;min-width:0}.evidence-card strong{font-size:13px;overflow-wrap:anywhere}.evidence-card header small{display:block;color:#819085;font-size:10px;margin-top:5px}.channels{display:flex;gap:4px;flex-wrap:wrap}.channels span{font-size:9px;background:#e8f2e7;color:#407755;padding:3px 6px;border-radius:4px}.evidence-card pre,.source-reader pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;font-size:13px;line-height:1.8;margin:16px 0;color:#3d5144}.scores{display:flex;gap:14px;flex-wrap:wrap;color:#859084;font-size:10px}.scores b{font-weight:500;color:#4f6c58;margin-left:3px}.evidence-card footer{display:flex;justify-content:space-between;align-items:center;margin-top:15px;gap:12px}.evidence-card footer small{color:#8a968b;font-size:10px}.trace{font-size:9px;color:#9aa49b;overflow-wrap:anywhere}.source-reader{border:1px solid #abc6b1;border-radius:12px;background:#f5f8ef;padding:24px;margin-top:20px}.source-reader header{display:flex;justify-content:space-between;gap:20px}.source-reader h3{font-size:18px;margin:0}.source-reader>p{font-size:10px;color:#7d8d7e;overflow-wrap:anywhere}.source-reader pre{max-height:360px;overflow:auto}@media(max-width:900px){.lab-workspace{grid-template-columns:270px minmax(0,1fr)}.query-panel,.evidence-panel{padding:18px}.stage-list{grid-template-columns:1fr}}@media(max-width:680px){.lab-heading{flex-direction:column;gap:5px}.lab-heading h2{font-size:24px}.lab-workspace{grid-template-columns:1fr}.query-panel{border-right:0;border-bottom:1px solid var(--line)}.evidence-panel{min-height:200px}.waiting{padding:32px 12px}.evidence-card footer,.source-reader header{align-items:start;flex-direction:column}.result-heading>div{align-items:start;flex-direction:column;gap:5px}}
</style>
