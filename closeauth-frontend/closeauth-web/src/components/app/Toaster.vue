<script setup lang="ts">
// Teleport and TransitionGroup are Vue compiler built-ins, resolved
// automatically in <template> — importing them from 'vue' is unnecessary
// (and was flagged as a dead import by eslint's no-unused-vars).
import { AlertCircle, CheckCircle, Info, TriangleAlert, X } from 'lucide-vue-next'
import { useToast, type ToastType } from '@/composables/useToast'

const { toasts, remove } = useToast()

const iconMap: Record<ToastType, typeof Info> = {
  success: CheckCircle,
  error:   AlertCircle,
  warning: TriangleAlert,
  info:    Info,
}

// §3.3/§3.6 state semantics: success/error/warning map onto the ok/danger/
// warn tones. 'info' has no §3.3 tone (only error/success/warning are
// specced) — mapped onto the primary/accent colour as the app's one
// neutral-attention highlight, not invented as a new semantic colour.
const colorMap: Record<ToastType, string> = {
  success: 'border-ok/40 bg-ok-wash text-ok',
  error:   'border-destructive/40 bg-destructive/10 text-destructive',
  warning: 'border-warn/40 bg-warn-wash text-warn',
  info:    'border-primary/30 bg-accent-wash text-primary',
}

const iconColorMap: Record<ToastType, string> = {
  success: 'text-ok',
  error:   'text-destructive',
  warning: 'text-warn',
  info:    'text-primary',
}
</script>

<template>
  <Teleport to="body">
    <div
      aria-live="polite"
      aria-label="Notifications"
      class="fixed bottom-5 right-5 z-[9999] flex flex-col gap-2 w-80 pointer-events-none"
    >
      <TransitionGroup
        enter-active-class="transition-all duration-300 ease-out"
        enter-from-class="opacity-0 translate-y-2 scale-95"
        enter-to-class="opacity-100 translate-y-0 scale-100"
        leave-active-class="transition-all duration-200 ease-in"
        leave-from-class="opacity-100"
        leave-to-class="opacity-0 scale-95"
      >
        <div
          v-for="toast in toasts"
          :key="toast.id"
          class="pointer-events-auto flex items-start gap-3 rounded-lg border px-4 py-3 shadow-md text-sm"
          :class="colorMap[toast.type]"
          role="alert"
        >
          <component
            :is="iconMap[toast.type]"
            class="h-4 w-4 shrink-0 mt-0.5"
            :class="iconColorMap[toast.type]"
          />
          <div class="flex-1 leading-snug">
            <p v-if="toast.title" class="font-medium">{{ toast.title }}</p>
            <p v-if="toast.description" class="text-xs opacity-80">{{ toast.description }}</p>
          </div>
          <button
            type="button"
            class="shrink-0 opacity-60 hover:opacity-100 transition-opacity"
            :aria-label="`Dismiss: ${toast.title ?? toast.description ?? 'notification'}`"
            @click="remove(toast.id)"
          >
            <X class="h-4 w-4" />
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

