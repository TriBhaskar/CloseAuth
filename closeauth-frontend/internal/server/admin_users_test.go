package server

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"testing"
	"time"

	"closeauth-frontend/internal/testsupport"
)

// Stage UI-3b: live, Docker-gated proofs that the console's first real CRUD
// surface works end to end through the REAL backend AND a REAL BFF — not
// assertions from reading code. Reuses admin_console_test.go's harness
// helpers verbatim (newAdminConsoleServer, newBrowserLikeClient,
// driveAdminLoginToCallback, resolveLocation, readBody, assertNoTokenLeak);
// see that file's header comment for the cookie-port-blindness caveat, which
// applies here identically.
//
// Setup (provisioning the tenant/users/roles these tests act on) goes
// through Fixtures / the platform token directly — the harness's own "don't
// re-prove what's already proven" discipline. The ACTIONS under test
// (list/create/suspend/activate/approve/delete/assign/revoke) always go
// through the BFF's /t/{slug}/api/** surface, exactly as a browser would.

// adminAPIErrorBody mirrors admin_api_result.go's adminProblemErrorBody —
// duplicated here (not exported) because tests should assert against the
// wire shape, not the producing type.
type adminAPIErrorBody struct {
	Error            string         `json:"error"`
	ErrorDescription string         `json:"error_description"`
	Errors           map[string]any `json:"errors,omitempty"`
}

type pageViewBody[T any] struct {
	Items         []T `json:"items"`
	Page          int `json:"page"`
	Size          int `json:"size"`
	TotalElements int `json:"totalElements"`
	TotalPages    int `json:"totalPages"`
}

type userViewBody struct {
	ID     string `json:"id"`
	Email  string `json:"email"`
	Status string `json:"status"`
}

type tenantRoleViewBody struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// establishAdminSession drives the full login round trip and leaves client's
// cookie jar holding a valid bff_admin_session for slug — the setup step
// every test below needs before it can call the guarded /t/{slug}/api/**
// surface the way a browser would.
func establishAdminSession(t *testing.T, client *http.Client, bffBase, slug, adminClientID, email, password string) {
	t.Helper()
	returnTo := "/t/" + slug + "/console"
	callbackURL := driveAdminLoginToCallback(t, client, bffBase, slug, adminClientID, email, password, returnTo)
	resp, err := client.Get(callbackURL)
	if err != nil {
		t.Fatalf("GET admin/callback: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusFound {
		t.Fatalf("admin/callback: status = %d, want 302", resp.StatusCode)
	}
}

// csrfToken fetches a fresh CSRF token via GET /api/csrf — the same call
// tenantAdminClient.ts's tenantAdminFetch makes for every mutating request
// (getCsrfToken() ?? fetchCsrfToken()), reproduced manually here since
// client.Post/client.Do don't attach it automatically the way the SPA's
// fetch wrapper does. Mirrors admin_console_test.go's denied/dismiss CSRF
// step.
func csrfToken(t *testing.T, client *http.Client, bffBase string) string {
	t.Helper()
	resp, err := client.Get(bffBase + "/api/csrf")
	if err != nil {
		t.Fatalf("GET api/csrf: %v", err)
	}
	defer resp.Body.Close()
	var body struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode csrf token: %v", err)
	}
	if body.Token == "" {
		t.Fatalf("GET api/csrf: empty token")
	}
	return body.Token
}

// doWithCSRF issues a mutating request (POST/DELETE) carrying a fresh
// X-CSRF-Token header — every write to /t/{slug}/api/** in these tests goes
// through this rather than client.Post, which never attaches the header.
func doWithCSRF(t *testing.T, client *http.Client, bffBase, method, targetURL, contentType string, body io.Reader) *http.Response {
	t.Helper()
	req, err := http.NewRequest(method, targetURL, body)
	if err != nil {
		t.Fatalf("build %s %s: %v", method, targetURL, err)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	req.Header.Set("X-CSRF-Token", csrfToken(t, client, bffBase))
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, targetURL, err)
	}
	return resp
}

