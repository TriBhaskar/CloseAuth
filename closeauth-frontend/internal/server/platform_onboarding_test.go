package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"testing"
	"time"

	"closeauth-frontend/internal/testsupport"
)

// Stage UI-4b's "prove it": live, Docker-gated round trips against the REAL
// backend AND a REAL BFF, through the three routes handlers_platform_
// onboarding.go adds (list a tenant's users, bootstrap its first admin,
// reissue an unused onboarding credential) — reusing platform_console_test.go's
// harness verbatim (newAdminConsoleServer, newBrowserLikeClient, platformLogin,
// doWithCSRF, readBody, assertNoTokenLeak, tenantViewBody, pageViewBody[T],
// adminAPIErrorBody — same package, no need to redeclare).
//
// One Test function with ordered subtests, same reasoning as
// TestPlatformConsole: the stack's containers are shared across this
// package's tests, and PlatformAdminService.isLastActivePlatformAdmin counts
// PLATFORM-WIDE, so keeping everything here as subtests (not separate Test
// functions) avoids Go's unordered-Test-function execution from interleaving
// with TestPlatformConsole in a way that would make either test's state
// assumptions wrong. Subtests run in declaration order.

type tenantAdminBootstrappedBody struct {
	User                     userViewBody `json:"user"`
	TemporaryPassword        string       `json:"temporaryPassword"`
	TemporaryPasswordExpires string       `json:"temporaryPasswordExpiresAt"`
}

type tempCredentialReissuedBody struct {
	UserID                   string `json:"userId"`
	TemporaryPassword        string `json:"temporaryPassword"`
	TemporaryPasswordExpires string `json:"temporaryPasswordExpiresAt"`
}

