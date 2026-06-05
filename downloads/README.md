Downloads Directory
============

> **Note:** This staging directory was used by the deprecated Docker-based
> dev flow (`dev/start.bash`, also deprecated). See the root
> [README.md](../README.md#local-development-setup) for the native-host
> development flow.

Place GPDB RPM (for RPM-based platforms) or GPDB DEB (for Ubuntu) packages in this directory.

PLEASE DO NOT check these artifacts into this Git repository !!!

For example, one of the following artifacts could be used for Greenplum 7:

```
greenplum-db-7.x.y-el8-x86_64.rpm
greenplum-db-7.x.y-el9-x86_64.rpm
```

You should use only the artifact for the operating system that corresponds to your target platform.