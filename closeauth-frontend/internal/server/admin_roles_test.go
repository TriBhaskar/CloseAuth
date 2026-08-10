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

// Stage UI-3d: live, Docker-gated proofs that the tenant-role and
// application-role surfaces work end to end through the REAL backend AND a
// REAL BFF. Reuses admin_users_test.go's harness helpers (newTenantWithAdmin,
// establishAdminSession, doWithCSRF, pageViewBody, adminAPIErrorBody,
// tenantRoleViewBody) and admin_console_test.go's (newAdminConsoleServer,
// newBrowserLikeClient, readBody, assertNoTokenLeak) verbatim, plus
// admin_resource_servers_test.go's createResourceServer.
//
// The last-admin 409 (tenant_role.last_admin) is already proven by
// TestAdminUsers_LastAdmin_RevokeAndSuspendReturn409WithCodeIntact
// (admin_users_test.go) — not duplicated here.

type fullTenantRoleViewBody struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	IsDefault   bool   `json:"isDefault"`
	IsSystem    bool   `json:"isSystem"`
}

type applicationRoleViewBody struct {
	ID               string `json:"id"`
	ResourceServerID string `json:"resourceServerId"`
	Name             string `json:"name"`
	Description      string `json:"description"`
	IsDefault        bool   `json:"isDefault"`
	IsSystem         bool   `json:"isSystem"`
}

// ---- tenant-role CRUD -------------------------------------------------

func TestAdminTenantRoles_CRUDRoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "role-crud-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suffix := testsupport.Suffix()
	name := "CUSTOM_ROLE_" + suffix

	createBody, _ := json.Marshal(map[string]any{"name": name, "description": "Custom", "isDefault": false})
	createResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/roles", "application/json", bytes.NewReader(createBody))
	defer createResp.Body.Close()
	createBodyStr, _ := readBody(createResp.Body)
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST roles: status = %d, want 201, body=%s", createResp.StatusCode, createBodyStr)
	}
	var created fullTenantRoleViewBody
	if err := json.Unmarshal([]byte(createBodyStr), &created); err != nil {
		t.Fatalf("decode created role: %v (body=%s)", err, createBodyStr)
	}
	if created.Name != name || created.IsSystem {
		t.Errorf("created role = %+v, want name=%q isSystem=false", created, name)
	}
	assertNoTokenLeak(t, "POST roles", createBodyStr)

	// Appears in a real list call.
	listResp, err := client.Get(bffBase + "/t/" + slug + "/api/roles?page=0&size=100")
	if err != nil {
		t.Fatalf("GET roles: %v", err)
	}
	defer listResp.Body.Close()
	listBody, _ := readBody(listResp.Body)
	var page pageViewBody[fullTenantRoleViewBody]
	if err := json.Unmarshal([]byte(listBody), &page); err != nil {
		t.Fatalf("decode roles list: %v (body=%s)", err, listBody)
	}
	found := false
	for _, r := range page.Items {
		if r.ID == created.ID {
			found = true
		}
	}
	if !found {
		t.Errorf("created role %s not present in the list after creation", created.ID)
	}

	// Get.
	getResp, err := client.Get(bffBase + "/t/" + slug + "/api/roles/" + created.ID)
	if err != nil {
		t.Fatalf("GET role: %v", err)
	}
	defer getResp.Body.Close()
	if getResp.StatusCode != http.StatusOK {
		body, _ := readBody(getResp.Body)
		t.Fatalf("GET role: status = %d, want 200, body=%s", getResp.StatusCode, body)
	}

	// Patch: description + isDefault, both sent together (full replacement).
	patchBody, _ := json.Marshal(map[string]any{"description": "Updated", "isDefault": true})
	patchResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/roles/"+created.ID, "application/json", bytes.NewReader(patchBody))
	defer patchResp.Body.Close()
	patchBodyStr, _ := readBody(patchResp.Body)
	if patchResp.StatusCode != http.StatusOK {
		t.Fatalf("PATCH role: status = %d, want 200, body=%s", patchResp.StatusCode, patchBodyStr)
	}
	var patched fullTenantRoleViewBody
	if err := json.Unmarshal([]byte(patchBodyStr), &patched); err != nil {
		t.Fatalf("decode patched role: %v (body=%s)", err, patchBodyStr)
	}
	if patched.Description != "Updated" || !patched.IsDefault {
		t.Errorf("patched role = %+v, want description=Updated isDefault=true", patched)
	}

	// Delete -> 204, then re-get -> 404.
	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/roles/"+created.ID, "", nil)
	defer deleteResp.Body.Close()
	if deleteResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(deleteResp.Body)
		t.Fatalf("DELETE role: status = %d, want 204, body=%s", deleteResp.StatusCode, body)
	}
	regetResp, err := client.Get(bffBase + "/t/" + slug + "/api/roles/" + created.ID)
	if err != nil {
		t.Fatalf("GET role (after delete): %v", err)
	}
	defer regetResp.Body.Close()
	if regetResp.StatusCode != http.StatusNotFound {
		body, _ := readBody(regetResp.Body)
		t.Fatalf("GET role (after delete): status = %d, want 404, body=%s", regetResp.StatusCode, body)
	}
}

