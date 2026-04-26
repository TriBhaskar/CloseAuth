import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminService } from '@/api/services'

const STORAGE_KEY = 'closeauth_user'

interface PersistedUser {
  email: string
  username: string
  role: string
  isAuthenticated: boolean
}

function loadFromStorage(): PersistedUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as PersistedUser) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const stored = loadFromStorage()
  const router = useRouter()

  const email           = ref(stored?.email           ?? '')
  const username        = ref(stored?.username        ?? '')
  const role            = ref(stored?.role            ?? '')
  const isAuthenticated = ref(stored?.isAuthenticated ?? false)

  function persist() {
    const data: PersistedUser = {
      email: email.value,
      username: username.value,
      role: role.value,
      isAuthenticated: isAuthenticated.value,
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  }

  function setUser(userEmail: string, userUsername = '', userRole = 'Admin') {
    email.value           = userEmail
    username.value        = userUsername
    role.value            = userRole
    isAuthenticated.value = true
    persist()
  }

  async function fetchMe(): Promise<void> {
    try {
      const user = await adminService.getMe()
      setUser(user.email, user.username, user.role)
    } catch {
      // Not authenticated — leave as-is
    }
  }

  async function logout(): Promise<void> {
    try {
      await adminService.logout()
    } catch {
      // Ignore — still clear local state
    } finally {
      clear()
      await router.push('/')
    }
  }

  function clear() {
    email.value           = ''
    username.value        = ''
    role.value            = ''
    isAuthenticated.value = false
    localStorage.removeItem(STORAGE_KEY)
  }

  return { email, username, role, isAuthenticated, setUser, fetchMe, logout, clear }
})
