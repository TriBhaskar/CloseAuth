<script setup lang="ts">
// FE-1.9 (spec §5, §3.4): "relative label, absolute UTC ISO in title, mono
// — every timestamp." Uses Intl.RelativeTimeFormat (built into the
// platform) rather than adding a date-formatting dependency.
import { computed } from 'vue'

const props = defineProps<{ value: string }>()

const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })

const DIVISIONS: Array<{ amount: number; unit: Intl.RelativeTimeFormatUnit }> = [
  { amount: 60, unit: 'seconds' },
  { amount: 60, unit: 'minutes' },
  { amount: 24, unit: 'hours' },
  { amount: 7, unit: 'days' },
  { amount: 4.34524, unit: 'weeks' },
  { amount: 12, unit: 'months' },
  { amount: Number.POSITIVE_INFINITY, unit: 'years' },
]

function relativeLabel(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso

  let duration = (date.getTime() - Date.now()) / 1000
  for (const division of DIVISIONS) {
    if (Math.abs(duration) < division.amount) {
      return formatter.format(Math.round(duration), division.unit)
    }
    duration /= division.amount
  }
  return iso
}

const label = computed(() => relativeLabel(props.value))
const isoTitle = computed(() => {
  const date = new Date(props.value)
  return Number.isNaN(date.getTime()) ? props.value : date.toISOString()
})
</script>

<template>
  <time :datetime="isoTitle" :title="isoTitle" class="font-mono">{{ label }}</time>
</template>