// TestAdminTenantRoles_SystemRoleIsImmutableAndSessionSurvives is the
// admin_api_result.go 403-split proof: a domain 403 (role.system_immutable)
// must be forwarded as an ordinary error, NOT treated like access_denied —
// which would clear the session and force a re-login. A follow-up call in
// the SAME session must still succeed.
func TestAdminTenantRoles_SystemRoleIsImmutableAndSessionSurvives(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "role-system-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	rolesResp, err := client.Get(bffBase + "/t/" + slug + "/api/roles?page=0&size=100")
	if err != nil {
		t.Fatalf("GET roles: %v", err)
	}
	defer rolesResp.Body.Close()
	rolesBody, _ := readBody(rolesResp.Body)
	var rolesPage pageViewBody[tenantRoleViewBody]
	if err := json.Unmarshal([]byte(rolesBody), &rolesPage); err != nil {
		t.Fatalf("decode roles: %v (body=%s)", err, rolesBody)
	}
	var tenantAdminRoleID string
	for _, r := range rolesPage.Items {
		if r.Name == "TENANT_ADMIN" {
			tenantAdminRoleID = r.ID
		}
	}
	if tenantAdminRoleID == "" {
		t.Fatalf("TENANT_ADMIN not found in roles: %v", rolesPage.Items)
	}

	// PATCH a system role -> 403 role.system_immutable, not 409.
	patchBody, _ := json.Marshal(map[string]any{"description": "hostile edit", "isDefault": false})
	patchResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/roles/"+tenantAdminRoleID, "application/json", bytes.NewReader(patchBody))
	defer patchResp.Body.Close()
	patchBodyStr, _ := readBody(patchResp.Body)
	if patchResp.StatusCode != http.StatusForbidden {
		t.Fatalf("PATCH system role: status = %d, want 403, body=%s", patchResp.StatusCode, patchBodyStr)
	}
	var patchErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(patchBodyStr), &patchErr); err != nil {
		t.Fatalf("decode patch error: %v (body=%s)", err, patchBodyStr)
	}
	if patchErr.Error != "role.system_immutable" {
		t.Errorf("PATCH system role: error code = %q, want role.system_immutable", patchErr.Error)
	}

	// DELETE a system role -> same 403 code.
	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/roles/"+tenantAdminRoleID, "", nil)
	defer deleteResp.Body.Close()
	deleteBodyStr, _ := readBody(deleteResp.Body)
	if deleteResp.StatusCode != http.StatusForbidden {
		t.Fatalf("DELETE system role: status = %d, want 403, body=%s", deleteResp.StatusCode, deleteBodyStr)
	}
	var deleteErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(deleteBodyStr), &deleteErr); err != nil {
		t.Fatalf("decode delete error: %v (body=%s)", err, deleteBodyStr)
	}
	if deleteErr.Error != "role.system_immutable" {
		t.Errorf("DELETE system role: error code = %q, want role.system_immutable", deleteErr.Error)
	}

	// The session must still be live: a following call succeeds, proving the
	// 403 was forwarded as an ordinary error rather than treated the way
	// access_denied is (session clear + SetAdminDenied).
	followUp, err := client.Get(bffBase + "/t/" + slug + "/api/roles?page=0&size=1")
	if err != nil {
		t.Fatalf("GET roles (after system-role 403): %v", err)
	}
	defer followUp.Body.Close()
	followUpBody, _ := readBody(followUp.Body)
	if followUp.StatusCode != http.StatusOK {
		t.Fatalf("GET roles (after system-role 403): status = %d, want 200 (session must survive a domain 403), body=%s",
			followUp.StatusCode, followUpBody)
	}
}

