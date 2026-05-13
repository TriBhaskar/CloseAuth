# Improvements and Best Practices

**Focus**: Performance optimization, accessibility (a11y), testing strategy, monitoring, deployment  
**Date**: May 1, 2026

---

## Performance Optimization

### Frontend Performance Targets

| Metric | Target | Tool |
|--------|--------|------|
| **LCP** (Largest Contentful Paint) | < 2.5s | Lighthouse |
| **FID** (First Input Delay) | < 100ms | Lighthouse |
| **CLS** (Cumulative Layout Shift) | < 0.1 | Lighthouse |
| **TTI** (Time to Interactive) | < 3s | Lighthouse |
| **Bundle Size** | < 500KB (gzipped) | Bundle Analyzer |
| **Lighthouse Score** | > 90 | Lighthouse |

### Code Splitting by Route

**Problem**: Initial bundle contains all page components, slowing down first load.

**Solution**: Lazy-load page components

```typescript
// router/index.ts
const DashboardView = defineAsyncComponent(() =>
  import('@/views/admin/DashboardView.vue')
)

const UsersView = defineAsyncComponent(() =>
  import('@/views/admin/UsersView.vue')
)

// Routes automatically code-split into separate chunks
const routes = [
  {
    path: '/admin/dashboard',
    component: DashboardView  // Loaded on demand
  },
  {
    path: '/admin/users',
    component: UsersView      // Loaded on demand
  }
  // ... more routes
]
```

**Result**: Initial bundle ~100KB (gzipped), user gets components as needed.

### Lazy Load Heavy Components

```typescript
// Define chart component lazily
const LineChart = defineAsyncComponent(() =>
  import('@/components/admin/Dashboard/LineChart.vue')
)

// Use with Suspense for loading state
<template>
  <Suspense>
    <template #default>
      <LineChart :data="data" />
    </template>
    <template #fallback>
      <div class="h-80 bg-gray-200 rounded animate-pulse">
        Loading chart...
      </div>
    </template>
  </Suspense>
</template>
```

### Memoization of Expensive Computations

```typescript
// Bad: Recomputes on every render
const totalRequests = computed(() => {
  return requests.value.reduce((sum, req) => sum + req.count, 0)
})

// Good: Additional conditions prevent unnecessary recomputation
const totalRequests = computed(() => {
  // Only recomputes when requests array changes
  return requests.value.reduce((sum, req) => sum + req.count, 0)
}, {
  // Optional: set dependencies explicitly
  includes: [requests]
})
```

### Image & Asset Optimization

```typescript
// Use Vite's built-in image optimization
import logo from '@/assets/logo.svg?url'          // SVG as URL
import avatar from '@/assets/avatar.png?url'      // Optimized PNG
import icon from '@/assets/icon.png?url&w=32'     // Resize to 32px

// Serve WebP with fallback
<picture>
  <source srcset="image.webp" type="image/webp" />
  <source srcset="image.png" type="image/png" />
  <img src="image.png" alt="..." />
</picture>
```

### API Caching Strategy

```typescript
// Cache API responses for 5 minutes
const apiClient = axios.create()

const cache = new Map<string, { data: any; timestamp: number }>()

apiClient.interceptors.request.use(config => {
  const cached = cache.get(config.url!)
  const cacheAge = cached ? Date.now() - cached.timestamp : Infinity
  
  // Use cache if < 5 minutes old
  if (cached && cacheAge < 5 * 60 * 1000) {
    return Promise.resolve({ data: cached.data, status: 200 })
  }
  
  return config
})

apiClient.interceptors.response.use(response => {
  // Cache successful GET responses
  if (response.config.method === 'get') {
    cache.set(response.config.url!, {
      data: response.data,
      timestamp: Date.now()
    })
  }
  return response
})
```

### Bundle Analysis

```bash
# Analyze bundle size
npm install -D webpack-bundle-analyzer

# In vite.config.ts
import { visualizer } from 'rollup-plugin-visualizer'

export default {
  plugins: [
    visualizer({ open: true })
  ]
}

npm run build  # Shows interactive visualization
```

### Performance Monitoring

```typescript
// Track metrics
const webVitals = {
  lcp: 0,
  fid: 0,
  cls: 0
}

import { getCLS, getFID, getFCP, getLCP, getTTFB } from 'web-vitals'

getLCP(metric => {
  webVitals.lcp = metric.value
  if (metric.value > 2500) {
    console.warn('LCP exceeds target:', metric)
  }
})

// Send to analytics
getLCP(metric => {
  const data = { name: 'LCP', value: metric.value }
  navigator.sendBeacon('/api/analytics/metrics', JSON.stringify(data))
})
```

