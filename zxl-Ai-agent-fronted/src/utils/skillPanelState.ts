import { ref, watch } from 'vue'
import type { Ref } from 'vue'

/** Collapse at a new request boundary; completion must not reopen or interrupt manual reading. */
export function useSkillPanelExpansion(disabled: Ref<boolean | undefined>, onSettled?: () => void) {
  const expanded = ref(!disabled.value)
  watch(disabled, (value, previous) => {
    if (value && !previous) expanded.value = false
    if (!value && previous) onSettled?.()
  })
  return expanded
}
