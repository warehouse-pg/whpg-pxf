#!/usr/bin/env bash

# Build and install WarehousePG from source, for the PXF DB Extensions CI lane.
#
# Runs inside the ghcr.io/warehouse-pg/whpg-rocky8-build container (the
# image WarehousePG's own CI builds in), as root. The configure flag set
# and ccache knobs mirror that CI's recipe.
#
# Inputs (environment):
#   WHPG_REF    tag or branch to build (required, e.g. 7.6.0-WHPG or main)
#   WHPG_SHA    expected commit for WHPG_REF (optional; asserted if set)
#   WHPG_MAJOR  database major being built: 7 (default) or 6. Selects
#               the configure recipe; derived from WHPG_REF's leading
#               digit when unset (refs like 'main' derive 7).
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

if [ -n "${WHPG_SHA:-}" ]; then
  # Fetch the pinned COMMIT directly instead of cloning the tag: tags
  # are mutable, and a moved tag must never redden a PR build — the
  # pin-freshness watcher reports tag moves through an issue instead
  # (this lane hit exactly that: upstream re-cut 7.6.0-WHPG mid-review
  # and every cold-cache run failed until the SHA was bumped). Also
  # closes the canary's resolve-then-clone race: the commit that was
  # resolved is the commit that gets built.
  echo "==> Fetching ${WHPG_REPO} @ ${WHPG_SHA} (${WHPG_REF})"
  git init -q "${SRC_DIR}"
  cd "${SRC_DIR}"
  git remote add origin "${WHPG_REPO}"
  git fetch --depth 1 origin "${WHPG_SHA}"
  git checkout -q FETCH_HEAD
  git submodule update --init --depth 1 --recursive
else
  echo "==> Cloning ${WHPG_REPO} @ ${WHPG_REF}"
  git clone --depth 1 --branch "${WHPG_REF}" \
    --recurse-submodules --shallow-submodules \
    "${WHPG_REPO}" "${SRC_DIR}"
  cd "${SRC_DIR}"
fi

# Tags are needed so getversion's `git describe` resolves on branch builds.
git fetch --tags --depth 1 origin || true

built_sha=$(git rev-parse HEAD)
if [ -n "${WHPG_SHA:-}" ] && [ "${built_sha}" != "${WHPG_SHA}" ]; then
  echo "ERROR: checkout resolved to ${built_sha}, expected ${WHPG_SHA}." >&2
  exit 1
fi

WHPG_MAJOR="${WHPG_MAJOR:-}"
if [ -z "${WHPG_MAJOR}" ]; then
  case "${WHPG_REF}" in 6.*) WHPG_MAJOR=6 ;; *) WHPG_MAJOR=7 ;; esac
fi

echo "==> Configuring ccache"
ccache --set-config=max_size=1G
ccache --set-config=compression=true
ccache --zero-stats

