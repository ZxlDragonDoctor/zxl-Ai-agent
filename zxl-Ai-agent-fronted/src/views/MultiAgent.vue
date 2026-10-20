<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import WorkflowReportPanel from '../components/WorkflowReportPanel.vue'
import type { WorkflowReport } from '../types/workflow'
import {
  orchestrateGoalStream,
  getSystemStatus,
  listAgents,
  getRecentEvents,
  runBenchmark as runBenchmarkApi
} from '../api/multiagent'
import { locale, t } from '../i18n'

const goalInput = ref('')
const isRunning = ref(false)
const orchestrationResult = ref('')
const materialInput = ref('')
const workflowReport = ref<WorkflowReport | null>(null)
const streamError = ref('')
let workflowConnection: { close(): void } | undefined
let workflowGeneration = 0
const progressPercent = computed(() => {
  const tasks = workflowReport.value?.tasks || []
  return tasks.length ? tasks.filter(task => task.status === 'COMPLETED').length / tasks.length * 100 : 0
})
type AgentInfo = { id: string; type: string; state: string }
type AgentEvent = { timestamp: number | string; type: string; agentId: string }
type BenchmarkResult = {
  agentId: string
  report: { successRate?: number; avgResponseTimeMs?: number; p95ResponseTimeMs?: number }
}
type SystemStatus = {
  online: boolean
  activePlans?: number
  pendingTasks?: number
  observer?: { totalTasksAcrossAgents?: number }
}
const agents = ref<AgentInfo[]>([])
const recentEvents = ref<AgentEvent[]>([])
const systemStatus = ref<SystemStatus>({ online: true })
const benchmarkResult = ref<BenchmarkResult[] | null>(null)
const benchmarkRunning = ref(false)
let statusInterval: ReturnType<typeof setInterval> | undefined

function getAgentIcon(type: string): string {
  const icons: Record<string, string> = { orchestrator: '◇', planner: '△', code: '◈', research: '○', supervisor: '□', browser: '◎' }
  return icons[type] || '◇'
}

function getAgentLabel(type: string): string {
  const labels: Record<string, string> = {
    orchestrator: 'agent.orchestrator',
    planner: 'agent.planner',
    code: 'agent.code',
    research: 'agent.research',
    supervisor: 'agent.supervisor',
    browser: 'agent.browser',
  }
  return labels[type] ? t(labels[type]) : type
}

function getStateLabel(state: string): string {
  const labels: Record<string, string> = {
    IDLE: 'state.idle',
    BUSY: 'state.busy',
    ERROR: 'state.error',
  }
  return labels[state] ? t(labels[state]) : state
}

function getEventClass(type: string): string {
  if (type.includes('COMPLETED') || type.includes('IDLE')) return 'event-success'
  if (type.includes('FAILED') || type.includes('ERROR')) return 'event-error'
  if (type.includes('STARTED') || type.includes('BUSY')) return 'event-warn'
  return 'event-info'
}

function formatTime(timestamp: number | string): string {
  return new Date(timestamp).toLocaleTimeString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US')
}

function fillExample() {
  goalInput.value = t('workflow.exampleGoal')
  materialInput.value = t('workflow.exampleMaterial')
}

function submitGoal() {
  if (!goalInput.value.trim() || isRunning.value) return
  const generation = ++workflowGeneration
  workflowConnection?.close()
  isRunning.value = true
  orchestrationResult.value = ''
  streamError.value = ''
  workflowReport.value = null
  workflowConnection = orchestrateGoalStream(goalInput.value, { material: materialInput.value }, event => {
    if (generation !== workflowGeneration) return
    if (event.type === 'report') {
      if (workflowReport.value?.status === 'completed' || workflowReport.value?.status === 'failed') return
      if (workflowReport.value && event.data.sequence <= workflowReport.value.sequence) return
      workflowReport.value = event.data
      if (event.data.status === 'completed' || event.data.status === 'failed') {
        isRunning.value = false
        orchestrationResult.value = event.data.result || ''
        workflowConnection?.close()
        void refreshStatus()
      }
    } else if (event.type === 'complete') {
      orchestrationResult.value = event.data
      isRunning.value = false
      void refreshStatus()
    } else if (event.type === 'error') {
      streamError.value = `${event.data.code} · ${event.data.path} · ${event.data.message}`
      isRunning.value = false
      void refreshStatus()
    }
  }, () => {
    if (generation !== workflowGeneration) return
    if (workflowReport.value?.status === 'completed' || workflowReport.value?.status === 'failed') return
    streamError.value = t('workflow.disconnected')
    isRunning.value = false
  })
}

