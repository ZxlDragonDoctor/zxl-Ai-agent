import { ref } from 'vue'

export type Locale = 'zh-CN' | 'en'

const STORAGE_KEY = 'zxl_agent_locale'

const messages: Record<Locale, Record<string, string>> = {
  'zh-CN': {
    'nav.agentHub': 'Agent 协作中心',
    'nav.chatRoom': '对话空间',
    'nav.loveMaster': '恋爱大师',
    'nav.superAgent': '超级智能体',
    'nav.switchToEnglish': '切换为英文',
    'nav.switchToChinese': '切换为中文',
    'footer.agentName': 'Zxl AI 智能体',
    'footer.tagline': '多智能体协作平台',
    'home.badge': '智能体平台',
    'home.titleMain': '多智能体',
    'home.titleSub': '协同工作',
    'home.description': '多个专业 AI 智能体协同工作，统一完成任务编排、推理与执行。',
    'home.explore': '探索智能体',
    'home.modules': '4 个模块',
    'home.metricAgents': '5 个智能体',
    'home.metricRealtime': '实时协作',
    'home.metricSentiment': '情绪理解',
    'home.metricAutonomous': '自主执行',
    'home.tagCore': '核心能力',
    'home.tagCommunication': '沟通',
    'home.tagEmpathy': '共情',
    'home.tagAdvanced': '进阶能力',
    'home.cardHubTitle': 'Agent 协作中心',
    'home.cardHubDescription': '多个智能体实时协作，分解任务并各自处理擅长的工作。',
    'home.cardChatTitle': '对话空间',
    'home.cardChatDescription': '结合上下文和持久记忆进行自然对话，并根据主题灵活响应。',
    'home.cardLoveTitle': '恋爱大师',
    'home.cardLoveDescription': '通过有同理心的 AI 对话，获得贴合个人情况的关系建议。',
    'home.cardSuperTitle': '超级智能体',
    'home.cardSuperDescription': '使用工具、制定计划并自主完成复杂任务。',
    'dashboard.title': 'Agent 协作中心',
    'status.online': '系统在线',
    'status.offline': '离线',
    'action.refresh': '刷新',
    'task.title': '任务编排',
    'task.placeholder': '描述你想完成的目标，例如：分析并实现一个登录功能……',
    'action.running': '运行中…',
    'action.execute': '执行',
    'result.title': '执行结果',
    'agents.title': '智能体',
    'agent.orchestrator': '编排器',
    'agent.planner': '规划智能体',
    'agent.code': '代码智能体',
    'agent.research': '研究智能体',
    'agent.supervisor': '监督智能体',
    'agent.browser': '浏览器智能体',
    'state.idle': '空闲',
    'state.busy': '忙碌',
    'state.error': '异常',
    'events.title': '实时事件',
    'events.empty': '暂无事件',
    'benchmark.title': '性能基准',
    'benchmark.run': '运行基准测试',
    'benchmark.success': '成功率',
    'benchmark.average': '平均耗时',
    'benchmark.p95': 'P95 耗时',
    'metrics.title': '系统指标',
    'metrics.activePlans': '进行中的计划',
    'metrics.agents': '智能体数量',
    'metrics.pending': '待处理任务',
    'metrics.totalTasks': '累计任务数',
    'chat.inputPlaceholder': '输入消息…',
    'chat.send': '发送消息',
    'chat.session': '会话',
  },
  en: {
    'nav.agentHub': 'Agent Hub',
    'nav.chatRoom': 'Chat Room',
    'nav.loveMaster': 'Love Master',
    'nav.superAgent': 'Super Agent',
    'nav.switchToEnglish': 'Switch language to English',
    'nav.switchToChinese': 'Switch language to Chinese',
    'footer.agentName': 'Zxl AI Agent',
    'footer.tagline': 'Multi-Agent Collaboration Platform',
    'home.badge': 'Intelligent Agent Platform',
    'home.titleMain': 'Multi-Agent',
    'home.titleSub': 'Collaboration',
    'home.description': 'Specialized AI agents work together to plan, reason, and execute tasks in one coordinated platform.',
    'home.explore': 'Explore Agents',
    'home.modules': '4 modules',
    'home.metricAgents': '5 agents',
    'home.metricRealtime': 'real-time',
    'home.metricSentiment': 'sentiment',
    'home.metricAutonomous': 'autonomous',
    'home.tagCore': 'core',
    'home.tagCommunication': 'communication',
    'home.tagEmpathy': 'empathy',
    'home.tagAdvanced': 'advanced',
    'home.cardHubTitle': 'Agent Hub',
    'home.cardHubDescription': 'Specialized agents collaborate in real time, dividing a goal into focused tasks.',
    'home.cardChatTitle': 'Chat Room',
    'home.cardChatDescription': 'Context-aware conversations with persistent memory and flexible topic handling.',
    'home.cardLoveTitle': 'Love Master',
    'home.cardLoveDescription': 'Personalized relationship advice through empathetic AI conversations.',
    'home.cardSuperTitle': 'Super Agent',
    'home.cardSuperDescription': 'Plan and complete complex tasks autonomously with tool-use capabilities.',
    'dashboard.title': 'Agent Hub',
    'status.online': 'System Online',
    'status.offline': 'Offline',
    'action.refresh': 'Refresh',
    'task.title': 'Task Orchestration',
    'task.placeholder': "Describe your goal, e.g. 'Analyze and implement a login feature'...",
    'action.running': 'Running...',
    'action.execute': 'Execute',
    'result.title': 'Result',
    'agents.title': 'Agents',
    'agent.orchestrator': 'Orchestrator',
    'agent.planner': 'Planner',
    'agent.code': 'Code Agent',
    'agent.research': 'Researcher',
    'agent.supervisor': 'Supervisor',
    'agent.browser': 'Browser',
    'state.idle': 'Idle',
    'state.busy': 'Busy',
    'state.error': 'Error',
    'events.title': 'Live Events',
    'events.empty': 'No events yet',
    'benchmark.title': 'Benchmark',
    'benchmark.run': 'Run Benchmark',
    'benchmark.success': 'Success rate',
    'benchmark.average': 'Average',
    'benchmark.p95': 'P95',
    'metrics.title': 'System Metrics',
    'metrics.activePlans': 'Active Plans',
    'metrics.agents': 'Agents',
    'metrics.pending': 'Pending',
    'metrics.totalTasks': 'Total Tasks',
    'chat.inputPlaceholder': 'Type your message...',
    'chat.send': 'Send message',
    'chat.session': 'Session',
  },
}

function readSavedLocale(): Locale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'zh-CN' || saved === 'en') return saved
  } catch {
    // Storage can be unavailable in restricted browser contexts; Chinese is the default.
  }
  return 'zh-CN'
}

export const locale = ref<Locale>(readSavedLocale())

export function t(key: string): string {
  return messages[locale.value][key] ?? key
}

export function setLocale(nextLocale: Locale): void {
  locale.value = nextLocale
  try {
    localStorage.setItem(STORAGE_KEY, nextLocale)
  } catch {
    // The current session can still switch languages without persistent storage.
  }
  if (typeof document !== 'undefined') {
    document.documentElement.lang = nextLocale
  }
}

export function toggleLocale(): void {
  setLocale(locale.value === 'zh-CN' ? 'en' : 'zh-CN')
}

if (typeof document !== 'undefined') {
  document.documentElement.lang = locale.value
}
