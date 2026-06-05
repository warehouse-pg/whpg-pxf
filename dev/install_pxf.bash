#!/usr/bin/env bash

function display() {
    echo
    echo "=====> $1 <====="
    echo
}

display "Compiling and Installing PXF"
make -C ~gpadmin/workspace/pxf install

display "Installing pxf-hbase jar onto singlecluster HBase RegionServer classpath"
# HBase 2.x deserializes PXF-supplied filter classes (HBaseIntegerComparator,
# HBaseDoubleComparator, etc.) server-side inside the RegionServer JVM. The
# RegionServer therefore needs pxf-hbase-*.jar on its classpath — the PXF
# service itself can read HBase data without this (the Spring Boot fat-jar
# bundles pxf-hbase classes), but any query with a WHERE clause that pushes
# down to HBase will fail with ClassNotFoundException server-side.
#
# This is the dev-side equivalent of what smoke-tests/setup_hbase.bash's
# install_pxf_hbase_jars() does in CI and what customers do at first-install
# time per the root README. Without this step, dev clusters work for plain
# SELECTs over HBase but fail on filter pushdown — and developers have to
# remember to do the cp manually after every PXF rebuild.
#
# Build artifact lives at $PXF_HOME/share/ (NOT $PXF_HOME/lib/, despite the
# pre-PTT-1135 README copy-paste suggestion — the install task in
# pxf-hbase/build.gradle drops here: `from('pxf-hbase/build/libs') { into 'share' }`).
HBASE_LIB="${GPHD_ROOT:-/singlecluster}/hbase/lib"
hbase_jar=""
for candidate in "${PXF_HOME}"/share/pxf-hbase-*.jar; do
    if [[ -f "${candidate}" ]]; then
        hbase_jar="${candidate}"
        break
    fi
done
if [[ -z "${hbase_jar}" ]]; then
    echo "ERROR: pxf-hbase jar not found under ${PXF_HOME}/share/." >&2
    echo "       Ensure 'make install' above completed successfully." >&2
    ls -la "${PXF_HOME}/share/" 2>&1 | head -20 >&2 || true
    exit 1
fi
cp "${hbase_jar}" "${HBASE_LIB}/pxf-hbase.jar"
echo "Installed $(basename "${hbase_jar}") -> ${HBASE_LIB}/pxf-hbase.jar"
echo "NOTE: HBase RegionServer must be restarted to pick up the new jar."
echo "      If singlecluster is already running, stop + restart HBase."

display "Initializing PXF"
pxf init

display "Starting PXF"
pxf start

display "Setting up default PXF server"
cp "${PXF_HOME}"/templates/*-site.xml "${PXF_HOME}"/servers/default

display "Registering PXF Greenplum extension"
psql -d template1 -c "create extension pxf"

#cd ~/workspace/pxf/automation
#make GROUP=smoke