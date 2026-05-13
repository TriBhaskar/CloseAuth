# OAuth2 Proxy Flow Documentation

## Overview

This Backend-for-Frontend (BFF) application acts as a transparent proxy for OAuth2/OIDC endpoints, providing a custom authentication experience while maintaining security with the Spring Authorization Server.

## Architecture

```
┌─────────────┐         ┌──────────────┐         ┌─────────────────────────┐
│   External  │         │     BFF      │         │   Spring Authorization  │
│   Client    │◄───────►│   (Go App)   │◄───────►│   Server (Java/Spring)  │
│   (Admin    │         │  Port 8088   │         │      Port 9088          │
│   Dashboard)│         └──────────────┘         └─────────────────────────┘
└─────────────┘               │
                              │
                              ▼
                        ┌──────────────┐
                        │   Browser    │
                        │  (End User)  │
                        └──────────────┘
```

## Components

### 1. **OAuth Proxy Handler** (`internal/handlers/oauth_proxy.go`)

- Proxies `/closeauth/oauth2/authorize` requests
- Proxies `/closeauth/oauth2/token` requests
- Intercepts login redirects
- Manages OAuth context cookies

### 2. **Auth Handler** (`internal/handlers/auth.go`)

- Handles custom login page rendering
- Processes login form submissions
- Forwards authentication to Spring
- Manages session cookies (JSESSIONID)

### 3. **OAuth Context Middleware** (`internal/middleware/oauth_context.go`)

- Encrypts/decrypts OAuth parameters
- Stores context in secure cookies
- Validates context expiration

## Complete OAuth Flow

### Scenario 1: OAuth Flow from External Client

This is the standard OAuth2 Authorization Code flow with the BFF intercepting authentication.

#### Step 1: Authorization Request

```
User Action: External client initiates OAuth flow
URL: http://localhost:8088/closeauth/oauth2/authorize?
       response_type=code
       &client_id=admin-client
       &redirect_uri=http://localhost:8088/admin/dashboard
       &scope=client.create
```

**What Happens:**

1. ✅ Request hits BFF at `/closeauth/oauth2/authorize`
2. ✅ BFF validates required OAuth parameters (response_type, client_id, redirect_uri)
3. ✅ BFF proxies request to Spring at `localhost:9088/closeauth/oauth2/authorize`
4. ✅ Spring checks for authentication - finds none
5. ✅ Spring returns 302 redirect to `http://localhost:8088/auth/login`

**BFF Actions:** 6. ✅ BFF intercepts redirect (detects `/auth/login` path) 7. ✅ BFF creates `OAuthContext` object with all OAuth parameters 8. ✅ BFF encrypts context using AES-256-GCM 9. ✅ BFF saves encrypted context in `oauth_context` cookie (10 min expiry) 10. ✅ BFF redirects browser to `/auth/login?continue=true`

**Key Code:**

```go
// oauth_proxy.go - handleAuthorizeRedirect()
if h.isLoginRedirect(parsedLocation.Path) {
    log.Printf("INFO: User not authenticated, initiating BFF login flow")
    h.handleUnauthenticatedUser(w, r, params)
    return
}
```

#### Step 2: User Login

```
User Action: User sees custom BFF login page and enters credentials
URL: http://localhost:8088/auth/login?continue=true
```

**What Happens:**

1. ✅ BFF renders custom login page (Templ template)
2. ✅ User enters username and password
3. ✅ HTMX submits POST to `/auth/login`
4. ✅ BFF forwards credentials to Spring at `localhost:9088/closeauth/login`
5. ✅ Spring validates credentials
6. ✅ Spring creates session and returns JSESSIONID cookie
7. ✅ Spring returns 302 redirect to `/closeauth/` (default success URL)

**BFF Actions:** 8. ✅ BFF receives JSESSIONID cookie from Spring 9. ✅ BFF forwards JSESSIONID cookie to browser 10. ✅ BFF reads `oauth_context` cookie from request 11. ✅ BFF decrypts OAuth context 12. ✅ BFF rebuilds authorize URL with saved parameters 13. ✅ BFF redirects to `/closeauth/oauth2/authorize?[saved parameters]`

**Key Code:**

```go
// auth.go - HandleLoginPost()
oauthCtx, err := middleware.GetOAuthContext(r)
if err == nil && oauthCtx != nil {
    // OAuth flow - redirect back to authorize endpoint
    middleware.ClearOAuthContext(w)
    finalRedirect = middleware.BuildAuthorizeURL(oauthCtx, "http://localhost:8088")
} else {
    // Direct login - go to dashboard
    finalRedirect = "/admin/dashboard"
}
```

#### Step 3: Authorization Code Generation

```
User Action: Automatic (browser follows redirect)
URL: http://localhost:8088/closeauth/oauth2/authorize?[parameters]
```

**What Happens:**

1. ✅ Request hits BFF with JSESSIONID cookie
2. ✅ BFF proxies request to Spring **with JSESSIONID**
3. ✅ Spring validates session - user is authenticated
4. ✅ Spring validates client_id and redirect_uri
5. ✅ Spring generates authorization code
6. ✅ Spring returns 302 redirect to `redirect_uri?code=XXXXXX`

**BFF Actions:** 7. ✅ BFF forwards redirect to browser 8. ✅ Browser lands on `http://localhost:8088/admin/dashboard?code=XXXXXX`

