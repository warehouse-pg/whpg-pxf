#!/usr/bin/env bash

# Build and install WarehousePG from source, for the C-extension CI lane.
#
# Runs inside the ghcr.io/warehouse-pg/whpg-rocky8-build container (the
# image WarehousePG's own CI builds in), as root. The configure flag set
# and ccache knobs mirror that CI's recipe.
#
# Inputs (environment):
#   WHPG_REF    tag or branch to build (required, e.g. 7.6.0-WHPG or main)
#   WHPG_SHA    expected commit for WHPG_REF (optional; asserted if set)
#   WHPG_REPO   source repository (default: warehouse-pg/warehouse-pg)
#   PREFIX      install prefix (default: /usr/local/greenplum-db-devel)
#   SRC_DIR     scratch checkout dir (default: /tmp/whpg_src)
#
# Output: an installed tree at $PREFIX carrying provenance files
# (whpg-build.ref, whpg-build.sha, whpg-build.gp-major) that every
# consumer job asserts before use.

set -euo pipefail

WHPG_REPO="${WHPG_REPO:-https://github.com/warehouse-pg/warehouse-pg}"
PREFIX="${PREFIX:-/usr/local/greenplum-db-devel}"
SRC_DIR="${SRC_DIR:-/tmp/whpg_src}"
: "${WHPG_REF:?WHPG_REF is required (tag or branch to build)}"

echo "==> Cloning ${WHPG_REPO} @ ${WHPG_REF}"
git clone --depth 1 --branch "${WHPG_REF}" \
  --recurse-submodules --shallow-submodules \
  "${WHPG_REPO}" "${SRC_DIR}"
cd "${SRC_DIR}"

# Tags are needed so getversion's `git describe` resolves on branch builds.
git fetch --tags --depth 1 origin || true

built_sha=$(git rev-parse HEAD)
if [ -n "${WHPG_SHA:-}" ] && [ "${built_sha}" != "${WHPG_SHA}" ]; then
  echo "ERROR: ${WHPG_REF} resolved to ${built_sha}, expected ${WHPG_SHA}." >&2
  echo "The workflow's pin (tag + SHA) must be bumped together." >&2
  exit 1
fi

echo "==> Configuring ccache"
ccache --set-config=max_size=1G
ccache --set-config=compression=true
ccache --zero-stats

echo "==> Configuring WarehousePG"
# Flag set as used by WarehousePG's own CI. Options only recognized by
# WHPG 6 produce harmless "unrecognized options" warnings on 7.
CC='ccache gcc -m64' \
CFLAGS='-O2 -g3' LDFLAGS='-Wl,--enable-new-dtags -Wl,--export-dynamic' \
./configure --with-quicklz --disable-gpperfmon --with-gssapi --enable-mapreduce --enable-orafce --enable-ic-proxy \
            --enable-orca --with-libxml --with-pythonsrc-ext --with-uuid=e2fs --with-pgport=5432 --enable-tap-tests --with-llvm \
            --enable-debug-extensions --with-perl --with-python --with-openssl --with-pam --with-ldap --with-includes="" \
            --with-libraries="" --disable-rpath \
            --prefix="${PREFIX}" \
            --mandir="${PREFIX}/man"

echo "==> Building"
make -j"$(nproc)"

echo "==> Installing to ${PREFIX}"
make install -j"$(nproc)"

ccache --show-stats || true

echo "==> Postconditions"
"${PREFIX}/bin/pg_config" --version
pgxs_path=$("${PREFIX}/bin/pg_config" --pgxs)
test -f "${pgxs_path}"

# gp major version, cross-checked by consumers against the running binary
gp_major=$(
  # shellcheck disable=SC1091
  source "${PREFIX}/greenplum_path.sh"
  postgres --gp-version | sed -n 's/[^0-9]*\([0-9]\{1,\}\).*/\1/p' | head -1
)
test -n "${gp_major}"

echo "==> Writing provenance"
printf '%s\n' "${WHPG_REF}"   > "${PREFIX}/whpg-build.ref"
printf '%s\n' "${built_sha}"  > "${PREFIX}/whpg-build.sha"
printf '%s\n' "${gp_major}"   > "${PREFIX}/whpg-build.gp-major"

echo "==> Done: WarehousePG ${WHPG_REF} (${built_sha}, gp major ${gp_major}) installed at ${PREFIX}"
