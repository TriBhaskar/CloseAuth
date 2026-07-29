package backend

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// Problem is an RFC 7807 application/problem+json body, as produced
// uniformly by the backend's ApiExceptionHandler for domain/validation/
// forbidden/auth errors. The Go equivalent of the Java IT module's
// AdminApiClient.problemCode, generalized into a value type (rather than a
// static string-extractor) so callers can inspect the full body, not just the
// domain code.
type Problem struct {
	Type     string         `json:"type,omitempty"`
	Title    string         `json:"title,omitempty"`
	Status   int            `json:"status,omitempty"`
	Detail   string         `json:"detail,omitempty"`
	Code     string         `json:"code,omitempty"` // the domain error code, e.g. "tenant_role.last_admin"
	Instance string         `json:"instance,omitempty"`
	Errors   map[string]any `json:"errors,omitempty"` // per-property validation messages (400s)
}

// ParseProblem decodes an application/problem+json response body. It does
// NOT check the response's Content-Type or status code — callers that only
// care about the domain `code` on a known-non-2xx response can call this
// directly; callers that need to distinguish "was this even a problem+json
// response" should check r.Header.Get("Content-Type") themselves first (the
// backend's interactive auth endpoints deliberately return plain JSON, not
// problem+json, for some uniform/enumeration-safe failures — see
// API_REFERENCE.md's error-model section).
func ParseProblem(body []byte) (Problem, error) {
	var p Problem
	if err := json.Unmarshal(body, &p); err != nil {
		return Problem{}, fmt.Errorf("parse problem+json: %w", err)
	}
	return p, nil
}

// ParseProblemResponse reads and parses an http.Response body as a Problem.
// The caller remains responsible for closing resp.Body.
func ParseProblemResponse(resp *http.Response) (Problem, error) {
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return Problem{}, fmt.Errorf("parse problem+json: read body: %w", err)
	}
	return ParseProblem(body)
}

// Error implements the error interface so a Problem can be returned/wrapped
// directly by client methods that turn a non-2xx response into a Go error.
func (p Problem) Error() string {
	if p.Code != "" {
		return fmt.Sprintf("backend problem: %s (%s): %s", p.Code, p.Title, p.Detail)
	}
	return fmt.Sprintf("backend problem: %d %s: %s", p.Status, p.Title, p.Detail)
}
