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

// Stage UI-3e: live, Docker-gated proofs that tenant branding works end to
// end through the REAL backend AND a REAL BFF — the BFF's first PUT
// handlers. Reuses admin_users_test.go's harness helpers (newTenantWithAdmin,
// establishAdminSession, doWithCSRF, adminAPIErrorBody) and
// admin_console_test.go's (newAdminConsoleServer, newBrowserLikeClient,
// readBody, assertNoTokenLeak) verbatim.

type brandingViewBody struct {
	LogoUrl         string `json:"logoUrl"`
	PrimaryColor    string `json:"primaryColor"`
	BackgroundColor string `json:"backgroundColor"`
	AccentColor     string `json:"accentColor"`
	CompanyName     string `json:"companyName"`
}

func TestAdminBranding_ReadUpdateRoundTrip(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "branding-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	// A freshly provisioned tenant has no branding row: logoUrl/companyName
	// resolve to "" (never null on the wire — FEATURES_AND_USE_CASES §10),
	// not the raw JSON absence of a field.
	getResp, err := client.Get(bffBase + "/t/" + slug + "/api/branding")
	if err != nil {
		t.Fatalf("GET branding: %v", err)
	}
	defer getResp.Body.Close()
	getBodyStr, _ := readBody(getResp.Body)
	if getResp.StatusCode != http.StatusOK {
		t.Fatalf("GET branding: status = %d, want 200, body=%s", getResp.StatusCode, getBodyStr)
	}
	var initial brandingViewBody
	if err := json.Unmarshal([]byte(getBodyStr), &initial); err != nil {
		t.Fatalf("decode initial branding: %v (body=%s)", err, getBodyStr)
	}
	if initial.LogoUrl != "" {
		t.Errorf("initial branding: logoUrl = %q, want \"\" (unset, not null)", initial.LogoUrl)
	}
	if initial.CompanyName != "" {
		t.Errorf("initial branding: companyName = %q, want \"\" (unset, not null)", initial.CompanyName)
	}
	assertNoTokenLeak(t, "GET branding (initial)", getBodyStr)

	// PUT all five fields.
	update := map[string]any{
		"logoUrl":         "https://cdn.example.test/acme-logo.png",
		"primaryColor":    "#112233",
		"backgroundColor": "#445566",
		"accentColor":     "#778899",
		"companyName":     "Acme Corp",
	}
	updateBody, _ := json.Marshal(update)
	putResp := doWithCSRF(t, client, bffBase, http.MethodPut, bffBase+"/t/"+slug+"/api/branding", "application/json", bytes.NewReader(updateBody))
	defer putResp.Body.Close()
	putBodyStr, _ := readBody(putResp.Body)
	if putResp.StatusCode != http.StatusOK {
		t.Fatalf("PUT branding: status = %d, want 200, body=%s", putResp.StatusCode, putBodyStr)
	}
	var updated brandingViewBody
	if err := json.Unmarshal([]byte(putBodyStr), &updated); err != nil {
		t.Fatalf("decode updated branding: %v (body=%s)", err, putBodyStr)
	}
	if updated.LogoUrl != update["logoUrl"] || updated.PrimaryColor != update["primaryColor"] ||
		updated.BackgroundColor != update["backgroundColor"] || updated.AccentColor != update["accentColor"] ||
		updated.CompanyName != update["companyName"] {
		t.Errorf("PUT branding response = %+v, want it to echo %+v", updated, update)
	}
	assertNoTokenLeak(t, "PUT branding", putBodyStr)

	// Re-GET reflects the write — a real side effect, not just an echoed response.
	regetResp, err := client.Get(bffBase + "/t/" + slug + "/api/branding")
	if err != nil {
		t.Fatalf("GET branding (after update): %v", err)
	}
	defer regetResp.Body.Close()
	regetBodyStr, _ := readBody(regetResp.Body)
	if regetResp.StatusCode != http.StatusOK {
		t.Fatalf("GET branding (after update): status = %d, want 200, body=%s", regetResp.StatusCode, regetBodyStr)
	}
	var reget brandingViewBody
	if err := json.Unmarshal([]byte(regetBodyStr), &reget); err != nil {
		t.Fatalf("decode re-GET branding: %v (body=%s)", err, regetBodyStr)
	}
	if reget != updated {
		t.Errorf("re-GET branding = %+v, want it to match the PUT response %+v", reget, updated)
	}
}

