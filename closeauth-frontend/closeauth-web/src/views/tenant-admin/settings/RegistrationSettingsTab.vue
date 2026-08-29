<script setup lang="ts">
// FE-5.4 (spec §6.4.7 tab 2): registration mode, split out of Stage UI-3e's
// TenantSettingsView.vue. Four radio CARDS instead of a bare <select> — spec
// explicitly wants "four radio cards, each stating exactly what a new user
// experiences" — so every mode's consequence is readable at a glance, not
// just the currently-selected one, the same idiom TenantUsersView.vue's
// create-user dialog already uses for its two modes.
//
// The <select>'s literal-union constraint carries over verbatim: an
// unrecognised mode string is a backend 500, not a 400
// (api/tenantAdminRegistrationConfig.ts's header) — four fixed radio
// options are what actually prevents an admin from ever triggering it, not
// any client-side validation.
//
// FE-5.4's honest gap notice: email verification IS the EMAIL_VERIFIED
// mode, not a separate toggle, and allowed email domains aren't stored per
// tenant today (tenant_registration_config has a `mode` column only).
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import QueryState from '@/components/admin/QueryState.vue'
import { describeAdminError, errorStateProps } from '@/api/problem'
import {
  getRegistrationConfig,
  updateRegistrationMode,
  REGISTRATION_MODE_DESCRIPTIONS,
  type RegistrationMode,
} from '@/api/tenantAdminRegistrationConfig'

const props = defineProps<{ slug: string }>()
const emit = defineEmits<{ 'update:dirty': [dirty: boolean] }>()

const MODES = Object.keys(REGISTRATION_MODE_DESCRIPTIONS) as RegistrationMode[]

const isLoading = ref(true)
const errorMessage = ref<string | null>(null)
const errorRetryable = ref(true)
const form = reactive<{ mode: RegistrationMode }>({ mode: 'EMAIL_VERIFIED' })
const snapshotMode = ref<RegistrationMode>('EMAIL_VERIFIED')
const errors = reactive<Record<string, string>>({})
const banner = ref('')
const isSaving = ref(false)

const isDirty = computed(() => form.mode !== snapshotMode.value)
watch(isDirty, (value) => emit('update:dirty', value))

async function load(): Promise<void> {
  isLoading.value = true
  errorMessage.value = null
  const result = await getRegistrationConfig(props.slug)
  switch (result.kind) {
    case 'ok':
      form.mode = result.value.mode
      snapshotMode.value = result.value.mode
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
    const result = await updateRegistrationMode(props.slug, form.mode)
    switch (result.kind) {
      case 'ok':
        form.mode = result.value.mode
        snapshotMode.value = result.value.mode
        break
      case 'validationErrors':
        Object.assign(errors, result.errors)
        break
      case 'conflict':
        banner.value = result.message
        break
      case 'reauth':
        break
      default:
        banner.value = describeAdminError(result)
        break
    }
  } finally {
    isSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="rounded-xl border border-border p-6 flex flex-col gap-4 max-w-2xl">
    <h2 class="text-lg font-semibold tracking-tight">Registration</h2>
    <p class="text-sm text-muted-foreground">How new users can join this tenant.</p>

    <QueryState
      :loading="isLoading"
      :error="errorMessage"
      :retryable="errorRetryable"
      @retry="load"
    >
      <form
        id="registration-form"
        class="flex flex-col gap-4"
        novalidate
        @submit.prevent="handleSave"
      >
        <fieldset class="flex flex-col gap-2">
          <legend class="sr-only">Registration mode</legend>
          <label
            v-for="mode in MODES"
            :key="mode"
            class="flex items-start gap-3 rounded-md border border-line p-3 cursor-pointer"
            :class="form.mode === mode ? 'border-primary' : ''"
          >
            <input
              :id="`registration-mode-${mode}`"
              v-model="form.mode"
              type="radio"
              name="registration-mode"
              :value="mode"
              class="mt-1"
              :disabled="isSaving"
            />
            <span class="flex flex-col gap-0.5">
              <span class="text-sm font-medium">{{
                REGISTRATION_MODE_DESCRIPTIONS[mode].label
              }}</span>
              <span class="text-xs text-muted-foreground">{{
                REGISTRATION_MODE_DESCRIPTIONS[mode].consequence
              }}</span>
            </span>
          </label>
        </fieldset>

        <p
          v-if="errors.mode"
          id="registration-mode-error"
          role="alert"
          class="text-sm text-destructive"
        >
          {{ errors.mode }}
        </p>

        <div
          id="registration-gap-notice"
          class="rounded-md border border-border bg-muted px-3 py-2 text-xs text-muted-foreground"
        >
          Email verification is the "Email verified" mode above, not a separate toggle — and allowed
          email domains aren't configurable per tenant yet.
        </div>

        <p v-if="banner" role="alert" class="text-sm text-destructive">{{ banner }}</p>

        <Button id="registration-save" type="submit" class="self-start" :disabled="isSaving">
          {{ isSaving ? 'Saving…' : 'Save registration mode' }}
        </Button>
      </form>
    </QueryState>
  </div>
</template>
