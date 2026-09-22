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
#   $4  database major version (6 or 7) — selects the suite set

set -euo pipefail

PREFIX="${1:?usage: run-db-extension-checks-gpadmin.bash PREFIX GPDEMO_ENV PXF_SRC GP_MAJOR}"
GPDEMO_ENV="${2:?missing gpdemo-env.sh path}"
PXF_SRC="${3:?missing PXF checkout path}"
GP_MAJOR="${4:?missing database major version}"

# shellcheck disable=SC1090,SC1091
source "${PREFIX}/greenplum_path.sh"
# shellcheck disable=SC1090,SC1091
source "${GPDEMO_ENV}"

echo "==> Cluster postcondition"
psql -X -d template1 -Atc 'select version()'

echo "==> external-table: install + installcheck"
make -C "${PXF_SRC}/external-table" install
# The suite's `pxf` test performs SELECTs through pxf:// tables via the
# built-in Demo connectors and requires a RUNNING PXF service on :5888
# — out of scope for this lane. Run the two suites that exercise the
# extension itself.
make -C "${PXF_SRC}/external-table" installcheck REGRESS='setup pxfinvalid'

if [ "${GP_MAJOR}" = "7" ]; then
  echo "==> fdw: install + installcheck"
  make -C "${PXF_SRC}/fdw" install
  make -C "${PXF_SRC}/fdw" installcheck
else
  # fdw COMPILES on WHPG 6 (GP_MAJORVERSION >= 6) — the compile job
  # covers that — but its pg_regress expected files are pinned to
  # WHPG 7 server output (e.g. the zero-column CREATE WARNING), so the
  # fdw installcheck runs on the WHPG 7 leg only. Variant expected
  # files for 6 would be the way to lift this if ever wanted.
  echo "==> fdw: installcheck skipped on WHPG ${GP_MAJOR} (expected files are WHPG 7 output; see workflow README)"
fi
