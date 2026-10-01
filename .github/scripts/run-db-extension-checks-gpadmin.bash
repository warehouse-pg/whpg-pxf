#!/usr/bin/env bash

# The gpadmin half of the C-extension checks: install both extensions
# into the running demo cluster and run their pg_regress suites.
#
# Invoked by run-db-extension-checks.bash via `su gpadmin -c` as a STANDALONE
# script — it must not rely on any state from the calling shell (shell
# functions and unexported state do not cross the su boundary).
#
# Arguments:
#   $1  WarehousePG install prefix
#   $2  path to gpdemo-env.sh of the running demo cluster
#   $3  path to the PXF checkout

set -euo pipefail

PREFIX="${1:?usage: run-db-extension-checks-gpadmin.bash PREFIX GPDEMO_ENV PXF_SRC}"
GPDEMO_ENV="${2:?missing gpdemo-env.sh path}"
PXF_SRC="${3:?missing PXF checkout path}"

# shellcheck disable=SC1090,SC1091
source "${PREFIX}/greenplum_path.sh"
# shellcheck disable=SC1090,SC1091
source "${GPDEMO_ENV}"

echo "==> Cluster postcondition"
psql -X -d template1 -Atc 'select version()'

echo "==> external-table: install + installcheck"
make -C "${PXF_SRC}/external-table" install
# Derive the suite list from the Makefile rather than hardcoding it, so
# a suite added upstream runs here by default instead of being skipped
# silently (an allowlist did exactly that when `pxfpartition` was added).
# Then subtract the suites that need a RUNNING PXF service on :5888 —
# they SELECT through pxf:// tables via the built-in Demo connectors,
# which is out of scope for this lane. A new service-needing suite will
# fail loudly here and get added to this list on purpose, which is the
# right default.
needs_service='pxf pxfpartition'
all_suites=$(sed -n 's/^REGRESS[[:space:]]*=[[:space:]]*//p' "${PXF_SRC}/external-table/Makefile")
test -n "${all_suites}" || { echo "ERROR: could not read REGRESS from external-table/Makefile" >&2; exit 1; }
suites=""
for s in ${all_suites}; do
  case " ${needs_service} " in *" ${s} "*) ;; *) suites="${suites} ${s}" ;; esac
done
suites="${suites# }"
test -n "${suites}" || { echo "ERROR: every external-table suite is excluded; nothing to run" >&2; exit 1; }
echo "    Makefile REGRESS: ${all_suites}"
echo "    running:          ${suites}   (excluded, need a PXF service: ${needs_service})"
make -C "${PXF_SRC}/external-table" installcheck REGRESS="${suites}"

# fdw builds and installchecks on every supported major: the expected
# files are major-neutral (gpdiff start_matchignore absorbs the known
# per-major noise lines - see the comment blocks in fdw/sql/).
echo "==> fdw: install + installcheck"
make -C "${PXF_SRC}/fdw" install
make -C "${PXF_SRC}/fdw" installcheck