// newTenantWithAdmin provisions an active tenant and an ACTIVE TENANT_ADMIN
// user, returning everything a test needs to both call the BFF as that admin
// and make direct backend/platform-token calls for setup.
func newTenantWithAdmin(t *testing.T, stack *testsupport.Stack, fixtures *testsupport.Fixtures, ctx context.Context, namePrefix string) (tenantID, slug, adminClientID, adminUserID, adminEmail, adminPassword, platformToken string) {
	t.Helper()
	var err error
	platformToken, err = fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}
	tenantID, slug, err = fixtures.ProvisionActiveTenantWithSlug(ctx, platformToken)
	if err != nil {
		t.Fatalf("provision tenant: %v", err)
	}
	adminEmail = testsupport.Email(namePrefix)
	adminPassword = namePrefix + "-Pw-123!"
	adminUserID, err = fixtures.CreateActiveUser(ctx, platformToken, tenantID, adminEmail, adminPassword)
	if err != nil {
		t.Fatalf("create admin user: %v", err)
	}
	if err := fixtures.AssignTenantAdmin(ctx, platformToken, tenantID, adminUserID); err != nil {
		t.Fatalf("assign TENANT_ADMIN: %v", err)
	}
	adminClientID = testsupport.AdminConsoleClientID(slug)
	return
}

func TestAdminUsers_ListWithPaging_ReturnsRealPages(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	tenantID, slug, adminClientID, _, adminEmail, adminPassword, platformToken := newTenantWithAdmin(t, stack, fixtures, ctx, "paging-admin")
	_ = tenantID

	// Two more users beyond the admin itself -> 3 total.
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, testsupport.Email("paging-extra-1"), "Extra-Pw-123!"); err != nil {
		t.Fatalf("create extra user 1: %v", err)
	}
	if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, testsupport.Email("paging-extra-2"), "Extra-Pw-123!"); err != nil {
		t.Fatalf("create extra user 2: %v", err)
	}

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	page0, err := client.Get(bffBase + "/t/" + slug + "/api/users?page=0&size=2")
	if err != nil {
		t.Fatalf("GET users page 0: %v", err)
	}
	defer page0.Body.Close()
	body0, _ := readBody(page0.Body)
	if page0.StatusCode != http.StatusOK {
		t.Fatalf("users page 0: status = %d, want 200, body=%s", page0.StatusCode, body0)
	}
	var pv0 pageViewBody[userViewBody]
	if err := json.Unmarshal([]byte(body0), &pv0); err != nil {
		t.Fatalf("decode page 0: %v (body=%s)", err, body0)
	}
	if len(pv0.Items) != 2 {
		t.Errorf("page 0: len(items) = %d, want 2", len(pv0.Items))
	}
	if pv0.TotalElements != 3 {
		t.Errorf("page 0: totalElements = %d, want 3", pv0.TotalElements)
	}
	if pv0.TotalPages != 2 {
		t.Errorf("page 0: totalPages = %d, want 2", pv0.TotalPages)
	}
	assertNoTokenLeak(t, "users page 0", body0)

	page1, err := client.Get(bffBase + "/t/" + slug + "/api/users?page=1&size=2")
	if err != nil {
		t.Fatalf("GET users page 1: %v", err)
	}
	defer page1.Body.Close()
	body1, _ := readBody(page1.Body)
	if page1.StatusCode != http.StatusOK {
		t.Fatalf("users page 1: status = %d, want 200, body=%s", page1.StatusCode, body1)
	}
	var pv1 pageViewBody[userViewBody]
	if err := json.Unmarshal([]byte(body1), &pv1); err != nil {
		t.Fatalf("decode page 1: %v (body=%s)", err, body1)
	}
	if len(pv1.Items) != 1 {
		t.Errorf("page 1: len(items) = %d, want 1", len(pv1.Items))
	}
	if pv0.Items[0].ID == pv1.Items[0].ID {
		t.Errorf("page 0 and page 1 returned the same user id %q — paging isn't real", pv0.Items[0].ID)
	}
}