func TestPlatformOnboarding(t *testing.T) {
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

	bootstrapClient := newBrowserLikeClient(t)
	session, loginResp, loginBodyStr := platformLogin(t, bootstrapClient, bffBase, testsupport.BootstrapAdminEmail, testsupport.BootstrapAdminPassword, http.StatusOK)
	loginResp.Body.Close()
	if !session.Authenticated {
		t.Fatalf("bootstrap platform-admin login did not establish a session: %+v", session)
	}
	assertNoTokenLeak(t, "POST /platform/api/login (bootstrap)", loginBodyStr)

	// ---- 1. Happy path: provision+activate, bootstrap the first admin,
	// confirm it shows up via the new users route, confirm adminCount
	// flips 0 -> 1 in the list. ----
	t.Run("BootstrapCreatesFirstAdminAndFlipsAdminCount", func(t *testing.T) {
		tenantID, err := fixtures.ProvisionActiveTenant(ctx, platformToken)
		if err != nil {
			t.Fatalf("provision active tenant: %v", err)
		}

		// adminCount starts at 0 for a freshly provisioned tenant.
		beforeResp, err := bootstrapClient.Get(bffBase + "/platform/api/tenants/" + tenantID)
		if err != nil {
			t.Fatalf("GET tenant before bootstrap: %v", err)
		}
		beforeBodyStr, _ := readBody(beforeResp.Body)
		beforeResp.Body.Close()
		var before tenantViewBody
		if err := json.Unmarshal([]byte(beforeBodyStr), &before); err != nil {
			t.Fatalf("decode tenant before bootstrap: %v (body=%s)", err, beforeBodyStr)
		}
		if before.AdminCount == nil || *before.AdminCount != 0 {
			t.Fatalf("adminCount before bootstrap = %v, want 0", before.AdminCount)
		}

		email := testsupport.Email("bootstrap-first-admin")
		bootstrapBody, _ := json.Marshal(map[string]string{"email": email, "firstName": "Ada", "lastName": "Admin"})
		bootstrapResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/tenants/"+tenantID+"/bootstrap-admin",
			"application/json", bytes.NewReader(bootstrapBody))
		defer bootstrapResp.Body.Close()
		bootstrapBodyStr, _ := readBody(bootstrapResp.Body)
		if bootstrapResp.StatusCode != http.StatusCreated {
			t.Fatalf("POST bootstrap-admin: status = %d, want 201, body=%s", bootstrapResp.StatusCode, bootstrapBodyStr)
		}
		var bootstrapped tenantAdminBootstrappedBody
		if err := json.Unmarshal([]byte(bootstrapBodyStr), &bootstrapped); err != nil {
			t.Fatalf("decode bootstrap response: %v (body=%s)", err, bootstrapBodyStr)
		}
		if bootstrapped.TemporaryPassword == "" {
			t.Fatalf("bootstrap response carried no temporaryPassword")
		}
		if bootstrapped.User.Status != "ACTIVE" {
			t.Errorf("bootstrapped user status = %q, want ACTIVE", bootstrapped.User.Status)
		}
		assertNoTokenLeakExceptSecret(t, "POST bootstrap-admin", bootstrapBodyStr, bootstrapped.TemporaryPassword)

		// Side effect, not just the response body: the new user shows up via
		// the users-list route the console needs to find a userId for reissue.
		usersResp, err := bootstrapClient.Get(bffBase + "/platform/api/tenants/" + tenantID + "/users?page=0&size=100")
		if err != nil {
			t.Fatalf("GET tenant users: %v", err)
		}
		defer usersResp.Body.Close()
		usersBodyStr, _ := readBody(usersResp.Body)
		if usersResp.StatusCode != http.StatusOK {
			t.Fatalf("GET tenant users: status = %d, want 200, body=%s", usersResp.StatusCode, usersBodyStr)
		}
		var usersPage pageViewBody[userViewBody]
		if err := json.Unmarshal([]byte(usersBodyStr), &usersPage); err != nil {
			t.Fatalf("decode tenant users page: %v (body=%s)", err, usersBodyStr)
		}
		listed := false
		for _, u := range usersPage.Items {
			if u.ID == bootstrapped.User.ID {
				listed = true
			}
		}
		if !listed {
			t.Errorf("bootstrapped admin %s not present in GET tenant users", bootstrapped.User.ID)
		}

		// adminCount flips 0 -> 1.
		afterResp, err := bootstrapClient.Get(bffBase + "/platform/api/tenants/" + tenantID)
		if err != nil {
			t.Fatalf("GET tenant after bootstrap: %v", err)
		}
		afterBodyStr, _ := readBody(afterResp.Body)
		afterResp.Body.Close()
		var after tenantViewBody
		if err := json.Unmarshal([]byte(afterBodyStr), &after); err != nil {
			t.Fatalf("decode tenant after bootstrap: %v (body=%s)", err, afterBodyStr)
		}
		if after.AdminCount == nil || *after.AdminCount != 1 {
			t.Fatalf("adminCount after bootstrap = %v, want 1", after.AdminCount)
		}

		// A second bootstrap on the SAME tenant is refused with its own
		// domain code, not flattened to a generic conflict.
		secondEmail := testsupport.Email("bootstrap-second-attempt")
		secondBody, _ := json.Marshal(map[string]string{"email": secondEmail})
		secondResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/tenants/"+tenantID+"/bootstrap-admin",
			"application/json", bytes.NewReader(secondBody))
		defer secondResp.Body.Close()
		secondBodyStr, _ := readBody(secondResp.Body)
		if secondResp.StatusCode != http.StatusConflict {
			t.Fatalf("second bootstrap-admin: status = %d, want 409, body=%s", secondResp.StatusCode, secondBodyStr)
		}
		var secondErr adminAPIErrorBody
		if err := json.Unmarshal([]byte(secondBodyStr), &secondErr); err != nil {
			t.Fatalf("decode second bootstrap error: %v (body=%s)", err, secondBodyStr)
		}
		if secondErr.Error != "tenant_onboarding.admin_already_exists" {
			t.Errorf("second bootstrap error code = %q, want tenant_onboarding.admin_already_exists (must not be flattened to a generic conflict)", secondErr.Error)
		}
	})

	// ---- 2. Reissue succeeds against an un-rotated admin; completing a REAL
	// rotation (via the temp-password login on-ramp, exactly like a real
	// admin would) then makes a further reissue refused with its own domain
	// code — proving the 409 tracks the actual DB state, not a guess. ----
	t.Run("ReissueSucceedsThenRefusedAfterARealRotation", func(t *testing.T) {
		tenantID, slug, err := fixtures.ProvisionActiveTenantWithSlug(ctx, platformToken)
		if err != nil {
			t.Fatalf("provision active tenant: %v", err)
		}
		email := testsupport.Email("reissue-then-rotate")
		bootstrap, err := fixtures.BootstrapTenantAdmin(ctx, platformToken, tenantID, email)
		if err != nil {
			t.Fatalf("bootstrap tenant admin: %v", err)
		}

		reissueResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost,
			bffBase+"/platform/api/tenants/"+tenantID+"/users/"+bootstrap.UserID+"/reissue-onboarding-credential", "", nil)
		defer reissueResp.Body.Close()
		reissueBodyStr, _ := readBody(reissueResp.Body)
		if reissueResp.StatusCode != http.StatusOK {
			t.Fatalf("POST reissue-onboarding-credential: status = %d, want 200, body=%s", reissueResp.StatusCode, reissueBodyStr)
		}
		var reissued tempCredentialReissuedBody
		if err := json.Unmarshal([]byte(reissueBodyStr), &reissued); err != nil {
			t.Fatalf("decode reissue response: %v (body=%s)", err, reissueBodyStr)
		}
		if reissued.UserID != bootstrap.UserID {
			t.Errorf("reissue userId = %q, want %q", reissued.UserID, bootstrap.UserID)
		}
		if reissued.TemporaryPassword == "" {
			t.Fatalf("reissue response carried no temporaryPassword")
		}
		assertNoTokenLeakExceptSecret(t, "POST reissue-onboarding-credential", reissueBodyStr, reissued.TemporaryPassword)

		// Complete a REAL rotation with the REISSUED password (decision 7:
		// reissuing invalidates the outstanding onboarding token, so the
		// original bootstrap password is no longer the live one) — the same
		// temp-password login on-ramp a real admin uses
		// (TestPasswordRotationProxy_TempPasswordOnRamp_..., which this
		// mirrors the first two hops of). authorizeQuery is optional on both
		// endpoints (LoginPolicyService/PasswordRotationController's own
		// `required = false`), so this test skips the /oauth2/authorize
		// round trip entirely — it only needs a token, not a resume target.
		adminConsoleClientID := testsupport.AdminConsoleClientID(slug)
		loginBody, _ := json.Marshal(map[string]any{
			"email":    email,
			"password": reissued.TemporaryPassword,
			"clientId": adminConsoleClientID,
		})
		loginReq, err := http.NewRequest(http.MethodPost, bffBase+"/api/auth/login", bytes.NewReader(loginBody))
		if err != nil {
			t.Fatalf("build login request: %v", err)
		}
		loginReq.Header.Set("Content-Type", "application/json")
		loginResp, err := http.DefaultClient.Do(loginReq)
		if err != nil {
			t.Fatalf("POST /api/auth/login (temp password): %v", err)
		}
		defer loginResp.Body.Close()
		loginBodyStr, _ := readBody(loginResp.Body)
		if loginResp.StatusCode != http.StatusOK {
			t.Fatalf("POST /api/auth/login (temp password): status = %d, want 200 (translated rotation-required envelope), body=%s",
				loginResp.StatusCode, loginBodyStr)
		}
		var loginEnvelope struct {
			RedirectTo string `json:"redirectTo"`
		}
		if err := json.Unmarshal([]byte(loginBodyStr), &loginEnvelope); err != nil {
			t.Fatalf("decode login envelope: %v (body=%s)", err, loginBodyStr)
		}
		token := extractQueryParam(t, loginEnvelope.RedirectTo, "token")
		if token == "" {
			t.Fatalf("expected a rotation token in the login redirect, got %q", loginEnvelope.RedirectTo)
		}

		const newPassword = "Brand-New-Real-Pw-4!"
		confirmBody, _ := json.Marshal(map[string]any{
			"token":    token,
			"password": newPassword,
			"clientId": adminConsoleClientID,
		})
		confirmReq, err := http.NewRequest(http.MethodPost, bffBase+"/api/auth/password-rotation/confirm", bytes.NewReader(confirmBody))
		if err != nil {
			t.Fatalf("build confirm request: %v", err)
		}
		confirmReq.Header.Set("Content-Type", "application/json")
		confirmResp, err := http.DefaultClient.Do(confirmReq)
		if err != nil {
			t.Fatalf("POST /api/auth/password-rotation/confirm: %v", err)
		}
		defer confirmResp.Body.Close()
		confirmBodyStr, _ := readBody(confirmResp.Body)
		if confirmResp.StatusCode != http.StatusOK {
			t.Fatalf("POST /api/auth/password-rotation/confirm: status = %d, want 200, body=%s", confirmResp.StatusCode, confirmBodyStr)
		}

		// Now that a real rotation completed, reissue is refused — its OWN
		// domain code, not a generic conflict.
		secondReissueResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost,
			bffBase+"/platform/api/tenants/"+tenantID+"/users/"+bootstrap.UserID+"/reissue-onboarding-credential", "", nil)
		defer secondReissueResp.Body.Close()
		secondReissueBodyStr, _ := readBody(secondReissueResp.Body)
		if secondReissueResp.StatusCode != http.StatusConflict {
			t.Fatalf("reissue after rotation: status = %d, want 409, body=%s", secondReissueResp.StatusCode, secondReissueBodyStr)
		}
		var secondReissueErr adminAPIErrorBody
		if err := json.Unmarshal([]byte(secondReissueBodyStr), &secondReissueErr); err != nil {
			t.Fatalf("decode reissue-after-rotation error: %v (body=%s)", err, secondReissueBodyStr)
		}
		if secondReissueErr.Error != "tenant_onboarding.no_pending_temp_credential" {
			t.Errorf("reissue-after-rotation error code = %q, want tenant_onboarding.no_pending_temp_credential (must not be flattened to a generic conflict)",
				secondReissueErr.Error)
		}
	})

	// ---- 3. bootstrap-admin against a non-ACTIVE tenant is a domain 403,
	// NOT an authorization denial — the regression test for
	// platform_api_result.go's fix. Before the fix this 403 would have
	// cleared bff_platform_session and reported not_platform_admin, silently
	// signing the operator out of a perfectly valid session. ----
	t.Run("BootstrapAgainstNonActiveTenant_Returns403WithoutClearingTheSession", func(t *testing.T) {
		slug := testsupport.Slug("prov-only")
		provisionBody, _ := json.Marshal(map[string]string{"slug": slug, "name": "Provisioning Only"})
		provisionResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/tenants", "application/json", bytes.NewReader(provisionBody))
		provisionBodyStr, _ := readBody(provisionResp.Body)
		provisionResp.Body.Close()
		if provisionResp.StatusCode != http.StatusCreated {
			t.Fatalf("provision (no activate): status = %d, want 201, body=%s", provisionResp.StatusCode, provisionBodyStr)
		}
		var provisioned tenantViewBody
		if err := json.Unmarshal([]byte(provisionBodyStr), &provisioned); err != nil {
			t.Fatalf("decode provisioned tenant: %v (body=%s)", err, provisionBodyStr)
		}
		if provisioned.Status != "PROVISIONING" {
			t.Fatalf("freshly provisioned tenant status = %q, want PROVISIONING (this subtest needs it un-activated)", provisioned.Status)
		}

		email := testsupport.Email("bootstrap-against-provisioning")
		bootstrapBody, _ := json.Marshal(map[string]string{"email": email})
		bootstrapResp := doWithCSRF(t, bootstrapClient, bffBase, http.MethodPost, bffBase+"/platform/api/tenants/"+provisioned.ID+"/bootstrap-admin",
			"application/json", bytes.NewReader(bootstrapBody))
		defer bootstrapResp.Body.Close()
		bootstrapBodyStr, _ := readBody(bootstrapResp.Body)
		if bootstrapResp.StatusCode != http.StatusForbidden {
			t.Fatalf("bootstrap-admin against PROVISIONING tenant: status = %d, want 403, body=%s", bootstrapResp.StatusCode, bootstrapBodyStr)
		}
		var errBody adminAPIErrorBody
		if err := json.Unmarshal([]byte(bootstrapBodyStr), &errBody); err != nil {
			t.Fatalf("decode error: %v (body=%s)", err, bootstrapBodyStr)
		}
		if errBody.Error != "tenant.not_active" {
			t.Errorf("error code = %q, want tenant.not_active (a domain 403, not an authorization denial)", errBody.Error)
		}
		if cookie := platformSessionCookie(bootstrapResp); cookie != nil && cookie.MaxAge < 0 {
			t.Errorf("bootstrap-admin's domain 403 cleared bff_platform_session (MaxAge<0) — a business-rule refusal must not end a valid session")
		}

		// The session must still be genuinely usable, not merely
		// cookie-present — a real authenticated call proves it.
		sessionResp, err := bootstrapClient.Get(bffBase + "/platform/api/session")
		if err != nil {
			t.Fatalf("GET /platform/api/session after the 403: %v", err)
		}
		defer sessionResp.Body.Close()
		var session platformSessionResponseBody
		if err := json.NewDecoder(sessionResp.Body).Decode(&session); err != nil {
			t.Fatalf("decode session: %v", err)
		}
		if !session.Authenticated {
			t.Errorf("bootstrap admin session no longer authenticated after a domain 403 — the session must have survived")
		}
	})

	// ---- 4. Malformed path ids are rejected before ever reaching the
	// backend — 400, not 404/500/whatever the backend would do with a
	// non-UUID path segment. ----
	t.Run("MalformedIdsRejectedAsBadRequest", func(t *testing.T) {
		cases := []struct {
			name   string
			method string
			path   string
		}{
			{"users list, bad tenant id", http.MethodGet, "/platform/api/tenants/not-a-uuid/users?page=0&size=20"},
			{"bootstrap, bad tenant id", http.MethodPost, "/platform/api/tenants/not-a-uuid/bootstrap-admin"},
			{"reissue, bad tenant id", http.MethodPost, "/platform/api/tenants/not-a-uuid/users/also-not-a-uuid/reissue-onboarding-credential"},
		}
		for _, tc := range cases {
			t.Run(tc.name, func(t *testing.T) {
				var resp *http.Response
				var err error
				if tc.method == http.MethodGet {
					resp, err = bootstrapClient.Get(bffBase + tc.path)
				} else {
					resp = doWithCSRF(t, bootstrapClient, bffBase, tc.method, bffBase+tc.path, "application/json", bytes.NewReader([]byte("{}")))
				}
				if err != nil {
					t.Fatalf("%s %s: %v", tc.method, tc.path, err)
				}
				defer resp.Body.Close()
				bodyStr, _ := readBody(resp.Body)
				if resp.StatusCode != http.StatusBadRequest {
					t.Errorf("%s %s: status = %d, want 400, body=%s", tc.method, tc.path, resp.StatusCode, bodyStr)
				}
			})
		}
	})
}

// extractQueryParam pulls a single query parameter's value out of rawURL —
// the rotation redirect envelope's redirectTo is a full URL.
func extractQueryParam(t *testing.T, rawURL, name string) string {
	t.Helper()
	parsed, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse URL %q: %v", rawURL, err)
	}
	return parsed.Query().Get(name)
}
