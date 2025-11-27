package config

import (
	"os"
	"testing"
)

func TestLoadEndpointsConfig(t *testing.T) {
	// Set required environment variable
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, err := LoadEndpointsConfig()
	if err != nil {
		t.Fatalf("Expected no error, got: %v", err)
	}

	if config.OAuth2ServerURL != "http://localhost:9088" {
		t.Errorf("Expected OAuth2ServerURL to be 'http://localhost:9088', got: %s", config.OAuth2ServerURL)
	}
}

func TestLoadEndpointsConfig_MissingRequiredEnv(t *testing.T) {
	// Ensure OAUTH2_SERVER_URL is not set
	os.Unsetenv("OAUTH2_SERVER_URL")

	_, err := LoadEndpointsConfig()
	if err == nil {
		t.Fatal("Expected error when OAUTH2_SERVER_URL is missing, got nil")
	}
}

func TestGetTokenURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/oauth2/token"
	actual := config.GetTokenURL()
	
	if actual != expected {
		t.Errorf("Expected GetTokenURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetAuthorizeURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/oauth2/authorize"
	actual := config.GetAuthorizeURL()
	
	if actual != expected {
		t.Errorf("Expected GetAuthorizeURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetRegisterClientURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/connect/register"
	actual := config.GetRegisterClientURL()
	
	if actual != expected {
		t.Errorf("Expected GetRegisterClientURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetAdminLoginURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/api/v1/admin/auth/login"
	actual := config.GetAdminLoginURL()
	
	if actual != expected {
		t.Errorf("Expected GetAdminLoginURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetAdminRegisterURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/api/v1/admin/auth/register"
	actual := config.GetAdminRegisterURL()
	
	if actual != expected {
		t.Errorf("Expected GetAdminRegisterURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetAdminVerifyEmailURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/api/v1/admin/auth/verify-email"
	actual := config.GetAdminVerifyEmailURL()
	
	if actual != expected {
		t.Errorf("Expected GetAdminVerifyEmailURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetAdminResendOTPURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/api/v1/admin/auth/resend-otp"
	actual := config.GetAdminResendOTPURL()
	
	if actual != expected {
		t.Errorf("Expected GetAdminResendOTPURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetAdminForgotPasswordURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/api/v1/admin/auth/forgot-password"
	actual := config.GetAdminForgotPasswordURL()
	
	if actual != expected {
		t.Errorf("Expected GetAdminForgotPasswordURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetAdminPasswordResetRequestURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/api/v1/admin/auth/reset-password"
	actual := config.GetAdminPasswordResetRequestURL()
	
	if actual != expected {
		t.Errorf("Expected GetAdminPasswordResetRequestURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetFullURL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/custom/path"
	actual := config.GetFullURL("/custom/path")
	
	if actual != expected {
		t.Errorf("Expected GetFullURL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetOAuth2URL(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	defer os.Unsetenv("OAUTH2_SERVER_URL")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/closeauth/custom/path"
	actual := config.GetOAuth2URL("/custom/path")
	
	if actual != expected {
		t.Errorf("Expected GetOAuth2URL() to return '%s', got: '%s'", expected, actual)
	}
}

func TestCustomContextPath(t *testing.T) {
	os.Setenv("OAUTH2_SERVER_URL", "http://localhost:9088")
	os.Setenv("OAUTH2_API_CONTEXT_PATH", "http://localhost:9088/custom-context")
	defer os.Unsetenv("OAUTH2_SERVER_URL")
	defer os.Unsetenv("OAUTH2_API_CONTEXT_PATH")

	config, _ := LoadEndpointsConfig()
	
	expected := "http://localhost:9088/custom-context/oauth2/token"
	actual := config.GetTokenURL()
	
	if actual != expected {
		t.Errorf("Expected GetTokenURL() with custom context to return '%s', got: '%s'", expected, actual)
	}
}

func TestGetEnvOrDefault(t *testing.T) {
	// Test when env var is set
	os.Setenv("TEST_VAR", "test_value")
	defer os.Unsetenv("TEST_VAR")
	
	result := GetEnvOrDefault("TEST_VAR", "default_value")
	if result != "test_value" {
		t.Errorf("Expected 'test_value', got: '%s'", result)
	}
	
	// Test when env var is not set
	result = GetEnvOrDefault("NON_EXISTENT_VAR", "default_value")
	if result != "default_value" {
		t.Errorf("Expected 'default_value', got: '%s'", result)
	}
}
