<script setup lang="ts">
// Stage UI-3b: the confirmation gate for consequential actions (suspend,
// delete). Deliberately leaves the copy to the caller — only the caller
// knows the SPECIFIC consequence worth naming ("this revokes the user's
// live sessions immediately," "this is terminal — there is no undo"), per
// the plan's "copy that carries real consequences" rule; this component
// just frames title + consequence body + a destructive confirm action and
// disables both buttons while `pending` so a slow request can't be
// double-submitted.
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description: string
    confirmLabel?: string
    destructive?: boolean
    pending?: boolean
  }>(),
  { confirmLabel: 'Confirm', destructive: true, pending: false },
)

const emit = defineEmits<{ 'update:open': [open: boolean]; confirm: [] }>()
</script>

<template>
  <Dialog :open="props.open" @update:open="(value: boolean) => emit('update:open', value)">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription>{{ description }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" :disabled="pending" @click="emit('update:open', false)">Cancel</Button>
        <Button
          id="confirm-dialog-confirm"
          :variant="destructive ? 'destructive' : 'default'"
          :disabled="pending"
          @click="emit('confirm')"
        >
          {{ pending ? 'Working…' : confirmLabel }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
