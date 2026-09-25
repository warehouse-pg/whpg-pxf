#!/usr/bin/env bash
# Select the newest WarehousePG release tag from a list of tag names —
# pure stdin/stdout so the filter (a detection mechanism) is
# fixture-testable (test-pin-freshness-decide.bash) instead of living
# only inside the live gather step.
#
# stdin:  one tag name per line (bare names, no refs/tags/ prefix)
# argv1:  major-version prefix to match (default: 7)
# stdout: the newest tag matching ^<major>.<minor>.<patch>-WHPG, or
#         nothing if none match (the caller must treat empty as an
#         error — see pin-freshness-decide.bash's NEWEST_TAG guard)
#
# The strict anchors are load-bearing: they exclude rc suffixes
# (7.2.1-WHPG-rc.1), pre-fork tags (7.0.0), and the ~200
# 6.x-NN-g<sha> describe-tags. sort -V so 7.10 orders above 7.9.
set -euo pipefail

major="${1:-7}"
grep -E "^${major}\.[0-9]+\.[0-9]+-WHPG$" | sort -V | tail -1 || true
