package middleware

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"fmt"
	"io"
	"os"
)

// GetEncryptionKey returns the 32-byte AES-256 key used to seal every cookie
// this package manages. Pads or truncates to exactly 32 bytes.
//
// Prefers BFF_ENCRYPTION_KEY (the stage UI-3a name, since this key now backs
// more than the original oauth_context cookie); falls back to the original
// OAUTH_CONTEXT_ENCRYPTION_KEY for anyone who already set that; falls back
// further to a hardcoded development-only literal. Neither env var is set in
// the repo's (stale) root .env today, so the dev literal is what actually
// runs locally unless one is exported — callers that care should check
// BFFConfig.IsProduction and log a warning, as server.go does at startup.
func GetEncryptionKey() []byte {
	key := os.Getenv("BFF_ENCRYPTION_KEY")
	if key == "" {
		key = os.Getenv("OAUTH_CONTEXT_ENCRYPTION_KEY")
	}
	if key == "" {
		key = "default-32-byte-key-change-me!!" // Development only!
	}

	keyBytes := []byte(key)
	if len(keyBytes) < 32 {
		padded := make([]byte, 32)
		copy(padded, keyBytes)
		return padded
	}
	return keyBytes[:32]
}

// Seal encrypts plaintext using AES-256-GCM, authenticating (but not
// encrypting) aad alongside it. Returns the nonce prepended to the
// ciphertext, exactly like Encrypt.
//
// aad purpose: this package seals three different cookie shapes (admin
// session, oauth round-trip context, denial marker) with the same key. With
// a nil/empty aad (as Encrypt uses) a ciphertext from one cookie is
// syntactically valid and would decrypt "successfully" if presented as a
// different cookie — Decrypt/Open can't distinguish them by content alone
// once decrypted into the same JSON-shaped bytes. Binding aad to a string
// like "closeauth.bff.admin-session:<slug>" (purpose + tenant) makes a
// ciphertext cryptographically unusable outside the exact cookie and slug it
// was sealed for: Open fails closed (returns an error) rather than
// succeeding on a semantically wrong payload.
func Seal(plaintext, key, aad []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("create cipher: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("create GCM: %w", err)
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, fmt.Errorf("generate nonce: %w", err)
	}

	ciphertext := gcm.Seal(nonce, nonce, plaintext, aad)
	return ciphertext, nil
}

// Open decrypts AES-256-GCM ciphertext (with prepended nonce) sealed by
// Seal, requiring the same aad used at seal time. See Seal's doc comment for
// why aad matters here.
func Open(ciphertext, key, aad []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("create cipher: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("create GCM: %w", err)
	}

	nonceSize := gcm.NonceSize()
	if len(ciphertext) < nonceSize {
		return nil, fmt.Errorf("ciphertext too short")
	}

	nonce, ciphertextBody := ciphertext[:nonceSize], ciphertext[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertextBody, aad)
	if err != nil {
		return nil, fmt.Errorf("decrypt: %w", err)
	}

	return plaintext, nil
}

// Encrypt encrypts plaintext using AES-256-GCM with no AAD. Kept as a thin
// wrapper over Seal for existing callers/tests; new cookie code (session.go,
// oauth_context.go) calls Seal directly with a purpose-bound AAD instead.
// Returns nonce prepended to ciphertext.
func Encrypt(plaintext, key []byte) ([]byte, error) {
	return Seal(plaintext, key, nil)
}

// Decrypt decrypts AES-256-GCM ciphertext (with prepended nonce) sealed with
// no AAD. Kept as a thin wrapper over Open for existing callers/tests.
func Decrypt(ciphertext, key []byte) ([]byte, error) {
	return Open(ciphertext, key, nil)
}
