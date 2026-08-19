<script setup lang="ts">
// Stage UI-3e: the last two tenant-admin CRUD surfaces before the console is
// complete — branding and registration mode. Both are single-row,
// GET/PUT-only settings (no list, no dialogs), so this view is two
// independent cards, each following TenantResourceServerDetailView.vue's
// inline-edit-form idiom (page-level QueryState per card, `novalidate
// @submit.prevent`, a self-start submit button, a role="alert" banner) —
// there was nothing here that needed a new pattern.
//
// Branding: PUT is a full replacement (see tenantAdminBranding.ts's header)
// — the form always sends all five fields, seeded from the last GET/PUT
// response, never a sparse object. The logo preview reuses
// hasBrandingLogo (src/lib/branding.ts) rather than re-deriving the
// empty-string guard — the same rule UI-2a's public login page already
// enforces for `<img src="">`.
//
// Registration: the mode <select> is deliberately constrained to the four
// RegistrationMode literals (never free text) — an unrecognised mode string
// is a backend 500, not a 400, so client-side constraint is the only thing
// standing between an admin and that gap (see tenantAdminRegistrationConfig.ts's
// header). The consequence panel below the select is sourced from the real
// strategy classes (REGISTRATION_MODE_DESCRIPTIONS), not invented, so an
// admin flipping this isn't flying blind.
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import QueryState from '@/components/admin/QueryState.vue'
import FormField from '@/components/common/FormField.vue'
import { describeAdminError } from '@/api/problem'
import { getBranding, updateBranding, BRANDING_ERROR_FIELDS, type BrandingView } from '@/api/tenantAdminBranding'
import {
  getRegistrationConfig,
  updateRegistrationMode,
  REGISTRATION_MODE_DESCRIPTIONS,
  type RegistrationMode,
} from '@/api/tenantAdminRegistrationConfig'
import { hasBrandingLogo } from '@/lib/branding'

const route = useRoute()
const slug = String(route.params.slug ?? '')

// ---- branding -----------------------------------------------------------

const isBrandingLoading = ref(true)
const brandingErrorMessage = ref<string | null>(null)
const brandingForm = reactive<BrandingView>({
  logoUrl: '',
  primaryColor: '#000000',
  backgroundColor: '#000000',
  accentColor: '#000000',
  companyName: '',
})
const brandingErrors = reactive<Record<string, string>>({})
const brandingBanner = ref('')
const isSavingBranding = ref(false)

async function loadBranding(): Promise<void> {
  isBrandingLoading.value = true
  brandingErrorMessage.value = null
  const result = await getBranding(slug)
  switch (result.kind) {
    case 'ok':
      Object.assign(brandingForm, result.value)
      isBrandingLoading.value = false
      break
    case 'reauth':
      break
    default:
      brandingErrorMessage.value = describeAdminError(result)
      isBrandingLoading.value = false
      break
  }
}

async function handleSaveBranding(): Promise<void> {
  if (isSavingBranding.value) return
  brandingBanner.value = ''
  for (const key of Object.keys(brandingErrors)) delete brandingErrors[key]

  isSavingBranding.value = true
  try {
    // Full replacement — all five fields, always. See tenantAdminBranding.ts's header.
    const result = await updateBranding(slug, { ...brandingForm })
    switch (result.kind) {
      case 'ok':
        Object.assign(brandingForm, result.value)
        break
      case 'validationErrors':
        Object.assign(brandingErrors, result.errors)
        break
      case 'conflict':
        brandingBanner.value = result.message
        break
      case 'reauth':
        break
      case 'error': {
        const field = BRANDING_ERROR_FIELDS[result.code]
        if (field) brandingErrors[field] = result.message
        else brandingBanner.value = result.message
        break
      }
    }
  } finally {
    isSavingBranding.value = false
  }
}

// ---- registration config --------------------------------------------------

const isRegLoading = ref(true)
const regErrorMessage = ref<string | null>(null)
const regForm = reactive<{ mode: RegistrationMode }>({ mode: 'EMAIL_VERIFIED' })
const regErrors = reactive<Record<string, string>>({})
const regBanner = ref('')
const isSavingReg = ref(false)

async function loadRegistrationConfig(): Promise<void> {
  isRegLoading.value = true
  regErrorMessage.value = null
  const result = await getRegistrationConfig(slug)
  switch (result.kind) {
    case 'ok':
      regForm.mode = result.value.mode
      isRegLoading.value = false
      break
    case 'reauth':
      break
    default:
      regErrorMessage.value = describeAdminError(result)
      isRegLoading.value = false
      break
  }
}

async function handleSaveRegistrationConfig(): Promise<void> {
  if (isSavingReg.value) return
  regBanner.value = ''
  for (const key of Object.keys(regErrors)) delete regErrors[key]

  isSavingReg.value = true
  try {
    const result = await updateRegistrationMode(slug, regForm.mode)
    switch (result.kind) {
      case 'ok':
        regForm.mode = result.value.mode
        break
      case 'validationErrors':
        Object.assign(regErrors, result.errors)
        break
      case 'conflict':
        regBanner.value = result.message
        break
      case 'reauth':
        break
      default:
        regBanner.value = describeAdminError(result)
        break
    }
  } finally {
    isSavingReg.value = false
  }
}

onMounted(() => {
  void loadBranding()
  void loadRegistrationConfig()
})
</script>