---

## Accessibility (a11y) Best Practices

### WCAG 2.1 AA Compliance Checklist

#### Perceivable

- [ ] **Color Contrast**: Text has minimum 4.5:1 ratio (normal), 3:1 (large)
  ```typescript
  // Test: Use WebAIM contrast checker
  // Dark text on light background: #333 on #fff = 12.6:1 ✓
  // Light text on dark background: #fff on #222 = 14.6:1 ✓
  ```

- [ ] **Text Alternatives**: Images have alt text
  ```vue
  <img src="logo.svg" alt="CloseAuth logo" />
  <div aria-label="Delete user" role="button" @click="delete">🗑️</div>
  ```

- [ ] **Adaptable Content**: No info lost on 320px screens, text can be resized
  ```css
  html { font-size: 16px; }  /* User can zoom/resize */
  @media (max-width: 320px) {
    /* Reflow, don't hide */
    .sidebar { display: none; }
    .main { width: 100%; }
  }
  ```

#### Operable

- [ ] **Keyboard Navigation**: All interactive elements reachable via Tab
  ```vue
  <!-- Use semantic HTML -->
  <button @click="submit">Submit</button>
  <a href="/dashboard">Dashboard</a>
  <input type="text" />
  
  <!-- Skip to main content link (hidden, shown on focus) -->
  <a href="#main" class="sr-only focus:not-sr-only">Skip to main content</a>
  ```

- [ ] **Enough Time**: No time limits on tasks that require user input
  ```typescript
  // Don't auto-logout too quickly
  SESSION_TIMEOUT=3600  // 1 hour minimum
  ```

- [ ] **Input Modalities**: Accept both keyboard + mouse
  ```vue
  <!-- Mouse: click -->
  <!-- Keyboard: Enter key -->
  <button @click="submit" @keydown.enter="submit">Submit</button>
  ```

#### Understandable

- [ ] **Readable**: Content clear and easy to understand
  ```
  ✓ "Enter your email address"
  ✗ "Provide electronic mail ingress identifier"
  ```

- [ ] **Predictable**: Navigation consistent, no surprises
  ```
  ✓ Same sidebar on all admin pages
  ✗ Sidebar appears/disappears randomly
  ```

- [ ] **Input Assistance**: Errors are clear, prevention/recovery options
  ```vue
  <div role="alert" aria-describedby="error-txt">
    <span id="error-txt" class="text-red-600">
      Email must be in format: user@example.com
    </span>
  </div>
  ```

#### Robust

- [ ] **Compatible**: Works with assistive technologies (screen readers, etc.)
  ```vue
  <!-- Semantic HTML first -->
  <header>, <nav>, <main>, <aside>, <footer>
  <button>, <a>, <form>
  
  <!-- Proper ARIA only when HTML insufficient -->
  <div role="button" aria-pressed="false">Toggle</div>
  ```

### Accessible Component Patterns

#### Form Fields with Errors

```vue
<div class="space-y-2">
  <label for="email" class="block font-medium">
    Email Address
    <span aria-label="required" class="text-red-600">*</span>
  </label>
  
  <input
    id="email"
    type="email"
    placeholder="user@example.com"
    aria-describedby="email-error"
    required
    @blur="validateEmail"
  />
  
  <div
    v-if="errors.email"
    id="email-error"
    role="alert"
    class="text-sm text-red-600"
  >
    {{ errors.email }}
  </div>
</div>
```

#### Icon-Only Buttons

```vue
<button
  aria-label="Delete user"
  @click="deleteUser"
  class="text-red-600 hover:text-red-800"
>
  <TrashIcon size={24} />
</button>
```

#### Skip Navigation

```vue
<!-- Hidden, shown on focus -->
<a
  href="#main-content"
  class="sr-only focus:not-sr-only focus:absolute focus:top-0 focus:left-0 bg-blue-600 text-white p-2"
>
  Skip to main content
</a>

<main id="main-content">
  <!-- Page content -->
</main>

<!-- Utility class -->
<style>
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border-width: 0;
  }
</style>
```

### Testing Accessibility

```bash
# Install axe DevTools
npm install -D @axe-core/react

# Run accessibility audit
npx axe <path-to-component>

# Manual testing
# Tab through all pages
# Open screen reader (NVDA on Windows, VoiceOver on Mac)
# Disable CSS, verify content is still readable
```

---

## Testing Strategy

### Unit Tests (Vitest + Vue Test Utils)

