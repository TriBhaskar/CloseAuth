package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"closeauth-frontend/internal/testsupport"
)

// Stage UI-3c: live, Docker-gated proofs that the resource-server and scope
// catalog surface works end to end through the REAL backend AND a REAL BFF.
// Unlike clients, the backend exposes full CRUD here, so these tests are a
// straightforward relay proof: every response's shape, domain conflict code,
// and immutability behavior must survive the hop through
// writeAdminAPIResult/PatchJSON unchanged. Reuses admin_users_test.go's
// harness helpers (newTenantWithAdmin, establishAdminSession, doWithCSRF,
// pageViewBody, adminAPIErrorBody) and admin_console_test.go's
// (newAdminConsoleServer, newBrowserLikeClient, readBody) verbatim.

type resourceServerViewBody struct {
	ID                 string `json:"id"`
	Slug               string `json:"slug"`
	Name               string `json:"name"`
	AudienceIdentifier string `json:"audienceIdentifier"`
	AutoCreated        bool   `json:"autoCreated"`
}

type scopeViewBody struct {
	ID               string `json:"id"`
	ResourceServerID string `json:"resourceServerId"`
	ScopeName        string `json:"scopeName"`
	Description      string `json:"description"`
	IsDefault        bool   `json:"isDefault"`
	RequiresConsent  bool   `json:"requiresConsent"`
}

func createResourceServer(t *testing.T, client *http.Client, bffBase, slug, rsSlug, name, audience string) (int, resourceServerViewBody, string) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"slug":               rsSlug,
		"name":               name,
		"audienceIdentifier": audience,
	})
	resp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers", "application/json", bytes.NewReader(body))
	defer resp.Body.Close()
	bodyStr, _ := readBody(resp.Body)
	var rs resourceServerViewBody
	if resp.StatusCode == http.StatusCreated {
		if err := json.Unmarshal([]byte(bodyStr), &rs); err != nil {
			t.Fatalf("decode created resource server: %v (body=%s)", err, bodyStr)
		}
	}
	return resp.StatusCode, rs, bodyStr
}

