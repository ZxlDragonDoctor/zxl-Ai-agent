import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'
import LoveMaster from '../views/LoveMaster.vue'
import SuperAgent from '../views/SuperAgent.vue'
import MultiAgent from '../views/MultiAgent.vue'

const routes = [
  { path: '/', component: Home },
  { path: '/love', component: LoveMaster },
  { path: '/agent', component: SuperAgent },
  { path: '/multiagent', component: MultiAgent }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router 