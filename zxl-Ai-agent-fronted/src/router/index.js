import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'

const routes = [
  { path: '/', component: Home },
  {
    path: '/multi-agent',
    name: 'MultiAgent',
    component: () => import('../views/MultiAgent.vue')
  },
  {
    path: '/love-master',
    redirect: { path: '/chat-room', query: { agentType: 'love-assistant' } }
  },
  {
    path: '/super-agent',
    redirect: '/chat-room'
  },
  {
    path: '/chat-room',
    name: 'AgentChat',
    component: () => import('../views/AgentChat.vue')
  },
  {
    path: '/learning',
    name: 'LearningGraph',
    component: () => import('../views/LearningGraph.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
