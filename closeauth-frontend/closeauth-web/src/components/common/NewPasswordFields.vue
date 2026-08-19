<script setup lang="ts">
// Phase 4a: the shared "type a new password twice" form body, extracted so
// ResetPasswordView.vue (self-service reset) and PasswordRotationView.vue
// (forced rotation) share ONE implementation of the fields + match check,
// per decision 8's intent — without forcing the two flows into a single
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
//
// FE-2c: two additive extensions for RegisterView.vue, which also needs
// email/name/phone fields in the SAME submission — nesting an HTML <form>
// inside another isn't valid, so a second <form> (this component's own)
// can't just wrap around a caller's other fields.
//   - `bare` (default false, opt-in): renders only the two inputs + their
//     errors + the checklist below — no <form>/submit button of its own.
//     The caller owns the outer <form> and calls the newly-exposed
//     `validate()` at ITS OWN submit time instead of listening for
//     `@submit`. ResetPasswordView/PasswordRotationView never pass this —
//     their rendered output and specs are unaffected.
//   - `showChecklist` (default false, opt-in, independent of `bare`):
//     a live ✓/○ list for "at least N characters" / "passwords match" —
//     the only two rules this component can honestly show, since no
//     backend-exposed password-complexity policy exists beyond length.
//     Deliberately NOT tied to `minLength > 0` (which would have silently
//     changed PasswordRotationView's rendered output) — a caller opts in
//     explicitly.
import { computed, reactive } from 'vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'

const props = withDefaults(
  defineProps<{
    idPrefix: string
    // Optional (not just unused) in bare mode — no submit button renders
    // there at all, so a bare-mode caller has nothing sensible to pass.
    submitLabel?: string
    submittingLabel?: string
    isSubmitting: boolean
    bannerMessage?: string
    minLength?: number
    bare?: boolean
    showChecklist?: boolean
  }>(),
  {
    submitLabel: '',
    submittingLabel: '',
    bannerMessage: '',
    minLength: 0,
    bare: false,
    showChecklist: false,
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

// Checklist display only — falls back to 8 (RegisterUserCommand's own
// @Size(min=8,...) bound) so the copy is sensible even for a caller that
// enables the checklist without also setting minLength explicitly.
const effectiveMinLength = computed(() => (props.minLength > 0 ? props.minLength : 8))
const meetsMinLength = computed(() => form.password.length >= effectiveMinLength.value)
const passwordsMatch = computed(() => form.confirmPassword.length > 0 && form.confirmPassword === form.password)

function clearFieldErrors(): void {
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
}

// Shared by handleSubmit (form mode) and validate() (bare mode) — the SAME
// two checks, in the SAME order, so neither mode can silently drift from
// the other.
function runValidation(): boolean {
  clearFieldErrors()

  if (props.minLength > 0 && form.password.length < props.minLength) {
    fieldErrors.password = `Password must be at least ${props.minLength} characters.`
    return false
  }
  if (form.confirmPassword !== form.password) {
    fieldErrors.confirmPassword = 'Passwords do not match.'
    return false
  }
  return true
}

function handleSubmit(): void {
  if (props.isSubmitting) return
  if (!runValidation()) return
  emit('submit', form.password)
}

// Bare mode's entry point: the caller's own <form> submit handler calls
// this instead of listening for `@submit`. Returns the password on success
// (field errors already populated for display otherwise), null on failure —
// mirrors handleSubmit's checks exactly via runValidation.
function validate(): string | null {
  if (props.isSubmitting) return null
  if (!runValidation()) return null
  return form.password
}

// Exposed so a caller can clear the fields after a successful submission
// that doesn't unmount the form (not currently needed by either
// form-mode view — both navigate away or show a separate success block —
// but kept small and explicit rather than silently leaving stale values in
// a form that could be shown again). `validate` is bare mode's real entry
// point (see above).
defineExpose({ clearFieldErrors, validate })
</script>

<template>
  <component
    :is="bare ? 'div' : 'form'"
    class="flex flex-col gap-4"
    v-bind="bare ? {} : { novalidate: true }"
    @submit.prevent="handleSubmit"
  >
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

    <ul v-if="showChecklist" class="flex flex-col gap-1 text-sm" aria-live="polite">
      <li :class="meetsMinLength ? 'text-foreground' : 'text-muted-foreground'">
        <span aria-hidden="true">{{ meetsMinLength ? '✓' : '○' }}</span>
        At least {{ effectiveMinLength }} characters
      </li>
      <li :class="passwordsMatch ? 'text-foreground' : 'text-muted-foreground'">
        <span aria-hidden="true">{{ passwordsMatch ? '✓' : '○' }}</span>
        Passwords match
      </li>
    </ul>

    <p v-if="bannerMessage" role="alert" class="text-sm text-destructive">{{ bannerMessage }}</p>

    <Button v-if="!bare" type="submit" class="w-full" :disabled="isSubmitting">
      {{ isSubmitting ? submittingLabel : submitLabel }}
    </Button>
  </component>
</template>
