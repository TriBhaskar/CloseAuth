# HTMX hx-boost and Form Enhancement Guide

## What is hx-boost?

`hx-boost` is an HTMX attribute that progressively enhances regular HTML forms and links by converting them into AJAX requests while maintaining full backward compatibility.

## Key Benefits of hx-boost

1. **Progressive Enhancement**: Forms work without JavaScript
2. **SEO Friendly**: Standard HTML that search engines understand
3. **Accessibility**: Maintains proper form semantics and keyboard navigation
4. **Gradual Migration**: Easy to add to existing HTML forms
5. **Fallback Support**: Graceful degradation when JavaScript is disabled

## Basic Usage

### Simple Form Boost

```html
<form method="POST" action="/login" hx-boost="true">
  <input type="email" name="email" required />
  <input type="password" name="password" required />
  <button type="submit">Login</button>
</form>
```

### Link Boost

```html
<a href="/profile" hx-boost="true">View Profile</a>
```

## Advanced Form Enhancement

### Complete Form with Error Handling

```html
<form
  method="POST"
  action="/login"
  hx-post="/login"
  hx-target="#form-container"
  hx-swap="outerHTML"
  hx-indicator="#loading"
  hx-disabled-elt="button[type=submit]"
  hx-on::before-request="clearErrors()"
  hx-on::response-error="handleError(event)"
>
  <!-- Form fields -->
  <input type="email" name="email" required />
  <input type="password" name="password" required />

  <!-- Loading indicator -->
  <div id="loading" class="htmx-indicator">
    <span>Logging in...</span>
  </div>

  <!-- Submit button -->
  <button type="submit">Login</button>
</form>
```

## HTMX Attributes Explained

### hx-boost="true"

- **Purpose**: Converts regular forms/links to AJAX
- **Behavior**: Intercepts form submission, makes AJAX request
- **Fallback**: Works as regular form if JavaScript fails

### hx-target="#selector"

- **Purpose**: Specifies where to put the response
- **Options**:
  - `"body"` - Replace entire page body
  - `"#form-container"` - Replace specific element
  - `"closest .card"` - Target closest ancestor

### hx-swap="strategy"

- **Purpose**: How to swap content
- **Options**:
  - `"innerHTML"` - Replace inner content (default)
  - `"outerHTML"` - Replace entire element
  - `"beforeend"` - Append to end
  - `"afterend"` - Insert after element

### hx-indicator="#loading"

- **Purpose**: Show loading state during request
- **Behavior**: Shows element with `htmx-indicator` class
- **CSS**: Hide by default, show during request

### hx-disabled-elt="selector"

- **Purpose**: Disable elements during request
- **Common**: `"button[type=submit]"` - Disable submit button
- **Prevents**: Double-form submission

## Server-Side Handling

### Go Handler for Boosted Forms

```go
func (s *Server) handleLoginPost(w http.ResponseWriter, r *http.Request) {
    // Parse form data
    err := r.ParseForm()
    if err != nil {
        s.handleError(w, r, "Invalid form data", 400)
        return
    }

    // Extract values
    email := r.FormValue("email")
    password := r.FormValue("password")

    // Validate credentials
    if !s.validateCredentials(email, password) {
        s.handleError(w, r, "Invalid credentials", 401)
        return
    }

    // Success handling
    if middleware.IsHTMXRequest(r) {
        // For HTMX requests
        middleware.HTMXRedirect(w, "/dashboard")
    } else {
        // For regular requests
        http.Redirect(w, r, "/dashboard", http.StatusSeeOther)
    }
}

func (s *Server) handleError(w http.ResponseWriter, r *http.Request, message string, code int) {
    if middleware.IsHTMXRequest(r) {
        // Return partial HTML for HTMX
        w.Header().Set("Content-Type", "text/html")
        w.WriteHeader(code)
        fmt.Fprintf(w, `<div class="error">%s</div>`, message)
    } else {
        // Standard error response
        http.Error(w, message, code)
    }
}
```

## Event Handling

### JavaScript Event Listeners

