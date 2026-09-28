/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.iceberg.integration.test;

import java.util.Map;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergFormatVersionResolution;
import org.apache.gravitino.integration.test.util.ITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.server.DatastratoGravitinoServer;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

/** Server acceptance coverage for admission with KMS key validation left at its default (off). */
@Tag("gravitino-docker-test")
@DisabledIfSystemProperty(named = ITUtils.TEST_MODE, matches = ITUtils.DEPLOY_TEST_MODE)
public class IcebergEncryptionKeyValidationIT extends IcebergAuthorizationIT {

  private static final String SCHEMA_NAME = "key_validation_off";

  /** Starts the server with a configured provider that must never be constructed. */
  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    RecordingKmsClientFactory.reset();
    customConfigs.put("gravitino.kms.providers", RecordingKmsClientFactory.PROVIDER);
    customConfigs.put(
        "gravitino.kms.provider." + RecordingKmsClientFactory.PROVIDER + ".className",
        RecordingKmsClientFactory.class.getName());
    super.startIntegrationTest();
    catalogClientWithAllPrivilege
        .asSchemas()
        .createSchema(SCHEMA_NAME, "key validation off acceptance", Map.of());
  }

  /** Starts the Enterprise server, including its governed Gravitino table dispatcher. */
  @Override
  protected void startMiniGravitinoServer(String[] args) {
    DatastratoGravitinoServer.main(args);
  }

  /**
   * Binds the catalog to the provider whose server client is intentionally absent, and declares the
   * format version encryption needs. The catalog binds no key; its declaration carries the two
   * client-key creates below to version 3 because Gravitino never raises the version itself.
   */
  @Override
  protected Map<String, String> getCustomProperties() {
    return Map.of(
        IcebergConstants.ENCRYPTION_KMS_SOURCE,
        RecordingKmsClientFactory.PROVIDER,
        "gravitino.bypass." + IcebergFormatVersionResolution.TABLE_DEFAULT_FORMAT_VERSION,
        "3");
  }

  @Test
  void testBothCreateApisForwardTheKeyWithoutServerKmsClient() {
    Assertions.assertEquals(0, RecordingKmsClientFactory.creationCount());

    String restName = "rest_created";
    Table restTable =
        adminIcebergCatalog
            .buildTable(
                TableIdentifier.of(Namespace.of(SCHEMA_NAME), restName),
                new Schema(Types.NestedField.required(1, "id", Types.LongType.get(), "record id")))
            .withProperty(TableProperties.ENCRYPTION_TABLE_KEY, RecordingKmsClientFactory.KEY_ID)
            .create();
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID,
        restTable.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertEquals(3, ((BaseTable) restTable).operations().current().formatVersion());

    String apiName = "gravitino_created";
    catalogClientWithAllPrivilege
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(SCHEMA_NAME, apiName),
            new Column[] {
              Column.of("id", org.apache.gravitino.rel.types.Types.LongType.get(), "record id")
            },
            "skip KMS validation acceptance",
            Map.of(
                IcebergConstants.ENCRYPTION_KEY_PROVIDER,
                RecordingKmsClientFactory.PROVIDER,
                IcebergConstants.ENCRYPTION_KEY_ID,
                RecordingKmsClientFactory.KEY_ID));
    Map<String, String> properties =
        catalogClientWithAllPrivilege
            .asTableCatalog()
            .loadTable(NameIdentifier.of(SCHEMA_NAME, apiName))
            .properties();
    Assertions.assertEquals(
        RecordingKmsClientFactory.KEY_ID, properties.get(IcebergConstants.ENCRYPTION_KEY_ID));
    Assertions.assertEquals("3", properties.get(TableProperties.FORMAT_VERSION));

    Assertions.assertEquals(0, RecordingKmsClientFactory.creationCount());
    Assertions.assertEquals(0, RecordingKmsClientFactory.inspectionCount());
  }
}
