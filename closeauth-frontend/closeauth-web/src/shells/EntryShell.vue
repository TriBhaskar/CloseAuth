<!-- FE-1.10 (spec §4.3): "Same geometry as AuthShell but never branded — the
     tenant is not known yet." Taken literally: this wraps AuthShell itself
     rather than duplicating its markup, since the two are only ever meant
     to diverge in what's NOT here — EntryShell is never wrapped in
     TenantBrandingProvider (there's no tenant to brand for yet) and never
     shows a tenant logo, only the generic CloseAuth wordmark AuthShell
     already renders. If a future screen needs EntryShell to diverge
     visually (a different max-width, no card border), that's a reason to
     stop composing and copy the markup then — not a reason to duplicate it
     preemptively now, before any such need exists. -->
<script setup lang="ts">
import AuthShell from './AuthShell.vue'
</script>

<template>
  <AuthShell>
    <template #above>
      <slot name="above" />
    </template>
    <slot />
    <template #footer>
      <slot name="footer" />
    </template>
  </AuthShell>
</template>
