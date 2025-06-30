import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'
import LoveMaster from '../views/LoveMaster.vue'
import SuperAgent from '../views/SuperAgent.vue'

const routes = [
  { path: '/', component: Home },
  { path: '/love', component: LoveMaster },
  { path: '/agent', component: SuperAgent }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router 