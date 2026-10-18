<script setup lang="ts">
import { t } from '../i18n'

const features = [
  {
    titleKey: 'home.cardHubTitle',
    descKey: 'home.cardHubDescription',
    path: '/multi-agent',
    metricKey: 'home.metricAgents',
    tag: 'core',
    tagKey: 'home.tagCore',
  },
  {
    titleKey: 'home.cardChatTitle',
    descKey: 'home.cardChatDescription',
    path: '/chat-room',
    metricKey: 'home.metricRealtime',
    tag: 'communication',
    tagKey: 'home.tagCommunication',
  },
]
</script>

<template>
  <div class="home">
    <section class="hero">
      <div class="hero-text">
        <span class="hero-badge">{{ t('home.badge') }}</span>
        <h1 class="hero-title">
          <span class="hero-title-main">{{ t('home.titleMain') }}</span>
          <span class="hero-title-sub">{{ t('home.titleSub') }}</span>
        </h1>
        <p class="hero-desc">
          {{ t('home.description') }}
        </p>
        <div class="hero-actions">
          <router-link to="/multi-agent" class="btn-primary">
            <span>{{ t('home.explore') }}</span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
          </router-link>
          <span class="hero-version">v2.0 &middot; {{ t('home.modules') }}</span>
        </div>
      </div>
      <div class="hero-visual">
        <div class="hero-grid">
          <div v-for="i in 9" :key="i" class="hero-cell" :style="{ '--i': i }"></div>
        </div>
      </div>
    </section>

    <section class="cards">
      <router-link
        v-for="(item, idx) in features"
        :key="item.path"
        :to="item.path"
        class="card"
        :style="{ '--idx': idx }"
      >
        <div class="card-header">
          <span class="card-tag" :class="`card-tag--${item.tag}`">{{ t(item.tagKey) }}</span>
          <span class="card-metric">{{ t(item.metricKey) }}</span>
        </div>
        <h2 class="card-title">{{ t(item.titleKey) }}</h2>
        <p class="card-desc">{{ t(item.descKey) }}</p>
        <span class="card-arrow">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
        </span>
      </router-link>
    </section>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 48px;
  padding-top: 16px;
}

/* Hero Section */
.hero {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 48px;
  align-items: center;
}

.hero-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 999px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  margin-bottom: 16px;
}

.hero-title {
  display: flex;
  flex-direction: column;
  line-height: 1.05;
  margin-bottom: 16px;
}

.hero-title-main {
  font-family: var(--font-display);
  font-size: clamp(44px, 6vw, 68px);
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--color-text);
}

.hero-title-sub {
  font-family: var(--font-sans);
  font-size: clamp(32px, 4.5vw, 52px);
  font-weight: 300;
  color: var(--color-text-tertiary);
  letter-spacing: -0.02em;
}

.hero-desc {
  font-size: 15px;
  line-height: 1.7;
  color: var(--color-text-secondary);
  max-width: 440px;
  margin-bottom: 28px;
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  border-radius: var(--radius-md);
  background: var(--color-text);
  color: var(--color-text-inverse);
  font-size: 14px;
  font-weight: 600;
  transition: all var(--transition-fast);
}

.btn-primary:hover {
  background: var(--color-primary);
  transform: translateY(-1px);
}

.hero-version {
  font-size: 12.5px;
  color: var(--color-text-tertiary);
}

.hero-visual {
  display: flex;
  justify-content: center;
}

.hero-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  width: 240px;
  height: 240px;
}

.hero-cell {
  border-radius: var(--radius-sm);
  background: var(--color-border-light);
  transition: all var(--transition-base);
}

.hero-cell:hover {
  background: var(--color-primary-light);
  transform: scale(1.05);
}

/* Cards Grid */
.cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.card {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 28px;
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  transition: all var(--transition-base);
}

.card:hover {
  border-color: var(--color-border);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.card-tag {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.card-tag--core { background: var(--color-primary-light); color: var(--color-primary); }
.card-tag--communication { background: var(--color-info-light); color: var(--color-info); }
.card-tag--empathy { background: var(--color-accent-light); color: var(--color-accent); }
.card-tag--advanced { background: var(--color-warning-light); color: var(--color-warning); }

.card-metric {
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-tertiary);
}

.card-title {
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 8px;
  letter-spacing: -0.02em;
}

.card-desc {
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--color-text-secondary);
  flex: 1;
}

.card-arrow {
  margin-top: 16px;
  color: var(--color-text-tertiary);
  transition: all var(--transition-fast);
}

.card:hover .card-arrow {
  color: var(--color-primary);
  transform: translateX(4px);
}

@media (max-width: 800px) {
  .hero { grid-template-columns: 1fr; gap: 32px; }
  .hero-visual { display: none; }
  .cards { grid-template-columns: 1fr; }
}
</style>