async function refreshStatus() {
  try {
    const [statusData, agentsData, eventsData] = await Promise.all([
      getSystemStatus(),
      listAgents(),
      getRecentEvents(20)
    ])
    systemStatus.value = { online: true, ...statusData }
    agents.value = agentsData.agents || []
    recentEvents.value = eventsData.events || []
  } catch (e) {
    systemStatus.value = { online: false }
  }
}

async function runBenchmark() {
  benchmarkRunning.value = true
  try {
    const result = await runBenchmarkApi()
    benchmarkResult.value = result.benchmarkResults || []
  } catch (e) {
    console.error('Benchmark failed:', e)
  } finally {
    benchmarkRunning.value = false
  }
}

onMounted(() => {
  refreshStatus()
  statusInterval = setInterval(refreshStatus, 5000)
})

onUnmounted(() => {
  if (statusInterval) clearInterval(statusInterval)
  workflowGeneration++
  workflowConnection?.close()
})
</script>

<template>
  <div class="agent-dashboard">
    <header class="dash-header">
      <div class="dash-header-left">
        <h1 class="dash-title">{{ t('dashboard.title') }}</h1>
        <span class="dash-badge" :class="systemStatus.online ? 'online' : 'offline'">
          <span class="dash-dot"></span>
          {{ systemStatus.online ? t('status.online') : t('status.offline') }}
        </span>
      </div>
      <button class="btn-ghost" @click="refreshStatus">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
        {{ t('action.refresh') }}
      </button>
    </header>

    <div class="dash-grid">
      <!-- Orchestration Panel -->
      <section class="card card--wide">
        <div class="card-head">
          <h2 class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
            {{ t('task.title') }}
          </h2>
        </div>
        <div class="input-row">
          <input
            v-model="goalInput"
            :aria-label="t('task.title')"
            :disabled="isRunning"
            maxlength="12000"
            @keyup.enter="submitGoal"
            :placeholder="t('task.placeholder')"
            class="input-field"
          />
          <button @click="submitGoal" class="btn-primary" :disabled="isRunning">
            {{ isRunning ? t('action.running') : t('action.execute') }}
          </button>
        </div>
        <button class="btn-ghost example-button" @click="fillExample" :disabled="isRunning">{{ t('workflow.example') }}</button>
        <label for="workflow-material" class="material-label">{{ t('workflow.material') }}</label>
        <textarea id="workflow-material" v-model="materialInput" :disabled="isRunning" maxlength="24000"
          class="input-field material-input" :placeholder="t('workflow.materialHint')" rows="3" />
        <p v-if="isRunning" class="material-label" role="status">{{ t('workflow.liveNotice') }}</p>
        <div v-if="workflowReport?.tasks.length" class="progress-track" role="progressbar"
          :aria-label="t('workflow.progress')" :aria-valuenow="Math.round(progressPercent)" aria-valuemin="0" aria-valuemax="100">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
        </div>
        <WorkflowReportPanel v-if="workflowReport" :report="workflowReport" />
        <p v-if="streamError && !workflowReport?.error" class="workflow-error" role="alert">{{ streamError }}</p>
        <div v-if="orchestrationResult" class="result-card">
          <div class="result-head">
            <span>{{ t('result.title') }}</span>
            <button @click="orchestrationResult = ''" class="btn-close">&times;</button>
          </div>
          <pre class="result-body">{{ orchestrationResult }}</pre>
        </div>
      </section>

      <!-- Agent Status -->
      <section class="card">
        <div class="card-head">
          <h2 class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
            {{ t('agents.title') }}
          </h2>
          <span class="card-count">{{ agents.length }}</span>
        </div>
        <div class="agent-list">
          <div
            v-for="agent in agents"
            :key="agent.id"
            class="agent-row"
            :class="agent.state.toLowerCase()"
          >
            <span class="agent-icon">{{ getAgentIcon(agent.type) }}</span>
            <div class="agent-meta">
              <span class="agent-name">{{ getAgentLabel(agent.type) }}</span>
              <span class="agent-id">{{ agent.id }}</span>
            </div>
            <span class="agent-state" :class="agent.state.toLowerCase()">
              {{ getStateLabel(agent.state) }}
            </span>
          </div>
        </div>
      </section>

      <!-- Live Events -->
      <section class="card">
        <div class="card-head">
          <h2 class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
            {{ t('events.title') }}
          </h2>
          <span class="card-count">{{ recentEvents.length }}</span>
        </div>
        <div class="event-list">
          <div v-for="(event, idx) in recentEvents" :key="idx" class="event-row">
            <span class="event-time">{{ formatTime(event.timestamp) }}</span>
            <span class="event-tag" :class="getEventClass(event.type)">{{ event.type }}</span>
            <span class="event-agent">{{ event.agentId }}</span>
          </div>
          <div v-if="recentEvents.length === 0" class="empty-state">{{ t('events.empty') }}</div>
        </div>
      </section>

      <!-- Benchmark -->
      <section class="card">
        <div class="card-head">
          <h2 class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
            {{ t('benchmark.title') }}
          </h2>
        </div>
        <button @click="runBenchmark" class="btn-secondary" :disabled="benchmarkRunning">
          {{ benchmarkRunning ? t('action.running') : t('benchmark.run') }}
        </button>
        <div v-if="benchmarkResult" class="bench-list">
          <div v-for="(result, idx) in benchmarkResult" :key="idx" class="bench-row">
            <div class="bench-agent">{{ result.agentId }}</div>
            <div class="bench-stats">
              <span>{{ t('benchmark.success') }}: {{ (result.report.successRate || 0).toFixed(1) }}%</span>
              <span>{{ t('benchmark.average') }}: {{ (result.report.avgResponseTimeMs || 0).toFixed(0) }}ms</span>
              <span>{{ t('benchmark.p95') }}: {{ (result.report.p95ResponseTimeMs || 0) }}ms</span>
            </div>
          </div>
        </div>
      </section>

      <!-- System Metrics -->
      <section class="card">
        <div class="card-head">
          <h2 class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
            {{ t('metrics.title') }}
          </h2>
        </div>
        <div class="metrics-grid">
          <div class="metric-cell">
            <span class="metric-val">{{ systemStatus.activePlans || 0 }}</span>
            <span class="metric-lbl">{{ t('metrics.activePlans') }}</span>
          </div>
          <div class="metric-cell">
            <span class="metric-val">{{ agents.length }}</span>
            <span class="metric-lbl">{{ t('metrics.agents') }}</span>
          </div>
          <div class="metric-cell">
            <span class="metric-val">{{ systemStatus.pendingTasks || 0 }}</span>
            <span class="metric-lbl">{{ t('metrics.pending') }}</span>
          </div>
          <div class="metric-cell">
            <span class="metric-val">{{ systemStatus.observer?.totalTasksAcrossAgents || 0 }}</span>
            <span class="metric-lbl">{{ t('metrics.totalTasks') }}</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.material-label { font-size: 12px; color: var(--color-text-secondary); margin: 0; }
