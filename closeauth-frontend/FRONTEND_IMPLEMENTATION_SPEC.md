# Vue.js Frontend Implementation Specification

**Target Stack**: Vue 3 + Vite + TypeScript + Pinia + Tailwind CSS v4  
**Port**: 5173  
**Go Backend Server**: Port 5000 (recommended)  
**Spring Auth Server**: Port 9088 (unchanged)  
**Date**: May 1, 2026

---

## Architecture Overview

### Three-Tier Structure

```
View Layer (Vue Components)
  ↓
Composable Layer (Auth, Theme, Form, API)
  ↓
API Integration Layer (HTTP client, Interceptors)
  ↓
Backend (Go server → Spring Auth Server)
```

### Technology Stack (refined)

| Layer | Tech | Version | Purpose |
|-------|------|---------|---------|
| **Framework** | Vue | 3.5+ | Reactive UI framework |
| **Build Tool** | Vite | 8.0+ | Fast dev server, optimized builds |
| **Language** | TypeScript | 6.0+ | Type safety |
| **State Mgmt** | Pinia | 3.0+ | Global state, auth tokens, theme |
| **HTTP Client** | Axios | 1.6+ | API requests, interceptors |
| **Styling** | Tailwind CSS | 4.2+ | Utility-first CSS |
| **UI Library** | Reka UI | 2.9+ | Headless components |
| **Icons** | Lucide Vue | 1.0+ | SVG icon library |
| **Routing** | Vue Router | 5.0+ | Client-side routing |
| **Tables** | @tanstack/vue-table | 8.21+ | Advanced data display |

---

## Directory Structure (Vue Frontend)

```
src/
├── main.ts                          # App entry point
├── App.vue                          # Root component, router outlet
├── router/
│   ├── index.ts                     # Router config, route definitions
│   └── guards.ts                    # Route guards (auth, redirect)
├── stores/                          # Pinia stores (global state)
│   ├── auth.ts                      # Authentication state
│   ├── theme.ts                     # Theme state (colors, mode)
│   ├── oauth.ts                     # OAuth context state
│   └── admin.ts                     # Admin data (users, clients, etc.)
├── composables/                     # Reusable logic
│   ├── useAuth.ts                   # Auth hooks (login, register, logout)
│   ├── useTheme.ts                  # Theme hooks (color injection, mode toggle)
│   ├── useForm.ts                   # Form validation, error handling
│   ├── useOAuth.ts                  # OAuth flow management
│   └── useApi.ts                    # API request management
├── api/                             # HTTP client configuration
│   ├── client.ts                    # Axios instance, interceptors
│   ├── interceptors.ts              # Request/response interception
│   ├── endpoints.ts                 # API routes (constants)
│   └── models/                      # TypeScript interfaces
│       ├── auth.ts                  # Login, Register, OTP requests/responses
│       ├── oauth.ts                 # OAuth context, consent data
│       ├── admin.ts                 # User, Client, Analytics models
│       └── theme.ts                 # ThemeData, ClientTheme models
├── components/
│   ├── app/
│   │   ├── Toaster.vue
│   │   └── Layout.vue
│   ├── auth/
│   │   ├── LoginForm.vue
│   │   ├── RegisterForm.vue
│   │   ├── ForgotPasswordForm.vue
│   │   └── OTPInput.vue
│   ├── ui/                          # Reka UI wrapper components
│   │   ├── Button.vue
│   │   ├── Card.vue
│   │   ├── Dialog.vue
│   │   └── ...others
│   ├── oauth/
│   │   ├── OAuthLoginForm.vue
│   │   ├── OAuthRegisterForm.vue
│   │   ├── OAuthConsent.vue
│   │   └── ClientBrandedLayout.vue
│   ├── admin/
│   │   ├── Sidebar.vue
│   │   ├── TopBar.vue
│   │   ├── Dashboard/
│   │   ├── Users/
│   │   ├── Clients/
│   │   ├── Analytics/
│   │   ├── Security/
│   │   └── Settings/
│   └── public/
│       ├── PublicHome.vue
│       ├── Header.vue
│       └── Footer.vue
├── layouts/
│   ├── AdminLayout.vue
│   ├── AuthLayout.vue
│   ├── OAuthLayout.vue
│   └── PublicLayout.vue
├── views/
│   ├── public/
│   │   └── HomeView.vue
│   ├── auth/
│   │   ├── AdminLoginView.vue
│   │   ├── AdminRegisterView.vue
│   │   └── AdminForgotPasswordView.vue
│   ├── admin/
│   │   ├── DashboardView.vue
│   │   ├── UsersView.vue
│   │   ├── ClientsView.vue
│   │   ├── CreateClientView.vue
│   │   ├── AnalyticsView.vue
│   │   ├── SecurityView.vue
│   │   └── SettingsView.vue
│   └── oauth/
│       ├── OAuthLoginView.vue
│       ├── OAuthRegisterView.vue
│       └── OAuthConsentView.vue
├── lib/
│   ├── validators.ts                # Form validators
│   └── formatters.ts                # Date, number formatting
├── assets/
│   ├── css/
│   │   ├── main.css
│   │   ├── tailwind.css
│   │   └── theme-variables.css      # CSS custom properties
│   └── images/
└── types/
    └── index.ts                     # Global TypeScript types
```

