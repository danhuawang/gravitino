# Datastrato Enterprise Flink Connector

> **📖 Full Documentation**: See [Flink Connector Enterprise Documentation](../../../docs-enterprise/flink-connector/flink-connector-enterprise.md) for complete installation, configuration, and usage instructions.

## Overview

Image: `datastrato/enterprise-flink-connector:{version}`

This image packages the open source Apache Gravitino Flink connector with Datastrato Enterprise adapters (Oracle, SQL Server, etc.). Use it as a Kubernetes init container or unpack it on a VM to install the connector JARs.

**Supported Flink versions**: 1.18, 1.19, 1.20 (Scala 2.12)

## ⚠️ Important Notes

- **Version Compatibility**: The connector must not be newer than the Gravitino server. Match versions or keep the connector version lower.
- **Mount Location**: Mount JARs at `/opt/flink/usrlib` (not `/opt/flink/lib`) on the Flink main container.
- **Extra JARs**: JDBC catalogs need the Flink JDBC connector (`flink-connector-jdbc`) and database drivers on the same classpath.
- **PostgreSQL Catalogs**: Set `jdbc-database` and `flink.bypass.default-database` so `USE CATALOG` has a default database.

## Installation

### Kubernetes

```yaml
volumes:
  - name: flink-jars
    emptyDir: {}
initContainers:
  - name: copy-gravitino-jars
    image: datastrato/enterprise-flink-connector:{version}
    env:
      - name: FLINK_VERSION
        value: "1.20"
    volumeMounts:
      - name: flink-jars
        mountPath: /target
containers:
  - name: flink-main-container
    volumeMounts:
      - name: flink-jars
        mountPath: /opt/flink/usrlib
```

To update, redeploy the `FlinkDeployment` so the init container runs again.

### VM / On-Premises

```bash
docker run --rm \
  -e FLINK_VERSION=1.20 \
  -v /opt/flink/lib:/target \
  datastrato/enterprise-flink-connector:{version}
```

Then restart JobManager and TaskManager.

## Configuration

Set these in `flink-conf.yaml`, `FlinkDeployment.spec.flinkConfiguration`, or `TableEnvironment`.

### Basic Authentication

```yaml
table.catalog-store.kind: gravitino
table.catalog-store.gravitino.gravitino.uri: http://gravitino.example.svc.cluster.local:8090
table.catalog-store.gravitino.gravitino.metalake: test
table.catalog-store.gravitino.gravitino.client.auth.type: basic
table.catalog-store.gravitino.gravitino.client.basic.username: admin
table.catalog-store.gravitino.gravitino.client.basic.password: {password}
```

### OAuth2 Authentication

```yaml
table.catalog-store.kind: gravitino
table.catalog-store.gravitino.gravitino.uri: http://gravitino.example.svc.cluster.local:8090
table.catalog-store.gravitino.gravitino.metalake: test
table.catalog-store.gravitino.gravitino.client.auth.type: oauth2
table.catalog-store.gravitino.gravitino.client.oauth2.serverUri: https://login.microsoftonline.com
table.catalog-store.gravitino.gravitino.client.oauth2.tokenPath: {tenant_id}/oauth2/v2.0/token
table.catalog-store.gravitino.gravitino.client.oauth2.credential: {client_id}:{client_secret}
table.catalog-store.gravitino.gravitino.client.oauth2.scope: {client_id}/.default
```

## Catalog Names

Use metalake catalog names directly, e.g., `catalog_postgres`. Names must not start with a digit.

```sql
SHOW CATALOGS;
USE CATALOG catalog_postgres;
SHOW DATABASES;
SHOW TABLES FROM `public`;
SELECT * FROM catalog_postgres.`public`.my_table LIMIT 20;
```

**Note**: Session-only catalogs (`enableSessionCatalogSupport=true`) do not survive a redeploy.

## Additional Resources

For detailed information on:
- Supported versions and directories
- Properties reference
- Extra JARs (JDBC connectors and drivers)
- Troubleshooting

Please refer to the [complete documentation](../../../docs-enterprise/flink-connector/flink-connector-enterprise.md).
