# Session, Security, and Cookie Strategy

**Focus**: HTTP-only cookies, CSRF protection, service-to-service security, token management  
**Date**: May 1, 2026

---

## Architecture Overview

```
┌──────────────────┐
│  Vue Frontend    │
│  (Port 5173)     │
└────────┬─────────┘
         │ (Axios + credentials)
         │
┌────────▼──────────────────────────────┐
│   Go Backend (BFF Layer)               │
│   (Port 5000)                          │
│   - Session management                 │
│   - Token generation/refresh           │
│   - Set HTTP-only cookies              │
│   - OAuth context encryption           │
│   - CSRF token generation              │
└────────┬──────────────────────────────┘
         │ (Spring client credentials)
         │
┌────────▼──────────────────────────────┐
│  Spring Authorization Server           │
│  (Port 9088)                           │
│  - User authentication                 │
│  - Token issuance                      │
│  - OAuth2/OIDC provider                │
└──────────────────────────────────────┘
         │
    PostgreSQL
    (Admin + Theme data)
```

---

## Session Management Strategy

### Overview

The migration from Go server-rendered to Vue SPA requires a paradigm shift in session management:

**Go BFF (Old)**:
- Server-side session storage
- Session data: user ID, email, tokens
- Cookie: `bff_session` (encrypted with AES-256-GCM)
- Automatic session validation on every request

**Vue SPA (New)**:
- Token-based authentication (JWT or opaque tokens)
- Tokens stored on backend, not in browser
- HTTP-only cookies contain session ID only
- Frontend stores tokens in Pinia, sends in Authorization headers

### HTTP-Only Cookie Strategy

**What are HTTP-only cookies?**

HTTP-only cookies are set by the backend and:
- Are stored in the browser's cookie jar
- Are automatically sent with every request to the domain
- **Cannot be accessed by JavaScript** (protects against XSS)
- Can be read/written only by HTTP servers

**Why use them?**

1. **XSS Protection**: Even if attacker injects malicious JS, can't steal cookies
2. **Automatic Transmission**: No need to manually add to requests
3. **Browser Native Security**: Enforced by browser sandbox

### Implementation

#### Backend: Set HTTP-Only Cookies

**Go backend** (Port 5000) should set cookies in login response:

```go
// After successful login with Spring Auth Server
accessToken := "eyJhbGc..." // Token from Spring

// Set HTTP-only cookie with access token (or session ID)
http.SetCookie(w, &http.Cookie{
    Name:     "auth_token",
    Value:    accessToken,
    HttpOnly: true,
    Secure:   isProduction,    // Only over HTTPS in production
    SameSite: http.SameSiteLax,
    MaxAge:   3600,            // 1 hour
    Path:     "/",
    Domain:   "",              // Current domain only
})

// Also set CSRF token (can be read by JS for form headers)
http.SetCookie(w, &http.Cookie{
    Name:     "csrf_token",
    Value:    csrfToken,
    HttpOnly: false,           // JS can read this
    Secure:   isProduction,
    SameSite: http.SameSiteLax,
    MaxAge:   0,               // Browser session
    Path:     "/",
})
```

**Go Handler Example**:
```go
func (h *AuthHandler) HandleLogin(w http.ResponseWriter, r *http.Request) {
    email := r.FormValue("email")
    password := r.FormValue("password")
    
    // Authenticate with Spring
    springResp, err := h.authenticateWithSpring(email, password)
    if err != nil {
        http.Error(w, "invalid credentials", http.StatusUnauthorized)
        return
    }
    
    // Extract tokens from Spring response
    accessToken := springResp.AccessToken
    refreshToken := springResp.RefreshToken
    
    // Set HTTP-only cookies
    http.SetCookie(w, &http.Cookie{
        Name:     "access_token",
        Value:    accessToken,
        HttpOnly: true,
        Secure:   os.Getenv("ENVIRONMENT") == "production",
        SameSite: http.SameSiteLax,
        MaxAge:   3600,
        Path:     "/",
    })
    
    http.SetCookie(w, &http.Cookie{
        Name:     "refresh_token",
        Value:    refreshToken,
        HttpOnly: true,
        Secure:   os.Getenv("ENVIRONMENT") == "production",
        SameSite: http.SameSiteLax,
        MaxAge:   86400 * 7,  // 7 days
        Path:     "/",
    })
    
    // Return JSON response (no sensitive data)
    json.NewEncoder(w).Encode(map[string]interface{}{
        "success": true,
        "user": map[string]string{
            "id":    springResp.UserID,
            "email": springResp.Email,
        },
    })
}
```

