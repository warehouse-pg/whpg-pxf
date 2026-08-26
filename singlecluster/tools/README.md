# singlecluster/tools/

Helper scripts that prepare the `tars/` input directory used by the
top-level `Makefile`.

## `downloadApache.sh`

Populates `singlecluster/tars/` with the four vanilla Apache component
tarballs the build pipeline needs, plus the corresponding checksum
sidecars, then verifies each tarball against its sidecar before exit.

### Usage

```bash
./downloadApache.sh           # download missing tarballs, verify existing
./downloadApache.sh --force   # re-download everything (overrides cache)
```

The script is idempotent: a re-run with all tarballs already present
and their checksums still valid is a no-op. To force a re-download of
just one component, delete its `.tar.gz` (and `.sha512` / `.sha256`)
under `singlecluster/tars/` and re-run.

### Components

Component versions are sourced at runtime from `server/gradle.properties`
(`hadoopVersion`, `hbaseVersion`, `zookeeperVersion`,
`singleclusterHiveVersion`) — that file is the canonical source-of-truth.
The values shown below are the current pins (for orientation;
`gradle.properties` may move ahead of this table):

| Component | gradle.properties key      | Current pin | Source                  | Checksum |
|-----------|----------------------------|-------------|-------------------------|----------|
| Hadoop    | `hadoopVersion`            | 3.4.3       | `dlcdn.apache.org`      | `.sha512` |
| HBase     | `hbaseVersion`             | 2.6.5       | `dlcdn.apache.org`      | `.sha512` |
| ZooKeeper | `zookeeperVersion`         | 3.8.6       | `dlcdn.apache.org`      | `.sha512` |
| Hive      | `singleclusterHiveVersion` | 4.0.1       | `archive.apache.org`    | `.sha256` |

Hive is pinned by `singleclusterHiveVersion` — the test-cluster SERVER
pin — rather than by `hiveVersion`, which selects the Hive client jars
PXF compiles against. See the comments on both keys in
`gradle.properties`.

Hive 4.0.1 is an archived release (`dlcdn.apache.org` only serves the
newest line, currently 4.1.x); only `.sha256` sidecars are published on
`archive.apache.org` (verified for both 2.3.8 and 4.0.1 — no `.sha512`
exists). The script handles per-component checksum algorithms via the
inline `components=` table at the top of the file.

### History

Replaced the retired `downloadCDH.sh` (CDH 5.12.2) and `compressHDP.sh`
(Hortonworks HDP) flows during the migration to the vanilla Apache stack
for PXF 7.0 (HBase 2.x / Hadoop 3.x). See `CHANGELOG.md` for the
release-level summary.
