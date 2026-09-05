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
	ID              string   `json:"id"`
	ClientID        string   `json:"clientId"`
	ClientName      string   `json:"clientName"`
	PublicClient    bool     `json:"publicClient"`
	Scopes          []string `json:"scopes"`
	RedirectURIs    []string `json:"redirectUris"`
	RequireProofKey bool     `json:"requireProofKey"`
	Trusted         bool     `json:"trusted"`
	PlatformManaged bool     `json:"platformManaged"`
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

// Client update/delete: PATCH replaces the mutable field set, then a real
// re-GET proves persistence (not just an echoed response); DELETE removes
// the client and its 1:1 auto-created resource server, and the
// platform-managed admin-console client refuses both. Same shape as
// TestAdminResourceServers_FullCRUDLifecycle in admin_resource_servers_test.go.
func TestAdminClients_Update_ReplacesMutableFieldsAndPersists(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "client-update-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	status, created, createBodyStr := createClient(t, client, bffBase, slug, false)
	if status != http.StatusCreated {
		t.Fatalf("POST clients: status = %d, want 201, body=%s", status, createBodyStr)
	}

	patchBody, _ := json.Marshal(map[string]any{
		"clientName":      "Renamed via PATCH",
		"scopes":          []string{"read"},
		"redirectUris":    []string{},
		"postLogoutUris":  []string{},
		"requireProofKey": true,
		"trusted":         false,
	})
	patchResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/clients/"+created.Client.ID,
		"application/json", bytes.NewReader(patchBody))
	defer patchResp.Body.Close()
	patchBodyStr, _ := readBody(patchResp.Body)
	if patchResp.StatusCode != http.StatusOK {
		t.Fatalf("PATCH client: status = %d, want 200, body=%s", patchResp.StatusCode, patchBodyStr)
	}
	assertNoTokenLeak(t, "PATCH client", patchBodyStr)

	// Re-GET (not just the PATCH echo) to prove the change actually persisted.
	getResp, err := client.Get(bffBase + "/t/" + slug + "/api/clients/" + created.Client.ID)
	if err != nil {
		t.Fatalf("GET client (after patch): %v", err)
	}
	defer getResp.Body.Close()
	getBodyStr, _ := readBody(getResp.Body)
	var reread clientViewBody
	if err := json.Unmarshal([]byte(getBodyStr), &reread); err != nil {
		t.Fatalf("decode re-read client: %v (body=%s)", err, getBodyStr)
	}
	if reread.ClientName != "Renamed via PATCH" {
		t.Errorf("re-read clientName = %q, want %q", reread.ClientName, "Renamed via PATCH")
	}
	if len(reread.Scopes) != 1 || reread.Scopes[0] != "read" {
		t.Errorf("re-read scopes = %v, want [read]", reread.Scopes)
	}
	if !reread.RequireProofKey {
		t.Errorf("re-read requireProofKey = false, want true")
	}
	if reread.Trusted {
		t.Errorf("re-read trusted = true, want false")
	}
	// publicClient has no field on the update command at all — must survive untouched.
	if reread.PublicClient {
		t.Errorf("re-read publicClient = true, want false (unchanged — PATCH cannot flip it)")
	}
}

