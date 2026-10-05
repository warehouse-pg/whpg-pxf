#!/usr/bin/env bash
# Fixture tests for the installcheck suite derivation: the lane asks
# `make print-regress` for the EVALUATED REGRESS value instead of
# scraping the Makefile text. Each fixture is a Makefile shape the old
# `sed -n 's/^REGRESS[[:space:]]*=//p'` scrape got wrong; the target
# body below is the one external-table/Makefile defines, extracted
# from the real file so the two cannot drift.
set -euo pipefail
cd "$(dirname "$0")"

target=$(awk '/^print-regress:/{p=1} p{print} p&&/^\t/{exit}' ../../external-table/Makefile)
test -n "${target}" || { echo "FAIL: print-regress target not found in external-table/Makefile"; exit 1; }

fails=0
tmp=$(mktemp -d); trap 'rm -rf "${tmp}"' EXIT

expect() {
  # expect <name> <want> <<< fixture-makefile-body
  local name=$1 want=$2 got
  { cat; printf '\n.PHONY: print-regress\n%s\n' "${target}"; } > "${tmp}/Makefile"
  if ! got=$(make -s --no-print-directory -C "${tmp}" print-regress 2>&1); then
    echo "FAIL ${name}: make exited non-zero: ${got}"; fails=$((fails + 1)); return
  fi
  if [ "${got}" != "${want}" ]; then
    echo "FAIL ${name}: got [${got}], want [${want}]"; fails=$((fails + 1)); return
  fi
  echo "ok   ${name}: [${got}]"
}

expect "plain =" "setup pxf pxfinvalid" <<'M'
REGRESS     = setup pxf pxfinvalid
M

expect "+= on a later line (scrape dropped newsuite)" "setup pxf newsuite" <<'M'
REGRESS = setup pxf
REGRESS += newsuite
M

expect ":= (scrape aborted with empty)" "setup pxf" <<'M'
REGRESS := setup pxf
M

expect "backslash continuation (scrape made a suite named \\)" "setup pxf pxfinvalid" <<'M'
REGRESS = setup \
          pxf \
          pxfinvalid
M

expect "ifeq — only the live arm (scrape ran both)" "a b" <<'M'
ifeq (1,1)
REGRESS = a b
else
REGRESS = c d
endif
M

expect "ifeq — other arm" "c d" <<'M'
ifeq (1,2)
REGRESS = a b
else
REGRESS = c d
endif
M

if [ "${fails}" -ne 0 ]; then echo "${fails} fixture(s) FAILED"; exit 1; fi
echo "all derive-regress fixtures passed"
