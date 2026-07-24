package dbconn_test

import (
	"strings"
	"testing"

	"github.com/blang/semver"
	"pxf-cli/dbconn"
)

// Banner fixtures for the two "SELECT version()" shapes ParseGPVersion has to handle:
//   - GP6/GP7 shape: the version sits inside the "(Greenplum Database <ver>)" parens.
//   - Newer WarehousePG shape (illustrated here with a hypothetical WHPG42): the closing
//     paren moved to right after "Database", so the version sits outside the parens -
//     "... (Greenplum Database) 42.0.0 build dev ... WarehousePG" - and the banner leads
//     with a gcc toolchain version that must not be mistaken for the product version.
const (
	whpg6Banner  = "PostgreSQL 9.4.26 (Greenplum Database 6.27.5-WHPG build dev) on x86_64-pc-linux-gnu, compiled by gcc (GCC) 6.4.0, 64-bit compiled on Nov 15 2024 WarehousePG"
	whpg7Banner  = "PostgreSQL 12.12 (Greenplum Database 7.5.0-WHPG build dev) on x86_64-pc-linux-gnu, compiled by gcc (GCC) 9.4.0, 64-bit compiled on Jan 1 2025 Bhuvnesh C. WarehousePG"
	whpg42Banner = "PostgreSQL 42beta1 on aarch64-unknown-linux-gnu, compiled by gcc (GCC) 11.5.0 20240719 (Red Hat 11.5.0-14), 64-bit (Greenplum Database) 42.0.0 build dev compiled on Jul 20 2026 08:21:10 Bhuvnesh C. WarehousePG"
)

func TestParseGPVersion(t *testing.T) {
	cases := []struct {
		name           string
		banner         string
		wantSemVer     string
		wantTrailerPfx string
	}{
		{
			name:           "GP6/GP7 shape, version inside the parens (WHPG6)",
			banner:         whpg6Banner,
			wantSemVer:     "6.27.5",
			wantTrailerPfx: "6.27.5-WHPG",
		},
		{
			name:           "GP6/GP7 shape, version inside the parens (WHPG7)",
			banner:         whpg7Banner,
			wantSemVer:     "7.5.0",
			wantTrailerPfx: "7.5.0-WHPG",
		},
		{
			name:           "newer shape, closing paren before the version (WHPG42)",
			banner:         whpg42Banner,
			wantSemVer:     "42.0.0",
			wantTrailerPfx: "42.0.0",
		},
		{
			// Anticipates a possible future rebrand that drops "Greenplum Database" from
			// this part of the banner in favor of "WarehousePG" while keeping the same
			// surrounding shape - not observed in any real banner. Paren before the
			// version (newer shape).
			name:           "hypothetical future (WarehousePG marker, paren before version",
			banner:         "PostgreSQL 50beta1 on aarch64-unknown-linux-gnu, compiled by gcc (GCC) 12.1.0, 64-bit (WarehousePG) 50.0.0 build dev compiled on Jan 1 2027 WarehousePG",
			wantSemVer:     "50.0.0",
			wantTrailerPfx: "50.0.0",
		},
		{
			// Paren after the version (GP6/GP7 shape) under the future marker.
			name:           "hypothetical future (WarehousePG marker, paren after version",
			banner:         "PostgreSQL 15.4 (WarehousePG 50.0.0 build dev) on x86_64-pc-linux-gnu, compiled by gcc (GCC) 12.1.0, 64-bit compiled on Jan 1 2027",
			wantSemVer:     "50.0.0",
			wantTrailerPfx: "50.0.0",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			versionAndTrailer, version, err := dbconn.ParseGPVersion(tc.banner)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if !version.Equals(semver.MustParse(tc.wantSemVer)) {
				t.Errorf("SemVer = %v, want %v", version, tc.wantSemVer)
			}
			if !strings.HasPrefix(versionAndTrailer, tc.wantTrailerPfx) {
				t.Errorf("versionAndTrailer = %q, want prefix %q", versionAndTrailer, tc.wantTrailerPfx)
			}
		})
	}
}

// In the newer banner shape the gcc toolchain version ("11.5.0") appears ahead of the
// real product version. Anchoring only on the marker keeps the parser from latching onto
// it, and keeps it out of the returned string so callers that re-derive the major version
// via strings.Split(v, ".")[0] don't silently pick up 11 instead of 42.
func TestParseGPVersion_DoesNotMistakeToolchainVersion(t *testing.T) {
	versionAndTrailer, version, err := dbconn.ParseGPVersion(whpg42Banner)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if version.Equals(semver.MustParse("11.5.0")) {
		t.Errorf("parsed the gcc toolchain version 11.5.0 as the product version")
	}
	if strings.Contains(versionAndTrailer, "11.5.0") {
		t.Errorf("versionAndTrailer = %q, should not contain gcc version 11.5.0", versionAndTrailer)
	}
	if major := strings.Split(versionAndTrailer, ".")[0]; major != "42" {
		t.Errorf("re-derived major version = %q, want %q", major, "42")
	}
}

func TestParseGPVersion_ErrorsInsteadOfPanicking(t *testing.T) {
	cases := []struct {
		name   string
		banner string
	}{
		{"marker missing", "this is not a GPDB version string at all"},
		{"marker present but no version number follows", "(Greenplum Database) no version number here"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, _, err := dbconn.ParseGPVersion(tc.banner); err == nil {
				t.Errorf("expected an error, got nil")
			}
		})
	}
}

func TestGPDBVersionComparisons(t *testing.T) {
	fake43 := dbconn.GPDBVersion{VersionString: "4.3.0.0", SemVer: semver.MustParse("4.3.0")}
	fake50 := dbconn.GPDBVersion{VersionString: "5.0.0", SemVer: semver.MustParse("5.0.0")}
	fake51 := dbconn.GPDBVersion{VersionString: "5.1.0", SemVer: semver.MustParse("5.1.0")}

	cases := []struct {
		name string
		got  bool
		want bool
	}{
		{"Before: 4.3 before 5", fake43.Before("5"), true},
		{"Before: 5 before 5.1", fake50.Before("5.1"), true},
		{"Before: 5 before 5 is false", fake50.Before("5"), false},
		{"AtLeast: 5 at least 4", fake50.AtLeast("4"), true},
		{"AtLeast: 5 at least 5", fake50.AtLeast("5"), true},
		{"AtLeast: 5.1 at least 5", fake51.AtLeast("5"), true},
		{"AtLeast: 4.3 at least 5 is false", fake43.AtLeast("5"), false},
		{"AtLeast: 5.0 at least 5.1 is false", fake50.AtLeast("5.1"), false},
		{"Is: 5 is 5", fake50.Is("5"), true},
		{"Is: 5.1 is 5", fake51.Is("5"), true},
		{"Is: 5.0 is 5.1 is false", fake50.Is("5.1"), false},
		{"Is: 4.3 is 5 is false", fake43.Is("5"), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if tc.got != tc.want {
				t.Errorf("got %v, want %v", tc.got, tc.want)
			}
		})
	}
}