.material-input { resize: vertical; min-height: 78px; font-family: inherit; }
.example-button { align-self: flex-start; padding: 0; }
.workflow-error { padding: 14px; border: 1px solid var(--color-error); border-radius: 8px; color: var(--color-error); overflow-wrap: anywhere; font-size: 13px; }
.input-field:disabled { opacity: .65; }
.btn-ghost:disabled { opacity: .5; cursor: not-allowed; }
.agent-dashboard {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding-bottom: 32px;
}

.dash-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dash-header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.dash-title {
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--color-text);
}

.dash-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
}

.dash-badge.online { background: var(--color-success-light); color: var(--color-success); }
.dash-badge.offline { background: var(--color-error-light); color: var(--color-error); }

.dash-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  transition: all var(--transition-fast);
}

.btn-ghost:hover {
  background: var(--color-primary-subtle);
  color: var(--color-primary);
}

.dash-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.card {
  display: flex;
  flex-direction: column;
  padding: 24px;
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  gap: 16px;
  transition: border-color var(--transition-fast);
}

.card:hover {
  border-color: var(--color-border);
}

.card--wide {
  grid-column: 1 / -1;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-display);
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  letter-spacing: -0.01em;
}

.card-title svg {
  color: var(--color-primary);
}

.card-count {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--color-bg-alt);
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-tertiary);
}

