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

// Stage UI-3e: live, Docker-gated proof that registration-config read/update
// works end to end through the REAL backend AND a REAL BFF. Reuses
// admin_users_test.go's harness helpers (newTenantWithAdmin,
// establishAdminSession, doWithCSRF) and admin_console_test.go's
// (newAdminConsoleServer, newBrowserLikeClient, readBody, assertNoTokenLeak)
// verbatim. testsupport.Fixtures.SetRegistrationMode already exercises this
// backend endpoint directly (bypassing the BFF) for registration_proxy_test.go's
// four-mode drive — this test instead proves the BFF's own GET/PUT route pair.

type registrationConfigViewBody struct {
	TenantID string `json:"tenantId"`
	Mode     string `json:"mode"`
}

func TestAdminRegistrationConfig_ReadUpdateRoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	tenantID, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "regconfig-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	// A freshly provisioned tenant already has an explicit config row (seeded
	// at provisioning with the platform default — RegistrationConfigProvisioningCallback)
	// — GET never 404s, and always returns a valid mode literal.
	getResp, err := client.Get(bffBase + "/t/" + slug + "/api/registration-config")
	if err != nil {
		t.Fatalf("GET registration-config: %v", err)
	}
	defer getResp.Body.Close()
	getBodyStr, _ := readBody(getResp.Body)
	if getResp.StatusCode != http.StatusOK {
		t.Fatalf("GET registration-config: status = %d, want 200, body=%s", getResp.StatusCode, getBodyStr)
	}
	var initial registrationConfigViewBody
	if err := json.Unmarshal([]byte(getBodyStr), &initial); err != nil {
		t.Fatalf("decode initial registration-config: %v (body=%s)", err, getBodyStr)
	}
	if initial.TenantID != tenantID {
		t.Errorf("initial registration-config: tenantId = %q, want %q", initial.TenantID, tenantID)
	}
	validModes := map[string]bool{"OPEN": true, "EMAIL_VERIFIED": true, "ADMIN_APPROVED": true, "INVITE_ONLY": true}
	if !validModes[initial.Mode] {
		t.Errorf("initial registration-config: mode = %q, want one of the four RegistrationMode literals", initial.Mode)
	}
	assertNoTokenLeak(t, "GET registration-config (initial)", getBodyStr)

	// PUT a mode different from every default candidate's plausible value —
	// INVITE_ONLY is never a sane platform default, so this always proves a
	// genuine change regardless of what the platform default is configured to.
	putBody, _ := json.Marshal(map[string]any{"mode": "INVITE_ONLY"})
	putResp := doWithCSRF(t, client, bffBase, http.MethodPut, bffBase+"/t/"+slug+"/api/registration-config", "application/json", bytes.NewReader(putBody))
	defer putResp.Body.Close()
	putBodyStr, _ := readBody(putResp.Body)
	if putResp.StatusCode != http.StatusOK {
		t.Fatalf("PUT registration-config: status = %d, want 200, body=%s", putResp.StatusCode, putBodyStr)
	}
	var updated registrationConfigViewBody
	if err := json.Unmarshal([]byte(putBodyStr), &updated); err != nil {
		t.Fatalf("decode updated registration-config: %v (body=%s)", err, putBodyStr)
	}
	if updated.Mode != "INVITE_ONLY" {
		t.Errorf("PUT registration-config: mode = %q, want INVITE_ONLY", updated.Mode)
	}
	assertNoTokenLeak(t, "PUT registration-config", putBodyStr)

	// Re-GET reflects the write.
	regetResp, err := client.Get(bffBase + "/t/" + slug + "/api/registration-config")
	if err != nil {
		t.Fatalf("GET registration-config (after update): %v", err)
	}
	defer regetResp.Body.Close()
	regetBodyStr, _ := readBody(regetResp.Body)
	if regetResp.StatusCode != http.StatusOK {
		t.Fatalf("GET registration-config (after update): status = %d, want 200, body=%s", regetResp.StatusCode, regetBodyStr)
	}
	var reget registrationConfigViewBody
	if err := json.Unmarshal([]byte(regetBodyStr), &reget); err != nil {
		t.Fatalf("decode re-GET registration-config: %v (body=%s)", err, regetBodyStr)
	}
	if reget.Mode != "INVITE_ONLY" {
		t.Errorf("re-GET registration-config: mode = %q, want INVITE_ONLY (the write must be a real side effect, not just an echoed response)", reget.Mode)
	}
}
