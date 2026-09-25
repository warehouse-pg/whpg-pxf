#!/usr/bin/env bash
# Fixture tests for pin-freshness-decide.bash. Run as the FIRST step of
# the pin-freshness job: a detector whose decision table regresses must
# fail its own job before it gets to touch a live issue. Covers catch
# AND evasion cases (a checker nobody tried to fool is untested where
# it matters).
set -euo pipefail
cd "$(dirname "$0")"

fails=0

expect() {
  # expect <name> <want_action> <want_state> [ENV=VAL ...]
  local name=$1 want_action=$2 want_state=$3
  shift 3
  local out action state
  if ! out=$(env "$@" bash pin-freshness-decide.bash 2>/dev/null); then
    echo "FAIL ${name}: decide exited non-zero (wanted ACTION=${want_action})"
    fails=$((fails + 1))
    return
  fi
  action=$(sed -n 's/^ACTION=//p' <<<"${out}")
  state=$(sed -n 's/^STATE=//p' <<<"${out}")
  if [ "${action}" != "${want_action}" ] || [ "${state}" != "${want_state}" ]; then
    echo "FAIL ${name}: got ACTION=${action} STATE=${state}, want ACTION=${want_action} STATE=${want_state}"
    fails=$((fails + 1))
  else
    echo "ok   ${name}"
  fi
}

expect_error() {
  # expect_error <name> [ENV=VAL ...]
  local name=$1
  shift
  if env "$@" bash pin-freshness-decide.bash >/dev/null 2>&1; then
    echo "FAIL ${name}: decide succeeded, wanted non-zero exit"
    fails=$((fails + 1))
  else
    echo "ok   ${name}"
  fi
}

# Shared baseline: pin 7.6.0-WHPG at SHA "aaa".
B=(MAJOR=7 PIN_TAG=7.6.0-WHPG PIN_SHA=aaa)

# Healthy paths.
expect "healthy, no issue"                 none    ""                "${B[@]}" NEWEST_TAG=7.6.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE= OPEN_STATE=
expect "healthy, leftover issue -> close"  close   ""                "${B[@]}" NEWEST_TAG=7.6.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE=12 OPEN_STATE=whpg7:stale:7.6.0-WHPG
expect "pin newer than upstream (never stale backwards)" none "" \
  "${B[@]}" NEWEST_TAG=7.5.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE= OPEN_STATE=

# Staleness.
expect "stale, no issue -> create"         create  whpg7:stale:7.7.0-WHPG  "${B[@]}" NEWEST_TAG=7.7.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE= OPEN_STATE=
expect "stale, same state reported -> none" none    whpg7:stale:7.7.0-WHPG  "${B[@]}" NEWEST_TAG=7.7.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE=12 OPEN_STATE=whpg7:stale:7.7.0-WHPG
expect "stale, even newer tag -> comment"  comment whpg7:stale:7.8.0-WHPG  "${B[@]}" NEWEST_TAG=7.8.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE=12 OPEN_STATE=whpg7:stale:7.7.0-WHPG
expect "sort -V 9->10 boundary"            create  whpg7:stale:7.10.0-WHPG MAJOR=7 PIN_TAG=7.9.0-WHPG PIN_SHA=aaa NEWEST_TAG=7.10.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE= OPEN_STATE=

# Retag (priority over stale).
expect "retag, no issue -> create"         create  whpg7:retag:bbb         "${B[@]}" NEWEST_TAG=7.6.0-WHPG PINNED_TAG_COMMIT=bbb OPEN_ISSUE= OPEN_STATE=
expect "retag, same state -> none"         none    whpg7:retag:bbb         "${B[@]}" NEWEST_TAG=7.6.0-WHPG PINNED_TAG_COMMIT=bbb OPEN_ISSUE=12 OPEN_STATE=whpg7:retag:bbb
expect "retag wins over stale"             create  whpg7:retag:bbb         "${B[@]}" NEWEST_TAG=7.7.0-WHPG PINNED_TAG_COMMIT=bbb OPEN_ISSUE= OPEN_STATE=
expect "stale escalates to retag -> comment" comment whpg7:retag:bbb       "${B[@]}" NEWEST_TAG=7.7.0-WHPG PINNED_TAG_COMMIT=bbb OPEN_ISSUE=12 OPEN_STATE=whpg7:stale:7.7.0-WHPG

# Deletion (priority over everything).
expect "deleted, no issue -> create"       create  whpg7:deleted:7.6.0-WHPG "${B[@]}" NEWEST_TAG=7.7.0-WHPG PINNED_TAG_COMMIT= OPEN_ISSUE= OPEN_STATE=
expect "deleted, same state -> none"       none    whpg7:deleted:7.6.0-WHPG "${B[@]}" NEWEST_TAG=7.7.0-WHPG PINNED_TAG_COMMIT= OPEN_ISSUE=12 OPEN_STATE=whpg7:deleted:7.6.0-WHPG

