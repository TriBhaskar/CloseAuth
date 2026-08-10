package middleware

import "net/http"

// clearCookie expires the named cookie by setting MaxAge:-1, matching the
// Path/Secure/SameSite/HttpOnly attributes it was originally set with.
//
// Before this stage, ClearOAuthContext/ClearSession/ClearCSRFToken each set
// MaxAge:-1 but omitted Secure/SameSite — RFC 6265 identifies a cookie by
// (name, domain, path) for storage, but a browser matches a Set-Cookie
// clear against its own SameSite/Secure bookkeeping too, and a mismatched
// clear can silently fail to remove the cookie the browser thinks it has.
// Every Clear* function in this package now goes through this one helper so
// there is exactly one place that attribute set has to be right.
func clearCookie(w http.ResponseWriter, name, path string, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     name,
		Value:    "",
		Path:     path,
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteLaxMode,
	})
}
