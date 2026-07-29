package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// AdminClient is a thin, bearer-authenticated client for the admin API
// (/v1/**). Unlike OAuthClient it needs no PKCE/cookies/redirect handling —
// just "Authorization: Bearer <token>" calls, plus the two conveniences every
// admin-surface caller needs: RFC 7807 problem decoding (Problem, in
// problem.go) and the platform-admin token mint (MintPlatformAdminToken,
// below — the one /v1 endpoint that is NOT bearer-authenticated, since it's
// what MINTS the bearer token).
//
// Design vs. the Java IT module's AdminApiClient: methods return an
// APIResponse (status/header/body already read, connection released) rather
// than a live *http.Response — Go callers almost always want to either
// json.Unmarshal the body or inspect it as a Problem, and returning bytes up
// front avoids every caller having to remember `defer resp.Body.Close()`.
// This mirrors AdminApiClient's "return the raw Response, caller asserts"
// philosophy (deliberately thin, no per-endpoint typed methods baked in)
// while fitting idiomatic Go body-handling.
type AdminClient struct {
	baseURL     string
	contextPath string
	httpClient  *http.Client
}

// NewAdminClient constructs a client against a running backend.
func NewAdminClient(baseURL, contextPath string) *AdminClient {
	return &AdminClient{
		baseURL:     strings.TrimRight(baseURL, "/"),
		contextPath: contextPath,
		httpClient:  &http.Client{Timeout: 30 * time.Second},
	}
}

func (c *AdminClient) url(path string) string {
	return c.baseURL + c.contextPath + path
}

// APIResponse is a fully-drained HTTP response: status, headers, and body
// bytes, ready for JSON decoding or RFC 7807 problem parsing.
type APIResponse struct {
	StatusCode int
	Header     http.Header
	Body       []byte
}

// OK reports whether the status code is 2xx.
func (r APIResponse) OK() bool {
	return r.StatusCode >= 200 && r.StatusCode < 300
}

// JSON decodes the body into v.
func (r APIResponse) JSON(v any) error {
	if err := json.Unmarshal(r.Body, v); err != nil {
		return fmt.Errorf("decode json response: %w (body=%s)", err, r.Body)
	}
	return nil
}

// Problem decodes the body as an RFC 7807 problem+json document. Callers on a
// non-2xx response typically want Problem().Code, e.g. to assert a specific
// domain error like "tenant_role.last_admin" rather than just the HTTP status.
func (r APIResponse) Problem() (Problem, error) {
	return ParseProblem(r.Body)
}

func (c *AdminClient) do(ctx context.Context, method, path, token string, body io.Reader, contentType string) (APIResponse, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.url(path), body)
	if err != nil {
		return APIResponse{}, fmt.Errorf("admin client: build request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return APIResponse{}, fmt.Errorf("admin client: %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return APIResponse{}, fmt.Errorf("admin client: read response body: %w", err)
	}
	return APIResponse{StatusCode: resp.StatusCode, Header: resp.Header, Body: respBody}, nil
}

// Get issues a bearer GET.
func (c *AdminClient) Get(ctx context.Context, token, path string) (APIResponse, error) {
	return c.do(ctx, http.MethodGet, path, token, nil, "")
}

// GetQuery issues a bearer GET with query parameters appended to path.
func (c *AdminClient) GetQuery(ctx context.Context, token, path string, query url.Values) (APIResponse, error) {
	if len(query) > 0 {
		path = path + "?" + query.Encode()
	}
	return c.Get(ctx, token, path)
}

// Post issues a bearer POST with no body (e.g. .../activate).
func (c *AdminClient) Post(ctx context.Context, token, path string) (APIResponse, error) {
	return c.do(ctx, http.MethodPost, path, token, nil, "")
}

// PostJSON issues a bearer POST with a JSON body.
func (c *AdminClient) PostJSON(ctx context.Context, token, path string, body any) (APIResponse, error) {
	return c.jsonRequest(ctx, http.MethodPost, path, token, body)
}

// PutJSON issues a bearer PUT with a JSON body.
func (c *AdminClient) PutJSON(ctx context.Context, token, path string, body any) (APIResponse, error) {
	return c.jsonRequest(ctx, http.MethodPut, path, token, body)
}

// PatchJSON issues a bearer PATCH with a JSON body.
func (c *AdminClient) PatchJSON(ctx context.Context, token, path string, body any) (APIResponse, error) {
	return c.jsonRequest(ctx, http.MethodPatch, path, token, body)
}

// Delete issues a bearer DELETE.
func (c *AdminClient) Delete(ctx context.Context, token, path string) (APIResponse, error) {
	return c.do(ctx, http.MethodDelete, path, token, nil, "")
}

