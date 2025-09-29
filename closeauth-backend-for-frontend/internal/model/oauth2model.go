package model

// TokenRequest represents the OAuth2 token request
type AccessTokenRequest struct {
	GrantType    string `json:"grant_type"`
	ClientID     string `json:"client_id"`
	ClientSecret string `json:"client_secret"`
	RedirectURL  string `json:"redirect_url"`
	Scope        string `json:"scope"`
}

// TokenResponse represents the OAuth2 token response
type AccessTokenResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int    `json:"expires_in"`
	Scope       string `json:"scope"`
}

// ClientRegistrationRequest represents the client registration request
type ClientRegistrationRequest struct {
	ClientName                 string   `json:"client_name"`
	GrantTypes                 []string `json:"grant_types"`
	TokenEndpointAuthMethod    string   `json:"token_endpoint_auth_method"`
	Scope                      string   `json:"scope"`
	RedirectURIs              []string `json:"redirect_uris"`
}

// ClientRegistrationResponse represents the client registration response
type ClientRegistrationResponse struct {
	ClientID                  string   `json:"client_id"`
	ClientSecret              string   `json:"client_secret"`
	ClientName                string   `json:"client_name"`
	GrantTypes                []string `json:"grant_types"`
	RedirectURIs             []string `json:"redirect_uris"`
	Scope                     string   `json:"scope"`
	RegistrationClientURI     string   `json:"registration_client_uri"`
	ClientIDIssuedAt         int64    `json:"client_id_issued_at"`
	TokenEndpointAuthMethod   string   `json:"token_endpoint_auth_method"`
	ResponseTypes            []string `json:"response_types"`
	IDTokenSignedResponseAlg  string   `json:"id_token_signed_response_alg"`
	RegistrationAccessToken   string   `json:"registration_access_token"`
	ClientSecretExpiresAt    int64    `json:"client_secret_expires_at"`
	Error                     string   `json:"error,omitempty"`
	ErrorDescription         string   `json:"error_description,omitempty"`
}

type ClientFormRequest struct {
    ClientName     string   `json:"client_name" validate:"required"`
    RedirectURIs []string `json:"redirect_uris" validate:"required,min=1"`
	GrantTypes   []string `json:"grant_types"`
	TokenEndpointAuthMethod    string   `json:"token_endpoint_auth_method"`
    Description  string   `json:"description"`
    Scope        string   `json:"scope"`
}