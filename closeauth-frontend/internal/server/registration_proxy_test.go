package server

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"closeauth-frontend/internal/backend"
	"closeauth-frontend/internal/proxy"
	"closeauth-frontend/internal/testsupport"
)

// TestRegistrationProxy_AllFourModes is Stage UI-2b, Deliverable 3's "prove
// it": drives all four registration modes, plus the documented error cases,
// through the REAL BFF proxy routes (handlers_registration_proxy.go) against
// the real harness — not directly at the backend, since the point is to
// prove the proxy layer relays these correctly (form body in, JSON/empty
// body out, status codes preserved), not to re-prove the backend's own
// registration-mode logic (already proven by the Java IT module's
// RegistrationModesJourneyTest / EmailVerifiedRegistrationJourneyTest).
//
// Each mode gets its own fresh tenant + confidential client (Fixtures), so
// the four sub-tests never interfere with each other even though they share
// one Stack/httptest.Server for the whole file.
func TestRegistrationProxy_AllFourModes(t *testing.T) {
	stack := testsupport.Get(t)
	ctx := context.Background()
	fixtures := testsupport.NewFixtures(stack)

	platformToken, err := fixtures.PlatformAdminToken(ctx)
	if err != nil {
		t.Fatalf("mint platform admin token: %v", err)
	}

	mailpit, err := stack.Mailpit(ctx)
	if err != nil {
		t.Fatalf("build mailpit client: %v", err)
	}

	s := &Server{authProxy: proxy.New(stack.AppBaseURI() + stack.ContextPath())}
	ts := httptest.NewServer(s.RegisterRoutes())
	defer ts.Close()

	const password = "Ui2b-Registration-Pw-123!"

	// provisionInMode gives each sub-test its own isolated tenant + client,
	// with the tenant's registration mode set via the admin API exactly as
	// a real tenant admin would (RegistrationConfigController, §4.6) —
	// never assumed from a platform default.
	provisionInMode := func(t *testing.T, mode string) (tenantID, clientID string) {
		t.Helper()
		tenantID, err := fixtures.ProvisionActiveTenant(ctx, platformToken)
		if err != nil {
			t.Fatalf("provision tenant: %v", err)
		}
		if err := fixtures.SetRegistrationMode(ctx, platformToken, tenantID, mode); err != nil {
			t.Fatalf("set registration mode %s: %v", mode, err)
		}
		creds, err := fixtures.RegisterConfidentialClient(ctx, platformToken, tenantID, "http://localhost:9999/callback")
		if err != nil {
			t.Fatalf("register client: %v", err)
		}
		return tenantID, creds.ClientID
	}

	t.Run("OPEN activates immediately and rejects a duplicate email with 409", func(t *testing.T) {
		_, clientID := provisionInMode(t, "OPEN")
		email := testsupport.Email("open")

		resp := postRegister(t, ts, url.Values{
			"email": {email}, "password": {password}, "client_id": {clientID},
		})
		result := decodeRegistration(t, resp)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d body=%+v", resp.StatusCode, result)
		}
		if result.Status != "ACTIVE" || result.Mode != "OPEN" || result.EmailVerificationSent {
			t.Fatalf("OPEN registration: expected ACTIVE/OPEN/no-email, got %+v", result)
		}
		t.Logf("OPEN via proxy: %+v", result)

		dup := postRegister(t, ts, url.Values{
			"email": {email}, "password": {password}, "client_id": {clientID},
		})
		defer dup.Body.Close()
		if dup.StatusCode != http.StatusConflict {
			t.Fatalf("expected 409 on duplicate email in the same tenant (via proxy), got %d", dup.StatusCode)
		}
		problem := decodeProblem(t, dup)
		t.Logf("duplicate email via proxy: HTTP 409 code=%s", problem.Code)
	})

	t.Run("EMAIL_VERIFIED registers PENDING, real Mailpit code confirms via proxy", func(t *testing.T) {
		_, clientID := provisionInMode(t, "EMAIL_VERIFIED")
		email := testsupport.Email("verify")

		resp := postRegister(t, ts, url.Values{
			"email": {email}, "password": {password}, "client_id": {clientID},
		})
		result := decodeRegistration(t, resp)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d body=%+v", resp.StatusCode, result)
		}
		if result.Status != "PENDING" || result.Mode != "EMAIL_VERIFIED" || !result.EmailVerificationSent {
			t.Fatalf("EMAIL_VERIFIED registration: expected PENDING/EMAIL_VERIFIED/emailSent, got %+v", result)
		}
		t.Logf("EMAIL_VERIFIED via proxy: %+v", result)

		message, err := mailpit.WaitForMessageTo(ctx, email, 30*time.Second)
		if err != nil {
			t.Fatalf("wait for verification email: %v", err)
		}
		code, err := testsupport.ExtractSixDigitCode(message.Text)
		if err != nil {
			t.Fatalf("extract verification code: %v", err)
		}
		t.Logf("captured real verification email, extracted code (redacted length=%d)", len(code))

		confirm := postForm(t, ts, "/verify-email/confirm", url.Values{
			"email": {email}, "code": {code}, "client_id": {clientID},
		})
		defer confirm.Body.Close()
		if confirm.StatusCode != http.StatusOK {
			body, _ := io.ReadAll(confirm.Body)
			t.Fatalf("expected 200 confirming with the real code via proxy, got %d body=%s", confirm.StatusCode, body)
		}
		t.Logf("verify-email/confirm via proxy: HTTP 200 (verified)")
	})

	t.Run("ADMIN_APPROVED registers PENDING with no verification email", func(t *testing.T) {
		_, clientID := provisionInMode(t, "ADMIN_APPROVED")
		email := testsupport.Email("approve")

		resp := postRegister(t, ts, url.Values{
			"email": {email}, "password": {password}, "client_id": {clientID},
		})
		result := decodeRegistration(t, resp)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("expected 200, got %d body=%+v", resp.StatusCode, result)
		}
		if result.Status != "PENDING" || result.Mode != "ADMIN_APPROVED" || result.EmailVerificationSent {
			t.Fatalf("ADMIN_APPROVED registration: expected PENDING/ADMIN_APPROVED/no-email, got %+v", result)
		}
		t.Logf("ADMIN_APPROVED via proxy: %+v", result)
	})

	t.Run("INVITE_ONLY: no invite -> 403, admin-issued invite via Mailpit -> ACTIVE via proxy", func(t *testing.T) {
		tenantID, clientID := provisionInMode(t, "INVITE_ONLY")
		email := testsupport.Email("invite")

		noInvite := postRegister(t, ts, url.Values{
			"email": {email}, "password": {password}, "client_id": {clientID},
		})
		defer noInvite.Body.Close()
		if noInvite.StatusCode != http.StatusForbidden {
			t.Fatalf("expected 403 registering with no invite (via proxy), got %d", noInvite.StatusCode)
		}
		problem := decodeProblem(t, noInvite)
		t.Logf("no-invite via proxy: HTTP 403 code=%s", problem.Code)

		if _, err := fixtures.IssueInvite(ctx, platformToken, tenantID, email); err != nil {
			t.Fatalf("issue invite: %v", err)
		}
		message, err := mailpit.WaitForMessageTo(ctx, email, 30*time.Second)
		if err != nil {
			t.Fatalf("wait for invite email: %v", err)
		}
		inviteToken, err := testsupport.ExtractLinkParam(message.Text, "invite")
		if err != nil {
			t.Fatalf("extract invite token: %v", err)
		}
		t.Logf("captured real invite email, extracted token (redacted length=%d)", len(inviteToken))

		withInvite := postRegister(t, ts, url.Values{
			"email": {email}, "password": {password}, "client_id": {clientID}, "invite_token": {inviteToken},
		})
		result := decodeRegistration(t, withInvite)
		if withInvite.StatusCode != http.StatusOK {
			t.Fatalf("expected 200 registering with the real invite token via proxy, got %d body=%+v", withInvite.StatusCode, result)
		}
		if result.Status != "ACTIVE" || result.Mode != "INVITE_ONLY" {
			t.Fatalf("INVITE_ONLY registration: expected immediate ACTIVE, got %+v", result)
		}
		t.Logf("INVITE_ONLY via proxy (invite consumed): %+v", result)
	})

	t.Run("verify-email/confirm: wrong code -> generic 400, enough wrong attempts -> 429", func(t *testing.T) {
		_, clientID := provisionInMode(t, "EMAIL_VERIFIED")
		email := testsupport.Email("lockout")

		reg := postRegister(t, ts, url.Values{
			"email": {email}, "password": {password}, "client_id": {clientID},
		})
		result := decodeRegistration(t, reg)
		if reg.StatusCode != http.StatusOK || !result.EmailVerificationSent {
			t.Fatalf("setup: expected a PENDING EMAIL_VERIFIED registration, got %d body=%+v", reg.StatusCode, result)
		}
		// Don't need the real code for this sub-test — every attempt below is
		// deliberately wrong. Draining the real email out of Mailpit isn't
		// necessary; the attempt-lockout counts calls, not correctness.
		wrongCode := "000000"

		// CloseAuthProperties.OneTimeToken.verifyMaxAttemptsPerWindow defaults
		// to 5 (verified against backend source, common/config/properties/
		// CloseAuthProperties.java) — the 6th attempt within the window trips
		// the lockout. The first 5 are the generic, enumeration-safe 400
		// (never a code-specific error); the 6th is 429.
		const maxAttemptsBeforeLockout = 5
		var statuses []int
		for i := 0; i < maxAttemptsBeforeLockout+1; i++ {
			resp := postForm(t, ts, "/verify-email/confirm", url.Values{
				"email": {email}, "code": {wrongCode}, "client_id": {clientID},
			})
			statuses = append(statuses, resp.StatusCode)
			resp.Body.Close()
		}
		t.Logf("verify-email/confirm wrong-code attempts via proxy: %v", statuses)

		for i, status := range statuses[:maxAttemptsBeforeLockout] {
			if status != http.StatusBadRequest {
				t.Fatalf("attempt %d: expected generic 400 (invalid code, not yet locked out), got %d", i+1, status)
			}
		}
		last := statuses[len(statuses)-1]
		if last != http.StatusTooManyRequests {
			t.Fatalf("expected the %dth attempt to trip the attempt-lockout (429), got %d", maxAttemptsBeforeLockout+1, last)
		}
	})
}

