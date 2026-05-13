# CloseAuth Backend-for-Frontend Migration Analysis

**Current State**: Go + Templ + HTMX (Port 8088)  
**Target State**: Vue 3 + Vite + TypeScript (Port 5173)  
**Spring Auth Server**: Port 9088 (unchanged)  
**Date**: May 1, 2026

---

## Executive Summary

The current Go BFF project is a fully-featured Backend-for-Frontend layer with:
- **17 distinct screens** across 3 surfaces (Public, Admin, OAuth2 Client)
- **Complex OAuth2 proxy logic** intercepting and theming authentication flows
- **Client-specific theming** system with per-client branding
- **Security hardening** with AES-256-GCM encrypted cookies, CSRF protection, session management

**Challenges identified**:
- Tailwind v4 compatibility issues with Templ
- Templui component library learning curve + maintenance burden
- Limited team expertise in Go + Templ stack
- UI/UX inconsistencies across screens

**Migration Goal**: Replicate all functionality in Vue.js for better maintainability and developer experience.

---

## Current Architecture Overview

### High-Level Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Language** | Go 1.25.1 | Server-side rendering, OAuth proxy logic |
| **HTTP Router** | go-chi/chi v5 | Route handling, middleware composition |
| **Templates** | Templ v0.3.943 | Type-safe HTML generation |
| **Frontend** | HTMX + Tailwind CSS v4 | Progressive enhancement, styling |
| **Database** | PostgreSQL + sqlx | Client themes, session storage |
| **Security** | crypto/rand + AES-256-GCM | CSRF tokens, OAuth context encryption |
| **Logging** | log/slog | Structured logging |

### Directory Structure (Current Go BFF)

```
internal/
├── server.go                    # Server init, handler wiring
├── routes.go                    # Route registration (124 lines)
├── config/                      # Env var loading, validation
├── middleware/                  # CSRF, OAuth context, auth guards
├── handlers/
│   ├── auth.go                  # Admin login/register/forgot-password
│   ├── admin.go                 # Admin dashboard handlers
│   ├── client.go                # OAuth clients management
│   ├── public.go                # Public pages
│   ├── oauth_proxy.go           # OAuth2 proxy to Spring
│   ├── oauth_client_auth.go     # Client-branded auth pages
│   └── response/                # HTMX/JSON response helpers
├── database/                    # DB connection, models, repositories
├── sas/                         # Spring Auth Server client
├── templates/                   # Templ HTML templates
│   ├── base.templ               # Root HTML skeleton
│   ├── layouts/                 # Full pages (17 screens)
│   └── components/              # Reusable UI primitives
└── logger/                      # slog initialization

cmd/main.go                      # Entry point, graceful shutdown
static/                          # CSS, JS, images
```

---

## Current Screens & Surfaces (17 Total)

### Surface 1: Public (1 screen)
| Screen | Route | Components | Purpose |
|--------|-------|-----------|---------|
| Public Home | `/` | Header + Navigation + Hero + Footer | Landing page, auth status aware |

### Surface 2: Admin Authentication (4 screens)
| Screen | Route | Components | Notes |
|--------|-------|-----------|-------|
| Admin Login | `/admin/login` | Centered card form | Email/password, remember me |
| Admin Register | `/admin/register` | First/last name + email + password | Client-side validation |
| Admin Forgot Password | `/admin/forgot-password` | 3-step form (email → OTP → reset) | Multi-step HTMX swaps |
| OTP Verification | (inline fragment) | Email icon + 6-digit input | Injected into form container |

### Surface 3: Admin Portal (7 screens, protected)
| Screen | Route | Required Auth | Features |
|--------|-------|---------------|----------|
| Dashboard | `/admin/dashboard` | Yes | 6 stat cards, charts, activity feed |
| Users | `/admin/users` | Yes | User table, filters, avatars |
| OAuth Clients | `/admin/clients` | Yes | Client list, search, status badges |
| Create Client | `/admin/clients/new` | Yes | Form with scope checkboxes, grant types |
| Analytics | `/admin/analytics` | Yes | 4 metric cards, charts, trends |
| Security | `/admin/security` | Yes | Event logs, severity filtering, IP tracking |
| Settings | `/admin/settings` | Yes | 4 tabs (General, Security, Tokens, Notifications) |

### Surface 4: OAuth2 Client Identity (3 screens, client-branded)
| Screen | Route | Customizable | Purpose |
|--------|-------|-------------|---------|
| OAuth Login | `/oauth/login` | Logo, colors (light/dark), button text | Client-specific themed login |
| OAuth Register | `/oauth/register` | Logo, colors, consent page | Client-specific registration |
| OAuth Consent | `/oauth/consent` | Logo, client name, scope display | Scope approval screen |

---

## Current Security Architecture

### 1. OAuth Context Cookie Encryption