// ---- application-role CRUD + scope bundling ----------------------------

func TestAdminApplicationRoles_CRUDRoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "app-role-crud-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suffix := testsupport.Suffix()
	status, rs, rsBody := createResourceServer(t, client, bffBase, slug, "rs-role-"+suffix, "Role RS", "https://rs-role."+suffix+".example.test")
	if status != http.StatusCreated {
		t.Fatalf("POST resource-servers: status = %d, want 201, body=%s", status, rsBody)
	}

	name := "READER_" + suffix
	createBody, _ := json.Marshal(map[string]any{"name": name, "description": "Reader", "isDefault": false})
	createResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/roles", "application/json", bytes.NewReader(createBody))
	defer createResp.Body.Close()
	createBodyStr, _ := readBody(createResp.Body)
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST application roles: status = %d, want 201, body=%s", createResp.StatusCode, createBodyStr)
	}
	var created applicationRoleViewBody
	if err := json.Unmarshal([]byte(createBodyStr), &created); err != nil {
		t.Fatalf("decode created application role: %v (body=%s)", err, createBodyStr)
	}
	if created.Name != name || created.IsSystem {
		t.Errorf("created application role = %+v, want name=%q isSystem=false (createApplicationRole hardcodes is_system=false)", created, name)
	}
	assertNoTokenLeak(t, "POST application roles", createBodyStr)

	// Get.
	getResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers/" + rs.ID + "/roles/" + created.ID)
	if err != nil {
		t.Fatalf("GET application role: %v", err)
	}
	defer getResp.Body.Close()
	if getResp.StatusCode != http.StatusOK {
		body, _ := readBody(getResp.Body)
		t.Fatalf("GET application role: status = %d, want 200, body=%s", getResp.StatusCode, body)
	}

	// Patch.
	patchBody, _ := json.Marshal(map[string]any{"description": "Updated", "isDefault": true})
	patchResp := doWithCSRF(t, client, bffBase, http.MethodPatch, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/roles/"+created.ID, "application/json", bytes.NewReader(patchBody))
	defer patchResp.Body.Close()
	patchBodyStr, _ := readBody(patchResp.Body)
	if patchResp.StatusCode != http.StatusOK {
		t.Fatalf("PATCH application role: status = %d, want 200, body=%s", patchResp.StatusCode, patchBodyStr)
	}
	var patched applicationRoleViewBody
	if err := json.Unmarshal([]byte(patchBodyStr), &patched); err != nil {
		t.Fatalf("decode patched application role: %v (body=%s)", err, patchBodyStr)
	}
	if patched.Description != "Updated" || !patched.IsDefault {
		t.Errorf("patched application role = %+v, want description=Updated isDefault=true", patched)
	}

	// Delete -> 204, then re-get -> 404.
	deleteResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/roles/"+created.ID, "", nil)
	defer deleteResp.Body.Close()
	if deleteResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(deleteResp.Body)
		t.Fatalf("DELETE application role: status = %d, want 204, body=%s", deleteResp.StatusCode, body)
	}
	regetResp, err := client.Get(bffBase + "/t/" + slug + "/api/resource-servers/" + rs.ID + "/roles/" + created.ID)
	if err != nil {
		t.Fatalf("GET application role (after delete): %v", err)
	}
	defer regetResp.Body.Close()
	if regetResp.StatusCode != http.StatusNotFound {
		body, _ := readBody(regetResp.Body)
		t.Fatalf("GET application role (after delete): status = %d, want 404, body=%s", regetResp.StatusCode, body)
	}
}

