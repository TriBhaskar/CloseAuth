package backend

import (
	"testing"
	"time"
)

func TestClaims_Int64_DecodesFloat64Exp(t *testing.T) {
	// A real decoded JWT payload always yields float64 for a bare JSON
	// number via encoding/json — this is the shape String("exp") can't read.
	claims := Claims{"exp": float64(1735689600)}

	got, ok := claims.Int64("exp")
	if !ok {
		t.Fatal("Int64(\"exp\") ok = false, want true")
	}
	if got != 1735689600 {
		t.Errorf("Int64(\"exp\") = %d, want 1735689600", got)
	}

	// The pre-existing String() accessor can't read this claim shape at all.
	if s := claims.String("exp"); s != "" {
		t.Errorf("String(\"exp\") = %q, want empty (float64 is not a string)", s)
	}
}

func TestClaims_Int64_AbsentOrWrongType(t *testing.T) {
	claims := Claims{"tenant_id": "abc"}

	if _, ok := claims.Int64("exp"); ok {
		t.Error("Int64() on an absent claim should report ok=false")
	}
	if _, ok := claims.Int64("tenant_id"); ok {
		t.Error("Int64() on a non-numeric string claim should report ok=false")
	}
}

func TestClaims_Int64_NumericString(t *testing.T) {
	claims := Claims{"exp": "1735689600"}
	got, ok := claims.Int64("exp")
	if !ok || got != 1735689600 {
		t.Errorf("Int64() = %d, %v, want 1735689600, true", got, ok)
	}
}

func TestClaims_Time_DecodesUnixSeconds(t *testing.T) {
	claims := Claims{"exp": float64(1735689600)}
	got, ok := claims.Time("exp")
	if !ok {
		t.Fatal("Time() ok = false, want true")
	}
	want := time.Unix(1735689600, 0)
	if !got.Equal(want) {
		t.Errorf("Time() = %v, want %v", got, want)
	}
}

func TestClaims_Time_Absent(t *testing.T) {
	claims := Claims{}
	if _, ok := claims.Time("exp"); ok {
		t.Error("Time() on an absent claim should report ok=false")
	}
}
