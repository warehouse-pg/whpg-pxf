# PXF Documentation

**Note:** The official WarehousePG PXF documentation is published at
[https://warehouse-pg.io/pxf/6x/](https://warehouse-pg.io/pxf/6x/) and is the
maintained reference. This in-repo book remains available while the official
site reaches full coverage of its content; once parity is reached, this book
will be retired in its favor.

This directory contains the markdown source (`content/`) and the
[Bookbinder](https://github.com/pivotal-cf/bookbinder) book configuration
(`book/`) for the PXF docs.

## Reading the Documentation

- Official rendered documentation: [https://warehouse-pg.io/pxf/6x/](https://warehouse-pg.io/pxf/6x/)
- The pages under `content/` in this directory are readable directly as
  markdown.

## Build Tooling (Legacy)

This book was historically rendered with Bookbinder, a retired Ruby
toolchain, via a docker harness that lived in the repository's deprecated
legacy-CI tree. Building the book locally is no longer supported or
verified. The published documentation is the official site above.