```javascript
// Before request starts
document.body.addEventListener("htmx:beforeRequest", function (evt) {
  console.log("Request starting to:", evt.detail.requestConfig.path);
  clearErrors();
});

// After successful response
document.body.addEventListener("htmx:afterSettle", function (evt) {
  console.log("Content settled");
  // Re-initialize any JavaScript components
});

// On response error
document.body.addEventListener("htmx:responseError", function (evt) {
  console.log("Request failed:", evt.detail.xhr.status);
  showError(evt.detail.xhr.responseText);
});
```

## CSS for Loading States

### HTMX Indicator Styling

```css
.htmx-indicator {
  display: none;
}

.htmx-request .htmx-indicator {
  display: inline-block;
}

.htmx-request button[type="submit"] {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Loading spinner */
.spinner {
  border: 2px solid #f3f3f3;
  border-top: 2px solid #3498db;
  border-radius: 50%;
  width: 20px;
  height: 20px;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  0% {
    transform: rotate(0deg);
  }
  100% {
    transform: rotate(360deg);
  }
}
```

## Comparison: Regular vs Boosted Forms

### Regular Form (Full Page Reload)

```html
<form method="POST" action="/login">
  <!-- Browser navigates to new page -->
  <!-- Full page refresh -->
  <!-- Lose any JavaScript state -->
</form>
```

### Boosted Form (AJAX with Fallback)

```html
<form method="POST" action="/login" hx-boost="true">
  <!-- HTMX makes AJAX request -->
  <!-- Updates only necessary parts -->
  <!-- Maintains JavaScript state -->
  <!-- Falls back to regular form if needed -->
</form>
```

### Custom HTMX Form (Maximum Control)

```html
<form hx-post="/login" hx-target="#result" hx-swap="innerHTML">
  <!-- Complete HTMX control -->
  <!-- Custom targeting and swapping -->
  <!-- Requires JavaScript to work -->
</form>
```

## Best Practices

### 1. Progressive Enhancement

```html
<!-- Good: Works without JavaScript -->
<form method="POST" action="/login" hx-boost="true">
  <!-- Avoid: Requires JavaScript -->
  <div hx-post="/login"></div>
</form>
```

### 2. Proper Error Handling

```html
<form
  hx-boost="true"
  hx-on::response-error="showError(event)"
  hx-on::before-request="clearErrors()"
></form>
```

### 3. Loading States

```html
<form hx-boost="true" hx-indicator="#loading" hx-disabled-elt="button">
  <button type="submit">
    <span class="htmx-indicator spinner"></span>
    Submit
  </button>
</form>
```

### 4. CSRF Token Handling

```html
<form hx-boost="true">
  <input type="hidden" name="csrf_token" value="{{ .CSRFToken }}" />
  <!-- HTMX automatically includes form fields -->
</form>
```

## Common Patterns

### Login Form with Validation

```html
<form
  method="POST"
  action="/login"
  hx-boost="true"
  hx-target="closest .login-container"
  hx-swap="outerHTML"
  hx-indicator="#login-spinner"
>
  <input type="email" name="email" placeholder="Email" required />
  <input type="password" name="password" placeholder="Password" required />

  <button type="submit">
    <span id="login-spinner" class="htmx-indicator">Loading...</span>
    Login
  </button>
</form>
```

### Search Form with Live Results

```html
<form
  hx-get="/search"
  hx-target="#results"
  hx-trigger="submit, keyup delay:300ms from:input[name=q]"
>
  <input type="search" name="q" placeholder="Search..." />
  <button type="submit">Search</button>
</form>

<div id="results"></div>
```

### Multi-Step Form

```html
<form hx-post="/step1" hx-target="#form-container" hx-swap="outerHTML">
  <!-- Step 1 fields -->
  <button type="submit">Next Step</button>
</form>
```

## Debugging HTMX Forms

### Enable Debug Mode

```html
<script>
  htmx.logger = function (elt, event, data) {
    if (console) {
      console.log(event, elt, data);
    }
  };
</script>
```

### Check Network Tab

- Look for XHR requests instead of document loads
- Verify correct headers are sent
- Check response content type and structure

### Common Issues

1. **CSRF tokens not included** - Ensure form fields are present
2. **Wrong content type** - Server should return HTML for HTMX
3. **Target not found** - Verify hx-target selector exists
4. **JavaScript errors** - Check console for event handler issues