.input-row {
  display: flex;
  gap: 8px;
}

.input-field {
  flex: 1;
  padding: 10px 16px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-bg);
  color: var(--color-text);
  font-size: 14px;
  transition: all var(--transition-fast);
}

.input-field:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-subtle);
}

.input-field::placeholder {
  color: var(--color-text-tertiary);
}

.btn-primary {
  padding: 10px 20px;
  border-radius: var(--radius-sm);
  background: var(--color-text);
  color: var(--color-text-inverse);
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-primary);
}

.btn-primary:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-secondary {
  padding: 10px 20px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-alt);
  color: var(--color-text);
  font-size: 13.5px;
  font-weight: 500;
  border: 1px solid var(--color-border);
  transition: all var(--transition-fast);
}

.btn-secondary:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.btn-secondary:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.progress-track {
  height: 4px;
  background: var(--color-bg-alt);
  border-radius: 2px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: 2px;
  transition: width 300ms ease;
}

.result-card {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.result-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--color-success-light);
  font-size: 13px;
  font-weight: 500;
  color: var(--color-success);
}

.btn-close {
  font-size: 18px;
  color: var(--color-text-tertiary);
  transition: color var(--transition-fast);
}

.btn-close:hover { color: var(--color-text); }

.result-body {
  padding: 14px;
  margin: 0;
  max-height: 200px;
  overflow-y: auto;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-secondary);
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}

.agent-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  transition: all var(--transition-fast);
}

.agent-row:hover {
  background: var(--color-surface-hover);
}

.agent-row.busy { border-left: 3px solid var(--color-warning); }
.agent-row.idle { border-left: 3px solid var(--color-success); }
.agent-row.error { border-left: 3px solid var(--color-error); }

.agent-icon {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--color-bg-alt);
  font-size: 13px;
  color: var(--color-primary);
}

.agent-meta {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.agent-name {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--color-text);
}

.agent-id {
  font-size: 11.5px;
  color: var(--color-text-tertiary);
}

.agent-state {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
}

.agent-state.idle { background: var(--color-success-light); color: var(--color-success); }
.agent-state.busy { background: var(--color-warning-light); color: var(--color-warning); }
.agent-state.error { background: var(--color-error-light); color: var(--color-error); }

.event-list {
  max-height: 240px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.event-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  font-size: 12.5px;
}

.event-row:nth-child(odd) {
  background: var(--color-bg);
}

.event-time {
  color: var(--color-text-tertiary);
  min-width: 70px;
  font-variant-numeric: tabular-nums;
}

.event-tag {
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
  font-size: 11px;
}

.event-success { background: var(--color-success-light); color: var(--color-success); }
.event-error { background: var(--color-error-light); color: var(--color-error); }
.event-warn { background: var(--color-warning-light); color: var(--color-warning); }
.event-info { background: var(--color-info-light); color: var(--color-info); }

.event-agent {
  margin-left: auto;
  color: var(--color-text-tertiary);
}

.empty-state {
  text-align: center;
  padding: 24px;
  color: var(--color-text-tertiary);
  font-size: 13px;
}

.bench-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bench-row {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--color-bg);
}

.bench-agent {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 4px;
  color: var(--color-text);
}

.bench-stats {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.metrics-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.metric-cell {
  text-align: center;
  padding: 16px;
  border-radius: var(--radius-sm);
  background: var(--color-bg);
}

.metric-val {
  display: block;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 700;
  color: var(--color-primary);
  line-height: 1.2;
}

.metric-lbl {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-top: 4px;
}

@media (max-width: 720px) {
  .dash-grid {
    grid-template-columns: 1fr;
  }
  .card { padding: 18px; }
}
</style>
