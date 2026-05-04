# Component Architecture & Design System

**Focus**: Reusable components, theming system, form patterns, layouts  
**Date**: May 1, 2026

---

## Component Hierarchy

### Level 1: Atomic Components (Reka UI Wrappers)

These are minimal wrappers around Reka UI headless components for consistent styling.

```
ui/
├── Button.vue              # Button variants (primary, outline, ghost, destructive)
├── Input.vue               # Text input with error state
├── Card.vue                # Container with header, title, content, footer
├── Card/
│   ├── Header.vue
│   ├── Title.vue
│   ├── Content.vue
│   └── Footer.vue
├── Dialog.vue              # Modal overlay
├── Tabs.vue                # Tabbed interface
├── Badge.vue               # Status labels (active, inactive, admin, etc.)
├── Avatar.vue              # User initials circles
├── Alert.vue               # Error, warning, info, success alerts
└── Select.vue              # Dropdown select
```

### Level 2: Composite Components

These combine atomic components into domain-specific patterns.

```
auth/
├── LoginForm.vue           # Email + password + submit
├── RegisterForm.vue        # Multi-field registration
├── ForgotPasswordForm.vue  # Multi-step password recovery
└── OTPInput.vue            # 6-digit centered input

admin/
├── Sidebar.vue             # Navigation sidebar
├── TopBar.vue              # Top navigation
├── Dashboard/
│   ├── StatCard.vue        # KPI card with icon + value + trend
│   ├── LineChart.vue       # Line chart (wrapper for chart library)
│   ├── ActivityFeed.vue    # Activity list
│   └── SecurityAlerts.vue  # Alert list
├── Users/
│   ├── UserTable.vue       # Data table with TanStack Vue Table
│   └── SearchBar.vue       # Search + filter inputs
└── Clients/
    ├── ClientTable.vue
    └── SearchBar.vue

oauth/
├── OAuthLoginForm.vue      # Client-branded login
├── OAuthRegisterForm.vue   # Client-branded register
├── OAuthConsent.vue        # Scope approval page
└── ScopeCard.vue           # Scope display with icon
```

### Level 3: Page-Level Components (Views)

These are route-level components that compose composite components.

```
views/
├── admin/
│   ├── DashboardView.vue
│   ├── UsersView.vue
│   ├── ClientsView.vue
│   ├── AnalyticsView.vue
│   └── ...others
└── oauth/
    ├── OAuthLoginView.vue
    └── OAuthConsentView.vue
```

### Level 4: Layouts

These wrap page-level components with shared structure (sidebar, header, etc.).

```
layouts/
├── AdminLayout.vue         # Sidebar + TopBar + content
├── AuthLayout.vue          # Centered card
├── OAuthLayout.vue         # Client-branded background
└── PublicLayout.vue        # Header + Footer + content
```

---

## UI Component Specifications

### Button.vue

**Props**:
```typescript
interface ButtonProps {
  variant?: 'primary' | 'outline' | 'ghost' | 'destructive'  // default: 'primary'
  size?: 'sm' | 'md' | 'lg'  // default: 'md'
  disabled?: boolean
  loading?: boolean
  icon?: LucideIcon  // Lucide icon component
  fullWidth?: boolean
}
```

**Usage**:
```vue
<Button variant="primary" size="lg" :disabled="isLoading">
  {{ isLoading ? 'Loading...' : 'Sign In' }}
</Button>
```

**Styling**:
```
primary: bg-[var(--theme-button)] text-white hover:opacity-90
outline: border-2 border-gray-300 bg-transparent text-gray-900 hover:bg-gray-50
ghost: bg-transparent text-gray-600 hover:bg-gray-100
destructive: bg-red-600 text-white hover:bg-red-700
```

---

### Card.vue

**Structure**:
```vue
<Card>
  <CardHeader>
    <CardTitle>Dashboard</CardTitle>
    <CardDescription>Welcome back!</CardDescription>
  </CardHeader>
  <CardContent>
    <!-- Content here -->
  </CardContent>
  <CardFooter>
    <Button>Save</Button>
  </CardFooter>
</Card>
```

**Styling**: White background (white-50 in dark mode), rounded border, shadow

---

### Input.vue

**Props**:
```typescript
interface InputProps {
  type?: 'text' | 'email' | 'password' | 'number' | 'url'
  placeholder?: string
  disabled?: boolean
  error?: string  // Error message
  value?: string | number
  required?: boolean
}
```

**Usage**:
```vue
<Input 
  type="email" 
  placeholder="user@example.com"
  :error="errors.email"
  @update:modelValue="handleChange"
/>
```

