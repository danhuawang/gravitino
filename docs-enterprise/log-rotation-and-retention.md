<!--
Copyright 2026 Datastrato Inc.
-->

# Log Rotation and Retention

This page lists the log files that Gravitino writes, how each one rotates, and how long its history
is kept in the tarball distribution.

## Log files

All files are written to the log directory, `${GRAVITINO_HOME}/logs` by default. Set
`GRAVITINO_LOG_DIR` in `conf/gravitino-env.sh` to change it.

| Process                          | Log file                            | Log4j2 configuration file                          |
|----------------------------------|-------------------------------------|----------------------------------------------------|
| Gravitino server                 | `gravitino-server.log`              | `conf/log4j2.properties`                           |
| Gravitino server, audit          | `gravitino_audit.log`               | `conf/log4j2.properties`                           |
| Gravitino server, lineage        | `gravitino_lineage.log`             | `conf/log4j2.properties`                           |
| Gravitino server, search         | `gravitino-search.log`              | `conf/log4j2.properties`                           |
| Gravitino metrics service        | `gravitino-metrics-service.log`     | `conf/gravitino-metrics-service-log4j2.properties` |
| Iceberg REST server (standalone) | `gravitino-iceberg-rest-server.log` | `conf/gravitino-iceberg-rest-log4j2.properties`    |
| Lance REST server (standalone)   | `gravitino-lance-rest-server.log`   | `conf/gravitino-lance-rest-log4j2.properties`      |

An Iceberg REST server that runs inside the Gravitino server writes to `gravitino-server.log`.

Each process started with `bin/<script>.sh start` also writes a `.out` file, for example
`gravitino-server.out`. See [The .out files](#the-out-files).

## How logs rotate

A log rotates when its current file reaches the roll size or when the day ends. Every rotation
compresses the file into its own archive:

```text
<log name>_<yyyyMMdd>.<index>.log.gz
```

For example, `gravitino-server_20260916.1.log.gz`, `gravitino-server_20260916.2.log.gz`. The index
starts at 1 each day and has no upper limit, so no archive replaces another.

When a log rotates, its archives are checked, and an archive is deleted when either of these is true:

- It is older than `logMaxAge`.
- The archives of the same log exceed that log's total size cap. The oldest archives are deleted
  first, until the rest fit under the cap.

Deletion only looks at the archives of the log itself, directly in the log directory. It does not
touch other logs, other files, or subdirectories. The cap counts archives only, so the disk used by
a log can be up to the cap plus its current file.

## Defaults

| Log file                            | Roll size | Kept for | Total size cap | Cap property             |
|-------------------------------------|-----------|----------|----------------|--------------------------|
| `gravitino-server.log`              | 100MB     | 30 days  | 2GB            | `serverLogMaxTotalSize`  |
| `gravitino_audit.log`               | 256MB     | 30 days  | 10GB           | `auditLogMaxTotalSize`   |
| `gravitino_lineage.log`             | 100MB     | 30 days  | 1GB            | `lineageLogMaxTotalSize` |
| `gravitino-search.log`              | 100MB     | 30 days  | 1GB            | `searchLogMaxTotalSize`  |
| `gravitino-metrics-service.log`     | 100MB     | 30 days  | 1GB            | `serverLogMaxTotalSize`  |
| `gravitino-iceberg-rest-server.log` | 100MB     | 30 days  | 2GB            | `serverLogMaxTotalSize`  |
| `gravitino-lance-rest-server.log`   | 100MB     | 30 days  | 1GB            | `serverLogMaxTotalSize`  |

Archives are gzip files. Server logs usually compress to about a tenth of their size, so a 2GB cap
holds roughly 20GB of log text, or about 650MB a day for 30 days. Raise a cap if a server logs more
than that and you need the full 30 days.

## Change retention

Retention is set at the top of each Log4j2 configuration file. For example, in
`conf/log4j2.properties`:

```properties
property.logMaxAge = 30d
property.serverLogMaxTotalSize = 2GB
property.auditLogMaxTotalSize = 10GB
property.lineageLogMaxTotalSize = 1GB
property.searchLogMaxTotalSize = 1GB
```

To keep audit logs for 90 days with up to 30GB of archives, change the two values and restart the
server:

```properties
property.logMaxAge = 90d
property.auditLogMaxTotalSize = 30GB
```

The deletion rules are Log4j2
[Delete action](https://logging.apache.org/log4j/2.x/manual/appenders/rolling-file.html#DeleteAction)
conditions, and each `if...` key is named after a condition type: `ifFileName` (`IfFileName`) selects
the archives of the log by name, and `ifAny` (`IfAny`) deletes an archive when either
`ifLastModified` (`IfLastModified`, older than an age) or `ifAccumulatedFileSize`
(`IfAccumulatedFileSize`, beyond the size cap) matches.

`logMaxAge` applies to every log in the same file. To keep one log longer than the others, set
`appender.<name>.strategy.delete.ifFileName.ifAny.ifLastModified.age` to a fixed value instead of
`${logMaxAge}`:

```properties
appender.audit_file.strategy.delete.ifFileName.ifAny.ifLastModified.age = 90d
```

The roll size of each log is its `policies.size.size`, for example
`appender.rolling.policies.size.size = 100MB`.

If you change the archive name in a `filePattern`, change
`appender.<name>.strategy.delete.ifFileName.glob` to match. Otherwise the old archives of that log are
never deleted.

## The .out files

`bin/<script>.sh start` sends the standard output and standard error of the process to a `.out`
file. The processes write their logs through Log4j2, so a `.out` file only holds output from before
Log4j2 starts and fatal JVM errors.

Each `start` rotates the `.out` file first. The previous files are kept as `<name>.out.1` (newest)
to `<name>.out.5` (oldest). To keep a different number, set `GRAVITINO_OUT_FILE_KEEP` in
`conf/gravitino-env.sh`:

```bash
export GRAVITINO_OUT_FILE_KEEP=10
```

Set it to `0` to keep no previous file. `bin/<script>.sh run`, which the Docker images use, writes
to the console and creates no `.out` file.

## Upgrade

An upgrade does not replace the files in an existing `conf` directory. To get the new rotation
behavior:

1. Merge the changes from the new `conf/log4j2.properties.template`,
   `conf/gravitino-iceberg-rest-log4j2.properties.template`, and
   `conf/gravitino-lance-rest-log4j2.properties.template` into your configuration files.
2. Copy `conf/gravitino-metrics-service-log4j2.properties.template` to
   `conf/gravitino-metrics-service-log4j2.properties`. Without it, the metrics service uses
   `conf/log4j2.properties` and prints a notice at startup.
3. Restart the processes.

Archives from older releases, such as `gravitino-server_20260915.log.gz`, match the new rules and
are deleted by age and by the total size cap like new archives.

Older releases set the audit log retention with
`appender.audit_file.strategy.delete.ifAll.ifLastModified.age`. That key no longer exists. Do not
carry it over: Log4j2 rejects the whole file when it finds it, and the server then writes no log
files at all. Use `property.logMaxAge`, or
`appender.audit_file.strategy.delete.ifFileName.ifAny.ifLastModified.age` for the audit log alone.