func TestAdminClients_Delete_RemovesClientAndItsAutoCreatedResourceServer(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "client-delete-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	status, created, createBodyStr := createClient(t, client, bffBase, slug, false)
	if status != http.StatusCreated {
		t.Fatalf("POST clients: status = %d, want 201, body=%s", status, createBodyStr)
	}

	listResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers?page=0&size=100")
	if err != nil {
		t.Fatalf("GET resource-servers: %v", err)
	}
	defer listResp.Body.Close()
	listBody, _ := readBody(listResp.Body)
	var rsPage pageViewBody[resourceServerViewBody]
	if err := json.Unmarshal([]byte(listBody), &rsPage); err != nil {
		t.Fatalf("decode resource-servers: %v (body=%s)", err, listBody)
	}
	var autoRS *resourceServerViewBody
	for i, rs := range rsPage.Items {
		if rs.AutoCreated && rs.Name == created.Client.ClientName {
			autoRS = &rsPage.Items[i]
		}
	}
	if autoRS == nil {
		t.Fatalf("no auto-created resource server named %q found in %v", created.Client.ClientName, rsPage.Items)
	}

	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/clients/"+created.Client.ID, "", nil)
	defer deleteResp.Body.Close()
	if deleteResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(deleteResp.Body)
		t.Fatalf("DELETE client: status = %d, want 204, body=%s", deleteResp.StatusCode, body)
	}

	regetResp, err := client.Get(bffBase + "/t/" + slug + "/api/clients/" + created.Client.ID)
	if err != nil {
		t.Fatalf("GET client (after delete): %v", err)
	}
	defer regetResp.Body.Close()
	if regetResp.StatusCode != http.StatusNotFound {
		body, _ := readBody(regetResp.Body)
		t.Fatalf("GET client (after delete): status = %d, want 404, body=%s", regetResp.StatusCode, body)
	}

	regetRSResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers/" + autoRS.ID)
	if err != nil {
		t.Fatalf("GET resource-server (after client delete): %v", err)
	}
	defer regetRSResp.Body.Close()
	if regetRSResp.StatusCode != http.StatusNotFound {
		body, _ := readBody(regetRSResp.Body)
		t.Fatalf("GET auto-created resource-server (after client delete): status = %d, want 404, body=%s",
			regetRSResp.StatusCode, body)
	}
}

func TestAdminClients_PlatformManagedAdminConsoleClient_RefusesUpdateAndDelete(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "client-platform-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	listResp, err := client.Get(bffBase + "/t/" + slug + "/api/clients?page=0&size=100")
	if err != nil {
		t.Fatalf("GET clients: %v", err)
	}
	defer listResp.Body.Close()
	listBody, _ := readBody(listResp.Body)
	var page pageViewBody[clientViewBody]
	if err := json.Unmarshal([]byte(listBody), &page); err != nil {
		t.Fatalf("decode clients: %v (body=%s)", err, listBody)
	}
	var consoleClient *clientViewBody
	for i, c := range page.Items {
		if c.PlatformManaged {
			consoleClient = &page.Items[i]
		}
	}
	if consoleClient == nil {
		t.Fatalf("no platform-managed (admin-console) client found in %v", page.Items)
	}

	patchBody, _ := json.Marshal(map[string]any{
		"clientName": "Hijacked", "scopes": []string{}, "redirectUris": []string{}, "postLogoutUris": []string{},
		"requireProofKey": true, "trusted": true,
	})
	patchResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/clients/"+consoleClient.ID,
		"application/json", bytes.NewReader(patchBody))
	defer patchResp.Body.Close()
	patchBodyStr, _ := readBody(patchResp.Body)
	if patchResp.StatusCode != http.StatusConflict {
		t.Fatalf("PATCH admin-console client: status = %d, want 409, body=%s", patchResp.StatusCode, patchBodyStr)
	}
	var patchErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(patchBodyStr), &patchErr); err != nil {
		t.Fatalf("decode patch error: %v (body=%s)", err, patchBodyStr)
	}
	if patchErr.Error != "client.platform_managed" {
		t.Errorf("PATCH error code = %q, want client.platform_managed", patchErr.Error)
	}

	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/clients/"+consoleClient.ID, "", nil)
	defer deleteResp.Body.Close()
	deleteBodyStr, _ := readBody(deleteResp.Body)
	if deleteResp.StatusCode != http.StatusConflict {
		t.Fatalf("DELETE admin-console client: status = %d, want 409, body=%s", deleteResp.StatusCode, deleteBodyStr)
	}
	var deleteErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(deleteBodyStr), &deleteErr); err != nil {
		t.Fatalf("decode delete error: %v (body=%s)", err, deleteBodyStr)
	}
	if deleteErr.Error != "client.platform_managed" {
		t.Errorf("DELETE error code = %q, want client.platform_managed", deleteErr.Error)
	}
}
