package server

import "testing"

// TestNormalizeTenantID proves the BFF-side re-normalisation
// handleEntryResolveProxy applies (spec §6.1's Data line: "the BFF
// re-normalises rather than trusting it") — pure-function, no Docker
// needed. Mirrors the SPA's own normalizeTenantId (closeauth-web/src/lib/tenantId.ts)
// rule for rule.
func TestNormalizeTenantID(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"already canonical", "ten_acme-inc", "ten_acme-inc"},
		{"uppercase", "TEN_ACME-INC", "ten_acme-inc"},
		{"missing prefix", "acme-inc", "ten_acme-inc"},
		{"surrounding whitespace", "  ten_acme-inc  ", "ten_acme-inc"},
		{"pasted URL with trailing page", "https://app.example.com/t/ten_acme-inc/login", "ten_acme-inc"},
		{"pasted URL with no trailing page", "https://app.example.com/t/ten_acme-inc", "ten_acme-inc"},
		{"pasted URL with query string", "https://app.example.com/t/ten_acme-inc/login?client_id=x", "ten_acme-inc"},
		{"pasted URL, unprefixed segment", "https://app.example.com/t/acme-inc", "ten_acme-inc"},
		{"empty string stays empty (no prefix added to nothing)", "", ""},
		{"malformed characters pass through unchanged (not this function's job to reject)", "ten_bad_char!", "ten_bad_char!"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := normalizeTenantID(tc.in); got != tc.want {
				t.Errorf("normalizeTenantID(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}
