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
import ErrorState from '@/components/common/ErrorState.vue'

// FE-6.1: the default (non-overridden) error slot used to be a bare
// `<p role="alert">` with no way forward on a transient failure — every
// detail screen built on this component (no #error override) had NO retry
// path at all. `retryable` defaults `true` so a caller with a categorized
// AdminResult can hide it for a 403 (spec §7.3), same convention as
// ErrorState's own new prop. A caller that still only has a plain string
// message gets a working Retry by default, which is strictly better than
// today's dead end. Callers that already override #error are unaffected —
// this only changes the FALLBACK.
const props = withDefaults(
  defineProps<{
    loading: boolean
    error?: string | null
    retryable?: boolean
    class?: HTMLAttributes['class']
  }>(),
  { error: null, retryable: true },
)

defineEmits<{ retry: [] }>()
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
        <ErrorState :message="error" :retryable="retryable" @retry="$emit('retry')" />
      </slot>
    </template>
    <template v-else>
      <slot />
    </template>
  </div>
</template>