---

## Route Configuration

```typescript
// src/router/index.ts

const routes = [
  // Public routes
  {
    path: '/',
    component: HomeView,
    meta: { layout: 'public', requiresAuth: false }
  },
  
  // Admin Auth Routes
  {
    path: '/admin/login',
    component: AdminLoginView,
    meta: { layout: 'auth', requiresAuth: false }
  },
  {
    path: '/admin/register',
    component: AdminRegisterView,
    meta: { layout: 'auth', requiresAuth: false }
  },
  {
    path: '/admin/forgot-password',
    component: AdminForgotPasswordView,
    meta: { layout: 'auth', requiresAuth: false }
  },
  
  // Admin Dashboard Routes (protected)
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAuth: true },
    children: [
      { path: 'dashboard', component: DashboardView, meta: { title: 'Dashboard' } },
      { path: 'users', component: UsersView, meta: { title: 'Users' } },
      { path: 'clients', component: ClientsView, meta: { title: 'OAuth Clients' } },
      { path: 'clients/new', component: CreateClientView, meta: { title: 'Create Client' } },
      { path: 'analytics', component: AnalyticsView, meta: { title: 'Analytics' } },
      { path: 'security', component: SecurityView, meta: { title: 'Security' } },
      { path: 'settings', component: SettingsView, meta: { title: 'Settings' } }
    ]
  },
  
  // OAuth Client Routes (branded)
  {
    path: '/oauth/login',
    component: OAuthLoginView,
    meta: { layout: 'oauth', requiresAuth: false }
  },
  {
    path: '/oauth/register',
    component: OAuthRegisterView,
    meta: { layout: 'oauth', requiresAuth: false }
  },
  {
    path: '/oauth/consent',
    component: OAuthConsentView,
    meta: { layout: 'oauth', requiresAuth: false }
  }
]
```

---

## Pinia Stores Structure

### Auth Store (`stores/auth.ts`)

```typescript
export interface AuthState {
  user: { id: string; email: string; firstName: string; lastName: string } | null
  accessToken: string | null
  refreshToken: string | null
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    user: null,
    accessToken: null,
    refreshToken: null,
    isAuthenticated: false,
    isLoading: false,
    error: null
  }),
  
  actions: {
    async login(email: string, password: string): Promise<void>
    async register(data: RegisterRequest): Promise<void>
    async logout(): Promise<void>
    async refreshAccessToken(): Promise<void>
    async verifyOTP(email: string, otp: string): Promise<void>
    async requestPasswordReset(email: string): Promise<void>
    async verifyResetOTP(email: string, otp: string): Promise<void>
    async resetPassword(email: string, token: string, newPassword: string): Promise<void>
  },
  
  getters: {
    isAdmin: (state) => state.user?.role === 'ADMIN',
    canAccessDashboard: (state) => state.isAuthenticated
  }
})
```

### Theme Store (`stores/theme.ts`)

```typescript
export interface ThemeState {
  clientId: string | null
  clientName: string | null
  logoUrl: string | null
  colors: { light: ThemeColor; dark: ThemeColor }
  currentMode: 'light' | 'dark' | 'system'
  allowModeToggle: boolean
  isLoading: boolean
}

export const useThemeStore = defineStore('theme', {
  state: (): ThemeState => ({
    clientId: null,
    clientName: null,
    logoUrl: null,
    colors: { light: { ...defaultLight }, dark: { ...defaultDark } },
    currentMode: 'light',
    allowModeToggle: true,
    isLoading: false
  }),
  
  actions: {
    async loadThemeByClientId(clientId: string): Promise<void>
    setMode(mode: 'light' | 'dark' | 'system'): void
    applyThemeToDB(): Promise<void>
  },
  
  getters: {
    activeThemeColors: (state) => state.colors[state.currentMode === 'light' ? 'light' : 'dark'],
    isDarkMode: (state) => state.currentMode === 'dark',
    cssVariables: (state) => ({ /* CSS variable mappings */ })
  }
})
```

