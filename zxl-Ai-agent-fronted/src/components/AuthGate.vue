<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { locale } from '../i18n'
import { login, logout, refreshAuth } from '../api/auth'
import { sessionState, subscribeSession, SessionRequestError } from '../utils/sessionRequest'
const route = useRoute()
const state = ref(sessionState())
const unsubscribe = subscribeSession(() => { state.value = sessionState() })
onUnmounted(unsubscribe)
const busy = ref(false), issue = ref('')
const loginName = ref(''), password = ref(''), tenantId = ref('')
const zh = computed(() => locale.value === 'zh-CN')
const protectedPage = computed(() => route.path !== '/' && !route.path.startsWith('/learning'))
const allowed = computed(() => state.value.status?.authenticated || state.value.status?.mode === 'legacy-demo')
const errors: Record<string, [string,string]> = {
  AUTH_CONNECTION_ERROR:['无法连接身份服务，请确认主应用已启动。','Cannot connect to the identity service. Check the main application.'],
  INVALID_AUTH_RESPONSE:['身份服务响应不符合契约，请检查服务版本。','The identity response is invalid. Check the service version.'],
  UNAUTHENTICATED:['登录已失效，请重新登录。','Your session expired. Sign in again.'],
  FORBIDDEN:['当前身份无权执行此操作。','Your account cannot perform this action.'],
  CSRF_REQUIRED:['请先刷新身份状态，再重试登录。','Refresh the session status before signing in.'],
  DEPENDENCY_UNAVAILABLE:['身份依赖暂不可用，请稍后重试。','An identity dependency is unavailable. Try again later.'],
}
const message = computed(() => { const code=issue.value || state.value.error; if(!code) return ''; return errors[code]?.[zh.value ? 0 : 1] || (zh.value ? `操作未完成（${code}）。请检查账号、工作空间或服务状态。` : `Action failed (${code}). Check your account, workspace or service status.`) })
async function run(action:()=>Promise<void>) {
  busy.value=true; issue.value=''
  try { await action() } catch(error) { issue.value=error instanceof SessionRequestError ? error.code : 'AUTH_CONNECTION_ERROR' }
  finally { busy.value=false; password.value='' }
}
onMounted(() => { void run(refreshAuth) })
</script>
<template>
  <section class="identity-bar" aria-label="Account workspace">
    <template v-if="state.status?.authenticated">
      <div><span class="identity-dot"></span><strong>{{ zh ? '工作空间' : 'Workspace' }} · {{ state.status.principal?.tenantId }}</strong><span class="account">{{ state.status.principal?.loginName }} · {{ state.status.principal?.role }}</span></div>
      <button type="button" :disabled="busy" @click="run(logout)">{{ zh ? '退出账号' : 'Sign out' }}</button>
    </template>
    <template v-else-if="state.status?.mode === 'legacy-demo'">
      <p>{{ zh ? '独立教学演示 · 未建立生产身份与租户隔离' : 'Standalone teaching demo · No production identity or tenant isolation' }}</p>
    </template>
    <template v-else><span>{{ zh ? '安全工作空间' : 'Secure workspace' }}</span><span>{{ zh ? '登录后使用业务功能；课程知识可直接阅读。' : 'Sign in for business features. Lessons remain available.' }}</span></template>
  </section>
  <div v-if="message && (!protectedPage || allowed)" class="auth-message" role="alert">{{ message }}</div>
  <section v-if="protectedPage && !allowed" class="auth-gate" :aria-busy="busy">
    <div class="auth-intro"><span class="auth-eyebrow">ZXL AI / WORKSPACE</span><h1>{{ zh ? '进入你的工作空间' : 'Enter your workspace' }}</h1><p>{{ zh ? '使用服务端分配的账号与工作空间，访问属于当前身份的对话、知识和运行。' : 'Use your assigned account and workspace to access your conversations, knowledge and runs.' }}</p><router-link to="/learning">{{ zh ? '先阅读学习课程 →' : 'Read the lessons →' }}</router-link></div>
    <form class="auth-form" @submit.prevent="run(() => login({loginName, password, tenantId}))">
      <h2>{{ zh ? '账号登录' : 'Sign in' }}</h2>
      <p v-if="message" class="auth-message" role="alert">{{ message }}</p>
      <label for="auth-tenant">{{ zh ? '工作空间 ID' : 'Workspace ID' }}</label><input id="auth-tenant" v-model="tenantId" autocomplete="organization" required maxlength="120" :disabled="busy" />
      <label for="auth-login">{{ zh ? '账号' : 'Account' }}</label><input id="auth-login" v-model="loginName" autocomplete="username" required maxlength="120" :disabled="busy" />
      <label for="auth-password">{{ zh ? '密码' : 'Password' }}</label><input id="auth-password" v-model="password" type="password" autocomplete="current-password" required :disabled="busy" />
      <button class="auth-submit" type="submit" :disabled="busy || !state.status?.csrfToken">{{ busy ? (zh ? '正在连接…' : 'Connecting…') : (zh ? '登录工作空间' : 'Sign in to workspace') }}</button>
      <button type="button" :disabled="busy" @click="run(refreshAuth)">{{ zh ? '刷新身份状态' : 'Refresh session status' }}</button>
      <p class="auth-note">{{ zh ? '登录凭据由安全 Cookie 管理；页面不会保存密码。' : 'The session uses a secure cookie. This page does not store passwords.' }}</p>
    </form>
  </section>
  <slot v-else :generation="protectedPage ? state.generation : 0" />
