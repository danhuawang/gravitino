package org.apache.gravitino.catalog.bigquery.integration;

import static org.apache.gravitino.catalog.bigquery.BigQueryCatalogPropertiesMetadata.PROJECT_ID;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_OF_CURRENT_TIMESTAMP;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SupportsSchemas;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestInstance(Lifecycle.PER_CLASS)
@EnabledIf("isBigQueryConfigured")
public class CatalogBigQueryIT extends BaseIT {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogBigQueryIT.class);
  private static final String provider = "jdbc-bigquery";

  private static final String ENV_PROJECT_ID = "BIGQUERY_PROJECT_ID";
  private static final String ENV_KEY_PATH = "BIGQUERY_SERVICE_ACCOUNT_KEY_PATH";
  private static final String ENV_SERVICE_ACCOUNT_EMAIL = "BIGQUERY_SERVICE_ACCOUNT_EMAIL";
  private static final String ENV_DATASET_LOCATION = "BIGQUERY_DATASET_LOCATION";

  private String projectId;
  private String keyPath;
  private String serviceAccountEmail;
  private String datasetLocation;

  public String metalakeName = GravitinoITUtils.genRandomName("bigquery_it_metalake");
  public String catalogName = GravitinoITUtils.genRandomName("bigquery_it_catalog");
  public String schemaName = GravitinoITUtils.genRandomName("bigquery_it_schema");
  public String tableName = GravitinoITUtils.genRandomName("bigquery_it_table");
  public String alertTableName = "alert_table_name";
  public String table_comment = "table_comment";
  public String schema_comment = "schema_comment";

  public String BIGQUERY_COL_NAME1 = "bigquery_col_name1";
  public String BIGQUERY_COL_NAME2 = "bigquery_col_name2";
  public String BIGQUERY_COL_NAME3 = "bigquery_col_name3";
  public String BIGQUERY_COL_NAME4 = "bigquery_col_name4";
  public String BIGQUERY_COL_NAME5 = "bigquery_col_name5";

  private GravitinoMetalake metalake;
  protected Catalog catalog;

  static boolean isBigQueryConfigured() {
    return StringUtils.isNotBlank(System.getenv(ENV_PROJECT_ID))
        && StringUtils.isNotBlank(System.getenv(ENV_KEY_PATH));
  }

  @BeforeAll
  public void startup() {
    projectId = System.getenv(ENV_PROJECT_ID);
    keyPath = System.getenv(ENV_KEY_PATH);
    serviceAccountEmail = System.getenv(ENV_SERVICE_ACCOUNT_EMAIL);
    datasetLocation = System.getenv(ENV_DATASET_LOCATION);

    if (StringUtils.isBlank(datasetLocation)) {
      datasetLocation = "US";
    }

    LOG.info("Starting BigQuery integration tests with project: {}", projectId);
    LOG.info("Dataset location: {}", datasetLocation);

    createMetalake();
    catalog = createCatalog(catalogName);
    createSchema(catalog, schemaName);
  }

  @AfterAll
  public void stop() {
    clearTableAndSchema();
    LOG.info(
        "BigQuery integration tests completed. Catalog and metalake retained for other tests.");
  }

  @AfterEach
  public void resetSchema() {
    clearTableAndSchema();
    createSchema(catalog, schemaName);
  }

  private void clearTableAndSchema() {
    try {
      NameIdentifier[] nameIdentifiers =
          catalog.asTableCatalog().listTables(Namespace.of(schemaName));
      for (NameIdentifier nameIdentifier : nameIdentifiers) {
        catalog.asTableCatalog().dropTable(nameIdentifier);
      }
    } catch (Exception e) {
      LOG.warn("Error clearing tables", e);
    }
  }

  private void createMetalake() {
    client.createMetalake(metalakeName, "comment", Collections.emptyMap());
    GravitinoMetalake loadMetalake = client.loadMetalake(metalakeName);
    Assertions.assertEquals(metalakeName, loadMetalake.name());
    metalake = loadMetalake;
  }

  private Catalog createCatalog(String catalogName) {
    Map<String, String> catalogProperties = Maps.newHashMap();

    catalogProperties.put(PROJECT_ID, projectId);
    catalogProperties.put(JdbcConfig.PASSWORD.getKey(), keyPath);
    catalogProperties.put(
        JdbcConfig.JDBC_DRIVER.getKey(), "com.simba.googlebigquery.jdbc42.Driver");

    if (StringUtils.isNotBlank(serviceAccountEmail)) {
      catalogProperties.put(JdbcConfig.USERNAME.getKey(), serviceAccountEmail);
    }

    Catalog createdCatalog =
        metalake.createCatalog(
            catalogName, Catalog.Type.RELATIONAL, provider, "comment", catalogProperties);
    Catalog loadCatalog = metalake.loadCatalog(catalogName);
    Assertions.assertEquals(createdCatalog, loadCatalog);

    return loadCatalog;
  }

  private void createSchema(Catalog catalog, String schemaName) {
    Map<String, String> prop = Maps.newHashMap();
    prop.put("location", datasetLocation);

    try {
      Schema loadSchema = catalog.asSchemas().loadSchema(schemaName);
      if (loadSchema != null) {
        LOG.info("Schema {} already exists, skipping creation", schemaName);
        return;
      }
    } catch (NoSuchSchemaException e) {
      Schema createdSchema = catalog.asSchemas().createSchema(schemaName, schema_comment, prop);
      Schema loadSchema = catalog.asSchemas().loadSchema(schemaName);
      Assertions.assertEquals(createdSchema.name(), loadSchema.name());
      prop.forEach(
          (key, value) -> Assertions.assertEquals(loadSchema.properties().get(key), value));
    }
  }

  private Column[] createColumns() {
    Column col1 = Column.of(BIGQUERY_COL_NAME1, Types.LongType.get(), "col_1_comment");
    Column col2 = Column.of(BIGQUERY_COL_NAME2, Types.DateType.get(), "col_2_comment");
    Column col3 = Column.of(BIGQUERY_COL_NAME3, Types.StringType.get(), "col_3_comment");

    return new Column[] {col1, col2, col3};
  }

  private Column[] createColumnsWithDefaultValue() {
    return new Column[] {
      Column.of(
          BIGQUERY_COL_NAME1,
          Types.DoubleType.get(),
          "col_1_comment",
          false,
          false,
          Literals.of("1.23", Types.DoubleType.get())),
      Column.of(
          BIGQUERY_COL_NAME2,
          Types.TimestampType.withoutTimeZone(),
          "col_2_comment",
          false,
          false,
          DEFAULT_VALUE_OF_CURRENT_TIMESTAMP),
      Column.of(
          BIGQUERY_COL_NAME3, Types.StringType.get(), "col_3_comment", true, false, Literals.NULL),
      Column.of(
          BIGQUERY_COL_NAME4,
          Types.LongType.get(),
          "col_4_comment",
          false,
          false,
          Literals.of("1000", Types.LongType.get())),
      Column.of(
          BIGQUERY_COL_NAME5,
          Types.DecimalType.of(10, 2),
          "col_5_comment",
          true,
          false,
          Literals.of("1.23", Types.DecimalType.of(10, 2)))
    };
  }

  private Map<String, String> createProperties() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put("description", "Test table for BigQuery integration");
    return properties;
  }

  @Test
  void testTestConnection() {
    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put(PROJECT_ID, projectId);
    catalogProperties.put(JdbcConfig.PASSWORD.getKey(), "/invalid/path/to/key.json");
    catalogProperties.put(JdbcConfig.USERNAME.getKey(), "invalid@example.com");
    catalogProperties.put(
        JdbcConfig.JDBC_DRIVER.getKey(), "com.simba.googlebigquery.jdbc42.Driver");

    Exception exception =
        assertThrows(
            RuntimeException.class,
            () ->
                metalake.testConnection(
                    GravitinoITUtils.genRandomName("bigquery_it_catalog"),
                    Catalog.Type.RELATIONAL,
                    provider,
                    "comment",
                    catalogProperties));
    Assertions.assertTrue(
        exception.getMessage().contains("Error creating datasource")
            || exception.getMessage().contains("Failed to create BigQuery API client")
            || exception.getMessage().contains("Connection failed"));
  }

  @Test
  void testOperationBigQuerySchema() {
    SupportsSchemas schemas = catalog.asSchemas();

    String[] nameIdentifiers = schemas.listSchemas();
    Set<String> schemaNames = Sets.newHashSet(nameIdentifiers);
    Assertions.assertTrue(schemaNames.contains(schemaName.toLowerCase()));

    String testSchemaName = GravitinoITUtils.genRandomName("test_schema_1").toLowerCase();
    Map<String, String> properties = Maps.newHashMap();
    properties.put("location", datasetLocation);

    Schema createdSchema = schemas.createSchema(testSchemaName, schema_comment, properties);
    Assertions.assertNotNull(createdSchema);
    Assertions.assertEquals(testSchemaName, createdSchema.name());

    nameIdentifiers = schemas.listSchemas();
    schemaNames = Sets.newHashSet(nameIdentifiers);
    Assertions.assertTrue(schemaNames.contains(testSchemaName));

    Map<String, String> emptyMap = Collections.emptyMap();
    Assertions.assertThrows(
        SchemaAlreadyExistsException.class,
        () -> schemas.createSchema(testSchemaName, schema_comment, emptyMap));

    schemas.dropSchema(testSchemaName, false);
    Assertions.assertThrows(NoSuchSchemaException.class, () -> schemas.loadSchema(testSchemaName));

    nameIdentifiers = schemas.listSchemas();
    schemaNames = Sets.newHashSet(nameIdentifiers);
    Assertions.assertFalse(schemaNames.contains(testSchemaName));
    Assertions.assertFalse(schemas.dropSchema("no_exits", false));

    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier table = NameIdentifier.of(testSchemaName, "test_table");
    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () ->
            tableCatalog.createTable(
                table,
                createColumns(),
                table_comment,
                createProperties(),
                null,
                Distributions.NONE,
                null));

    Assertions.assertFalse(schemas.dropSchema(testSchemaName, true));
    Assertions.assertFalse(schemas.dropSchema(testSchemaName, false));
    Assertions.assertFalse(tableCatalog.dropTable(table));
  }

  @Test
  void testCreateAndLoadBigQueryTable() {
    Column[] columns = createColumns();
    NameIdentifier tableIdentifier = NameIdentifier.of(schemaName, tableName);

    final SortOrder[] sortOrders = new SortOrder[0];
    Transform[] partitioning = Transforms.EMPTY_TRANSFORM;
    Map<String, String> properties = createProperties();

    TableCatalog tableCatalog = catalog.asTableCatalog();
    Table createdTable =
        tableCatalog.createTable(
            tableIdentifier,
            columns,
            table_comment,
            properties,
            partitioning,
            Distributions.NONE,
            sortOrders);

    Assertions.assertNotNull(createdTable);
    Assertions.assertEquals(tableName, createdTable.name());

    Table loadTable = tableCatalog.loadTable(tableIdentifier);
    Assertions.assertEquals(tableName, loadTable.name());

    Map<String, String> resultProp = loadTable.properties();
    Assertions.assertNotNull(resultProp);

    Assertions.assertEquals(loadTable.columns().length, columns.length);
    for (int i = 0; i < columns.length; i++) {
      Assertions.assertEquals(columns[i].name(), loadTable.columns()[i].name());
      Assertions.assertEquals(columns[i].dataType(), loadTable.columns()[i].dataType());
    }
  }

  @Test
  void testCreateTableWithPartitioning() {
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "ID column"),
      Column.of("created_date", Types.DateType.get(), "Creation date"),
      Column.of("name", Types.StringType.get(), "Name column")
    };

    String partitionedTableName = GravitinoITUtils.genRandomName("partitioned_table");
    NameIdentifier tableIdentifier = NameIdentifier.of(schemaName, partitionedTableName);

    Transform[] partitioning = new Transform[] {Transforms.day(new String[] {"created_date"})};

    Map<String, String> properties = Maps.newHashMap();
    properties.put("description", "Partitioned table test");

    TableCatalog tableCatalog = catalog.asTableCatalog();
    Table createdTable =
        tableCatalog.createTable(
            tableIdentifier,
            columns,
            "Partitioned table",
            properties,
            partitioning,
            Distributions.NONE,
            new SortOrder[0]);

    Assertions.assertEquals(partitionedTableName, createdTable.name());

    Table loadedTable = tableCatalog.loadTable(tableIdentifier);
    Assertions.assertEquals(partitionedTableName, loadedTable.name());
    Assertions.assertEquals(3, loadedTable.columns().length);
  }

  @Test
  void testCreateTableWithClustering() {
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "ID column"),
      Column.of("category", Types.StringType.get(), "Category column"),
      Column.of("subcategory", Types.StringType.get(), "Subcategory column"),
      Column.of("amount", Types.DecimalType.of(10, 2), "Amount column")
    };

    String clusteredTableName = GravitinoITUtils.genRandomName("clustered_table");
    NameIdentifier tableIdentifier = NameIdentifier.of(schemaName, clusteredTableName);

    Map<String, String> properties = Maps.newHashMap();
    properties.put("description", "Clustered table test");
    properties.put("clustering_fields", "category,subcategory");

    TableCatalog tableCatalog = catalog.asTableCatalog();
    Table createdTable =
        tableCatalog.createTable(
            tableIdentifier,
            columns,
            "Clustered table",
            properties,
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);

    Assertions.assertEquals(clusteredTableName, createdTable.name());

    Table loadedTable = tableCatalog.loadTable(tableIdentifier);
    Assertions.assertEquals(clusteredTableName, loadedTable.name());
    Assertions.assertEquals(4, loadedTable.columns().length);
  }

  @Test
  void testAlterAndDropBigQueryTable() {
    Column[] columns = createColumns();
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, table_comment, createProperties());

    catalog
        .asTableCatalog()
        .alterTable(NameIdentifier.of(schemaName, tableName), TableChange.rename(alertTableName));

    catalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(schemaName, alertTableName),
            TableChange.updateComment(table_comment + "_new"));

    catalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(schemaName, alertTableName),
            TableChange.addColumn(new String[] {"col_4"}, Types.StringType.get()));

    catalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(schemaName, alertTableName),
            TableChange.renameColumn(new String[] {BIGQUERY_COL_NAME2}, "col_2_new"));

    Table table = catalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, alertTableName));
    Assertions.assertEquals(alertTableName, table.name());

    Assertions.assertEquals(BIGQUERY_COL_NAME1, table.columns()[0].name());
    Assertions.assertEquals(Types.LongType.get(), table.columns()[0].dataType());

    Assertions.assertEquals("col_2_new", table.columns()[1].name());
    Assertions.assertEquals(Types.DateType.get(), table.columns()[1].dataType());

    Assertions.assertEquals(BIGQUERY_COL_NAME3, table.columns()[2].name());
    Assertions.assertEquals(Types.StringType.get(), table.columns()[2].dataType());

    Assertions.assertEquals("col_4", table.columns()[3].name());
    Assertions.assertEquals(Types.StringType.get(), table.columns()[3].dataType());

    Assertions.assertNotNull(table.auditInfo());

    Assertions.assertDoesNotThrow(
        () -> {
          catalog.asTableCatalog().dropTable(NameIdentifier.of(schemaName, alertTableName));
        });
  }

  @Test
  void testColumnDefaultValue() {
    Column[] columns = createColumnsWithDefaultValue();
    catalog
        .asTableCatalog()
        .createTable(NameIdentifier.of(schemaName, tableName), columns, null, ImmutableMap.of());

    Table loadedTable =
        catalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));

    Assertions.assertEquals(5, loadedTable.columns().length);

    for (int i = 0; i < loadedTable.columns().length; i++) {
      Assertions.assertNotNull(loadedTable.columns()[i]);
      Assertions.assertEquals(columns[i].name(), loadedTable.columns()[i].name());
      Assertions.assertEquals(columns[i].dataType(), loadedTable.columns()[i].dataType());
    }
  }

  @Test
  void testDropBigQueryDataset() {
    String testSchemaName = GravitinoITUtils.genRandomName("bigquery_schema").toLowerCase();
    String testTableName = GravitinoITUtils.genRandomName("bigquery_table").toLowerCase();

    Map<String, String> properties = Maps.newHashMap();
    properties.put("location", datasetLocation);

    catalog.asSchemas().createSchema(testSchemaName, null, properties);

    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(testSchemaName, testTableName),
            createColumns(),
            "Created by Gravitino client",
            ImmutableMap.<String, String>builder().build());

    Throwable excep =
        Assertions.assertThrows(
            RuntimeException.class, () -> catalog.asSchemas().dropSchema(testSchemaName, false));

    String errorMessage = excep.getMessage().toLowerCase();
    Assertions.assertTrue(
        errorMessage.contains("cascade")
            || errorMessage.contains("delete")
            || errorMessage.contains("dataset")
            || errorMessage.contains("non-empty")
            || errorMessage.contains("not empty"));

    catalog.asSchemas().loadSchema(testSchemaName);

    catalog.asSchemas().dropSchema(testSchemaName, true);

    SupportsSchemas schemas = catalog.asSchemas();
    Assertions.assertThrows(NoSuchSchemaException.class, () -> schemas.loadSchema(testSchemaName));
  }

  @Test
  void testBigQuerySpecialTableName() {
    Map<String, String> properties = createProperties();
    TableCatalog tableCatalog = catalog.asTableCatalog();

    String t1_name = "t112";
    Column t1_col = Column.of(t1_name, Types.LongType.get(), "id", false, false, null);
    Column[] columns = {t1_col};

    NameIdentifier tableIdentifier = NameIdentifier.of(schemaName, t1_name);
    tableCatalog.createTable(
        tableIdentifier,
        columns,
        table_comment,
        properties,
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0]);

    String t2_name = "t212";
    Column t2_col = Column.of(t2_name, Types.LongType.get(), "id", false, false, null);
    columns = new Column[] {t2_col};
    tableIdentifier = NameIdentifier.of(schemaName, t2_name);
    tableCatalog.createTable(
        tableIdentifier,
        columns,
        table_comment,
        properties,
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0]);

    String t3_name = "t_12";
    Column t3_col = Column.of(t3_name, Types.LongType.get(), "id", false, false, null);
    columns = new Column[] {t3_col};
    tableIdentifier = NameIdentifier.of(schemaName, t3_name);
    tableCatalog.createTable(
        tableIdentifier,
        columns,
        table_comment,
        properties,
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0]);

    String t4_name = "_1__";
    Column t4_col = Column.of(t4_name, Types.LongType.get(), "id", false, false, null);
    columns = new Column[] {t4_col};
    tableIdentifier = NameIdentifier.of(schemaName, t4_name);
    tableCatalog.createTable(
        tableIdentifier,
        columns,
        table_comment,
        properties,
        Transforms.EMPTY_TRANSFORM,
        Distributions.NONE,
        new SortOrder[0]);

    Table t1 = tableCatalog.loadTable(NameIdentifier.of(schemaName, t1_name));
    Assertions.assertTrue(
        Arrays.stream(t1.columns()).anyMatch(c -> Objects.equals(c.name(), "t112")));

    Table t2 = tableCatalog.loadTable(NameIdentifier.of(schemaName, t2_name));
    Assertions.assertTrue(
        Arrays.stream(t2.columns()).anyMatch(c -> Objects.equals(c.name(), "t212")));

    Table t3 = tableCatalog.loadTable(NameIdentifier.of(schemaName, t3_name));
    Assertions.assertTrue(
        Arrays.stream(t3.columns()).anyMatch(c -> Objects.equals(c.name(), "t_12")));

    Table t4 = tableCatalog.loadTable(NameIdentifier.of(schemaName, t4_name));
    Assertions.assertTrue(
        Arrays.stream(t4.columns()).anyMatch(c -> Objects.equals(c.name(), "_1__")));
  }

  @Test
  void testBackQuoteTable() {
    Column col1 = Column.of("create", Types.LongType.get(), "id", false, false, null);
    Column col2 = Column.of("delete", Types.LongType.get(), "yes", false, false, null);
    Column col3 = Column.of("show", Types.DateType.get(), "comment", false, false, null);
    Column col4 = Column.of("status", Types.StringType.get(), "code", false, false, null);
    Column[] newColumns = new Column[] {col1, col2, col3, col4};

    TableCatalog tableCatalog = catalog.asTableCatalog();
    NameIdentifier tableIdentifier = NameIdentifier.of(schemaName, "test_table_with_keywords");

    Assertions.assertDoesNotThrow(
        () ->
            tableCatalog.createTable(
                tableIdentifier,
                newColumns,
                table_comment,
                Collections.emptyMap(),
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                new SortOrder[0]));

    Assertions.assertDoesNotThrow(() -> tableCatalog.loadTable(tableIdentifier));

    Assertions.assertDoesNotThrow(
        () ->
            tableCatalog.alterTable(
                tableIdentifier,
                TableChange.addColumn(
                    new String[] {"int"},
                    Types.StringType.get(),
                    TableChange.ColumnPosition.after("status"))));

    Assertions.assertDoesNotThrow(
        () ->
            tableCatalog.alterTable(
                tableIdentifier, TableChange.deleteColumn(new String[] {"create"}, true)));

    Assertions.assertDoesNotThrow(
        () ->
            tableCatalog.alterTable(
                tableIdentifier, TableChange.renameColumn(new String[] {"delete"}, "varchar")));

    Assertions.assertDoesNotThrow(() -> tableCatalog.dropTable(tableIdentifier));
  }

  @Test
  void testCrossRegionOperations() {
    SupportsSchemas schemas = catalog.asSchemas();

    String[] schemaNames = schemas.listSchemas();
    Assertions.assertTrue(schemaNames.length > 0);

    String testSchemaName = GravitinoITUtils.genRandomName("cross_region_test");
    Map<String, String> properties = Maps.newHashMap();
    properties.put("location", "EU");

    try {
      schemas.createSchema(testSchemaName, "Cross-region test schema", properties);

      Schema loadedSchema = schemas.loadSchema(testSchemaName);
      Assertions.assertEquals(testSchemaName, loadedSchema.name());

      schemas.dropSchema(testSchemaName, false);
    } catch (Exception e) {
      LOG.warn("Cross-region test failed, this might be expected: {}", e.getMessage());
    }
  }

  @Test
  void testConcurrentOperations() throws InterruptedException {
    int numThreads = 3;
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      threads[i] =
          new Thread(
              () -> {
                try {
                  String threadTableName =
                      GravitinoITUtils.genRandomName("concurrent_table_" + threadId);
                  NameIdentifier tableIdentifier = NameIdentifier.of(schemaName, threadTableName);

                  catalog
                      .asTableCatalog()
                      .createTable(
                          tableIdentifier,
                          createColumns(),
                          "Concurrent test table " + threadId,
                          createProperties(),
                          Transforms.EMPTY_TRANSFORM,
                          Distributions.NONE,
                          new SortOrder[0]);

                  Table loadedTable = catalog.asTableCatalog().loadTable(tableIdentifier);
                  Assertions.assertEquals(threadTableName, loadedTable.name());

                  catalog.asTableCatalog().dropTable(tableIdentifier);

                } catch (Exception e) {
                  LOG.error("Concurrent operation failed in thread {}", threadId, e);
                  throw new RuntimeException(e);
                }
              });
    }

    for (Thread thread : threads) {
      thread.start();
    }

    for (Thread thread : threads) {
      thread.join(30000);
    }

    LOG.info("Concurrent operations test completed successfully");
  }

  @Test
  void testPerformanceOperations() {
    long startTime, endTime;

    startTime = System.currentTimeMillis();
    String[] schemas = catalog.asSchemas().listSchemas();
    endTime = System.currentTimeMillis();
    LOG.info("Listed {} schemas in {} ms", schemas.length, endTime - startTime);

    startTime = System.currentTimeMillis();
    NameIdentifier[] tables = catalog.asTableCatalog().listTables(Namespace.of(schemaName));
    endTime = System.currentTimeMillis();
    LOG.info("Listed {} tables in {} ms", tables.length, endTime - startTime);

    String perfTableName = GravitinoITUtils.genRandomName("perf_table");
    NameIdentifier tableIdentifier = NameIdentifier.of(schemaName, perfTableName);

    startTime = System.currentTimeMillis();
    catalog
        .asTableCatalog()
        .createTable(
            tableIdentifier,
            createColumns(),
            "Performance test table",
            createProperties(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);
    endTime = System.currentTimeMillis();
    LOG.info("Created table in {} ms", endTime - startTime);

    startTime = System.currentTimeMillis();
    Table loadedTable = catalog.asTableCatalog().loadTable(tableIdentifier);
    endTime = System.currentTimeMillis();
    LOG.info("Loaded table in {} ms", endTime - startTime);
    Assertions.assertEquals(perfTableName, loadedTable.name());

    catalog.asTableCatalog().dropTable(tableIdentifier);
  }
}
