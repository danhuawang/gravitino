# Datastrato Enterprise Trino Connector

> **📖 Full Documentation**: See [Trino Connector Enterprise Documentation](../../../docs-enterprise/trino-connector/trino-connector-enterprise.md) for complete installation, configuration, and usage instructions.

## Overview

Image: `datastrato/enterprise-trino-connector:{version}`

This image packages the open source Apache Gravitino Trino connector with Datastrato Enterprise adapters (Oracle, SQL Server, etc.). Use it as a Kubernetes init container or unpack it on a VM to install the connector plugin.

**Supported Trino/Starburst versions**: 435–478

## ⚠️ Important Notes

- **Version Compatibility**: The connector must not be newer than the Gravitino server. Match versions or keep the connector version lower to avoid silent failures.
- **JDBC Drivers**: This image ships NO JDBC drivers. Gravitino JDBC catalogs (MySQL, PostgreSQL) use Trino's own connectors. Ensure `plugin/mysql` or `plugin/postgresql` exist in your Trino installation.
- **Logging**: The connector emits no logs unless the coordinator JVM is started with `-Dlog4j.configurationFile=/etc/trino/log4j2.properties`.

## Installation

### Kubernetes

Mount an empty volume at `/target` in the init container, and at the plugin path on the coordinator and every worker. The last path component must be `gravitino`.

```yaml
volumes:
  - name: gravitino-plugin
    emptyDir: {}
initContainers:
  - name: install-gravitino-connector
    image: datastrato/enterprise-trino-connector:{version}
    env:
      - name: TRINO_VERSION
        value: "477"
    volumeMounts:
      - name: gravitino-plugin
        mountPath: /target
containers:
  - name: trino
    volumeMounts:
      - name: gravitino-plugin
        mountPath: /usr/lib/trino/plugin/gravitino
```

### VM / On-Premises

```bash
docker run --rm \
  -e TRINO_VERSION=477 \
  -v /usr/lib/trino/plugin/gravitino:/target \
  datastrato/enterprise-trino-connector:{version}
```

Repeat on coordinator and all workers, then restart Trino.

### Trino Server Settings

On the coordinator:

```properties
catalog.management=dynamic
```

## Configuration

Create `/etc/trino/catalog/gravitino.properties`. On Helm charts, set these under `catalogs.gravitino`.

### Basic Authentication

```properties
connector.name=gravitino
gravitino.uri=http://gravitino.example.svc.cluster.local:8090
gravitino.metalake=test
gravitino.client.authType=basic
gravitino.client.basic.username=admin
gravitino.client.basic.password={password}
```

### OAuth2 Authentication

```properties
connector.name=gravitino
gravitino.uri=http://gravitino.example.svc.cluster.local:8090
gravitino.metalake=test
gravitino.client.authType=oauth2
gravitino.client.oauth2.serverUri=https://login.microsoftonline.com
gravitino.client.oauth2.credential={client_id}:{client_secret}
gravitino.client.oauth2.path={tenant_id}/oauth2/v2.0/token
gravitino.client.oauth2.scope={client_id}/.default
```

### Worker Credentials (Distributed Clusters)

Secret values are stripped from worker catalog definitions. Reference secrets by environment variable:

```properties
gravitino.dynamic-catalog.environment-variable.gravitino.client.oauth2.credential=GRAVITINO_CLIENT_CREDENTIAL
gravitino.dynamic-catalog.environment-variable.gravitino.iceberg.rest-catalog.oauth2.credential=IRC_CLIENT_CREDENTIAL
```

Set the matching env vars on coordinator and all workers (e.g., via Kubernetes `envFrom` with a `Secret`).

### TLS-Enabled Coordinator

```properties
trino.jdbc.user=admin
trino.jdbc.password={trino_password}
trino.jdbc.ssl.truststore.path=/etc/trino/truststore.jks
trino.jdbc.ssl.truststore.password={truststore_password}
```

## Catalog Names

- `gravitino.use-single-metalake=true` (default): catalogs appear as `<catalog_name>`, e.g. `hive`
- `gravitino.use-single-metalake=false`: catalogs appear as `<metalake_name>.<catalog_name>`, e.g. `test.hive`

```sql
SHOW CATALOGS;
```

## Additional Resources

For detailed information on:
- Supported versions and band directories
- Iceberg REST routing configuration
- Identity forwarding and per-user authorization
- Complete properties reference
- Troubleshooting

Please refer to the [complete documentation](../../../docs-enterprise/trino-connector/trino-connector-enterprise.md).
