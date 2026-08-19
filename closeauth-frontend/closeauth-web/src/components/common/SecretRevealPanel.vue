<script setup lang="ts">
// FE-1.8 (spec §5): "client secrets, temporary passwords, recovery values —
// show once: full-width warning, copy button, 'I've saved this' gate before
// the dialog can close; value is never re-fetchable and the UI says so."
// Built directly off views/tenant-admin/TenantClientCredentialsView.vue's
// proven pattern (masked-by-default reveal toggle, per-field copy, a
// required acknowledgement checkbox) rather than designed from scratch —
// that page already carries every rule this component names, just as a
// full routed page instead of a reusable dialog.
//
// Deliberately non-dismissible (spec §5, §8's one named exception to
// "dialogs... close on Esc"): no Esc, no backdrop click, no close (×)
// button. The only way out is the acknowledgement checkbox unlocking
// Continue. aria-describedby on the dialog root names the warning text so
// screen-reader users get the same "this won't ask twice" context sighted
// users get from the banner.
import { reactive } from 'vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import CopyButton from './CopyButton.vue'

export interface SecretField {
  id: string
  label: string
  value: string
  hint?: string
  /** Starts masked with a reveal toggle — use for the secret itself, not for a client_id that's safe to show plainly. */
  maskable?: boolean
}

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    warningMessage: string
    fields: SecretField[]
    acknowledgeLabel?: string
    continueLabel?: string
  }>(),
  {
    acknowledgeLabel: "I've saved this. I understand it cannot be shown again.",
    continueLabel: 'Continue',
  },
)

const emit = defineEmits<{ (e: 'continue'): void }>()

const revealed = reactive<Record<string, boolean>>({})
const acknowledged = reactive({ value: false })

function maskedValue(value: string): string {
  return '•'.repeat(Math.min(value.length, 32))
}

function displayValue(field: SecretField): string {
  if (!field.maskable) return field.value
  return revealed[field.id] ? field.value : maskedValue(field.value)
}

function handleContinue(): void {
  if (!acknowledged.value) return
  emit('continue')
}

// Blocks every dismiss path but the gated Continue action — see file header.
function preventDismiss(event: Event): void {
  event.preventDefault()
}
</script>

<template>
  <Dialog :open="props.open">
    <DialogContent
      :show-close-button="false"
      aria-describedby="secret-reveal-warning"
      @escape-key-down="preventDismiss"
      @pointer-down-outside="preventDismiss"
      @interact-outside="preventDismiss"
    >
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription id="secret-reveal-warning" role="alert" class="font-medium text-destructive">
          {{ warningMessage }}
        </DialogDescription>
      </DialogHeader>

      <div class="flex flex-col gap-5">
        <div v-for="field in fields" :key="field.id" class="flex flex-col gap-1.5">
          <Label :for="`secret-reveal-${field.id}`">{{ field.label }}</Label>
          <p v-if="field.hint" class="text-xs text-muted-foreground">{{ field.hint }}</p>
          <div class="flex items-center gap-2">
            <code
              :id="`secret-reveal-${field.id}`"
              class="flex-1 rounded-md border border-border bg-muted px-3 py-2 text-sm font-mono break-all"
            >
              {{ displayValue(field) }}
            </code>
            <Button
              v-if="field.maskable"
              type="button"
              variant="outline"
              size="sm"
              @click="revealed[field.id] = !revealed[field.id]"
            >
              {{ revealed[field.id] ? 'Hide' : 'Reveal' }}
            </Button>
            <CopyButton
              :value="field.value"
              class="inline-flex items-center justify-center rounded-md border border-input bg-background px-3 py-1.5 text-sm shadow-xs hover:bg-accent"
            />
          </div>
        </div>
      </div>

      <div class="flex flex-col gap-4">
        <div class="flex items-start gap-2">
          <Checkbox
            id="secret-reveal-ack"
            :model-value="acknowledged.value"
            class="mt-0.5"
            @update:model-value="(value) => (acknowledged.value = Boolean(value))"
          />
          <Label for="secret-reveal-ack" class="font-normal leading-snug">{{ acknowledgeLabel }}</Label>
        </div>
        <Button id="secret-reveal-continue" :disabled="!acknowledged.value" class="self-start" @click="handleContinue">
          {{ continueLabel }}
        </Button>
      </div>
    </DialogContent>
  </Dialog>
</template>
