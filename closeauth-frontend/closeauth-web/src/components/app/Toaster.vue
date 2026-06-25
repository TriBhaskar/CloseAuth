<script setup lang="ts">
import { Teleport, TransitionGroup } from 'vue'
import { AlertCircle, CheckCircle, Info, TriangleAlert, X } from 'lucide-vue-next'
import { useToast, type ToastType } from '@/composables/useToast'

const { toasts, remove } = useToast()

const iconMap: Record<ToastType, typeof Info> = {
  success: CheckCircle,
  error:   AlertCircle,
  warning: TriangleAlert,
  info:    Info,
}

const colorMap: Record<ToastType, string> = {
  success: 'border-green-200 bg-green-50 text-green-800',
  error:   'border-destructive/40 bg-destructive/10 text-destructive',
  warning: 'border-amber-200 bg-amber-50 text-amber-800',
  info:    'border-blue-200 bg-blue-50 text-blue-800',
}

const iconColorMap: Record<ToastType, string> = {
  success: 'text-green-500',
  error:   'text-destructive',
  warning: 'text-amber-500',
  info:    'text-blue-500',
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

