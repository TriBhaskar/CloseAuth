package middleware

import (
	"fmt"
	"net/http"
	"regexp"
	"strings"
)

// FormData holds parsed form values for validation
type FormData struct {
	request *http.Request
	errors  map[string]string
}

// NewFormData creates a new FormData instance after parsing the form
func NewFormData(r *http.Request) (*FormData, error) {
	if err := r.ParseForm(); err != nil {
		return nil, fmt.Errorf("failed to parse form: %w", err)
	}

	return &FormData{
		request: r,
		errors:  make(map[string]string),
	}, nil
}

// Get retrieves a form value by key
func (f *FormData) Get(key string) string {
	return f.request.FormValue(key)
}

// GetRequired retrieves a required form value and validates it's not empty
func (f *FormData) GetRequired(key, fieldName string) string {
	value := strings.TrimSpace(f.request.FormValue(key))
	if value == "" {
		f.errors[key] = fmt.Sprintf("%s is required", fieldName)
	}
	return value
}

// GetEmail retrieves and validates an email field
func (f *FormData) GetEmail(key, fieldName string) string {
	email := strings.TrimSpace(f.request.FormValue(key))
	if email == "" {
		f.errors[key] = fmt.Sprintf("%s is required", fieldName)
		return email
	}

	if !isValidEmail(email) {
		f.errors[key] = fmt.Sprintf("%s is not a valid email address", fieldName)
	}

	return email
}

// GetBool retrieves a checkbox/boolean form value
func (f *FormData) GetBool(key string) bool {
	return f.request.FormValue(key) == "on" || f.request.FormValue(key) == "true"
}

// ValidatePasswordMatch validates that password and confirmation match
func (f *FormData) ValidatePasswordMatch(passwordKey, confirmKey, fieldName string) (string, string) {
	password := f.GetRequired(passwordKey, fieldName)
	confirmPassword := f.GetRequired(confirmKey, "Confirm "+fieldName)

	if password != "" && confirmPassword != "" && password != confirmPassword {
		f.errors[confirmKey] = "Passwords do not match"
	}

	return password, confirmPassword
}

// ValidatePasswordStrength validates password meets minimum requirements
func (f *FormData) ValidatePasswordStrength(passwordKey, fieldName string) string {
	password := f.GetRequired(passwordKey, fieldName)
	
	if password == "" {
		return password
	}

	if len(password) < 8 {
		f.errors[passwordKey] = fmt.Sprintf("%s must be at least 8 characters", fieldName)
	}

	return password
}

// HasErrors checks if any validation errors occurred
func (f *FormData) HasErrors() bool {
	return len(f.errors) > 0
}

// AddError adds a custom validation error
func (f *FormData) AddError(key, message string) {
	f.errors[key] = message
}

// Errors returns all validation errors
func (f *FormData) Errors() map[string]string {
	return f.errors
}

// FirstError returns the first error message (useful for display)
func (f *FormData) FirstError() string {
	for _, msg := range f.errors {
		return msg
	}
	return ""
}

// AllErrors returns all error messages as a single string
func (f *FormData) AllErrors() string {
	var messages []string
	for _, msg := range f.errors {
		messages = append(messages, msg)
	}
	return strings.Join(messages, "; ")
}

// isValidEmail validates email format using regex
func isValidEmail(email string) bool {
	// RFC 5322 compliant email regex (simplified)
	emailRegex := regexp.MustCompile(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`)
	return emailRegex.MatchString(email)
}

// ParseFormSafely wraps r.ParseForm() with error handling
func ParseFormSafely(r *http.Request) error {
	if err := r.ParseForm(); err != nil {
		return fmt.Errorf("failed to parse form: %w", err)
	}
	return nil
}