func TestAdminBranding_RejectsBadColorAndNonHttpsLogo(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "branding-bad-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	// Bean-validation failure: not a #RRGGBB hex color -> 400 with a
	// field-keyed errors map (validation.failed), same shape
	// tenantAdminProblem.ts's validationErrors branch expects.
	badColorBody, _ := json.Marshal(map[string]any{"primaryColor": "red"})
	badColorResp := doWithCSRF(t, client, bffBase, http.MethodPut, bffBase+"/t/"+slug+"/api/branding", "application/json", bytes.NewReader(badColorBody))
	defer badColorResp.Body.Close()
	badColorBodyStr, _ := readBody(badColorResp.Body)
	if badColorResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("PUT branding (bad color): status = %d, want 400, body=%s", badColorResp.StatusCode, badColorBodyStr)
	}
	var badColorErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(badColorBodyStr), &badColorErr); err != nil {
		t.Fatalf("decode bad-color error: %v (body=%s)", err, badColorBodyStr)
	}
	if _, ok := badColorErr.Errors["primaryColor"]; !ok {
		t.Errorf("bad-color error body missing field-keyed 'primaryColor' entry, got errors=%v (full body=%s)", badColorErr.Errors, badColorBodyStr)
	}

	// Service-level validation: a non-https logo URL -> 400
	// branding.logo_url_not_https, a domain code with NO errors map (empty
	// exception context) — this is exactly why tenantAdminProblem.ts's
	// `error` kind needed a `code` field.
	badLogoBody, _ := json.Marshal(map[string]any{"logoUrl": "http://insecure.example.test/logo.png"})
	badLogoResp := doWithCSRF(t, client, bffBase, http.MethodPut, bffBase+"/t/"+slug+"/api/branding", "application/json", bytes.NewReader(badLogoBody))
	defer badLogoResp.Body.Close()
	badLogoBodyStr, _ := readBody(badLogoResp.Body)
	if badLogoResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("PUT branding (non-https logo): status = %d, want 400, body=%s", badLogoResp.StatusCode, badLogoBodyStr)
	}
	var badLogoErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(badLogoBodyStr), &badLogoErr); err != nil {
		t.Fatalf("decode non-https-logo error: %v (body=%s)", err, badLogoBodyStr)
	}
	if badLogoErr.Error != "branding.logo_url_not_https" {
		t.Errorf("non-https logo: error code = %q, want branding.logo_url_not_https (must not be flattened to bad_gateway)", badLogoErr.Error)
	}

	// A malformed URL (not a valid URI at all) -> the sibling code.
	malformedBody, _ := json.Marshal(map[string]any{"logoUrl": "not a url at all ://"})
	malformedResp := doWithCSRF(t, client, bffBase, http.MethodPut, bffBase+"/t/"+slug+"/api/branding", "application/json", bytes.NewReader(malformedBody))
	defer malformedResp.Body.Close()
	malformedBodyStr, _ := readBody(malformedResp.Body)
	if malformedResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("PUT branding (malformed logo): status = %d, want 400, body=%s", malformedResp.StatusCode, malformedBodyStr)
	}
	var malformedErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(malformedBodyStr), &malformedErr); err != nil {
		t.Fatalf("decode malformed-logo error: %v (body=%s)", err, malformedBodyStr)
	}
	if malformedErr.Error != "branding.invalid_logo_url" {
		t.Errorf("malformed logo: error code = %q, want branding.invalid_logo_url", malformedErr.Error)
	}
}