**Styling**:
```
Normal: border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-[var(--theme-primary)]
Error: border-2 border-red-500 bg-red-50
```

---

### Badge.vue

**Props**:
```typescript
interface BadgeProps {
  variant?: 'default' | 'success' | 'warning' | 'error' | 'info'
}
```

**Usage**:
```vue
<Badge variant="success">Active</Badge>
<Badge variant="error">Inactive</Badge>
<Badge variant="info">Admin</Badge>
```

**Variants**:
| Variant | Background | Text Color |
|---------|-----------|-----------|
| default | gray-200 | gray-800 |
| success | green-100 | green-700 |
| warning | yellow-100 | yellow-700 |
| error | red-100 | red-700 |
| info | blue-100 | blue-700 |

---

### Avatar.vue

**Props**:
```typescript
interface AvatarProps {
  initials?: string  // E.g., "JD" for John Doe
  email?: string     // Fallback if no initials
  size?: 'sm' | 'md' | 'lg'
}
```

**Usage**:
```vue
<Avatar initials="JD" size="md" />
```

**Styling**: Circular, colored background (hash email to color), white initials

---

### Dialog.vue

**Props**:
```typescript
interface DialogProps {
  open?: boolean
  title?: string
}
```

**Usage**:
```vue
<Dialog v-model:open="isOpen" title="Confirm">
  <p>Are you sure?</p>
  <div class="flex gap-2 justify-end">
    <Button variant="outline" @click="isOpen = false">Cancel</Button>
    <Button @click="handleConfirm">Confirm</Button>
  </div>
</Dialog>
```

---

### Alert.vue

**Props**:
```typescript
interface AlertProps {
  type?: 'error' | 'success' | 'warning' | 'info'
  title?: string
  closeable?: boolean
}
```

**Usage**:
```vue
<Alert type="error" title="Login failed">
  Invalid email or password
</Alert>
```

**Icons**:
| Type | Icon | Background |
|------|------|-----------|
| error | X | red-50 |
| success | Check | green-50 |
| warning | AlertTriangle | yellow-50 |
| info | Info | blue-50 |

---

## Composite Component Patterns

### StatCard.vue

**Purpose**: Display a KPI with icon, value, trend

**Props**:
```typescript
interface StatCardProps {
  icon: LucideIcon
  title: string
  value: string | number
  trend?: string  // E.g., "+12% vs yesterday"
  trendColor?: 'green' | 'red'  // default: 'green'
}
```

**Example**:
```vue
<StatCard
  :icon="LayoutGrid"
  title="OAuth Clients"
  value="24"
  trend="+2 this week"
  trendColor="green"
/>
```

**Rendering**:
```
┌─────────────────┐
│ [icon] Title    │
│                 │
│ 24              │
│ +2 this week ↗️  │
└─────────────────┘
```

---

### LoginForm.vue

**Purpose**: Standard admin login with email, password, remember me

**Props**:
```typescript
interface LoginFormProps {
  isLoading?: boolean
  error?: string
}
```

**Emits**:
```typescript
emit('submit', { email: string, password: string, rememberMe: boolean })
```

**Features**:
- Email + password inputs with validation
- Remember me checkbox
- Error message display
- Submit button (disabled while loading)
- Link to forgot password

---

### UserTable.vue

**Purpose**: Display users with sorting, filtering, pagination

**Built with**: @tanstack/vue-table

**Props**:
```typescript
interface UserTableProps {
  users: User[]
  isLoading?: boolean
  pageSize?: number
}
```

**Features**:
- Sortable columns: Name, Role, Status, Last Login, Created
- Filter by role or status
- Pagination (10 items/page default)
- Row actions (edit, delete)

**Implementation Pattern**:
```typescript
const columns = [
  {
    accessorKey: 'email',
    header: 'Email',
    cell: (info) => `${info.row.original.firstName} ${info.row.original.lastName}`
  },
  // ... more columns
]

const table = useVueTable({
  data: users,
  columns,
  getCoreRowModel: getCoreRowModel()
})
```

---

## Theming System

### CSS Custom Properties

All theming uses CSS vars defined in `assets/css/theme-variables.css`:

```css
:root {
  /* Light mode defaults */
  --theme-primary: #3b82f6;           /* Primary action color */
  --theme-background: #ffffff;        /* Page background */
  --theme-button: #3b82f6;             /* Button background */
  --theme-text: #1f2937;               /* Primary text */
  
  /* Additional semantic colors */
  --theme-border: #e5e7eb;
  --theme-card: #ffffff;
  --theme-input-bg: #f9fafb;
}

@media (prefers-color-scheme: dark) {
  :root {
    --theme-primary: #1e40af;
    --theme-background: #111827;
    --theme-button: #1e40af;
    --theme-text: #f3f4f6;
    /* ... more dark mode colors ... */
  }
}
```