func TestAdminApplicationRoles_ScopeBundling_AddListRemove(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "scope-bundle-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suffix := testsupport.Suffix()
	status, rs, rsBody := createResourceServer(t, client, bffBase, slug, "rs-bundle-"+suffix, "Bundle RS", "https://rs-bundle."+suffix+".example.test")
	if status != http.StatusCreated {
		t.Fatalf("POST resource-servers: status = %d, want 201, body=%s", status, rsBody)
	}

	scopeBody, _ := json.Marshal(map[string]any{"scopeName": "read", "description": "d", "isDefault": false, "requiresConsent": false})
	scopeResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/scopes", "application/json", bytes.NewReader(scopeBody))
	defer scopeResp.Body.Close()
	scopeBodyStr, _ := readBody(scopeResp.Body)
	if scopeResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST scope: status = %d, want 201, body=%s", scopeResp.StatusCode, scopeBodyStr)
	}
	var scope scopeViewBody
	if err := json.Unmarshal([]byte(scopeBodyStr), &scope); err != nil {
		t.Fatalf("decode scope: %v (body=%s)", err, scopeBodyStr)
	}

	roleBody, _ := json.Marshal(map[string]any{"name": "BUNDLER_" + suffix, "description": "", "isDefault": false})
	roleResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/roles", "application/json", bytes.NewReader(roleBody))
	defer roleResp.Body.Close()
	roleBodyStr, _ := readBody(roleResp.Body)
	if roleResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST role: status = %d, want 201, body=%s", roleResp.StatusCode, roleBodyStr)
	}
	var role applicationRoleViewBody
	if err := json.Unmarshal([]byte(roleBodyStr), &role); err != nil {
		t.Fatalf("decode role: %v (body=%s)", err, roleBodyStr)
	}

	scopesPath := bffBase + "/t/" + slug + "/api/resource-servers/" + rs.ID + "/roles/" + role.ID + "/scopes"

	bundleContains := func() bool {
		t.Helper()
		resp, err := client.Get(scopesPath + "?page=0&size=100")
		if err != nil {
			t.Fatalf("GET role scopes: %v", err)
		}
		defer resp.Body.Close()
		body, _ := readBody(resp.Body)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET role scopes: status = %d, want 200, body=%s", resp.StatusCode, body)
		}
		var page pageViewBody[scopeViewBody]
		if err := json.Unmarshal([]byte(body), &page); err != nil {
			t.Fatalf("decode role scopes: %v (body=%s)", err, body)
		}
		for _, s := range page.Items {
			if s.ID == scope.ID {
				return true
			}
		}
		return false
	}

	if bundleContains() {
		t.Fatalf("scope already bundled before add — not a clean starting state")
	}

	addResp := doWithCSRF(t, client, bffBase, http.MethodPost, scopesPath+"/"+scope.ID, "", nil)
	defer addResp.Body.Close()
	if addResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(addResp.Body)
		t.Fatalf("POST bundle scope: status = %d, want 204, body=%s", addResp.StatusCode, body)
	}
	if !bundleContains() {
		t.Errorf("scope not reflected in role's bundle after add")
	}

	removeResp := doWithCSRF(t, client, bffBase, http.MethodDelete, scopesPath+"/"+scope.ID, "", nil)
	defer removeResp.Body.Close()
	if removeResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(removeResp.Body)
		t.Fatalf("DELETE bundle scope: status = %d, want 204, body=%s", removeResp.StatusCode, body)
	}
	if bundleContains() {
		t.Errorf("scope still reflected in role's bundle after removal")
	}
}

