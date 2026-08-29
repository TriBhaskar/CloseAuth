<script setup lang="ts">
// FE-5.3 (spec §6.4.7 tab 1): branding, split out of the old two-card
// TenantSettingsView.vue (Stage UI-3e) into its own tab. Same GET/PUT
// idiom as every other admin settings surface (page-level QueryState per
// card, `novalidate @submit.prevent`, a self-start submit button, a
// role="alert" banner) — nothing here needed a new pattern.
//
// New this session: a live login-card preview (BrandingLoginPreview.vue)
// beside the form, and dirty-state tracking reported up to the settings
// shell via `update:dirty` (FE-5.7) so switching tabs or leaving the page
// mid-edit prompts before discarding.
//
// PUT is a full replacement — the form always sends all five fields, seeded
// from the last GET/PUT response, never a sparse object (see
// api/tenantAdminBranding.ts's header). The logo preview reuses
// hasBrandingLogo (src/lib/branding.ts), the same empty-string guard the
// hosted login page enforces for <img src="">.
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import QueryState from '@/components/admin/QueryState.vue'
import FormField from '@/components/common/FormField.vue'
import BrandingLoginPreview from '@/components/common/BrandingLoginPreview.vue'
import { errorStateProps } from '@/api/problem'
import {
  getBranding,
  updateBranding,
  BRANDING_ERROR_FIELDS,
  type BrandingView,
} from '@/api/tenantAdminBranding'
import { hasBrandingLogo } from '@/lib/branding'

const props = defineProps<{ slug: string }>()
const emit = defineEmits<{ 'update:dirty': [dirty: boolean] }>()

const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const errorRetryable = ref(true)
const form = reactive<BrandingView>({
  logoUrl: '',
  primaryColor: '#000000',
  backgroundColor: '#000000',
  accentColor: '#000000',
  companyName: '',
})
const snapshot = ref<BrandingView>({ ...form })
const errors = reactive<Record<string, string>>({})
const banner = ref('')
const isSaving = ref(false)

const isDirty = computed(() => JSON.stringify(form) !== JSON.stringify(snapshot.value))
watch(isDirty, (value) => emit('update:dirty', value))

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getBranding(props.slug)
  switch (result.kind) {
    case 'ok':
      Object.assign(form, result.value)
      snapshot.value = { ...result.value }
      isLoading.value = false
      break
    case 'reauth':
      break
    default: {
      const props = errorStateProps(result)
      errorMessage.value = props.message
      errorRetryable.value = props.retryable
      isLoading.value = false
      break
    }
  }
}

