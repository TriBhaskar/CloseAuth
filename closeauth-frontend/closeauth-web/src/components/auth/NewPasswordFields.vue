<script setup lang="ts">
// Phase 4a: the shared "type a new password twice" form body, extracted so
// ResetPasswordView.vue (self-service reset) and PasswordRotationView.vue
// (forced rotation) share ONE implementation of the fields + match check,
// per decision 8's intent — without forcing the two views into a single
// file with a runtime mode branch. Everything that differs between the two
// flows (submit target, success semantics, branding source, error copy,
// navigation-vs-inline-success) stays in the calling view; this component
// owns only the two inputs and their client-side validation.
//
// idPrefix exists so ResetPasswordView keeps its exact existing element ids
// (#reset-password-new / #reset-password-confirm) — ResetPasswordView.spec.ts
// asserts those ids and must pass unmodified; that's the regression guard
// for the frozen self-service-reset flow.
//
// The min-length rule is opt-in (minLength prop, default 0 = off) rather
// than baked in, because the two flows deliberately enforce different
// policy here: rotation gets a client-side min-8/max-200 guard (mirroring
// RegisterUserCommand's @Size(min=8,max=200) — the backend applies NO
// server-side policy on the rotation-confirm path, so this is UX only, not
// a control), while self-service reset stays exactly as it was to avoid
// tightening a flow this phase isn't scoped to change.
import { computed, reactive } from 'vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'

const props = withDefaults(
  defineProps<{
    idPrefix: string
    submitLabel: string
    submittingLabel: string
    isSubmitting: boolean
    bannerMessage?: string
    minLength?: number
  }>(),
  {
    bannerMessage: '',
    minLength: 0,
  },
)

const emit = defineEmits<{
  submit: [password: string]
}>()

const form = reactive({
  password: '',
  confirmPassword: '',
})
const fieldErrors = reactive<Record<string, string>>({})

const newId = computed(() => `${props.idPrefix}-new`)
const confirmId = computed(() => `${props.idPrefix}-confirm`)

function clearFieldErrors(): void {
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
}

function handleSubmit(): void {
  if (props.isSubmitting) return
  clearFieldErrors()

  if (props.minLength > 0 && form.password.length < props.minLength) {
    fieldErrors.password = `Password must be at least ${props.minLength} characters.`
    return
  }
  if (form.confirmPassword !== form.password) {
    fieldErrors.confirmPassword = 'Passwords do not match.'
    return
  }

  emit('submit', form.password)
}

// Exposed so a caller can clear the fields after a successful submission
// that doesn't unmount the form (not currently needed by either view — both
// navigate away or show a separate success block — but kept small and
// explicit rather than silently leaving stale values in a form that could
// be shown again).
defineExpose({ clearFieldErrors })
</script>

<template>
  <form class="flex flex-col gap-4" novalidate @submit.prevent="handleSubmit">
    <div class="flex flex-col gap-1.5">
      <Label :for="newId">New password</Label>
      <Input
        :id="newId"
        v-model="form.password"
        type="password"
        autocomplete="new-password"
        required
        :disabled="isSubmitting"
        :aria-invalid="!!fieldErrors.password"
      />
      <p v-if="fieldErrors.password" role="alert" class="text-sm text-destructive">{{ fieldErrors.password }}</p>
    </div>

    <div class="flex flex-col gap-1.5">
      <Label :for="confirmId">Confirm new password</Label>
      <Input
        :id="confirmId"
        v-model="form.confirmPassword"
        type="password"
        autocomplete="new-password"
        required
        :disabled="isSubmitting"
        :aria-invalid="!!fieldErrors.confirmPassword"
      />
      <p v-if="fieldErrors.confirmPassword" role="alert" class="text-sm text-destructive">
        {{ fieldErrors.confirmPassword }}
      </p>
    </div>

    <p v-if="bannerMessage" role="alert" class="text-sm text-destructive">{{ bannerMessage }}</p>

    <Button type="submit" class="w-full" :disabled="isSubmitting">
      {{ isSubmitting ? submittingLabel : submitLabel }}
    </Button>
  </form>
</template>
