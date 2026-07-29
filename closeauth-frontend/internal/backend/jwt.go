package backend

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
)

// Claims is a black-box view of a decoded JWT payload — claim names as the
// backend defines them (see session.go's provenance comment), not a typed
// struct, so callers can reach any claim without this package chasing every
// backend claim addition.
//
// Deliberately no signature verification here (mirrors the Java IT module's
// support.Jwt — claims-only, no crypto). Cryptographic verification against
// the published JWKS is a later stage's concern (the Java module's own
// equivalent only added it in IT-11); this stage only needs to assert claim
// SHAPE for the round-trip proof, and it already trusts the token because it
// just received it directly from the backend's token endpoint over the
// harness's private Docker network.
type Claims map[string]any

// DecodeJWTClaims decodes a JWT's payload segment without verifying its
// signature. Returns an error if the token isn't a well-formed three-segment
// JWT or the payload isn't valid JSON.
//
// # Trust boundary — read before calling
//
// Safe ONLY for a token this BFF just received directly from its own call to
// the backend's token endpoint (trusted provenance, no untrusted intermediary
// in between — see the harness's private Docker network in this stage's
// proofs). Because there is no signature check, this function must NEVER be
// used to interpret or trust a bearer token presented by an arbitrary
// external caller (e.g. an Authorization header on an incoming request) —
// that requires real signature verification against the backend's published
// JWKS, which this package does not implement yet.
func DecodeJWTClaims(token string) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("decode jwt claims: expected 3 segments, got %d", len(parts))
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("decode jwt claims: base64url decode payload: %w", err)
	}
	var claims Claims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return nil, fmt.Errorf("decode jwt claims: unmarshal payload: %w", err)
	}
	return claims, nil
}

// String returns the named claim as a string, or "" if absent/not a string.
func (c Claims) String(name string) string {
	v, _ := c[name].(string)
	return v
}

// StringSlice returns the named claim as a []string, or nil if absent/not an
// array. JSON numbers/bools inside the array are skipped rather than erroring
// — callers that need strict shape validation should assert len() themselves.
func (c Claims) StringSlice(name string) []string {
	raw, ok := c[name].([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(raw))
	for _, v := range raw {
		if s, ok := v.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

// Has reports whether the named claim is present at all (distinguishing
// "absent" from "present but empty" — load-bearing for tenant_id, whose
// ABSENCE is the platform-admin signal).
func (c Claims) Has(name string) bool {
	_, ok := c[name]
	return ok
}
