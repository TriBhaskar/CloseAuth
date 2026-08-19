import './styles/main.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './app/router'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia).use(router)

// TODO(ui-1/ui-2): the VITE_MOCK_MODE dev-tooling mechanism survives, but its
// seeded fake-user data was tied to the old (deleted) auth store shape. Rewire
// this once src/stores/auth.ts models the current backend's principal types
// (TENANT_ADMIN vs PLATFORM_ADMIN — see vision §7.8).

app.mount('#app')
