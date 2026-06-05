#!/bin/bash
#
# singlecluster.bash — CI wrapper for the singlecluster build pipeline.
#
# This script is invoked by Concourse tasks (historical) or the equivalent CI
# job; it stages inputs from upstream resources (a tars tarball + a JDBC jar
# tarball) into the singlecluster/ source tree and shells out to `make`.
#
# Args: <HADOOP_VERSION> <HADOOP_DISTRO>
#   HADOOP_DISTRO is the *only* value the script branches on. Per
#   PTT-1135 Phase 4a (implementation-plan.md §4a.3), only "Apache"
#   is accepted; the retired CDH and HDP build paths reject explicitly
#   so any leftover CI consumer fails fast with a clear message.
#   HADOOP_VERSION is stylistic — preserved as a positional arg so that
#   downstream Concourse `args: ["<ver>", "Apache"]` task specs do not
#   need to be re-shaped, but is otherwise informational (the only Hadoop
#   we ship is 3.3.6 per server/gradle.properties; tools/downloadApache.sh
#   is the canonical version source).
#
# Resource layout (Concourse convention preserved):
#   <pwd>/pxf_src/singlecluster/             — checkout of this repo
#   <pwd>/<lower(distro)>_tars_tarball/      — upstream tars resource
#   <pwd>/jdbc/                              — upstream jdbc jar resource
#   <pwd>/../artifacts/                      — output for the built tarball

set -exo pipefail

_main() {
  if [[ $# -ne 2 ]]; then
    >&2 echo "ERROR: usage: singlecluster.bash <HADOOP_VERSION> <HADOOP_DISTRO>"
    >&2 echo "       (HADOOP_DISTRO must be 'Apache' per PTT-1135 Phase 4a)"
    exit 1
  fi
  if [[ "${2}" != "Apache" ]]; then
    >&2 echo "ERROR: HADOOP_DISTRO must be Apache (CDH/HDP retired in PTT-1135 Phase 4a)"
    exit 1
  fi

  singlecluster=$(pwd)/pxf_src/singlecluster
  HADOOP_DISTRO_LOWER=$(echo ${2} | tr A-Z a-z)
  mkdir -p ${singlecluster}/tars
  mv ${HADOOP_DISTRO_LOWER}_tars_tarball/*.tar.gz ${singlecluster}/tars
  mv jdbc/*.jar ${singlecluster}
  pushd ${singlecluster}
    make HADOOP_VERSION="${1}" HADOOP_DISTRO="${2}"
    mv singlecluster-${2}.tar.gz ../../artifacts/singlecluster-${2}.tar.gz
  popd
}

_main "$@"
