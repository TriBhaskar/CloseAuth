import { ref } from 'vue'

export type ToastType = 'success' | 'error' | 'info' | 'warning'

export interface Toast {
  id: number
  title?: string
  description?: string
  type: ToastType
}

export interface ToastOptions {
  title?: string
  description?: string
  type?: ToastType
}

// Module-level reactive state so all consumers share the same list
const toasts = ref<Toast[]>([])
let nextId = 0

const DURATION_MS = 4000

function add(options: ToastOptions): number {
  const id = ++nextId
  toasts.value.push({
    id,
    title: options.title,
    description: options.description,
    type: options.type ?? 'info',
  })
  setTimeout(() => remove(id), DURATION_MS)
  return id
}

function remove(id: number) {
  const idx = toasts.value.findIndex((t) => t.id === id)
  if (idx !== -1) toasts.value.splice(idx, 1)
}

export function useToast() {
  return { toasts, toast: add, remove }
}
