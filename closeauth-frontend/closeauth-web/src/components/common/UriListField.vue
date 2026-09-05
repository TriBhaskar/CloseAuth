<script setup lang="ts">
// Extracted from CreateClientDialog.vue (client update/delete stage) so the
// new client edit form (TenantClientDetailView.vue) and the create wizard
// share one repeatable-URI-row editor instead of two drifting copies.
// Per-row format errors are computed by the caller via
// lib/uriValidation.ts's validateUri/validateUriList — this component only
// owns the row add/remove UI and rendering, same split CreateClientDialog.vue
// already had between validateDetails() and its template.
//
// idPrefix reproduces CreateClientDialog.vue's exact existing ids
// (client-wizard-redirect-{i}/-add/-remove-{i}, client-wizard-post-logout-*)
// so its own tests needed no id changes when it switched to this component.
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  modelValue: string[]
  errors: Record<number, string>
  label: string
  idPrefix: string
  placeholder: string
  addLabel: string
  /** Rendered once below the Add button — the group-level "at least one required" message. */
  groupError?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

function updateRow(index: number, value: string): void {
  const next = [...props.modelValue]
  next[index] = value
  emit('update:modelValue', next)
}

function addRow(): void {
  emit('update:modelValue', [...props.modelValue, ''])
}

function removeRow(index: number): void {
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <Label>{{ label }}</Label>
    <div v-for="(uri, index) in modelValue" :key="index" class="flex flex-col gap-1">
      <div class="flex items-center gap-2">
        <Input
          :id="`${idPrefix}-${index}`"
          :model-value="uri"
          type="text"
          :placeholder="placeholder"
          :aria-invalid="Boolean(errors[index])"
          @update:model-value="(v: string | number) => updateRow(index, String(v))"
        />
        <Button
          v-if="modelValue.length > 1"
          :id="`${idPrefix}-remove-${index}`"
          type="button"
          variant="ghost"
          size="sm"
          @click="removeRow(index)"
        >
          Remove
        </Button>
      </div>
      <p v-if="errors[index]" role="alert" class="text-sm text-destructive">
        {{ errors[index] }}
      </p>
    </div>
    <Button
      :id="`${idPrefix}-add`"
      type="button"
      variant="outline"
      size="sm"
      class="self-start"
      @click="addRow"
    >
      {{ addLabel }}
    </Button>
    <p v-if="groupError" role="alert" class="text-sm text-destructive">
      {{ groupError }}
    </p>
  </div>
</template>
