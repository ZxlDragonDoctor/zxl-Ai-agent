<script setup lang="ts">
import evidence from '../../../docs/images/lesson23-usage-2026-11-20/live-usage.json'
import workflow from '../../../docs/images/lesson23-usage-2026-11-20/http-usage.json'
defineProps<{ zh: boolean }>()
</script>

<template>
  <section class="usage-evidence" :aria-label="zh ? '模型用量验收快照' : 'Model usage acceptance snapshot'">
    <h3>{{ zh ? '一次真实模型调用，怎样记账？' : 'How was one live model call accounted for?' }}</h3>
    <p class="scope">{{ zh ? '历史验收快照 · 直连模型与 PostgreSQL 账本 · 非当前会话' : 'Historical snapshot · Direct provider call and PostgreSQL ledger · Not the current conversation' }}</p>
    <div class="usage-result"><strong>{{ evidence.reportedTotal }}</strong><span>{{ zh ? '供应商返回的 token 用量' : 'Provider-reported tokens' }}</span><b>{{ evidence.call.state }}</b></div>
    <dl>
      <dt>{{ zh ? '模型 / 回答' : 'Model / Answer' }}</dt><dd>{{ evidence.model }} / {{ evidence.answer }}</dd>
      <dt>{{ zh ? '调用前预留' : 'Reserved before dispatch' }}</dt><dd>{{ evidence.call.estimate }} token</dd>
      <dt>{{ zh ? '数据库实际记入' : 'Recorded in PostgreSQL' }}</dt><dd>{{ evidence.account.spent }} token</dd>
      <dt>{{ zh ? '剩余在途预留' : 'Remaining reservation' }}</dt><dd>{{ evidence.account.reserved }} token</dd>
      <dt>{{ zh ? '已受理调用' : 'Accepted calls' }}</dt><dd>{{ evidence.account.calls }}</dd>
    </dl>
    <p>{{ zh ? '返回 usage 与数据库结算一致。token 不是金额，也不等于供应商账单。' : 'Returned usage matches database settlement. Tokens are not currency or a provider invoice.' }}</p>
    <section class="http-usage">
      <h3>{{ zh ? '再验证完整 HTTP 调用链' : 'Then verify the complete HTTP call chain' }}</h3>
      <p class="scope">{{ zh ? '独立账户登录 → 多 Agent → MCP 空检索 → 正确拒答 → PG 结算；历史验收记录。' : 'Fixture login → multi-agent → empty MCP retrieval → grounded abstention → PG settlement. Historical acceptance record.' }}</p>
      <dl>
        <dt>{{ zh ? '工作流结果' : 'Workflow outcome' }}</dt><dd>{{ workflow.status }}</dd>
        <dt>{{ zh ? '已受理模型调用' : 'Accepted model calls' }}</dt><dd>{{ workflow.account.calls }}</dd>
        <dt>{{ zh ? '实际记入总量' : 'Total recorded usage' }}</dt><dd>{{ workflow.account.spent }} token</dd>
        <dt>{{ zh ? '在途预留 / 清理' : 'Reserved / Cleanup' }}</dt><dd>{{ workflow.account.reserved }} / {{ workflow.cleanupPending ? (zh ? '待清理' : 'Pending') : (zh ? '已完成' : 'Complete') }}</dd>
      </dl>
      <p>{{ zh ? '逐次返回用量' : 'Per-call returned usage' }}: {{ workflow.account.entries.map(row => row.actual ?? (zh ? '未知' : 'Unknown')).join(' + ') }} = {{ workflow.account.spent }} token</p>
    </section>
    <p class="scope">{{ zh ? '预算开关默认关闭，验收实例显式开启。以上是每 run 配额阶段的历史记录；新增租户周期机制请阅读本课周期预算文档，其真实 HTTP 验收另记。' : 'Budget enforcement is off by default and explicitly enabled in the acceptance instance. These are historical per-run quota observations. See the tenant-period guide for the new mechanism; its live HTTP acceptance is recorded separately.' }}</p>
  </section>
</template>

<style scoped>
.usage-evidence{margin:14px 0;padding:14px;border:1px solid #b9d6ce;border-radius:10px;background:#f4faf7;color:#254e43;font-size:13px;line-height:1.65}
h3{margin:0 0 6px;font-size:15px}p{margin:8px 0}.scope{font-size:12px;color:#567267}
.usage-result{display:flex;align-items:center;gap:10px;margin:14px 0;flex-wrap:wrap}.usage-result strong{font-size:32px;line-height:1}.usage-result b{font-size:11px;border:1px solid #a1c9b9;border-radius:5px;padding:2px 6px}
dl{display:grid;grid-template-columns:1fr auto;gap:6px 12px;margin:0}dt{color:#4a665b}dd{margin:0;text-align:right;font-variant-numeric:tabular-nums}
.http-usage{margin-top:16px;padding-top:14px;border-top:1px solid #b9d6ce}
</style>
