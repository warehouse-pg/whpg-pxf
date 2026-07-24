package dbconn

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/blang/semver"
)

type GPDBVersion struct {
	VersionString string `db:"versionstring"`
	SemVer        semver.Version
}

// versionNumPattern finds the first X.Y.Z token in a "SELECT version()" banner.
var versionNumPattern = regexp.MustCompile(`\d+\.\d+\.\d+`)

/*
 * NewVersion is a convenience constructor for tests and defaults; dbconn.Connect
 * auto-populates the version from the live server.
 *
 * versionStr must be a semantic version "X.Y.Z" (not a libpq version() string).
 * An invalid semver panics; that's programmer error.
 */
func NewVersion(versionStr string) GPDBVersion {
	return GPDBVersion{
		VersionString: versionStr,
		SemVer:        semver.MustParse(versionStr),
	}
}

func InitializeVersion(dbconn *DBConn) (dbversion GPDBVersion, err error) {
	err = dbconn.Get(&dbversion, "SELECT pg_catalog.version() AS versionstring")
	if err != nil {
		return
	}
	dbversion.VersionString, dbversion.SemVer, err = ParseGPVersion(dbversion.VersionString)
	return
}

// gpDatabaseMarkers are the brand-name markers that introduce the GPDB/WarehousePG
// version number in a "SELECT version()" banner, tried in order. "(Greenplum Database"
// covers GP6/GP7 as well as newer WarehousePG majors; "(WarehousePG" is included
// pre-emptively for a possible future rebrand that drops the "Greenplum Database" name
// from this part of the banner while keeping the same surrounding format, just with the
// brand name swapped, e.g. "(WarehousePG) 42.0.0 ...". A plain trailing " WarehousePG"
// brand suffix (no leading paren, present in every banner already) can't collide with
// this marker, since the match requires the literal "(WarehousePG" substring.
var gpDatabaseMarkers = []string{"(Greenplum Database", "(WarehousePG"}

// ParseGPVersion extracts the Greenplum/WarehousePG version out of a raw
// "SELECT version()" banner. Two banner shapes exist:
//
//   - GP6/GP7: "... (Greenplum Database 6.28.9 build ...) ..."      - version sits
//     right after the marker, inside the same parens.
//   - Newer WarehousePG majors (for example WHPG42): "... (Greenplum Database) 42.0.0
//     build dev ... WarehousePG" - the backend moved the closing paren to right after
//     "Database", ahead of the version number, so the version now sits *outside* those
//     parens.
//
// Rather than assume where the closing paren falls, this only anchors on the marker
// text (see gpDatabaseMarkers) and takes the first X.Y.Z token after it, which matches
// both shapes - and any future rebrand that keeps the same shape under a different
// marker - without needing to special-case any of them. If no marker or no version
// number can be found, an error is returned instead of panicking.
//
// It returns the matched version number plus whatever trailing text follows it
// in the banner (e.g. "6.28.9 build ...", "42.0.0 build dev ... WarehousePG"),
// not just the bare semver, because callers such as gpbackup's own backup
// history/config persist this string verbatim and later re-derive the major
// version from it (e.g. via strings.Split(v, ".")[0]) - keeping any leading
// compiler-version noise (e.g. gcc's "11.5.0") out of the returned string, as
// the original slicing logic did for GP6/GP7, avoids that code silently
// mis-deriving the major version on the newer banner shape.
func ParseGPVersion(versionString string) (versionAndTrailer string, version semver.Version, err error) {
	var rest string
	found := false
	for _, marker := range gpDatabaseMarkers {
		if _, r, ok := strings.Cut(versionString, marker); ok {
			rest = r
			found = true
			break
		}
	}
	if !found {
		err = fmt.Errorf("could not find a Greenplum Database/WarehousePG version marker in version string: %q", versionString)
		return
	}

	loc := versionNumPattern.FindStringIndex(rest)
	if loc == nil {
		err = fmt.Errorf("could not find a X.Y.Z version number in version string: %q", versionString)
		return
	}

	versionAndTrailer = strings.TrimSpace(rest[loc[0]:])
	version, err = semver.Make(rest[loc[0]:loc[1]])
	return
}

func stringToSemVerRange(versionStr string) semver.Range {
	numDigits := len(strings.Split(versionStr, "."))
	if numDigits < 3 {
		versionStr += ".x"
	}
	return semver.MustParseRange(versionStr)
}

func (dbversion GPDBVersion) Before(targetVersion string) bool {
	return stringToSemVerRange("<" + targetVersion)(dbversion.SemVer)
}

func (dbversion GPDBVersion) AtLeast(targetVersion string) bool {
	return stringToSemVerRange(">=" + targetVersion)(dbversion.SemVer)
}

func (dbversion GPDBVersion) Is(targetVersion string) bool {
	return stringToSemVerRange("==" + targetVersion)(dbversion.SemVer)
}