**Flow**:
```
User initiates OAuth flow → Spring redirects to login
↓
BFF captures JSESSIONID + OAuth params (response_type, client_id, etc.)
↓
AES-256-GCM encrypt: params → encrypted bytes → base64
↓
Set httpOnly cookie: oauth_context = base64(AES-encrypted data)
↓
Cookie expires in 600 seconds (10 min)
```

**OAuthContext Structure**:
```go
type OAuthContext struct {
    ResponseType    string  // "code"
    ClientID        string  // OAuth2 client ID
    RedirectURI     string  // Where to send authz code
    Scope           string  // Requested permissions
    State           string  // CSRF token from client
    Timestamp       int64   // For expiration check
    SpringSessionID string  // JSESSIONID for session continuity
    Username        string  // Set after login
}
```

### 2. CSRF Protection

**Mechanism**:
- 32-byte cryptographic random tokens (`crypto/rand`)
- Stored in httpOnly, SameSite=Lax cookie
- Validated on all POST/PUT/DELETE requests
- Can be submitted via form field or `X-CSRF-Token` header (for HTMX)
- Validation uses `crypto/subtle.ConstantTimeCompare` (timing-attack safe)

### 3. Session Management (Admin Routes)

**Session Cookie** (`bff_session`):
- Stores: User ID, email, access token, refresh token, expiry, created timestamp
- Encrypted: JSON → AES-256-GCM → base64
- Validated on every protected route
- httpOnly + Secure (production only)

### 4. Cookie Security Settings

| Cookie | httpOnly | Secure | SameSite | Max-Age | Purpose |
|--------|----------|--------|----------|---------|---------|
| `csrf_token` | ✅ | Prod only | Lax | Browser session | CSRF protection |
| `oauth_context` | ✅ | Prod only | Lax | 600 sec | OAuth flow state |
| `bff_session` | ✅ | Prod only | Strict | 24h | Admin authentication |
| `JSESSIONID` | ✅ | Prod only | Lax | — | Spring session (proxied) |

---

## Current OAuth2 Flow (Simplified)

```
1. Client initiates: GET /closeauth/oauth2/authorize?response_type=code&client_id=...
                     ↓
2. BFF proxies to Spring: GET localhost:9088/closeauth/oauth2/authorize
                     ↓
3. Spring detects no auth: 302 to /auth/login
                     ↓
4. BFF intercepts redirect, saves OAuth context + JSESSIONID in encrypted cookie
                     ↓
5. BFF redirects: 302 to /oauth/login?client_id=...
                     ↓
6. User logs in via themed BFF UI, credentials go to Spring
                     ↓
7. Spring authenticates, returns new JSESSIONID
                     ↓
8. BFF resumes auth flow: GET /closeauth/oauth2/authorize (with JSESSIONID)
                     ↓
9. Spring detects consent needed: 302 to /oauth/consent
                     ↓
10. BFF renders consent page with scope descriptions
                     ↓
11. User approves, BFF submits: POST /oauth2/authorize?client_id=...&state=...&scope=...
                     ↓
12. Spring returns: 302 to client_redirect_uri?code=AUTHZCODE&state=...
                     ↓
13. BFF forwards redirect to original client
```

**Critical JSESSIONID Management**:
- Spring won't set JSESSIONID on BFF domain (different port)
- BFF captures JSESSIONID from Spring responses
- BFF stores in encrypted cookie + forwards on subsequent requests
- Session continuity depends on correct JSESSIONID forwarding

---

## Current Theme System

### Database Structure

**Table**: `client_themes`
```sql
CREATE TABLE client_themes (
    id SERIAL PRIMARY KEY,
    client_id VARCHAR(100),        -- OAuth2 client ID
    theme_name VARCHAR(100),       -- 'light', 'dark', 'custom'
    is_active BOOLEAN,
    is_default BOOLEAN,            -- Which theme auto-loads
    logo_url VARCHAR(500),         -- Client logo
    light_primary_color VARCHAR(7),      -- #RRGGBB
    light_background_color VARCHAR(7),
    light_button_color VARCHAR(7),
    light_text_color VARCHAR(7),
    dark_primary_color VARCHAR(7),
    dark_background_color VARCHAR(7),
    dark_button_color VARCHAR(7),
    dark_text_color VARCHAR(7),
    default_mode VARCHAR(10),      -- 'light', 'dark', 'system'
    allow_mode_toggle BOOLEAN
);
```

### Theme Data Model

```go
type ThemeData struct {
    LogoURL        string          // Client logo URL
    ThemeName      string          // Theme identifier
    DefaultMode    string          // 'light', 'dark', 'system'
    AllowModeToggle bool           // Can user toggle light/dark?
    LightColors    map[string]string
    DarkColors     map[string]string
}
```

### Template Integration

**Base OAuth Template** (`templates/layouts/oauth_base.templ`):
- Injected as CSS custom properties:
  ```css
  :root {
      --theme-primary: #3b82f6;
      --theme-background: #ffffff;
      --theme-button: #3b82f6;
      --theme-text: #1f2937;
  }
  ```
