# Datastrato Enterprise Spark Connector

> **📖 Full Documentation**: See [Spark Connector Enterprise Documentation](../../../docs-enterprise/spark-connector/spark-connector-enterprise.md) for complete installation, configuration, and usage instructions.

## Overview

Image: `datastrato/enterprise-spark-connector:{version}`

This image packages the open source Apache Gravitino Spark connector with Datastrato Enterprise adapters (Oracle, SQL Server, etc.). Use it as a Kubernetes init container or unpack it on a VM to install the connector JARs.

**Supported Spark versions**: 3.3, 3.4, 3.5 (Scala 2.12, 2.13)

## ⚠️ Important Notes

- **Version Compatibility**: The connector must not be newer than the Gravitino server. Match versions or keep the connector version lower.
- **Classpath Location**: Mount JARs **outside** `/opt/spark/jars` (e.g., `/opt/gravitino/jars`) to avoid conflicts. Use `spark.driver/executor.extraClassPath`.
- **Plugin Append**: If `spark.plugins` is already set, append `GravitinoSparkPlugin` (comma-separated) rather than replacing it.
- **Extra JARs**: JDBC catalogs need database drivers. Iceberg catalogs need `iceberg-spark-runtime` and `iceberg-aws-bundle` (for S3).

## Installation

### Kubernetes

```yaml
volumes:
  - name: spark-jars
    emptyDir: {}
driver:
  volumeMounts:
    - name: spark-jars
      mountPath: /opt/gravitino/jars
  initContainers:
    - name: copy-gravitino-jars
      image: datastrato/enterprise-spark-connector:{version}
      env:
        - name: SPARK_VERSION
          value: "3.5"
        - name: SCALA_VERSION
          value: "2.12"
      volumeMounts:
        - name: spark-jars
          mountPath: /target
# Repeat volumeMount and initContainer on executor
sparkConf:
  "spark.plugins": "org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin"
  "spark.driver.extraClassPath": "/opt/gravitino/jars/*"
  "spark.executor.extraClassPath": "/opt/gravitino/jars/*"
  "spark.sql.gravitino.uri": "http://gravitino.example.svc.cluster.local:8090"
  "spark.sql.gravitino.metalake": "test"
  "spark.sql.gravitino.authType": "basic"
  "spark.sql.gravitino.basic.username": "admin"
  "spark.sql.gravitino.basic.password": "{password}"
```

### VM / On-Premises

```bash
docker run --rm \
  -e SPARK_VERSION=3.5 -e SCALA_VERSION=2.12 \
  -v /opt/gravitino/jars:/target \
  datastrato/enterprise-spark-connector:{version}
```

Then use `--jars` or set `spark.driver.extraClassPath` and `spark.executor.extraClassPath`.

## Configuration

### Basic Authentication

```properties
spark.plugins=org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin
spark.sql.gravitino.uri=http://gravitino.example.svc.cluster.local:8090
spark.sql.gravitino.metalake=test
spark.sql.gravitino.authType=basic
spark.sql.gravitino.basic.username=admin
spark.sql.gravitino.basic.password={password}
```

### OAuth2 Authentication

```properties
spark.plugins=org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin
spark.sql.gravitino.uri=http://gravitino.example.svc.cluster.local:8090
spark.sql.gravitino.metalake=test
spark.sql.gravitino.authType=oauth2
spark.sql.gravitino.oauth2.serverUri=https://login.microsoftonline.com
spark.sql.gravitino.oauth2.tokenPath={tenant_id}/oauth2/v2.0/token
spark.sql.gravitino.oauth2.credential={client_id}:{client_secret}
spark.sql.gravitino.oauth2.scope={client_id}/.default
```

### Iceberg Support

For Iceberg catalogs, add:

```properties
spark.sql.gravitino.enableIcebergSupport=true
```

And place these JARs on the classpath:
- `iceberg-spark-runtime-{spark_version}_{scala_version}-{iceberg_version}.jar`
- `iceberg-aws-bundle-{iceberg_version}.jar` (if using S3)

Iceberg REST routing is enabled by default (`spark.sql.gravitino.iceberg.rest-routing-enabled=true`).

## Catalog Names

Use metalake catalog names directly, e.g., `catalog_postgres`. Fully qualified names are recommended:

```sql
SHOW DATABASES IN catalog_postgres;
SHOW TABLES IN catalog_postgres.public;
SELECT * FROM catalog_postgres.public.my_table LIMIT 20;
```

## Additional Resources

For detailed information on:
- Supported versions and directories
- Iceberg REST routing configuration
- Properties reference
- Troubleshooting

Please refer to the [complete documentation](../../../docs-enterprise/spark-connector/spark-connector-enterprise.md).
