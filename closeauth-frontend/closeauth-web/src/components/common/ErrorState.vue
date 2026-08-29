<script setup lang="ts">
// FE-1.9 (spec §5, §7.3): "shows category-appropriate message + trace_id
// chip + Retry." No backend response includes a trace_id anywhere in this
// codebase yet (confirmed by grep across both the Go BFF and the Java
// backend during FE-1b) — traceId is optional and the chip renders only
// when present, so this component is correct today and ready the day the
// backend emits one.
//
// The trace_id display isn't IdentifierChip: that component's `kind` prop
// is spec's fixed 7-entry entity vocabulary (tenant/user/client/…), and a
// trace_id isn't an entity. This is a smaller, purpose-built mono+copy
// treatment reusing CopyButton for the "click copies" affordance a support
// agent needs — not a new, unspecced IdentifierChip kind.
import CopyButton from './CopyButton.vue'

// FE-6.1 (spec §7.3): "Only server/network are retryable — a 403/404/429
// retry would just fail identically." api/problem.ts's errorStateProps()
// already computes this per-result; `retryable` defaults to `true` so every
// pre-existing caller that only ever passed `message`/`traceId` keeps
// showing Retry exactly as before — a caller that now HAS a category
// (list/detail views wired to errorStateProps this session) passes it
// explicitly and a 403 stops showing an action that's guaranteed to fail
// identically.
withDefaults(
  defineProps<{
    message: string
    traceId?: string
    retryable?: boolean
  }>(),
  { retryable: true },
)

defineEmits<{ (e: 'retry'): void }>()
</script>

<template>
  <div class="flex flex-col items-center gap-3 py-12 px-6 text-center" role="alert">
    <p class="text-sm text-ink">{{ message }}</p>

    <div
      v-if="traceId"
      class="inline-flex items-center gap-1.5 rounded border border-line px-1.5 py-0.5 text-[0.75rem]"
    >
      <span class="text-ink-muted">trace_id</span>
      <span class="font-mono text-ink">{{ traceId }}</span>
      <CopyButton
        :value="traceId"
        label="Copy"
        copied-label="Copied"
        class="text-ink-muted hover:text-ink"
      />
    </div>

    <button
      v-if="retryable"
      type="button"
      class="text-sm font-medium text-primary hover:underline"
      @click="$emit('retry')"
    >
      <slot name="action">Retry</slot>
    </button>
  </div>
</template>
