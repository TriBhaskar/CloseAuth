<script setup lang="ts">
// FE-2a (spec §2.2 route table: "Post-logout landing"). The Go BFF's
// handleAdminLogout now sends both the console's degraded-config fallback
// and the real RP-initiated logout's post_logout_redirect_uri here instead
// of straight back to /console (internal/server/handlers_admin_auth.go) —
// AdminConsoleClientProvisioningCallback (Java) registers this URI on the
// admin-console-{tenantId} client alongside the old /console one, so
// already-provisioned tenants keep working during the rollout and a stale
// bookmark of the old logout URL never becomes an unregistered, silently-
// ignored redirect.
import { useRoute } from 'vue-router'
import AuthShell from '@/shells/AuthShell.vue'
import { Button } from '@/components/ui/button'
import TenantBrandingProvider from '@/components/common/TenantBrandingProvider.vue'

const route = useRoute()
const tenantId = String(route.params.slug ?? route.params.tenantId ?? '')
const clientId = `admin-console-${tenantId}`
</script>

<template>
  <TenantBrandingProvider :client-id="clientId" v-slot="{ branding, hasLogo }">
    <AuthShell>
      <template #above>
        <div class="flex flex-col items-center gap-2 text-center">
          <!-- FE-6.5: reserved box — see LoginView.vue's identical fix. -->
          <div class="h-10">
            <img
              v-if="hasLogo"
              :src="branding.logoUrl"
              :alt="branding.companyName"
              class="h-10 w-auto object-contain"
            />
          </div>
          <h1 class="text-xl font-semibold tracking-tight">You've been signed out</h1>
        </div>
      </template>
      <RouterLink :to="`/t/${tenantId}`">
        <Button variant="outline" class="w-full">Sign in again</Button>
      </RouterLink>
    </AuthShell>
  </TenantBrandingProvider>
</template>