// TestAdminApplicationRoles_CrossRSScopeBundlingRejected400WithCodeIntact
// proves application_role.scope_rs_mismatch arrives through the BFF as a 400
// with its code intact (not flattened to bad_gateway, not reported as 409 —
// it's a VALIDATION-category error, not CONFLICT).
func TestAdminApplicationRoles_CrossRSScopeBundlingRejected400WithCodeIntact(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "cross-rs-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suffix := testsupport.Suffix()
	statusA, rsA, rsABody := createResourceServer(t, client, bffBase, slug, "rs-a-"+suffix, "RS A", "https://rs-a."+suffix+".example.test")
	if statusA != http.StatusCreated {
		t.Fatalf("POST resource-servers (A): status = %d, want 201, body=%s", statusA, rsABody)
	}
	statusB, rsB, rsBBody := createResourceServer(t, client, bffBase, slug, "rs-b-"+suffix, "RS B", "https://rs-b."+suffix+".example.test")
	if statusB != http.StatusCreated {
		t.Fatalf("POST resource-servers (B): status = %d, want 201, body=%s", statusB, rsBBody)
	}

	roleOnABody, _ := json.Marshal(map[string]any{"name": "READER_A_" + suffix, "description": "", "isDefault": false})
	roleOnAResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+rsA.ID+"/roles", "application/json", bytes.NewReader(roleOnABody))
	defer roleOnAResp.Body.Close()
	roleOnABodyStr, _ := readBody(roleOnAResp.Body)
	if roleOnAResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST role on A: status = %d, want 201, body=%s", roleOnAResp.StatusCode, roleOnABodyStr)
	}
	var roleOnA applicationRoleViewBody
	if err := json.Unmarshal([]byte(roleOnABodyStr), &roleOnA); err != nil {
		t.Fatalf("decode role on A: %v (body=%s)", err, roleOnABodyStr)
	}

	scopeOnBBody, _ := json.Marshal(map[string]any{"scopeName": "read", "description": "d", "isDefault": false, "requiresConsent": false})
	scopeOnBResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+rsB.ID+"/scopes", "application/json", bytes.NewReader(scopeOnBBody))
	defer scopeOnBResp.Body.Close()
	scopeOnBBodyStr, _ := readBody(scopeOnBResp.Body)
	if scopeOnBResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST scope on B: status = %d, want 201, body=%s", scopeOnBResp.StatusCode, scopeOnBBodyStr)
	}
	var scopeOnB scopeViewBody
	if err := json.Unmarshal([]byte(scopeOnBBodyStr), &scopeOnB); err != nil {
		t.Fatalf("decode scope on B: %v (body=%s)", err, scopeOnBBodyStr)
	}

	// Bundle RS-B's scope into RS-A's role, via RS-A's own URL — the mismatch.
	mismatchResp := doWithCSRF(t, client, bffBase, http.MethodPost,
		bffBase+"/t/"+slug+"/api/resource-servers/"+rsA.ID+"/roles/"+roleOnA.ID+"/scopes/"+scopeOnB.ID, "", nil)
	defer mismatchResp.Body.Close()
	mismatchBody, _ := readBody(mismatchResp.Body)
	if mismatchResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("POST cross-RS bundle: status = %d, want 400, body=%s", mismatchResp.StatusCode, mismatchBody)
	}
	var mismatchErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(mismatchBody), &mismatchErr); err != nil {
		t.Fatalf("decode cross-RS bundle error: %v (body=%s)", err, mismatchBody)
	}
	if mismatchErr.Error != "application_role.scope_rs_mismatch" {
		t.Errorf("cross-RS bundle: error code = %q, want application_role.scope_rs_mismatch (must not be flattened to bad_gateway)", mismatchErr.Error)
	}
	if len(mismatchErr.Errors) == 0 {
		t.Errorf("cross-RS bundle: errors map missing — must arrive intact through the RFC 7807 mapping")
	}
}

// ---- application-role assignment/revocation + held-names --------------

