<script setup lang="ts">
import { ref } from 'vue'
import WorkflowReportPanel from '../../src/components/WorkflowReportPanel.vue'
import { toggleLocale, t } from '../../src/i18n'
import type { WorkflowReport } from '../../src/types/workflow'
const cases = [
  { id: 'branch-failure', label: '分支失败与依赖阻塞' },
  { id: 'success', label: '正常完成' },
  { id: 'synthesis-failure', label: '主任务成功、汇总失败' },
  { id: 'planning-failure', label: '规划失败' },
]
    const report = ref<WorkflowReport | null>(null)
    const error = ref('')
    const selected = ref('')
    async function load(id: string) {
      selected.value = id; error.value = ''; report.value = null
      if (id === 'disconnected') { error.value = t('workflow.disconnected'); return }
      try {
        const response = await fetch(`./${id}.generated.json`)
        if (!response.ok) throw new Error('missing fixture')
        report.value = await response.json()
      } catch { error.value = '请先运行课程文档中的验收和准备命令，生成本地执行记录。' }
    }
    void load('branch-failure')

</script>
<template>
    <main style="max-width:1000px;margin:auto;padding:28px 20px">
      <p style="color:var(--color-primary);font-size:12px">第六课 · 本地验收预览</p>
      <h1 style="font-size:24px;margin:8px 0">任务终态与失败因果</h1>
      <p style="font-size:13px;line-height:1.8;color:var(--color-text-secondary)">以下记录来自 Java 可控 Agent 替身的实际执行。这里复用业务页面组件，不调用真实模型。连接中断选项仅预览提示文案。</p>
      <nav aria-label="验收场景" style="display:flex;flex-wrap:wrap;gap:8px;margin:20px 0">
        <button v-for="item in cases" :key="item.id" @click="load(item.id)" :aria-pressed="selected===item.id" style="padding:8px 10px;border:1px solid var(--color-border);border-radius:8px">{{item.label}}</button>
        <button @click="load('disconnected')" style="padding:8px 10px;border:1px solid var(--color-border);border-radius:8px">连接中断文案</button>
        <button @click="toggleLocale" style="padding:8px 10px">中文 / EN</button>
      </nav>
      <WorkflowReportPanel v-if="report" :report="report" />
      <p v-if="error" role="alert" style="padding:18px;border:1px solid var(--color-error);border-radius:8px;color:var(--color-error)">{{error}}</p>
    </main>
</template>