func TestAdminResourceServers_CRUDRoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "rs-crud-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	// List (may already contain nothing standalone yet — just prove it's a real, decodable page).
	listResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers?page=0&size=100")
	if err != nil {
		t.Fatalf("GET resource-servers: %v", err)
	}
	defer listResp.Body.Close()
	listBody, _ := readBody(listResp.Body)
	if listResp.StatusCode != http.StatusOK {
		t.Fatalf("GET resource-servers: status = %d, want 200, body=%s", listResp.StatusCode, listBody)
	}
	var beforePage pageViewBody[resourceServerViewBody]
	if err := json.Unmarshal([]byte(listBody), &beforePage); err != nil {
		t.Fatalf("decode resource-servers list: %v (body=%s)", err, listBody)
	}

	// Create.
	suffix := testsupport.Suffix()
	rsSlug := "rs-" + suffix
	audience := "https://rs-crud." + suffix + ".example.test"
	status, created, createBodyStr := createResourceServer(t, client, bffBase, slug, rsSlug, "RS CRUD Test", audience)
	if status != http.StatusCreated {
		t.Fatalf("POST resource-servers: status = %d, want 201, body=%s", status, createBodyStr)
	}
	if created.Slug != rsSlug || created.AudienceIdentifier != audience {
		t.Errorf("created RS = %+v, want slug=%q audience=%q", created, rsSlug, audience)
	}
	if created.AutoCreated {
		t.Errorf("standalone-created RS reports autoCreated=true, want false")
	}
	assertNoTokenLeak(t, "POST resource-servers", createBodyStr)

	// It shows up in a real list call (side effect, not just the status code).
	listAfterResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers?page=0&size=100")
	if err != nil {
		t.Fatalf("GET resource-servers (after create): %v", err)
	}
	defer listAfterResp.Body.Close()
	listAfterBody, _ := readBody(listAfterResp.Body)
	var afterPage pageViewBody[resourceServerViewBody]
	if err := json.Unmarshal([]byte(listAfterBody), &afterPage); err != nil {
		t.Fatalf("decode resource-servers list (after create): %v (body=%s)", err, listAfterBody)
	}
	if afterPage.TotalElements != beforePage.TotalElements+1 {
		t.Errorf("totalElements after create = %d, want %d", afterPage.TotalElements, beforePage.TotalElements+1)
	}

	// Get.
	getResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers/" + created.ID)
	if err != nil {
		t.Fatalf("GET resource-server: %v", err)
	}
	defer getResp.Body.Close()
	getBody, _ := readBody(getResp.Body)
	if getResp.StatusCode != http.StatusOK {
		t.Fatalf("GET resource-server: status = %d, want 200, body=%s", getResp.StatusCode, getBody)
	}

	// Patch: rename + re-slug (both mutable).
	newSlug := "rs-renamed-" + suffix
	patchBody, _ := json.Marshal(map[string]any{"name": "RS CRUD Test Renamed", "slug": newSlug})
	patchResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/resource-servers/"+created.ID,
		"application/json", bytes.NewReader(patchBody))
	defer patchResp.Body.Close()
	patchBodyStr, _ := readBody(patchResp.Body)
	if patchResp.StatusCode != http.StatusOK {
		t.Fatalf("PATCH resource-server: status = %d, want 200, body=%s", patchResp.StatusCode, patchBodyStr)
	}
	var patched resourceServerViewBody
	if err := json.Unmarshal([]byte(patchBodyStr), &patched); err != nil {
		t.Fatalf("decode patched resource server: %v (body=%s)", err, patchBodyStr)
	}
	if patched.Name != "RS CRUD Test Renamed" || patched.Slug != newSlug {
		t.Errorf("patched RS = %+v, want name=%q slug=%q", patched, "RS CRUD Test Renamed", newSlug)
	}
	if patched.AudienceIdentifier != audience {
		t.Errorf("patched RS audienceIdentifier = %q, want unchanged %q (audience is immutable)", patched.AudienceIdentifier, audience)
	}

	// Delete -> 204, then re-get -> 404.
	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/resource-servers/"+created.ID, "", nil)
	defer deleteResp.Body.Close()
	if deleteResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(deleteResp.Body)
		t.Fatalf("DELETE resource-server: status = %d, want 204, body=%s", deleteResp.StatusCode, body)
	}
	regetResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers/" + created.ID)
	if err != nil {
		t.Fatalf("GET resource-server (after delete): %v", err)
	}
	defer regetResp.Body.Close()
	if regetResp.StatusCode != http.StatusNotFound {
		body, _ := readBody(regetResp.Body)
		t.Fatalf("GET resource-server (after delete): status = %d, want 404, body=%s", regetResp.StatusCode, body)
	}
}

func TestAdminResourceServers_AutoCreatedAppearsAfterClientCreate_AndCannotBeDeleted(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "rs-auto-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	createStatus, createdClient, createClientBody := createClient(t, client, bffBase, slug, false)
	if createStatus != http.StatusCreated {
		t.Fatalf("POST clients: status = %d, want 201, body=%s", createStatus, createClientBody)
	}

	listResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers?page=0&size=100")
	if err != nil {
		t.Fatalf("GET resource-servers: %v", err)
	}
	defer listResp.Body.Close()
	listBody, _ := readBody(listResp.Body)
	var page pageViewBody[resourceServerViewBody]
	if err := json.Unmarshal([]byte(listBody), &page); err != nil {
		t.Fatalf("decode resource-servers: %v (body=%s)", err, listBody)
	}
	// autoCreateForClient (ResourceServerService.java) sets the RS name to the
	// client's clientName verbatim — an exact match, not a heuristic.
	var autoRS *resourceServerViewBody
	for i, rs := range page.Items {
		if rs.AutoCreated && rs.Name == createdClient.Client.ClientName {
			autoRS = &page.Items[i]
		}
	}
	if autoRS == nil {
		t.Fatalf("no auto-created resource server named %q found in %v", createdClient.Client.ClientName, page.Items)
	}

	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/resource-servers/"+autoRS.ID, "", nil)
	defer deleteResp.Body.Close()
	deleteBody, _ := readBody(deleteResp.Body)
	if deleteResp.StatusCode != http.StatusConflict {
		t.Fatalf("DELETE auto-created resource-server: status = %d, want 409, body=%s", deleteResp.StatusCode, deleteBody)
	}
	var deleteErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(deleteBody), &deleteErr); err != nil {
		t.Fatalf("decode delete error: %v (body=%s)", err, deleteBody)
	}
	if deleteErr.Error != "resource_server.deletion_not_allowed" {
		t.Errorf("delete error code = %q, want resource_server.deletion_not_allowed", deleteErr.Error)
	}
}

