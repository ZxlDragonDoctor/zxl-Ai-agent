<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../i18n'
import type { WorkflowReport } from '../types/workflow'

const props = defineProps<{ report: WorkflowReport }>()
const completed = computed(() => props.report.tasks.filter(task => task.status === 'COMPLETED').length)
const modelCalls = computed(() => props.report.planningModelCalls + props.report.workerModelCalls + props.report.synthesisModelCalls)
const taskNumber = (id: string) => `#${props.report.tasks.findIndex(task => task.id === id) + 1}`
const showValue = (value: unknown) => typeof value === 'string' ? value : JSON.stringify(value, null, 2)
</script>

<template>
  <section class="workflow-report" :aria-label="t('workflow.title')">
    <header class="report-heading">
      <div>
        <span class="eyebrow">{{ t('workflow.title') }}</span>
        <h3>{{ t(`workflow.${report.status}`) }}</h3>
      </div>
      <span class="report-status" :class="report.status">{{ completed }} / {{ report.tasks.length }} {{ t('workflow.done') }}</span>
    </header>
    <div class="report-metrics">
      <span>{{ t('workflow.calls') }} <strong>{{ modelCalls }}</strong></span>
      <span>{{ t('workflow.planner') }} {{ report.planningModelCalls }} · {{ t('workflow.workers') }} {{ report.workerModelCalls }} · {{ t('workflow.synthesis') }} {{ report.synthesisModelCalls }}</span>
      <span v-if="report.finishedAt">{{ t('workflow.duration') }} {{ ((report.finishedAt - report.startedAt) / 1000).toFixed(1) }}s</span>
    </div>
    <p v-if="!report.tasks.length" class="empty-plan">{{ t('workflow.awaitPlan') }}</p>
    <ol class="task-flow">
      <li v-for="(task, index) in report.tasks" :key="task.id" class="flow-task" :class="task.status.toLowerCase()">
        <div class="task-heading">
          <span class="task-index">{{ index + 1 }}</span>
          <h4>{{ task.description }}</h4>
          <span class="task-state">{{ t(`workflow.task.${task.status}`) }}</span>
        </div>
        <div class="task-meta">
          <span>{{ t(`agent.${task.capability}`) }} <small v-if="task.assignedAgentId">· {{ task.assignedAgentId }}</small></span>
          <span>{{ t('workflow.depends') }} {{ task.dependencies.length ? task.dependencies.map(taskNumber).join(', ') : t('workflow.noDependencies') }}</span>
        </div>
        <details v-if="task.executionContext" class="context-detail">
          <summary>{{ t('workflow.actualInput') }} · {{ task.executionContext.dependencyResults.length }} {{ t('workflow.upstreamCount') }}</summary>
          <div class="context-body">
            <h5>{{ t('workflow.originalGoal') }}</h5>
            <p>{{ task.executionContext.originalGoal }}</p>
            <h5>{{ t('workflow.material') }}</h5>
            <dl v-for="(value, key) in task.executionContext.userInput" :key="key">
              <dt>{{ key }}</dt><dd>{{ showValue(value) || t('workflow.empty') }}</dd>
            </dl>
            <article v-for="upstream in task.executionContext.dependencyResults" :key="upstream.taskId" class="upstream-output">
              <h5>{{ taskNumber(upstream.taskId) }} · {{ upstream.description }}</h5>
              <small>{{ upstream.agentId }}</small>
              <pre>{{ upstream.output }}</pre>
            </article>
            <p v-if="!task.executionContext.dependencyResults.length" class="muted">{{ t('workflow.noUpstream') }}</p>
          </div>
        </details>
        <details v-if="task.result" class="context-detail">
          <summary>{{ t('workflow.output') }} · {{ task.modelCalls }} {{ t('workflow.callUnit') }}</summary>
          <pre class="task-output">{{ task.result }}</pre>
        </details>
        <p v-if="task.error" class="task-error">{{ task.errorCode }} · {{ task.error }}</p>
      </li>
    </ol>
    <p v-if="report.error" class="task-error" role="alert">{{ report.error.code }} · {{ report.error.path }} · {{ report.error.message }}</p>
  </section>
