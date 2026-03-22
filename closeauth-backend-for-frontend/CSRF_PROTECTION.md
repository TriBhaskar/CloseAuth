# CSRF Protection Implementation Guide

## Overview

Cross-Site Request Forgery (CSRF) protection has been implemented in your Go application using custom middleware that generates and validates tokens for all state-changing HTTP requests.

## How It Works

### 1. Token Generation

- CSRF tokens are generated using cryptographically secure random bytes
- Tokens are base64-URL encoded for safe transmission
- Default token length is 32 bytes (providing 256 bits of entropy)

### 2. Token Storage

- Tokens are stored in HTTP-only cookies with the name `csrf_token`
- Cookie settings can be configured for security (Secure, SameSite, etc.)
- Tokens expire after 24 hours by default

### 3. Token Validation

- For GET, HEAD, OPTIONS requests: No validation required (safe methods)
- For POST, PUT, PATCH, DELETE requests: Token validation is mandatory
- Tokens can be provided via:
  - HTTP header: `X-CSRF-Token`
  - Form field: `csrf_token`
- Validation uses constant-time comparison to prevent timing attacks

## Implementation Details

### Middleware Components

1. **CSRFMiddleware**: Main protection middleware
2. **CSRFTokenMiddleware**: Adds tokens to request context
3. **HTMX Support**: Automatic token injection for HTMX requests

### Configuration

```go
type CSRFConfig struct {
    TokenLength  int           // Token length in bytes (default: 32)
    CookieName   string        // Cookie name (default: "csrf_token")
    HeaderName   string        // Header name (default: "X-CSRF-Token")
    FormField    string        // Form field name (default: "csrf_token")
    CookiePath   string        // Cookie path (default: "/")
    CookieDomain string        // Cookie domain
    Secure       bool          // HTTPS only (set to true in production)
    HttpOnly     bool          // Prevent XSS access (default: true)
    SameSite     http.SameSite // SameSite policy (default: Lax)
}
```

### Route Setup

```go
// Apply CSRF protection to all routes
csrfConfig := middleware.DefaultCSRFConfig()
r.Use(middleware.CSRFTokenMiddleware(csrfConfig))
r.Use(middleware.CSRFMiddleware(csrfConfig))
```

### Template Integration

#### For Regular Forms

```html
<form method="POST" action="/login">
  <input type="hidden" name="csrf_token" value="{{ .CSRFToken }}" />
  <!-- other form fields -->
</form>
```

#### For HTMX Requests

The JavaScript automatically handles CSRF tokens for HTMX requests by:

1. Reading the token from the meta tag or existing form fields
2. Adding the `X-CSRF-Token` header to all HTMX requests

## Security Features

### 1. Secure Token Generation

- Uses `crypto/rand` for cryptographically secure random number generation
- Base64-URL encoding ensures safe transmission in URLs and forms

### 2. Constant-Time Comparison

- Prevents timing attacks by using `subtle.ConstantTimeCompare`

### 3. Automatic Token Rotation

- Tokens are regenerated on each GET request if none exists
- 24-hour expiration prevents long-term token reuse

### 4. HTTP-Only Cookies

- Prevents XSS attacks from accessing CSRF tokens
- SameSite policy provides additional CSRF protection

## Error Handling

### Validation Failures

- Returns 403 Forbidden for invalid/missing tokens
- Different handling for HTMX vs regular requests
- Descriptive error messages for debugging

### Common Error Scenarios

1. **Token Missing**: No CSRF token provided in request
2. **Token Mismatch**: Provided token doesn't match expected value
3. **Token Expired**: Cookie has expired (handled automatically)

## Production Considerations

### 1. HTTPS Configuration

```go
csrfConfig := middleware.DefaultCSRFConfig()
csrfConfig.Secure = true  // Enable for HTTPS-only cookies
```

### 2. Domain Configuration

```go
csrfConfig.CookieDomain = ".yourdomain.com"  // For subdomain support
```

### 3. SameSite Policy

```go
csrfConfig.SameSite = http.SameSiteStrictMode  // Strictest protection
```

## Testing CSRF Protection

### 1. Valid Request Test

```bash
# Get CSRF token first
curl -c cookies.txt http://localhost:8080/auth/login

# Extract token from page/cookies and use in POST
curl -b cookies.txt -X POST \
  -H "X-CSRF-Token: YOUR_TOKEN_HERE" \
  -d "email=test@example.com&password=password" \
  http://localhost:8080/login
```

### 2. Invalid Request Test

```bash
# This should fail with 403 Forbidden
curl -X POST \
  -d "email=test@example.com&password=password" \
  http://localhost:8080/login
```

## HTMX Integration

The CSRF JavaScript automatically:

1. Extracts tokens from meta tags or existing form fields
2. Adds `X-CSRF-Token` header to all HTMX requests
3. Handles token rotation transparently

### Manual HTMX Configuration

```javascript
// Custom HTMX event handler
document.body.addEventListener("htmx:configRequest", function (evt) {
  let csrfToken = getCSRFToken();
  if (csrfToken) {
    evt.detail.headers["X-CSRF-Token"] = csrfToken;
  }
});
```

## Best Practices

1. **Always validate on state-changing operations**
2. **Use HTTPS in production** with Secure cookies
3. **Set appropriate SameSite policies**
4. **Monitor for CSRF failures** in logs
5. **Test with real browsers** to ensure cookie handling works
6. **Consider CORS settings** - ensure X-CSRF-Token header is allowed

## Troubleshooting

### Common Issues

1. **Cookies not set**: Check domain/path configuration
2. **Tokens not matching**: Verify token extraction logic
3. **CORS errors**: Ensure X-CSRF-Token is in allowed headers
4. **Time sync issues**: Check server time if using time-based tokens

### Debug Mode

Add logging to middleware to trace token generation and validation:

```go
log.Printf("CSRF: Generated token for %s", r.URL.Path)
log.Printf("CSRF: Validating token for %s: expected=%s, actual=%s",
    r.URL.Path, expectedToken, actualToken)
```
