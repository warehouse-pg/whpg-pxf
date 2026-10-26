PXF Packaging
============

PXF consists of 3 groups of artifacts, each developed using a different underlying technology:

* Greenplum extension -- written in C; when built, produces a `pxf.so` library and configuration files
* PXF Server -- written in Java (Spring Boot); when built, produces a `pxf-app-<version>.jar` (with embedded Tomcat), its dependent JAR files, templates and scripts
* Script Cluster Plugin -- written in Go; when built, produces a `pxf-cli` executable

The distributed PXF package for WarehousePG is **`edb-whpg<N>-pxf`** — one RPM per WarehousePG major
version. It is produced by the WarehousePG extension-packaging build, which compiles PXF via the top-level
`make stage` target and assembles the RPM from its own release spec. This repository holds no package
specs or installer scripts of its own; the former in-tree `pxf-gp<N>.spec`, Debian control files and
`install_*` helpers (and the `make tar` / `rpm` / `deb` targets that used them) were a Greenplum-era
source-build path with no consumer and were removed. They remain in history under the
`archive/concourse-final` tag. This document describes the shipped package and how to install, upgrade,
and remove it.

## The package

* **Name:** `edb-whpg<N>-pxf`, where `<N>` is the WarehousePG major version (e.g. `edb-whpg6-pxf`, `edb-whpg7-pxf`).
* **Version / release:** the PXF release version (e.g. `6.10.3`), release number `1`, plus the platform dist tag.
  Example: `edb-whpg7-pxf-6.10.3-1.el8.x86_64.rpm` (and `...el9...`).
* **Install location:** `/usr/edb/whpg<N>-pxf`. The package is **relocatable** — pass `--prefix` to `rpm` to install elsewhere.
  Releases up to and including 6.10.3 installed under `/usr/local/edb-whpg<N>-pxf`; see [Upgrade](#upgrade) for
  what happens when an upgrade crosses that change.
* **Requires:** `warehouse-pg-<N>` — the matching WarehousePG server package.
* **Extension registration is automatic:** the PXF extension control files are installed into the
  WarehousePG installation (`$GPHOME/share/postgresql/extension/`) at RPM install **and** upgrade time, so
  `CREATE EXTENSION pxf;` works immediately — no manual registration step. On WarehousePG 7 the Foreign Data
  Wrapper extension (`pxf_fdw`) is also included (`CREATE EXTENSION pxf_fdw;`); on WarehousePG 6 only the
  external-table extension (`pxf`) ships.

## Install
```bash
sudo rpm -Uvh edb-whpg7-pxf-6.10.3-1.el8.x86_64.rpm
sudo chown -R gpadmin:gpadmin /usr/edb/whpg7-pxf
```
A JDK (8 or 11) must be available to run the PXF server.

## Upgrade
Run the same `rpm -U` command with the newer RPM on every host, give the install directory back to
`gpadmin` on every host, then restart PXF once from the coordinator (as `gpadmin`):
```bash
sudo rpm -Uvh edb-whpg7-pxf-<newer-version>-1.el8.x86_64.rpm   # on every host
sudo chown -R gpadmin:gpadmin /usr/edb/whpg7-pxf                 # on every host
pxf cluster restart                                              # once, from the coordinator
```
The previous version's files and runtime directories are replaced, the extension control files are
re-registered into `$GPHOME`, and your PXF configuration directory (`$PXF_BASE`) is left intact. The
`chown` is not optional: the package owns the install directory and its subdirectories as `root`, so
`rpm -U` resets the ownership that the [Install](#install) step set, including `run/`, `logs/`,
`keytabs/` and `servers/` when `$PXF_BASE` is left at its default. Without it, `pxf cluster restart`
fails (`Operation not permitted` on `run/pxf-app.pid`), the hosts keep serving the previous version,
and `gpadmin` can no longer read its keytabs and server configurations. The running PXF servers keep
serving the previous version until the restart; after it, every host runs the new one with its
configuration and keytabs unchanged.

### Upgrading from 6.10.3 or earlier (install path change)

Releases up to 6.10.3 installed under `/usr/local/edb-whpg<N>-pxf`; newer RPMs install under
`/usr/edb/whpg<N>-pxf`. The same `rpm -U` handles the move. The RPM's scriptlets detect the previous
prefix (from the `pxf.control` the old release registered in `$GPHOME`, falling back to probing
`/usr/local/edb-whpg<N>-pxf`) and, after the old files are removed, copy into the new prefix what rpm
does not own: the default `$PXF_BASE` content (`servers/`, `lib/`, `keytabs/`, `run/`) and the `conf/`
files. An edited config file keeps your version, with the packaged one placed beside it as `.rpmnew`.
Ownership of the new directory follows the old one.

The old directory is left in place with compatibility symlinks to the PXF libraries, because existing
`pxf` (and `pxf_fdw`) function definitions still hold the old absolute library path. The scriptlet
prints the remaining steps: point `PATH` at the new `bin/`, restart PXF (`pxf cluster stop` then
`pxf cluster start`), repoint the function definitions to the new prefix, then remove the old
directory by hand. If `$PXF_BASE` was set to a directory outside the install prefix, nothing moves
and it keeps working unchanged. If the old release was installed with a custom `--prefix` that the
scriptlets cannot find, the upgrade warns and the copy has to be done by hand.

## Remove
```bash
sudo rpm -e edb-whpg7-pxf
```
This removes the files installed by the package and the PXF runtime directories. The PXF configuration
directory is preserved.
