<script setup lang="ts">
import { computed } from 'vue'
import { Badge } from '@/components/ui/badge'
import type { UserStatus } from '@/api/tenantAdminUsers'

const props = defineProps<{ status: UserStatus }>()

const variant = computed<'default' | 'secondary' | 'destructive' | 'outline'>(() => {
  switch (props.status) {
    case 'ACTIVE':
      return 'default'
    case 'PENDING':
      return 'secondary'
    case 'SUSPENDED':
      return 'destructive'
    case 'DELETED':
      return 'outline'
    default:
      return 'outline'
  }
})
</script>

<template>
  <Badge :variant="variant">{{ status }}</Badge>
</template>
