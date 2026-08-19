<script setup lang="ts">
// FE-1.9 (spec §5, §7.4): "Copy: In-place label swap, no toast — a toast for
// a copy is noise." IdentifierChip composes this rather than duplicating the
// copy-with-label-swap logic (spec §3.5 names the exact same behaviour).
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{
    value: string
    label?: string
    copiedLabel?: string
  }>(),
  { label: 'Copy', copiedLabel: 'Copied' },
)

const COPIED_DURATION_MS = 1200

const copied = ref(false)
let resetTimer: ReturnType<typeof setTimeout> | undefined

async function copy(): Promise<void> {
  try {
    await navigator.clipboard.writeText(props.value)
  } catch {
    return
  }
  copied.value = true
  if (resetTimer) clearTimeout(resetTimer)
  resetTimer = setTimeout(() => {
    copied.value = false
  }, COPIED_DURATION_MS)
}
</script>

<template>
  <button type="button" @click="copy">
    <slot :copied="copied">{{ copied ? copiedLabel : label }}</slot>
  </button>
</template>