func TestAdminUsers_Create_PresentInListAndRejectsMalformedEmail(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "create-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	newEmail := testsupport.Email("created-user")
	createBody, _ := json.Marshal(map[string]any{
		"email":         newEmail,
		"password":      "Created-User-Pw-123!",
		"firstName":     "Created",
		"lastName":      "User",
		"initialStatus": "ACTIVE",
	})
	createResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users", "application/json", bytes.NewReader(createBody))
	defer createResp.Body.Close()
	createBodyStr, _ := readBody(createResp.Body)
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST users: status = %d, want 201, body=%s", createResp.StatusCode, createBodyStr)
	}
	var created userViewBody
	if err := json.Unmarshal([]byte(createBodyStr), &created); err != nil {
		t.Fatalf("decode created user: %v (body=%s)", err, createBodyStr)
	}
	if created.Email != newEmail {
		t.Errorf("created.Email = %q, want %q", created.Email, newEmail)
	}
	assertNoTokenLeak(t, "POST users", createBodyStr)

	// Side effect, not just the status code: the user shows up in a real list call.
	listResp, err := client.Get(bffBase + "/t/" + slug + "/api/users?page=0&size=100")
	if err != nil {
		t.Fatalf("GET users: %v", err)
	}
	defer listResp.Body.Close()
	listBody, _ := readBody(listResp.Body)
	var page pageViewBody[userViewBody]
	if err := json.Unmarshal([]byte(listBody), &page); err != nil {
		t.Fatalf("decode users list: %v (body=%s)", err, listBody)
	}
	found := false
	for _, u := range page.Items {
		if u.ID == created.ID {
			found = true
		}
	}
	if !found {
		t.Errorf("created user %s not present in the list after creation", created.ID)
	}

	// Malformed email -> 400 whose errors map arrives field-keyed, exactly
	// what src/api/tenantAdminProblem.ts's validationErrors branch needs.
	badBody, _ := json.Marshal(map[string]any{
		"email":    "not-an-email",
		"password": "Whatever-Pw-123!",
	})
	badResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users", "application/json", bytes.NewReader(badBody))
	defer badResp.Body.Close()
	badBodyStr, _ := readBody(badResp.Body)
	if badResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("POST users (malformed email): status = %d, want 400, body=%s", badResp.StatusCode, badBodyStr)
	}
	var badErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(badBodyStr), &badErr); err != nil {
		t.Fatalf("decode validation error: %v (body=%s)", err, badBodyStr)
	}
	if _, ok := badErr.Errors["email"]; !ok {
		t.Errorf("validation error body missing field-keyed 'email' entry, got errors=%v (full body=%s)", badErr.Errors, badBodyStr)
	}
}

func TestAdminUsers_SuspendThenActivate_TransitionsStatus(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	tenantID, slug, adminClientID, _, adminEmail, adminPassword, platformToken := newTenantWithAdmin(t, stack, fixtures, ctx, "lifecycle-admin")

	// A SEPARATE user from the admin — suspending the sole active admin
	// would hit the last-admin guard, which is a different test below.
	targetID, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, testsupport.Email("lifecycle-target"), "Target-Pw-123!")
	if err != nil {
		t.Fatalf("create target user: %v", err)
	}

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	suspendResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users/"+targetID+"/suspend", "", nil)
	defer suspendResp.Body.Close()
	suspendBody, _ := readBody(suspendResp.Body)
	if suspendResp.StatusCode != http.StatusOK {
		t.Fatalf("POST suspend: status = %d, want 200, body=%s", suspendResp.StatusCode, suspendBody)
	}
	var suspended userViewBody
	if err := json.Unmarshal([]byte(suspendBody), &suspended); err != nil {
		t.Fatalf("decode suspended user: %v (body=%s)", err, suspendBody)
	}
	if suspended.Status != "SUSPENDED" {
		t.Errorf("after suspend: status = %q, want SUSPENDED", suspended.Status)
	}

	activateResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users/"+targetID+"/activate", "", nil)
	defer activateResp.Body.Close()
	activateBody, _ := readBody(activateResp.Body)
	if activateResp.StatusCode != http.StatusOK {
		t.Fatalf("POST activate: status = %d, want 200, body=%s", activateResp.StatusCode, activateBody)
	}
	var activated userViewBody
	if err := json.Unmarshal([]byte(activateBody), &activated); err != nil {
		t.Fatalf("decode activated user: %v (body=%s)", err, activateBody)
	}
	if activated.Status != "ACTIVE" {
		t.Errorf("after activate: status = %q, want ACTIVE", activated.Status)
	}
}