<template>
  <div class="flex flex-col gap-6 max-w-3xl">
    <div>
      <h1 class="text-xl font-semibold tracking-tight">Tenant settings</h1>
      <p class="text-sm text-muted-foreground">Branding for the hosted pages, and how new users can join this tenant.</p>
    </div>

    <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
      <h2 class="text-lg font-semibold tracking-tight">Branding</h2>

      <QueryState :loading="isBrandingLoading" :error="brandingErrorMessage">
        <form id="branding-form" class="flex flex-col gap-4" novalidate @submit.prevent="handleSaveBranding">
          <FormField id="branding-logo-url" label="Logo URL" :error="brandingErrors.logoUrl" hint="Must be an absolute https URL.">
            <template #default="{ hasError, describedBy }">
              <Input
                id="branding-logo-url"
                v-model="brandingForm.logoUrl"
                type="text"
                placeholder="https://cdn.example.com/logo.png"
                :disabled="isSavingBranding"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>

          <img
            v-if="hasBrandingLogo(brandingForm.logoUrl)"
            id="branding-logo-preview"
            :src="brandingForm.logoUrl"
            alt="Logo preview"
            class="h-10 w-auto object-contain rounded-md border border-border"
          />

          <div class="grid grid-cols-3 gap-3">
            <FormField id="branding-primary-color" label="Primary color" :error="brandingErrors.primaryColor">
              <template #default="{ hasError, describedBy }">
                <div class="flex items-center gap-2">
                  <input
                    type="color"
                    :value="brandingForm.primaryColor"
                    :disabled="isSavingBranding"
                    aria-hidden="true"
                    tabindex="-1"
                    class="h-9 w-9 shrink-0 rounded-md border border-input p-0.5"
                    @input="(e) => (brandingForm.primaryColor = (e.target as HTMLInputElement).value)"
                  />
                  <Input
                    id="branding-primary-color"
                    v-model="brandingForm.primaryColor"
                    type="text"
                    :disabled="isSavingBranding"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </div>
              </template>
            </FormField>

            <FormField id="branding-background-color" label="Background color" :error="brandingErrors.backgroundColor">
              <template #default="{ hasError, describedBy }">
                <div class="flex items-center gap-2">
                  <input
                    type="color"
                    :value="brandingForm.backgroundColor"
                    :disabled="isSavingBranding"
                    aria-hidden="true"
                    tabindex="-1"
                    class="h-9 w-9 shrink-0 rounded-md border border-input p-0.5"
                    @input="(e) => (brandingForm.backgroundColor = (e.target as HTMLInputElement).value)"
                  />
                  <Input
                    id="branding-background-color"
                    v-model="brandingForm.backgroundColor"
                    type="text"
                    :disabled="isSavingBranding"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </div>
              </template>
            </FormField>

            <FormField id="branding-accent-color" label="Accent color" :error="brandingErrors.accentColor">
              <template #default="{ hasError, describedBy }">
                <div class="flex items-center gap-2">
                  <input
                    type="color"
                    :value="brandingForm.accentColor"
                    :disabled="isSavingBranding"
                    aria-hidden="true"
                    tabindex="-1"
                    class="h-9 w-9 shrink-0 rounded-md border border-input p-0.5"
                    @input="(e) => (brandingForm.accentColor = (e.target as HTMLInputElement).value)"
                  />
                  <Input
                    id="branding-accent-color"
                    v-model="brandingForm.accentColor"
                    type="text"
                    :disabled="isSavingBranding"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </div>
              </template>
            </FormField>
          </div>
          <p class="text-xs text-muted-foreground">
            Saving pins these exact colors as this tenant's own — including any you didn't touch, which are currently
            inherited platform defaults. There is no way to revert a color to "inherited" from here afterward.
          </p>

          <FormField id="branding-company-name" label="Company name" :error="brandingErrors.companyName">
            <template #default="{ hasError, describedBy }">
              <Input
                id="branding-company-name"
                v-model="brandingForm.companyName"
                type="text"
                :disabled="isSavingBranding"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>

          <p v-if="brandingBanner" role="alert" class="text-sm text-destructive">{{ brandingBanner }}</p>

          <Button id="branding-save" type="submit" class="self-start" :disabled="isSavingBranding">
            {{ isSavingBranding ? 'Saving…' : 'Save branding' }}
          </Button>
        </form>
      </QueryState>
    </div>

    <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
      <h2 class="text-lg font-semibold tracking-tight">Registration</h2>

      <QueryState :loading="isRegLoading" :error="regErrorMessage">
        <form id="registration-form" class="flex flex-col gap-4" novalidate @submit.prevent="handleSaveRegistrationConfig">
          <div class="flex flex-col gap-1.5">
            <Label for="registration-mode">Registration mode</Label>
            <select
              id="registration-mode"
              v-model="regForm.mode"
              :disabled="isSavingReg"
              class="border-input h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]"
            >
              <option v-for="(desc, mode) in REGISTRATION_MODE_DESCRIPTIONS" :key="mode" :value="mode">
                {{ desc.label }}
              </option>
            </select>
            <p v-if="regErrors.mode" id="registration-mode-error" role="alert" class="text-sm text-destructive">
              {{ regErrors.mode }}
            </p>
          </div>

          <div id="registration-mode-consequence" class="rounded-md border border-border bg-muted px-3 py-2 text-sm">
            {{ REGISTRATION_MODE_DESCRIPTIONS[regForm.mode].consequence }}
          </div>

          <p v-if="regBanner" role="alert" class="text-sm text-destructive">{{ regBanner }}</p>

          <Button id="registration-save" type="submit" class="self-start" :disabled="isSavingReg">
            {{ isSavingReg ? 'Saving…' : 'Save registration mode' }}
          </Button>
        </form>
      </QueryState>
    </div>
  </div>
</template>
