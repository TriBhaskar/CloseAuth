import { ref } from 'vue'
import { ApiError } from '@/api/client'
import { useToast } from '@/composables/useToast'

/**
 * Wraps async operations with isLoading + errorMessage state.
 *
 * - 4xx ApiErrors: surface as inline errorMessage (form-level feedback)
 * - 5xx / network errors: trigger a global toast + set a generic errorMessage
 *
 * Usage:
 *   const { isLoading, errorMessage, execute } = useAsyncState()
 *   await execute(() => adminService.login(payload))
 */
export function useAsyncState() {
  const isLoading = ref(false)
  const errorMessage = ref('')
  const { toast } = useToast()

  async function execute<T>(fn: () => Promise<T>): Promise<T | null> {
    isLoading.value = true
    errorMessage.value = ''
    try {
      const result = await fn()
      return result
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status >= 500) {
          // Server error → toast + generic inline message
          toast({ title: 'A server error occurred. Please try again later.', type: 'error' })
          errorMessage.value = 'A server error occurred. Please try again later.'
        } else {
          // Client error (400, 401, 422…) → inline only
          errorMessage.value = err.message
        }
      } else {
        // Network/fetch error
        toast({ title: 'Network error. Please check your connection.', type: 'error' })
        errorMessage.value = 'Network error. Please check your connection.'
      }
      return null
    } finally {
      isLoading.value = false
    }
  }

  return { isLoading, errorMessage, execute }
}

