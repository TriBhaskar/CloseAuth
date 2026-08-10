package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"closeauth-frontend/internal/testsupport"
)

// Stage UI-3c: live, Docker-gated proofs that the client-registration surface
// works end to end through the REAL backend AND a REAL BFF. Reuses
// admin_users_test.go's harness helpers (newTenantWithAdmin,
// establishAdminSession, doWithCSRF) and admin_console_test.go's
// (newAdminConsoleServer, newBrowserLikeClient, readBody, assertNoTokenLeak /
// assertNoTokenLeakExceptSecret) verbatim.
//
// The headline fact under test is the backend change this stage made: the
// secret is BACKEND-generated (ClientSecretGenerator, closeauth-backend),
// never caller-supplied, and — genuinely new — recoverable via regeneration,
// which this codebase had no answer for before. The BFF's role here is
// purely to prove it stays a thin relay: the generated secret and the
// domain conflict code (client.public_no_secret) both survive the hop
// through writeAdminAPIResult intact.

type clientCreatedBody struct {
	Client       clientViewBody `json:"client"`
	ClientSecret *string        `json:"clientSecret"`
}

type clientViewBody struct {
	ID         string `json:"id"`
	ClientID   string `json:"clientId"`
	ClientName string `json:"clientName"`
}

func createClient(t *testing.T, client *http.Client, bffBase, slug string, publicClient bool) (int, clientCreatedBody, string) {
	t.Helper()
	clientID := "c-" + testsupport.Suffix()
	body, _ := json.Marshal(map[string]any{
		"clientId":        clientID,
		"clientName":      "Test Client " + clientID,
		"publicClient":    publicClient,
		"grantTypes":      []string{"client_credentials"},
		"requireProofKey": false,
		"trusted":         true,
	})
	resp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/clients", "application/json", bytes.NewReader(body))
	defer resp.Body.Close()
	bodyStr, _ := readBody(resp.Body)
	var created clientCreatedBody
	if resp.StatusCode == http.StatusCreated {
		if err := json.Unmarshal([]byte(bodyStr), &created); err != nil {
			t.Fatalf("decode created client: %v (body=%s)", err, bodyStr)
		}
	}
	return resp.StatusCode, created, bodyStr
}

