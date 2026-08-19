<script setup lang="ts">
// FE-1.9 (spec §5): "collapsed by default, mono, copy-all." Built now per
// the build plan's task list; its real consumer (audit event payloads)
// lands in FE-5 — not wired anywhere yet.
import { ref, computed } from 'vue'
import CopyButton from './CopyButton.vue'

const props = defineProps<{ value: unknown }>()

const expanded = ref(false)
const json = computed(() => JSON.stringify(props.value, null, 2))
</script>

<template>
  <div class="rounded border border-line">
    <div class="flex items-center justify-between gap-2 px-2 py-1 border-b border-line">
      <button
        type="button"
        class="text-[0.75rem] font-medium text-ink-muted hover:text-ink"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        {{ expanded ? 'Collapse' : 'Expand' }}
      </button>
      <CopyButton :value="json" label="Copy all" copied-label="Copied" class="text-[0.75rem] text-ink-muted hover:text-ink" />
    </div>
    <pre v-if="expanded" class="font-mono text-[0.75rem] p-2 overflow-auto bg-surface-sunken">{{ json }}</pre>
  </div>
</template>
