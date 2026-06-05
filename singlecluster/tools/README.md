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

| Component | Version | Source                  | Checksum |
|-----------|---------|-------------------------|----------|
| Hadoop    | 3.3.6   | `dlcdn.apache.org`      | `.sha512` |
| HBase     | 2.6.5   | `dlcdn.apache.org`      | `.sha512` |
| ZooKeeper | 3.8.6   | `dlcdn.apache.org`      | `.sha512` |
| Hive      | 2.3.8   | `archive.apache.org`    | `.sha256` |

Hive 2.3.8 is an archived release (current Apache mirrors only serve
the latest line); only `.sha256` sidecars are published on
`archive.apache.org` (verified — no `.sha512` exists). The script
handles per-component checksum algorithms via the inline
`components=` table at the top of the file.

### History

Replaced the retired `downloadCDH.sh` (CDH 5.12.2) and `compressHDP.sh`
(Hortonworks HDP) flows in PTT-1135 Phase 4a — see
[`03-plan/implementation-plan.md`](../../../../Documents/WorkTasks/ptt-1135-pxf/03-plan/implementation-plan.md)
§4a.1.