echo "==> Configuring WarehousePG (major ${WHPG_MAJOR})"
if [ "${WHPG_MAJOR}" = "6" ]; then
  # WHPG 6 recipe, mirroring warehouse-pg's own 6.x CI with ONE
  # deliberate, lane-scoped deviation: their CI re-runs configure with
  # PYTHON=python3 afterwards to rebuild ONLY plpython for its
  # regression tests — this lane runs no PL/Python suites, so that
  # second pass is skipped.
  #
  # The whole tree builds against Python 2 (WHPG 6 ships PyGreSQL 4.0,
  # and gpdemo / cluster scripts assume `python` is Python 2).
  # The build image does not ship Python 2 — install it first (the RPM
  # registers the alternatives entry), exactly as warehouse-pg's own
  # 6.x CI does; -devel is needed because --with-python builds
  # PL/Python against the Python 2 headers.
  yum install -y --setopt=keepcache=1 python2 python2-devel
  alternatives --set python /usr/bin/python2
  python --version

  # ORCA on WHPG 6 needs Xerces-C 3.1, not the 3.2 the image ships.
  # WHPG 6's gporca is compiled as -std=gnu++98, while Xerces 3.2
  # typedefs XMLCh to char16_t (a C++11 type) in
  # xercesc/util/Xerces_autoconf_config.hpp — so every ORCA
  # translation unit that includes a Xerces header fails with
  # "char16_t does not name a type". WHPG 7 is unaffected because its
  # gporca compiles as -std=c++14.
  #
  # configure does NOT catch this: config/orca.m4 probes
  # AC_CHECK_LIB(xerces-c, strnicmp) and calls that "the Greenplum
  # patched version", but stock xerces-c 3.2.5 exports strnicmp too,
  # so the probe passes and the build dies later in the compile.
  #
  # Remedy is warehouse-pg's own 6.x CI recipe: drop the distro 3.2
  # and build 3.1 from the in-tree helper. The helper downloads
  # xerces-c-3.1.2 from archive.apache.org and verifies it against the
  # SHA-256 committed next to it.
  echo "==> Replacing Xerces-C 3.2 with the 3.1 build ORCA needs"
  yum remove -y xerces-c xerces-c-devel
  rm -rf /tmp/xerces_build
  mkdir -p /tmp/xerces_build/xerces_patch/concourse
  cp -r "${SRC_DIR}/src/backend/gporca/concourse/xerces-c" \
        /tmp/xerces_build/xerces_patch/concourse/
  # build_xerces.py resolves the checksum file relative to the cwd,
  # so it must run from the parent of xerces_patch/.
  ( cd /tmp/xerces_build && /usr/bin/python2 \
      xerces_patch/concourse/xerces-c/build_xerces.py --output_dir=/usr/local )
  ln -sf /usr/local/lib/libxerces-c-3.1.so /usr/lib64/libxerces-c-3.1.so
  ldconfig
  rm -rf /tmp/xerces_build
  # Postcondition: the 3.1 library is the one ORCA will find (testing
  # principle 8 — a bring-up step asserts its own outcome rather than
  # letting a silent miss surface as a confusing compile error).
  test -f /usr/local/lib/libxerces-c-3.1.so
  test -f /usr/local/include/xercesc/util/XercesVersion.hpp
  grep -qx '#define XERCES_VERSION_MAJOR 3' /usr/local/include/xercesc/util/XercesVersion.hpp
  grep -qx '#define XERCES_VERSION_MINOR 1' /usr/local/include/xercesc/util/XercesVersion.hpp
  # The 3.2 headers must be GONE, or gcc could still resolve xercesc/
  # from /usr/include and reintroduce the char16_t failure.
  test ! -e /usr/include/xercesc

  CC='ccache gcc -m64' \
  CFLAGS='-O2 -g3' LDFLAGS='-Wl,--enable-new-dtags -Wl,--export-dynamic' \
  ./configure --disable-gpperfmon --with-gssapi --enable-mapreduce --enable-orafce --enable-ic-proxy \
              --enable-orca --with-libxml --with-pythonsrc-ext --with-uuid=e2fs --with-pgport=5432 --enable-tap-tests \
              --enable-debug-extensions --with-perl --with-python --with-openssl --with-pam --with-ldap --with-includes="" \
              --with-libraries="" --disable-rpath \
              --prefix="${PREFIX}" \
              --mandir="${PREFIX}/man"
else
  # WHPG 7 recipe, as used by WarehousePG's own CI.
  CC='ccache gcc -m64' \
  CFLAGS='-O2 -g3' LDFLAGS='-Wl,--enable-new-dtags -Wl,--export-dynamic' \
  ./configure --with-quicklz --disable-gpperfmon --with-gssapi --enable-mapreduce --enable-orafce --enable-ic-proxy \
              --enable-orca --with-libxml --with-pythonsrc-ext --with-uuid=e2fs --with-pgport=5432 --enable-tap-tests --with-llvm \
              --enable-debug-extensions --with-perl --with-python --with-openssl --with-pam --with-ldap --with-includes="" \
              --with-libraries="" --disable-rpath \
              --prefix="${PREFIX}" \
              --mandir="${PREFIX}/man"
fi

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
if [ "${gp_major}" != "${WHPG_MAJOR}" ]; then
  echo "ERROR: built binary reports gp major ${gp_major}, expected ${WHPG_MAJOR}." >&2
  exit 1
fi

echo "==> Writing provenance"
printf '%s\n' "${WHPG_REF}"   > "${PREFIX}/whpg-build.ref"
printf '%s\n' "${built_sha}"  > "${PREFIX}/whpg-build.sha"
printf '%s\n' "${gp_major}"   > "${PREFIX}/whpg-build.gp-major"

echo "==> Done: WarehousePG ${WHPG_REF} (${built_sha}, gp major ${gp_major}) installed at ${PREFIX}"
