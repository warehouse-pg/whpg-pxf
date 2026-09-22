#!/usr/bin/env bash

# Bring up a WarehousePG demo cluster and run the C extensions'
# pg_regress suites (external-table and fdw installcheck).
#
# Runs inside the ghcr.io/warehouse-pg/whpg-rocky8-build container as
# root, against an installed WarehousePG tree produced by
# build-whpg.bash (restored from cache or built in-run).
#
# Inputs (environment):
#   WHPG_REF    the ref the install tree must have been built from (required)
#   PXF_SRC     path to the PXF checkout (required)
#   PREFIX      WarehousePG install prefix (default: /usr/local/greenplum-db-devel)
#   WHPG_SRC    scratch dir for the WarehousePG source clone (default: /tmp/whpg_src)
#
# The gpadmin half of the work lives in run-db-extension-checks-gpadmin.bash as a
# standalone script: shell functions and exported variables do not
# survive an `su` boundary, so nothing past that boundary may rely on
# this script's scope.

set -euo pipefail

PREFIX="${PREFIX:-/usr/local/greenplum-db-devel}"
WHPG_SRC="${WHPG_SRC:-/tmp/whpg_src}"
: "${WHPG_REF:?WHPG_REF is required}"
: "${PXF_SRC:?PXF_SRC is required (path to the PXF checkout)}"

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

echo "==> Asserting install-tree provenance"
for f in whpg-build.ref whpg-build.sha whpg-build.gp-major; do
  test -f "${PREFIX}/${f}" || { echo "ERROR: ${PREFIX}/${f} missing — not a tree built by build-whpg.bash" >&2; exit 1; }
done
tree_ref=$(cat "${PREFIX}/whpg-build.ref")
tree_sha=$(cat "${PREFIX}/whpg-build.sha")
tree_gp_major=$(cat "${PREFIX}/whpg-build.gp-major")
if [ "${tree_ref}" != "${WHPG_REF}" ]; then
  echo "ERROR: install tree was built from '${tree_ref}', expected '${WHPG_REF}'" >&2
  exit 1
fi

echo "==> Cross-checking the installed binary"
"${PREFIX}/bin/pg_config" --version
actual_gp_major=$(
  # shellcheck disable=SC1091
  source "${PREFIX}/greenplum_path.sh"
  postgres --gp-version | sed -n 's/[^0-9]*\([0-9]\{1,\}\).*/\1/p' | head -1
)
if [ "${actual_gp_major}" != "${tree_gp_major}" ]; then
  echo "ERROR: installed binary reports gp major ${actual_gp_major}, provenance says ${tree_gp_major}" >&2
  exit 1
fi

echo "==> Fetching WarehousePG source at the exact built commit (${tree_sha})"
# gpdemo and the gpadmin setup script live in the WarehousePG source
# tree. Fetch the exact SHA the install was built from so scripts and
# binaries can never drift apart (a branch ref may have moved since).
if [ ! -d "${WHPG_SRC}/.git" ]; then
  git init -q "${WHPG_SRC}"
  git -C "${WHPG_SRC}" remote add origin https://github.com/warehouse-pg/warehouse-pg
fi
git -C "${WHPG_SRC}" fetch --depth 1 origin "${tree_sha}"
git -C "${WHPG_SRC}" checkout -q FETCH_HEAD

echo "==> Setting up the gpadmin user"
# Idempotent vs the image's pre-created gpadmin (the script guards its
# useradd); its real work here is the sudoers/ssh halves that gpdemo's
# gpinitsystem needs.
# setup_gpadmin_user.bash resolves gpdb_src relative to its own cwd,
# so the self-referential symlink inside WHPG_SRC is the only one
# needed (no symlink in the PXF checkout — it would litter a
# developer's working tree when reproducing locally).
export TEST_OS=centos
(cd "${WHPG_SRC}" && ln -sfn . gpdb_src && ./concourse/scripts/setup_gpadmin_user.bash)

chown -R gpadmin:gpadmin "${PREFIX}" "${WHPG_SRC}" "${PXF_SRC}"

echo "==> Creating the demo cluster (no mirrors — this lane tests extension DDL, not the database)"
export STATEMENT_MEM=250MB
su gpadmin -c "source '${PREFIX}/greenplum_path.sh' && cd '${WHPG_SRC}/gpAux/gpdemo' && LANG=en_US.utf8 make create-demo-cluster WITH_MIRRORS=false NUM_PRIMARY_MIRROR_PAIRS=1"

gpdemo_env="${WHPG_SRC}/gpAux/gpdemo/gpdemo-env.sh"
test -f "${gpdemo_env}" || { echo "ERROR: ${gpdemo_env} not created — demo cluster bring-up failed silently" >&2; exit 1; }

echo "==> Running the extension installchecks as gpadmin"
# Standalone script invocation across the su boundary — see header note.
su gpadmin -c "bash '${script_dir}/run-db-extension-checks-gpadmin.bash' '${PREFIX}' '${gpdemo_env}' '${PXF_SRC}' '${tree_gp_major}'"

echo "==> All extension checks passed"
