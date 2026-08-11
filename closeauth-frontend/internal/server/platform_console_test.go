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

// Stage UI-4's "prove it": live, Docker-gated round trips against the REAL
// backend AND a REAL BFF (via testsupport.Stack, exactly like
// admin_console_test.go / admin_users_test.go) — not assertions from reading
// code. Reuses that harness verbatim: newAdminConsoleServer (the platform
// routes live on the SAME *Server as the tenant-admin console — bff/
// oauthClient/adminClient wiring is identical, only the route group
// differs), newBrowserLikeClient, doWithCSRF/csrfToken, readBody,
// assertNoTokenLeak, adminAPIErrorBody, pageViewBody[T].
//
// # Why one Test function with ordered subtests, not several Test functions
//
// PlatformAdminService.isLastActivePlatformAdmin (the guard behind the
// platform_admin.last_admin 409) counts ACTIVE PLATFORM_ADMIN holders
// PLATFORM-WIDE, and testsupport.Stack's containers are shared across every
// test in this package (booting is slow) — there is exactly ONE bootstrap
// platform admin until a test creates another. If tests ran as independent
// Test functions in an unpredictable order, a test that assigns
// PLATFORM_ADMIN to a second admin would silently invalidate the last-admin
// proof for anything running later (now there'd be 2 active holders), and Go
// gives no ordering guarantee between top-level Test functions. Subtests of
// one Test function, by contrast, run in the exact order they're declared
// (testing.T.Run is sequential for non-parallel subtests) — so the
// last-admin proof runs FIRST, before any second PLATFORM_ADMIN exists, and
// the one subtest that creates a lasting second holder cleans up after
// itself at the end. The bootstrap admin itself is NEVER suspended or
// stripped of its role by anything here — every other test in this package
// (and every other package needing Fixtures.PlatformAdminToken) depends on
// it staying active.

type tenantViewBody struct {
	ID     string `json:"id"`
	Slug   string `json:"slug"`
	Name   string `json:"name"`
	Status string `json:"status"`
	// AdminCount is nil on provision/activate/suspend/delete responses (the
	// backend leaves TenantView.adminCount unset there — see TenantView.java's
	// doc comment) and populated only on GET /tenants and GET /tenants/{id}.
	// Stage UI-4b's platform_onboarding_test.go is the first caller that
	// reads it.
	AdminCount *int64 `json:"adminCount"`
}

type platformAdminViewBody struct {
	ID     string `json:"id"`
	Email  string `json:"email"`
	Status string `json:"status"`
}

type platformSessionResponseBody struct {
	Authenticated        bool     `json:"authenticated"`
	AdminID              string   `json:"adminId"`
	Email                string   `json:"email"`
	Roles                []string `json:"roles"`
	AccessTokenExpiresAt string   `json:"accessTokenExpiresAt"`
}

// platformLogin POSTs email/password to /platform/api/login through client
// (a real cookiejar-backed *http.Client — see newBrowserLikeClient), asserts
// the given expected status, and returns the decoded body plus the response
// for the caller to inspect cookies/headers on. Every call is a real
// round-trip through the CSRF middleware (doWithCSRF), exactly like a
// browser's fetch() would need.
func platformLogin(t *testing.T, client *http.Client, bffBase, email, password string, wantStatus int) (platformSessionResponseBody, *http.Response, string) {
	t.Helper()
	body, err := json.Marshal(map[string]string{"email": email, "password": password})
	if err != nil {
		t.Fatalf("marshal login body: %v", err)
	}
	resp := doWithCSRF(t, client, bffBase, http.MethodPost, bffBase+"/platform/api/login", "application/json", bytes.NewReader(body))
	bodyStr, _ := readBody(resp.Body)
	if resp.StatusCode != wantStatus {
		t.Fatalf("POST /platform/api/login: status = %d, want %d, body=%s", resp.StatusCode, wantStatus, bodyStr)
	}
	var decoded platformSessionResponseBody
	// A non-2xx body doesn't decode into platformSessionResponseBody (it's an
	// adminAPIErrorBody instead) — callers expecting a failure status pass
	// the raw bodyStr through instead and decode it themselves.
	if wantStatus == http.StatusOK {
		if err := json.Unmarshal([]byte(bodyStr), &decoded); err != nil {
			t.Fatalf("decode login response: %v (body=%s)", err, bodyStr)
		}
	}
	return decoded, resp, bodyStr
}

