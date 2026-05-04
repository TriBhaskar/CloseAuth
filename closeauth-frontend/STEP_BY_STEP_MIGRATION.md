# Step-by-Step Migration Guide: Go BFF → Vue.js

**Timeline**: 8-12 weeks (phased)  
**Prerequisite**: Follow `FRONTEND_IMPLEMENTATION_SPEC.md`  
**Target**: Full functional parity with current Go BFF  
**Date**: May 1, 2026

---

## Migration Phases Overview

```
Phase 1: Infrastructure Setup (Week 1-2)
  ↓
Phase 2: Authentication Flows (Week 3-4)
  ↓
Phase 3: Admin Dashboard Pages (Week 5-6)
  ↓
Phase 4: OAuth Client Pages (Week 7)
  ↓
Phase 5: Advanced Features + Polish (Week 8)
  ↓
Phase 6: Testing & Validation (Week 9-10)
  ↓
Phase 7: Deployment & Cutover (Week 11-12)
```

---

## Phase 1: Infrastructure Setup (Weeks 1-2)

### Goal
Set up Vue project structure, build tools, and foundational patterns.

### Tasks

#### 1.1: Project Initialization
- [ ] Initialize Vue 3 + Vite + TypeScript in `closeauth-frontend/closeauth-web/`
- [ ] Install dependencies from `package.json` (already present)
- [ ] Configure Vite for dev server on port 5173
- [ ] Set up `.env` files for dev/prod environments

**Command**:
```bash
cd closeauth-frontend/closeauth-web
npm install
npm run dev
```

#### 1.2: Create Directory Structure
- [ ] Create `src/` subdirectories per spec:
  - `router/`, `stores/`, `composables/`, `api/`, `components/`, `layouts/`, `views/`, `lib/`, `assets/`, `types/`
- [ ] Create `src/main.ts` with Vue app initialization
- [ ] Create `src/App.vue` with RouterView outlet

**File: `src/main.ts`**
```typescript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
```

#### 1.3: Set Up Pinia Stores
- [ ] Create `stores/auth.ts` with basic state structure
- [ ] Create `stores/theme.ts` with theme state
- [ ] Create `stores/oauth.ts` with OAuth context state
- [ ] Create `stores/admin.ts` with admin data state

**Pattern**: Each store should follow composition API + TypeScript interface

#### 1.4: Create API Client
- [ ] Create `api/client.ts` with Axios instance
- [ ] Implement request interceptor (add auth token)
- [ ] Implement response interceptor (handle 401, refresh token)
- [ ] Create `api/endpoints.ts` with route constants
- [ ] Create `api/models/` subdirectory with TypeScript interfaces

**Critical**: Ensure `withCredentials: true` for HTTP-only cookies

#### 1.5: Set Up Router
- [ ] Create `router/index.ts` with route definitions (27 routes)
- [ ] Create `router/guards.ts` with auth guards
- [ ] Register router with Vue app

**Routes** (27 total):
```
/ (public home)
/admin/login (auth)
/admin/register (auth)
/admin/forgot-password (auth)
/admin/dashboard (protected) + 6 child routes
/oauth/login (branded)
/oauth/register (branded)
/oauth/consent (branded)
```

#### 1.6: Configure Tailwind CSS
- [ ] Update `tailwind.config.js` for Vue + Vite
- [ ] Create `src/assets/css/main.css` with Tailwind imports
- [ ] Create `src/assets/css/theme-variables.css` with CSS custom properties
- [ ] Import CSS in `main.ts`

**Important**: Set up CSS vars for dynamic theming:
```css
:root {
  --theme-primary: #3b82f6;
  --theme-background: #ffffff;
  --theme-button: #3b82f6;
  --theme-text: #1f2937;
}
```

#### 1.7: Set Up Development Workflow
- [ ] Verify hot module reloading (HMR) works
- [ ] Test build output: `npm run build`
- [ ] Verify TypeScript compilation: `npm run type-check`
- [ ] Create `.gitignore` entries for node_modules, dist, etc.