func TestAdminResourceServers_ConflictCodesSurviveTheBFF(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "rs-conflict-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suffix := testsupport.Suffix()
	rsSlug := "rs-conflict-" + suffix
	audience := "https://rs-conflict." + suffix + ".example.test"
	status, first, firstBody := createResourceServer(t, client, bffBase, slug, rsSlug, "First", audience)
	if status != http.StatusCreated {
		t.Fatalf("POST resource-servers (first): status = %d, want 201, body=%s", status, firstBody)
	}

	// Duplicate slug within the same tenant -> 409 resource_server.slug_conflict.
	slugConflictStatus, _, slugConflictBody := createResourceServer(t, client, bffBase, slug, rsSlug, "Second", "https://different."+suffix+".example.test")
	if slugConflictStatus != http.StatusConflict {
		t.Fatalf("POST resource-servers (slug conflict): status = %d, want 409, body=%s", slugConflictStatus, slugConflictBody)
	}
	var slugErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(slugConflictBody), &slugErr); err != nil {
		t.Fatalf("decode slug conflict error: %v (body=%s)", err, slugConflictBody)
	}
	if slugErr.Error != "resource_server.slug_conflict" {
		t.Errorf("slug conflict error code = %q, want resource_server.slug_conflict", slugErr.Error)
	}

	// Duplicate audience (globally unique) -> 409 resource_server.audience_conflict.
	audienceConflictStatus, _, audienceConflictBody := createResourceServer(t, client, bffBase, slug, "rs-other-"+suffix, "Other", audience)
	if audienceConflictStatus != http.StatusConflict {
		t.Fatalf("POST resource-servers (audience conflict): status = %d, want 409, body=%s", audienceConflictStatus, audienceConflictBody)
	}
	var audienceErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(audienceConflictBody), &audienceErr); err != nil {
		t.Fatalf("decode audience conflict error: %v (body=%s)", err, audienceConflictBody)
	}
	if audienceErr.Error != "resource_server.audience_conflict" {
		t.Errorf("audience conflict error code = %q, want resource_server.audience_conflict", audienceErr.Error)
	}

	// Duplicate scope name within the same RS -> 409 resource_server.scope_conflict.
	scopeBody, _ := json.Marshal(map[string]any{"scopeName": "read", "description": "d", "isDefault": false, "requiresConsent": false})
	firstScopeResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+first.ID+"/scopes",
		"application/json", bytes.NewReader(scopeBody))
	defer firstScopeResp.Body.Close()
	firstScopeBodyStr, _ := readBody(firstScopeResp.Body)
	if firstScopeResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST scopes (first): status = %d, want 201, body=%s", firstScopeResp.StatusCode, firstScopeBodyStr)
	}

	dupScopeResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+first.ID+"/scopes",
		"application/json", bytes.NewReader(scopeBody))
	defer dupScopeResp.Body.Close()
	dupScopeBodyStr, _ := readBody(dupScopeResp.Body)
	if dupScopeResp.StatusCode != http.StatusConflict {
		t.Fatalf("POST scopes (duplicate): status = %d, want 409, body=%s", dupScopeResp.StatusCode, dupScopeBodyStr)
	}
	var scopeErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(dupScopeBodyStr), &scopeErr); err != nil {
		t.Fatalf("decode scope conflict error: %v (body=%s)", err, dupScopeBodyStr)
	}
	if scopeErr.Error != "resource_server.scope_conflict" {
		t.Errorf("scope conflict error code = %q, want resource_server.scope_conflict", scopeErr.Error)
	}
}