</template>
<style scoped>
.identity-bar { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:12px 18px; margin-bottom:24px; border:1px solid var(--color-border-light); border-radius:12px; background:var(--color-bg-card,#fffdf9); font-size:12px; color:var(--color-text-secondary); }
.identity-bar>div { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }.identity-bar p {margin:0}.identity-dot{width:7px;height:7px;border-radius:50%;background:var(--color-primary,#557c6a)}.account{opacity:.85}
button{border:1px solid var(--color-border-light);border-radius:7px;padding:8px 12px;cursor:pointer;color:var(--color-text);background:transparent}button:disabled{opacity:.5;cursor:wait}.auth-gate{display:grid;grid-template-columns:1fr 1fr;gap:60px;max-width:920px;margin:60px auto 90px;align-items:center}.auth-eyebrow{font-size:11px;letter-spacing:.15em;color:var(--color-primary)}h1{font-family:var(--font-display);font-size:38px;line-height:1.3;letter-spacing:-.04em;margin:18px 0}.auth-intro p{color:var(--color-text-secondary);line-height:1.9;margin-bottom:24px}.auth-intro a{color:var(--color-primary)}.auth-form{display:flex;flex-direction:column;gap:10px;padding:28px;border:1px solid var(--color-border-light);border-radius:18px;background:var(--color-bg-card,#fffdf9);box-shadow:0 16px 45px #384c4010}.auth-form h2{font-size:20px;margin:0 0 10px}.auth-form label{font-size:13px;margin-top:5px}.auth-form input{padding:11px 12px;background:transparent;border:1px solid var(--color-border,#bbc8bf);border-radius:7px;color:var(--color-text);font-size:15px;min-width:0}.auth-form input:focus-visible,button:focus-visible{outline:2px solid var(--color-primary);outline-offset:3px}.auth-submit{margin-top:12px;background:var(--color-primary,#557c6a);color:white}.auth-note{font-size:11px;line-height:1.7;color:var(--color-text-secondary)}.auth-message{border-left:3px solid #a75c3d;background:#a75c3d0c;padding:10px 13px;line-height:1.7;font-size:13px;color:var(--color-text)}
@media(max-width:720px){.identity-bar{align-items:flex-start;flex-direction:column;gap:8px}.auth-gate{grid-template-columns:1fr;gap:24px;margin:28px auto}h1{font-size:29px}.auth-form{padding:22px}}
</style>