func TestAdminUsers_Approve_PendingToActiveAndRejectsNonPending(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "approve-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	// Create a PENDING user through the BFF itself (also re-exercises create).
	pendingEmail := testsupport.Email("pending-user")
	createBody, _ := json.Marshal(map[string]any{
		"email":         pendingEmail,
		"password":      "Pending-User-Pw-123!",
		"initialStatus": "PENDING",
	})
	createResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users", "application/json", bytes.NewReader(createBody))
	defer createResp.Body.Close()
	createBodyStr, _ := readBody(createResp.Body)
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST users (pending): status = %d, want 201, body=%s", createResp.StatusCode, createBodyStr)
	}
	var created userViewBody
	if err := json.Unmarshal([]byte(createBodyStr), &created); err != nil {
		t.Fatalf("decode created pending user: %v (body=%s)", err, createBodyStr)
	}
	if created.Status != "PENDING" {
		t.Fatalf("created user status = %q, want PENDING", created.Status)
	}

	approveResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users/"+created.ID+"/approve", "", nil)
	defer approveResp.Body.Close()
	approveBody, _ := readBody(approveResp.Body)
	if approveResp.StatusCode != http.StatusOK {
		t.Fatalf("POST approve: status = %d, want 200, body=%s", approveResp.StatusCode, approveBody)
	}
	var approved userViewBody
	if err := json.Unmarshal([]byte(approveBody), &approved); err != nil {
		t.Fatalf("decode approved user: %v (body=%s)", err, approveBody)
	}
	if approved.Status != "ACTIVE" {
		t.Errorf("after approve: status = %q, want ACTIVE", approved.Status)
	}

	// A second approve on a now-ACTIVE user must 409 with the specific
	// domain code, not a flattened bad_gateway.
	reapproveResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users/"+created.ID+"/approve", "", nil)
	defer reapproveResp.Body.Close()
	reapproveBody, _ := readBody(reapproveResp.Body)
	if reapproveResp.StatusCode != http.StatusConflict {
		t.Fatalf("POST re-approve: status = %d, want 409, body=%s", reapproveResp.StatusCode, reapproveBody)
	}
	var reapproveErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(reapproveBody), &reapproveErr); err != nil {
		t.Fatalf("decode re-approve error: %v (body=%s)", err, reapproveBody)
	}
	if reapproveErr.Error != "user.not_pending" {
		t.Errorf("re-approve error code = %q, want user.not_pending", reapproveErr.Error)
	}
}

func TestAdminUsers_AssignThenRevokeTenantRole_ReflectedInHeldRoles(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	tenantID, slug, adminClientID, _, adminEmail, adminPassword, platformToken := newTenantWithAdmin(t, stack, fixtures, ctx, "roles-admin")

	targetID, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, testsupport.Email("roles-target"), "Target-Pw-123!")
	if err != nil {
		t.Fatalf("create target user: %v", err)
	}

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	// BILLING_ADMIN — a starter-pack system role that is NOT the tenant's
	// default role (TENANT_MEMBER is), so the target user starts out NOT
	// holding it: a deterministic starting state for this test.
	rolesResp, err := client.Get(bffBase + "/t/" + slug + "/api/roles?page=0&size=100")
	if err != nil {
		t.Fatalf("GET roles: %v", err)
	}
	defer rolesResp.Body.Close()
	rolesBody, _ := readBody(rolesResp.Body)
	if rolesResp.StatusCode != http.StatusOK {
		t.Fatalf("GET roles: status = %d, want 200, body=%s", rolesResp.StatusCode, rolesBody)
	}
	var rolesPage pageViewBody[tenantRoleViewBody]
	if err := json.Unmarshal([]byte(rolesBody), &rolesPage); err != nil {
		t.Fatalf("decode roles: %v (body=%s)", err, rolesBody)
	}
	var billingAdminID string
	for _, r := range rolesPage.Items {
		if r.Name == "BILLING_ADMIN" {
			billingAdminID = r.ID
		}
	}
	if billingAdminID == "" {
		t.Fatalf("BILLING_ADMIN not found in starter-pack roles: %v", rolesPage.Items)
	}

	getHeld := func() []string {
		t.Helper()
		resp, err := client.Get(bffBase + "/t/" + slug + "/api/users/" + targetID + "/tenant-roles")
		if err != nil {
			t.Fatalf("GET tenant-roles: %v", err)
		}
		defer resp.Body.Close()
		body, _ := readBody(resp.Body)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET tenant-roles: status = %d, want 200, body=%s", resp.StatusCode, body)
		}
		var names []string
		if err := json.Unmarshal([]byte(body), &names); err != nil {
			t.Fatalf("decode tenant-roles: %v (body=%s)", err, body)
		}
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

	if contains(getHeld(), "BILLING_ADMIN") {
		t.Fatalf("target user already holds BILLING_ADMIN before assignment — not a clean starting state")
	}

	assignResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users/"+targetID+"/tenant-roles/"+billingAdminID, "", nil)
	defer assignResp.Body.Close()
	if assignResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(assignResp.Body)
		t.Fatalf("POST assign role: status = %d, want 204, body=%s", assignResp.StatusCode, body)
	}
	if !contains(getHeld(), "BILLING_ADMIN") {
		t.Errorf("BILLING_ADMIN not reflected in held roles after assignment")
	}

	revokeResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/users/"+targetID+"/tenant-roles/"+billingAdminID, "", nil)
	defer revokeResp.Body.Close()
	if revokeResp.StatusCode != http.StatusNoContent {
		body, _ := readBody(revokeResp.Body)
		t.Fatalf("DELETE revoke role: status = %d, want 204, body=%s", revokeResp.StatusCode, body)
	}
	if contains(getHeld(), "BILLING_ADMIN") {
		t.Errorf("BILLING_ADMIN still reflected in held roles after revocation")
	}
}

