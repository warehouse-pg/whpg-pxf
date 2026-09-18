#!/usr/bin/env bash

# The gpadmin half of the C-extension checks: install both extensions
# into the running demo cluster and run their pg_regress suites.
#
# Invoked by run-c-checks.bash via `su gpadmin -c` as a STANDALONE
# script — it must not rely on any state from the calling shell (shell
# functions and unexported state do not cross the su boundary).
#
# Arguments:
#   $1  WarehousePG install prefix
#   $2  path to gpdemo-env.sh of the running demo cluster
#   $3  path to the PXF checkout

set -euo pipefail

PREFIX="${1:?usage: run-c-checks-gpadmin.bash PREFIX GPDEMO_ENV PXF_SRC}"
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
# The suite's `pxf` test performs SELECTs through pxf:// tables via the
# built-in Demo connectors and requires a RUNNING PXF service on :5888
# — out of scope for this lane (that surface is covered post-merge by
# the release packaging CI, which tests against a running service).
# Run the two suites that exercise the extension itself.
make -C "${PXF_SRC}/external-table" installcheck REGRESS='setup pxfinvalid'

echo "==> fdw: install + installcheck"
make -C "${PXF_SRC}/fdw" install
make -C "${PXF_SRC}/fdw" installcheck
