import './assets/main.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia).use(router)

// In mock mode, seed a fake authenticated user so admin pages are accessible
if (import.meta.env.VITE_MOCK_MODE === 'true') {
  import('@/stores/auth').then(({ useAuthStore }) => {
    const authStore = useAuthStore()
    if (!authStore.isAuthenticated) {
      authStore.setUser('admin@closeauth.dev', 'MockAdmin', 'Admin')
    }
  })
}

app.mount('#app')