#### Frontend: Use Cookies Automatically

**Axios Configuration** (`api/client.ts`):

```typescript
import axios from 'axios'

const apiClient = axios.create({
    baseURL: import.meta.env.VITE_API_URL || 'http://localhost:5000',
    withCredentials: true,  // ✅ CRITICAL: Send cookies with every request
    timeout: 10000
})

// Cookies are sent automatically by the browser
// No need to manually add "Authorization" header with token
```

**How it works**:

1. User logs in → Browser receives `access_token` cookie (HTTP-only)
2. Browser stores cookie in cookie jar
3. Every API request automatically includes the cookie
4. Backend reads cookie from request
5. Backend uses token to authorize request

**Vue Component Example**:

```typescript
// No need to store access_token in JavaScript
// Just call API normally
const login = async (email: string, password: string) => {
    try {
        const response = await apiClient.post('/api/auth/login', {
            email,
            password
        })
        
        // Response contains user info, NOT the token
        const user = response.data.user
        
        // Token is already in HTTP-only cookie (set by backend)
        // Will be sent automatically on next request
        
        authStore.setUser(user)
        router.push('/admin/dashboard')
    } catch (error) {
        console.error('Login failed')
    }
}
```

---

## Token Refresh Strategy

### Problem

Access tokens expire (typically 1 hour). When expired, API returns 401.

### Solution: Automatic Token Refresh

**Backend provides**: 
- Short-lived access token (1 hour)
- Long-lived refresh token (7 days, HTTP-only cookie)

**Frontend behavior**:
- On 401: Automatically call `POST /api/auth/refresh`
- Backend uses refresh token from cookie to get new access token
- Set new access token in cookie
- Retry original request

### Implementation

**Go Backend** (`auth.ts` handler):

```go
func (h *AuthHandler) HandleRefreshToken(w http.ResponseWriter, r *http.Request) {
    // Read refresh token from HTTP-only cookie
    refreshCookie, err := r.Cookie("refresh_token")
    if err != nil {
        http.Error(w, "no refresh token", http.StatusUnauthorized)
        return
    }
    
    // Request new access token from Spring using refresh token
    springResp, err := h.refreshTokenWithSpring(refreshCookie.Value)
    if err != nil {
        http.Error(w, "refresh failed", http.StatusUnauthorized)
        return
    }
    
    // Set new access token cookie
    http.SetCookie(w, &http.Cookie{
        Name:     "access_token",
        Value:    springResp.AccessToken,
        HttpOnly: true,
        Secure:   isProduction,
        SameSite: http.SameSiteLax,
        MaxAge:   3600,
        Path:     "/",
    })
    
    // Return success
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(map[string]bool{"success": true})
}
```

**Vue Frontend** (`api/client.ts`):

```typescript
const apiClient = axios.create({
    baseURL: import.meta.env.VITE_API_URL,
    withCredentials: true
})

// Response interceptor: Handle 401 → refresh token → retry
apiClient.interceptors.response.use(
    response => response,
    async error => {
        const originalRequest = error.config
        
        // If 401 and haven't already retried
        if (error.response?.status === 401 && !originalRequest._retry) {
            originalRequest._retry = true
            
            try {
                // Call refresh endpoint
                // New access token will be set in cookie by backend
                await apiClient.post('/api/auth/refresh')
                
                // Retry original request (cookie now has fresh token)
                return apiClient(originalRequest)
            } catch (refreshError) {
                // Refresh failed, logout user
                window.location.href = '/admin/login'
                return Promise.reject(refreshError)
            }
        }
        
        return Promise.reject(error)
    }
)

export default apiClient
```

---

## CSRF Protection Strategy

### Problem

CSRF (Cross-Site Request Forgery): Attacker tricks user into making unauthorized requests from malicious site.

### Solution: CSRF Tokens

**Mechanism**:
1. Backend generates random token
2. Sends in Set-Cookie header (can be read by JS)
3. Frontend reads token from cookie
4. Frontend includes token in request header with every POST/PUT/DELETE
5. Backend validates token matches

### Implementation

**Backend: Generate CSRF Token** (`middleware/csrf.go`):

