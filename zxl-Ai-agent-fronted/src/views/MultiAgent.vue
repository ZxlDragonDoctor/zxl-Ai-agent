<template>
  <div class="multi-agent-container">
    <div class="dashboard-header glass">
      <div class="header-left">
        <h2 class="neon-text">🤖 多智能体系统</h2>
        <div class="status-badge" :class="systemStatus.online ? 'online' : 'offline'">
          {{ systemStatus.online ? '系统在线' : '系统离线' }}
        </div>
      </div>
      <div class="header-actions">
        <button class="action-btn" @click="refreshStatus">🔄 刷新</button>
        <router-link to="/" class="action-btn">🏠 首页</router-link>
      </div>
    </div>

    <div class="dashboard-grid">
      <!-- 控制面板 -->
      <div class="panel glass" style="grid-area: control">
        <h3>🎯 任务编排</h3>
        <div class="input-group">
          <input v-model="goalInput" @keyup.enter="submitGoal" placeholder="输入你的目标，例如：帮我分析并实现一个用户登录功能..." class="input-field" />
          <button @click="submitGoal" class="btn-primary" :disabled="isRunning">
            {{ isRunning ? '执行中...' : '🚀 执行' }}
          </button>
        </div>
        <div v-if="isRunning" class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
        </div>
        <div v-if="orchestrationResult" class="result-box">
          <div class="result-header">
            <span>✅ 执行结果</span>
            <button @click="orchestrationResult = ''" class="close-btn">×</button>
          </div>
          <pre class="result-content">{{ orchestrationResult }}</pre>
        </div>
      </div>

      <!-- Agent 状态 -->
      <div class="panel glass" style="grid-area: agents">
        <h3>👥 智能体状态</h3>
        <div class="agent-list">
          <div v-for="agent in agents" :key="agent.id" class="agent-card" :class="agent.state.toLowerCase()">
            <div class="agent-icon">{{ getAgentIcon(agent.type) }}</div>
            <div class="agent-info">
              <div class="agent-name">{{ getAgentLabel(agent.type) }}</div>
              <div class="agent-type">{{ agent.id }}</div>
            </div>
            <div class="agent-state" :class="agent.state.toLowerCase()">
              {{ agent.state === 'IDLE' ? '空闲' : agent.state === 'BUSY' ? '工作中' : agent.state }}
            </div>
          </div>
        </div>
      </div>

      <!-- 实时事件 -->
      <div class="panel glass" style="grid-area: events">
        <h3>📡 实时事件</h3>
        <div class="event-list">
          <div v-for="(event, idx) in recentEvents" :key="idx" class="event-item">
            <span class="event-time">{{ formatTime(event.timestamp) }}</span>
            <span class="event-type" :class="getEventClass(event.type)">{{ event.type }}</span>
            <span class="event-agent">{{ event.agentId }}</span>
          </div>
          <div v-if="recentEvents.length === 0" class="empty-state">暂无事件</div>
        </div>
      </div>

      <!-- Benchmark -->
      <div class="panel glass" style="grid-area: benchmark">
        <h3>📊 性能评估</h3>
        <button @click="runBenchmark" class="btn-secondary" :disabled="benchmarkRunning">
          {{ benchmarkRunning ? '测试中...' : '🏃 运行基准测试' }}
        </button>
        <div v-if="benchmarkResult" class="benchmark-result">
          <div v-for="(result, idx) in benchmarkResult" :key="idx" class="benchmark-item">
            <div class="benchmark-agent">{{ result.agentId }}</div>
            <div class="benchmark-stats">
              <span>成功率: {{ (result.report.successRate || 0).toFixed(1) }}%</span>
              <span>平均耗时: {{ (result.report.avgResponseTimeMs || 0).toFixed(0) }}ms</span>
              <span>P95: {{ (result.report.p95ResponseTimeMs || 0) }}ms</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 系统指标 -->
      <div class="panel glass" style="grid-area: metrics">
        <h3>📈 系统指标</h3>
        <div class="metrics-grid">
          <div class="metric">
            <div class="metric-value">{{ systemStatus.activePlans || 0 }}</div>
            <div class="metric-label">活跃任务</div>
          </div>
          <div class="metric">
            <div class="metric-value">{{ agents.length }}</div>
            <div class="metric-label">智能体数</div>
          </div>
          <div class="metric">
            <div class="metric-value">{{ systemStatus.pendingTasks || 0 }}</div>
            <div class="metric-label">排队中</div>
          </div>
          <div class="metric">
            <div class="metric-value">{{ systemStatus.observer?.totalTasksAcrossAgents || 0 }}</div>
            <div class="metric-label">总任务</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import {
  orchestrateGoal,
  orchestrateGoalStream,
  getSystemStatus,
  listAgents,
  getRecentEvents,
  runBenchmark as runBenchmarkApi
} from '../api/multiagent'

