<script setup lang="ts">
// FE-1.8 (spec §5): the confirmation gate for IRREVERSIBLE actions (delete
// tenant, revoke all sessions, delete client) — ConfirmDialog's shape plus
// a required exact-match text input, so the primary button can't be reached
// by habit-clicking through a dialog whose consequence is permanent.
import { computed, ref, watch } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description: string
    /** The exact string the user must type — e.g. the tenant's Tenant ID or name. Case-sensitive. */
    matchText: string
    matchLabel?: string
    confirmLabel?: string
    pending?: boolean
  }>(),
  { matchLabel: 'Type to confirm', confirmLabel: 'Delete', pending: false },
)

const emit = defineEmits<{ 'update:open': [open: boolean]; confirm: [] }>()

const typed = ref('')

// Cleared whenever the dialog closes (either way) so a re-open never starts
// pre-filled with a stale, already-matching value.
watch(
  () => props.open,
  (open) => {
    if (!open) typed.value = ''
  },
)

const canConfirm = computed(() => typed.value === props.matchText && !props.pending)
</script>

<template>
  <Dialog :open="props.open" @update:open="(value: boolean) => emit('update:open', value)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription>{{ description }}</DialogDescription>
      </DialogHeader>

      <div class="flex flex-col gap-1.5">
        <Label for="typed-confirm-input">
          {{ matchLabel }} <span class="font-mono">{{ matchText }}</span>
        </Label>
        <Input id="typed-confirm-input" v-model="typed" autocomplete="off" :disabled="pending" />
      </div>

      <DialogFooter>
        <Button variant="outline" :disabled="pending" @click="emit('update:open', false)">Cancel</Button>
        <Button
          id="typed-confirm-dialog-confirm"
          variant="destructive"
          :disabled="!canConfirm"
          @click="emit('confirm')"
        >
          {{ pending ? 'Working…' : confirmLabel }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