func TestAdminScopes_CRUDAndImmutability(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "scope-crud-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suffix := testsupport.Suffix()
	status, rs, rsBody := createResourceServer(t, client, bffBase, slug, "rs-scope-"+suffix, "Scope RS", "https://rs-scope."+suffix+".example.test")
	if status != http.StatusCreated {
		t.Fatalf("POST resource-servers: status = %d, want 201, body=%s", status, rsBody)
	}

	// Add.
	addBody, _ := json.Marshal(map[string]any{"scopeName": "widgets:read", "description": "Read widgets", "isDefault": true, "requiresConsent": false})
	addResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/scopes",
		"application/json", bytes.NewReader(addBody))
	defer addResp.Body.Close()
	addBodyStr, _ := readBody(addResp.Body)
	if addResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST scopes: status = %d, want 201, body=%s", addResp.StatusCode, addBodyStr)
	}
	var added scopeViewBody
	if err := json.Unmarshal([]byte(addBodyStr), &added); err != nil {
		t.Fatalf("decode added scope: %v (body=%s)", err, addBodyStr)
	}
	if added.ScopeName != "widgets:read" || !added.IsDefault || added.RequiresConsent {
		t.Errorf("added scope = %+v, want scopeName=widgets:read isDefault=true requiresConsent=false", added)
	}

	// List.
	listResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers/" + rs.ID + "/scopes?page=0&size=100")
	if err != nil {
		t.Fatalf("GET scopes: %v", err)
	}
	defer listResp.Body.Close()
	listBody, _ := readBody(listResp.Body)
	if listResp.StatusCode != http.StatusOK {
		t.Fatalf("GET scopes: status = %d, want 200, body=%s", listResp.StatusCode, listBody)
	}
	var listPage pageViewBody[scopeViewBody]
	if err := json.Unmarshal([]byte(listBody), &listPage); err != nil {
		t.Fatalf("decode scopes list: %v (body=%s)", err, listBody)
	}
	if listPage.TotalElements < 1 {
		t.Errorf("scopes list totalElements = %d, want >= 1", listPage.TotalElements)
	}

	// Patch: the three mutable fields, all sent together — PATCH is a full
	// replacement of the mutable set (updateScope sets all three
	// unconditionally), so an edit form must always send all three.
	patchBody, _ := json.Marshal(map[string]any{"description": "Updated description", "isDefault": false, "requiresConsent": true})
	patchResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/scopes/"+added.ID,
		"application/json", bytes.NewReader(patchBody))
	defer patchResp.Body.Close()
	patchBodyStr, _ := readBody(patchResp.Body)
	if patchResp.StatusCode != http.StatusOK {
		t.Fatalf("PATCH scope: status = %d, want 200, body=%s", patchResp.StatusCode, patchBodyStr)
	}
	var patched scopeViewBody
	if err := json.Unmarshal([]byte(patchBodyStr), &patched); err != nil {
		t.Fatalf("decode patched scope: %v (body=%s)", err, patchBodyStr)
	}
	if patched.Description != "Updated description" || patched.IsDefault || !patched.RequiresConsent {
		t.Errorf("patched scope = %+v, want description=%q isDefault=false requiresConsent=true", patched, "Updated description")
	}
	if patched.ScopeName != "widgets:read" {
		t.Errorf("patched scope scopeName = %q, want unchanged %q (scopeName is immutable)", patched.ScopeName, "widgets:read")
	}

	// scopeName is absent from UpdateScopeCommand entirely — a submitted
	// value there is silently ignored (structural immutability), never
	// rejected. Prove the ignore, not an error.
	attemptRenameBody, _ := json.Marshal(map[string]any{
		"scopeName": "widgets:renamed", "description": "Updated description", "isDefault": false, "requiresConsent": true,
	})
	attemptRenameResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/scopes/"+added.ID,
		"application/json", bytes.NewReader(attemptRenameBody))
	defer attemptRenameResp.Body.Close()
	attemptRenameBodyStr, _ := readBody(attemptRenameResp.Body)
	if attemptRenameResp.StatusCode != http.StatusOK {
		t.Fatalf("PATCH scope (attempted rename): status = %d, want 200 (silently ignored, not rejected), body=%s",
			attemptRenameResp.StatusCode, attemptRenameBodyStr)
	}
	var afterAttemptedRename scopeViewBody
	if err := json.Unmarshal([]byte(attemptRenameBodyStr), &afterAttemptedRename); err != nil {
		t.Fatalf("decode scope after attempted rename: %v (body=%s)", err, attemptRenameBodyStr)
	}
	if afterAttemptedRename.ScopeName != "widgets:read" {
		t.Errorf("scopeName after attempted rename = %q, want unchanged %q", afterAttemptedRename.ScopeName, "widgets:read")
	}

	// Delete -> 204.
	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/scopes/"+added.ID, "", nil)
	defer deleteResp.Body.Close()
	if deleteResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(deleteResp.Body)
		t.Fatalf("DELETE scope: status = %d, want 204, body=%s", deleteResp.StatusCode, body)
	}
}
