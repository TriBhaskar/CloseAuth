import { ref } from 'vue'

export type ToastType = 'success' | 'error' | 'info' | 'warning'

export interface Toast {
  id: number
  message: string
  type: ToastType
}

// Module-level reactive state so all consumers share the same list
const toasts = ref<Toast[]>([])
let nextId = 0

const DURATION_MS = 4000

export function useToast() {
  function add(message: string, type: ToastType = 'info'): number {
    const id = ++nextId
    toasts.value.push({ id, message, type })
    setTimeout(() => remove(id), DURATION_MS)
    return id
  }

  function remove(id: number) {
    const idx = toasts.value.findIndex((t) => t.id === id)
    if (idx !== -1) toasts.value.splice(idx, 1)
  }

  return { toasts, add, remove }
}