async function handleSave(): Promise<void> {
  if (isSaving.value) return
  banner.value = ''
  for (const key of Object.keys(errors)) delete errors[key]

  isSaving.value = true
  try {
    // Full replacement — all five fields, always. See tenantAdminBranding.ts's header.
    const result = await updateBranding(props.slug, { ...form })
    switch (result.kind) {
      case 'ok':
        Object.assign(form, result.value)
        snapshot.value = { ...result.value }
        break
      case 'validationErrors':
        Object.assign(errors, result.errors)
        break
      case 'conflict':
        banner.value = result.message
        break
      case 'reauth':
        break
      case 'error': {
        const field = BRANDING_ERROR_FIELDS[result.code]
        if (field) errors[field] = result.message
        else banner.value = result.message
        break
      }
    }
  } finally {
    isSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-[1fr_auto] gap-6 items-start">
    <div class="rounded-xl border border-border p-6 flex flex-col gap-4">
      <h2 class="text-lg font-semibold tracking-tight">Branding</h2>

      <QueryState
        :loading="isLoading"
        :error="errorMessage"
        :retryable="errorRetryable"
        @retry="load"
      >
        <form
          id="branding-form"
          class="flex flex-col gap-4"
          novalidate
          @submit.prevent="handleSave"
        >
          <FormField
            id="branding-logo-url"
            label="Logo URL"
            :error="errors.logoUrl"
            hint="Must be an absolute https URL."
          >
            <template #default="{ hasError, describedBy }">
              <Input
                id="branding-logo-url"
                v-model="form.logoUrl"
                type="text"
                placeholder="https://cdn.example.com/logo.png"
                :disabled="isSaving"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>

          <img
            v-if="hasBrandingLogo(form.logoUrl)"
            id="branding-logo-preview"
            :src="form.logoUrl"
            alt="Logo preview"
            class="h-10 w-auto object-contain rounded-md border border-border"
          />

          <!-- FE-6.4: three (swatch + text input) pairs side by side
               overflow at 375px with no breakpoint — single column below
               sm, three columns at sm and up. -->
          <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <FormField
              id="branding-primary-color"
              label="Primary color"
              :error="errors.primaryColor"
            >
              <template #default="{ hasError, describedBy }">
                <div class="flex items-center gap-2">
                  <input
                    type="color"
                    :value="form.primaryColor"
                    :disabled="isSaving"
                    aria-hidden="true"
                    tabindex="-1"
                    class="h-9 w-9 shrink-0 rounded-md border border-input p-0.5"
                    @input="(e) => (form.primaryColor = (e.target as HTMLInputElement).value)"
                  />
                  <Input
                    id="branding-primary-color"
                    v-model="form.primaryColor"
                    type="text"
                    :disabled="isSaving"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </div>
              </template>
            </FormField>

            <FormField
              id="branding-background-color"
              label="Background color"
              :error="errors.backgroundColor"
            >
              <template #default="{ hasError, describedBy }">
                <div class="flex items-center gap-2">
                  <input
                    type="color"
                    :value="form.backgroundColor"
                    :disabled="isSaving"
                    aria-hidden="true"
                    tabindex="-1"
                    class="h-9 w-9 shrink-0 rounded-md border border-input p-0.5"
                    @input="(e) => (form.backgroundColor = (e.target as HTMLInputElement).value)"
                  />
                  <Input
                    id="branding-background-color"
                    v-model="form.backgroundColor"
                    type="text"
                    :disabled="isSaving"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </div>
              </template>
            </FormField>

            <FormField id="branding-accent-color" label="Accent color" :error="errors.accentColor">
              <template #default="{ hasError, describedBy }">
                <div class="flex items-center gap-2">
                  <input
                    type="color"
                    :value="form.accentColor"
                    :disabled="isSaving"
                    aria-hidden="true"
                    tabindex="-1"
                    class="h-9 w-9 shrink-0 rounded-md border border-input p-0.5"
                    @input="(e) => (form.accentColor = (e.target as HTMLInputElement).value)"
                  />
                  <Input
                    id="branding-accent-color"
                    v-model="form.accentColor"
                    type="text"
                    :disabled="isSaving"
                    :aria-invalid="hasError"
                    :aria-describedby="describedBy"
                  />
                </div>
              </template>
            </FormField>
          </div>
          <p class="text-xs text-muted-foreground">
            Saving pins these exact colors as this tenant's own — including any you didn't touch,
            which are currently inherited platform defaults. There is no way to revert a color to
            "inherited" from here afterward.
          </p>

          <FormField id="branding-company-name" label="Company name" :error="errors.companyName">
            <template #default="{ hasError, describedBy }">
              <Input
                id="branding-company-name"
                v-model="form.companyName"
                type="text"
                :disabled="isSaving"
                :aria-invalid="hasError"
                :aria-describedby="describedBy"
              />
            </template>
          </FormField>

          <p v-if="banner" role="alert" class="text-sm text-destructive">{{ banner }}</p>

          <Button id="branding-save" type="submit" class="self-start" :disabled="isSaving">
            {{ isSaving ? 'Saving…' : 'Save branding' }}
          </Button>
        </form>
      </QueryState>
    </div>

    <div class="flex flex-col gap-2">
      <p class="text-xs font-medium text-muted-foreground">Live preview</p>
      <BrandingLoginPreview :branding="form" />
    </div>
  </div>
</template>
