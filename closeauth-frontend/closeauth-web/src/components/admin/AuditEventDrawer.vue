<script setup lang="ts">
// FE-5.1 (spec §6.4.6): "Drawer: full event with JsonViewer for the payload,
// and IdentifierChip links to the entities involved." Extracted out of
// TenantAuditView.vue's inline expand-row (Stage UI-3e) into its own
// component so the view stays a filter-bar + DataTable composition, same
// separation TenantUserDetailView.vue's tabs already follow.
//
// JsonViewer's very first real caller (its own file header note said so
// explicitly — built at FE-1.9, unwired until now).
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import IdentifierChip from '@/components/common/IdentifierChip.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import JsonViewer from '@/components/common/JsonViewer.vue'
import StateBadge, { auditOutcomeTone } from '@/components/common/StateBadge.vue'
import { describeAuditActor, type AuditEventView } from '@/api/tenantAdminAudit'

defineProps<{
  open: boolean
  event: AuditEventView | null
}>()

defineEmits<{ 'update:open': [open: boolean] }>()
</script>

<template>
  <Sheet :open="open" @update:open="(v: boolean) => $emit('update:open', v)">
    <SheetContent v-if="event" id="audit-event-drawer" class="overflow-y-auto p-6">
      <SheetHeader class="p-0">
        <SheetTitle class="font-mono text-base">{{ event.eventType }}</SheetTitle>
        <SheetDescription>
          <RelativeTime :value="event.createdAt" />
        </SheetDescription>
      </SheetHeader>

      <dl class="flex flex-col gap-3 text-sm">
        <div class="flex items-center justify-between gap-2">
          <dt class="text-muted-foreground">Outcome</dt>
          <dd><StateBadge :tone="auditOutcomeTone(event.outcome)" :label="event.outcome" /></dd>
        </div>

        <div v-if="event.subjectUserId" class="flex items-center justify-between gap-2">
          <dt class="text-muted-foreground">Subject user</dt>
          <dd><IdentifierChip kind="user" :value="event.subjectUserId" /></dd>
        </div>

        <div class="flex items-center justify-between gap-2">
          <dt class="text-muted-foreground">Actor</dt>
          <dd class="flex items-center gap-2">
            <IdentifierChip v-if="event.actorUserId" kind="user" :value="event.actorUserId" />
            <IdentifierChip
              v-else-if="event.actorClientRegisteredId"
              kind="client"
              :value="event.actorClientRegisteredId"
            />
            <span v-else class="text-xs">{{ describeAuditActor(event) }}</span>
          </dd>
        </div>

        <div v-if="event.resourceServerId" class="flex items-center justify-between gap-2">
          <dt class="text-muted-foreground">Resource server</dt>
          <dd><IdentifierChip kind="resource-server" :value="event.resourceServerId" /></dd>
        </div>

        <div v-if="event.ipAddress" class="flex items-center justify-between gap-2">
          <dt class="text-muted-foreground">IP address</dt>
          <dd class="font-mono text-xs">{{ event.ipAddress }}</dd>
        </div>

        <div v-if="event.userAgent" class="flex flex-col gap-1">
          <dt class="text-muted-foreground">User agent</dt>
          <dd class="text-xs break-all">{{ event.userAgent }}</dd>
        </div>

        <div v-if="event.errorCode" class="flex items-center justify-between gap-2">
          <dt class="text-muted-foreground">Error code</dt>
          <dd class="font-mono text-xs text-destructive">{{ event.errorCode }}</dd>
        </div>

        <div class="flex flex-col gap-1.5">
          <dt class="text-muted-foreground">Event data</dt>
          <dd><JsonViewer :value="event.eventData" /></dd>
        </div>

        <div class="flex items-center justify-between gap-2 border-t border-border pt-3">
          <dt class="text-muted-foreground">Event ID</dt>
          <dd class="font-mono text-xs break-all">{{ event.id }}</dd>
        </div>
      </dl>
    </SheetContent>
  </Sheet>
</template>