func TestAdminApplicationRoles_AssignThenRevoke_ReflectedInHeldRolesScopedToRS(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	tenantID, slug, adminClientID, _, adminEmail, adminPassword, platformToken := newTenantWithAdmin(t, stack, fixtures, ctx, "app-assign-admin")

	targetID, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, testsupport.Email("app-role-target"), "Target-Pw-123!")
	if err != nil {
		t.Fatalf("create target user: %v", err)
	}

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suffix := testsupport.Suffix()
	status, rs, rsBody := createResourceServer(t, client, bffBase, slug, "rs-assign-"+suffix, "Assign RS", "https://rs-assign."+suffix+".example.test")
	if status != http.StatusCreated {
		t.Fatalf("POST resource-servers: status = %d, want 201, body=%s", status, rsBody)
	}
	otherStatus, otherRS, otherRSBody := createResourceServer(t, client, bffBase, slug, "rs-other-"+suffix, "Other RS", "https://rs-other."+suffix+".example.test")
	if otherStatus != http.StatusCreated {
		t.Fatalf("POST resource-servers (other): status = %d, want 201, body=%s", otherStatus, otherRSBody)
	}

	roleName := "INVOICE_READER_" + suffix
	roleBody, _ := json.Marshal(map[string]any{"name": roleName, "description": "", "isDefault": false})
	roleResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/resource-servers/"+rs.ID+"/roles", "application/json", bytes.NewReader(roleBody))
	defer roleResp.Body.Close()
	roleBodyStr, _ := readBody(roleResp.Body)
	if roleResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST role: status = %d, want 201, body=%s", roleResp.StatusCode, roleBodyStr)
	}
	var role applicationRoleViewBody
	if err := json.Unmarshal([]byte(roleBodyStr), &role); err != nil {
		t.Fatalf("decode role: %v (body=%s)", err, roleBodyStr)
	}

	getHeld := func(resourceServerID string) []string {
		t.Helper()
		resp, err := client.Get(bffBase + "/t/" + slug + "/api/users/" + targetID + "/application-roles?resourceServerId=" + resourceServerID)
		if err != nil {
			t.Fatalf("GET application-roles: %v", err)
		}
		defer resp.Body.Close()
		body, _ := readBody(resp.Body)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET application-roles: status = %d, want 200, body=%s", resp.StatusCode, body)
		}
		var names []string
		if err := json.Unmarshal([]byte(body), &names); err != nil {
			t.Fatalf("decode application-roles: %v (body=%s)", err, body)
		}
		assertNoTokenLeak(t, "GET application-roles", body)
		return names
	}
	contains := func(names []string, name string) bool {
		for _, n := range names {
			if n == name {
				return true
			}
		}
		return false
	}

	// Missing resourceServerId -> 400 locally (never silently tenant-wide).
	missingParamResp, err := client.Get(bffBase + "/t/" + slug + "/api/users/" + targetID + "/application-roles")
	if err != nil {
		t.Fatalf("GET application-roles (no param): %v", err)
	}
	defer missingParamResp.Body.Close()
	if missingParamResp.StatusCode != http.StatusBadRequest {
		body, _ := readBody(missingParamResp.Body)
		t.Fatalf("GET application-roles (no resourceServerId): status = %d, want 400, body=%s", missingParamResp.StatusCode, body)
	}

	if contains(getHeld(rs.ID), roleName) {
		t.Fatalf("target user already holds %s before assignment — not a clean starting state", roleName)
	}

	assignResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users/"+targetID+"/application-roles/"+role.ID, "", nil)
	defer assignResp.Body.Close()
	if assignResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(assignResp.Body)
		t.Fatalf("POST assign application role: status = %d, want 204, body=%s", assignResp.StatusCode, body)
	}
	if !contains(getHeld(rs.ID), roleName) {
		t.Errorf("%s not reflected in held application roles after assignment", roleName)
	}
	// A different resource server's view never sees this assignment — the
	// per-RS scoping this whole read exists for.
	if contains(getHeld(otherRS.ID), roleName) {
		t.Errorf("%s leaked into an unrelated resource server's held-roles view", roleName)
	}

	revokeResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/users/"+targetID+"/application-roles/"+role.ID, "", nil)
	defer revokeResp.Body.Close()
	if revokeResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(revokeResp.Body)
		t.Fatalf("DELETE revoke application role: status = %d, want 204, body=%s", revokeResp.StatusCode, body)
	}
	if contains(getHeld(rs.ID), roleName) {
		t.Errorf("%s still reflected in held application roles after revocation", roleName)
	}
}
