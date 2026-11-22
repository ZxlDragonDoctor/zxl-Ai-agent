<script setup lang="ts">
import evidence from '../../../docs/images/lesson23-usage-2026-11-20/live-usage.json'
import workflow from '../../../docs/images/lesson23-usage-2026-11-20/http-usage.json'
import periodWorkflow from '../../../docs/images/lesson23-period-live-2026-11-23/http-usage.json'
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
    <section class="http-usage period-usage">
      <h3>{{ zh ? '新验收：运行与租户周期双层结算' : 'New acceptance: run and tenant-period settlement' }}</h3>
      <p class="scope">{{ zh ? '2026-11-23 本地时间 · 新租户真实认证 HTTP / MCP / 模型；历史快照。' : '2026-11-23 local time · Fresh-tenant live authenticated HTTP / MCP / model; historical snapshot.' }}</p>
      <dl>
        <dt>{{ zh ? '归属周期 / 数据库日期（UTC）' : 'Bound period / database date (UTC)' }}</dt><dd>{{ periodWorkflow.account.periodId }} / {{ periodWorkflow.account.databaseUtcDate }}</dd>
        <dt>{{ zh ? '运行已知用量' : 'Recorded run usage' }}</dt><dd>{{ periodWorkflow.account.spent }} token</dd>
        <dt>{{ zh ? '租户周期已知用量' : 'Recorded tenant-period usage' }}</dt><dd>{{ periodWorkflow.account.period.spent }} token</dd>
        <dt>{{ zh ? '运行 / 周期预留' : 'Run / period reservation' }}</dt><dd>{{ periodWorkflow.account.reserved }} / {{ periodWorkflow.account.period.reserved }}</dd>
        <dt>{{ zh ? '运行 / 周期受理次数' : 'Run / period accepted calls' }}</dt><dd>{{ periodWorkflow.account.calls }} / {{ periodWorkflow.account.period.calls }}</dd>
      </dl>
      <p>{{ zh ? '新租户只有本次运行，因此双层余额相同；跨运行竞争和跨日结算另由真实 PG 测试验证。此处不是实时账单。' : 'This fresh tenant has only this run, so both totals match. Cross-run contention and rollover are covered by separate PostgreSQL tests. This is not a live bill.' }}</p>
    </section>
    <p class="scope">{{ zh ? '预算开关默认关闭，验收实例显式开启。验收卡均为历史快照；周期按运行首次初始化UTC日归集。其他模型入口、UNKNOWN运维对账和更多业务场景仍待补齐。' : 'Budget enforcement is off by default and explicitly enabled in the acceptance instance. All cards are historical snapshots. Periods follow the UTC date of first run initialization. Other model entry points, UNKNOWN reconciliation and broader workflows remain incomplete.' }}</p>
  </section>
</template>

<style scoped>
.usage-evidence{margin:14px 0;padding:14px;border:1px solid #b9d6ce;border-radius:10px;background:#f4faf7;color:#254e43;font-size:13px;line-height:1.65}
h3{margin:0 0 6px;font-size:15px}p{margin:8px 0}.scope{font-size:12px;color:#567267}
.usage-result{display:flex;align-items:center;gap:10px;margin:14px 0;flex-wrap:wrap}.usage-result strong{font-size:32px;line-height:1}.usage-result b{font-size:11px;border:1px solid #a1c9b9;border-radius:5px;padding:2px 6px}
dl{display:grid;grid-template-columns:1fr auto;gap:6px 12px;margin:0}dt{color:#4a665b}dd{margin:0;text-align:right;font-variant-numeric:tabular-nums}
.http-usage{margin-top:16px;padding-top:14px;border-top:1px solid #b9d6ce}
</style>