</template>

<style scoped>
.workflow-report { border: 1px solid var(--color-border); border-radius: 14px; overflow: hidden; background: var(--color-bg); }
.report-heading { padding: 20px 22px 12px; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.eyebrow { font-size: 11px; color: var(--color-text-tertiary); letter-spacing: .08em; }
h3 { margin: 4px 0 0; font-size: 19px; color: var(--color-text); }
.report-status { padding: 6px 10px; border-radius: 6px; background: var(--color-bg-alt); white-space: nowrap; font-size: 12px; }
.report-status.completed { color: var(--color-success); background: var(--color-success-light); }
.report-status.failed { color: var(--color-error); background: var(--color-error-light); }
.report-metrics { display: flex; flex-wrap: wrap; gap: 8px 20px; padding: 0 22px 18px; color: var(--color-text-secondary); font-size: 12px; border-bottom: 1px solid var(--color-border-light); }
.report-metrics strong { color: var(--color-primary); padding-left: 5px; }
.task-flow { list-style: none; margin: 0; padding: 12px; display: grid; gap: 10px; }
.flow-task { padding: 16px; background: var(--color-surface); border: 1px solid var(--color-border-light); border-left: 3px solid var(--color-border); border-radius: 9px; min-width: 0; }
.flow-task.completed { border-left-color: var(--color-success); }
.flow-task.in_progress { border-left-color: var(--color-primary); }
.flow-task.failed, .flow-task.blocked { border-left-color: var(--color-error); }
.task-heading { display: flex; align-items: flex-start; gap: 10px; }
.task-index { display: grid; place-items: center; flex-shrink: 0; width: 24px; height: 24px; border: 1px solid var(--color-border); border-radius: 50%; font-size: 11px; color: var(--color-text-secondary); }
h4 { margin: 2px 0 0; flex: 1; font-size: 13px; font-weight: 600; line-height: 1.65; overflow-wrap: anywhere; }
.task-state { font-size: 11px; white-space: nowrap; margin-top: 4px; color: var(--color-text-secondary); }
.completed .task-state { color: var(--color-success); }
.failed .task-state, .blocked .task-state, .task-error { color: var(--color-error); }
.task-meta { display: flex; flex-wrap: wrap; gap: 8px 18px; margin: 9px 0 12px 34px; font-size: 11px; color: var(--color-text-tertiary); }
.task-meta small { font-size: inherit; }
summary { cursor: pointer; color: var(--color-text-secondary); font-size: 12px; line-height: 1.8; padding: 6px 0; }
summary:hover { color: var(--color-primary); }
summary:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 3px; }
.context-detail { border-top: 1px solid var(--color-border-light); }
.context-body { padding: 6px 10px 12px; font-size: 12px; overflow-wrap: anywhere; }
h5 { margin: 8px 0 4px; font-size: 12px; color: var(--color-text); }
p { margin: 4px 0 12px; line-height: 1.7; }
dl { margin: 8px 0 12px; } dt, .muted, small { color: var(--color-text-tertiary); } dd { margin: 4px 0 0; white-space: pre-wrap; line-height: 1.7; }
.upstream-output { margin-top: 12px; border-left: 2px solid var(--color-primary); padding-left: 12px; }
pre { font-family: inherit; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.7; max-height: 320px; overflow: auto; }
.task-output { margin: 8px 0; }
.task-error { margin: 10px 16px; font-size: 12px; overflow-wrap: anywhere; }
.empty-plan { padding: 16px 22px; color: var(--color-text-secondary); font-size: 13px; }
@media (max-width: 600px) { .report-heading { padding: 16px; } .task-heading { flex-wrap: wrap; } .task-state { margin-left: 34px; } .task-meta { margin-left: 0; } .flow-task { padding: 12px; } }
</style>
