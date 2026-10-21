<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { locale, t, toggleLocale } from './i18n'

const router = useRouter()
const currentYear = ref(new Date().getFullYear())
const navItems = [
  { path: '/multi-agent', labelKey: 'nav.agentHub' },
  { path: '/chat-room', labelKey: 'nav.chatRoom' },
  { path: '/learning', labelKey: 'nav.learningGraph' },
]

onMounted(() => {
  const saved = localStorage.getItem('zxl_agent_route')
  if (saved && saved !== '/') {
    router.push(saved)
  }
})
</script>

<template>
  <div class="app-shell">
    <div class="ambient-bg" aria-hidden="true">
      <div class="blob blob--1"></div>
      <div class="blob blob--2"></div>
      <div class="blob blob--3"></div>
    </div>

    <nav class="nav" role="navigation">
      <router-link to="/" class="nav-brand">
        <span class="nav-brand-icon">✦</span>
        <span class="nav-brand-text">Zxl AI</span>
      </router-link>

      <div class="nav-links">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-link"
          active-class="nav-link--active"
        >
          {{ t(item.labelKey) }}
        </router-link>
        <button
          class="language-toggle"
          type="button"
          :aria-label="locale === 'zh-CN' ? t('nav.switchToEnglish') : t('nav.switchToChinese')"
          :title="locale === 'zh-CN' ? t('nav.switchToEnglish') : t('nav.switchToChinese')"
          @click="toggleLocale"
        >
          {{ locale === 'zh-CN' ? 'EN' : '中文' }}
        </button>
      </div>
    </nav>

    <main class="main">
      <router-view v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <footer class="footer">
      <div class="footer-inner">
        <span class="footer-text">
          &copy; {{ currentYear }} {{ t('footer.agentName') }}
        </span>
        <span class="footer-sep">&middot;</span>
        <span class="footer-text">{{ t('footer.tagline') }}</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  position: relative;
}

.ambient-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}

.blob {
  position: absolute;
  border-radius: 50%;
  will-change: transform;
  animation: blob-float 18s ease-in-out infinite;
}

.blob--1 {
  width: 55vw;
  height: 55vw;
  top: -20%;
  right: -15%;
  background: radial-gradient(circle, rgba(122, 156, 140, 0.09) 0%, transparent 70%);
  animation-delay: 0s;
}

.blob--2 {
  width: 40vw;
  height: 40vw;
  bottom: -10%;
  left: -10%;
  background: radial-gradient(circle, rgba(212, 131, 109, 0.06) 0%, transparent 70%);
  animation-delay: -6s;
}

.blob--3 {
  width: 35vw;
  height: 35vw;
  top: 40%;
  left: 50%;
  background: radial-gradient(circle, rgba(196, 155, 94, 0.04) 0%, transparent 70%);
  animation-delay: -12s;
}

@keyframes blob-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(3%, -4%) scale(1.05); }
  66% { transform: translate(-2%, 3%) scale(0.95); }
}

.nav {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  height: 60px;
  background: rgba(250, 248, 245, 0.85);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-bottom: 1px solid var(--color-border-light);
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 17px;
  color: var(--color-text);
  letter-spacing: -0.02em;
}

.nav-brand-icon {
  font-size: 18px;
  color: var(--color-primary);
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-link {
  padding: 6px 16px;
  border-radius: var(--radius-sm);
  font-size: 13.5px;
  font-weight: 500;
  color: var(--color-text-secondary);
  transition: all var(--transition-fast);
  letter-spacing: -0.01em;
}

.nav-link:hover {
  color: var(--color-text);
  background: var(--color-primary-subtle);
}

.nav-link--active {
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.language-toggle {
  min-width: 42px;
  padding: 6px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 600;
  transition: all var(--transition-fast);
}

.language-toggle:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  background: var(--color-primary-subtle);
}

.main {
  flex: 1;
  position: relative;
  z-index: 1;
  padding: 32px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
}

.footer {
  position: relative;
  z-index: 1;
  padding: 20px 32px;
  border-top: 1px solid var(--color-border-light);
}

.footer-inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.footer-text {
  font-size: 12.5px;
  color: var(--color-text-tertiary);
}

.footer-sep {
  color: var(--color-border);
}

.page-enter-active {
  transition: opacity 0.35s ease, transform 0.35s ease;
}
.page-leave-active {
  transition: opacity 0.2s ease, transform 0.25s ease;
}
.page-enter-from {
  opacity: 0;
  transform: translateY(10px);
}
.page-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

@media (max-width: 720px) {
  .nav { padding: 0 16px; }
  .nav-links { gap: 0; }
  .nav-link { padding: 6px 10px; font-size: 12px; }
  .main { padding: 16px; }
  .footer { padding: 16px; }
}
</style>
