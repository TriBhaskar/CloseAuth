package middleware

import (
	"net/http"
	"strings"
)

// FormValidationError represents a form validation error
type FormValidationError struct {
	Field   string
	Message string
}

// FormValidator provides common form validation functions
type FormValidator struct {
	Errors []FormValidationError
}

// NewFormValidator creates a new form validator
func NewFormValidator() *FormValidator {
	return &FormValidator{
		Errors: make([]FormValidationError, 0),
	}
}

// Required validates that a field is not empty
func (fv *FormValidator) Required(field, value, message string) {
	if strings.TrimSpace(value) == "" {
		fv.Errors = append(fv.Errors, FormValidationError{
			Field:   field,
			Message: message,
		})
	}
}

// Email validates email format (basic validation)
func (fv *FormValidator) Email(field, value, message string) {
	if value != "" && !strings.Contains(value, "@") {
		fv.Errors = append(fv.Errors, FormValidationError{
			Field:   field,
			Message: message,
		})
	}
}

// MinLength validates minimum length
func (fv *FormValidator) MinLength(field, value string, minLen int, message string) {
	if len(value) < minLen {
		fv.Errors = append(fv.Errors, FormValidationError{
			Field:   field,
			Message: message,
		})
	}
}

// IsValid returns true if there are no validation errors
func (fv *FormValidator) IsValid() bool {
	return len(fv.Errors) == 0
}

// FormParsingMiddleware handles form parsing and basic error handling
func FormParsingMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" || r.Method == "PUT" || r.Method == "PATCH" {
			contentType := r.Header.Get("Content-Type")
			
			if strings.Contains(contentType, "multipart/form-data") {
				// Handle multipart forms (file uploads)
				err := r.ParseMultipartForm(10 << 20) // 10 MB max
				if err != nil {
					http.Error(w, "Failed to parse multipart form", http.StatusBadRequest)
					return
				}
			} else {
				// Handle regular forms
				err := r.ParseForm()
				if err != nil {
					http.Error(w, "Failed to parse form data", http.StatusBadRequest)
					return
				}
			}
		}
		
		next.ServeHTTP(w, r)
	}
}