```go
func CSRFTokenMiddleware(config *CSRFConfig) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            // Generate token on every request
            token := generateSecureToken(32)  // 32 random bytes
            
            // Set cookie (not HTTP-only, JS needs to read it)
            http.SetCookie(w, &http.Cookie{
                Name:     "csrf_token",
                Value:    token,
                HttpOnly: false,           // JS must read this
                Secure:   isProduction,
                SameSite: http.SameSiteLax,
                MaxAge:   0,               // Browser session
                Path:     "/",
            })
            
            // Also pass to template/response for JS to access
            r.Header.Set("X-CSRF-Token", token)
            
            next.ServeHTTP(w, r)
        })
    }
}
```

**Backend: Validate CSRF Token** (`middleware/csrf.go`):

```go
func CSRFMiddleware(config *CSRFConfig) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            // Skip for GET, HEAD, OPTIONS
            if r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions {
                next.ServeHTTP(w, r)
                return
            }
            
            // Get token from request (priority: header → form field)
            tokenFromHeader := r.Header.Get("X-CSRF-Token")
            tokenFromForm := r.FormValue("csrf_token")
            tokenFromRequest := tokenFromHeader
            if tokenFromRequest == "" {
                tokenFromRequest = tokenFromForm
            }
            
            // Get token from cookie
            tokenCookie, err := r.Cookie("csrf_token")
            if err != nil {
                http.Error(w, "CSRF token missing", http.StatusForbidden)
                return
            }
            
            // Validate using constant-time comparison (prevents timing attacks)
            if subtle.ConstantTimeCompare(
                []byte(tokenFromRequest),
                []byte(tokenCookie.Value),
            ) != 1 {
                http.Error(w, "CSRF token invalid", http.StatusForbidden)
                return
            }
            
            next.ServeHTTP(w, r)
        })
    }
}
```

**Frontend: Include CSRF Token** (`api/client.ts`):

```typescript
// Request interceptor: Add CSRF token to headers
apiClient.interceptors.request.use(config => {
    // Get token from cookie
    const csrfToken = getCookie('csrf_token')
    
    if (csrfToken && ['post', 'put', 'delete', 'patch'].includes(config.method?.toLowerCase())) {
        config.headers['X-CSRF-Token'] = csrfToken
    }
    
    return config
})

// Helper function to read cookie
function getCookie(name: string): string | null {
    const value = `; ${document.cookie}`
    const parts = value.split(`; ${name}=`)
    if (parts.length === 2) return parts.pop()?.split(';').shift() || null
    return null
}
```

---

## OAuth Context Encryption

### Problem

