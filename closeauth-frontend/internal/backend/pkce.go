package backend

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

// PKCE is a verifier/challenge pair per RFC 7636 (S256 only — the backend's
// fixture clients and every real client register requireProofKey=true and
// CloseAuth does not support the "plain" method).
type PKCE struct {
	Verifier  string
	Challenge string
}

// NewPKCE generates a fresh RFC 7636 S256 verifier/challenge pair: a 32-byte
// random verifier, base64url-encoded (no padding), and its SHA-256 challenge,
// same encoding.
func NewPKCE() (PKCE, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return PKCE{}, err
	}
	verifier := base64URLNoPad(raw)
	challenge := base64URLNoPad(sha256Sum([]byte(verifier)))
	return PKCE{Verifier: verifier, Challenge: challenge}, nil
}

func base64URLNoPad(b []byte) string {
	return base64.RawURLEncoding.EncodeToString(b)
}

func sha256Sum(b []byte) []byte {
	sum := sha256.Sum256(b)
	return sum[:]
}
