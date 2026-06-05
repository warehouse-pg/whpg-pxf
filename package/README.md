PXF Packaging
============

PXF consists of 3 groups of artifacts, each developed using a different underlying technology:

* Greenplum extension -- written in C; when built, produces a `pxf.so` library and configuration files
* PXF Server -- written in Java (Spring Boot); when built, produces a `pxf-app-<version>.jar` (with embedded Tomcat), its dependent JAR files, templates and scripts
* Script Cluster Plugin -- written in Go; when built, produces a `pxf-cli` executable

The distributed PXF package for WarehousePG is **`edb-whpg<N>-pxf`** — one RPM per WarehousePG major
version. It is produced by the WarehousePG extension-packaging build (which compiles PXF via the top-level
`make stage` target and assembles the RPM from its own release spec); it is **not** built from this
directory. This document describes that package and how to install, upgrade, and remove it.

## The package

* **Name:** `edb-whpg<N>-pxf`, where `<N>` is the WarehousePG major version (e.g. `edb-whpg6-pxf`, `edb-whpg7-pxf`).
* **Version / release:** the PXF release version (e.g. `7.0.0`), release number `1`, plus the platform dist tag.
  Example: `edb-whpg7-pxf-7.0.0-1.el8.x86_64.rpm` (and `...el9...`).
* **Install location:** `/usr/local/edb-whpg<N>-pxf`. The package is **relocatable** — pass `--prefix` to `rpm` to install elsewhere.
* **Requires:** `warehouse-pg-<N>` — the matching WarehousePG server package.
* **Extension registration is automatic:** the PXF extension control files are installed into the
  WarehousePG installation (`$GPHOME/share/postgresql/extension/`) at RPM install **and** upgrade time, so
  `CREATE EXTENSION pxf;` works immediately — no manual registration step. On WarehousePG 7 the Foreign Data
  Wrapper extension (`pxf_fdw`) is also included (`CREATE EXTENSION pxf_fdw;`); on WarehousePG 6 only the
  external-table extension (`pxf`) ships.

## Install
```bash
sudo rpm -Uvh edb-whpg7-pxf-7.0.0-1.el8.x86_64.rpm
sudo chown -R gpadmin:gpadmin /usr/local/edb-whpg7-pxf
```
A JDK (8 or 11) must be available to run the PXF server.

## Upgrade
Use the same `rpm -U` command with the newer RPM:
```bash
sudo rpm -Uvh edb-whpg7-pxf-<newer-version>-1.el8.x86_64.rpm
```
The previous version's files and runtime directories are replaced, the extension control files are
re-registered into `$GPHOME`, and your PXF configuration directory (`$PXF_BASE`) is left intact.

## Remove
```bash
sudo rpm -e edb-whpg7-pxf
```
This removes the files installed by the package and the PXF runtime directories. The PXF configuration
directory is preserved.

---
> The in-repo `pxf-gp<N>.spec` files (and the `make rpm` / `make deb` targets) are a **legacy source-build**
> path; they are **not** used to produce the released `edb-whpg<N>-pxf` package and are retained for
> reference only.
