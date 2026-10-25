// Part of MImic's Listen Together server. Derived from the BitChord backend
// (https://github.com/kushagrasinghx/BitChord, backend/), Copyright (C) BitChord
// contributors, licensed GPL-3.0; modified for MImic (2026): module path, branding,
// invite deep link (mimic://), host "endParty" and "voteSkip". MImic is GPL-3.0.

package clock

import "time"

var (
	epochWallMs   = time.Now().UnixMilli()
	epochMonotonic = time.Now()
)

// NowMs returns the server time in milliseconds since the Unix epoch, advancing monotonically
// from startup to ensure NTP steps cannot step the clock backwards under live listening parties.
func NowMs() int64 {
	elapsed := time.Since(epochMonotonic).Milliseconds()
	return epochWallMs + elapsed
}
