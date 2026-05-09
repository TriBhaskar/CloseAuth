package spring

import "testing"

func TestNormalizeContextPath(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{name: "empty", in: "", want: ""},
		{name: "plain path", in: "/closeauth", want: "/closeauth"},
		{name: "missing leading slash", in: "closeauth", want: "/closeauth"},
		{name: "trailing slash", in: "/closeauth/", want: "/closeauth"},
		{name: "full url", in: "http://localhost:9088/closeauth", want: "/closeauth"},
		{name: "malformed protocol prefix", in: "http//localhost:9088/closeauth", want: "/closeauth"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := normalizeContextPath(tt.in); got != tt.want {
				t.Fatalf("normalizeContextPath(%q) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}

func TestConfigBaseURL(t *testing.T) {
	cfg := &Config{
		OAuth2ServerURL: "http://localhost:9088/",
		ContextPath:     "http://localhost:9088/closeauth",
	}

	if got, want := cfg.baseURL(), "http://localhost:9088/closeauth"; got != want {
		t.Fatalf("baseURL() = %q, want %q", got, want)
	}
}
