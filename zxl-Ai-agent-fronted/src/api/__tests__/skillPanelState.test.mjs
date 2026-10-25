import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, nextTick, effectScope } from 'vue'
import { useSkillPanelExpansion } from '../../utils/skillPanelState.ts'

test('skill panel initially exposes policy then collapses on a new request and stays collapsed after completion', async () => {
  const scope = effectScope(), disabled = ref(false)
  const expanded = scope.run(() => useSkillPanelExpansion(disabled))
  try {
    assert.equal(expanded.value, true)
    disabled.value = true; await nextTick(); assert.equal(expanded.value, false)
    disabled.value = false; await nextTick(); assert.equal(expanded.value, false)
    expanded.value = true; await nextTick(); assert.equal(expanded.value, true)
    disabled.value = false; await nextTick(); assert.equal(expanded.value, true)
    disabled.value = true; await nextTick(); assert.equal(expanded.value, false)
  } finally { scope.stop() }
})
test('a panel mounted while already sending starts collapsed but permits explicit inspection', async () => {
  const scope = effectScope(), disabled = ref(true)
  const expanded = scope.run(() => useSkillPanelExpansion(disabled))
  try {
    assert.equal(expanded.value, false)
    expanded.value = true; await nextTick(); assert.equal(expanded.value, true)
    disabled.value = false; await nextTick(); assert.equal(expanded.value, true)
  } finally { scope.stop() }
})