**Success!** The client now has an authorization code that can be exchanged for an access token.

### Scenario 2: Direct BFF Login

This is a simple login without OAuth flow (user goes directly to login page).

#### Flow:

1. ✅ User navigates to `http://localhost:8088/auth/login`
2. ✅ User enters credentials and submits
3. ✅ BFF authenticates with Spring
4. ✅ BFF receives JSESSIONID
5. ✅ BFF checks for OAuth context - **none found**
6. ✅ BFF redirects to `/admin/dashboard` (default landing page)

## Security Features

### 1. **Encrypted OAuth Context**

- OAuth parameters are encrypted using AES-256-GCM
- Encryption key from environment variable `OAUTH_CONTEXT_ENCRYPTION_KEY`
- Cookie is HttpOnly, preventing JavaScript access
- 10-minute expiration prevents replay attacks

### 2. **CSRF Protection**

- All forms include CSRF tokens
- CSRF middleware validates tokens on POST requests
- HTMX automatically includes CSRF token in headers

### 3. **Session Security**

- JSESSIONID cookie is HttpOnly
- Secure flag enabled in production (HTTPS)
- SameSite=Lax prevents CSRF attacks
- Sessions managed by Spring (server-side)

### 4. **Cookie Security Settings**

```go
cookie := &http.Cookie{
    Name:     "oauth_context",
    Value:    encryptedData,
    Path:     "/",
    MaxAge:   600, // 10 minutes
    HttpOnly: true,
    Secure:   isProduction(),
    SameSite: http.SameSiteLaxMode,
}
```

## Configuration

### Environment Variables

| Variable                       | Description                              | Default                          |
| ------------------------------ | ---------------------------------------- | -------------------------------- |
| `OAUTH2_SERVER_URL`            | Spring Authorization Server URL          | `http://localhost:9088`          |
| `BFF_BASE_URL`                 | BFF server URL                           | `http://localhost:8088`          |
| `OAUTH_CONTEXT_ENCRYPTION_KEY` | 32-byte encryption key                   | `default-32-byte-key-change-me!` |
| `ENVIRONMENT`                  | Environment (`production`/`development`) | `development`                    |

### Production Checklist

- [ ] Set `OAUTH_CONTEXT_ENCRYPTION_KEY` to a secure random 32-byte key
- [ ] Set `ENVIRONMENT=production`
- [ ] Enable HTTPS (Secure cookies automatically enabled)
- [ ] Configure proper CORS settings
- [ ] Set appropriate cookie domains
- [ ] Enable rate limiting on login endpoint
- [ ] Configure session timeout in Spring
- [ ] Enable production logging (remove debug logs)

## Key Files

```
internal/
├── handlers/
│   ├── oauth_proxy.go    # OAuth2 endpoint proxy
│   └── auth.go           # Authentication handlers
├── middleware/
│   ├── oauth_context.go  # OAuth context encryption/storage
│   ├── csrf.go           # CSRF protection
│   └── htmx.go           # HTMX utilities
├── routes.go             # Route definitions
└── server.go             # Server initialization

templates/
└── layouts/
    └── login.templ       # Custom login page
```

## Testing

### Test OAuth Flow

```bash
# 1. Start BFF (port 8088)
make watch

# 2. Initiate OAuth flow (in browser)
http://localhost:8088/closeauth/oauth2/authorize?response_type=code&client_id=admin-client&redirect_uri=http://localhost:8088/admin/dashboard&scope=client.create

# 3. Login with credentials
# username: user
# password: password

# 4. Verify authorization code in URL
# Expected: http://localhost:8088/admin/dashboard?code=XXXXXX
```

### Test Direct Login

```bash
# 1. Navigate to login
http://localhost:8088/auth/login

# 2. Login with credentials
# Expected redirect: http://localhost:8088/admin/dashboard
# No code in URL
```

## Troubleshooting

### No authorization code in URL

- ✅ Verify you're starting from `/closeauth/oauth2/authorize` endpoint
- ✅ Check that Spring Authorization Server is running on port 9088
- ✅ Verify client_id is registered in Spring
- ✅ Check BFF logs for OAuth context save/retrieve messages

### CSRF token errors

- ✅ Ensure forms include `csrf_token` hidden input
- ✅ Check CSRF middleware is enabled in routes
- ✅ Verify HTMX includes `X-CSRF-Token` header

### Session not persisting

- ✅ Verify JSESSIONID cookie is being set
- ✅ Check cookie path and domain settings
- ✅ Ensure Spring session is properly configured

## Future Enhancements

1. **Token Exchange Endpoint**: Add `/closeauth/oauth2/token` proxy for access token exchange
2. **Token Introspection**: Validate access tokens from Spring
3. **Refresh Tokens**: Support refresh token flow
4. **Multi-Factor Authentication**: Add OTP/2FA support
5. **Remember Me**: Implement persistent sessions
6. **Rate Limiting**: Protect against brute force attacks
7. **Audit Logging**: Track authentication events
8. **Session Management UI**: Allow users to view/revoke sessions

## References

- OAuth 2.0 RFC: https://datatracker.ietf.org/doc/html/rfc6749
- Backend-for-Frontend Pattern: https://samnewman.io/patterns/architectural/bff/
- Spring Authorization Server: https://spring.io/projects/spring-authorization-server
