package response

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"closeauth-backend-for-frontend/internal/middleware"
	"closeauth-backend-for-frontend/internal/templates/components/alerts"

	"github.com/a-h/templ"
)

// RenderError handles error responses for both HTMX and standard HTTP requests
// HTMX requests receive HTML fragments with 200 status (so content is swapped),
// standard requests receive HTTP errors with proper status codes
func RenderError(w http.ResponseWriter, r *http.Request, message string, statusCode int) {
	if middleware.IsHTMXRequest(r) {
		component := alerts.ErrorAlert(message)
		// Use 200 status for HTMX so the error content is swapped into target
		// The error is visually indicated by the alert styling
		w.WriteHeader(http.StatusOK)
		templ.Handler(component).ServeHTTP(w, r)
	} else {
		http.Error(w, message, statusCode)
	}
}

// RenderSuccess handles success responses for HTMX requests
// For standard requests, caller should handle redirect/response appropriately
func RenderSuccess(w http.ResponseWriter, r *http.Request, message string) {
	if middleware.IsHTMXRequest(r) {
		component := alerts.SuccessAlert(message)
		w.WriteHeader(http.StatusOK)
		templ.Handler(component).ServeHTTP(w, r)
	}
}

// RenderWarning handles warning responses for HTMX requests
func RenderWarning(w http.ResponseWriter, r *http.Request, message string) {
	if middleware.IsHTMXRequest(r) {
		component := alerts.WarningAlert(message)
		w.WriteHeader(http.StatusOK)
		templ.Handler(component).ServeHTTP(w, r)
	}
}

// RenderInfo handles informational responses for HTMX requests
func RenderInfo(w http.ResponseWriter, r *http.Request, message string) {
	if middleware.IsHTMXRequest(r) {
		component := alerts.InfoAlert(message)
		w.WriteHeader(http.StatusOK)
		templ.Handler(component).ServeHTTP(w, r)
	}
}

// RenderJSONError handles JSON error responses (for API endpoints)
// Used by themehandler which serves JSON responses
func RenderJSONError(w http.ResponseWriter, message string, statusCode int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	if err := json.NewEncoder(w).Encode(map[string]string{"error": message}); err != nil {
		slog.Error("failed to encode JSON error response", "error", err)
	}
}

// RenderJSONSuccess handles JSON success responses
func RenderJSONSuccess(w http.ResponseWriter, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(data); err != nil {
		slog.Error("failed to encode JSON success response", "error", err)
	}
}