// ---- helpers ---------------------------------------------------------------

func postForm(t *testing.T, ts *httptest.Server, path string, form url.Values) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, ts.URL+path, strings.NewReader(form.Encode()))
	if err != nil {
		t.Fatalf("build request for %s: %v", path, err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("proxy request %s: %v", path, err)
	}
	return resp
}

func postRegister(t *testing.T, ts *httptest.Server, form url.Values) *http.Response {
	t.Helper()
	return postForm(t, ts, "/register", form)
}

type registrationResultView struct {
	UserID                string `json:"userId"`
	Status                string `json:"status"`
	Mode                  string `json:"mode"`
	EmailVerificationSent bool   `json:"emailVerificationSent"`
}

// decodeRegistration decodes a /register response body as JSON on the
// success path. On a non-2xx, RegistrationController's own error responses
// are either empty-bodied (bad client_id) or RFC 7807 (409/403/400 with
// errors) — callers asserting those cases should use decodeProblem instead;
// this helper tolerates a body that fails to decode by returning the zero
// value rather than failing the test outright, since some callers only care
// about the status code.
func decodeRegistration(t *testing.T, resp *http.Response) registrationResultView {
	t.Helper()
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read /register response body: %v", err)
	}
	var v registrationResultView
	_ = json.Unmarshal(body, &v) // best-effort; error-path bodies may not match this shape
	return v
}

func decodeProblem(t *testing.T, resp *http.Response) backend.Problem {
	t.Helper()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read problem response body: %v", err)
	}
	problem, err := backend.ParseProblem(body)
	if err != nil {
		t.Fatalf("parse problem+json: %v (body=%s)", err, body)
	}
	return problem
}
