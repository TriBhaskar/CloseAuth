package spring

// --- Token Endpoints ---

// AccessTokenResponse represents the OAuth2 token response from Spring.
type AccessTokenResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int    `json:"expires_in"`
	Scope       string `json:"scope,omitempty"`
}

// --- Client Registration ---

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

// ClientInfoResponse represents client metadata fetched from Spring.
type ClientInfoResponse struct {
	ClientID   string   `json:"clientId"`
	ClientName string   `json:"clientName"`
	LogoURI    string   `json:"logoUri"`
	Scopes     []string `json:"scopes"`
}

// --- Admin Auth ---

// LoginResponse represents the response from Spring's admin login endpoint.
type LoginResponse struct {
	UserID         int    `json:"userId,omitempty"`
	Email          string `json:"email,omitempty"`
	FirstName      string `json:"firstName,omitempty"`
	LastName       string `json:"lastName,omitempty"`
	AccessToken    string `json:"accessToken,omitempty"`
	RefreshToken   string `json:"refreshToken,omitempty"`
	TokenExpiresAt string `json:"tokenExpiresAt,omitempty"`
	TokenType      string `json:"token_type,omitempty"`
	ExpiresIn      int    `json:"expires_in,omitempty"`
	Message        string `json:"message,omitempty"`
	Error          string `json:"error,omitempty"`
}

// RegisterResponse represents the response from admin registration.
type RegisterResponse struct {
	UserID             string `json:"userId,omitempty"`
	Email              string `json:"email,omitempty"`
	FirstName          string `json:"firstName,omitempty"`
	LastName           string `json:"lastName,omitempty"`
	Message            string `json:"message,omitempty"`
	OTPValiditySeconds int    `json:"otpValiditySeconds,omitempty"`
	Timestamp          string `json:"timestamp,omitempty"`
	Error              string `json:"error,omitempty"`
}

// VerifyEmailRequest represents the email verification request payload.
type VerifyEmailRequest struct {
	Email            string `json:"email"`
	VerificationCode string `json:"verificationCode"`
}

// VerifyEmailResponse represents the response from verify-email endpoint.
type VerifyEmailResponse struct {
	Status    string `json:"status,omitempty"`
	Message   string `json:"message,omitempty"`
	Timestamp string `json:"timestamp,omitempty"`
	Error     string `json:"error,omitempty"`
}

// ResendOTPRequest represents the resend OTP request payload.
type ResendOTPRequest struct {
	Email string `json:"email"`
}

// ResendOTPResponse represents the response from resend-otp endpoint.
type ResendOTPResponse struct {
	Message            string `json:"message,omitempty"`
	OTPValiditySeconds int    `json:"otpValiditySeconds,omitempty"`
	Email              string `json:"email,omitempty"`
	Timestamp          string `json:"timestamp,omitempty"`
	Error              string `json:"error,omitempty"`
}

// --- Proxy Results ---

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
