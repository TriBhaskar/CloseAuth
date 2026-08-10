<script setup lang="ts">
// Stage UI-3b: the shared loading/error/content state machine for every
// admin data view — the reusable piece behind the "no silent mock-data
// fallback" rule (src/stores/admin.ts, standing since UI-0). When `error`
// is set, only the error slot renders; the default slot never mounts, so
// there's no code path where stale or partial data renders alongside (or
// instead of) a visible failure. Mirrors the ad hoc
// isLoading/pingResult v-if chain TenantAdminHomeView.vue used in UI-3a,
// promoted into one component so every later surface gets it for free.
import type { HTMLAttributes } from 'vue'

const props = withDefaults(
  defineProps<{
    loading: boolean
    error?: string | null
    class?: HTMLAttributes['class']
  }>(),
  { error: null },
)
</script>

<template>
  <div :class="props.class">
    <template v-if="loading">
      <slot name="loading">
        <div class="flex flex-col gap-2" aria-busy="true" aria-live="polite">
          <div class="skeleton h-10 w-full rounded-md" />
          <div class="skeleton h-10 w-full rounded-md" />
          <div class="skeleton h-10 w-full rounded-md" />
        </div>
      </slot>
    </template>
    <template v-else-if="error">
      <slot name="error" :message="error">
        <p role="alert" class="text-sm text-destructive">{{ error }}</p>
      </slot>
    </template>
    <template v-else>
      <slot />
    </template>
  </div>
</template>
