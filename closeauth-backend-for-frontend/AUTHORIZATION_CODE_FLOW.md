# OAuth2 Authorization Code Flow - BFF Implementation Guide

This document explains how the OAuth2 Authorization Code flow is implemented in the CloseAuth Backend-for-Frontend (BFF) application. The BFF acts as an intermediary between external OAuth2 clients and the Spring Authorization Server (SAS).

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Flow Diagram](#flow-diagram)
3. [Detailed Flow Steps](#detailed-flow-steps)
4. [Key Components](#key-components)
5. [Session Management](#session-management)
6. [Consent Flow](#consent-flow)
7. [Code Reference](#code-reference)
8. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────────────┐
│  OAuth2 Client  │────▶│       BFF        │────▶│  Spring Auth Server     │
│  (Third Party)  │◀────│  (Go Application)│◀────│  (Authorization Server) │
└─────────────────┘     └──────────────────┘     └─────────────────────────┘
                               │
                               ▼
                        ┌──────────────────┐
                        │   User Browser   │
                        │  (Login/Consent) │
                        └──────────────────┘
```

**Key Responsibilities:**

| Component              | Responsibility                                                              |
| ---------------------- | --------------------------------------------------------------------------- |
| **OAuth2 Client**      | Initiates authorization request, receives authorization code                |
| **BFF**                | Proxies requests, provides themed login/consent UI, manages session cookies |
| **Spring Auth Server** | Validates clients, authenticates users, issues authorization codes          |

---

## Flow Diagram

```
                                    AUTHORIZATION CODE FLOW
    ┌────────────────────────────────────────────────────────────────────────────────┐
    │                                                                                │
    │  ┌─────────┐          ┌─────────┐          ┌─────────┐          ┌───────────┐ │
    │  │ Client  │          │   BFF   │          │ Browser │          │  Spring   │ │
    │  │  App    │          │         │          │         │          │  Auth     │ │
    │  └────┬────┘          └────┬────┘          └────┬────┘          └─────┬─────┘ │
    │       │                    │                    │                     │       │
    │       │  1. GET /closeauth/oauth2/authorize     │                     │       │
    │       │────────────────────▶                    │                     │       │
    │       │                    │  2. Proxy request  │                     │       │
    │       │                    │─────────────────────────────────────────▶│       │
    │       │                    │                    │                     │       │
    │       │                    │  3. 302 Redirect to /oauth/login         │       │
    │       │                    │◀─────────────────────────────────────────│       │
    │       │                    │                    │                     │       │
    │       │                    │  4. Save OAuth context + JSESSIONID      │       │
    │       │                    │     in encrypted cookie                  │       │
    │       │                    │                    │                     │       │
    │       │  5. 302 Redirect to /oauth/login        │                     │       │
    │       │◀───────────────────│                    │                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  6. GET /oauth/login                     │       │
    │       │                    │◀───────────────────│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  7. Render themed login page             │       │
    │       │                    │───────────────────▶│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  8. POST /closeauth/login (credentials)  │       │
    │       │                    │◀───────────────────│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  9. POST /login (with JSESSIONID)        │       │
    │       │                    │─────────────────────────────────────────▶│       │
    │       │                    │                    │                     │       │
    │       │                    │  10. 302 + New JSESSIONID (authenticated)│       │
    │       │                    │◀─────────────────────────────────────────│       │
    │       │                    │                    │                     │       │
    │       │                    │  11. Update oauth_context with new       │       │
    │       │                    │      JSESSIONID and username             │       │
    │       │                    │                    │                     │       │
    │       │                    │  12. 302 to /closeauth/oauth2/authorize  │       │
    │       │                    │───────────────────▶│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  13. GET /closeauth/oauth2/authorize     │       │
    │       │                    │◀───────────────────│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  14. Proxy with authenticated JSESSIONID │       │
    │       │                    │─────────────────────────────────────────▶│       │
    │       │                    │                    │                     │       │
    │       │                    │  15. 302 to /oauth/consent (if required) │       │
    │       │                    │◀─────────────────────────────────────────│       │
    │       │                    │                    │                     │       │
    │       │  16. 302 Redirect to /oauth/consent     │                     │       │
    │       │◀───────────────────│                    │                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  17. GET /oauth/consent                  │       │
    │       │                    │◀───────────────────│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  18. Render consent page                 │       │
    │       │                    │───────────────────▶│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  19. POST /oauth/consent (approve)       │       │
    │       │                    │◀───────────────────│                     │       │
    │       │                    │                    │                     │       │
    │       │                    │  20. POST /oauth2/authorize              │       │
    │       │                    │      (client_id, state, scope ONLY)      │       │
    │       │                    │─────────────────────────────────────────▶│       │
    │       │                    │                    │                     │       │
    │       │                    │  21. 302 to redirect_uri?code=xxx        │       │
    │       │                    │◀─────────────────────────────────────────│       │
    │       │                    │                    │                     │       │
    │       │  22. 302 to redirect_uri?code=xxx       │                     │       │
    │       │◀───────────────────│                    │                     │       │
    │       │                    │                    │                     │       │
    └───────┴────────────────────┴────────────────────┴─────────────────────┴───────┘
```

---

## Detailed Flow Steps

### Phase 1: Initial Authorization Request

| Step | Actor      | Action                                                          | Handler/File                                     |
| ---- | ---------- | --------------------------------------------------------------- | ------------------------------------------------ |
| 1    | Client App | Sends authorization request to BFF                              | -                                                |
| 2    | BFF        | Proxies request to Spring Auth Server                           | `oauth_proxy.go` → `HandleAuthorize()`           |
| 3    | Spring     | Detects unauthenticated user, returns redirect to login         | -                                                |
| 4    | BFF        | Captures `JSESSIONID` from Spring response, saves OAuth context | `oauth_proxy.go` → `handleUnauthenticatedUser()` |
| 5    | BFF        | Redirects browser to `/oauth/login`                             | `oauth_proxy.go`                                 |

**OAuth Context Cookie Structure:**

```go
type OAuthContext struct {
    ResponseType    string // "code"
    ClientID        string // OAuth2 client identifier
    RedirectURI     string // Where to send authorization code
    Scope           string // Requested permissions
    State           string // CSRF protection token from client
    Timestamp       int64  // For expiration check
    SpringSessionID string // JSESSIONID for session continuity
    Username        string // Set after login, used in consent
}
```

### Phase 2: User Authentication

| Step | Actor   | Action                                                     | Handler/File                                      |
| ---- | ------- | ---------------------------------------------------------- | ------------------------------------------------- |
| 6    | Browser | Requests login page                                        | -                                                 |
| 7    | BFF     | Renders client-themed login page                           | `oauth_client_auth.go` → `HandleOAuthLoginGet()`  |
| 8    | Browser | Submits credentials                                        | -                                                 |
| 9    | BFF     | Forwards credentials to Spring with preserved `JSESSIONID` | `oauth_client_auth.go` → `HandleOAuthLoginPost()` |
| 10   | Spring  | Authenticates user, returns new session                    | -                                                 |
| 11   | BFF     | Updates OAuth context with new `JSESSIONID` and username   | `oauth_client_auth.go`                            |
| 12   | BFF     | Redirects to authorization endpoint                        | `oauth_client_auth.go`                            |

**Critical: Session Continuity**

The BFF must preserve and forward `JSESSIONID` between requests:

```go
// When saving OAuth context after initial redirect
oauthCtx := &middleware.OAuthContext{
    // ... other fields
    SpringSessionID: springSessionID, // Captured from Spring's redirect response
}

// When submitting login
if oauthCtx.SpringSessionID != "" {
    req.AddCookie(&http.Cookie{
        Name:  "JSESSIONID",
        Value: oauthCtx.SpringSessionID,
    })
}
```

### Phase 3: Consent

| Step  | Actor       | Action                                               | Handler/File                                        |
| ----- | ----------- | ---------------------------------------------------- | --------------------------------------------------- |
| 13-14 | Browser/BFF | Re-requests authorization with authenticated session | `oauth_proxy.go`                                    |
| 15    | Spring      | Requires consent, redirects to consent page          | -                                                   |
| 16    | BFF         | Forwards redirect to consent page                    | `oauth_proxy.go`                                    |
| 17-18 | Browser/BFF | Renders consent page with scopes                     | `oauth_client_auth.go` → `HandleOAuthConsentGet()`  |
| 19    | Browser     | User approves consent                                | -                                                   |
| 20    | BFF         | Submits consent to Spring                            | `oauth_client_auth.go` → `HandleOAuthConsentPost()` |
| 21-22 | Spring/BFF  | Returns authorization code to client                 | -                                                   |

### Phase 4: Token Exchange

After receiving the authorization code, the client exchanges it for tokens:

```
Client App                     BFF                      Spring Auth Server
    │                           │                              │
    │  POST /closeauth/oauth2/token                            │
    │  (code, client_credentials)                              │
    │─────────────────────────▶│                              │
    │                           │  POST /oauth2/token          │
    │                           │─────────────────────────────▶│
    │                           │                              │
    │                           │  {access_token, refresh_token}
    │                           │◀─────────────────────────────│
    │                           │                              │
    │  {access_token, refresh_token}                           │
    │◀─────────────────────────│                              │
```

---

## Key Components

### 1. OAuth Proxy Handler (`internal/handlers/oauth_proxy.go`)

Proxies OAuth2 endpoints to Spring Authorization Server.

```go
// Main entry point for authorization requests
func (h *OAuthProxyHandler) HandleAuthorize(w http.ResponseWriter, r *http.Request)

// Handles token exchange
func (h *OAuthProxyHandler) HandleToken(w http.ResponseWriter, r *http.Request)

// Processes redirect responses (login or authorization code)
func (h *OAuthProxyHandler) handleAuthorizeRedirect(...)

// Saves OAuth context when user needs to authenticate
func (h *OAuthProxyHandler) handleUnauthenticatedUser(...)
```

### 2. OAuth Client Auth Handler (`internal/handlers/oauth_client_auth.go`)

Handles client-themed authentication and consent pages.

```go
// Renders themed login page
func (h *OAuthClientAuthHandler) HandleOAuthLoginGet(...)

// Processes login form, authenticates with Spring
func (h *OAuthClientAuthHandler) HandleOAuthLoginPost(...)

// Renders consent page with scope descriptions
func (h *OAuthClientAuthHandler) HandleOAuthConsentGet(...)

// Submits consent decision to Spring
func (h *OAuthClientAuthHandler) HandleOAuthConsentPost(...)
```

### 3. OAuth Context Middleware (`internal/middleware/oauth_context.go`)

Manages encrypted cookie storage for OAuth flow state.

```go
// Saves OAuth context to encrypted cookie
func SaveOAuthContext(w http.ResponseWriter, ctx *OAuthContext) error

// Retrieves OAuth context from cookie
func GetOAuthContext(r *http.Request) (*OAuthContext, error)

// Clears OAuth context cookie
func ClearOAuthContext(w http.ResponseWriter)
```

### 4. Routes (`internal/constants/routespath.go`)

```go
// OAuth2 proxy routes (to Spring)
RouteOAuthAuthorize = "/closeauth/oauth2/authorize"
RouteOAuthToken     = "/closeauth/oauth2/token"

// OAuth client authentication routes (BFF pages)
RouteOAuthClientLogin    = "/oauth/login"      // GET - display login
RouteOAuthClientLoginPost = "/closeauth/login" // POST - submit login
RouteOAuthConsent        = "/oauth/consent"    // GET/POST - consent page
```

---

## Session Management

### The JSESSIONID Challenge

Since the BFF and Spring Authorization Server are on different domains/ports, the browser won't automatically send Spring's `JSESSIONID` to the BFF. The BFF solves this by:

1. **Capturing JSESSIONID** from Spring's initial redirect response
2. **Storing it** in the encrypted `oauth_context` cookie
3. **Forwarding it** with subsequent requests to Spring

```
Browser → BFF Request
   │
   ├─ Cookies: csrf_token, oauth_context (BFF's cookies)
   │
   └─ BFF extracts SpringSessionID from oauth_context
      and adds it to proxy request to Spring
```

### Cookie Flow Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                           COOKIE MANAGEMENT                                  │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Initial Authorize Request                                                │
│     Spring Response: Set-Cookie: JSESSIONID=ABC123                          │
│     BFF Action: Save ABC123 in oauth_context cookie                         │
│                                                                              │
│  2. Login Submission                                                         │
│     Browser sends: oauth_context cookie (contains ABC123)                   │
│     BFF extracts: JSESSIONID=ABC123                                         │
│     BFF forwards to Spring with: Cookie: JSESSIONID=ABC123                  │
│                                                                              │
│  3. After Successful Login                                                   │
│     Spring Response: Set-Cookie: JSESSIONID=XYZ789 (new session)           │
│     BFF Action: Update oauth_context with XYZ789                            │
│     BFF also: Forward JSESSIONID cookie to browser                          │
│                                                                              │
│  4. Consent Submission                                                       │
│     Browser may send: JSESSIONID (if same domain)                           │
│     OR BFF uses: oauth_context.SpringSessionID                              │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Consent Flow

### Critical: Consent vs New Authorization Request

When submitting consent to Spring, the BFF must send **only** these parameters:

```go
formData := url.Values{}
formData.Set("client_id", clientID)
formData.Set("state", state)      // State from Spring's consent redirect
formData.Set("scope", scopeStr)   // Consented scopes, space-separated
```

**DO NOT send:**

- `response_type` - Makes Spring treat this as a NEW authorization request
- `redirect_uri` - Same issue

Spring uses the `state` parameter to look up the existing authorization request and complete the consent flow.

### Consent Page Data

```go
type OAuthConsentData struct {
    CSRFToken   string
    Theme       ThemeData      // Client-specific styling
    ClientID    string
    ClientName  string         // Human-readable name
    LogoURL     string         // Client's logo
    Username    string         // Logged-in user
    Scopes      []ScopeDisplay // Scope descriptions
    State       string         // Spring's consent state
    RedirectURI string         // Where to redirect after consent
}
```

---

## Code Reference

### File Structure

```
internal/
├── handlers/
│   ├── oauth_proxy.go          # Proxies /oauth2/authorize and /oauth2/token
│   └── oauth_client_auth.go    # Handles /oauth/login and /oauth/consent
├── middleware/
│   └── oauth_context.go        # Encrypted cookie management
├── constants/
│   └── routespath.go           # Route definitions
└── routes.go                   # Route registration
```

### Request Flow Through Code

```
1. GET /closeauth/oauth2/authorize
   └─▶ OAuthProxyHandler.HandleAuthorize()
       └─▶ proxyToSpring()
           └─▶ handleAuthorizeRedirect()
               └─▶ handleUnauthenticatedUser()  [if not logged in]
                   └─▶ middleware.SaveOAuthContext()
                   └─▶ Redirect to /oauth/login

2. GET /oauth/login
   └─▶ OAuthClientAuthHandler.HandleOAuthLoginGet()
       └─▶ middleware.GetOAuthContext()
       └─▶ Render templates.OAuthLogin()

3. POST /closeauth/login
   └─▶ OAuthClientAuthHandler.HandleOAuthLoginPost()
       └─▶ HTTP POST to Spring /login (with JSESSIONID)
       └─▶ middleware.SaveOAuthContext() [update session]
       └─▶ Redirect to /closeauth/oauth2/authorize

4. GET /closeauth/oauth2/authorize (authenticated)
   └─▶ OAuthProxyHandler.HandleAuthorize()
       └─▶ proxyToSpring() [with authenticated JSESSIONID]
       └─▶ handleAuthorizeRedirect()
           └─▶ Redirect to /oauth/consent [if consent required]

5. GET /oauth/consent
   └─▶ OAuthClientAuthHandler.HandleOAuthConsentGet()
       └─▶ Render templates.OAuthConsent()

6. POST /oauth/consent
   └─▶ OAuthClientAuthHandler.HandleOAuthConsentPost()
       └─▶ HTTP POST to Spring /oauth2/authorize
           (client_id, state, scope ONLY)
       └─▶ middleware.ClearOAuthContext()
       └─▶ Redirect to client's redirect_uri with code
```

---

## Troubleshooting

### Common Issues

#### 1. Consent Loop (Redirected to consent page repeatedly)

**Symptom:** After approving consent, user is redirected back to consent page.

**Cause:** Sending `response_type` or `redirect_uri` with consent submission makes Spring treat it as a new authorization request.

**Solution:** Only send `client_id`, `state`, and `scope` when submitting consent.

```go
// ✅ Correct
formData.Set("client_id", clientID)
formData.Set("state", state)
formData.Set("scope", scopeStr)

// ❌ Wrong - DO NOT include these
formData.Set("response_type", responseType)  // Causes new auth request
formData.Set("redirect_uri", redirectURI)    // Causes new auth request
```

#### 2. Session Lost After Login

**Symptom:** After successful login, user is redirected back to login page.

**Cause:** `JSESSIONID` not being forwarded properly.

**Solution:** Ensure OAuth context preserves and forwards `SpringSessionID`:

```go
// Capture from Spring's response
for _, cookie := range resp.Cookies() {
    if cookie.Name == "JSESSIONID" {
        oauthCtx.SpringSessionID = cookie.Value
    }
}

// Forward to Spring on subsequent requests
if oauthCtx.SpringSessionID != "" {
    req.AddCookie(&http.Cookie{
        Name:  "JSESSIONID",
        Value: oauthCtx.SpringSessionID,
    })
}
```

#### 3. OAuth Context Expired

**Symptom:** Error "OAuth context has expired" after 10 minutes.

**Cause:** User took too long to complete login/consent flow.

**Solution:** The `CookieMaxAge` is set to 600 seconds (10 minutes). If users need more time, increase this value in `oauth_context.go`.

#### 4. CSRF Token Mismatch

**Symptom:** Form submission fails with CSRF error.

**Cause:** CSRF token not included in form or doesn't match.

**Solution:** Ensure all forms include the CSRF token:

```html
<input type="hidden" name="csrf_token" value="{{ .CSRFToken }}" />
```

---

## Environment Variables

| Variable                       | Description                            | Default                 |
| ------------------------------ | -------------------------------------- | ----------------------- |
| `BFF_BASE_URL`                 | Base URL of BFF server                 | `http://localhost:8088` |
| `OAUTH_CONTEXT_ENCRYPTION_KEY` | 32-byte key for cookie encryption      | Development default     |
| `ENVIRONMENT`                  | Set to `production` for secure cookies | -                       |
| `OAUTH2_BASE_URL`              | Spring Authorization Server URL        | From endpoints config   |

---

## See Also

- [CSRF_PROTECTION.md](./CSRF_PROTECTION.md) - CSRF protection implementation
- [OAUTH_PROXY_FLOW.md](./OAUTH_PROXY_FLOW.md) - OAuth proxy details
- [ENDPOINTS_USAGE.md](./ENDPOINTS_USAGE.md) - Endpoint configuration
