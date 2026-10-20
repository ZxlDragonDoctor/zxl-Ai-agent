/// <reference types="vite/client" />
import { createApp } from 'vue'
import Preview from './Preview.vue'
import '../../src/style.css'

// Local verification only. The normal production build has no entry for this page.
if (!import.meta.env.DEV) throw new Error('Development preview only')
createApp(Preview).mount('#app')