func (c *AdminClient) jsonRequest(ctx context.Context, method, path, token string, body any) (APIResponse, error) {
	encoded, err := json.Marshal(body)
	if err != nil {
		return APIResponse{}, fmt.Errorf("admin client: encode json body: %w", err)
	}
	return c.do(ctx, method, path, token, bytes.NewReader(encoded), "application/json")
}

// ---- platform-admin auth (the one unauthenticated /v1 endpoint) ----------

// PlatformTokenResponse is the POST /v1/platform/auth/token response body.
type PlatformTokenResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int64  `json:"expires_in"`
}

// MintPlatformAdminToken authenticates a platform admin by email/password and
// mints a platform-admin access token. This is the ONE /v1 endpoint that is
// NOT bearer-authenticated (it's what produces the bearer token every other
// /v1/platform/** and /v1/tenants/{id}/** call needs) — a simple JSON POST,
// no PKCE, no cookies.
func (c *AdminClient) MintPlatformAdminToken(ctx context.Context, email, password string) (PlatformTokenResponse, error) {
	resp, err := c.PostJSON(ctx, "", "/v1/platform/auth/token", map[string]string{"email": email, "password": password})
	if err != nil {
		return PlatformTokenResponse{}, err
	}
	if !resp.OK() {
		return PlatformTokenResponse{}, fmt.Errorf("mint platform admin token: %w", responseError(resp))
	}
	var out PlatformTokenResponse
	if err := resp.JSON(&out); err != nil {
		return PlatformTokenResponse{}, err
	}
	return out, nil
}

// PlatformMeView is the GET /v1/platform/me response body — the caller's own
// platform-admin identity (sub, email, status, platform roles).
type PlatformMeView struct {
	Sub    string   `json:"sub"`
	Email  string   `json:"email"`
	Status string   `json:"status"`
	Roles  []string `json:"roles"`
}

// PlatformMe calls GET /v1/platform/me — the 7a demonstration endpoint that
// proves the platform-admin token + @RequiresPlatformAdmin gate end to end.
func (c *AdminClient) PlatformMe(ctx context.Context, token string) (PlatformMeView, error) {
	resp, err := c.Get(ctx, token, "/v1/platform/me")
	if err != nil {
		return PlatformMeView{}, err
	}
	if !resp.OK() {
		return PlatformMeView{}, fmt.Errorf("get /v1/platform/me: %w", responseError(resp))
	}
	var view PlatformMeView
	if err := resp.JSON(&view); err != nil {
		return PlatformMeView{}, err
	}
	return view, nil
}

// MeView is the GET /v1/me response for a TENANT USER principal (UserView on
// the backend). Calling /v1/me with a platform-admin token instead decodes
// into the platform-admin's profile shape (id/email/status/firstName/
// lastName/...), which does not match this struct's field set — callers that
// need the platform-admin's own profile should prefer PlatformMe above,
// which has a stable, documented shape (§5 API_REFERENCE.md), rather than
// depend on /v1/me's principal-dependent response shape.
type MeView struct {
	ID            string `json:"id"`
	TenantID      string `json:"tenantId"`
	Email         string `json:"email"`
	EmailVerified bool   `json:"emailVerified"`
	FirstName     string `json:"firstName"`
	LastName      string `json:"lastName"`
	Status        string `json:"status"`
}

// Me calls GET /v1/me with a TENANT USER's bearer token and decodes the
// response as MeView. This is how a Session's Email field is populated after
// an OAuthClient token exchange — the JWT itself carries no email claim (see
// session.go's provenance comment).
func (c *AdminClient) Me(ctx context.Context, token string) (MeView, error) {
	resp, err := c.Get(ctx, token, "/v1/me")
	if err != nil {
		return MeView{}, err
	}
	if !resp.OK() {
		return MeView{}, fmt.Errorf("get /v1/me: %w", responseError(resp))
	}
	var view MeView
	if err := resp.JSON(&view); err != nil {
		return MeView{}, err
	}
	return view, nil
}

// responseError builds an error from a non-2xx APIResponse, preferring the
// RFC 7807 domain code when the body parses as problem+json, and falling
// back to the raw status/body otherwise (the interactive auth endpoints
// deliberately return plain JSON, not problem+json, for some uniform/
// enumeration-safe failures — see API_REFERENCE.md's error-model section).
func responseError(resp APIResponse) error {
	if problem, err := resp.Problem(); err == nil && (problem.Code != "" || problem.Title != "") {
		return fmt.Errorf("HTTP %d: %w", resp.StatusCode, problem)
	}
	return fmt.Errorf("HTTP %d: %s", resp.StatusCode, resp.Body)
}
