package server

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"closeauth-frontend/internal/testsupport"
)

// Stage UI-3e: live, Docker-gated proofs that the read-only audit query
// surface works end to end through the REAL backend AND a REAL BFF. Reuses
// admin_users_test.go's harness helpers (newTenantWithAdmin,
// establishAdminSession, doWithCSRF, adminAPIErrorBody, pageViewBody) and
// admin_console_test.go's (newAdminConsoleServer, newBrowserLikeClient,
// readBody, assertNoTokenLeak) verbatim.
//
// Audit events reach the query API asynchronously (outbox -> drain worker,
// ~2s — FEATURES_AND_USE_CASES.md §13) — auditEventPollInterval/
// waitForAuditEvent below is this file's bounded-poll equivalent of
// testsupport.MailpitClient.WaitForMessageTo, "wait, don't sleep."

type auditEventViewBody struct {
	ID                      string          `json:"id"`
	EventType               string          `json:"eventType"`
	Outcome                 string          `json:"outcome"`
	TenantID                *string         `json:"tenantId"`
	SubjectUserID           *string         `json:"subjectUserId"`
	ActorUserID             *string         `json:"actorUserId"`
	ActorPlatformAdminID    *string         `json:"actorPlatformAdminId"`
	ActorClientRegisteredID *string         `json:"actorClientRegisteredId"`
	ResourceServerID        *string         `json:"resourceServerId"`
	IPAddress               *string         `json:"ipAddress"`
	UserAgent               *string         `json:"userAgent"`
	ErrorCode               *string         `json:"errorCode"`
	EventData               json.RawMessage `json:"eventData"`
	CreatedAt               string          `json:"createdAt"`
}

const auditEventPollInterval = 500 * time.Millisecond

// waitForAuditEvent bounded-polls GET /audit-events with the given query
// string until at least one item comes back, then returns the first one.
// Fails the test (not a hang) if none arrives within timeout.
func waitForAuditEvent(t *testing.T, client *http.Client, bffBase, slug, query string, timeout time.Duration) auditEventViewBody {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		resp, err := client.Get(bffBase + "/t/" + slug + "/api/audit-events?" + query)
		if err != nil {
			t.Fatalf("GET audit-events: %v", err)
		}
		body, _ := readBody(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET audit-events: status = %d, want 200, body=%s", resp.StatusCode, body)
		}
		var page pageViewBody[auditEventViewBody]
		if err := json.Unmarshal([]byte(body), &page); err != nil {
			t.Fatalf("decode audit-events page: %v (body=%s)", err, body)
		}
		if len(page.Items) > 0 {
			return page.Items[0]
		}
		if !time.Now().Before(deadline) {
			t.Fatalf("no audit event matching %q arrived within %s (outbox drain worker runs ~every 2s)", query, timeout)
		}
		time.Sleep(auditEventPollInterval)
	}
}

