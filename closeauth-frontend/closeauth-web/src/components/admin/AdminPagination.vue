<script setup lang="ts">
// Stage UI-3b: paging driven by a real PageView<T> (common/web/PageView.java)
// — page is 0-based and size is whatever the backend echoed back (it clamps
// the requested size to [1, 100]), so this component trusts the values it's
// given rather than computing its own notion of "current page." Nothing
// like this existed in the repo before this stage; every later paged
// surface (clients, roles, audit) reuses it as-is.
import { computed } from 'vue'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  page: number
  size: number
  totalElements: number
  totalPages: number
}>()

const emit = defineEmits<{ 'update:page': [page: number] }>()

const rangeStart = computed(() => (props.totalElements === 0 ? 0 : props.page * props.size + 1))
const rangeEnd = computed(() => Math.min((props.page + 1) * props.size, props.totalElements))
const hasPrev = computed(() => props.page > 0)
const hasNext = computed(() => props.page + 1 < props.totalPages)
</script>

<template>
  <div class="flex items-center justify-between gap-4 text-sm text-muted-foreground">
    <span id="admin-pagination-summary">Showing {{ rangeStart }}–{{ rangeEnd }} of {{ totalElements }}</span>
    <div class="flex items-center gap-2">
      <Button
        id="admin-pagination-prev"
        variant="outline"
        size="sm"
        :disabled="!hasPrev"
        @click="emit('update:page', page - 1)"
      >
        Previous
      </Button>
      <span class="font-mono text-xs">{{ totalPages === 0 ? 0 : page + 1 }} / {{ totalPages }}</span>
      <Button
        id="admin-pagination-next"
        variant="outline"
        size="sm"
        :disabled="!hasNext"
        @click="emit('update:page', page + 1)"
      >
        Next
      </Button>
    </div>
  </div>
</template>