```typescript
// tests/components/LoginForm.spec.ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import LoginForm from '@/components/auth/LoginForm.vue'

describe('LoginForm.vue', () => {
  let wrapper: any
  
  beforeEach(() => {
    wrapper = mount(LoginForm)
  })
  
  it('renders email and password inputs', () => {
    expect(wrapper.find('input[type="email"]').exists()).toBe(true)
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
  })
  
  it('emits submit event with form data', async () => {
    await wrapper.find('input[type="email"]').setValue('test@example.com')
    await wrapper.find('input[type="password"]').setValue('password123')
    await wrapper.find('form').trigger('submit')
    
    expect(wrapper.emitted('submit')).toBeTruthy()
    expect(wrapper.emitted('submit')[0]).toEqual([{
      email: 'test@example.com',
      password: 'password123'
    }])
  })
  
  it('disables submit button while loading', async () => {
    await wrapper.setProps({ isLoading: true })
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
  })
  
  it('displays error message', async () => {
    await wrapper.setProps({ error: 'Invalid credentials' })
    expect(wrapper.text()).toContain('Invalid credentials')
  })
})
```

### Integration Tests (Vitest + Pinia)

```typescript
// tests/integration/auth.spec.ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { mount } from '@vue/test-utils'

describe('Auth Integration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })
  
  it('logs in user and updates store', async () => {
    const authStore = useAuthStore()
    
    // Mock API
    vi.mock('@/api/client', () => ({
      default: {
        post: vi.fn(() =>
          Promise.resolve({
            data: {
              user: { id: '123', email: 'test@example.com' }
            }
          })
        )
      }
    }))
    
    await authStore.login('test@example.com', 'password')
    
    expect(authStore.user).toEqual({ id: '123', email: 'test@example.com' })
    expect(authStore.isAuthenticated).toBe(true)
  })
})
```

### E2E Tests (Playwright)

```typescript
// tests/e2e/auth.spec.ts
import { test, expect } from '@playwright/test'

test.describe('Admin Login Flow', () => {
  test('should login successfully', async ({ page }) => {
    await page.goto('http://localhost:5173/admin/login')
    
    await page.fill('input[type="email"]', 'admin@example.com')
    await page.fill('input[type="password"]', 'password123')
    await page.click('button:has-text("Sign In")')
    
    await page.waitForURL('**/admin/dashboard')
    expect(page.url()).toContain('/admin/dashboard')
    
    // Verify dashboard loaded
    await expect(page.locator('h1')).toContainText('Dashboard')
  })
  
  test('should display error on invalid credentials', async ({ page }) => {
    await page.goto('http://localhost:5173/admin/login')
    
    await page.fill('input[type="email"]', 'wrong@example.com')
    await page.fill('input[type="password"]', 'wrongpassword')
    await page.click('button:has-text("Sign In")')
    
    const errorMsg = page.locator('[role="alert"]')
    await expect(errorMsg).toContainText('Invalid credentials')
  })
})
```

### Test Coverage Target

- **Unit Tests**: 80% code coverage
- **Integration Tests**: Critical paths (auth, OAuth, admin CRUD)
- **E2E Tests**: User journeys (login → dashboard, OAuth flow)

### Run Tests Locally

```bash
# Unit tests
npm run test

# Watch mode
npm run test:watch

# Coverage report
npm run test:coverage

# E2E tests
npm run test:e2e

# E2E headed mode (see browser)
npm run test:e2e:ui
```

---

## Monitoring & Logging

### Frontend Error Tracking

```typescript
// With Sentry
import * as Sentry from '@sentry/vue'

Sentry.init({
  dsn: import.meta.env.VITE_SENTRY_DSN,
  environment: import.meta.env.MODE,
  tracesSampleRate: 1.0,
  integrations: [
    new Sentry.BrowserTracing(),
    new Sentry.Replay()
  ]
})

app.use(Sentry.vueIntegration())

// Errors automatically caught and reported
```

### Custom Event Logging

```typescript
// Log user actions
const trackEvent = (eventName: string, properties: Record<string, any>) => {
  if (window.analytics) {
    window.analytics.track(eventName, properties)
  }
  
  console.log(`Event: ${eventName}`, properties)
}

// Usage
trackEvent('login', { email: 'user@example.com', success: true })
trackEvent('oauth_consent_approved', { clientId: 'abc123', scopes: ['openid', 'profile'] })
```

### Performance Metrics

```typescript
// Monitor real user metrics
import { getCLS, getFID, getFCP, getLCP, getTTFB } from 'web-vitals'

const reportMetric = (metric: Metric) => {
  // Send to analytics backend
  navigator.sendBeacon('/api/analytics/metrics', JSON.stringify({
    name: metric.name,
    value: metric.value,
    id: metric.id,
    page: window.location.pathname
  }))
}

getCLS(reportMetric)
getFID(reportMetric)
getFCP(reportMetric)
getLCP(reportMetric)
getTTFB(reportMetric)
```