### Deliverables (Phase 1)
- ✅ Vue + Vite project running on 5173 with HMR
- ✅ Pinia stores initialized with TypeScript
- ✅ 27 routes configured with auth guards
- ✅ Axios client with interceptors ready
- ✅ Tailwind CSS + theme variables setup
- ✅ Build pipeline validates with no errors

---

## Phase 2: Authentication Flows (Weeks 3-4)

### Goal
Implement all admin authentication screens: login, register, forgot password, OTP.

### Tasks

#### 2.1: Create Auth Composables
- [ ] Implement `useAuth.ts` with:
  - `login()` action
  - `register()` action
  - `logout()` action
  - `requestPasswordReset()` action
  - `verifyOTP()` action
  - `resetPassword()` action
- [ ] Export computed properties: `isAuthenticated`, `user`, `isLoading`, `error`

**Example**:
```typescript
export function useAuth() {
  const authStore = useAuthStore()
  
  const login = async (email: string, password: string) => {
    try {
      await authStore.login(email, password)
      // Handle success
    } catch (error) {
      // Error in store
    }
  }
  
  return { login, isAuthenticated: computed(() => ...) }
}
```

#### 2.2: Implement Auth Store Actions
- [ ] Update `stores/auth.ts`:
  - `async login(email, password)` → POST to `/api/auth/login`
  - `async register(data)` → POST to `/api/auth/register`
  - `async logout()` → POST to `/api/auth/logout`
  - `async refreshAccessToken()` → POST to `/api/auth/refresh`
  - Update state with response: `accessToken`, `refreshToken`, `user`
  - Handle errors → set `error` state

**Critical**: Extract tokens from response and store in Pinia state. Don't store in localStorage (use HTTP-only cookies).

#### 2.3: Create UI Components
- [ ] `components/auth/LoginForm.vue` — Admin login form
- [ ] `components/auth/RegisterForm.vue` — Admin registration form
- [ ] `components/auth/ForgotPasswordForm.vue` — 3-step password recovery
- [ ] `components/auth/OTPInput.vue` — 6-digit OTP input
- [ ] `components/ui/Button.vue` — Reka UI button wrapper
- [ ] `components/ui/Input.vue` — Reka UI input wrapper
- [ ] `components/ui/Card.vue` — Reka UI card wrapper

**LoginForm Pattern**:
```vue
<template>
  <form @submit.prevent="handleSubmit">
    <input v-model="form.email" type="email" required />
    <input v-model="form.password" type="password" required />
    <button type="submit" :disabled="isLoading">Sign In</button>
    <div v-if="error" class="text-red-600">{{ error }}</div>
  </form>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { useAuth } from '@/composables/useAuth'

const form = reactive({ email: '', password: '' })
const { login, isLoading, error } = useAuth()

const handleSubmit = async () => {
  await login(form.email, form.password)
}
</script>
```

#### 2.4: Create Auth Views
- [ ] `views/auth/AdminLoginView.vue` — Route: `/admin/login`
- [ ] `views/auth/AdminRegisterView.vue` — Route: `/admin/register`
- [ ] `views/auth/AdminForgotPasswordView.vue` — Route: `/admin/forgot-password`

**Key**: Each view wraps components with `AuthLayout`.

#### 2.5: Create Auth Layout
- [ ] `layouts/AuthLayout.vue` — Centered card layout for auth screens
- [ ] Styling: centered, gray background, shadow card
- [ ] Should NOT show auth header/sidebar

#### 2.6: Implement OTP Verification Flow
- [ ] Create multi-step form component for forgot password
- [ ] Step 1: Email input → `requestPasswordReset()`
- [ ] Step 2: OTP input → `verifyResetOTP()`
- [ ] Step 3: New password form → `resetPassword()`
- [ ] Each step replaces previous content (HTMX-like swap pattern)

