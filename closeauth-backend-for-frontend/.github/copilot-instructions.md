# CloseAuth Backend-for-Frontend (BFF) - AI Coding Guide

## Architecture Overview

This is a **Go web application** serving as a Backend-for-Frontend (BFF) for CloseAuth OAuth2/OIDC authorization server. It uses:

- **Web Framework**: Go-Chi router with custom middleware stack
- **Template Engine**: Templ (compile-time HTML generation)
- **Frontend**: HTMX + Tailwind CSS (progressive enhancement approach)
- **Security**: Custom CSRF protection + HTMX integration

Key architectural pattern: **Progressive Enhancement** - all forms work without JavaScript, HTMX adds dynamic behavior.

## Critical Development Workflows

### Build & Run (Windows-Optimized)

```powershell
# Full build (installs tools, generates templates, builds CSS)
make build

# Development with hot-reload
make watch  # Uses Air for Go + Templ hot-reload

# Manual steps (if needed)
templ generate        # Generate Go from .templ files
npm run css:build     # Build Tailwind CSS
go run cmd/main.go    # Start server
```

### Template Development Cycle

1. Edit `.templ` files in `internal/templates/`
2. Run `templ generate` (or use `make watch`)
3. Generated `*_templ.go` files are auto-created (don't edit these)

### CSS Development

- Edit `static/css/input.css` for Tailwind directives
- Run `npm run css:build` or `npm run css:dev` (watch mode)
- Output goes to `static/css/output.css`

## Project-Specific Patterns

### Handler Structure

Handlers follow this pattern for HTMX compatibility:

```go
func (h *Handler) HandlePost(w http.ResponseWriter, r *http.Request) {
    // Parse and validate form
    validator := middleware.NewFormValidator()

    if middleware.IsHTMXRequest(r) {
        // Return HTML fragment or use middleware.HTMXRedirect()
        middleware.HTMXRedirect(w, "/success-url")
    } else {
        // Standard HTTP redirect
        http.Redirect(w, r, "/success-url", http.StatusSeeOther)
    }
}
```

### Template Organization

- `base.templ` - Root HTML structure with CSS/JS includes
- `layouts/*.templ` - Page-level templates (login, dashboard, etc.)
- `components/*.templ` - Reusable UI components
- All templates auto-include HTMX, Tailwind CSS, and CSRF handling

### CSRF Token Integration

**Critical**: All forms need CSRF protection:

```templ
// In .templ files
templ LoginForm(csrfToken string) {
    <form method="POST" action="/login" hx-boost="true">
        <input type="hidden" name="csrf_token" value={csrfToken}/>
        // other fields
    </form>
}

// In handlers
csrfToken := middleware.GetCSRFTokenFromContext(r.Context())
```

### SAS (Service Authentication Server) Integration

- `internal/sas/` contains OAuth2 client integration
- Models in `sas/model/` define OAuth2 request/response structures
- `sas/client/oauth2.go` handles external auth service communication
- Currently stubbed - integrate with actual CloseAuth authorization server

## Key Conventions

### Route Organization (`internal/routes.go`)

- Static files: `/static/*` (auto MIME types)
- Public routes: `/` (cacheable)
- Auth routes: `/auth/login`, `/auth/register` (GET/POST pairs)
- Admin routes: `/admin/*` (protected, no-cache headers)

### Middleware Stack (Order Matters)

1. Logger
2. CSRF Token Generation (`CSRFTokenMiddleware`)
3. CSRF Validation (`CSRFMiddleware`)
4. CORS (allows `X-CSRF-Token` header)

### Error Handling Pattern

Dual response format for HTMX/standard requests:

```go
func (h *Handler) handleError(w http.ResponseWriter, r *http.Request, message string, code int) {
    if middleware.IsHTMXRequest(r) {
        // Return HTML error fragment
        w.WriteHeader(code)
        w.Write([]byte(`<div class="error">` + message + `</div>`))
    } else {
        http.Error(w, message, code)
    }
}
```

## Integration Points

### External Dependencies

- **CloseAuth Authorization Server**: OAuth2/OIDC endpoints (not yet integrated)
- **Database**: Not yet implemented (handlers use test credentials)
- **Session Management**: Not yet implemented (currently stateless)

### Frontend Assets

- HTMX library: `/static/script/htmx.min.js`
- CSRF JavaScript: `/static/script/csrf.js` (auto-adds tokens to HTMX requests)
- Tailwind CSS: Compiled to `/static/css/output.css`

### Critical Files for AI Context

- `internal/server.go` - Server setup and handler initialization
- `internal/routes.go` - Complete routing table
- `internal/middleware/` - CSRF, HTMX, form validation utilities
- `internal/handlers/auth.go` - Authentication flow patterns
- `CSRF_PROTECTION.md` - Security implementation details
- `HTMX_BOOST_GUIDE.md` - Progressive enhancement patterns

## Testing & Debugging

### Test Credentials (Remove in Production)

- `test@test.com` / `password`
- `admin@example.com` / `password123`
- `user@demo.com` / `demo123`

### Common Issues

- **CSRF failures**: Ensure forms include hidden `csrf_token` field
- **Templ compilation**: Run `templ generate` after editing `.templ` files
- **CSS not updating**: Run `npm run css:build` after Tailwind changes
- **HTMX not working**: Check browser console for JavaScript errors

When adding features, maintain the progressive enhancement pattern: make it work with standard HTML first, then add HTMX enhancements.
