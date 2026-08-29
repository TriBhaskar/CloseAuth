<script setup lang="ts">
// FE-5.6 (spec §6.4.7 tab 4): "Tenant profile" — entirely read-only. The
// Tenant ID and sign-in URL are derivable from the route alone (lib/
// tenantSignInUrl.ts, the same helper FE-3b's bootstrap hand-off panel
// uses) and never depend on a network call succeeding. Display name/status
// come from the EXISTING public entry resolver (api/entryResolve.ts) rather
// than a new endpoint — it's the same existence check the entry screen and
// the tenant resolver already call. Its `notFound` outcome can't distinguish
// unknown/suspended/deleted by design (spec §6.1), so a non-ok result here
// just degrades the display-name row to "unavailable" rather than treating
// it as fatal — the Tenant ID and sign-in URL below are unaffected either way.
//
// No PATCH exists for tenant display name or a support-email field
// (tenants has no support-email column; the only tenant mutation is
// platform-scoped, PlatformTenantController) — the gap notice says so
// rather than shipping an edit form that would 404/500.
import { onMounted, ref } from 'vue'
import CopyButton from '@/components/common/CopyButton.vue'
import { Button } from '@/components/ui/button'
import { resolveTenant } from '@/api/entryResolve'
import { tenantSignInUrl } from '@/lib/tenantSignInUrl'

const props = defineProps<{ slug: string }>()

const isLoading = ref(true)
const displayName = ref<string | null>(null)
const status = ref<string | null>(null)
const unavailable = ref(false)

async function load(): Promise<void> {
  isLoading.value = true
  // FE-6.1: reset on every call — previously stuck `true` forever once set,
  // so a retry after a transient failure never actually cleared the
  // "Unavailable right now." state even on success.
  unavailable.value = false
  const result = await resolveTenant(props.slug)
  if (result.kind === 'ok') {
    displayName.value = result.value.displayName
    status.value = result.value.status
  } else {
    unavailable.value = true
  }
  isLoading.value = false
}

onMounted(load)

const signInUrl = tenantSignInUrl(props.slug)
</script>

<template>
  <div class="rounded-xl border border-border p-6 flex flex-col gap-4 max-w-2xl">
    <h2 class="text-lg font-semibold tracking-tight">Tenant profile</h2>

    <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3 text-sm items-center">
      <dt class="text-muted-foreground">Display name</dt>
      <dd v-if="isLoading" class="skeleton h-4 w-32 rounded" />
      <dd
        v-else-if="unavailable"
        id="tenant-profile-name-unavailable"
        class="text-muted-foreground flex items-center gap-2"
      >
        Unavailable right now.
        <Button id="tenant-profile-retry" type="button" variant="ghost" size="sm" @click="load"
          >Retry</Button
        >
      </dd>
      <dd v-else>{{ displayName }}</dd>

      <dt class="text-muted-foreground">Status</dt>
      <dd v-if="isLoading" class="skeleton h-4 w-20 rounded" />
      <dd v-else-if="unavailable" class="text-muted-foreground">—</dd>
      <dd v-else class="font-mono text-xs">{{ status }}</dd>

      <dt class="text-muted-foreground">Tenant ID</dt>
      <dd class="flex items-center gap-2">
        <span id="tenant-profile-id" class="font-mono text-xs break-all">{{ slug }}</span>
        <CopyButton
          :value="slug"
          label="Copy"
          copied-label="Copied"
          class="text-xs text-muted-foreground hover:text-foreground"
        />
      </dd>

      <dt class="text-muted-foreground">Sign-in URL</dt>
      <dd class="flex items-center gap-2">
        <span id="tenant-profile-sign-in-url" class="font-mono text-xs break-all">{{
          signInUrl
        }}</span>
        <CopyButton
          :value="signInUrl"
          label="Copy"
          copied-label="Copied"
          class="text-xs text-muted-foreground hover:text-foreground"
        />
      </dd>
    </dl>

    <p class="text-xs text-muted-foreground border-t border-border pt-3">
      Tenant IDs can't change — they're in every sign-in URL.
    </p>

    <div
      id="tenant-profile-gap-notice"
      class="rounded-md border border-border bg-muted px-3 py-2 text-xs text-muted-foreground"
    >
      The display name and a support email address aren't editable from this console yet — a
      platform admin changes the display name today, and there is no support-email field on a tenant
      at all.
    </div>
  </div>
</template>