**Pattern**: Use `ref<'step1' | 'step2' | 'step3'>` to track current step

#### 2.7: Add Session Persistence (with HTTP-only cookies)
- [ ] Implement: On app load, axios interceptor checks response for auth token in headers
- [ ] On 401 response: Trigger token refresh via `authStore.refreshAccessToken()`
- [ ] Backend ensures HTTP-only cookies are set (don't expose in JS)
- [ ] Frontend passes cookies automatically via `withCredentials: true`

**Implementation**: Already in axios interceptor, just document flow.

#### 2.8: Test Authentication Routes
- [ ] Verify login form submits correctly
- [ ] Verify OTP step transitions
- [ ] Verify error messages display
- [ ] Verify redirect to `/admin/dashboard` on successful login

### Deliverables (Phase 2)
- ✅ `useAuth.ts` composable with all methods
- ✅ `auth.ts` Pinia store with API integration
- ✅ 5 auth components (LoginForm, RegisterForm, OTPInput, etc.)
- ✅ 3 auth views (login, register, forgot-password)
- ✅ AuthLayout wrapper
- ✅ All auth routes working with validation
- ✅ OTP flow with multi-step progression

---

## Phase 3: Admin Dashboard Pages (Weeks 5-6)

### Goal
Implement 7 admin dashboard pages with protected routes and data display.

### Tasks

#### 3.1: Create Admin Layout
- [ ] `layouts/AdminLayout.vue` — Sidebar + content area
- [ ] `components/admin/Sidebar.vue` — Navigation with icons
- [ ] `components/admin/TopBar.vue` — Top navigation bar
- [ ] Apply dark mode support throughout

**Structure**:
```
├── Sidebar (left)
│   └── Nav items (Dashboard, Users, Clients, Analytics, Security, Settings)
├── TopBar (top-right)
└── Content (main area)
```

#### 3.2: Create Route Guards
- [ ] Update `router/guards.ts`:
  - Check if user is authenticated
  - If not, redirect to `/admin/login`
  - Show loading state while checking auth
- [ ] Apply guard to all `/admin/*` routes

**Pattern**:
```typescript
router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next('/admin/login')
  } else {
    next()
  }
})
```

#### 3.3: Implement Dashboard Page
- [ ] `views/admin/DashboardView.vue` — Route: `/admin/dashboard`
- [ ] Create 6 stat cards: OAuth Clients, Total Users, Daily Requests, Success Rate, Active Sessions, Failed Auth
- [ ] Add mock data or connect to real API (from admin store)
- [ ] Add "Top Clients" section
- [ ] Add "Recent Activity" feed
- [ ] Add "Security Alerts" section

**Components**:
```
├── DashboardView.vue
│   ├── StatCard.vue (×6)
│   ├── LineChart.vue (OAuth Requests chart)
│   ├── ActivityFeed.vue
│   └── SecurityAlerts.vue
```

#### 3.4: Implement Users Page
- [ ] `views/admin/UsersView.vue` — Route: `/admin/users`
- [ ] Create user table with columns: Name, Role, Status, Last Login, Created, Actions
- [ ] Add 4 stat cards: Total Users, Active Users, Administrators, New This Week
- [ ] Add search bar with magnifying glass icon
- [ ] Implement mock data (5-10 users)

**Components**:
```
├── UsersView.vue
│   ├── StatCard.vue (×4)
│   ├── UserTable.vue (using @tanstack/vue-table)
│   └── SearchBar.vue
```

#### 3.5: Implement OAuth Clients Page
- [ ] `views/admin/ClientsView.vue` — Route: `/admin/clients`
- [ ] Create client table: Application, Client ID (monospace, cyan), Type (badge), Status (badge), Requests Today, Last Used, Actions
- [ ] Add "New Client" button → `/admin/clients/new`
- [ ] Add search bar
- [ ] Implement mock data (5-10 clients)

**Key**: Client ID should be displayed in monospace font, gray background, with copy icon

#### 3.6: Implement Create Client Form
- [ ] `views/admin/CreateClientView.vue` — Route: `/admin/clients/new`
- [ ] Form sections:
  1. Basic Information: name, description, type (dropdown), logo URL, homepage URL
  2. Grant Types & Redirect URIs: redemption method, dynamic URI list (add/remove buttons)
  3. Scopes: checkboxes for (openid, profile, email, offline_access, read:users, write:users)
- [ ] Submit button: "Register Application"
- [ ] Back button to `/admin/clients`

**Pattern**: Use `v-for` to render dynamic redirect URI inputs

#### 3.7: Implement Analytics Page
- [ ] `views/admin/AnalyticsView.vue` — Route: `/admin/analytics`
- [ ] 4 metric cards: Total Requests (7d), Tokens Issued, Avg Response Time, Error Rate
- [ ] Request Trends chart (line chart, Mon-Sun)
- [ ] Error Breakdown (horizontal bar: Invalid credentials, Expired token, Invalid redirect, Rate limited, Other)
- [ ] Token Status Distribution (donut chart: Active, Expired, Revoked)
- [ ] Grant Type Usage (horizontal bar chart)

**Mock Data**: Generate sample data for charts

#### 3.8: Implement Security Page
- [ ] `views/admin/SecurityView.vue` — Route: `/admin/security`
- [ ] 4 stat cards: Critical Alerts, Blocked Attacks, Tokens Revoked, Audit Events
- [ ] Tab bar: Security Events (active), Audit Logs, IP Access
- [ ] Search + filter (by severity)
- [ ] Security event cards (left-colored border: red=critical, orange=high, blue=medium, gray=low)
- [ ] Each card: severity, title, description, IP, location, timestamp, "Resolved" badge (optional), action buttons

**Mock Events**: Create 5 sample security events with different severities

#### 3.9: Implement Settings Page
- [ ] `views/admin/SettingsView.vue` — Route: `/admin/settings`
- [ ] Tab bar: General (active), Security, Tokens, Notifications
- [ ] General tab:
  - Issuer URL (text input)
  - Default Audience (text input)
  - Timezone (select dropdown)
  - Default Language (select dropdown)
- [ ] "Save Changes" button (top-right)
- [ ] All inputs have helper text below

**Pattern**: Tab bar is visual only for now (static), can be interactive later

#### 3.10: Create Admin Store Actions
- [ ] `fetchUsers()` → GET `/api/admin/users`
- [ ] `fetchClients()` → GET `/api/admin/clients`
- [ ] `fetchAnalytics()` → GET `/api/admin/analytics`
- [ ] `fetchSecurityEvents()` → GET `/api/admin/security/events`
- [ ] Implement error handling and loading states

#### 3.11: Create Data Table Component
- [ ] Create reusable `components/admin/DataTable.vue` using @tanstack/vue-table
- [ ] Support: columns, rows, sorting, filtering
- [ ] Used by Users page, Clients page

### Deliverables (Phase 3)
- ✅ AdminLayout with sidebar + topbar
- ✅ 7 admin views (Dashboard, Users, Clients, Create Client, Analytics, Security, Settings)
- ✅ 6 stat cards, charts, data tables
- ✅ Route guards protect all `/admin/*` routes
- ✅ Admin store with data fetching actions
- ✅ Mock data renders correctly
- ✅ Dark mode fully functional on all admin pages

---

## Phase 4: OAuth Client Pages (Week 7)

### Goal
Implement 3 client-branded OAuth pages with per-client theming.

### Tasks

#### 4.1: Create Theme Composable
- [ ] Implement `useTheme.ts`:
  - `loadTheme(clientId)` → Fetch theme from DB
  - `toggleDarkMode()` → Switch light/dark
  - `applyThemeToDB()` → Inject CSS vars
- [ ] Return: `currentMode`, `colors`, theme configuration

**Pattern**:
```typescript
const applyThemeToDB = () => {
  const vars = themeStore.cssVariables()
  Object.entries(vars).forEach(([key, value]) => {
    document.documentElement.style.setProperty(key, value)
  })
}
```

#### 4.2: Update Theme Store
- [ ] Add actions:
  - `loadThemeByClientId(clientId)` → GET `/api/oauth/theme/:clientId`
  - Parse response: logo, colors (light/dark), default mode, allow toggle
  - Set state with theme data
- [ ] Add getter: `activeThemeColors()` — returns light or dark colors based on mode

**Response Model**:
```typescript
interface ThemeData {
  logoUrl: string | null
  defaultMode: 'light' | 'dark' | 'system'
  allowModeToggle: boolean
  colors: {
    light: { primary, background, button, text }
    dark: { primary, background, button, text }
  }
}
```

#### 4.3: Create OAuth Layout
- [ ] `layouts/OAuthLayout.vue` — Branded background + card
- [ ] Load theme on component mount
- [ ] Apply CSS vars to document root
- [ ] Render dark mode toggle button (if `allowModeToggle` is true)
- [ ] Footer: "Powered by CloseAuth"

**Key**: Layout background color comes from theme, card stays white for contrast

#### 4.4: Implement OAuth Login Page
- [ ] `views/oauth/OAuthLoginView.vue` — Route: `/oauth/login`
- [ ] Use `OAuthLayout` wrapper
- [ ] Fetch theme from query param: `client_id`
- [ ] Display:
  - Client logo (or initials fallback with theme primary color)
  - "Sign in to continue" heading
  - "Sign in to access {ClientName}" subtitle
  - OAuth notice banner (blue info box)
  - Login form (email + password + remember me)
  - "Forgot your password?" link
  - "Don't have an account? Register here" link → `/oauth/register?client_id=...`
- [ ] Submit: POST to `/api/oauth/login` with clientId

**Important**: Pass `clientId`, `clientName`, `logoUrl` to form component via props

#### 4.5: Implement OAuth Register Page
- [ ] `views/oauth/OAuthRegisterView.vue` — Route: `/oauth/register`
- [ ] Use `OAuthLayout` wrapper
- [ ] Display:
  - Client logo/initials (same as login)
  - "Create your account" heading
  - "Already have an account? Sign in here" link
  - Register form: first name + last name + email + username + password + confirm password
  - Submit button: "Create Account"
- [ ] On success: Show OTP verification dialog (as modal overlay)
- [ ] Modal: email icon, "Verify your email", 6-digit OTP input, "Verify Email" button, "Resend code" link

**Pattern**: On form submit success, replace form with OTP modal

#### 4.6: Implement OAuth Consent Page
- [ ] `views/oauth/OAuthConsentView.vue` — Route: `/oauth/consent`
- [ ] Use `OAuthLayout` wrapper
- [ ] Display:
  - Client logo + name + "by {ClientName}" subtitle
  - "{ClientName} wants to access your account" message
  - User identity card (circular avatar with user icon, username, email)
  - "THIS WILL ALLOW {ClientName} TO:" section header
  - Scope permission cards: icon + title + description (one per scope)
  - Info box: "You can revoke access at any time from your account settings"
  - Action buttons: "Allow Access" (green, full-width) + "Deny" (white border, full-width)
- [ ] On approval: POST to `/api/oauth/consent?action=approve`
- [ ] On denial: POST to `/api/oauth/consent?action=deny`
- [ ] Backend redirects to client app with code or denial

**Scope Icons**: Map scope strings to icons (openid→id icon, profile→user icon, email→mail icon, etc.)

#### 4.7: Create Scope Display Component
- [ ] `components/oauth/ScopeCard.vue` — Shows permission with icon + title + description
- [ ] Used on consent page

**Scope Mapping**:
```typescript
const scopeDescriptions: Record<string, { title, description, icon }> = {
  'openid': { title: 'Your Identity', description: '...', icon: 'IdCard' },
  'profile': { title: 'Profile Information', description: '...', icon: 'User' },
  'email': { title: 'Email Address', description: '...', icon: 'Mail' },
  // ... more scopes
}
```

#### 4.8: Connect OAuth Store
- [ ] Update `stores/oauth.ts`:
  - `saveContext()` → called after initial OAuth request
  - `loadContext()` → retrieve from backend/cookies
  - `clearContext()` → called after consent flow completes
- [ ] Store: `responseType`, `clientId`, `redirectUri`, `scope`, `state`, `username`

#### 4.9: Test OAuth Flow End-to-End
- [ ] Verify client theme loads correctly
- [ ] Verify colors apply to page
- [ ] Verify dark mode toggle works (if enabled)
- [ ] Verify login/register/consent flows
- [ ] Verify OAuth context is maintained through flow

### Deliverables (Phase 4)
- ✅ `useTheme.ts` composable with dark mode toggle
- ✅ Theme store with API integration
- ✅ OAuthLayout wrapper with theme application
- ✅ OAuth Login page with client branding
- ✅ OAuth Register page with OTP modal
- ✅ OAuth Consent page with scope display
- ✅ Scope card component with icon mapping
- ✅ End-to-end OAuth flow tested

---

## Phase 5: Advanced Features & Polish (Week 8)

### Goal
Implement remaining features, error handling, dark mode, accessibility.

### Tasks

#### 5.1: Public Home Page
- [ ] `views/public/HomeView.vue` — Route: `/`
- [ ] Create `PublicLayout.vue` with public header + footer
- [ ] Display:
  - Header with CloseAuth logo, top navigation (Home, Documentation, API, Pricing)
  - Auth status in header (Sign In + Sign Up for anonymous, Dashboard + Logout for authenticated)
  - Hero section with CloseAuth branding
  - Feature sections (optional)
  - Footer with links + copyright

#### 5.2: Comprehensive Error Handling
- [ ] Create `components/app/ErrorAlert.vue` — Reusable error display
- [ ] Implement error toasts with `Toaster.vue` component
- [ ] Add try-catch in all composable actions
- [ ] Display user-friendly error messages from API responses
- [ ] Log errors to console in development

**Error Toast Pattern**:
```typescript
try {
  await login(email, password)
} catch (error: any) {
  const message = error.response?.data?.error || 'An error occurred'
  showErrorToast(message)
}
```

#### 5.3: Loading States
- [ ] Add loading spinners to all forms
- [ ] Disable buttons while loading
- [ ] Show loading skeletons on data tables (optional)
- [ ] Add timeout warnings for slow requests

#### 5.4: Form Validation Enhancements
- [ ] Update `useForm.ts` with more validators:
  - Email format validation
  - Password strength (8+ chars, uppercase, number)
  - Password match validation
  - URL validation (for redirect URIs)
- [ ] Display inline error messages below fields
- [ ] Mark invalid fields with red border

#### 5.5: Accessibility (a11y)
- [ ] Add `aria-label` to all buttons
- [ ] Add `aria-describedby` to form fields with error messages
- [ ] Ensure color contrast meets WCAG AA standards
- [ ] Test keyboard navigation (Tab through all fields)
- [ ] Use semantic HTML: `<button>`, `<form>`, `<label>` tags

#### 5.6: Responsive Design
- [ ] Test all pages on mobile (320px), tablet (768px), desktop (1024px+)
- [ ] Sidebar collapses to hamburger on mobile
- [ ] Data tables become scrollable cards on mobile
- [ ] Forms stack vertically on mobile
- [ ] Test on Chrome, Firefox, Safari, Edge

#### 5.7: Dark Mode Refinement
- [ ] Ensure all components support dark mode
- [ ] Use Tailwind's `dark:` prefix consistently
- [ ] Test dark mode on all 17 screens
- [ ] Persist dark mode preference to localStorage

**Pattern**:
```vue
<div class="bg-white dark:bg-gray-900 text-gray-900 dark:text-white">
  Content
</div>
```

#### 5.8: Performance Optimization
- [ ] Code split by route (lazy load views)
- [ ] Defer heavy components (charts)
- [ ] Minify production builds
- [ ] Test Lighthouse metrics (aim for >90 score)

**Lazy Loading Pattern**:
```typescript
const DashboardView = defineAsyncComponent(() => import('@/views/admin/DashboardView.vue'))
```

#### 5.9: Icon Integration
- [ ] Replace text labels with Lucide icons where appropriate
- [ ] Ensure all icons are imported from lucide-vue-next
- [ ] Test icon rendering on all pages

#### 5.10: Toast/Notification System
- [ ] Create comprehensive toast system:
  - Success: Green background, checkmark icon
  - Error: Red background, X icon
  - Warning: Yellow background, warning icon
  - Info: Blue background, info icon
- [ ] Auto-dismiss after 5 seconds
- [ ] Manual dismiss button
- [ ] Position: top-right corner

### Deliverables (Phase 5)
- ✅ Public home page with auth status awareness
- ✅ Comprehensive error handling + error toasts
- ✅ Loading states on all async operations
- ✅ Form validation with inline error display
- ✅ Accessibility compliance (WCAG AA)
- ✅ Responsive design on all screen sizes
- ✅ Dark mode fully tested
- ✅ Performance optimized (Lighthouse >90)
- ✅ Icon integration complete

---

## Phase 6: Testing & Validation (Weeks 9-10)

### Goal
Test all functionality and validate against Go BFF.

### Tasks

#### 6.1: Manual Testing Checklist
- [ ] Login flow: success, invalid credentials, OTP verification
- [ ] Register flow: validation errors, OTP verification, email confirmation
- [ ] Password reset: 3-step flow, error handling
- [ ] Admin dashboard: all 7 pages load correctly
- [ ] OAuth login/register/consent: client branding applied correctly
- [ ] Dark mode: toggle works, persists
- [ ] CSRF token: included in all POST requests
- [ ] Session persistence: stays logged in on page refresh
- [ ] Token refresh: automatic on 401 response

#### 6.2: OAuth Flow Validation
- [ ] Initiate OAuth from external client
- [ ] Verify BFF intercepts and saves OAuth context
- [ ] Verify login redirects to OAuth login page (not admin login)
- [ ] Verify consent page displays correct scopes
- [ ] Verify authorization code returned to client

**Test with**: Postman or real OAuth client

#### 6.3: API Contract Testing
- [ ] Verify all API responses match model interfaces
- [ ] Test error responses (400, 401, 403, 500)
- [ ] Test paginated responses (if applicable)
- [ ] Verify response times < 2s

#### 6.4: Security Testing
- [ ] Verify CSRF tokens are validated server-side
- [ ] Verify unauthorized access to `/admin/*` redirects to login
- [ ] Verify HTTP-only cookies are not accessible from JS
- [ ] Test token expiration and refresh

#### 6.5: Browser Compatibility
- [ ] Test on Chrome (latest)
- [ ] Test on Firefox (latest)
- [ ] Test on Safari (latest)
- [ ] Test on Edge (latest)

#### 6.6: Cross-Device Testing
- [ ] iPhone (375px)
- [ ] iPad (768px)
- [ ] Desktop (1920px)
- [ ] Verify responsive breakpoints

#### 6.7: Comparison Testing (Vue vs Go BFF)
- [ ] Performance: measure LCP, TTI, CLS
- [ ] Functionality: verify feature parity
- [ ] UI: compare visual appearance (should be very similar or improved)

### Deliverables (Phase 6)
- ✅ Manual testing checklist completed
- ✅ OAuth flow end-to-end validation
- ✅ API contracts verified
- ✅ Security testing passed
- ✅ All browsers tested
- ✅ All devices tested (mobile, tablet, desktop)
- ✅ Performance metrics documented

---

## Phase 7: Deployment & Cutover (Weeks 11-12)

### Goal
Deploy Vue frontend and transition from Go BFF.

### Tasks

#### 7.1: Deployment Preparation
- [ ] Create `docker-compose.yml` for local deployment (frontend + backend)
- [ ] Create `.env.production` with production API URL
- [ ] Update `vite.config.ts` proxy for production
- [ ] Create deployment guide in README

#### 7.2: Deploy to Staging
- [ ] Build production bundle: `npm run build`
- [ ] Deploy to staging environment
- [ ] Run smoke tests on staging
- [ ] Load test (simulate multiple concurrent users)

#### 7.3: Parallel Deployment (Go BFF + Vue concurrent)
- [ ] Keep Go BFF running on 8088
- [ ] Deploy Vue on 5173
- [ ] Configure nginx/load balancer to route traffic

**Nginx Config Example**:
```nginx
location ~ ^/api {
  proxy_pass http://go-backend:5000;
}

location ~ ^/(admin|oauth|public) {
  proxy_pass http://vue-frontend:5173;
}

location / {
  proxy_pass http://go-backend:8088;  # fallback to Go BFF
}
```

#### 7.4: Feature Flag Implementation (Optional)
- [ ] Add feature flag in Go backend to enable Vue frontend
- [ ] Route authenticated users to Vue or Go based on flag
- [ ] Gradually increase Vue traffic percentage

#### 7.5: Monitoring & Logging
- [ ] Set up application error tracking (Sentry, DataDog, etc.)
- [ ] Monitor API response times
- [ ] Monitor frontend performance (RUM)
- [ ] Set up alerts for errors > 5% of requests

#### 7.6: Cutover Strategy
- [ ] Send 10% traffic to Vue → monitor for 24h
- [ ] Send 50% traffic to Vue → monitor for 24h
- [ ] Send 100% traffic to Vue → continue monitoring
- [ ] Keep Go BFF running for immediate rollback

**Metrics to Monitor**:
- Error rate (< 1%)
- Response time (< 2s)
- Login success rate (>98%)
- OAuth flow completion rate (>95%)

#### 7.7: Go BFF Decommission
- [ ] After 1 week of 100% Vue traffic with no issues
- [ ] Archive Go BFF code (don't delete)
- [ ] Remove from production
- [ ] Update documentation

#### 7.8: Post-Launch
- [ ] Monitor for 2 weeks in production
- [ ] Collect user feedback
- [ ] Document lessons learned
- [ ] Create maintenance runbook

### Deliverables (Phase 7)
- ✅ Production build tested
- ✅ Staging deployment successful
- ✅ Parallel deployment running (Go + Vue)
- ✅ Feature flag (if used) configured
- ✅ Monitoring + alerting in place
- ✅ Gradual cutover to Vue completed
- ✅ Go BFF decommissioned
- ✅ Maintenance runbook documented

---

## Timeline Summary

| Phase | Duration | Focus |
|-------|----------|-------|
| 1 | Weeks 1-2 | Infrastructure setup |
| 2 | Weeks 3-4 | Auth flows |
| 3 | Weeks 5-6 | Admin dashboard |
| 4 | Week 7 | OAuth client pages |
| 5 | Week 8 | Polish & features |
| 6 | Weeks 9-10 | Testing & validation |
| 7 | Weeks 11-12 | Deployment & cutover |

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| OAuth flow breaks | Extensive E2E testing before cutover |
| Performance degradation | Implement lazy loading, code splitting |
| Data loss | Backup database before cutover |
| CSRF vulnerability | Ensure token validation in Go backend |
| Session expiration issues | Implement automatic token refresh |
| Team unfamiliarity with Vue | Pair programming, documentation, training |

---

**Next Steps**: See `COMPONENT_ARCHITECTURE.md` for detailed component design patterns.