### Backend Monitoring (Go)

```go
import "log/slog"

// Structured logging
slog.Info("user_login",
  "email", email,
  "success", success,
  "duration_ms", duration,
  "ip", ipAddress,
)

slog.Error("oauth_token_refresh_failed",
  "client_id", clientID,
  "error", err.Error(),
)

// Example output:
// time=2026-05-01T12:34:56Z level=INFO msg="user_login" email=user@example.com success=true duration_ms=245
```

---

## Deployment & DevOps

### Docker Deployment

```dockerfile
# Dockerfile (Vue Frontend)
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

```dockerfile
# Dockerfile (Go Backend)
FROM golang:1.25 AS builder
WORKDIR /build
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o server cmd/main.go

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=builder /build/server .
COPY .env .
EXPOSE 5000
CMD ["./server"]
```

### Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  frontend:
    build:
      context: ./closeauth-frontend/closeauth-web
      dockerfile: Dockerfile
    ports:
      - "5173:80"
    depends_on:
      - backend

  backend:
    build:
      context: ./
      dockerfile: Dockerfile
    ports:
      - "5000:5000"
    environment:
      PORT: 5000
      OAUTH2_SERVER_URL: http://spring:9088
      BFF_BASE_URL: http://backend:5000
      DB_HOST: postgres
      ENVIRONMENT: development
    depends_on:
      - postgres

  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: closeauth_bff
      POSTGRES_PASSWORD: password
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

### CI/CD Pipeline (GitHub Actions)

```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Node
        uses: actions/setup-node@v3
        with:
          node-version: 20
      
      - name: Install deps
        run: npm ci
      
      - name: Lint
        run: npm run lint
      
      - name: Type check
        run: npm run type-check
      
      - name: Unit tests
        run: npm run test
      
      - name: Build
        run: npm run build

  deploy:
    needs: test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v3
      
      - name: Build Docker image
        run: docker build -t closeauth:${{ github.sha }} .
      
      - name: Push to registry
        run: docker push closeauth:${{ github.sha }}
      
      - name: Deploy to production
        run: |
          # Deploy logic here
          kubectl set image deployment/closeauth closeauth=closeauth:${{ github.sha }}
```

### Kubernetes Deployment (Optional)

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: closeauth
spec:
  replicas: 3
  selector:
    matchLabels:
      app: closeauth
  template:
    metadata:
      labels:
        app: closeauth
    spec:
      containers:
      - name: frontend
        image: closeauth:latest
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
      
      - name: backend
        image: closeauth-backend:latest
        ports:
        - containerPort: 5000
        env:
        - name: OAUTH2_SERVER_URL
          value: https://auth.prod.com
        - name: ENVIRONMENT
          value: production
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"

---
apiVersion: v1
kind: Service
metadata:
  name: closeauth
spec:
  selector:
    app: closeauth
  ports:
  - protocol: TCP
    port: 80
    targetPort: 80
  type: LoadBalancer
```

---

## Monitoring Checklist

### Pre-Launch

- [ ] Lighthouse score > 90
- [ ] All unit tests passing
- [ ] All E2E tests passing
- [ ] No console errors in dev/prod builds
- [ ] No security vulnerabilities (npm audit)
- [ ] WCAG AA accessibility compliant
- [ ] Load tested (1000 concurrent users)
- [ ] Tested on all major browsers

### Post-Launch

- [ ] Error rate monitored (< 1%)
- [ ] Response time monitored (< 2s p99)
- [ ] Uptime monitored (> 99.9%)
- [ ] Database performance monitored
- [ ] User analytics enabled
- [ ] Alerts configured for critical issues
- [ ] Daily log review (first week)

---

## Optimization Summary

| Area | Optimization | Impact |
|------|-------------|--------|
| Bundle | Code splitting by route | -60% initial load |
| Loading | Lazy load charts | -200ms TTI |
| API | Response caching | -40% API calls |
| Images | WebP + resize | -70% image size |
| CSS | Purge unused classes | -30% CSS size |
| Monitoring | Error tracking | 50% faster issue resolution |
| Testing | Automated E2E | 80% fewer production bugs |

---

**Related Guides**:
- `STEP_BY_STEP_MIGRATION.md` - Phases 5-7 (Polish, testing, deployment)
- `SECURITY_AND_COOKIE_STRATEGY.md` - Security hardening
- `COMPONENT_ARCHITECTURE.md` - Reusable components


