<script setup lang="ts">
import { computed, ref } from 'vue'
import { readDurableRunEvents } from '../api/scopedMultiagent'
import type { DurableRunEvent } from '../api/scopedMultiagent'
import { locale } from '../i18n'

const props = defineProps<{ runId: string; phase: (status: string) => string }>()
const tr = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
const events = ref<DurableRunEvent[]>([])
const latest = ref<number | null>(null)
const cursor = computed(() => events.value[events.value.length - 1]?.sequence ?? 0)
const busy = ref(false)
const failure = ref('')

async function read() {
  if (busy.value) return
  busy.value = true
  failure.value = ''
  try {
    const page = await readDurableRunEvents(props.runId, cursor.value)
    let expected = cursor.value + 1
    if (page.events.some(event => event.sequence !== expected++) || page.lastSequence < expected - 1) {
      throw new Error('EVENT_SEQUENCE_INVALID')
    }
    events.value.push(...page.events)
    latest.value = page.lastSequence
  } catch (error) {
    failure.value = error instanceof Error ? error.message : 'REQUEST_FAILED'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <details class="event-journal">
    <summary>{{ tr('已保存的运行事件', 'Saved run events') }}</summary>
    <p>{{ tr('从当前序号读取后续已保存的事件。', 'Read saved events after the current cursor.') }}</p>
    <button type="button" :disabled="busy" @click="read">
      {{ busy ? tr('正在读取…', 'Reading…') : tr('读取后续事件', 'Read newer events') }}
    </button>
    <p v-if="failure" role="alert">{{ tr('读取失败', 'Unable to read events') }} · <code>{{ failure }}</code></p>
    <p v-if="latest !== null" role="status">{{ tr('已读取至', 'Read through') }} #{{ cursor }} / #{{ latest }}</p>
    <ol v-if="events.length">
      <li v-for="event in events" :key="event.sequence">
        <code>#{{ event.sequence }}</code> {{ phase(event.status) }}
        <span v-if="event.errorCode"> · {{ event.errorCode }}</span>
      </li>
    </ol>
  </details>
</template>

<style scoped>
.event-journal{margin:18px 0;padding:12px 14px;border:1px solid #dce6df;border-radius:9px;color:#395c50;font-size:13px}
summary{font-weight:650;cursor:pointer}p{margin:8px 0}button{padding:6px 10px;border:1px solid #d5e1da;border-radius:7px;color:inherit}
button:focus-visible,summary:focus-visible{outline:2px solid #367f6b;outline-offset:3px}ol{list-style:none;padding:0;margin:8px 0}
li{padding:5px 0;border-bottom:1px solid #e5e9e1}li code{margin-right:8px}p[role=alert]{color:#a45b47}
</style>
