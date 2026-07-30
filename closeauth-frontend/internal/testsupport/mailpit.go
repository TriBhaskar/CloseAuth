package testsupport

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// MailpitClient is a thin client over Mailpit's REST API (default port
// 8025) — the Go equivalent of the Java IT module's MailpitClient/Emails
// (closeauth-integration-tests/.../it/support/MailpitClient.java,
// Emails.java). Its reason for existing is WaitForMessageTo: "wait for an
// email to this recipient to arrive" as a bounded poll, NOT a fixed sleep —
// every email-driven journey in Deliverable 3 uses it. Reads only the
// plain-text body; extraction of a code or a link query-param out of that
// body is the caller's job via ExtractSixDigitCode/ExtractLinkParam below.
//
// Deliberately independent of any other HTTP client this package builds
// (AdminClient, OAuthClient) — it always targets Mailpit, never the app.
type MailpitClient struct {
	baseURI    string
	httpClient *http.Client
}

// NewMailpitClient constructs a client against a running Mailpit's REST API
// base URL (see Stack.MailpitBaseURI).
func NewMailpitClient(baseURI string) *MailpitClient {
	return &MailpitClient{
		baseURI:    strings.TrimRight(baseURI, "/"),
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
}

// Mailpit returns a MailpitClient pointed at the running Stack's Mailpit
// container.
func (s *Stack) Mailpit(ctx context.Context) (*MailpitClient, error) {
	baseURI, err := s.MailpitBaseURI(ctx)
	if err != nil {
		return nil, err
	}
	return NewMailpitClient(baseURI), nil
}

// MailpitMessage is a captured email as read from Mailpit's REST API — just
// the fields the journeys need: the recipients, the subject, and the
// plain-text body (from which one-time codes/links are extracted). Deep
// email-template assertion is not a goal — same scope as the Java module's
// MailpitMessage record.
type MailpitMessage struct {
	ID         string
	Recipients []string
	Subject    string
	Text       string
}

const mailpitPollInterval = 500 * time.Millisecond

// WaitForMessageTo bounded-polls Mailpit (every mailpitPollInterval, "wait,
// don't sleep") until a message addressed to recipient arrives, then returns
// it (body included). Returns an error — never blocks past timeout — if
// none arrives in time, so a caller gets a clear failure rather than a hang.
func (c *MailpitClient) WaitForMessageTo(ctx context.Context, recipient string, timeout time.Duration) (MailpitMessage, error) {
	target := strings.ToLower(recipient)
	deadline := time.Now().Add(timeout)
	for {
		id, err := c.findMessageIDTo(ctx, target)
		if err != nil {
			return MailpitMessage{}, err
		}
		if id != "" {
			return c.fetchMessage(ctx, id)
		}
		if !time.Now().Before(deadline) {
			return MailpitMessage{}, fmt.Errorf("no email addressed to %q arrived in Mailpit within %s", recipient, timeout)
		}
		select {
		case <-ctx.Done():
			return MailpitMessage{}, ctx.Err()
		case <-time.After(mailpitPollInterval):
		}
	}
}

// mailpitListResponse is the subset of GET /api/v1/messages this client
// needs: each summary carries its own ID + To list, enough to find a match
// without fetching the full message body for every candidate.
type mailpitListResponse struct {
	Messages []struct {
		ID string `json:"ID"`
		To []struct {
			Address string `json:"Address"`
		} `json:"To"`
	} `json:"messages"`
}

func (c *MailpitClient) findMessageIDTo(ctx context.Context, target string) (string, error) {
	var list mailpitListResponse
	if err := c.get(ctx, "/api/v1/messages?limit=50", &list); err != nil {
		return "", err
	}
	for _, msg := range list.Messages {
		for _, to := range msg.To {
			if strings.EqualFold(to.Address, target) {
				return msg.ID, nil
			}
		}
	}
	return "", nil
}

// mailpitMessageResponse is the subset of GET /api/v1/message/{id} this
// client needs.
type mailpitMessageResponse struct {
	To []struct {
		Address string `json:"Address"`
	} `json:"To"`
	Subject string `json:"Subject"`
	Text    string `json:"Text"`
}

func (c *MailpitClient) fetchMessage(ctx context.Context, id string) (MailpitMessage, error) {
	var msg mailpitMessageResponse
	if err := c.get(ctx, "/api/v1/message/"+url.PathEscape(id), &msg); err != nil {
		return MailpitMessage{}, err
	}
	recipients := make([]string, 0, len(msg.To))
	for _, to := range msg.To {
		recipients = append(recipients, to.Address)
	}
	return MailpitMessage{ID: id, Recipients: recipients, Subject: msg.Subject, Text: msg.Text}, nil
}

func (c *MailpitClient) get(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURI+path, nil)
	if err != nil {
		return fmt.Errorf("mailpit: build request: %w", err)
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("mailpit: %s: %w", path, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("mailpit: read response body: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("mailpit: %s: HTTP %d: %s", path, resp.StatusCode, body)
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("mailpit: decode %s response: %w (body=%s)", path, err, body)
	}
	return nil
}

// ---- extractors: the one-time secrets CloseAuth emails carry -------------
//
// Mirrors the Java IT module's Emails.java: EMAIL_VERIFIED mode emails a
// 6-digit numeric code inline in the body (ExtractSixDigitCode); the
// link-based flows (invites now; magic-link/password-reset in UI-2c) instead
// carry the secret as a URL query parameter inside a link in the body
// (ExtractLinkParam) — an invite link carries `invite=`, confirmed against
// InviteService.inviteUrl (backend source: "{bffBaseUrl}/register?invite=" +
// URLEncoder.encode(rawSecret)), NOT `token=` (that param name is
// magic-link/password-reset's, per the Java module's own comment — UI-2c's
// job, not this stage's).

var sixDigitCode = regexp.MustCompile(`\b(\d{6})\b`)

// ExtractSixDigitCode pulls the 6-digit numeric verification code out of an
// email body (EMAIL_VERIFIED mode's code-entry flow).
func ExtractSixDigitCode(body string) (string, error) {
	match := sixDigitCode.FindStringSubmatch(body)
	if match == nil {
		return "", fmt.Errorf("no 6-digit verification code found in email body")
	}
	return match[1], nil
}

// ExtractLinkParam pulls a named link query-parameter value out of an email
// body — different flows use different param names (an invite link carries
// `invite=`; magic-link/password-reset links carry `token=`, UI-2c's job).
func ExtractLinkParam(body, paramName string) (string, error) {
	pattern := regexp.MustCompile(`[?&]` + regexp.QuoteMeta(paramName) + `=([^&\s"'<>]+)`)
	match := pattern.FindStringSubmatch(body)
	if match == nil {
		return "", fmt.Errorf("no %q link parameter found in the email body", paramName+"=")
	}
	decoded, err := url.QueryUnescape(match[1])
	if err != nil {
		return "", fmt.Errorf("decode %q link parameter: %w", paramName, err)
	}
	return decoded, nil
}
