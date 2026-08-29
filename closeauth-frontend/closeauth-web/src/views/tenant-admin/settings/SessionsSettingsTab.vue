<script setup lang="ts">
// FE-5.5 (spec §6.4.7 tab 3): "Sessions & tokens." Idle/absolute/remember-me/
// refresh-token-window durations are PLATFORM-wide config
// (CloseAuthProperties.java's SessionConfig) — there is no
// tenant_session_policy table, no read endpoint, no write endpoint. This tab
// is a single honest EmptyState, not a form with hard-coded numbers: baking
// today's CloseAuthProperties defaults into the SPA would be exactly the
// "mock data path substituting for a real API response" the definition of
// done forbids, and would silently go stale the moment ops changed the
// config. No numbers appear here on purpose.
import EmptyState from '@/components/common/EmptyState.vue'
</script>

<template>
  <div class="rounded-xl border border-border p-6 max-w-2xl">
    <h2 class="text-lg font-semibold tracking-tight mb-4">Sessions &amp; tokens</h2>
    <EmptyState
      title="Session policy isn't configurable per tenant yet."
      description="Idle timeout, absolute timeout, remember-me duration, and the refresh-token sliding window are set platform-wide today, not per tenant. There is no tenant-scoped setting here to change."
    />
  </div>
</template>
