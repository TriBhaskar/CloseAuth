<script setup lang="ts">
// FE-1.12 (spec §7.3 route table, §7.3's "404 ... not-found route for
// pages"): the SPA's own unmatched-URL landing — router.ts's catch-all
// redirects here instead of silently bouncing to '/', which was vague
// about what happened (§7.3's closing line bans exactly that). A domain
// lookup's own 404 (e.g. a client-id lookup that finds nothing) stays
// inline per §7.3's own split — this route is only for a URL this router
// has no route for at all.
import { useRouter } from 'vue-router'
import EntryShell from '@/shells/EntryShell.vue'
import ErrorState from '@/components/common/ErrorState.vue'

const router = useRouter()
</script>

<template>
  <EntryShell>
    <ErrorState message="This page doesn't exist." @retry="router.push('/')">
      <template #action>Go home</template>
    </ErrorState>
  </EntryShell>
</template>
