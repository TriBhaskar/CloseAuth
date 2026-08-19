<script setup lang="ts">
// FE-1.9 (spec §5): "states what the thing is, why the screen is blank, and
// the one action that fills it." Used for both first-run and filtered-empty
// — the caller supplies copy appropriate to which.
defineProps<{
  title: string
  description: string
}>()
</script>

<template>
  <div class="flex flex-col items-center gap-3 py-12 px-6 text-center">
    <div v-if="$slots.icon" class="text-ink-subtle" aria-hidden="true">
      <slot name="icon" />
    </div>
    <div class="flex flex-col gap-1">
      <p class="text-sm font-medium text-ink">{{ title }}</p>
      <p class="text-sm text-ink-muted">{{ description }}</p>
    </div>
    <div v-if="$slots.action" class="mt-1">
      <slot name="action" />
    </div>
  </div>
</template>
