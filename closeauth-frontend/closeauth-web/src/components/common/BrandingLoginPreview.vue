<script setup lang="ts">
// FE-5.3 (spec §6.4.7: "Branding — ... a live login-card preview beside the
// form"). Renders the AuthShell card treatment (bordered, no shadow, no
// elevation — §3.7) driven by the SETTINGS FORM'S OWN DRAFT VALUES, updating
// live as the admin types, rather than the last-saved GET response
// TenantBrandingProvider.vue renders on the real hosted pages.
//
// Runs the SAME validation and per-theme contrast fallback the hosted pages
// use (lib/brandingValidation.ts, factored out in FE-5.3 for exactly this
// reuse) — an invalid or unsafe value previews exactly as it will actually
// render for a real signed-out visitor, not a rosier approximation. Only
// primaryColor gets the text-contrast substitute (--brand-primary-text is
// what a button's text ends up as); accentColor and backgroundColor are
// non-text accents in the real component too, so neither gets one here.
//
// No colour literal appears in this file's source — every colour comes from
// a bound `:style`, sourced from props/computed, never a class or a literal
// hex in markup (scripts/check-tokens.mjs scans source text, not runtime
// values).
import { computed } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { isValidHexColor, isValidLogoUrl, brandTextColor } from '@/lib/brandingValidation'
import { hasBrandingLogo } from '@/lib/branding'
import type { BrandingView } from '@/api/tenantAdminBranding'

const props = defineProps<{ branding: BrandingView }>()

const themeStore = useThemeStore()

const hasLogo = computed(
  () => hasBrandingLogo(props.branding.logoUrl) && isValidLogoUrl(props.branding.logoUrl),
)
const primaryColor = computed(() =>
  isValidHexColor(props.branding.primaryColor) ? props.branding.primaryColor : undefined,
)
const primaryTextColor = computed(() =>
  primaryColor.value ? brandTextColor(primaryColor.value, themeStore.resolved) : undefined,
)
const backgroundColor = computed(() =>
  isValidHexColor(props.branding.backgroundColor) ? props.branding.backgroundColor : undefined,
)
const accentColor = computed(() =>
  isValidHexColor(props.branding.accentColor) ? props.branding.accentColor : undefined,
)
</script>

<template>
  <div
    id="branding-login-preview"
    class="w-full max-w-[320px] rounded-xl border border-border p-6 flex flex-col gap-4"
    :style="backgroundColor ? { backgroundColor } : undefined"
  >
    <div class="flex items-center gap-2">
      <img v-if="hasLogo" :src="branding.logoUrl" alt="" class="h-8 w-auto object-contain" />
      <span class="text-sm font-semibold tracking-tight">{{
        branding.companyName || 'Your company'
      }}</span>
    </div>

    <div class="flex flex-col gap-2" aria-hidden="true">
      <div
        class="h-9 rounded-md border border-border px-3 flex items-center text-xs text-muted-foreground"
      >
        Email
      </div>
      <div
        class="h-9 rounded-md border border-border px-3 flex items-center text-xs text-muted-foreground"
      >
        Password
      </div>
      <a class="self-end text-xs" :style="accentColor ? { color: accentColor } : undefined"
        >Forgot password?</a
      >
      <button
        type="button"
        disabled
        class="h-9 rounded-md text-sm font-medium"
        :style="{ backgroundColor: primaryColor, color: primaryTextColor }"
      >
        Sign in
      </button>
    </div>
  </div>
</template>
