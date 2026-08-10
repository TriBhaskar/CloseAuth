package backend

import (
	"net/url"
	"testing"
)

// TestAuthorizeURL_ContainsAllRequiredPKCEParams is a pure unit test (no
// Docker/testsupport dependency, unlike oauth_client_test.go's round-trip
// proof) locking down AuthorizeURL's query shape — the exported URL builder
// extracted from Authorize for stage UI-3a's browser-redirect admin-console
// flow.
func TestAuthorizeURL_ContainsAllRequiredPKCEParams(t *testing.T) {
	client := NewOAuthClient("http://localhost:9000", "/closeauth", "http://localhost:8080/admin/callback")
	pkce := PKCE{Verifier: "verifier-value", Challenge: "challenge-value"}

	got := client.AuthorizeURL("admin-console-acme", "openid profile", pkce, "state-value")

	u, err := url.Parse(got)
	if err != nil {
		t.Fatalf("AuthorizeURL() produced an unparseable URL: %v", err)
	}
	if u.Path != "/closeauth/oauth2/authorize" {
		t.Errorf("path = %q, want /closeauth/oauth2/authorize", u.Path)
	}

	q := u.Query()
	want := map[string]string{
		"response_type":         "code",
		"client_id":             "admin-console-acme",
		"redirect_uri":          "http://localhost:8080/admin/callback",
		"scope":                 "openid profile",
		"code_challenge":        "challenge-value",
		"code_challenge_method": "S256",
		"state":                 "state-value",
	}
	for name, expected := range want {
		if got := q.Get(name); got != expected {
			t.Errorf("query param %q = %q, want %q", name, got, expected)
		}
	}
}

func TestAuthorizeURL_DoesNotPerformTheRequest(t *testing.T) {
	// AuthorizeURL must be a pure string builder — Authorize is the one that
	// performs the GET. This just confirms calling it against an
	// unreachable base URL doesn't error or block.
	client := NewOAuthClient("http://127.0.0.1:1", "/closeauth", "http://localhost:8080/admin/callback")
	pkce := PKCE{Verifier: "v", Challenge: "c"}

	got := client.AuthorizeURL("admin-console-acme", "openid profile", pkce, "state-value")
	if got == "" {
		t.Fatal("AuthorizeURL() returned empty string")
	}
}
