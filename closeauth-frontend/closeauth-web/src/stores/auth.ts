import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const email = ref('')
  const isAuthenticated = ref(false)

  function setUser(userEmail: string) {
    email.value = userEmail
    isAuthenticated.value = true
  }

  function clear() {
    email.value = ''
    isAuthenticated.value = false
  }

  return { email, isAuthenticated, setUser, clear }
})
