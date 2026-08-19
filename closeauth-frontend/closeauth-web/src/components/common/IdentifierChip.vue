<script setup lang="ts">
// FE-1.4 (spec §3.5): CloseAuth's signature element — opaque identifiers get
// first-class treatment instead of being dumped as raw truncated text. This
// is the one component that should make a screenshot recognisable as
// CloseAuth; everything around it stays quiet.
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import CopyButton from './CopyButton.vue'

export type ChipKind = 'tenant' | 'user' | 'client' | 'resource-server' | 'scope' | 'session' | 'role'

const KIND_LABELS: Record<ChipKind, string> = {
  tenant: 'tenant',
  user: 'user',
  client: 'client',
  'resource-server': 'resource server',
  scope: 'scope',
  session: 'session',
  role: 'role',
}

// Full, literal class strings — never built from a template literal like
// `bg-chip-${kind}`. Tailwind's scanner finds classes by static text match
// in the source; a runtime-concatenated string is invisible to it and the
// utility never gets generated.
const KIND_RULE_CLASSES: Record<ChipKind, string> = {
  tenant: 'bg-chip-tenant',
  user: 'bg-chip-user',
  client: 'bg-chip-client',
  'resource-server': 'bg-chip-resource-server',
  scope: 'bg-chip-scope',
  session: 'bg-chip-session',
  role: 'bg-chip-role',
}

const props = defineProps<{
  value: string
  kind: ChipKind
  href?: string
}>()

// Middle truncation, never tail — the tail disambiguates (spec §3.5). Short
// enough values (below the head+ellipsis+tail budget) render unchanged.
const HEAD = 8
const TAIL = 4
const MIN_TRUNCATE_LENGTH = HEAD + TAIL + 1

function middleTruncate(value: string): string {
  if (value.length <= MIN_TRUNCATE_LENGTH) return value
  return `${value.slice(0, HEAD)}…${value.slice(-TAIL)}`
}

const displayValue = computed(() => middleTruncate(props.value))
const ariaLabel = computed(() => `${KIND_LABELS[props.kind]} ID ${props.value}`)
</script>

<template>
  <span
    class="inline-flex items-center gap-0 rounded font-mono text-[0.75rem] border border-line overflow-hidden"
  >
    <span class="w-0.5 self-stretch shrink-0" :class="KIND_RULE_CLASSES[kind]" aria-hidden="true" />
    <component
      :is="href ? RouterLink : 'span'"
      :to="href"
      :title="value"
      :aria-label="ariaLabel"
      class="px-1.5 py-0.5"
      :class="href ? 'hover:underline' : ''"
    >
      {{ displayValue }}
    </component>
    <CopyButton :value="value" label="Copy" copied-label="Copied" class="px-1.5 py-0.5 border-l border-line text-ink-muted hover:text-ink" />
  </span>
</template>
