// Part of MImic's Listen Together server. Derived from the BitChord backend
// (https://github.com/kushagrasinghx/BitChord, backend/), Copyright (C) BitChord
// contributors, licensed GPL-3.0; modified for MImic (2026): module path, branding,
// invite deep link (mimic://), host "endParty" and "voteSkip". MImic is GPL-3.0.

package codes

import (
	"strings"
	"testing"
)

func TestNormalise(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"abc123", "ABC123"},
		{"abc-def", "ABCDEF"},
		{" 123 456 ", "123456"},
		{"o 1 l i", "0111"},
		{"OIL", "011"},
		{"a/b.c", "ABC"},
		{"", ""},
	}
	for _, c := range cases {
		if got := Normalise(c.in); got != c.want {
			t.Errorf("Normalise(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestIsValid(t *testing.T) {
	if !IsValid("ABCDEF") {
		t.Errorf("IsValid(ABCDEF) = false, want true")
	}
	for _, bad := range []string{"", "ABCDE", "ABCDEFG", "ABCDEI", "ABCDEL", "ABCDEO", "abcDEF", "ABC EF"} {
		if IsValid(bad) {
			t.Errorf("IsValid(%q) = true, want false", bad)
		}
	}
}

func TestNewCode(t *testing.T) {
	seen := make(map[string]bool)
	for i := 0; i < 100; i++ {
		code := NewCode()
		if !IsValid(code) {
			t.Fatalf("NewCode() = %q, which IsValid rejects", code)
		}
		seen[code] = true
	}
	if len(seen) < 90 {
		t.Errorf("NewCode() produced only %d unique codes in 100 tries, want near 100", len(seen))
	}
	for _, r := range "ILOilo " {
		if strings.ContainsRune(Alphabet, r) {
			t.Errorf("Alphabet contains %q, which Normalise maps away", r)
		}
	}
}