// obtainClientCredentialsToken proves a secret is genuinely valid/invalid by
// actually authenticating against the REAL backend's /oauth2/token — not
// just inspecting a response body — mirroring the Java integration-test
// module's Basic-auth-then-assert-status pattern.
func obtainClientCredentialsToken(t *testing.T, stack *testsupport.Stack, clientID, secret string) int {
	t.Helper()
	form := url.Values{"grant_type": {"client_credentials"}}
	req, err := http.NewRequest(http.MethodPost, stack.AppBaseURI()+stack.ContextPath()+"/oauth2/token",
		strings.NewReader(form.Encode()))
	if err != nil {
		t.Fatalf("build token request: %v", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.SetBasicAuth(clientID, secret)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST oauth2/token: %v", err)
	}
	defer resp.Body.Close()
	return resp.StatusCode
}

func TestAdminClients_CreateReturnsGeneratedSecretOnceAndNeverAgain(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "client-create-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	status, created, bodyStr := createClient(t, client, bffBase, slug, false)
	if status != http.StatusCreated {
		t.Fatalf("POST clients: status = %d, want 201, body=%s", status, bodyStr)
	}
	if created.ClientSecret == nil || *created.ClientSecret == "" {
		t.Fatalf("create response: clientSecret missing/blank, want a generated secret, body=%s", bodyStr)
	}
	secret := *created.ClientSecret
	if len(secret) < 40 {
		t.Errorf("generated secret looks too short to be genuinely random: len=%d", len(secret))
	}
	assertNoTokenLeakExceptSecret(t, "POST clients", bodyStr, secret)

	// The generated secret genuinely authenticates against the real backend.
	if code := obtainClientCredentialsToken(t, stack, created.Client.ClientID, secret); code != http.StatusOK {
		t.Errorf("token request with the freshly-generated secret: status = %d, want 200", code)
	}

	// Never surfaced again: a subsequent GET carries neither the field nor the secret string.
	getResp, err := client.Get(bffBase + "/t/" + slug + "/api/clients/" + created.Client.ID)
	if err != nil {
		t.Fatalf("GET client: %v", err)
	}
	defer getResp.Body.Close()
	getBody, _ := readBody(getResp.Body)
	if getResp.StatusCode != http.StatusOK {
		t.Fatalf("GET client: status = %d, want 200, body=%s", getResp.StatusCode, getBody)
	}
	if strings.Contains(getBody, "secret") {
		t.Errorf("GET client body mentions \"secret\": %s", getBody)
	}
	if strings.Contains(getBody, secret) {
		t.Errorf("GET client body leaks the plaintext secret: %s", getBody)
	}
	assertNoTokenLeak(t, "GET client", getBody)
}

func TestAdminClients_RegenerateSecret_IssuesNewSecretAndInvalidatesOld(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "client-regen-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	status, created, createBodyStr := createClient(t, client, bffBase, slug, false)
	if status != http.StatusCreated {
		t.Fatalf("POST clients: status = %d, want 201, body=%s", status, createBodyStr)
	}
	oldSecret := *created.ClientSecret

	// The original secret works before rotation.
	if code := obtainClientCredentialsToken(t, stack, created.Client.ClientID, oldSecret); code != http.StatusOK {
		t.Fatalf("token request with the original secret (pre-rotation): status = %d, want 200", code)
	}

	regenResp := doWithCSRF(t, client, bffBase, http.MethodPost,
		bffBase+"/t/"+slug+"/api/clients/"+created.Client.ID+"/client-secret", "", nil)
	defer regenResp.Body.Close()
	regenBodyStr, _ := readBody(regenResp.Body)
	if regenResp.StatusCode != http.StatusOK {
		t.Fatalf("POST client-secret (regenerate): status = %d, want 200, body=%s", regenResp.StatusCode, regenBodyStr)
	}
	var regenerated clientCreatedBody
	if err := json.Unmarshal([]byte(regenBodyStr), &regenerated); err != nil {
		t.Fatalf("decode regenerate response: %v (body=%s)", err, regenBodyStr)
	}
	if regenerated.ClientSecret == nil || *regenerated.ClientSecret == "" {
		t.Fatalf("regenerate response: clientSecret missing/blank, body=%s", regenBodyStr)
	}
	newSecret := *regenerated.ClientSecret
	if newSecret == oldSecret {
		t.Fatalf("regenerated secret equals the original — not a genuine rotation")
	}
	assertNoTokenLeakExceptSecret(t, "POST client-secret", regenBodyStr, newSecret)

	// The missing recovery path this stage adds, proven against the real token endpoint:
	// the OLD secret now fails, the NEW secret succeeds.
	if code := obtainClientCredentialsToken(t, stack, created.Client.ClientID, oldSecret); code == http.StatusOK {
		t.Errorf("token request with the OLD secret after rotation: status = %d, want a rejection (401)", code)
	}
	if code := obtainClientCredentialsToken(t, stack, created.Client.ClientID, newSecret); code != http.StatusOK {
		t.Errorf("token request with the NEW secret after rotation: status = %d, want 200", code)
	}
}

func TestAdminClients_RegenerateSecret_PublicClientRejected(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "client-public-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	status, created, createBodyStr := createClient(t, client, bffBase, slug, true)
	if status != http.StatusCreated {
		t.Fatalf("POST clients (public): status = %d, want 201, body=%s", status, createBodyStr)
	}
	if created.ClientSecret != nil {
		t.Fatalf("public client create response carries a clientSecret, want none: body=%s", createBodyStr)
	}

	regenResp := doWithCSRF(t, client, bffBase, http.MethodPost,
		bffBase+"/t/"+slug+"/api/clients/"+created.Client.ID+"/client-secret", "", nil)
	defer regenResp.Body.Close()
	regenBodyStr, _ := readBody(regenResp.Body)
	if regenResp.StatusCode != http.StatusConflict {
		t.Fatalf("POST client-secret (public client): status = %d, want 409, body=%s", regenResp.StatusCode, regenBodyStr)
	}
	var regenErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(regenBodyStr), &regenErr); err != nil {
		t.Fatalf("decode regenerate error: %v (body=%s)", err, regenBodyStr)
	}
	if regenErr.Error != "client.public_no_secret" {
		t.Errorf("regenerate error code = %q, want client.public_no_secret (must not be flattened)", regenErr.Error)
	}
}
