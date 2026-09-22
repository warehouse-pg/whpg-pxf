# PXF Documentation

**Note:** The official WarehousePG PXF documentation is published at
[https://warehouse-pg.io/pxf/6x/](https://warehouse-pg.io/pxf/6x/) and is the
maintained reference. This in-repo book remains available while the official
site reaches full coverage of its content; once parity is reached, this book
will be retired in its favor.

This directory contains the book and markdown source for the PXF docs. The
markdown was historically built into HTML output using
[Bookbinder](https://github.com/cloudfoundry-incubator/bookbinder).

Bookbinder is a Ruby gem that binds together a unified documentation web application from markdown, html, and/or DITA source material. The source material for bookbinder must be stored either in local directories or in GitHub repositories. Bookbinder runs [middleman](http://middlemanapp.com/) to produce a Rackup app that can be deployed locally or as a Web application.

This document describes the book layout. It includes the sections:

* [About Bookbinder](#about)
* [Building the Documentation (Legacy)](#building_docker)
* [Getting More Information](#moreinfo)


<a name="about"></a>
## About Bookbinder

You use bookbinder from within a project called a **book**. The book includes a configuration file named `config.yml` that specifies the documentation repositories/directories to use as source material. Bookbinder provides a set of scripts to aggregate those repositories and publish them to various locations in your final web application.

PXF provides a preconfigured **book** in the `docs/book` directory of this repo. This configuration was used to build HTML for the PXF docs.

<a name="building_docker"></a>
## Building the Documentation (Legacy)

This book was historically rendered with Bookbinder via the docker harness now kept
under `docs/docker` for reference. Building the book locally is no longer supported
or verified. The published documentation is the official site referenced above.

<a name="moreinfo"></a>
## Getting More Information

Bookbinder provides additional functionality to construct books from multiple Github repos, to perform variable substitution, and also to automatically build documentation in a continuous integration pipeline.  For more information, see [https://github.com/pivotal-cf/bookbinder](https://github.com/pivotal-cf/bookbinder).
