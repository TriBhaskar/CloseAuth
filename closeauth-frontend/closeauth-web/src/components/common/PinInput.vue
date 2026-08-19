<script setup lang="ts">
// FE-2d (spec §6.2.4): "6-digit input, auto-advance, paste-aware." Nothing
// in components/ui/ covers this — built from scratch, no new dependency
// (matching this codebase's own precedent, e.g. RelativeTime.vue using
// native Intl.RelativeTimeFormat rather than a library).
//
// Deliberately no auto-submit on completion — every other form in this
// codebase requires an explicit submit action; auto-firing a network call
// the instant the 6th digit lands would be the one exception, not a
// consistency win.
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    modelValue: string
    length?: number
    idPrefix?: string
    disabled?: boolean
  }>(),
  {
    length: 6,
    idPrefix: 'pin-input',
    disabled: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const digits = reactive<string[]>(Array.from({ length: props.length }, () => ''))
const boxRefs = ref<(HTMLInputElement | null)[]>([])

function syncFromModelValue(value: string): void {
  const chars = value.split('').slice(0, props.length)
  for (let i = 0; i < props.length; i++) {
    digits[i] = chars[i] ?? ''
  }
}

// External resets (e.g. the caller clears the code after a failed attempt)
// flow back in; internal edits below flow out via emit, not this watcher —
// avoids a feedback loop.
watch(() => props.modelValue, syncFromModelValue, { immediate: true })

function emitValue(): void {
  emit('update:modelValue', digits.join(''))
}

function focusBox(index: number): void {
  const clamped = Math.max(0, Math.min(index, props.length - 1))
  void nextTick(() => boxRefs.value[clamped]?.focus())
}

function handleInput(index: number, event: Event): void {
  const raw = (event.target as HTMLInputElement).value
  // Keep only the last digit typed — handles both a fresh keystroke and a
  // single-character IME/mobile-keyboard replacement uniformly.
  const digit = raw.replace(/\D/g, '').slice(-1)
  digits[index] = digit
  ;(event.target as HTMLInputElement).value = digit
  emitValue()
  if (digit && index < props.length - 1) {
    focusBox(index + 1)
  }
}

function handleKeydown(index: number, event: KeyboardEvent): void {
  if (event.key !== 'Backspace') return
  if (digits[index]) return // let the default handler clear this box's own digit first
  if (index > 0) {
    event.preventDefault()
    digits[index - 1] = ''
    emitValue()
    focusBox(index - 1)
  }
}

function handlePaste(index: number, event: ClipboardEvent): void {
  const text = event.clipboardData?.getData('text') ?? ''
  const pasted = text.replace(/\D/g, '').slice(0, props.length)
  if (!pasted) return
  event.preventDefault()
  for (let i = 0; i < props.length; i++) {
    digits[i] = pasted[i] ?? ''
  }
  emitValue()
  focusBox(Math.min(pasted.length, props.length - 1))
}

const ids = computed(() => Array.from({ length: props.length }, (_, i) => `${props.idPrefix}-${i}`))
</script>

<template>
  <div role="group" aria-label="Verification code" class="flex gap-2">
    <input
      v-for="(id, index) in ids"
      :id="id"
      :key="id"
      :ref="(el) => (boxRefs[index] = el as HTMLInputElement | null)"
      :value="digits[index]"
      type="text"
      inputmode="numeric"
      autocomplete="one-time-code"
      maxlength="1"
      :aria-label="`Digit ${index + 1} of ${length}`"
      :disabled="disabled"
      :class="cn(
        'border-input h-11 w-9 rounded-md border bg-transparent text-center text-lg shadow-xs outline-none transition-[color,box-shadow]',
        'focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]',
        'disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50',
      )"
      @input="handleInput(index, $event)"
      @keydown="handleKeydown(index, $event)"
      @paste="handlePaste(index, $event)"
    >
  </div>
</template>
