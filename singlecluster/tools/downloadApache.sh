#!/usr/bin/env bash
#
# downloadApache.sh — populate singlecluster/tars/ with vanilla Apache
# component tarballs for the singlecluster build pipeline.
#
# Replaces the legacy downloadCDH.sh + compressHDP.sh flow (CDH/HDP support
# was retired).
#
# Component versions are sourced at runtime from server/gradle.properties
# (the canonical source-of-truth pin for the whole tree). Bump a version
# there and this script — along with the smoke setup_*.bash, the PXF
# client lib build, and every other consumer — picks it up automatically.
# Do NOT hardcode versions in this file.
#
# Components:
#   - Hadoop    (dlcdn.apache.org, .sha512 checksum)
#   - HBase     (dlcdn.apache.org, .sha512 checksum)
#   - ZooKeeper (dlcdn.apache.org, .sha512 checksum)
#   - Hive      (archive.apache.org, .sha256 checksum — see note)
#
# Note on Hive: dlcdn.apache.org carries only current Apache releases.
# Superseded lines (2.3.x, and 4.0.x since 4.1 shipped) live on
# archive.apache.org, which publishes .sha256 sidecars but NOT .sha512
# (verified via HTTP HEAD for both 2.3.8 and 4.0.1). Per-component
# checksum algorithm is therefore configurable below.
#
# Output: tars/<component>.tar.gz + tars/<component>.tar.gz.<sha512|sha256>
# Flat layout (one entry per file), consumed directly by the Makefile's
# %.extracted rule.
#
# Idempotent: skips a component if both tarball and verified checksum are
# already present. To force re-download, delete the relevant files in tars/
# first (or pass --force).

set -euo pipefail

force=false
if [[ "${1:-}" == "--force" ]]; then
    force=true
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tars_dir="${script_dir}/../tars"
mkdir -p "${tars_dir}"

# Resolve component versions from server/gradle.properties (canonical pin).
# Matches the pattern used by the smoke setup_*.bash scripts so all
# consumers read from one place. Fail fast if any pin is missing — better
# to surface a config error here than to fetch a 404'd URL later.
gradle_props="${script_dir}/../../server/gradle.properties"
if [[ ! -f "${gradle_props}" ]]; then
    echo "ERROR: cannot find canonical version pin file at ${gradle_props}" >&2
    echo "       Expected layout: <repo>/server/gradle.properties relative to <repo>/singlecluster/tools/" >&2
    exit 1
fi

read_version() {
    local key="$1"
    local value
    value=$(awk -F= -v k="^${key}=" '$0 ~ k {print $2}' "${gradle_props}" | tr -d '\r\n')
    if [[ -z "${value}" ]]; then
        echo "ERROR: could not read ${key} from ${gradle_props}" >&2
        exit 1
    fi
    printf '%s' "${value}"
}

# singleclusterHadoopVersion, not hadoopVersion: this script provisions the
# test CLUSTER (a Hadoop server), whereas hadoopVersion pins the client jars
# bundled into the PXF service jar. They are deliberately separate -- see the
# comment on both keys in server/gradle.properties.
HADOOP_VERSION=$(read_version singleclusterHadoopVersion)
HBASE_VERSION=$(read_version hbaseVersion)
# singleclusterHiveVersion, not hiveVersion, for the same reason as Hadoop
# above: this script provisions the test CLUSTER's Hive service, whereas
# hiveVersion pins the Hive client jars PXF compiles against. See the
# comment on both keys in server/gradle.properties.
HIVE_VERSION=$(read_version singleclusterHiveVersion)
ZOOKEEPER_VERSION=$(read_version zookeeperVersion)

echo "Resolved component versions from ${gradle_props}:"
echo "  Hadoop    = ${HADOOP_VERSION}"
echo "  HBase     = ${HBASE_VERSION}"
echo "  ZooKeeper = ${ZOOKEEPER_VERSION}"
echo "  Hive      = ${HIVE_VERSION}"

# Each entry: <local_filename>|<url>|<sha_algo>|<sha_url>
# sha_algo is "sha512" or "sha256"; sha_url is the sidecar URL.
components=(
    "hadoop-${HADOOP_VERSION}.tar.gz|https://dlcdn.apache.org/hadoop/common/hadoop-${HADOOP_VERSION}/hadoop-${HADOOP_VERSION}.tar.gz|sha512|https://dlcdn.apache.org/hadoop/common/hadoop-${HADOOP_VERSION}/hadoop-${HADOOP_VERSION}.tar.gz.sha512"
    "hbase-${HBASE_VERSION}-bin.tar.gz|https://dlcdn.apache.org/hbase/${HBASE_VERSION}/hbase-${HBASE_VERSION}-bin.tar.gz|sha512|https://dlcdn.apache.org/hbase/${HBASE_VERSION}/hbase-${HBASE_VERSION}-bin.tar.gz.sha512"
    "apache-zookeeper-${ZOOKEEPER_VERSION}-bin.tar.gz|https://dlcdn.apache.org/zookeeper/zookeeper-${ZOOKEEPER_VERSION}/apache-zookeeper-${ZOOKEEPER_VERSION}-bin.tar.gz|sha512|https://dlcdn.apache.org/zookeeper/zookeeper-${ZOOKEEPER_VERSION}/apache-zookeeper-${ZOOKEEPER_VERSION}-bin.tar.gz.sha512"
    "apache-hive-${HIVE_VERSION}-bin.tar.gz|https://archive.apache.org/dist/hive/hive-${HIVE_VERSION}/apache-hive-${HIVE_VERSION}-bin.tar.gz|sha256|https://archive.apache.org/dist/hive/hive-${HIVE_VERSION}/apache-hive-${HIVE_VERSION}-bin.tar.gz.sha256"
)

