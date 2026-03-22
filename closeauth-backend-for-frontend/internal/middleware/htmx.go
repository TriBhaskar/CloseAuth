package middleware

import (
	"encoding/json"
	"net/http"
)

// HTMXResponse represents an HTMX response
type HTMXResponse struct {
	HTML     string            `json:"html,omitempty"`
	Redirect string            `json:"redirect,omitempty"`
	Refresh  bool              `json:"refresh,omitempty"`
	Headers  map[string]string `json:"headers,omitempty"`
}

// IsHTMXRequest checks if the request is from HTMX
func IsHTMXRequest(r *http.Request) bool {
	return r.Header.Get("HX-Request") == "true"
}

// HTMXRedirect sends an HTMX redirect response
func HTMXRedirect(w http.ResponseWriter, url string) {
	w.Header().Set("HX-Redirect", url)
	w.WriteHeader(http.StatusOK)
}

// HTMXRefresh tells HTMX to refresh the page
func HTMXRefresh(w http.ResponseWriter) {
	w.Header().Set("HX-Refresh", "true")
	w.WriteHeader(http.StatusOK)
}

// HTMXError sends an error response that HTMX can handle
func HTMXError(w http.ResponseWriter, message string, statusCode int) {
	if IsHTMXRequest(&http.Request{Header: w.Header()}) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(statusCode)
		w.Write([]byte(message))
	} else {
		http.Error(w, message, statusCode)
	}
}

// SendHTMXResponse sends a JSON response for HTMX
func SendHTMXResponse(w http.ResponseWriter, response HTMXResponse) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(response)
}