- All components reference CSS vars: `background-color: var(--theme-background)`

---

## Routes Summary (30+ endpoints)

### OAuth2 Proxy Routes
- `GET  /closeauth/oauth2/authorize` — Forward to Spring, intercept login
- `POST /closeauth/oauth2/token` — Forward token request to Spring

### Admin Authentication Routes
- `GET  /admin/login` — Admin login form
- `POST /admin/login` — Submit login
- `GET  /admin/register` — Admin registration form
- `POST /admin/register` — Submit registration
- `GET  /admin/forgot-password` — Password recovery form
- `POST /admin/forgot-password/request` — Send reset OTP
- `POST /admin/forgot-password/verify-otp` — Verify OTP
- `POST /admin/forgot-password/reset` — Reset password

### Admin Dashboard Routes (protected)
- `GET /admin/dashboard` — Overview
- `GET /admin/users` — User management
- `GET /admin/clients` — OAuth client list
- `POST /admin/clients` — Create client
- `GET /admin/clients/new` — New client form
- `GET /admin/analytics` — Analytics
- `GET /admin/security` — Security logs
- `GET /admin/settings` — Settings

### OAuth Client Routes
- `GET  /oauth/login` — Client-branded login
- `POST /closeauth/login` — Submit OAuth login
- `GET  /oauth/register` — Client-branded register
- `POST /oauth/register` — Submit OAuth registration
- `POST /oauth/register/verify-otp` — Verify registration OTP
- `GET  /oauth/consent` — Consent page
- `POST /oauth/consent` — Submit consent

### Other
- `GET /health` — Health check
- `GET /` — Public home page
- `GET /static/*` — Static files

---

## Environment Configuration (Current Go BFF)

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8080` | BFF server port |
| `OAUTH2_SERVER_URL` | — | Spring Auth Server (e.g., `http://localhost:9088`) |
| `BFF_BASE_URL` | — | BFF's own URL (e.g., `http://localhost:8088`) |
| `OAUTH_CONTEXT_ENCRYPTION_KEY` | — | 32-byte AES key for OAuth cookie encryption |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `LOG_FORMAT` | `text` | `text` or `json` |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_USER` | `postgres` | PostgreSQL user |
| `DB_PASSWORD` | — | PostgreSQL password |
| `DB_NAME` | `closeauth_bff` | Database name |
| `ENVIRONMENT` | — | Set to `production` for Secure cookies |

---

## Known Challenges with Current Stack

### 1. **Tailwind CSS v4 Incompatibility**
- Templ's integration with Tailwind CSS v4 causes build issues
- Need to manually manage CSS build pipeline
- Class parsing problems with dynamic class strings

### 2. **Templui Learning Curve**
- Component library is less documented than mainstream alternatives
- Limited community support vs. shadcn/ui or Headless UI
- Templui requires understanding both Go templating + web components

### 3. **Limited Go Expertise**
- Team more familiar with JavaScript/TypeScript ecosystems
- Go concurrency model, goroutines unfamiliar
- Slower onboarding for new developers

### 4. **UI/UX Inconsistencies**
- Inconsistent spacing, typography across screens
- Dark mode support incomplete in some areas
- Component styling not fully aligned with modern design systems

### 5. **HTMX Complexity**
- While HTMX is powerful, it's unfamiliar to most frontend teams
- Harder to debug client-side behavior
- Limited tooling for testing HTMX interactions

---

## What Must Be Preserved in Vue Migration

### Non-Negotiable Requirements

1. ✅ **OAuth2 proxy interception logic** — Core value of BFF
2. ✅ **Session + CSRF security** — AES-256-GCM encryption, token validation
3. ✅ **Client theming system** — Per-client branding in database
4. ✅ **All 17 screens and layouts** — Exact feature parity
5. ✅ **JSESSIONID forwarding** — Critical for Spring session continuity
6. ✅ **Consent flow correctness** — No `response_type`/`redirect_uri` on submission
7. ✅ **Health check endpoint** — For load balancer probes
8. ✅ **Graceful shutdown** — Signal handling, resource cleanup

### Nice-to-Have Additions

- Improved admin dashboard UX (charts, analytics)
- Enhanced form validation with inline feedback
- Progressive enhancement without HTMX
- Enhanced logging and monitoring
- TypeScript type safety across frontend + backend

---

## Success Criteria

- [ ] All 17 screens replicated in Vue.js
- [ ] OAuth2 flow works end-to-end with Spring Auth Server
- [ ] CSRF tokens validated correctly on all POST requests
- [ ] AES-256-GCM encryption still protects OAuth context cookies
- [ ] Client theming system loads and applies correctly
- [ ] Admin dashboard loads and displays real data
- [ ] E2E tests pass for critical OAuth flows
- [ ] Performance metrics match or exceed Go BFF
- [ ] Team can modify and deploy without Go knowledge required

---

**Next Steps**: See `FRONTEND_IMPLEMENTATION_SPEC.md` for detailed Vue component specifications.

