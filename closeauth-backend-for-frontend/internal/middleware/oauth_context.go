package middleware

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"time"
)

const (
	OAuthContextCookieName = "oauth_context"
	CookieMaxAge           = 600 // 10 minutes
)

// OAuthContext stores OAuth authorization request parameters
type OAuthContext struct {
	ResponseType string `json:"response_type"`
	ClientID     string `json:"client_id"`
	RedirectURI  string `json:"redirect_uri"`
	Scope        string `json:"scope"`
	State        string `json:"state"`
	Timestamp    int64  `json:"timestamp"`
}

// GetEncryptionKey retrieves or generates encryption key for OAuth context cookies
func GetEncryptionKey() []byte {
	key := os.Getenv("OAUTH_CONTEXT_ENCRYPTION_KEY")
	if key == "" {
		// For development - use a default key (CHANGE IN PRODUCTION!)
		key = "default-32-byte-key-change-me!"
	}
	// Ensure key is 32 bytes for AES-256
	keyBytes := []byte(key)
	if len(keyBytes) < 32 {
		// Pad with zeros
		paddedKey := make([]byte, 32)
		copy(paddedKey, keyBytes)
		return paddedKey
	}
	return keyBytes[:32]
}

// SaveOAuthContext encrypts and saves OAuth context to a cookie
func SaveOAuthContext(w http.ResponseWriter, ctx *OAuthContext) error {
	ctx.Timestamp = time.Now().Unix()
	
	// Marshal context to JSON
	jsonData, err := json.Marshal(ctx)
	if err != nil {
		return fmt.Errorf("failed to marshal OAuth context: %w", err)
	}

	// Encrypt the JSON data
	encryptedData, err := encrypt(jsonData, GetEncryptionKey())
	if err != nil {
		return fmt.Errorf("failed to encrypt OAuth context: %w", err)
	}

	// Encode to base64 for cookie storage
	encodedData := base64.URLEncoding.EncodeToString(encryptedData)

	// Set cookie
	cookie := &http.Cookie{
		Name:     OAuthContextCookieName,
		Value:    encodedData,
		Path:     "/",
		MaxAge:   CookieMaxAge,
		HttpOnly: true,
		Secure:   false, // Set to true in production with HTTPS
		SameSite: http.SameSiteLaxMode,
	}
	http.SetCookie(w, cookie)

	return nil
}

// GetOAuthContext retrieves and decrypts OAuth context from cookie
func GetOAuthContext(r *http.Request) (*OAuthContext, error) {
	cookie, err := r.Cookie(OAuthContextCookieName)
	if err != nil {
		return nil, fmt.Errorf("OAuth context cookie not found: %w", err)
	}

	// Decode from base64
	encryptedData, err := base64.URLEncoding.DecodeString(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("failed to decode OAuth context: %w", err)
	}

	// Decrypt the data
	jsonData, err := decrypt(encryptedData, GetEncryptionKey())
	if err != nil {
		return nil, fmt.Errorf("failed to decrypt OAuth context: %w", err)
	}

	// Unmarshal JSON
	var ctx OAuthContext
	if err := json.Unmarshal(jsonData, &ctx); err != nil {
		return nil, fmt.Errorf("failed to unmarshal OAuth context: %w", err)
	}

	// Check if context has expired (10 minutes)
	if time.Now().Unix()-ctx.Timestamp > CookieMaxAge {
		return nil, fmt.Errorf("OAuth context has expired")
	}

	return &ctx, nil
}

// ClearOAuthContext removes the OAuth context cookie
func ClearOAuthContext(w http.ResponseWriter) {
	cookie := &http.Cookie{
		Name:     OAuthContextCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   false, // Set to true in production
		SameSite: http.SameSiteLaxMode,
	}
	http.SetCookie(w, cookie)
}

// BuildAuthorizeURL reconstructs the OAuth authorize URL from context
func BuildAuthorizeURL(ctx *OAuthContext, baseURL string) string {
	params := url.Values{}
	params.Set("response_type", ctx.ResponseType)
	params.Set("client_id", ctx.ClientID)
	params.Set("redirect_uri", ctx.RedirectURI)
	if ctx.Scope != "" {
		params.Set("scope", ctx.Scope)
	}
	if ctx.State != "" {
		params.Set("state", ctx.State)
	}

	return fmt.Sprintf("%s/closeauth/oauth2/authorize?%s", baseURL, params.Encode())
}

// encrypt encrypts data using AES-GCM
func encrypt(plaintext []byte, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}

	ciphertext := gcm.Seal(nonce, nonce, plaintext, nil)
	return ciphertext, nil
}

// decrypt decrypts data using AES-GCM
func decrypt(ciphertext []byte, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonceSize := gcm.NonceSize()
	if len(ciphertext) < nonceSize {
		return nil, fmt.Errorf("ciphertext too short")
	}

	nonce, ciphertext := ciphertext[:nonceSize], ciphertext[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, err
	}

	return plaintext, nil
}
