package config

import (
	"os"
	"testing"
	"time"
)

func withEnv(t *testing.T, kv map[string]string, fn func()) {
	t.Helper()
	prev := make(map[string]string, len(kv))
	hadPrev := make(map[string]bool, len(kv))
	for k, v := range kv {
		if old, ok := os.LookupEnv(k); ok {
			prev[k] = old
			hadPrev[k] = true
		}
		if err := os.Setenv(k, v); err != nil {
			t.Fatalf("setenv %s: %v", k, err)
		}
	}
	defer func() {
		for k := range kv {
			if hadPrev[k] {
				_ = os.Setenv(k, prev[k])
			} else {
				_ = os.Unsetenv(k)
			}
		}
	}()
	fn()
}

func TestLoadBFFConfig_Defaults(t *testing.T) {
	withEnv(t, map[string]string{
		"BFF_BASE_URL": "", "BFF_ADMIN_CALLBACK_PATH": "", "BFF_ADMIN_CLIENT_ID_PREFIX": "",
		"BFF_ADMIN_SCOPE": "", "BFF_ADMIN_SESSION_MAX_AGE": "", "BFF_OAUTH_CONTEXT_TTL": "",
		"BFF_REAUTH_SKEW": "", "ENVIRONMENT": "",
	}, func() {
		cfg := LoadBFFConfig()
		if cfg.BaseURL != "http://localhost:8080" {
			t.Errorf("BaseURL = %q", cfg.BaseURL)
		}
		if cfg.AdminCallbackURL() != "http://localhost:8080/admin/callback" {
			t.Errorf("AdminCallbackURL() = %q", cfg.AdminCallbackURL())
		}
		if got := cfg.AdminClientID("acme"); got != "admin-console-acme" {
			t.Errorf("AdminClientID() = %q", got)
		}
		if cfg.AdminScope != "openid profile" {
			t.Errorf("AdminScope = %q", cfg.AdminScope)
		}
		if cfg.SessionMaxAge != 12*time.Hour {
			t.Errorf("SessionMaxAge = %v", cfg.SessionMaxAge)
		}
		if cfg.ReauthSkew != 30*time.Second {
			t.Errorf("ReauthSkew = %v", cfg.ReauthSkew)
		}
		if cfg.IsProduction {
			t.Error("IsProduction should default false")
		}
		if err := cfg.Validate(); err != nil {
			t.Errorf("Validate() = %v, want nil", err)
		}
	})
}

func TestBFFConfig_BaseURL_TrailingSlashTrimmed(t *testing.T) {
	withEnv(t, map[string]string{"BFF_BASE_URL": "http://localhost:9999/"}, func() {
		cfg := LoadBFFConfig()
		if cfg.BaseURL != "http://localhost:9999" {
			t.Errorf("BaseURL = %q, want trailing slash trimmed", cfg.BaseURL)
		}
	})
}

func TestBFFConfig_IsProduction_CaseInsensitive(t *testing.T) {
	withEnv(t, map[string]string{"ENVIRONMENT": "Production"}, func() {
		if !LoadBFFConfig().IsProduction {
			t.Error("IsProduction should be true for 'Production'")
		}
	})
}

func TestBFFConfig_Validate_RejectsBadValues(t *testing.T) {
	base := func() *BFFConfig {
		cfg := LoadBFFConfig()
		return cfg
	}

	tests := []struct {
		name   string
		mutate func(*BFFConfig)
	}{
		{"empty base URL", func(c *BFFConfig) { c.BaseURL = "" }},
		{"callback path without leading slash", func(c *BFFConfig) { c.AdminCallbackPath = "admin/callback" }},
		{"empty client id prefix", func(c *BFFConfig) { c.AdminClientIDPrefix = "" }},
		{"empty scope", func(c *BFFConfig) { c.AdminScope = "" }},
		{"non-positive session max age", func(c *BFFConfig) { c.SessionMaxAge = 0 }},
		{"non-positive oauth context ttl", func(c *BFFConfig) { c.OAuthContextTTL = -1 }},
		{"negative reauth skew", func(c *BFFConfig) { c.ReauthSkew = -1 }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := base()
			tt.mutate(cfg)
			if err := cfg.Validate(); err == nil {
				t.Error("Validate() = nil, want error")
			}
		})
	}
}
