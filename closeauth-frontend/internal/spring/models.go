package spring

import "encoding/json"

// ──────────────────────────────────────────────────────────────────────────────
// Token Endpoints
// ──────────────────────────────────────────────────────────────────────────────

// AccessTokenResponse represents the OAuth2 token response from Spring.
type AccessTokenResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int    `json:"expires_in"`
	Scope       string `json:"scope,omitempty"`
}

// ──────────────────────────────────────────────────────────────────────────────
// OIDC Client Registration (Spring /connect/register)
// ──────────────────────────────────────────────────────────────────────────────

// ClientFormRequest represents the form input for client registration from the Vue UI.
type ClientFormRequest struct {
	ClientName              string   `json:"client_name" validate:"required"`
	RedirectURIs            []string `json:"redirect_uris" validate:"required,min=1"`
	GrantTypes              []string `json:"grant_types,omitempty"`
	TokenEndpointAuthMethod string   `json:"token_endpoint_auth_method,omitempty"`
	Description             string   `json:"description,omitempty"`
	Scope                   string   `json:"scope,omitempty"`
}

// ClientRegistrationRequest is the payload sent to Spring's /connect/register endpoint.
type ClientRegistrationRequest struct {
	ClientName              string   `json:"client_name"`
	GrantTypes              []string `json:"grant_types,omitempty"`
	TokenEndpointAuthMethod string   `json:"token_endpoint_auth_method,omitempty"`
	Scope                   string   `json:"scope,omitempty"`
	RedirectURIs            []string `json:"redirect_uris"`
}

// ClientRegistrationResponse is what Spring returns after successful registration.
type ClientRegistrationResponse struct {
	ClientID                 string   `json:"client_id"`
	ClientSecret             string   `json:"client_secret,omitempty"`
	ClientName               string   `json:"client_name"`
	GrantTypes               []string `json:"grant_types,omitempty"`
	RedirectURIs             []string `json:"redirect_uris,omitempty"`
	Scope                    string   `json:"scope,omitempty"`
	RegistrationClientURI    string   `json:"registration_client_uri,omitempty"`
	ClientIDIssuedAt         int64    `json:"client_id_issued_at,omitempty"`
	TokenEndpointAuthMethod  string   `json:"token_endpoint_auth_method,omitempty"`
	ResponseTypes            []string `json:"response_types,omitempty"`
	IDTokenSignedResponseAlg string   `json:"id_token_signed_response_alg,omitempty"`
	RegistrationAccessToken  string   `json:"registration_access_token,omitempty"`
	ClientSecretExpiresAt    int64    `json:"client_secret_expires_at,omitempty"`
}

// ClientRegistrationError represents an error response from client registration.
type ClientRegistrationError struct {
	Error            string `json:"error"`
	ErrorDescription string `json:"error_description,omitempty"`
}

// ──────────────────────────────────────────────────────────────────────────────
// Client Info (/oauth2/client-info)
// ──────────────────────────────────────────────────────────────────────────────

// ClientInfoResponse represents client metadata fetched from Spring.
type ClientInfoResponse struct {
	ClientID   string   `json:"clientId"`
	ClientName string   `json:"clientName"`
	LogoURI    string   `json:"logoUri"`
	Scopes     []string `json:"scopes"`
}

// ──────────────────────────────────────────────────────────────────────────────
// Admin Auth – matches Spring's UserLoginResponse / UserRegistrationResponse
// ──────────────────────────────────────────────────────────────────────────────

// LoginResponse represents the response from Spring's admin login endpoint.
// Maps to: com.anterka.closeauthbackend.auth.dto.response.UserLoginResponse
type LoginResponse struct {
	UserID         int    `json:"userId"`
	Email          string `json:"email"`
	FirstName      string `json:"firstName"`
	LastName       string `json:"lastName"`
	AccessToken    string `json:"accessToken"`
	TokenExpiresAt string `json:"tokenExpiresAt"`
}

// RegisterResponse represents the response from admin registration.
// Maps to: com.anterka.closeauthbackend.auth.dto.response.UserRegistrationResponse
type RegisterResponse struct {
	UserID             int64  `json:"userId,omitempty"`
	Email              string `json:"email,omitempty"`
	FirstName          string `json:"firstName,omitempty"`
	LastName           string `json:"lastName,omitempty"`
	Message            string `json:"message,omitempty"`
	OTPValiditySeconds int64  `json:"otpValiditySeconds,omitempty"`
	Timestamp          string `json:"timestamp,omitempty"`
}

// ResendOTPResponse represents the response from resend-otp endpoint.
// Maps to: com.anterka.closeauthbackend.auth.dto.response.ResendOtpResponse
type ResendOTPResponse struct {
	Message            string `json:"message,omitempty"`
	OTPValiditySeconds int64  `json:"otpValiditySeconds,omitempty"`
	Email              string `json:"email,omitempty"`
	Timestamp          string `json:"timestamp,omitempty"`
}

// ──────────────────────────────────────────────────────────────────────────────
// Generic Spring API response wrapper
// Maps to: com.anterka.closeauthbackend.common.dto.CustomApiResponse<T>
// ──────────────────────────────────────────────────────────────────────────────

// CustomApiResponse is a generic wrapper matching Spring's CustomApiResponse<T>.
type CustomApiResponse struct {
	Message   string          `json:"message,omitempty"`
	Status    string          `json:"status,omitempty"` // "SUCCESS" or "FAILED"
	Timestamp string          `json:"timestamp,omitempty"`
	Data      json.RawMessage `json:"data,omitempty"`
}

// ApiErrorResponse represents a Spring error response body.
type ApiErrorResponse struct {
	Error            string `json:"error,omitempty"`
	ErrorDescription string `json:"error_description,omitempty"`
	Message          string `json:"message,omitempty"`
	Status           string `json:"status,omitempty"`
}

// ──────────────────────────────────────────────────────────────────────────────
// Proxy Results (internal to the BFF – not mapped to Spring DTOs)
// ──────────────────────────────────────────────────────────────────────────────

// ProxyResult holds the result of proxying a request to Spring.
type ProxyResult struct {
	StatusCode int
	Location   string // Redirect URL (if 3xx)
	Body       []byte
	Cookies    []*Cookie
}

// Cookie is a simplified cookie representation for passing between layers.
type Cookie struct {
	Name   string
	Value  string
	Path   string
	MaxAge int
}