### OAuth Store (`stores/oauth.ts`)

```typescript
export interface OAuthContextState {
  responseType: string | null
  clientId: string | null
  redirectUri: string | null
  scope: string | null
  state: string | null
  username: string | null
  isSaving: boolean
  error: string | null
}

export const useOAuthStore = defineStore('oauth', {
  state: (): OAuthContextState => ({
    responseType: null,
    clientId: null,
    redirectUri: null,
    scope: null,
    state: null,
    username: null,
    isSaving: false,
    error: null
  }),
  
  actions: {
    async saveContext(ctx: OAuthContext): Promise<void>
    async loadContext(): Promise<OAuthContext | null>
    async clearContext(): Promise<void>
    updateUsername(username: string): void
  },
  
  getters: {
    isContextValid: (state) => !!state.clientId && !!state.redirectUri,
    contextExpiresIn: (state) => state.state ? 600 : 0
  }
})
```

### Admin Store (`stores/admin.ts`)

```typescript
export interface AdminState {
  users: User[]
  clients: OAuthClient[]
  analytics: AnalyticsData
  securityEvents: SecurityEvent[]
  isLoading: boolean
  error: string | null
}

export const useAdminStore = defineStore('admin', {
  state: (): AdminState => ({
    users: [],
    clients: [],
    analytics: {},
    securityEvents: [],
    isLoading: false,
    error: null
  }),
  
  actions: {
    async fetchUsers(): Promise<void>
    async createUser(data: CreateUserRequest): Promise<void>
    async fetchClients(): Promise<void>
    async createClient(data: CreateClientRequest): Promise<void>
    async fetchAnalytics(period: string): Promise<void>
    async fetchSecurityEvents(): Promise<void>
  }
})
```

---

## Composable Hooks (Key Examples)

### useAuth.ts

```typescript
export function useAuth() {
  const authStore = useAuthStore()
  const router = useRouter()
  
  const login = async (email: string, password: string) => {
    try {
      await authStore.login(email, password)
      await router.push('/admin/dashboard')
    } catch (error) {
      // Error handled in store
    }
  }
  
  const logout = async () => {
    await authStore.logout()
    await router.push('/admin/login')
  }
  
  return {
    login,
    logout,
    isAuthenticated: computed(() => authStore.isAuthenticated),
    user: computed(() => authStore.user),
    isLoading: computed(() => authStore.isLoading),
    error: computed(() => authStore.error)
  }
}
```

### useTheme.ts

```typescript
export function useTheme() {
  const themeStore = useThemeStore()
  
  const loadTheme = async (clientId: string) => {
    await themeStore.loadThemeByClientId(clientId)
    applyThemeToDB()
  }
  
  const toggleDarkMode = () => {
    const newMode = themeStore.currentMode === 'light' ? 'dark' : 'light'
    themeStore.setMode(newMode)
    applyThemeToDB()
  }
  
  const applyThemeToDB = () => {
    const vars = themeStore.cssVariables()
    Object.entries(vars).forEach(([key, value]) => {
      document.documentElement.style.setProperty(key, value)
    })
  }
  
  return {
    loadTheme,
    toggleDarkMode,
    currentMode: computed(() => themeStore.currentMode),
    colors: computed(() => themeStore.colors)
  }
}
```

### useForm.ts

```typescript
export function useForm() {
  const errors = ref<Record<string, string>>({})
  const isSubmitting = ref(false)
  
  const validateEmail = (email: string): boolean => {
    const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    return re.test(email)
  }
  
  const validatePassword = (password: string): string | null => {
    if (password.length < 8) return 'Password must be at least 8 characters'
    if (!/[A-Z]/.test(password)) return 'Password must contain uppercase letter'
    if (!/[0-9]/.test(password)) return 'Password must contain number'
    return null
  }
  
  return {
    errors: readonly(errors),
    isSubmitting,
    validateEmail,
    validatePassword,
    clearErrors: () => { errors.value = {} },
    addError: (field: string, message: string) => { errors.value[field] = message },
    hasErrors: () => Object.keys(errors.value).length > 0
  }
}
```

---

## API Client Configuration

### Axios Instance (`api/client.ts`)

