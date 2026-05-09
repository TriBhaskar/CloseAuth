import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useAsyncState } from '@/composables/useAsyncState'
import { adminService } from '@/api/services'

/**
 * Composable wrapping authentication operations.
 * Provides login, register, logout, forgot-password flows
 * with loading/error state management.
 */
export function useAuth() {
  const authStore = useAuthStore()
  const { isLoading, errorMessage, execute } = useAsyncState()

  const isAuthenticated = computed(() => authStore.isAuthenticated)
  const user = computed(() => ({
    email: authStore.email,
    username: authStore.username,
    role: authStore.role,
  }))

  async function login(username: string, password: string): Promise<boolean> {
    const result = await execute(() =>
      adminService.login({ username, password }),
    )
    if (!result) return false
    await authStore.fetchMe()
    return true
  }

  async function register(payload: {
    username: string
    email: string
    password: string
    firstName: string
    lastName: string
  }): Promise<boolean> {
    const result = await execute(() => adminService.register(payload))
    return result !== null
  }

  async function verifyOtp(email: string, verificationCode: string): Promise<boolean> {
    const result = await execute(() =>
      adminService.verifyOtp({ email, otp: verificationCode }),
    )
    return result !== null
  }

  async function resendOtp(email: string): Promise<boolean> {
    const result = await execute(() => adminService.resendOtp({ email }))
    return result !== null
  }

  async function logout(): Promise<void> {
    await authStore.logout()
  }

  return {
    isAuthenticated,
    user,
    isLoading,
    errorMessage,
    login,
    register,
    verifyOtp,
    resendOtp,
    logout,
  }
}

