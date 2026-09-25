#!/usr/bin/env bash
# Pure decision logic for the pin-freshness watcher — no network, no
# gh. Kept free of live I/O so the whole decision table is exercised by
# fixture tests (test-pin-freshness-decide.bash) before every live run.
#
# Inputs (environment):
#   PIN_TAG            the workflow's pinned tag (e.g. 7.6.0-WHPG)
#   PIN_SHA            the commit SHA the workflow pins for that tag
#   NEWEST_TAG         newest matching upstream tag; MUST be non-empty —
#                      an empty value means the tag query broke, and
#                      concluding "fresh" from a broken query is the
#                      one failure mode this watcher must never have
#   PINNED_TAG_COMMIT  the commit the pinned tag currently points at
#                      upstream, AFTER peeling annotated tags
#                      (empty = the tag no longer exists upstream)
#   OPEN_ISSUE         number of the open watcher issue (empty = none)
#   OPEN_STATE         pin-state marker read from that issue's body
#                      (empty = none)
#
# Output (stdout, two lines):
#   ACTION=none|create|comment|close
#   STATE=<kind>:<detail>   kind in {stale, retag, deleted}; empty when
#                           the pin is healthy
#
# Priority: deleted > retag > stale. A deleted or moved pin is a
# build-integrity problem; staleness is merely an upgrade nudge.
set -euo pipefail

: "${PIN_TAG:?PIN_TAG is required}"
: "${PIN_SHA:?PIN_SHA is required}"
if [ -z "${NEWEST_TAG:-}" ]; then
  echo "ERROR: NEWEST_TAG is empty — the upstream tag query returned nothing." >&2
  echo "Refusing to conclude 'fresh' from a broken query (fail loud, never silent)." >&2
  exit 1
fi

state=""
if [ -z "${PINNED_TAG_COMMIT:-}" ]; then
  state="deleted:${PIN_TAG}"
elif [ "${PINNED_TAG_COMMIT}" != "${PIN_SHA}" ]; then
  state="retag:${PINNED_TAG_COMMIT}"
elif [ "${NEWEST_TAG}" != "${PIN_TAG}" ] &&
     [ "$(printf '%s\n%s\n' "${PIN_TAG}" "${NEWEST_TAG}" | sort -V | tail -1)" = "${NEWEST_TAG}" ]; then
  # sort -V so 7.10 orders above 7.9; the second clause keeps a pin
  # NEWER than anything upstream (never expected) from reading as stale.
  state="stale:${NEWEST_TAG}"
fi

if [ -z "${state}" ]; then
  # Healthy pin. Close a leftover issue (the bump landed but nobody
  # closed it), otherwise stay silent.
  if [ -n "${OPEN_ISSUE:-}" ]; then
    echo "ACTION=close"
  else
    echo "ACTION=none"
  fi
  echo "STATE="
elif [ -z "${OPEN_ISSUE:-}" ]; then
  echo "ACTION=create"
  echo "STATE=${state}"
elif [ "${OPEN_STATE:-}" = "${state}" ]; then
  # Already reported exactly this condition — no weekly spam.
  echo "ACTION=none"
  echo "STATE=${state}"
else
  # The condition changed (newer tag appeared, or stale escalated to
  # retag/deleted) — update the existing issue rather than opening a
  # second one.
  echo "ACTION=comment"
  echo "STATE=${state}"
fi