// platformSessionCookie finds the bff_platform_session cookie among resp's
// Set-Cookie headers, or nil if absent.
func platformSessionCookie(resp *http.Response) *http.Cookie {
	for _, c := range resp.Cookies() {
		if c.Name == "bff_platform_session" {
			return c
		}
	}
	return nil
}

func TestPlatformConsole(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	platformToken, err := fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint bootstrap platform admin token: %v", err)
	}

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()

	// Resolve the bootstrap admin's own id once, via a real BFF login —
	// every later subtest needing it (the last-admin proof, tenant
	// provisioning) reuses this rather than re-deriving it.
	bootstrapClient := newBrowserLikeClient(t)
	bootstrapSession, loginResp, loginBodyStr := platformLogin(t, bootstrapClient, bffBase, testsupport.BootstrapAdminEmail, testsupport.BootstrapAdminPassword, http.StatusOK)
	loginResp.Body.Close()
	if !bootstrapSession.Authenticated || bootstrapSession.AdminID == "" {
		t.Fatalf("bootstrap platform-admin login did not establish a session: %+v", bootstrapSession)
	}
	found := false
	for _, r := range bootstrapSession.Roles {
		if r == "PLATFORM_ADMIN" {
			found = true
		}
	}
	if !found {
		t.Fatalf("bootstrap admin session roles = %v, want to contain PLATFORM_ADMIN", bootstrapSession.Roles)
	}
	bootstrapAdminID := bootstrapSession.AdminID
	assertNoTokenLeak(t, "POST /platform/api/login (bootstrap)", loginBodyStr)

	// ---- 1. Login + session, and no-token-leakage across the journey ----
	t.Run("LoginAndSessionNeverLeaksTheToken", func(t *testing.T) {
		sessionResp, err := bootstrapClient.Get(bffBase + "/platform/api/session")
		if err != nil {
			t.Fatalf("GET /platform/api/session: %v", err)
		}
		defer sessionResp.Body.Close()
		sessionBodyStr, _ := readBody(sessionResp.Body)
		if sessionResp.StatusCode != http.StatusOK {
			t.Fatalf("GET /platform/api/session: status = %d, want 200, body=%s", sessionResp.StatusCode, sessionBodyStr)
		}
		var session platformSessionResponseBody
		if err := json.Unmarshal([]byte(sessionBodyStr), &session); err != nil {
			t.Fatalf("decode session: %v (body=%s)", err, sessionBodyStr)
		}
		if !session.Authenticated || session.AdminID != bootstrapAdminID {
			t.Errorf("session = %+v, want authenticated for admin %q", session, bootstrapAdminID)
		}
		assertNoTokenLeak(t, "GET /platform/api/session", sessionBodyStr)

		// The session cookie itself: sealed (AES-GCM + base64), so a raw
		// "access_token"/"eyJ" substring match inside it would mean the
		// plaintext leaked into the cookie value unencrypted — check it too.
		cookie := platformSessionCookie(loginResp)
		if cookie == nil {
			t.Fatalf("no bff_platform_session cookie was set on the login response")
		}
		if !cookie.HttpOnly {
			t.Errorf("bff_platform_session cookie is not HttpOnly")
		}
		if cookie.Path != "/platform" {
			t.Errorf("bff_platform_session cookie Path = %q, want /platform", cookie.Path)
		}
		assertNoTokenLeak(t, "bff_platform_session cookie value", cookie.Value)
	})

	// ---- 2. Tenant lifecycle through the BFF ----
	t.Run("TenantLifecycle", func(t *testing.T) {
		slug := testsupport.Slug("plat")
		provisionBody, _ := json.Marshal(map[string]string{"slug": slug, "name": "Platform Test Co"})
		provisionResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/tenants", "application/json", bytes.NewReader(provisionBody))
		defer provisionResp.Body.Close()
		provisionBodyStr, _ := readBody(provisionResp.Body)
		if provisionResp.StatusCode != http.StatusCreated {
			t.Fatalf("POST /platform/api/tenants: status = %d, want 201, body=%s", provisionResp.StatusCode, provisionBodyStr)
		}
		var provisioned tenantViewBody
		if err := json.Unmarshal([]byte(provisionBodyStr), &provisioned); err != nil {
			t.Fatalf("decode provisioned tenant: %v (body=%s)", err, provisionBodyStr)
		}
		if provisioned.Status != "PROVISIONING" {
			t.Errorf("provisioned tenant status = %q, want PROVISIONING", provisioned.Status)
		}
		assertNoTokenLeak(t, "POST /platform/api/tenants", provisionBodyStr)

		// Side effect, not just the response body: it shows up in a real list call.
		listResp, err := bootstrapClient.Get(bffBase + "/platform/api/tenants?page=0&size=100")
		if err != nil {
			t.Fatalf("GET /platform/api/tenants: %v", err)
		}
		defer listResp.Body.Close()
		listBodyStr, _ := readBody(listResp.Body)
		if listResp.StatusCode != http.StatusOK {
			t.Fatalf("GET /platform/api/tenants: status = %d, want 200, body=%s", listResp.StatusCode, listBodyStr)
		}
		var page pageViewBody[tenantViewBody]
		if err := json.Unmarshal([]byte(listBodyStr), &page); err != nil {
			t.Fatalf("decode tenants page: %v (body=%s)", err, listBodyStr)
		}
		listed := false
		for _, item := range page.Items {
			if item.ID == provisioned.ID {
				listed = true
			}
		}
		if !listed {
			t.Errorf("provisioned tenant %s not present in GET /platform/api/tenants", provisioned.ID)
		}

		activateResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/tenants/"+provisioned.ID+"/activate", "", nil)
		defer activateResp.Body.Close()
		activateBodyStr, _ := readBody(activateResp.Body)
		if activateResp.StatusCode != http.StatusOK {
			t.Fatalf("POST activate: status = %d, want 200, body=%s", activateResp.StatusCode, activateBodyStr)
		}
		var activated tenantViewBody
		if err := json.Unmarshal([]byte(activateBodyStr), &activated); err != nil {
			t.Fatalf("decode activated tenant: %v (body=%s)", err, activateBodyStr)
		}
		if activated.Status != "ACTIVE" {
			t.Errorf("after activate: status = %q, want ACTIVE", activated.Status)
		}

		suspendResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/tenants/"+provisioned.ID+"/suspend", "", nil)
		defer suspendResp.Body.Close()
		suspendBodyStr, _ := readBody(suspendResp.Body)
		if suspendResp.StatusCode != http.StatusOK {
			t.Fatalf("POST suspend: status = %d, want 200, body=%s", suspendResp.StatusCode, suspendBodyStr)
		}
		var suspended tenantViewBody
		if err := json.Unmarshal([]byte(suspendBodyStr), &suspended); err != nil {
			t.Fatalf("decode suspended tenant: %v (body=%s)", err, suspendBodyStr)
		}
		if suspended.Status != "SUSPENDED" {
			t.Errorf("after suspend: status = %q, want SUSPENDED", suspended.Status)
		}
	})

	// ---- 3. The last-active-PLATFORM_ADMIN 409 — MUST run before any
	// subtest creates a second active PLATFORM_ADMIN holder. ----
	t.Run("LastPlatformAdminRefusal", func(t *testing.T) {
		resp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/admins/"+bootstrapAdminID+"/suspend", "", nil)
		defer resp.Body.Close()
		bodyStr, _ := readBody(resp.Body)
		if resp.StatusCode != http.StatusConflict {
			t.Fatalf("suspend the sole active PLATFORM_ADMIN: status = %d, want 409, body=%s", resp.StatusCode, bodyStr)
		}
		var errBody adminAPIErrorBody
		if err := json.Unmarshal([]byte(bodyStr), &errBody); err != nil {
			t.Fatalf("decode error: %v (body=%s)", err, bodyStr)
		}
		if errBody.Error != "platform_admin.last_admin" {
			t.Errorf("error code = %q, want platform_admin.last_admin (must not be flattened to a generic conflict)", errBody.Error)
		}

		// Confirm it's still ACTIVE — the refusal actually prevented the
		// state change, not just returned an error alongside it.
		meResp, err := bootstrapClient.Get(bffBase + "/platform/api/session")
		if err != nil {
			t.Fatalf("GET session: %v", err)
		}
		defer meResp.Body.Close()
		var session platformSessionResponseBody
		if err := json.NewDecoder(meResp.Body).Decode(&session); err != nil {
			t.Fatalf("decode session: %v", err)
		}
		if !session.Authenticated {
			t.Errorf("bootstrap admin session no longer authenticated after a REFUSED suspend — the refusal should have been a true no-op")
		}
	})

	// ---- 4. Platform-admin create -> empty roles -> assign -> reflected ->
	// suspend -> activate. Creates admin2, a SECOND active PLATFORM_ADMIN
	// holder by the end — every subtest after this one must account for that
	// (see the CleanupRestoresLastAdminInvariant subtest at the end). ----
	var admin2ID, admin2Email, admin2Password string
	t.Run("PlatformAdminCreateRolesAndLifecycle", func(t *testing.T) {
		admin2Email = testsupport.Email("platform-admin-2")
		admin2Password = "Platform-Admin-2-Pw-123!"
		createBody, _ := json.Marshal(map[string]string{
			"email": admin2Email, "password": admin2Password, "firstName": "Second", "lastName": "Admin",
		})
		createResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/admins", "application/json", bytes.NewReader(createBody))
		defer createResp.Body.Close()
		createBodyStr, _ := readBody(createResp.Body)
		if createResp.StatusCode != http.StatusCreated {
			t.Fatalf("POST /platform/api/admins: status = %d, want 201, body=%s", createResp.StatusCode, createBodyStr)
		}
		var created platformAdminViewBody
		if err := json.Unmarshal([]byte(createBodyStr), &created); err != nil {
			t.Fatalf("decode created admin: %v (body=%s)", err, createBodyStr)
		}
		if created.Status != "ACTIVE" {
			t.Errorf("created admin status = %q, want ACTIVE (roles, not status, gate console access)", created.Status)
		}
		admin2ID = created.ID
		assertNoTokenLeak(t, "POST /platform/api/admins", createBodyStr)

		// A newly created platform admin has NO roles by default — creation
		// alone doesn't grant access.
		rolesResp, err := bootstrapClient.Get(bffBase + "/platform/api/admins/" + admin2ID + "/roles")
		if err != nil {
			t.Fatalf("GET roles: %v", err)
		}
		var roles []string
		if err := json.NewDecoder(rolesResp.Body).Decode(&roles); err != nil {
			t.Fatalf("decode roles: %v", err)
		}
		rolesResp.Body.Close()
		if len(roles) != 0 {
			t.Fatalf("freshly created admin's roles = %v, want empty", roles)
		}

		assignResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/admins/"+admin2ID+"/roles/PLATFORM_ADMIN", "", nil)
		defer assignResp.Body.Close()
		assignBodyStr, _ := readBody(assignResp.Body)
		if assignResp.StatusCode != http.StatusNoContent {
			t.Fatalf("POST assign PLATFORM_ADMIN: status = %d, want 204, body=%s", assignResp.StatusCode, assignBodyStr)
		}

		rolesResp2, err := bootstrapClient.Get(bffBase + "/platform/api/admins/" + admin2ID + "/roles")
		if err != nil {
			t.Fatalf("GET roles (after assign): %v", err)
		}
		var rolesAfter []string
		if err := json.NewDecoder(rolesResp2.Body).Decode(&rolesAfter); err != nil {
			t.Fatalf("decode roles: %v", err)
		}
		rolesResp2.Body.Close()
		if !contains(rolesAfter, "PLATFORM_ADMIN") {
			t.Errorf("roles after assign = %v, want to contain PLATFORM_ADMIN", rolesAfter)
		}

		// Suspend then activate admin2 (NOT the bootstrap admin — this
		// doesn't touch the last-admin invariant, since bootstrap remains
		// the/an active holder throughout) — proves the lifecycle both ways,
		// and leaves admin2 ACTIVE + PLATFORM_ADMIN for the SESSION-
		// suspension subtest below, which needs a real, live admin2 session
		// to suspend out from under.
		suspendResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/admins/"+admin2ID+"/suspend", "", nil)
		defer suspendResp.Body.Close()
		suspendBodyStr, _ := readBody(suspendResp.Body)
		if suspendResp.StatusCode != http.StatusOK {
			t.Fatalf("suspend admin2: status = %d, want 200, body=%s", suspendResp.StatusCode, suspendBodyStr)
		}
		var suspended platformAdminViewBody
		if err := json.Unmarshal([]byte(suspendBodyStr), &suspended); err != nil {
			t.Fatalf("decode suspended admin: %v", err)
		}
		if suspended.Status != "SUSPENDED" {
			t.Errorf("admin2 status after suspend = %q, want SUSPENDED", suspended.Status)
		}

		activateResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/admins/"+admin2ID+"/activate", "", nil)
		defer activateResp.Body.Close()
		activateBodyStr, _ := readBody(activateResp.Body)
		if activateResp.StatusCode != http.StatusOK {
			t.Fatalf("activate admin2: status = %d, want 200, body=%s", activateResp.StatusCode, activateBodyStr)
		}
		var activated platformAdminViewBody
		if err := json.Unmarshal([]byte(activateBodyStr), &activated); err != nil {
			t.Fatalf("decode activated admin: %v", err)
		}
		if activated.Status != "ACTIVE" {
			t.Errorf("admin2 status after activate = %q, want ACTIVE", activated.Status)
		}
	})

	// ---- 5. Refusals ----
	t.Run("Refusals", func(t *testing.T) {
		t.Run("TenantUserCredentials_InvalidCredentials", func(t *testing.T) {
			tenantID, err := fixtures.ProvisionActiveTenant(ctx, platformToken)
			if err != nil {
				t.Fatalf("provision tenant: %v", err)
			}
			email := testsupport.Email("not-a-platform-admin")
			password := "Not-Platform-Admin-Pw-123!"
			if _, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password); err != nil {
				t.Fatalf("create tenant user: %v", err)
			}

			client := newBrowserLikeClient(t)
			_, resp, bodyStr := platformLogin(t, client, bffBase, email, password, http.StatusUnauthorized)
			resp.Body.Close()
			var errBody adminAPIErrorBody
			if err := json.Unmarshal([]byte(bodyStr), &errBody); err != nil {
				t.Fatalf("decode error: %v (body=%s)", err, bodyStr)
			}
			if errBody.Error != "invalid_credentials" {
				t.Errorf("error code = %q, want invalid_credentials (enumeration-safe — a tenant user is not a platform admin at all)", errBody.Error)
			}
		})

		t.Run("ZeroRoleAdmin_NotPlatformAdmin_NoSessionCookieSet", func(t *testing.T) {
			email := testsupport.Email("zero-role-admin")
			password := "Zero-Role-Admin-Pw-123!"
			createBody, _ := json.Marshal(map[string]string{"email": email, "password": password})
			createResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/admins", "application/json", bytes.NewReader(createBody))
			createBodyStr, _ := readBody(createResp.Body)
			createResp.Body.Close()
			if createResp.StatusCode != http.StatusCreated {
				t.Fatalf("create zero-role admin: status = %d, want 201, body=%s", createResp.StatusCode, createBodyStr)
			}

			client := newBrowserLikeClient(t)
			_, resp, bodyStr := platformLogin(t, client, bffBase, email, password, http.StatusForbidden)
			var errBody adminAPIErrorBody
			if err := json.Unmarshal([]byte(bodyStr), &errBody); err != nil {
				t.Fatalf("decode error: %v (body=%s)", err, bodyStr)
			}
			if errBody.Error != "not_platform_admin" {
				t.Errorf("error code = %q, want not_platform_admin", errBody.Error)
			}
			if cookie := platformSessionCookie(resp); cookie != nil {
				t.Errorf("a session cookie was set for a zero-role admin's login — creation alone must NOT grant a usable session")
			}
			resp.Body.Close()
		})

		t.Run("TenantAdminBearerToken_DirectToPlatformAPI_Forbidden", func(t *testing.T) {
			tenantID, slug, err := fixtures.ProvisionActiveTenantWithSlug(ctx, platformToken)
			if err != nil {
				t.Fatalf("provision tenant: %v", err)
			}
			email := testsupport.Email("real-tenant-admin")
			password := "Real-Tenant-Admin-Pw-123!"
			userID, err := fixtures.CreateActiveUser(ctx, platformToken, tenantID, email, password)
			if err != nil {
				t.Fatalf("create user: %v", err)
			}
			if err := fixtures.AssignTenantAdmin(ctx, platformToken, tenantID, userID); err != nil {
				t.Fatalf("assign TENANT_ADMIN: %v", err)
			}
			adminClientID := testsupport.AdminConsoleClientID(slug)

			// A real tenant-admin bearer token, minted directly against the
			// backend (bypassing the BFF entirely) — proves the backend's own
			// @RequiresPlatformAdmin gate refuses it, not just the BFF's
			// session layer.
			oauth := stack.OAuthClient(stack.BFFBaseURL() + "/admin/callback")
			loginResult, err := oauth.Login(ctx, adminClientID, "", email, password)
			if err != nil {
				t.Fatalf("tenant-admin oauth login: %v", err)
			}
			resp, err := stack.AdminClient().Get(ctx, loginResult.Tokens.AccessToken, "/v1/platform/tenants")
			if err != nil {
				t.Fatalf("GET /v1/platform/tenants with tenant-admin token: %v", err)
			}
			if resp.StatusCode != http.StatusForbidden {
				t.Errorf("GET /v1/platform/tenants with a TENANT_ADMIN token: status = %d, want 403", resp.StatusCode)
			}
		})
	})

	// ---- 6. Suspension kills a live platform-admin session within seconds,
	// not at the token's 5-minute expiry. Suspends admin2 (not bootstrap). ----
	t.Run("SuspensionKillsLiveSession", func(t *testing.T) {
		admin2Client := newBrowserLikeClient(t)
		admin2Session, loginResp2, _ := platformLogin(t, admin2Client, bffBase, admin2Email, admin2Password, http.StatusOK)
		loginResp2.Body.Close()
		if !admin2Session.Authenticated || admin2Session.AdminID != admin2ID {
			t.Fatalf("admin2 login did not establish the expected session: %+v", admin2Session)
		}

		suspendResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/admins/"+admin2ID+"/suspend", "", nil)
		suspendBodyStr, _ := readBody(suspendResp.Body)
		suspendResp.Body.Close()
		if suspendResp.StatusCode != http.StatusOK {
			t.Fatalf("suspend admin2: status = %d, want 200, body=%s", suspendResp.StatusCode, suspendBodyStr)
		}

		// admin2's OWN still-cookied client, reusing its now-revoked token —
		// no expiry wait needed: the sub-keyed revocation marker
		// (PlatformAdminRevocationTokenValidator) rejects it on the very
		// next request.
		guardedResp, err := admin2Client.Get(bffBase + "/platform/api/tenants")
		if err != nil {
			t.Fatalf("GET /platform/api/tenants (admin2, post-suspend): %v", err)
		}
		defer guardedResp.Body.Close()
		guardedBodyStr, _ := readBody(guardedResp.Body)
		if guardedResp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("admin2's request after suspension: status = %d, want 401, body=%s", guardedResp.StatusCode, guardedBodyStr)
		}
		var errBody adminAPIErrorBody
		if err := json.Unmarshal([]byte(guardedBodyStr), &errBody); err != nil {
			t.Fatalf("decode error: %v (body=%s)", err, guardedBodyStr)
		}
		if errBody.Error != "session_expired" {
			t.Errorf("error code = %q, want session_expired", errBody.Error)
		}
		if cookie := platformSessionCookie(guardedResp); cookie == nil || cookie.MaxAge >= 0 {
			t.Errorf("expected the 401 response to clear bff_platform_session (MaxAge<0), got %+v", cookie)
		}
	})

	// ---- 7. Restore the platform-wide last-admin invariant: admin2 is
	// already SUSPENDED (not an active holder, so this isn't strictly load-
	// bearing for later tests), but revoking the role too is the honest
	// cleanup — leaves the fixture data this test created in a state that
	// doesn't look like a stray second admin console. ----
	t.Run("CleanupRevokesPlatformAdminFromAdmin2", func(t *testing.T) {
		if admin2ID == "" {
			t.Skip("admin2 was never created (an earlier subtest must have failed)")
		}
		resp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodDelete, bffBase+"/platform/api/admins/"+admin2ID+"/roles/PLATFORM_ADMIN", "", nil)
		defer resp.Body.Close()
		bodyStr, _ := readBody(resp.Body)
		if resp.StatusCode != http.StatusNoContent {
			t.Errorf("revoke PLATFORM_ADMIN from admin2 (cleanup): status = %d, want 204, body=%s", resp.StatusCode, bodyStr)
		}
	})
}

func contains(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}