const goalInput = ref('')
const isRunning = ref(false)
const orchestrationResult = ref('')
const progressPercent = ref(0)
const agents = ref([])
const recentEvents = ref([])
const systemStatus = ref({ online: true })
const benchmarkResult = ref(null)
const benchmarkRunning = ref(false)
let statusInterval = null

function getAgentIcon(type) {
  const icons = { orchestrator: '🧠', planner: '📋', code: '💻', research: '🔍', supervisor: '👁', browser: '🌐' }
  return icons[type] || '🤖'
}

function getAgentLabel(type) {
  const labels = { orchestrator: '编排器', planner: '规划器', code: '代码助手', research: '研究员', supervisor: '监督员', browser: '浏览器' }
  return labels[type] || type
}

function getEventClass(type) {
  if (type.includes('COMPLETED') || type.includes('IDLE')) return 'event-success'
  if (type.includes('FAILED') || type.includes('ERROR')) return 'event-error'
  if (type.includes('STARTED') || type.includes('BUSY')) return 'event-warn'
  return 'event-info'
}

function formatTime(timestamp) {
  return new Date(timestamp).toLocaleTimeString()
}

async function submitGoal() {
  if (!goalInput.value.trim() || isRunning.value) return
  isRunning.value = true
  orchestrationResult.value = ''
  progressPercent.value = 0

  const progressTimer = setInterval(() => {
    if (progressPercent.value < 90) progressPercent.value += Math.random() * 10
  }, 500)

  try {
    const result = await orchestrateGoal(goalInput.value)
    clearInterval(progressTimer)
    progressPercent.value = 100
    orchestrationResult.value = result.result || result.error
    setTimeout(() => { progressPercent.value = 0 }, 1000)
  } catch (e) {
    orchestrationResult.value = `Error: ${e.message}`
  } finally {
    isRunning.value = false
    goalInput.value = ''
  }
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
})
</script>

<style scoped>
.multi-agent-container {
  height: 100vh;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  overflow-y: auto;
}

.dashboard-header {
  padding: 15px 25px;
  border-radius: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(13, 20, 43, 0.7);
  border: 1px solid rgba(255,255,255,0.1);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 15px;
}

.status-badge {
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 0.8em;
  font-weight: 500;
}

