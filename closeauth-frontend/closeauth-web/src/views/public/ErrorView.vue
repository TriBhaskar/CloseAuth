<script setup lang="ts">
// FE-1.12 (spec route map: "/error — public — Terminal error with a
// trace_id the user can quote to support."): the app's last-resort landing
// when there's nowhere more specific to send the user — e.g. §7.2's fatal
// tenant-mismatch path (tenantAdminClient.ts) redirects to the tenant
// resolver, not here, but a future caller with no tenant context at all
// would land here. `traceId` is read from the query string when present;
// no caller populates it yet — trace_id doesn't exist end-to-end (neither
// the Java ProblemDetails builder nor the Go BFF's error envelope emit one,
// confirmed during FE-1d's research; a tracked backend dependency, not
// silently worked around) — so the chip stays absent until it does.
import { useRoute } from 'vue-router'
import EntryShell from '@/shells/EntryShell.vue'
import ErrorState from '@/components/common/ErrorState.vue'

const route = useRoute()
const traceId = typeof route.query.traceId === 'string' ? route.query.traceId : undefined

function reload(): void {
  window.location.reload()
}
</script>

<template>
  <EntryShell>
    <ErrorState message="Something went wrong." :trace-id="traceId" @retry="reload">
      <template #action>Reload</template>
    </ErrorState>
  </EntryShell>
</template>
