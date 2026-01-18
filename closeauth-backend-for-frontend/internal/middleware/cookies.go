package middleware

import (
	"net/http"
	"os"
)

// CookieOptions holds configuration for creating cookies
type CookieOptions struct {
	Name     string
	Value    string
	Path     string
	MaxAge   int
	HttpOnly bool
	Secure   bool
	SameSite http.SameSite
}

// NewSecureCookie creates a secure cookie with standard security settings
func NewSecureCookie(name, value string, maxAge int) *http.Cookie {
	return &http.Cookie{
		Name:     name,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   isProductionEnv(),
		SameSite: http.SameSiteLaxMode,
	}
}

// NewSecureCookieWithOptions creates a cookie with custom options
func NewSecureCookieWithOptions(opts CookieOptions) *http.Cookie {
	// Apply defaults for security-critical fields if not specified
	if opts.Path == "" {
		opts.Path = "/"
	}
	
	return &http.Cookie{
		Name:     opts.Name,
		Value:    opts.Value,
		Path:     opts.Path,
		MaxAge:   opts.MaxAge,
		HttpOnly: opts.HttpOnly,
		Secure:   opts.Secure,
		SameSite: opts.SameSite,
	}
}

// DeleteCookie creates an expired cookie to delete it from the client
func DeleteCookie(name string) *http.Cookie {
	return &http.Cookie{
		Name:     name,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   isProductionEnv(),
		SameSite: http.SameSiteLaxMode,
	}
}

// SetSecureCookie sets a secure cookie with standard security settings
func SetSecureCookie(w http.ResponseWriter, name, value string, maxAge int) {
	cookie := NewSecureCookie(name, value, maxAge)
	http.SetCookie(w, cookie)
}

// ClearCookie deletes a cookie from the client
func ClearCookie(w http.ResponseWriter, name string) {
	cookie := DeleteCookie(name)
	http.SetCookie(w, cookie)
}

// isProductionEnv checks if the application is running in production
func isProductionEnv() bool {
	env := os.Getenv("ENVIRONMENT")
	return env == "production" || env == "prod"
}
