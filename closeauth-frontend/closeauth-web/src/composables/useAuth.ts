import { computed } from 'vue'
import { useRouter } from 'vue-router'
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
  const router = useRouter()
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

  async function forgotPasswordRequest(email: string): Promise<boolean> {
    const result = await execute(async () => {
      const resp = await fetch('/api/admin/forgot-password/request', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email }),
      })
      if (!resp.ok) throw new Error('Failed to send reset email')
      return resp.json()
    })
    return result !== null
  }

  async function forgotPasswordVerifyOtp(email: string, otp: string): Promise<boolean> {
    const result = await execute(async () => {
      const resp = await fetch('/api/admin/forgot-password/verify-otp', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email, verificationCode: otp }),
      })
      if (!resp.ok) throw new Error('Invalid or expired code')
      return resp.json()
    })
    return result !== null
  }

  async function resetPassword(email: string, otp: string, newPassword: string): Promise<boolean> {
    const result = await execute(async () => {
      const resp = await fetch('/api/admin/forgot-password/reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email, verificationCode: otp, newPassword }),
      })
      if (!resp.ok) throw new Error('Failed to reset password')
      return resp.json()
    })
    return result !== null
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
    forgotPasswordRequest,
    forgotPasswordVerifyOtp,
    resetPassword,
  }
}

