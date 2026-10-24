import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api/ai/learning/knowledge': {
        target: 'http://127.0.0.1:18132',
        rewrite: path => path.replace(/^\/api/, ''),
      },
    },
  },
})