OAuth flow requires temporary storage of:
- `client_id`, `redirect_uri`, `scope`, `state` (user's OAuth request parameters)
- `JSESSIONID` (Spring session token)

These must be protected from tampering by users.

### Solution: AES-256-GCM Encryption

**Implementation** (`Go Backend`):

```go
func encryptOAuthContext(plaintext []byte, key []byte) ([]byte, error) {
    block, err := aes.NewCipher(key)
    if err != nil {
        return nil, err
    }
    
    // Create AES-GCM cipher
    gcm, err := cipher.NewGCM(block)
    if err != nil {
        return nil, err
    }
    
    // Generate random nonce
    nonce := make([]byte, gcm.NonceSize())
    if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
        return nil, err
    }
    
    // Encrypt and authenticate
    // Result = nonce + ciphertext (GCM includes authentication tag)
    return gcm.Seal(nonce, nonce, plaintext, nil), nil
}

func decryptOAuthContext(ciphertext []byte, key []byte) ([]byte, error) {
    block, err := aes.NewCipher(key)
    if err != nil {
        return nil, err
    }
    
    gcm, err := cipher.NewGCM(block)
    if err != nil {
        return nil, err
    }
    
    // Extract nonce from ciphertext
    nonceSize := gcm.NonceSize()
    if len(ciphertext) < nonceSize {
        return nil, errors.New("ciphertext too short")
    }
    
    nonce, ciphertext := ciphertext[:nonceSize], ciphertext[nonceSize:]
    
    // Decrypt and verify authentication tag
    plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
    if err != nil {
        return nil, err
    }
    
    return plaintext, nil
}

// Usage: Save OAuth context to encrypted cookie
func SaveOAuthContext(w http.ResponseWriter, ctx *OAuthContext) error {
    // 1. Marshal to JSON
    jsonData, err := json.Marshal(ctx)
    if err != nil {
        return err
    }
    
    // 2. Encrypt with AES-256-GCM
    key := os.Getenv("OAUTH_CONTEXT_ENCRYPTION_KEY")  // 32 bytes
    encrypted, err := encryptOAuthContext(jsonData, []byte(key))
    if err != nil {
        return err
    }
    
    // 3. Encode to base64 (for safe cookie storage)
    encoded := base64.URLEncoding.EncodeToString(encrypted)
    
    // 4. Set HTTP-only cookie
    http.SetCookie(w, &http.Cookie{
        Name:     "oauth_context",
        Value:    encoded,
        HttpOnly: true,
        Secure:   isProduction,
        SameSite: http.SameSiteLax,
        MaxAge:   600,  // 10 minutes
        Path:     "/",
    })
    
    return nil
}
```

### Why AES-256-GCM?

- **AES-256**: 256-bit encryption key (brute-force resistant)
- **GCM**: Galois/Counter Mode provides:
  - Encryption (confidentiality)
  - Authentication tag (integrity + authenticity)
  - Prevents tampering and replay attacks

### Environment Configuration

```bash
# Generate 32-byte key (256 bits) for AES-256
# Linux/Mac:
openssl rand -base64 32

# Windows PowerShell:
[Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Maximum 256) }))

# Export to .env
export OAUTH_CONTEXT_ENCRYPTION_KEY="your-base64-encoded-32-byte-key"
```

---

## Service-to-Service Authentication

### Problem

Go backend needs to call Spring Auth Server APIs (e.g., `/oauth2/client-info`) without user context.

### Solution: OAuth2 Client Credentials Flow

**Implementation** (`Go Backend`):

```go
type OAuthClientCredentialsManager struct {
    clientID     string
    clientSecret string
    tokenURL     string
    mu            sync.RWMutex
    tokenCache    string
    tokenExpiry   time.Time
}

func (m *OAuthClientCredentialsManager) GetAccessToken() (string, error) {
    m.mu.RLock()
    if time.Now().Before(m.tokenExpiry.Add(-30 * time.Second)) && m.tokenCache != "" {
        defer m.mu.RUnlock()
        return m.tokenCache, nil
    }
    m.mu.RUnlock()
    
    // Request new token from Spring
    data := url.Values{}
    data.Set("grant_type", "client_credentials")
    data.Set("client_id", m.clientID)
    data.Set("client_secret", m.clientSecret)
    
    resp, err := http.PostForm(m.tokenURL, data)
    if err != nil {
        return "", err
    }
    defer resp.Body.Close()
    
    var result struct {
        AccessToken string `json:"access_token"`
        ExpiresIn   int    `json:"expires_in"`
    }
    json.NewDecoder(resp.Body).Decode(&result)
    
    // Cache token
    m.mu.Lock()
    defer m.mu.Unlock()
    m.tokenCache = result.AccessToken
    m.tokenExpiry = time.Now().Add(time.Duration(result.ExpiresIn) * time.Second)
    
    return result.AccessToken, nil
}

// Usage: Call Spring API with Bearer token
func (h *Handler) GetClientInfo(clientID string) (*ClientInfo, error) {
    token, err := h.tokenManager.GetAccessToken()
    if err != nil {
        return nil, err
    }
    
    req, _ := http.NewRequest("GET", fmt.Sprintf("http://spring:9088/api/clients/%s", clientID), nil)
    req.Header.Set("Authorization", fmt.Sprintf("Bearer %s", token))
    
    resp, err := http.DefaultClient.Do(req)
    if err != nil {
        return nil, err
    }
    defer resp.Body.Close()
    
    var clientInfo ClientInfo
    json.NewDecoder(resp.Body).Decode(&clientInfo)
    return &clientInfo, nil
}
```

---

## Cookie Security Checklist

| Setting | Value | Reason |
|---------|-------|--------|
| `HttpOnly` | true | Prevent JS access (XSS protection) |
| `Secure` | true (prod only) | Only over HTTPS |
| `SameSite` | Lax | Prevent CSRF (allow same-site requests) |
| `Domain` | Not set | Current domain only (no subdomain sharing) |
| `Path` | "/" | All paths can access |
| `Max-Age` | 3600 (access) | Short-lived for access token |
| Max-Age | 604800 (refresh) | Longer-lived for refresh token |

---

## Security Best Practices

### 1. **Never Store Secrets in Frontend**

❌ **WRONG**:
```typescript
const accessToken = "eyJhbGc..."  // In source code or state
```

✅ **RIGHT**:
```typescript
// Token only in HTTP-only cookie (set by backend)
// Frontend never sees it
```

### 2. **Validate All Inputs**

❌ **WRONG**:
```go
username := r.FormValue("username")
// Use directly in query
```

✅ **RIGHT**:
```go
username := r.FormValue("username")
if err := validateEmail(username); err != nil {
    http.Error(w, "invalid email", http.StatusBadRequest)
    return
}
```

### 3. **Use HTTPS in Production**

❌ **WRONG**:
```go
Secure: false  // HTTP in production
```

✅ **RIGHT**:
```go
Secure: os.Getenv("ENVIRONMENT") == "production"
```

### 4. **Regenerate CSRF Tokens**

CSRF tokens should be regenerated on:
- Login
- Logout
- Permission elevation (e.g., password change)

## 5. **Implement Rate Limiting**

Prevent brute-force attacks on login:

```go
type RateLimiter struct {
    attempts map[string][]time.Time
    mu       sync.Mutex
}

func (rl *RateLimiter) IsBlocked(email string) bool {
    rl.mu.Lock()
    defer rl.mu.Unlock()
    
    now := time.Now()
    attempts := rl.attempts[email]
    
    // Remove old attempts (older than 15 minutes)
    var recent []time.Time
    for _, t := range attempts {
        if now.Sub(t) < 15*time.Minute {
            recent = append(recent, t)
        }
    }
    
    // Block if 5+ attempts in 15 minutes
    return len(recent) >= 5
}
```

### 6. **Log Security Events**

```go
logger.Info("login_attempt",
    "email", email,
    "success", success,
    "ip", r.RemoteAddr,
    "user_agent", r.UserAgent(),
)
```

### 7. **Implement CORS Carefully**

```go
cors.Handler(cors.Options{
    AllowedOrigins: []string{
        os.Getenv("FRONTEND_URL"),  // Only trusted origins
    },
    AllowedMethods: []string{"GET", "POST", "PUT", "DELETE"},
    AllowedHeaders: []string{"Content-Type", "X-CSRF-Token"},
    AllowCredentials: true,  // Allow cookies
})
```

---

## Environment Configuration

```bash
# .env (Go Backend)
ENVIRONMENT=production
OAUTH_CONTEXT_ENCRYPTION_KEY="base64-encoded-32-byte-key"
OAUTH2_SERVER_URL=https://auth.prod.com
FRONTEND_URL=https://admin.prod.com
SESSION_TIMEOUT=3600
REFRESH_TOKEN_EXPIRY=604800

# .env (Vue Frontend)
VITE_API_URL=https://api.prod.com
VITE_LOG_LEVEL=info
```

---

## Testing Security

### Unit Tests

```typescript
describe('CSRF Protection', () => {
  it('rejects requests without CSRF token', async () => {
    const response = await axios.post('/api/auth/login', {
      email: 'user@example.com',
      password: 'password'
    }, {
      headers: {
        'X-CSRF-Token': ''  // Missing or invalid
      }
    })
    
    expect(response.status).toBe(403)
  })
})
```

### Manual Tests

- [ ] Login via browser, check cookies with DevTools
- [ ] Verify `access_token` is HTTP-only (not accessible from console)
- [ ] Verify `csrf_token` is readable from JS
- [ ] Test token refresh after expiration
- [ ] Test OAuth context encryption/decryption

---

## Monitoring & Alerts

```go
// Log failed login attempts
if !isPasswordCorrect {
    logger.Warn("failed_login",
        "email", email,
        "ip", r.RemoteAddr,
        "reason", "invalid_password",
    )
    
    // Alert if 10+ failed attempts in 1 hour
    if h.rateLimiter.GetAttempts(email, 1*time.Hour) >= 10 {
        sendSecurityAlert("Brute force attempt detected", email)
    }
}

// Log unauthorized access
if !hasAuth {
    logger.Warn("unauthorized_access",
        "route", r.URL.Path,
        "ip", r.RemoteAddr,
    )
}
```

---

**Related Guides**:
- `FRONTEND_IMPLEMENTATION_SPEC.md` - API client configuration
- `STEP_BY_STEP_MIGRATION.md` - Phase 2 (Auth implementation)
- `IMPROVEMENTS_AND_BEST_PRACTICES.md` - Performance & hardening