# Use shasum on macOS / sha512sum + sha256sum on Linux. Both produce the
# canonical "<hexdigest>  <filename>" format that sha512sum -c / sha256sum -c
# expects, so we always normalize the sidecar to that form before verifying.
hash_cmd() {
    local algo="$1"   # sha512 | sha256
    local file="$2"
    if command -v "${algo}sum" >/dev/null 2>&1; then
        "${algo}sum" "${file}" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        local bits=512
        [[ "${algo}" == "sha256" ]] && bits=256
        shasum -a "${bits}" "${file}" | awk '{print $1}'
    else
        echo "ERROR: neither ${algo}sum nor shasum is available" >&2
        exit 1
    fi
}

# Apache .sha512 / .sha256 sidecars come in a few formats:
#   GNU:            <hex>  <filename>                         (Hadoop, ZK)
#   BSD (one-line): SHA512 (<filename>) = <hex>               (some Hadoop, others)
#   BSD (multi-line):
#     <filename>: AB CD EF 01 ...
#                  23 45 67 89 ...                            (HBase)
#   coreutils-alt:  <filename>: <hex>                         (rare)
#
# We MUST avoid hex-stripping the whole file (filenames like
# "hadoop-3.3.6-RC1.tar.gz" contain hex digits that pollute the front).
#
# Strategy:
#   1. Try GNU: first whitespace-separated field on the first line, if it's
#      <want_len> hex chars, use it.
#   2. Try BSD one-line: text after "= " on the first line, hex of want_len.
#   3. Try BSD multi-line / coreutils-alt: concatenate all hex chunks AFTER
#      the first ':' or '=' in the file, until want_len chars are collected.
extract_expected_hash() {
    local sidecar="$1"
    local algo="$2"   # sha512 | sha256
    local want_len=128
    [[ "${algo}" == "sha256" ]] && want_len=64

    local first_line first_field gnu_candidate
    first_line=$(head -n 1 "${sidecar}")
    first_field=$(printf '%s' "${first_line}" | awk '{print $1}')
    # GNU format candidate: leading hex string of exact want_len.
    if [[ "${first_field}" =~ ^[0-9a-fA-F]+$ && ${#first_field} -eq ${want_len} ]]; then
        printf '%s' "${first_field}" | tr 'A-F' 'a-f'
        return 0
    fi

    # BSD one-line: "ALGO (filename) = <hex>"
    bsd_candidate=$(printf '%s' "${first_line}" | sed -nE 's/.*= *([0-9a-fA-F]+).*/\1/p')
    if [[ -n "${bsd_candidate}" && ${#bsd_candidate} -eq ${want_len} ]]; then
        printf '%s' "${bsd_candidate}" | tr 'A-F' 'a-f'
        return 0
    fi

    # BSD multi-line / coreutils-alt: skip everything up to and including
    # the first '=' or ':' on the first line, then strip all non-hex from
    # the remainder of the file.
    local body all_hex
    body=$(awk 'NR==1{sub(/^[^=:]*[=:]/, ""); print; next} {print}' "${sidecar}")
    all_hex=$(printf '%s' "${body}" | tr -cd '0-9a-fA-F' | tr 'A-F' 'a-f')
    if [[ ${#all_hex} -ge ${want_len} ]]; then
        printf '%s' "${all_hex:0:${want_len}}"
        return 0
    fi

    # Could not extract a digest of the expected length.
    printf ''
    return 0
}

download_and_verify() {
    local local_name="$1" url="$2" algo="$3" sha_url="$4"
    local tarball="${tars_dir}/${local_name}"
    local sidecar="${tars_dir}/${local_name}.${algo}"

    if [[ "${force}" == "false" && -f "${tarball}" && -f "${sidecar}" ]]; then
        local expected_skip
        expected_skip=$(extract_expected_hash "${sidecar}" "${algo}")
        local actual_skip
        actual_skip=$(hash_cmd "${algo}" "${tarball}")
        if [[ -n "${expected_skip}" && "${expected_skip}" == "${actual_skip}" ]]; then
            echo "[skip] ${local_name} (already present, ${algo} verified)"
            return 0
        fi
        echo "[stale] ${local_name} present but ${algo} mismatch; re-downloading"
        rm -f "${tarball}" "${sidecar}"
    fi

    echo "[fetch] ${local_name}"
    curl -fL --retry 3 --retry-delay 5 -o "${tarball}" "${url}"
    curl -fL --retry 3 --retry-delay 5 -o "${sidecar}" "${sha_url}"

    local expected actual
    expected=$(extract_expected_hash "${sidecar}" "${algo}")
    if [[ -z "${expected}" ]]; then
        echo "ERROR: could not extract ${algo} digest from ${sidecar}" >&2
        exit 1
    fi
    actual=$(hash_cmd "${algo}" "${tarball}")
    if [[ "${expected}" != "${actual}" ]]; then
        echo "ERROR: ${algo} mismatch for ${local_name}" >&2
        echo "  expected: ${expected}" >&2
        echo "  actual:   ${actual}" >&2
        exit 1
    fi
    echo "[ok]    ${local_name} (${algo} verified)"
}

for entry in "${components[@]}"; do
    IFS='|' read -r local_name url algo sha_url <<< "${entry}"
    download_and_verify "${local_name}" "${url}" "${algo}" "${sha_url}"
done

echo ""
echo "All tarballs present in ${tars_dir}:"
ls -lh "${tars_dir}"
