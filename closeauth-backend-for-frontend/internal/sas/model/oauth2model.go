package model

// AccessTokenRequest represents the OAuth2 token request
type AccessTokenRequest struct {
	GrantType    string `json:"grant_type" validate:"required"`
	ClientID     string `json:"client_id" validate:"required"`
	ClientSecret string `json:"client_secret" validate:"required"`
	RedirectURI  string `json:"redirect_uri,omitempty"` // Fixed: should be redirect_uri
	Scope        string `json:"scope,omitempty"`
}

// AccessTokenResponse represents the OAuth2 token response
type AccessTokenResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int    `json:"expires_in"`
	Scope       string `json:"scope,omitempty"`
}

// ClientRegistrationRequest represents the client registration request
type ClientRegistrationRequest struct {
	ClientName              string   `json:"client_name" validate:"required"`
	GrantTypes              []string `json:"grant_types,omitempty"`
	TokenEndpointAuthMethod string   `json:"token_endpoint_auth_method,omitempty"`
	Scope                   string   `json:"scope,omitempty"`
	RedirectURIs           []string `json:"redirect_uris" validate:"required,min=1"`
}

// ClientRegistrationResponse represents the client registration response
type ClientRegistrationResponse struct {
	ClientID                 string   `json:"client_id"`
	ClientSecret             string   `json:"client_secret,omitempty"`
	ClientName               string   `json:"client_name"`
	GrantTypes               []string `json:"grant_types,omitempty"`
	RedirectURIs            []string `json:"redirect_uris,omitempty"`
	Scope                    string   `json:"scope,omitempty"`
	RegistrationClientURI    string   `json:"registration_client_uri,omitempty"`
	ClientIDIssuedAt        int64    `json:"client_id_issued_at,omitempty"`
	TokenEndpointAuthMethod  string   `json:"token_endpoint_auth_method,omitempty"`
	ResponseTypes           []string `json:"response_types,omitempty"`
	IDTokenSignedResponseAlg string   `json:"id_token_signed_response_alg,omitempty"`
	RegistrationAccessToken  string   `json:"registration_access_token,omitempty"`
	ClientSecretExpiresAt   int64    `json:"client_secret_expires_at,omitempty"`
}

// ClientRegistrationError represents error response for client registration
type ClientRegistrationError struct {
	Error            string `json:"error"`
	ErrorDescription string `json:"error_description,omitempty"`
}

// ClientFormRequest represents the form input for client registration
type ClientFormRequest struct {
	ClientName              string   `json:"client_name" validate:"required"`
	RedirectURIs           []string `json:"redirect_uris" validate:"required,min=1"`
	GrantTypes              []string `json:"grant_types,omitempty"`
	TokenEndpointAuthMethod string   `json:"token_endpoint_auth_method,omitempty"`
	Description             string   `json:"description,omitempty"`
	Scope                   string   `json:"scope,omitempty"`
}