.status-badge.online { background: rgba(0, 200, 83, 0.2); color: #00c853; }
.status-badge.offline { background: rgba(255, 68, 68, 0.2); color: #ff4444; }

.header-actions { display: flex; gap: 10px; }
.action-btn {
  padding: 8px 16px;
  border-radius: 10px;
  background: rgba(255,255,255,0.1);
  color: #fff;
  text-decoration: none;
  border: 1px solid rgba(255,255,255,0.2);
  cursor: pointer;
  font-size: 0.9em;
  transition: all 0.3s;
}
.action-btn:hover { background: rgba(255,255,255,0.2); transform: translateY(-2px); }

.dashboard-grid {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: auto auto auto;
  grid-template-areas:
    "control control"
    "agents events"
    "benchmark metrics";
  gap: 20px;
}

.panel {
  padding: 20px;
  border-radius: 16px;
  background: rgba(13, 20, 43, 0.5);
  border: 1px solid rgba(255,255,255,0.08);
}

.panel h3 {
  margin: 0 0 15px;
  font-size: 1.1em;
  color: rgba(255,255,255,0.9);
}

.input-group {
  display: flex;
  gap: 10px;
}

.input-field {
  flex: 1;
  padding: 12px 16px;
  border: none;
  border-radius: 10px;
  background: rgba(255,255,255,0.1);
  color: white;
  font-size: 0.95em;
}
.input-field::placeholder { color: rgba(255,255,255,0.4); }
.input-field:focus { outline: none; background: rgba(255,255,255,0.15); }

.btn-primary, .btn-secondary {
  padding: 12px 24px;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-size: 0.95em;
  transition: all 0.3s;
  white-space: nowrap;
}
.btn-primary {
  background: linear-gradient(45deg, #00b4d8, #0077b6);
  color: white;
}
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-secondary {
  background: rgba(255,255,255,0.1);
  color: white;
  border: 1px solid rgba(255,255,255,0.2);
  width: 100%;
}

.progress-bar {
  margin-top: 10px;
  height: 4px;
  background: rgba(255,255,255,0.1);
  border-radius: 2px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #00b4d8, #48cae4);
  transition: width 0.3s;
}

.result-box {
  margin-top: 15px;
  border-radius: 10px;
  background: rgba(0,0,0,0.3);
  overflow: hidden;
}
.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 15px;
  background: rgba(0,200,83,0.1);
  font-size: 0.9em;
}
.close-btn {
  background: none;
  border: none;
  color: rgba(255,255,255,0.6);
  cursor: pointer;
  font-size: 1.2em;
}
.result-content {
  padding: 15px;
  margin: 0;
  max-height: 200px;
  overflow-y: auto;
  font-size: 0.85em;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.agent-list { display: flex; flex-direction: column; gap: 8px; }

.agent-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 10px;
  background: rgba(255,255,255,0.05);
  transition: all 0.3s;
}
.agent-card:hover { background: rgba(255,255,255,0.1); }
.agent-card.busy { border-left: 3px solid #ff9800; }
.agent-card.idle { border-left: 3px solid #00c853; }
.agent-card.error { border-left: 3px solid #ff4444; }

.agent-icon { font-size: 1.5em; }
.agent-info { flex: 1; }
.agent-name { font-size: 0.95em; font-weight: 500; }
.agent-type { font-size: 0.75em; opacity: 0.6; }
.agent-state {
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 0.75em;
  font-weight: 500;
}
.agent-state.idle { background: rgba(0,200,83,0.2); color: #00c853; }
.agent-state.busy { background: rgba(255,152,0,0.2); color: #ff9800; }
.agent-state.error { background: rgba(255,68,68,0.2); color: #ff4444; }

.event-list {
  max-height: 240px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.event-item {
  display: flex;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 0.8em;
  background: rgba(255,255,255,0.03);
}
.event-time { opacity: 0.5; min-width: 70px; }
.event-type { padding: 1px 6px; border-radius: 4px; font-weight: 500; }
.event-success { background: rgba(0,200,83,0.2); color: #00c853; }
.event-error { background: rgba(255,68,68,0.2); color: #ff4444; }
.event-warn { background: rgba(255,152,0,0.2); color: #ff9800; }
.event-info { background: rgba(33,150,243,0.2); color: #2196f3; }
.event-agent { opacity: 0.6; margin-left: auto; }

.empty-state { text-align: center; padding: 20px; opacity: 0.5; }

.benchmark-result { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.benchmark-item {
  padding: 10px;
  border-radius: 8px;
  background: rgba(255,255,255,0.05);
}
.benchmark-agent { font-size: 0.85em; font-weight: 500; margin-bottom: 4px; }
.benchmark-stats { display: flex; gap: 12px; font-size: 0.75em; opacity: 0.7; }

.metrics-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.metric {
  text-align: center;
  padding: 15px;
  border-radius: 10px;
  background: rgba(255,255,255,0.05);
}
.metric-value { font-size: 1.8em; font-weight: 700; color: #48cae4; }
.metric-label { font-size: 0.8em; opacity: 0.6; margin-top: 4px; }

@media (max-width: 900px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
    grid-template-areas:
      "control"
      "agents"
      "events"
      "benchmark"
      "metrics";
  }
}
</style>
