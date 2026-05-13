package middleware

import (
	"testing"
)

func TestEncryptDecrypt_RoundTrip(t *testing.T) {
	key := []byte("0123456789abcdef0123456789abcdef") // 32 bytes

	tests := []struct {
		name      string
		plaintext string
	}{
		{"empty", ""},
		{"short", "hello"},
		{"json", `{"email":"test@example.com","token":"abc123"}`},
		{"long", "Lorem ipsum dolor sit amet, consectetur adipiscing elit. This is a longer test string to verify encryption works on bigger payloads."},
		{"unicode", "日本語テスト 🔒"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			encrypted, err := Encrypt([]byte(tt.plaintext), key)
			if err != nil {
				t.Fatalf("Encrypt() error = %v", err)
			}

			if len(encrypted) == 0 {
				t.Fatal("Encrypt() returned empty ciphertext")
			}

			// Ciphertext should differ from plaintext
			if tt.plaintext != "" && string(encrypted) == tt.plaintext {
				t.Fatal("Encrypt() returned plaintext unchanged")
			}

			decrypted, err := Decrypt(encrypted, key)
			if err != nil {
				t.Fatalf("Decrypt() error = %v", err)
			}

			if string(decrypted) != tt.plaintext {
				t.Errorf("Decrypt() = %q, want %q", string(decrypted), tt.plaintext)
			}
		})
	}
}

func TestDecrypt_WrongKey(t *testing.T) {
	key1 := []byte("0123456789abcdef0123456789abcdef")
	key2 := []byte("fedcba9876543210fedcba9876543210")

	encrypted, err := Encrypt([]byte("secret data"), key1)
	if err != nil {
		t.Fatalf("Encrypt() error = %v", err)
	}

	_, err = Decrypt(encrypted, key2)
	if err == nil {
		t.Fatal("Decrypt() with wrong key should fail")
	}
}

func TestDecrypt_TamperedCiphertext(t *testing.T) {
	key := []byte("0123456789abcdef0123456789abcdef")

	encrypted, _ := Encrypt([]byte("secret"), key)

	// Tamper with ciphertext
	encrypted[len(encrypted)-1] ^= 0xFF

	_, err := Decrypt(encrypted, key)
	if err == nil {
		t.Fatal("Decrypt() with tampered data should fail")
	}
}

func TestDecrypt_TooShort(t *testing.T) {
	key := []byte("0123456789abcdef0123456789abcdef")

	_, err := Decrypt([]byte("short"), key)
	if err == nil {
		t.Fatal("Decrypt() with too-short data should fail")
	}
}

func TestGetEncryptionKey_DefaultLength(t *testing.T) {
	key := GetEncryptionKey()
	if len(key) != 32 {
		t.Errorf("GetEncryptionKey() length = %d, want 32", len(key))
	}
}