func TestAdminAuditEvents_FilteredQueryFindsAdminAction(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "audit-filter-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	newEmail := testsupport.Email("audit-target")
	createBody, _ := json.Marshal(map[string]any{
		"email":         newEmail,
		"password":      "Audit-Target-Pw-123!",
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

	query := fmt.Sprintf("event_type=USER_CREATED&user_id=%s&page=0&size=20", created.ID)
	event := waitForAuditEvent(t, client, bffBase, slug, query, 15*time.Second)

	if event.SubjectUserID == nil || *event.SubjectUserID != created.ID {
		t.Errorf("audit event subjectUserId = %v, want %q", event.SubjectUserID, created.ID)
	}
	if event.EventType != "USER_CREATED" {
		t.Errorf("audit event eventType = %q, want USER_CREATED", event.EventType)
	}

	eventJSON, _ := json.Marshal(event)
	assertNoTokenLeak(t, "GET audit-events", string(eventJSON))
}

// TestAdminAuditEvents_ToBoundaryIsExclusive proves `to` behaves exactly as
// documented (API_REFERENCE.md §6, verified at the boundary by IT-8):
// createdAt < to, so a query whose `to` equals an event's own createdAt
// excludes it, and `to` one millisecond later includes it.
func TestAdminAuditEvents_ToBoundaryIsExclusive(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "audit-boundary-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	newEmail := testsupport.Email("audit-boundary-target")
	createBody, _ := json.Marshal(map[string]any{
		"email":         newEmail,
		"password":      "Audit-Boundary-Pw-123!",
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

	baseQuery := fmt.Sprintf("event_type=USER_CREATED&user_id=%s", created.ID)
	event := waitForAuditEvent(t, client, bffBase, slug, baseQuery+"&page=0&size=20", 15*time.Second)

	createdAt, err := time.Parse(time.RFC3339Nano, event.CreatedAt)
	if err != nil {
		t.Fatalf("parse event.createdAt %q: %v", event.CreatedAt, err)
	}
	after := createdAt.Add(1 * time.Millisecond).Format(time.RFC3339Nano)

	// to == the event's own createdAt -> excluded (createdAt < to is false).
	excludedResp, err := client.Get(bffBase + "/t/" + slug + "/api/audit-events?" + baseQuery + "&to=" + event.CreatedAt)
	if err != nil {
		t.Fatalf("GET audit-events (to=createdAt): %v", err)
	}
	defer excludedResp.Body.Close()
	excludedBody, _ := readBody(excludedResp.Body)
	if excludedResp.StatusCode != http.StatusOK {
		t.Fatalf("GET audit-events (to=createdAt): status = %d, want 200, body=%s", excludedResp.StatusCode, excludedBody)
	}
	var excludedPage pageViewBody[auditEventViewBody]
	if err := json.Unmarshal([]byte(excludedBody), &excludedPage); err != nil {
		t.Fatalf("decode audit-events page (to=createdAt): %v (body=%s)", err, excludedBody)
	}
	for _, item := range excludedPage.Items {
		if item.ID == event.ID {
			t.Errorf("to=%s (the event's own createdAt) still returned the event — `to` must be exclusive", event.CreatedAt)
		}
	}

	// to == createdAt + 1ms -> included.
	includedResp, err := client.Get(bffBase + "/t/" + slug + "/api/audit-events?" + baseQuery + "&to=" + after)
	if err != nil {
		t.Fatalf("GET audit-events (to=createdAt+1ms): %v", err)
	}
	defer includedResp.Body.Close()
	includedBody, _ := readBody(includedResp.Body)
	if includedResp.StatusCode != http.StatusOK {
		t.Fatalf("GET audit-events (to=createdAt+1ms): status = %d, want 200, body=%s", includedResp.StatusCode, includedBody)
	}
	var includedPage pageViewBody[auditEventViewBody]
	if err := json.Unmarshal([]byte(includedBody), &includedPage); err != nil {
		t.Fatalf("decode audit-events page (to=createdAt+1ms): %v (body=%s)", err, includedBody)
	}
	found := false
	for _, item := range includedPage.Items {
		if item.ID == event.ID {
			found = true
		}
	}
	if !found {
		t.Errorf("to=%s (createdAt+1ms) did not return the event — `to` must include timestamps strictly before it", after)
	}
}

func TestAdminAuditEvents_BadFilterReturns400Intact(t *testing.T) {
	stack := testsupport.Get(t)
	fixtures := testsupport.NewFixtures(stack)
	ctx := context.Background()

	_, slug, adminClientID, _, adminEmail, adminPassword, _ := newTenantWithAdmin(t, stack, fixtures, ctx, "audit-badfilter-admin")

	s := newAdminConsoleServer(stack, 30*time.Second)
	stack.ServeBFF(t, s.RegisterRoutes())
	bffBase := stack.BFFBaseURL()
	client := newBrowserLikeClient(t)
	establishAdminSession(t, client, bffBase, slug, adminClientID, adminEmail, adminPassword)

	badTypeResp, err := client.Get(bffBase + "/t/" + slug + "/api/audit-events?event_type=NOT_A_REAL_TYPE")
	if err != nil {
		t.Fatalf("GET audit-events (bad event_type): %v", err)
	}
	defer badTypeResp.Body.Close()
	badTypeBody, _ := readBody(badTypeResp.Body)
	if badTypeResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("GET audit-events (bad event_type): status = %d, want 400, body=%s", badTypeResp.StatusCode, badTypeBody)
	}
	var badTypeErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(badTypeBody), &badTypeErr); err != nil {
		t.Fatalf("decode bad event_type error: %v (body=%s)", err, badTypeBody)
	}
	if badTypeErr.Error != "audit.invalid_event_type" {
		t.Errorf("bad event_type: error code = %q, want audit.invalid_event_type (must not be flattened to bad_gateway)", badTypeErr.Error)
	}

	badFromResp, err := client.Get(bffBase + "/t/" + slug + "/api/audit-events?from=yesterday")
	if err != nil {
		t.Fatalf("GET audit-events (bad from): %v", err)
	}
	defer badFromResp.Body.Close()
	badFromBody, _ := readBody(badFromResp.Body)
	if badFromResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("GET audit-events (bad from): status = %d, want 400, body=%s", badFromResp.StatusCode, badFromBody)
	}
	var badFromErr adminAPIErrorBody
	if err := json.Unmarshal([]byte(badFromBody), &badFromErr); err != nil {
		t.Fatalf("decode bad from error: %v (body=%s)", err, badFromBody)
	}
	if badFromErr.Error != "audit.invalid_from" {
		t.Errorf("bad from: error code = %q, want audit.invalid_from", badFromErr.Error)
	}
}
