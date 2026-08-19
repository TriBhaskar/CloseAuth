<script setup lang="ts">
// FE-4c: spec §6.4.3/§6.4.5 name ScopeSelector as the shared permission
// editor over a tenant's resource-server scope catalog. Extracted now
// because this component's wizard (step 3, "Scopes") is the real second
// consumer FE-4b's own deferral decision named as the trigger — spec's other
// named consumer, the application-role permission editor
// (TenantApplicationRoleDetailView.vue's inline checkbox list, built FE-4b),
// is left as-is: it's already correct and tested, and this arc's own
// precedent (FE-1b/FE-3b) is "build + prove at the new call site, no forced
// retrofit elsewhere."
//
// Purely presentational — accepts an already-fetched catalog (see
// api/tenantAdminScopeCatalog.ts) and a held-set, renders grouped
// checkboxes (a group header only when there's more than one group), emits
// toggles. Does not fetch, does not own pending/disabled state beyond an
// optional caller-supplied set (a future consumer that mutates one scope at
// a time, unlike this session's wizard which just accumulates local state
// until final submit).
//
// Selection keys are full `{resourceServerSlug}:{scopeName}` strings — the
// same shape every backend consumer of a scope list expects (see
// resourceserver package-info: "slug-prefixed scopes").
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import type { ScopeCatalogGroup } from '@/api/tenantAdminScopeCatalog'

const props = withDefaults(
  defineProps<{
    groups: ScopeCatalogGroup[]
    modelValue: Set<string>
    pendingKeys?: Set<string>
  }>(),
  { pendingKeys: () => new Set() },
)

const emit = defineEmits<{ (e: 'update:modelValue', value: Set<string>): void }>()

function scopeKey(group: ScopeCatalogGroup, scopeName: string): string {
  return `${group.resourceServerSlug}:${scopeName}`
}

function toggle(key: string): void {
  if (props.pendingKeys.has(key)) return
  const next = new Set(props.modelValue)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div v-if="groups.length === 0" class="text-sm text-muted-foreground">
      No resource servers exist yet in this tenant — nothing to select.
    </div>
    <div v-for="group in groups" :key="group.resourceServerId" class="flex flex-col gap-2">
      <p v-if="groups.length > 1" class="text-xs font-medium text-muted-foreground uppercase tracking-wide">
        {{ group.resourceServerName }}
      </p>
      <p v-if="group.scopes.length === 0" class="text-sm text-muted-foreground">No scopes published yet.</p>
      <div v-for="scope in group.scopes" :key="scope.id" class="flex items-center gap-2">
        <Checkbox
          :id="`scope-selector-${scopeKey(group, scope.scopeName)}`"
          :model-value="modelValue.has(scopeKey(group, scope.scopeName))"
          :disabled="pendingKeys.has(scopeKey(group, scope.scopeName))"
          @update:model-value="() => toggle(scopeKey(group, scope.scopeName))"
        />
        <Label :for="`scope-selector-${scopeKey(group, scope.scopeName)}`" class="font-mono text-sm">
          {{ scopeKey(group, scope.scopeName) }}
        </Label>
        <span v-if="scope.description" class="text-xs text-muted-foreground">{{ scope.description }}</span>
      </div>
    </div>
  </div>
</template>