### Theme Application in Components

**Direct Usage**:
```vue
<template>
  <button :style="{ backgroundColor: 'var(--theme-button)' }">
    Submit
  </button>
</template>
```

**Via Tailwind**:
```vue
<template>
  <button class="bg-[var(--theme-button)] text-white">
    Submit
  </button>
</template>
```

### Dynamic Theme Loading

**useTheme.ts**:
```typescript
export function useTheme() {
  const themeStore = useThemeStore()
  
  const applyThemeToDB = () => {
    const colors = themeStore.activeThemeColors
    document.documentElement.style.setProperty('--theme-primary', colors.primary)
    document.documentElement.style.setProperty('--theme-background', colors.background)
    document.documentElement.style.setProperty('--theme-button', colors.button)
    document.documentElement.style.setProperty('--theme-text', colors.text)
  }
  
  const loadTheme = async (clientId: string) => {
    await themeStore.loadThemeByClientId(clientId)
    applyThemeToDB()
  }
  
  return { loadTheme, applyThemeToDB }
}
```

---

## Form Patterns

### Multi-Step Form (ForgotPassword)

**Pattern**: State machine with 3 steps

```typescript
const currentStep = ref<'email' | 'otp' | 'password'>('email')

const handleEmailSubmit = async () => {
  await authStore.requestPasswordReset(form.email)
  currentStep.value = 'otp'
}

const handleOTPSubmit = async () => {
  const verified = await authStore.verifyResetOTP(form.email, form.otp)
  if (verified) {
    currentStep.value = 'password'
  }
}

const handlePasswordSubmit = async () => {
  await authStore.resetPassword(form.email, form.token, form.password)
  // Success
}
```

**Rendering**:
```vue
<form v-if="currentStep === 'email'" @submit.prevent="handleEmailSubmit">
  <!-- Email step -->
</form>

<form v-else-if="currentStep === 'otp'" @submit.prevent="handleOTPSubmit">
  <!-- OTP step -->
</form>

<form v-else-if="currentStep === 'password'" @submit.prevent="handlePasswordSubmit">
  <!-- Password step -->
</form>
```

---

### Modal Overlays (OTP Verification)

**Pattern**: Fixed overlay with backdrop

```vue
<template>
  <div v-if="showOTP" class="fixed inset-0 z-50">
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black bg-opacity-50" @click="closeOTP"></div>
    
    <!-- Modal -->
    <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 bg-white rounded-lg shadow-xl p-8">
      <h2 class="text-2xl font-bold mb-4">Verify your email</h2>
      <OTPInput @complete="handleOTPComplete" />
      <p class="text-sm text-gray-600 mt-4">
        Didn't receive a code?
        <button @click="resendOTP" class="text-blue-600">Resend</button>
      </p>
    </div>
  </div>
</template>
```

---

## Layout Patterns

### AdminLayout

**Structure**:
```
┌─────────────────────────────────┐
│       TopBar (fixed)            │
├──────────┬───────────────────────┤
│          │                       │
│ Sidebar  │    Main Content       │
│ (fixed)  │    (scrollable)       │
│          │                       │
│          │                       │
└──────────┴───────────────────────┘
```

**Responsive Behavior**:
- Desktop (1024px+): Sidebar always visible
- Tablet (768px-1023px): Sidebar collapsible
- Mobile (<768px): Sidebar hidden, hamburger menu

---

### AuthLayout

**Structure**:
```
┌────────────────────┐
│                    │
│  ┌──────────────┐  │
│  │              │  │
│  │  Auth Card   │  │
│  │              │  │
│  └──────────────┘  │
│                    │
└────────────────────┘
```

**Styling**: Gray background (neutral), centered white card, shadow

---

### OAuthLayout

**Structure**:
```
┌─────────────────────────┐
│ [dark mode toggle]      │
│ ┌───────────────────┐   │
│ │                   │   │
│ │  OAuth Card       │   │
│ │  (themed colors)  │   │
│ │                   │   │
│ └───────────────────┘   │
│ [powered by closeauth]  │
└─────────────────────────┘
```

**Styling**: Background color from theme, card white for contrast

---

## Component Communication Patterns

### Props Down, Events Up

```vue
<!-- Parent Component -->
<template>
  <LoginForm @submit="handleLogin" :is-loading="isLoading" :error="error" />
</template>

<!-- Child Component -->
<template>
  <form @submit.prevent="$emit('submit', formData)">
    <!-- form fields -->
  </form>
</template>

<script setup lang="ts">
defineProps<{ isLoading?: boolean; error?: string }>()
defineEmits<{ submit: [data: LoginFormData] }>()
</script>
```

