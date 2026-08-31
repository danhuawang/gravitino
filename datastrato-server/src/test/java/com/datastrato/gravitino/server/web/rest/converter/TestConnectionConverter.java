/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datastrato.gravitino.dto.ConnectionDTO;
import com.datastrato.gravitino.dto.ConnectionOverviewDTO;
import com.datastrato.gravitino.dto.ConnectionTestStatusDTO;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.CatalogDTO;
import org.junit.jupiter.api.Test;

public class TestConnectionConverter {

  @Test
  void testToConnectionOverviewDTOUsesWhitelistedCatalogFields() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(
        "jdbc-url",
        "jdbc:mysql://url-user:url-secret@mysql.example.com:3306/sales"
            + "?password=query-secret&accessToken=query-token");
    properties.put(Catalog.CLOUD_NAME, "aws");
    properties.put(Catalog.CLOUD_REGION_CODE, "us-east-1");
    properties.put("warehouse", "s3a://warehouse-user:warehouse-secret@bucket/path");
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName("mysql_prod")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("jdbc-mysql")
            .withProperties(properties)
            .withAudit(AuditDTO.builder().build())
            .build();
    ConnectionTestStatusDTO status =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);

    ConnectionOverviewDTO overview = ConnectionConverter.toConnectionOverviewDTO(catalog, status);

    assertEquals("jdbc:mysql://mysql.example.com:3306/sales", overview.getEndpoint());
    assertEquals("mysql_prod", overview.getName());
    assertEquals(Catalog.Type.RELATIONAL, overview.getType());
    assertEquals("jdbc-mysql", overview.getProvider());
    assertEquals("aws", overview.getCloudName());
    assertEquals("us-east-1", overview.getCloudRegionCode());
    assertEquals(
        "jdbc:mysql://url-user:url-secret@mysql.example.com:3306/sales"
            + "?password=query-secret&accessToken=query-token",
        catalog.properties().get("jdbc-url"));
  }

  @Test
  void testToConnectionOverviewDTOSanitizesNestedJdbcEndpointProperties() {
    ConnectionTestStatusDTO status =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);
    CatalogDTO iceberg =
        CatalogDTO.builder()
            .withName("iceberg_jdbc")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withProperties(
                ImmutableMap.of(
                    "catalog-backend",
                    "jdbc",
                    "uri",
                    "jdbc:postgresql://user:secret@iceberg.example.com:5432/catalog"
                        + "?password=query-secret"))
            .withAudit(AuditDTO.builder().build())
            .build();
    CatalogDTO paimon =
        CatalogDTO.builder()
            .withName("paimon_jdbc")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-paimon")
            .withProperties(
                ImmutableMap.of(
                    "catalog-backend",
                    "jdbc",
                    "uri",
                    "jdbc:mysql://user:secret@paimon.example.com:3306/catalog"
                        + "?password=query-secret"))
            .withAudit(AuditDTO.builder().build())
            .build();

    ConnectionOverviewDTO icebergOverview =
        ConnectionConverter.toConnectionOverviewDTO(iceberg, status);
    ConnectionOverviewDTO paimonOverview =
        ConnectionConverter.toConnectionOverviewDTO(paimon, status);

    assertEquals(
        "jdbc:postgresql://iceberg.example.com:5432/catalog", icebergOverview.getEndpoint());
    assertEquals("jdbc:mysql://paimon.example.com:3306/catalog", paimonOverview.getEndpoint());
  }

  @Test
  void testToConnectionOverviewDTOSanitizesNonJdbcEndpointProperties() {
    ConnectionTestStatusDTO status =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);
    CatalogDTO iceberg =
        CatalogDTO.builder()
            .withName("iceberg_rest")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withProperties(
                ImmutableMap.of(
                    "catalog-backend",
                    "rest",
                    "uri",
                    "https://rest-user:rest-secret@iceberg.example.com/v1/catalog"
                        + "?token=query-secret#fragment"))
            .withAudit(AuditDTO.builder().build())
            .build();
    CatalogDTO hive =
        CatalogDTO.builder()
            .withName("hive")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withProperties(
                ImmutableMap.of(
                    "metastore.uris",
                    "thrift://hive-user:hive-secret@hive-1.example.com:9083"
                        + "?token=query-secret,"
                        + "thrift://hive-2.example.com:9083/catalog#fragment"))
            .withAudit(AuditDTO.builder().build())
            .build();

    ConnectionOverviewDTO icebergOverview =
        ConnectionConverter.toConnectionOverviewDTO(iceberg, status);
    ConnectionOverviewDTO hiveOverview = ConnectionConverter.toConnectionOverviewDTO(hive, status);

    String expectedIcebergEndpoint = "https://iceberg.example.com/v1/catalog";
    String expectedHiveEndpoint =
        "thrift://hive-1.example.com:9083,thrift://hive-2.example.com:9083/catalog";
    assertEquals(expectedIcebergEndpoint, icebergOverview.getEndpoint());
    assertEquals(expectedHiveEndpoint, hiveOverview.getEndpoint());
    assertEquals(
        expectedIcebergEndpoint, ConnectionConverter.toConnectionDTO(iceberg, null).getEndpoint());
    assertEquals(
        expectedHiveEndpoint, ConnectionConverter.toConnectionDTO(hive, null).getEndpoint());
  }

  @Test
  void testToConnectionOverviewDTOUsesFallbackForUnsafeNonJdbcEndpoint() {
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName("invalid_iceberg_rest")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withProperties(
                ImmutableMap.of("catalog-backend", "rest", "uri", "not-a-uri?token=query-secret"))
            .withAudit(AuditDTO.builder().build())
            .build();
    ConnectionTestStatusDTO status =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);

    ConnectionOverviewDTO overview = ConnectionConverter.toConnectionOverviewDTO(catalog, status);

    assertEquals("--", overview.getEndpoint());
  }

  @Test
  void testToConnectionOverviewDTOUsesFallbackForUnsafeUnparseableJdbcUrl() {
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName("invalid_jdbc")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("jdbc-mysql")
            .withProperties(ImmutableMap.of("jdbc-url", "not-a-jdbc-url-with-secret"))
            .withAudit(AuditDTO.builder().build())
            .build();
    ConnectionTestStatusDTO status =
        new ConnectionTestStatusDTO(true, ConnectionTestStatusDTO.NOT_TESTED, null, null);

    ConnectionOverviewDTO overview = ConnectionConverter.toConnectionOverviewDTO(catalog, status);

    assertEquals("--", overview.getEndpoint());
  }

  @Test
  public void testResolveDisplayType() {
    assertEquals(
        "Iceberg REST",
        ConnectionConverter.resolveDisplayType(
            "lakehouse-iceberg", ImmutableMap.of("catalog-backend", "rest")));
    assertEquals(
        "Iceberg Hive",
        ConnectionConverter.resolveDisplayType(
            "lakehouse-iceberg", ImmutableMap.of("catalog-backend", "hive")));
    assertEquals(
        "Iceberg JDBC",
        ConnectionConverter.resolveDisplayType(
            "lakehouse-iceberg", ImmutableMap.of("catalog-backend", "jdbc")));
    assertEquals(
        "Iceberg Glue",
        ConnectionConverter.resolveDisplayType(
            "lakehouse-iceberg", ImmutableMap.of("catalog-backend", "glue")));
    assertEquals(
        "Iceberg REST",
        ConnectionConverter.resolveDisplayType("lakehouse-iceberg", Collections.emptyMap()));

    assertEquals("Hive", ConnectionConverter.resolveDisplayType("hive", Collections.emptyMap()));
    assertEquals(
        "MySQL", ConnectionConverter.resolveDisplayType("jdbc-mysql", Collections.emptyMap()));
    assertEquals(
        "PostgreSQL",
        ConnectionConverter.resolveDisplayType("jdbc-postgresql", Collections.emptyMap()));
    assertEquals(
        "Paimon",
        ConnectionConverter.resolveDisplayType("lakehouse-paimon", Collections.emptyMap()));
    assertEquals(
        "Fileset", ConnectionConverter.resolveDisplayType("fileset", Collections.emptyMap()));
    assertEquals(
        "Fileset", ConnectionConverter.resolveDisplayType("hadoop", Collections.emptyMap()));
    assertEquals("Kafka", ConnectionConverter.resolveDisplayType("kafka", Collections.emptyMap()));

    // CLI aliases are canonicalized before reaching the server converter
    assertEquals(
        "Postgres", ConnectionConverter.resolveDisplayType("postgres", Collections.emptyMap()));

    // Title Case formatting for unknown providers
    assertEquals(
        "Lakehouse Hudi",
        ConnectionConverter.resolveDisplayType("lakehouse-hudi", Collections.emptyMap()));
    assertEquals(
        "Custom Iceberg Provider",
        ConnectionConverter.resolveDisplayType(
            "custom-iceberg-provider", ImmutableMap.of("catalog-backend", "rest")));
    assertEquals(
        "Custom Source",
        ConnectionConverter.resolveDisplayType("custom_source", Collections.emptyMap()));
    assertEquals("--", ConnectionConverter.resolveDisplayType(null, Collections.emptyMap()));
  }

  @Test
  public void testResolveEndpoint() {
    assertEquals(
        "jdbc:mysql://localhost:3306/db",
        ConnectionConverter.resolveEndpoint(
            "jdbc-mysql", ImmutableMap.of("jdbc-url", "jdbc:mysql://localhost:3306/db")));
    assertEquals(
        "jdbc:mysql://mysql.example.com:3306/SalesDb",
        ConnectionConverter.resolveEndpoint(
            "jdbc-mysql",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:mysql://app-user:ui-secret@mysql.example.com:3306/SalesDb"
                    + "?user=query-user&password=query-secret&accessToken=query-token")));
    assertEquals(
        "jdbc:sqlserver://sqlserver.example.com:1433",
        ConnectionConverter.resolveEndpoint(
            "jdbc-sqlserver",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:sqlserver://sql-user:ui-secret@sqlserver.example.com:1433"
                    + ";databaseName=SalesDb;user=property-user;password=property-secret;"
                    + "accessToken=property-token")));
    assertEquals(
        "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443",
        ConnectionConverter.resolveEndpoint(
            "jdbc-bigquery",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:bigquery://https://bq-user:ui-secret@www.googleapis.com/bigquery/v2:443;"
                    + "ProjectId=SalesProject;OAuthPvtKeyPath=/tmp/key.json;ProxyUid=proxy-user;"
                    + "ProxyPwd=proxy-secret;OAuthAccessToken=oauth-token",
                "jdbc-driver",
                "com.simba.googlebigquery.jdbc42.Driver")));
    assertEquals(
        "jdbc:oracle:thin:@oracle.example.com:1521:SALES",
        ConnectionConverter.resolveEndpoint(
            "jdbc-oracle",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:oracle:thin:oracle-user/oracle-secret@oracle.example.com:1521:SALES")));
    assertEquals(
        "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)"
            + "(HOST=oracle-primary.example.com)(PORT=1521))"
            + "(ADDRESS=(PROTOCOL=TCPS)(HOST=oracle-secondary.example.com)(PORT=1522))"
            + "(CONNECT_DATA=(SERVICE_NAME=Sales.EXAMPLE.COM)))",
        ConnectionConverter.resolveEndpoint(
            "jdbc-oracle",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:oracle:thin:oracle-user/oracle-secret@"
                    + "(DESCRIPTION=(ADDRESS_LIST="
                    + "(ADDRESS=(PROTOCOL=TCP)(HOST=Oracle-Primary.example.com)(PORT=1521))"
                    + "(ADDRESS=(PROTOCOL=TCPS)(HOST=Oracle-Secondary.example.com)(PORT=1522)))"
                    + "(CONNECT_DATA=(SERVICE_NAME=Sales.EXAMPLE.COM))"
                    + "(SECURITY=(MY_WALLET_DIRECTORY=/secret/wallet)))")));
    assertEquals(
        "--",
        ConnectionConverter.resolveEndpoint(
            "jdbc-oracle",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:oracle:thin:user/secret@"
                    + "(DESCRIPTION=(ADDRESS=(HOST=oracle.example.com)(PORT=not-a-port))"
                    + "(CONNECT_DATA=(SERVICE_NAME=SALES)))")));
    assertEquals(
        "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=oracle.example.com)(PORT=1521))"
            + "(CONNECT_DATA=(SID=ORCL)))",
        ConnectionConverter.resolveEndpoint(
            "jdbc-oracle",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=oracle.example.com)(PORT=1521))"
                    + "(CONNECT_DATA=(SID=ORCL)))")));
    assertEquals(
        "--",
        ConnectionConverter.resolveEndpoint(
            "jdbc-mysql", ImmutableMap.of("jdbc-url", "secret-without-a-jdbc-url")));
    assertEquals(
        "thrift://hive:9083",
        ConnectionConverter.resolveEndpoint(
            "hive", ImmutableMap.of("metastore.uris", "thrift://hive:9083")));
    assertEquals(
        "https://iceberg.server/iceberg/",
        ConnectionConverter.resolveEndpoint(
            "lakehouse-iceberg", ImmutableMap.of("uri", "https://iceberg.server/iceberg/")));
    assertEquals(
        "jdbc:postgresql://iceberg-db.example.com:5432/IcebergMeta",
        ConnectionConverter.resolveEndpoint(
            "lakehouse-iceberg",
            ImmutableMap.of(
                "catalog-backend",
                "jdbc",
                "uri",
                "jdbc:postgresql://iceberg-user:iceberg-secret@iceberg-db.example.com:5432/"
                    + "IcebergMeta?password=query-secret")));
    assertEquals(
        "s3://my-bucket/warehouse",
        ConnectionConverter.resolveEndpoint(
            "lakehouse-paimon",
            ImmutableMap.of(
                "catalog-backend", "filesystem", "warehouse", "s3://my-bucket/warehouse")));
    assertEquals(
        "jdbc:mysql://paimon-db.example.com:3306/PaimonMeta",
        ConnectionConverter.resolveEndpoint(
            "lakehouse-paimon",
            ImmutableMap.of(
                "catalog-backend",
                "jdbc",
                "uri",
                "jdbc:mysql://paimon-db.example.com:3306/PaimonMeta;password=property-secret")));
    assertEquals(
        "http://localhost:4566",
        ConnectionConverter.resolveEndpoint(
            "glue", ImmutableMap.of("aws-glue-endpoint", "http://localhost:4566")));
    assertEquals(
        "s3://my-bucket/data",
        ConnectionConverter.resolveEndpoint(
            "fileset", ImmutableMap.of("location", "s3://my-bucket/data")));
    assertEquals(
        "kafka1:9092,kafka2:9092",
        ConnectionConverter.resolveEndpoint(
            "kafka", ImmutableMap.of("bootstrap.servers", "kafka1:9092,kafka2:9092")));
    assertEquals(
        "[::1]:9092,[2001:db8::1]:9093",
        ConnectionConverter.resolveEndpoint(
            "kafka", ImmutableMap.of("bootstrap.servers", "[::1]:9092,[2001:db8::1]:9093")));
    assertEquals(
        "glue.us-east-1.amazonaws.com",
        ConnectionConverter.resolveEndpoint("glue", ImmutableMap.of("aws-region", "us-east-1")));

    assertEquals(
        "kafka1:9092",
        ConnectionConverter.resolveEndpoint(
            "kafka",
            ImmutableMap.of("location", "s3://unrelated", "bootstrap.servers", "kafka1:9092")));
    assertEquals(
        "thrift://hive:9083",
        ConnectionConverter.resolveEndpoint(
            "hive",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:mysql://unrelated:3306/db",
                "metastore.uris",
                "thrift://hive:9083")));
    assertEquals(
        "jdbc:mysql://localhost:3306/db",
        ConnectionConverter.resolveEndpoint(
            "jdbc-mysql",
            ImmutableMap.of(
                "jdbc-url", "jdbc:mysql://localhost:3306/db", "location", "s3://unrelated")));

    assertEquals(
        "--",
        ConnectionConverter.resolveEndpoint("unknown-provider", ImmutableMap.of("jdbc-url", "x")));
    assertEquals("--", ConnectionConverter.resolveEndpoint("hive", Collections.emptyMap()));
    assertEquals("--", ConnectionConverter.resolveEndpoint("hive", null));
    assertEquals(
        "--", ConnectionConverter.resolveEndpoint(null, ImmutableMap.of("metastore.uris", "x")));
  }

  @Test
  public void testResolveEndpointAcceptsUnderscoreHostnamesWithoutSecrets() {
    String hiveEndpoint =
        ConnectionConverter.resolveEndpoint(
            "hive",
            ImmutableMap.of(
                "metastore.uris",
                "thrift://hive-user:hive-secret@hive_metastore:9083/catalog"
                    + ";password=path-secret"
                    + "?token=query-secret#fragment-secret"));
    String kafkaEndpoint =
        ConnectionConverter.resolveEndpoint(
            "kafka",
            ImmutableMap.of(
                "bootstrap.servers",
                "kafka-user:kafka-secret@kafka_broker_1:9092,kafka_broker_2:9093"));
    String hudiEndpoint =
        ConnectionConverter.resolveEndpoint(
            "lakehouse-hudi",
            ImmutableMap.of(
                "uri",
                "https://hudi-user:hudi-secret@hudi_service:8080/catalog"
                    + ";password=path-secret/v1;token=segment-secret"
                    + "?token=query-secret#fragment-secret"));
    String paimonEndpoint =
        ConnectionConverter.resolveEndpoint(
            "lakehouse-paimon",
            ImmutableMap.of(
                "catalog-backend",
                "hive",
                "uri",
                "thrift://paimon-user:paimon-secret@paimon_metastore:9083"));

    assertEquals("thrift://hive_metastore:9083/catalog", hiveEndpoint);
    assertEquals("kafka_broker_1:9092,kafka_broker_2:9093", kafkaEndpoint);
    assertEquals("https://hudi_service:8080/catalog/v1", hudiEndpoint);
    assertEquals("thrift://paimon_metastore:9083", paimonEndpoint);
    assertEquals(
        "kafka-broker:65536",
        ConnectionConverter.resolveEndpoint(
            "kafka", ImmutableMap.of("bootstrap.servers", "kafka-broker:65536")));
    assertEquals(
        "kafka_broker:65536",
        ConnectionConverter.resolveEndpoint(
            "kafka", ImmutableMap.of("bootstrap.servers", "kafka_broker:65536")));
    for (String endpoint :
        new String[] {hiveEndpoint, kafkaEndpoint, hudiEndpoint, paimonEndpoint}) {
      assertFalse(endpoint.contains("user"));
      assertFalse(endpoint.contains("secret"));
      assertFalse(endpoint.contains("token"));
    }

    assertEquals(
        "--",
        ConnectionConverter.resolveEndpoint(
            "kafka",
            ImmutableMap.of("bootstrap.servers", "kafka_broker_1:9092;password=authority-secret")));
  }

  @Test
  public void testResolveEndpointAcceptsSchemeLessFilesystemLocations() {
    assertEquals(
        "/data/warehouse",
        ConnectionConverter.resolveEndpoint(
            "fileset", ImmutableMap.of("location", "/data/warehouse")));
    assertEquals(
        "/data/warehouse",
        ConnectionConverter.resolveEndpoint(
            "hadoop", ImmutableMap.of("location", "/data/warehouse")));
    assertEquals(
        "/data/warehouse",
        ConnectionConverter.resolveEndpoint(
            "lakehouse-generic", ImmutableMap.of("location", "/data/warehouse")));
    assertEquals(
        "relative/warehouse",
        ConnectionConverter.resolveEndpoint(
            "lakehouse-paimon",
            ImmutableMap.of("catalog-backend", "filesystem", "warehouse", "relative/warehouse")));

    assertEquals(
        "--",
        ConnectionConverter.resolveEndpoint(
            "fileset", ImmutableMap.of("location", "//user:secret@host/data")));
    assertEquals(
        "--",
        ConnectionConverter.resolveEndpoint(
            "fileset", ImmutableMap.of("location", "/data/warehouse?token=secret")));
  }

  @Test
  public void testResolveCredential() {
    // Explicit credential providers
    assertEquals(
        "s3-token",
        ConnectionConverter.resolveCredential(ImmutableMap.of("credential-providers", "s3-token")));
    assertEquals(
        "s3-secret-key",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", "s3-secret-key,jdbc-user-password")));
    assertEquals(
        "adls-token",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", "adls-token")));
    assertEquals(
        "azure-account-key",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", "azure-account-key")));
    assertEquals(
        "oss-token",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", "oss-token")));
    assertEquals(
        "oss-secret-key",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", "oss-secret-key")));
    assertEquals(
        "gcs-token",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", "gcs-token")));
    // Explicit credential providers with whitespace and leading/trailing commas
    assertEquals(
        "s3-token",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", " , s3-token, jdbc-user-password")));
    assertEquals(
        "s3-token",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("credential-providers", ",s3-token")));

    // Kerberos
    assertEquals(
        "kerberos-keytab",
        ConnectionConverter.resolveCredential(ImmutableMap.of("authentication.type", "Kerberos")));
    assertEquals(
        "kerberos-keytab",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("authentication.kerberos.principal", "hive@ACME.COM")));
    assertEquals(
        "kerberos-keytab",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("kerberos.principal", "hive@ACME.COM")));
    assertEquals(
        "kerberos-keytab",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("gravitino.bypass.hive.metastore.sasl.enabled", "true")));
    assertEquals(
        "--",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("hive.metastore.sasl.enabled", "true")));

    // Authentication modes are not credential vending types
    assertEquals(
        "--", ConnectionConverter.resolveCredential(ImmutableMap.of("auth-type", "oauth2")));
    assertEquals(
        "--", ConnectionConverter.resolveCredential(ImmutableMap.of("token", "token-123")));

    // Inferred credentials (per docs/security/credential-vending.md)
    assertEquals(
        "s3-secret-key",
        ConnectionConverter.resolveCredential(ImmutableMap.of("s3-access-key-id", "AKIA123")));
    assertEquals(
        "oss-secret-key",
        ConnectionConverter.resolveCredential(ImmutableMap.of("oss-access-key-id", "OSS123")));
    assertEquals(
        "azure-account-key",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("azure-storage-account-key", "key-123")));
    assertEquals(
        "gcs-token",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("gcs-service-account-file", "/path/to/sa.json")));
    assertEquals(
        "jdbc-user-password",
        ConnectionConverter.resolveCredential(ImmutableMap.of("jdbc-user", "root")));

    // Token-based providers without credential-providers must not be inferred
    assertEquals(
        "--",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("s3-role-arn", "arn:aws:iam::123:role/read")));
    assertEquals(
        "--",
        ConnectionConverter.resolveCredential(
            ImmutableMap.of("oss-role-arn", "acs:ram::123:role/read")));
    assertEquals(
        "--",
        ConnectionConverter.resolveCredential(ImmutableMap.of("azure-tenant-id", "tenant-123")));

    // Default
    assertEquals("--", ConnectionConverter.resolveCredential(Collections.emptyMap()));
    assertEquals("--", ConnectionConverter.resolveCredential(null));
  }

  @Test
  public void testToConnectionDTO() {
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName("sales_catalog")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withProperties(
                ImmutableMap.of(
                    "catalog-backend",
                    "rest",
                    "uri",
                    "https://irc.acme.internal/iceberg/",
                    "credential-providers",
                    "s3-token"))
            .withAudit(AuditDTO.builder().build())
            .build();

    ConnectionDTO connection = ConnectionConverter.toConnectionDTO(catalog, 4L);
    assertNotNull(connection);
    assertEquals("sales_catalog", connection.getName());
    assertEquals("Iceberg REST", connection.getType());
    assertEquals("https://irc.acme.internal/iceberg/", connection.getEndpoint());
    assertEquals("s3-token", connection.getCredential());
    assertEquals(4L, connection.getSchemaCount());

    assertNull(ConnectionConverter.toConnectionDTO(null, 0L));
  }

  @Test
  public void testToConnectionDTOsNullOrMissingSchemaCounts() {
    CatalogDTO catalog1 =
        CatalogDTO.builder()
            .withName("cat1")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("lakehouse-iceberg")
            .withAudit(AuditDTO.builder().build())
            .build();
    CatalogDTO catalog2 =
        CatalogDTO.builder()
            .withName("cat2")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("hive")
            .withAudit(AuditDTO.builder().build())
            .build();

    // Map has an entry for cat1 but is missing cat2 after its schema listing failed.
    Map<String, Long> partialSchemaCounts = ImmutableMap.of("cat1", 5L);
    ConnectionDTO[] dtos =
        ConnectionConverter.toConnectionDTOs(
            new Catalog[] {catalog1, catalog2}, partialSchemaCounts);
    assertEquals(2, dtos.length);
    assertEquals(5L, dtos[0].getSchemaCount());
    assertNull(dtos[1].getSchemaCount());

    // Null schemaCounts map
    ConnectionDTO[] nullMapDtos =
        ConnectionConverter.toConnectionDTOs(new Catalog[] {catalog1}, null);
    assertEquals(1, nullMapDtos.length);
    assertNull(nullMapDtos[0].getSchemaCount());

    ConnectionDTO[] credentialDtos =
        ConnectionConverter.toConnectionDTOs(
            new Catalog[] {catalog1},
            partialSchemaCounts,
            ImmutableMap.of("cat1", "jdbc-user-password,s3-secret-key"));
    assertEquals("jdbc-user-password", credentialDtos[0].getCredential());
  }

  @Test
  public void testCalculateSystemCount() {
    ConnectionDTO conn1 =
        new ConnectionDTO(
            "sales_catalog", "Iceberg REST", "https://irc.acme.internal/iceberg/", "s3-token", 4L);
    ConnectionDTO conn2 =
        new ConnectionDTO(
            "events", "Iceberg REST", "https://irc.acme.internal/iceberg", "s3-token", 2L);
    ConnectionDTO conn3 =
        new ConnectionDTO("hive_legacy", "Hive", "thrift://hive:9083", "kerberos-keytab", 9L);
    ConnectionDTO conn4 = new ConnectionDTO("unknown_1", "MySQL", "--", "--", 1L);
    ConnectionDTO conn5 = new ConnectionDTO("unknown_2", "PostgreSQL", "--", "--", 1L);

    ConnectionDTO[] connections = new ConnectionDTO[] {conn1, conn2, conn3, conn4, conn5};
    // conn1 (with slash) and conn2 (without slash) normalize to same endpoint (1) + conn3 endpoint
    // (1) + conn4 unknown (1) + conn5
    // unknown (1) = 4
    assertEquals(4, ConnectionConverter.calculateSystemCount(connections));

    assertEquals(0, ConnectionConverter.calculateSystemCount(new ConnectionDTO[0]));
    assertEquals(0, ConnectionConverter.calculateSystemCount(null));
  }

  @Test
  public void testCalculateSystemCountPreservesCaseSensitivePathAndQuery() {
    ConnectionDTO normalized1 =
        new ConnectionDTO(
            "normalized_1",
            "Iceberg REST",
            "HTTPS://IRC.ACME.INTERNAL/Case/Path/?VersionId=ABC",
            "s3-token",
            1L);
    ConnectionDTO normalized2 =
        new ConnectionDTO(
            "normalized_2",
            "Iceberg REST",
            "https://irc.acme.internal/Case/Path?VersionId=ABC",
            "s3-token",
            1L);
    ConnectionDTO differentPath =
        new ConnectionDTO(
            "different_path",
            "Iceberg REST",
            "https://irc.acme.internal/case/Path?VersionId=ABC",
            "s3-token",
            1L);
    ConnectionDTO differentQuery =
        new ConnectionDTO(
            "different_query",
            "Iceberg REST",
            "https://irc.acme.internal/Case/Path?versionId=abc",
            "s3-token",
            1L);
    ConnectionDTO objectStoragePrefix =
        new ConnectionDTO(
            "object_storage", "Fileset", "s3://DATA-BUCKET/Production/Events/", "s3-token", 1L);
    ConnectionDTO differentObjectStoragePrefix =
        new ConnectionDTO(
            "different_object_storage",
            "Fileset",
            "s3://data-bucket/production/Events",
            "s3-token",
            1L);
    ConnectionDTO hostOnly1 =
        new ConnectionDTO("host_only_1", "Glue", "GLUE.US-EAST-1.AMAZONAWS.COM", "s3-token", 1L);
    ConnectionDTO hostOnly2 =
        new ConnectionDTO("host_only_2", "Glue", "glue.us-east-1.amazonaws.com", "s3-token", 1L);

    assertEquals(
        6,
        ConnectionConverter.calculateSystemCount(
            new ConnectionDTO[] {
              normalized1,
              normalized2,
              differentPath,
              differentQuery,
              objectStoragePrefix,
              differentObjectStoragePrefix,
              hostOnly1,
              hostOnly2
            }));
  }

  @Test
  public void testCalculateSystemCountNormalizesAuthorityListHostCase() {
    ConnectionDTO kafka1 =
        new ConnectionDTO("kafka_1", "Kafka", "broker1:9092,Broker2:9092", "--", 1L);
    ConnectionDTO kafka2 =
        new ConnectionDTO("kafka_2", "Kafka", "BROKER1:9092,broker2:9092", "--", 1L);

    assertEquals(1, ConnectionConverter.calculateSystemCount(new ConnectionDTO[] {kafka1, kafka2}));
  }

  @Test
  public void testResolvedJdbcEndpointsDoNotContainSecrets() {
    String mysqlEndpoint =
        ConnectionConverter.resolveEndpoint(
            "jdbc-mysql",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:mysql://user:user-info-secret@host:3306/db?password=query-secret"));
    String sqlServerEndpoint =
        ConnectionConverter.resolveEndpoint(
            "jdbc-sqlserver",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:sqlserver://host:1433;password=semicolon-secret;accessToken=token-secret"));
    String bigQueryEndpoint =
        ConnectionConverter.resolveEndpoint(
            "jdbc-bigquery",
            ImmutableMap.of(
                "jdbc-url",
                "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;"
                    + "ProxyPwd=proxy-secret;OAuthAccessToken=oauth-secret"));

    for (String endpoint : new String[] {mysqlEndpoint, sqlServerEndpoint, bigQueryEndpoint}) {
      assertFalse(endpoint.contains("secret"));
      assertFalse(endpoint.contains("password"));
      assertFalse(endpoint.contains("accessToken"));
      assertFalse(endpoint.contains("ProxyPwd"));
    }
  }
}
