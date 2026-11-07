<script setup lang="ts">
import { computed, defineAsyncComponent, onUnmounted, ref } from 'vue'
import { sessionState, subscribeSession } from '../utils/sessionRequest'
const state=ref(sessionState());const stop=subscribeSession(()=>{state.value=sessionState()});onUnmounted(stop)
const mode=computed(()=>state.value.status?.mode)
const Formal=defineAsyncComponent(()=>import('./FormalMultiAgent.vue'))
const Legacy=defineAsyncComponent(()=>import('./MultiAgent.vue'))
</script>
<template><Formal v-if="mode==='required' && state.status?.authenticated" :key="state.generation"/><Legacy v-else-if="mode==='legacy-demo'"/></template>