# Evasion: a broken tag query must fail loud, never conclude "fresh".
expect_error "empty NEWEST_TAG fails loud" "${B[@]}" NEWEST_TAG= PINNED_TAG_COMMIT=aaa OPEN_ISSUE= OPEN_STATE=
expect_error "missing PIN_TAG fails loud"  MAJOR=7 PIN_SHA=aaa NEWEST_TAG=7.6.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE= OPEN_STATE=
expect_error "missing MAJOR fails loud"    PIN_TAG=7.6.0-WHPG PIN_SHA=aaa NEWEST_TAG=7.6.0-WHPG PINNED_TAG_COMMIT=aaa OPEN_ISSUE= OPEN_STATE=

# Cross-major separation: the whpg6 leg with its own pin and issue.
B6=(MAJOR=6 PIN_TAG=6.27.6-WHPG PIN_SHA=ccc)
expect "whpg6 healthy, no issue"            none   ""                     "${B6[@]}" NEWEST_TAG=6.27.6-WHPG PINNED_TAG_COMMIT=ccc OPEN_ISSUE= OPEN_STATE=
expect "whpg6 stale -> create (own state)"  create whpg6:stale:6.27.7-WHPG "${B6[@]}" NEWEST_TAG=6.27.7-WHPG PINNED_TAG_COMMIT=ccc OPEN_ISSUE= OPEN_STATE=
expect "whpg6 same state reported -> none"  none   whpg6:stale:6.27.7-WHPG "${B6[@]}" NEWEST_TAG=6.27.7-WHPG PINNED_TAG_COMMIT=ccc OPEN_ISSUE=13 OPEN_STATE=whpg6:stale:6.27.7-WHPG
# A caller bug that hands this leg the OTHER major's issue must fail
# loud, never comment on the wrong issue.
expect_error "cross-major OPEN_STATE fails loud" "${B6[@]}" NEWEST_TAG=6.27.7-WHPG PINNED_TAG_COMMIT=ccc OPEN_ISSUE=12 OPEN_STATE=whpg7:stale:7.7.0-WHPG

# --- Tag-selection filter (pin-freshness-newest.bash): catch AND
# --- evasion fixtures for the detector's matching mechanism.
expect_newest() {
  # expect_newest <name> <want> <input-lines...>
  local name=$1 want=$2
  shift 2
  local got
  got=$(printf '%s\n' "$@" | bash pin-freshness-newest.bash)
  if [ "${got}" != "${want}" ]; then
    echo "FAIL ${name}: got '${got}', want '${want}'"
    fails=$((fails + 1))
  else
    echo "ok   ${name}"
  fi
}

expect_newest "picks the only release tag"     7.6.0-WHPG  7.6.0-WHPG
expect_newest "newest of several"              7.6.0-WHPG  7.4.1-WHPG 7.5.0-WHPG 7.6.0-WHPG
expect_newest "filter-level 9->10 boundary"    7.10.0-WHPG 7.9.0-WHPG 7.10.0-WHPG
expect_newest "rc tag excluded ($ anchor)"     7.6.0-WHPG  7.6.0-WHPG 7.6.1-WHPG-rc.1
expect_newest "pre-fork bare tag excluded"     7.6.0-WHPG  7.0.0 7.6.0-WHPG
expect_newest "describe-tag excluded"          7.6.0-WHPG  7.6.0-WHPG 7.6.0-12-gabcdef1
expect_newest "suffix near-miss excluded"      7.6.0-WHPG  7.6.0-WHPG 7.7.0-WHPGX
expect_newest "prefix near-miss excluded"      7.6.0-WHPG  7.6.0-WHPG x7.7.0-WHPG
expect_newest "no match yields empty (caller must fail loud)" "" 5.0.0-alpha.1 6.27.5-WHPG
expect_newest "6.x arm via argv works too"     ""          6.27.5-WHPG   # default major=7 ignores 6.x
got6=$(printf '6.27.2-WHPG\n6.27.6-WHPG\n7.6.0-WHPG\n' | bash pin-freshness-newest.bash 6)
if [ "${got6}" = "6.27.6-WHPG" ]; then echo "ok   major-6 arm selects 6.x only"; else echo "FAIL major-6 arm: got '${got6}'"; fails=$((fails + 1)); fi

if [ "${fails}" -gt 0 ]; then
  echo "${fails} fixture(s) FAILED"
  exit 1
fi
echo "all pin-freshness fixtures passed"
