// Part of MImic's Listen Together server. Derived from the BitChord backend
// (https://github.com/KabirSinghBhatia/BitChord, backend/), Copyright (C) BitChord
// contributors, licensed GPL-3.0; modified for MImic (2026): module path, branding,
// invite deep link (mimic://), host "endParty" and "voteSkip". MImic is GPL-3.0.

package codes

import (
	"crypto/rand"
	"math/big"
	"strings"
	"unicode"
)

const (
	Alphabet   = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ"
	CodeLength = 6
)

// NewCode generates a 6-character party code using cryptographically secure random choice.
func NewCode() string {
	var sb strings.Builder
	sb.Grow(CodeLength)
	alphaLen := big.NewInt(int64(len(Alphabet)))
	for i := 0; i < CodeLength; i++ {
		idx, err := rand.Int(rand.Reader, alphaLen)
		if err != nil {
			// Fallback: should practically never happen with crypto/rand
			sb.WriteByte(Alphabet[0])
			continue
		}
		sb.WriteByte(Alphabet[idx.Int64()])
	}
	return sb.String()
}

// Normalise cleans a user-entered code:
// - Removes spaces, hyphens, and non-alphanumeric characters
// - Converts to uppercase
// - Maps commonly confused characters: 'I' -> '1', 'L' -> '1', 'O' -> '0'
func Normalise(code string) string {
	var sb strings.Builder
	for _, r := range strings.ToUpper(strings.TrimSpace(code)) {
		if !unicode.IsLetter(r) && !unicode.IsDigit(r) {
			continue
		}
		switch r {
		case 'I', 'L':
			sb.WriteByte('1')
		case 'O':
			sb.WriteByte('0')
		default:
			sb.WriteRune(r)
		}
	}
	return sb.String()
}

// IsValid checks if the code is exactly 6 characters long and only contains Alphabet runes.
func IsValid(code string) bool {
	if len(code) != CodeLength {
		return false
	}
	for i := 0; i < len(code); i++ {
		if !strings.ContainsRune(Alphabet, rune(code[i])) {
			return false
		}
	}
	return true
}