```typescript
import axios from 'axios'
import { useAuthStore } from '@/stores/auth'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:5000',
  withCredentials: true,  // ✅ Send HTTP-only cookies
  timeout: 10000
})

// Request interceptor: add auth token to headers
apiClient.interceptors.request.use(
  (config) => {
    const authStore = useAuthStore()
    if (authStore.accessToken) {
      config.headers.Authorization = `Bearer ${authStore.accessToken}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// Response interceptor: handle 401, refresh token
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config
    
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true
      const authStore = useAuthStore()
      
      try {
        await authStore.refreshAccessToken()
        originalRequest.headers.Authorization = `Bearer ${authStore.accessToken}`
        return apiClient(originalRequest)
      } catch (refreshError) {
        authStore.logout()
        window.location.href = '/admin/login'
        return Promise.reject(refreshError)
      }
    }
    
    return Promise.reject(error)
  }
)

export default apiClient
```

### API Endpoints (`api/endpoints.ts`)

```typescript
export const API_ENDPOINTS = {
  // Auth
  login: '/api/auth/login',
  register: '/api/auth/register',
  logout: '/api/auth/logout',
  requestOTP: '/api/auth/otp/request',
  verifyOTP: '/api/auth/otp/verify',
  refreshToken: '/api/auth/refresh',
  
  // OAuth
  oauthTheme: (clientId: string) => `/api/oauth/theme/${clientId}`,
  oauthLogin: '/api/oauth/login',
  oauthConsent: '/api/oauth/consent',
  
  // Admin
  users: '/api/admin/users',
  users_id: (id: string) => `/api/admin/users/${id}`,
  clients: '/api/admin/clients',
  clients_id: (id: string) => `/api/admin/clients/${id}`,
  analytics: '/api/admin/analytics',
  securityEvents: '/api/admin/security/events',
  
  // Settings
  settings_general: '/api/admin/settings/general'
}
```

---

## API Request/Response Models

### Auth Models (`api/models/auth.ts`)

```typescript
export interface LoginRequest {
  email: string
  password: string
  rememberMe?: boolean
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  user: {
    id: string
    email: string
    firstName: string
    lastName: string
  }
}

export interface RegisterRequest {
  firstName: string
  lastName: string
  email: string
  password: string
}

export interface OTPVerifyRequest {
  email: string
  otp: string
}

export interface ErrorResponse {
  error: string
  details?: Record<string, string>
  timestamp: string
}
```

### OAuth Models (`api/models/oauth.ts`)

```typescript
export interface ThemeData {
  logoBrand: string | null
  defaultMode: 'light' | 'dark' | 'system'
  allowModeToggle: boolean
  colors: {
    light: ColorSet
    dark: ColorSet
  }
}

export interface ColorSet {
  primary: string
  background: string
  button: string
  text: string
}

export interface OAuthConsentData {
  clientId: string
  clientName: string
  logoUrl: string | null
  scopes: ScopeDisplay[]
  state: string
  redirectUri: string
}

export interface ScopeDisplay {
  scope: string
  title: string
  description: string
  icon: string
}
```

### Admin Models (`api/models/admin.ts`)

```typescript
export interface User {
  id: string
  email: string
  firstName: string
  lastName: string
  role: 'USER' | 'ADMIN' | 'MODERATOR'
  status: 'ACTIVE' | 'INACTIVE'
  lastLogin: string
  createdAt: string
}

export interface OAuthClient {
  id: string
  name: string
  clientId: string
  type: 'CONFIDENTIAL' | 'PUBLIC'
  status: 'ACTIVE' | 'INACTIVE'
  createdAt: string
  lastUsed: string
  requestsToday: number
}

export interface AnalyticsData {
  totalRequests: number
  tokensIssued: number
  avgResponseTime: number
  errorRate: number
  requestTrends: Array<{
    date: string
    authorizations: number
    tokens: number
  }>
}

export interface SecurityEvent {
  id: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
  title: string
  description: string
  ipAddress: string
  location: string
  timestamp: string
  resolved: boolean
}
```

---

## Environment Configuration

```
# .env.development
VITE_API_URL=http://localhost:5000
VITE_LOG_LEVEL=debug

# .env.production
VITE_API_URL=https://api.closeauth.com
VITE_LOG_LEVEL=info
```

```typescript
// vite.config.ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:5000',
        changeOrigin: true
      }
    }
  }
})
```

---

## Success Metrics

- ✅ All 17 screens render correctly
- ✅ Forms validate client-side + server-side
- ✅ OAuth theme colors apply to DOM
- ✅ CSRF tokens included in all POST requests
- ✅ HTTP-only cookies persisted securely
- ✅ Token refresh works silently
- ✅ Dark mode toggles correctly
- ✅ Performance: LCP < 2s, TTI < 3s
- ✅ All routes load without CORS issues

---

**Next Steps**: See `STEP_BY_STEP_MIGRATION.md` for detailed implementation phases.