// TestAdminUsers_LastAdmin_RevokeAndSuspendReturn409WithCodeIntact is the
// stage's headline proof: the BFF's response mapping (admin_api_result.go)
// must forward "tenant_role.last_admin" verbatim, not flatten it to a
// generic bad_gateway/conflict — that's the whole reason this stage built a
// shared problem-detail forwarder instead of reusing handlers_admin_ping.go's
// original inline switch.
func TestAdminUsers_LastAdmin_RevokeAndSuspendReturn409WithCodeIntact(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, adminUserID, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "sole-admin")

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

	// Revoking TENANT_ADMIN from the sole active admin -> 409, code intact.
	revokeResp := doWithCSRF(t, client, bffBase, http.MethodDelete, bffBase+"/t/"+slug+"/api/users/"+adminUserID+"/tenant-roles/"+tenantAdminRoleID, "", nil)
	defer revokeResp.Body.Close()
	revokeBody, _ := readBody(revokeResp.Body)
	if revokeResp.StatusCode != http.StatusConflict {
		t.Fatalf("revoke last admin: status = %d, want 409, body=%s", revokeResp.StatusCode, revokeBody)
	}
	var revokeErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(revokeBody), &revokeErr); err != nil {
		t.Fatalf("decode revoke error: %v (body=%s)", err, revokeBody)
	}
	if revokeErr.Error != "tenant_role.last_admin" {
		t.Errorf("revoke last admin: error code = %q, want tenant_role.last_admin (must not be flattened to bad_gateway)", revokeErr.Error)
	}

	// Suspending the sole active admin -> 409, same code, same guard.
	suspendResp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/t/"+slug+"/api/users/"+adminUserID+"/suspend", "", nil)
	defer suspendResp.Body.Close()
	suspendBody, _ := readBody(suspendResp.Body)
	if suspendResp.StatusCode != http.StatusConflict {
		t.Fatalf("suspend last admin: status = %d, want 409, body=%s", suspendResp.StatusCode, suspendBody)
	}
	var suspendErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(suspendBody), &suspendErr); err != nil {
		t.Fatalf("decode suspend error: %v (body=%s)", err, suspendBody)
	}
	if suspendErr.Error != "tenant_role.last_admin" {
		t.Errorf("suspend last admin: error code = %q, want tenant_role.last_admin", suspendErr.Error)
	}
}

// TestAdminUsers_MalformedUserID_RejectedLocallyAsBadRequest confirms
// validUUID rejects a malformed {userId} before it's ever concatenated into
// a backend URL.
func TestAdminUsers_MalformedUserID_RejectedLocallyAsBadRequest(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "malformed-id-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	resp, err := client.Get(bffBase + "/t/" + slug + "/api/users/" + url.PathEscape("not-a-uuid"))
	if err != nil {
		t.Fatalf("GET malformed user id: %v", err)
	}
	defer resp.Body.Close()
	body, _ := readBody(resp.Body)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("GET malformed user id: status = %d, want 400, body=%s", resp.StatusCode, body)
	}
	var errBody adminAPIErrorBody
	if err := json.Unmarshal([]byte(body), &errBody); err != nil {
		t.Fatalf("decode error body: %v (body=%s)", err, body)
	}
	if errBody.Error != "invalid_user_id" {
		t.Errorf("error code = %q, want invalid_user_id", errBody.Error)
	}
}
