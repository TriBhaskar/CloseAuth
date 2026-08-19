<script setup lang="ts">
// Stage UI-3b: promotes RegisterView.vue's (UI-2b) hand-rolled
// label+input+role="alert" markup into one reusable component — the pattern
// every admin form (create-user here, clients/roles/branding later) should
// build on. The control itself is left to the caller's slot (Input,
// Select, whatever) since this component can't reach into slotted content
// to set aria-invalid/aria-describedby directly — instead it hands those
// back via a scoped slot for the caller to bind, same division of labor
// RegisterView.vue already used manually.
import { computed } from 'vue'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  id: string
  label: string
  error?: string | null
  hint?: string
}>()

const errorId = computed(() => `${props.id}-error`)
const hasError = computed(() => Boolean(props.error))
const describedBy = computed(() => (hasError.value ? errorId.value : undefined))
</script>

<template>
  <div class="flex flex-col gap-1.5">
    <Label :for="id">{{ label }}</Label>
    <slot :has-error="hasError" :described-by="describedBy" />
    <p v-if="hasError" :id="errorId" role="alert" class="text-sm text-destructive">{{ error }}</p>
    <p v-else-if="hint" class="text-xs text-muted-foreground">{{ hint }}</p>
  </div>
</template>