### Pinia Store (Global State)

```typescript
// Components access store directly
const authStore = useAuthStore()
const isAuthenticated = computed(() => authStore.isAuthenticated)

// Multiple components share same state
// Changes in one component update all subscribers
```

### Provide/Inject (Context)

```typescript
// Parent provides context
provide('themeContext', {
  colors: themeStore.colors,
  isDarkMode: computed(() => themeStore.isDarkMode)
})

// Child injects context
const themeContext = inject('themeContext')
```

---

## Testing Component Patterns

### Unit Test Template

```typescript
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Button from '@/components/ui/Button.vue'

describe('Button.vue', () => {
  it('renders slot content', () => {
    const wrapper = mount(Button, {
      slots: { default: 'Click me' }
    })
    expect(wrapper.text()).toContain('Click me')
  })
  
  it('emits click event', async () => {
    const wrapper = mount(Button)
    await wrapper.trigger('click')
    expect(wrapper.emitted('click')).toHaveLength(1)
  })
  
  it('disables button when disabled prop is true', () => {
    const wrapper = mount(Button, { props: { disabled: true } })
    expect(wrapper.attributes('disabled')).toBeDefined()
  })
})
```

### Integration Test Template

```typescript
describe('LoginForm Integration', () => {
  it('submits form with credentials', async () => {
    const wrapper = mount(LoginForm)
    
    await wrapper.find('input[type="email"]').setValue('user@example.com')
    await wrapper.find('input[type="password"]').setValue('password')
    await wrapper.find('form').trigger('submit')
    
    expect(wrapper.emitted('submit')).toEqual([[{
      email: 'user@example.com',
      password: 'password'
    }]])
  })
})
```

---

## Accessibility Guidelines

### Forms

- ✅ Every input has associated `<label>`
- ✅ Error messages use `aria-describedby`
- ✅ Required fields have `required` attribute + mark with asterisk
- ✅ Form has `aria-label` if no visible title

**Example**:
```vue
<label for="email">Email Address</label>
<input
  id="email"
  type="email"
  aria-describedby="email-error"
  required
/>
<div id="email-error" v-if="errors.email" class="text-red-600">
  {{ errors.email }}
</div>
```

### Buttons

- ✅ All buttons have visible text or `aria-label`
- ✅ Icon-only buttons have `aria-label` describing action
- ✅ Buttons have sufficient color contrast (WCAG AA: 4.5:1 minimum)

**Example**:
```vue
<button aria-label="Delete user" class="text-red-600 hover:text-red-800">
  <TrashIcon />
</button>
```

### Navigation

- ✅ Use semantic HTML: `<nav>`, `<main>`, `<aside>`
- ✅ Sidebar nav items have `aria-current="page"` on active item
- ✅ Links are distinguishable from text

### Dark Mode

- ✅ Sufficient contrast in dark mode
- ✅ No reliance on color alone to convey information

---

## Performance Optimization

### Lazy Loading Components

```typescript
// Instead of:
import Dashboard from '@/views/admin/DashboardView.vue'

// Use:
const Dashboard = defineAsyncComponent(() =>
  import('@/views/admin/DashboardView.vue')
)

// In route:
{
  path: 'dashboard',
  component: Dashboard
}
```

### Memoization

```typescript
// Cache expensive computations
const expensiveComputation = computed(() => {
  return complexCalculation(data.value)
})

// Don't recompute unless `data` changes
```

### Event Delegation

```vue
<!-- Instead of:  v-for + @click on each item -->
<!-- Use:        @click on parent, check e.target -->
<ul @click="handleItemClick">
  <li v-for="item in items" :key="item.id" :data-id="item.id">
    {{ item.name }}
  </li>
</ul>
```

---

## Best Practices Summary

1. ✅ **Single Responsibility**: Each component does one thing
2. ✅ **Props Interface**: Clear, typed props
3. ✅ **Event Names**: Descriptive, conventional names
4. ✅ **Styling**: Use Tailwind classes + CSS vars for theming
5. ✅ **Accessibility**: WCAG AA compliance
6. ✅ **Error Handling**: Graceful fallbacks
7. ✅ **Performance**: Lazy load, memoize, delegate
8. ✅ **Testing**: Unit + integration tests
9. ✅ **Documentation**: JSDoc comments on props/emits
10. ✅ **Reusability**: Design for composability

---

**Next Steps**: See `SECURITY_AND_COOKIE_STRATEGY.md` for detailed security implementation.

