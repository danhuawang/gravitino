/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.trino;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.client.DefaultOAuth2TokenProvider;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.CatalogAlreadyExistsException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E tests for Iceberg multi-level (nested) namespace support verified through Trino JDBC and the
 * Gravitino Java client API.
 *
 * <p>Tests run against a real Gravitino + Trino deployment (same environment as {@link
 * TrinoIcebergRestGenericViewIT}). The test exercises:
 *
 * <ul>
 *   <li>TC-1: Trino CREATE SCHEMA with dot-notation nested namespace
 * </ul>
 *
 * @see TEST_PLAN_NESTED_NAMESPACE.md for the full test plan.
 */
@DisplayName("Trino Iceberg Nested Namespace E2E Tests (via IRC, S3 warehouse)")
public class TrinoIcebergRestNestedNamespaceIT {

  private static final Logger LOG =
      LoggerFactory.getLogger(TrinoIcebergRestNestedNamespaceIT.class);

  // ── Constants ─────────────────────────────────────────────────────────────

  private static final String METALAKE_NAME = "test";
  private static final String CATALOG_NAME = "catalog_iceberg_s3";

  /**
   * Trino catalog name — matches the properties file name {@code gravitino_irc_s3.properties}
   * mounted in the Trino container (without the {@code .properties} suffix).
   */
  private static final String TRINO_CATALOG = "gravitino_irc_s3";

  private static final String DEFAULT_OAUTH2_REALM = "myrealm";
  private static final String DEFAULT_OAUTH2_SCOPE = "openid profile email";

  /** Spark catalog name — registered in the SparkSession to point at the same IRC endpoint. */
  private static final String SPARK_CATALOG = "gravitino_irc_s3";

  // ── Shared state ──────────────────────────────────────────────────────────

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog catalog;
  private static Connection trinoConnection;

  /** Tracks whether this test run created the catalog (so we only drop what we created). */
  private static boolean catalogCreatedByTest = false;

  /** Unique prefix for this test run to avoid collisions across parallel runs. */
  private static String testRunPrefix;

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  @BeforeAll
  public static void setup() throws Exception {
    // 1. Validate required AWS env vars.
    String awsAccessKeyId = requireEnv("AWS_ACCESS_KEY_ID");
    String awsSecretAccessKey = requireEnv("AWS_SECRET_ACCESS_KEY");
    String awsS3RoleArn = requireEnv("AWS_S3_ROLE_ARN");
    String awsS3Bucket = requireEnv("AWS_S3_BUCKET");
    String awsS3Region = System.getenv().getOrDefault("AWS_S3_REGION", "us-east-1");

    // 2. Validate required OAuth2 env vars.
    String oauth2ServerUri = requireEnv("OAUTH2_SERVER_URI");
    String oauth2ClientId = requireEnv("OAUTH2_CLIENT_ID");
    String oauth2ClientSecret = requireEnv("OAUTH2_CLIENT_SECRET");
    String oauth2Realm = System.getenv().getOrDefault("OAUTH2_REALM", DEFAULT_OAUTH2_REALM);
    String oauth2Scope = System.getenv().getOrDefault("OAUTH2_SCOPE", DEFAULT_OAUTH2_SCOPE);
    String oauth2TokenPath =
        System.getenv()
            .getOrDefault(
                "OAUTH2_TOKEN_PATH",
                String.format("realms/%s/protocol/openid-connect/token", oauth2Realm));
    if (oauth2TokenPath.startsWith("/")) {
      oauth2TokenPath = oauth2TokenPath.substring(1);
    }

    // 3. Build OAuth2 admin client.
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String credential = oauth2ClientId + ":" + oauth2ClientSecret;
    DefaultOAuth2TokenProvider tokenProvider =
        DefaultOAuth2TokenProvider.builder()
            .withUri(oauth2ServerUri)
            .withCredential(credential)
            .withScope(oauth2Scope)
            .withPath(oauth2TokenPath)
            .build();
    adminClient = GravitinoAdminClient.builder(gravitinoUri).withOAuth(tokenProvider).build();
    metalake = adminClient.loadMetalake(METALAKE_NAME);

    // 4. Idempotent catalog creation.
    Map<String, String> catalogProps = new HashMap<>();
    catalogProps.put("catalog-backend", "jdbc");
    catalogProps.put(
        "uri",
        "jdbc:postgresql://gravitino-env2-oauth2-auth-postgresql"
            + ".gravitino-e2e-env2-oauth2-auth:5432/gravitinoirc");
    catalogProps.put("jdbc-driver", "org.postgresql.Driver");
    catalogProps.put("jdbc-user", "gravitino");
    catalogProps.put("jdbc-password", "gravitino");
    catalogProps.put("jdbc-schema-version", "V1");
    catalogProps.put("jdbc-initialize", "true");
    catalogProps.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
    catalogProps.put(
        "warehouse",
        String.format(
            "s3://%s/gravitino-e2e-env2-oauth2-auth/test/catalog_iceberg_s3", awsS3Bucket));
    catalogProps.put("credential-providers", "s3-token");
    catalogProps.put("s3-token-expire-in-secs", "900");
    catalogProps.put("s3-access-key-id", awsAccessKeyId);
    catalogProps.put("s3-secret-access-key", awsSecretAccessKey);
    catalogProps.put("s3-region", awsS3Region);
    catalogProps.put("s3-role-arn", awsS3RoleArn);

    try {
      metalake.createCatalog(
          CATALOG_NAME,
          Catalog.Type.RELATIONAL,
          "lakehouse-iceberg",
          "S3-backed Iceberg catalog for nested namespace E2E tests",
          catalogProps);
      catalogCreatedByTest = true;
      LOG.info("Created catalog '{}'", CATALOG_NAME);
    } catch (CatalogAlreadyExistsException e) {
      LOG.info("Catalog '{}' already exists — reusing", CATALOG_NAME);
    }

    catalog = metalake.loadCatalog(CATALOG_NAME);

    // 5. Build Trino JDBC connection.
    String trinoUri = System.getProperty("gravitino.trino.uri", "http://localhost:30880");
    String trinoJdbcUrl =
        "jdbc:trino://" + trinoUri.replaceFirst("^https?://", "") + "/" + TRINO_CATALOG;
    trinoConnection = DriverManager.getConnection(trinoJdbcUrl, "admin", null);

    // 6. Generate a unique prefix for this test run.
    testRunPrefix = RandomNameUtils.genRandomName("ns_e2e");

    LOG.info(
        "TrinoIcebergRestNestedNamespaceIT setup complete: gravitinoUri={}, trinoJdbc={}, prefix={}",
        gravitinoUri,
        trinoJdbcUrl,
        testRunPrefix);
  }

  @AfterAll
  public static void teardown() {
    // Drop catalog only if this test created it.
    if (catalogCreatedByTest && metalake != null) {
      try {
        // metalake.dropCatalog(CATALOG_NAME, true);
        LOG.info("Dropped catalog '{}' (created by this test run)", CATALOG_NAME);
      } catch (Exception e) {
        LOG.warn("Failed to drop catalog '{}'", CATALOG_NAME, e);
      }
    }

    // Close JDBC.
    if (trinoConnection != null) {
      try {
        trinoConnection.close();
      } catch (Exception e) {
        LOG.warn("Failed to close Trino JDBC connection", e);
      }
    }

    // Close admin client.
    if (adminClient != null) {
      adminClient.close();
    }
  }

  //   @AfterEach
  //   public void cleanupTestSchemas() {
  //     if (trinoConnection == null || testRunPrefix == null) {
  //       return;
  //     }
  //     try {
  //       // Find all schemas belonging to this test run.
  //       Set<String> allSchemas = queryFirstColumnAsSet("SHOW SCHEMAS IN " + TRINO_CATALOG);
  //       // Sort by length descending so we drop deepest schemas first.
  //       List<String> toClean =
  //           allSchemas.stream()
  //               .filter(s -> s.startsWith(testRunPrefix))
  //               .sorted((a, b) -> Integer.compare(b.length(), a.length()))
  //               .collect(java.util.stream.Collectors.toList());

  //       for (String schema : toClean) {
  //         try {
  //           // Drop all tables in the schema first.
  //           Set<String> tables =
  //               queryFirstColumnAsSet(
  //                   String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, schema));
  //           for (String table : tables) {
  //             executeTrinoDdlQuietly(
  //                 String.format("DROP TABLE IF EXISTS %s.\"%s\".\"%s\"", TRINO_CATALOG, schema,
  // table));
  //           }
  //           // Drop all views in the schema.
  //           try {
  //             Set<String> views =
  //                 queryFirstColumnAsSet(
  //                     String.format("SHOW VIEWS IN %s.\"%s\"", TRINO_CATALOG, schema));
  //             for (String view : views) {
  //               executeTrinoDdlQuietly(
  //                   String.format("DROP VIEW IF EXISTS %s.\"%s\".\"%s\"", TRINO_CATALOG, schema,
  // view));
  //             }
  //           } catch (Exception ignored) {
  //             // SHOW VIEWS may not be supported; ignore.
  //           }
  //           // Drop the schema itself.
  //           executeTrinoDdlQuietly(
  //               String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, schema));
  //         } catch (Exception e) {
  //           LOG.warn("Cleanup failed for schema '{}': {}", schema, e.getMessage());
  //         }
  //       }
  //     } catch (Exception e) {
  //       LOG.warn("@AfterEach cleanup failed: {}", e.getMessage());
  //     }
  //   }

  // ── Test Cases ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("TC-1: Trino CREATE SCHEMA - 3-level nested namespace via dot notation")
  public void testTrinoCreateSchemaNestedNamespaceViaDotNotation() {
    // Use a unique top-level name to avoid collisions.
    String level1 = testRunPrefix + "_tc1";
    String level2 = "mid";
    String level3 = "leaf";

    // In Trino, nested namespace is represented with dots: "level1.level2.level3".
    String l1l2InTrino = level1 + "." + level2;
    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;

    // In Gravitino, the same namespace uses colons: "level1:level2:level3".
    String l1InGravitino = level1;
    String l1l2InGravitino = level1 + ":" + level2;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create the 3-level nested schema via Trino using dot notation.
    //    The server should auto-create ancestors (level1 and level1.level2).
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));

    // 2. Verify via Gravitino client: the deepest schema exists with colon-separated name.
    Schema loadedLeaf = catalog.asSchemas().loadSchema(l1l2l3InGravitino);
    Assertions.assertNotNull(loadedLeaf, "3-level nested schema should be loadable");
    Assertions.assertEquals(
        l1l2l3InGravitino,
        loadedLeaf.name(),
        "Schema name in Gravitino should use colon separator for all 3 levels");

    // 3. Verify via Gravitino client: the middle-level ancestor was auto-created.
    Schema loadedMid = catalog.asSchemas().loadSchema(l1l2InGravitino);
    Assertions.assertNotNull(loadedMid, "Middle-level schema should be auto-created");
    Assertions.assertEquals(l1l2InGravitino, loadedMid.name());

    // 4. Verify via Gravitino client: the top-level ancestor was auto-created.
    Schema loadedTop = catalog.asSchemas().loadSchema(l1InGravitino);
    Assertions.assertNotNull(loadedTop, "Top-level schema should be auto-created");
    Assertions.assertEquals(l1InGravitino, loadedTop.name());

    // 5. Verify via Trino: SHOW SCHEMAS lists all 3 levels as separate entries.
    Set<String> schemas = queryFirstColumnAsSet("SHOW SCHEMAS IN " + TRINO_CATALOG);
    Assertions.assertTrue(
        schemas.contains(level1), "SHOW SCHEMAS should list top-level: " + level1);
    Assertions.assertTrue(
        schemas.contains(l1l2InTrino), "SHOW SCHEMAS should list middle-level: " + l1l2InTrino);
    Assertions.assertTrue(
        schemas.contains(l1l2l3InTrino), "SHOW SCHEMAS should list leaf-level: " + l1l2l3InTrino);

    // 6. Verify each level is independently accessible for table operations.
    List<String> tablesTop =
        queryFirstColumn(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, level1));
    Assertions.assertNotNull(tablesTop, "SHOW TABLES at top level should succeed");

    List<String> tablesMid =
        queryFirstColumn(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertNotNull(tablesMid, "SHOW TABLES at middle level should succeed");

    List<String> tablesLeaf =
        queryFirstColumn(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertNotNull(tablesLeaf, "SHOW TABLES at leaf level should succeed");

    // 7. Cleanup: drop from deepest to shallowest.
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, level1));

    // 8. Verify cleanup via Gravitino client.
    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> catalog.asSchemas().loadSchema(l1l2l3InGravitino),
        "Leaf schema should no longer exist after drop");
    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> catalog.asSchemas().loadSchema(l1l2InGravitino),
        "Middle schema should no longer exist after drop");
  }

  @Test
  @DisplayName("TC-2: Trino SHOW SCHEMAS - all namespace levels visible as separate entries")
  public void testTrinoShowSchemasAllLevelsVisible() {
    // Use a unique top-level name to avoid collisions.
    String level1 = testRunPrefix + "_tc2";
    String level2 = "mid";
    String level3 = "leaf";

    String l1l2InTrino = level1 + "." + level2;
    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;

    // 1. Create 3-level nested schema via Trino.
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));

    // 2. SHOW SCHEMAS should list all 3 levels as separate entries.
    Set<String> schemas = queryFirstColumnAsSet("SHOW SCHEMAS IN " + TRINO_CATALOG);
    Assertions.assertTrue(
        schemas.contains(level1), "SHOW SCHEMAS should list top-level schema: " + level1);
    Assertions.assertTrue(
        schemas.contains(l1l2InTrino),
        "SHOW SCHEMAS should list middle-level schema: " + l1l2InTrino);
    Assertions.assertTrue(
        schemas.contains(l1l2l3InTrino),
        "SHOW SCHEMAS should list leaf-level schema: " + l1l2l3InTrino);

    // 3. Verify each level is independently accessible (SHOW TABLES succeeds at each).
    List<String> tablesTop =
        queryFirstColumn(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, level1));
    Assertions.assertTrue(tablesTop.isEmpty(), "Top-level schema should have no tables initially");

    List<String> tablesMid =
        queryFirstColumn(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertTrue(
        tablesMid.isEmpty(), "Middle-level schema should have no tables initially");

    List<String> tablesLeaf =
        queryFirstColumn(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertTrue(
        tablesLeaf.isEmpty(), "Leaf-level schema should have no tables initially");

    // 4. Create a table at each level to prove they are independently usable.
    executeTrinoDdl(
        String.format("CREATE TABLE %s.\"%s\".t_top (id bigint)", TRINO_CATALOG, level1));
    executeTrinoDdl(
        String.format("CREATE TABLE %s.\"%s\".t_mid (id bigint)", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(
        String.format("CREATE TABLE %s.\"%s\".t_leaf (id bigint)", TRINO_CATALOG, l1l2l3InTrino));

    // 5. Verify SHOW TABLES at each level returns only its own table.
    Set<String> tablesTopAfter =
        queryFirstColumnAsSet(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, level1));
    Assertions.assertTrue(tablesTopAfter.contains("t_top"), "Top level should contain t_top");
    Assertions.assertFalse(tablesTopAfter.contains("t_mid"), "Top level should NOT contain t_mid");
    Assertions.assertFalse(
        tablesTopAfter.contains("t_leaf"), "Top level should NOT contain t_leaf");

    Set<String> tablesMidAfter =
        queryFirstColumnAsSet(
            String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertTrue(tablesMidAfter.contains("t_mid"), "Mid level should contain t_mid");
    Assertions.assertFalse(tablesMidAfter.contains("t_top"), "Mid level should NOT contain t_top");
    Assertions.assertFalse(
        tablesMidAfter.contains("t_leaf"), "Mid level should NOT contain t_leaf");

    Set<String> tablesLeafAfter =
        queryFirstColumnAsSet(
            String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertTrue(tablesLeafAfter.contains("t_leaf"), "Leaf level should contain t_leaf");
    Assertions.assertFalse(
        tablesLeafAfter.contains("t_top"), "Leaf level should NOT contain t_top");
    Assertions.assertFalse(
        tablesLeafAfter.contains("t_mid"), "Leaf level should NOT contain t_mid");

    // 6. Cleanup: drop tables then schemas from deepest to shallowest.
    executeTrinoDdl(
        String.format("DROP TABLE IF EXISTS %s.\"%s\".t_leaf", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format("DROP TABLE IF EXISTS %s.\"%s\".t_mid", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(String.format("DROP TABLE IF EXISTS %s.\"%s\".t_top", TRINO_CATALOG, level1));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, level1));
  }

  @Test
  @DisplayName("TC-3: Trino CREATE TABLE at each level of 3-level nested namespace")
  public void testTrinoCreateTableAtEachLevel() {
    String level1 = testRunPrefix + "_tc3";
    String level2 = "mid";
    String level3 = "deep";

    String l1l2InTrino = level1 + "." + level2;
    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;

    String l1InGravitino = level1;
    String l1l2InGravitino = level1 + ":" + level2;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level nested schema (auto-creates all ancestors).
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));

    // 2. Create table at top level.
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".t_top (id bigint, name varchar)", TRINO_CATALOG, level1));
    executeTrinoDdl(
        String.format("INSERT INTO %s.\"%s\".t_top VALUES (1, 'top')", TRINO_CATALOG, level1));

    // 3. Create table at middle level.
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".t_mid (id bigint, name varchar)", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(
        String.format("INSERT INTO %s.\"%s\".t_mid VALUES (2, 'mid')", TRINO_CATALOG, l1l2InTrino));

    // 4. Create table at leaf level.
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".t_leaf (id bigint, name varchar)",
            TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".t_leaf VALUES (3, 'leaf')", TRINO_CATALOG, l1l2l3InTrino));

    // 5. Query each table and verify correct data.
    List<String> topResult =
        queryFirstColumn(String.format("SELECT name FROM %s.\"%s\".t_top", TRINO_CATALOG, level1));
    Assertions.assertEquals(1, topResult.size());
    Assertions.assertEquals("top", topResult.get(0));

    List<String> midResult =
        queryFirstColumn(
            String.format("SELECT name FROM %s.\"%s\".t_mid", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertEquals(1, midResult.size());
    Assertions.assertEquals("mid", midResult.get(0));

    List<String> leafResult =
        queryFirstColumn(
            String.format("SELECT name FROM %s.\"%s\".t_leaf", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertEquals(1, leafResult.size());
    Assertions.assertEquals("leaf", leafResult.get(0));

    // 6. Verify via Gravitino client: tables exist in their respective schemas.
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1InGravitino, "t_top")),
        "t_top should exist in top-level schema");
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2InGravitino, "t_mid")),
        "t_mid should exist in middle-level schema");
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2l3InGravitino, "t_leaf")),
        "t_leaf should exist in leaf-level schema");

    // 7. Verify table isolation: SHOW TABLES at each level returns only its own table.
    Set<String> tablesTop =
        queryFirstColumnAsSet(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, level1));
    Assertions.assertTrue(tablesTop.contains("t_top"));
    Assertions.assertFalse(tablesTop.contains("t_mid"));
    Assertions.assertFalse(tablesTop.contains("t_leaf"));

    Set<String> tablesMid =
        queryFirstColumnAsSet(
            String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertTrue(tablesMid.contains("t_mid"));
    Assertions.assertFalse(tablesMid.contains("t_top"));
    Assertions.assertFalse(tablesMid.contains("t_leaf"));

    Set<String> tablesLeaf =
        queryFirstColumnAsSet(
            String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertTrue(tablesLeaf.contains("t_leaf"));
    Assertions.assertFalse(tablesLeaf.contains("t_top"));
    Assertions.assertFalse(tablesLeaf.contains("t_mid"));

    // 8. Cleanup.
    executeTrinoDdl(
        String.format("DROP TABLE IF EXISTS %s.\"%s\".t_leaf", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format("DROP TABLE IF EXISTS %s.\"%s\".t_mid", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(String.format("DROP TABLE IF EXISTS %s.\"%s\".t_top", TRINO_CATALOG, level1));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, level1));
  }

  @Test
  @DisplayName(
      "TC-5: Trino DROP TABLE in nested namespace - schema hierarchy and sibling tables intact")
  public void testTrinoDropTableInNestedNamespaceSchemaIntact() {
    String level1 = testRunPrefix + "_tc5";
    String level2 = "mid";
    String level3 = "deep";

    String l1l2InTrino = level1 + "." + level2;
    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;

    String l1InGravitino = level1;
    String l1l2InGravitino = level1 + ":" + level2;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level namespace and tables at each level.
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".t_top (id bigint, name varchar)", TRINO_CATALOG, level1));
    executeTrinoDdl(
        String.format("INSERT INTO %s.\"%s\".t_top VALUES (1, 'top')", TRINO_CATALOG, level1));
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".t_mid (id bigint, name varchar)", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(
        String.format("INSERT INTO %s.\"%s\".t_mid VALUES (2, 'mid')", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".t_leaf (id bigint, name varchar)",
            TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".t_leaf VALUES (3, 'leaf')", TRINO_CATALOG, l1l2l3InTrino));

    // 2. Drop the table at the middle level.
    executeTrinoDdl(String.format("DROP TABLE %s.\"%s\".t_mid", TRINO_CATALOG, l1l2InTrino));

    // 3. Verify the middle-level schema still exists (not dropped with the table).
    Schema midSchema = catalog.asSchemas().loadSchema(l1l2InGravitino);
    Assertions.assertNotNull(midSchema, "Middle schema must still exist after dropping its table");

    // 4. Verify the table at top level is unaffected.
    List<String> topResult =
        queryFirstColumn(String.format("SELECT name FROM %s.\"%s\".t_top", TRINO_CATALOG, level1));
    Assertions.assertEquals(1, topResult.size());
    Assertions.assertEquals("top", topResult.get(0));

    // 5. Verify the table at leaf level is unaffected.
    List<String> leafResult =
        queryFirstColumn(
            String.format("SELECT name FROM %s.\"%s\".t_leaf", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertEquals(1, leafResult.size());
    Assertions.assertEquals("leaf", leafResult.get(0));

    // 6. Verify the dropped table is gone.
    Assertions.assertFalse(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2InGravitino, "t_mid")),
        "t_mid should no longer exist after drop");

    // 7. Verify all 3 schema levels still exist.
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1InGravitino));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2InGravitino));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2l3InGravitino));

    // 8. Cleanup.
    executeTrinoDdl(
        String.format("DROP TABLE IF EXISTS %s.\"%s\".t_leaf", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(String.format("DROP TABLE IF EXISTS %s.\"%s\".t_top", TRINO_CATALOG, level1));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, level1));
  }

  @Test
  @DisplayName("TC-6/7/8: Trino views at each level, drop view, drop leaf schema - combined")
  public void testTrinoViewsAtEachLevelAndDropLeafSchema() {
    String level1 = testRunPrefix + "_tc678";
    String level2 = "mid";
    String level3 = "deep";

    String l1l2InTrino = level1 + "." + level2;
    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;

    String l1InGravitino = level1;
    String l1l2InGravitino = level1 + ":" + level2;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level namespace.
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));

    // 2. Create base tables at each level with data.
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".base_top (id bigint, val varchar)", TRINO_CATALOG, level1));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".base_top VALUES (1, 'a'), (2, 'b')", TRINO_CATALOG, level1));

    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".base_mid (id bigint, val varchar)",
            TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".base_mid VALUES (3, 'c'), (4, 'd')",
            TRINO_CATALOG, l1l2InTrino));

    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".base_leaf (id bigint, val varchar)",
            TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".base_leaf VALUES (5, 'e'), (6, 'f')",
            TRINO_CATALOG, l1l2l3InTrino));

    // ── TC-6: Create views at each level ────────────────────────────────────

    // 3. Create view at top level.
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".v_top AS SELECT * FROM %s.\"%s\".base_top WHERE id > 1",
            TRINO_CATALOG, level1, TRINO_CATALOG, level1));

    // 4. Create view at middle level.
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".v_mid AS SELECT * FROM %s.\"%s\".base_mid WHERE id > 3",
            TRINO_CATALOG, l1l2InTrino, TRINO_CATALOG, l1l2InTrino));

    // 5. Create view at leaf level.
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".v_leaf AS SELECT * FROM %s.\"%s\".base_leaf WHERE id > 5",
            TRINO_CATALOG, l1l2l3InTrino, TRINO_CATALOG, l1l2l3InTrino));

    // 6. Query each view and verify filtered results.
    List<String> topViewResult =
        queryFirstColumn(String.format("SELECT val FROM %s.\"%s\".v_top", TRINO_CATALOG, level1));
    Assertions.assertEquals(1, topViewResult.size());
    Assertions.assertEquals("b", topViewResult.get(0));

    List<String> midViewResult =
        queryFirstColumn(
            String.format("SELECT val FROM %s.\"%s\".v_mid", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertEquals(1, midViewResult.size());
    Assertions.assertEquals("d", midViewResult.get(0));

    List<String> leafViewResult =
        queryFirstColumn(
            String.format("SELECT val FROM %s.\"%s\".v_leaf", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertEquals(1, leafViewResult.size());
    Assertions.assertEquals("f", leafViewResult.get(0));

    // 7. Verify views exist via Gravitino client.
    Assertions.assertTrue(
        catalog.asViewCatalog().viewExists(NameIdentifier.of(l1InGravitino, "v_top")),
        "v_top should exist in top-level schema");
    Assertions.assertTrue(
        catalog.asViewCatalog().viewExists(NameIdentifier.of(l1l2InGravitino, "v_mid")),
        "v_mid should exist in middle-level schema");
    Assertions.assertTrue(
        catalog.asViewCatalog().viewExists(NameIdentifier.of(l1l2l3InGravitino, "v_leaf")),
        "v_leaf should exist in leaf-level schema");

    // // ── TC-7: Drop view in nested namespace ─────────────────────────────────

    // // 8. Drop the view at middle level.
    // executeTrinoDdl(String.format("DROP VIEW %s.\"%s\".v_mid", TRINO_CATALOG, l1l2InTrino));

    // // 9. Verify the dropped view is gone.
    // Assertions.assertFalse(
    //     catalog.asViewCatalog().viewExists(NameIdentifier.of(l1l2InGravitino, "v_mid")),
    //     "v_mid should no longer exist after drop");

    // // 10. Verify base table at middle level still exists.
    // Assertions.assertTrue(
    //     catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2InGravitino, "base_mid")),
    //     "base_mid should still exist after dropping the view");

    // // 11. Verify views at other levels are unaffected.
    // List<String> topViewAfterDrop =
    //     queryFirstColumn(String.format("SELECT val FROM %s.\"%s\".v_top", TRINO_CATALOG,
    // level1));
    // Assertions.assertEquals(1, topViewAfterDrop.size());
    // Assertions.assertEquals("b", topViewAfterDrop.get(0));

    // List<String> leafViewAfterDrop =
    //     queryFirstColumn(
    //         String.format("SELECT val FROM %s.\"%s\".v_leaf", TRINO_CATALOG, l1l2l3InTrino));
    // Assertions.assertEquals(1, leafViewAfterDrop.size());
    // Assertions.assertEquals("f", leafViewAfterDrop.get(0));

    // // ── TC-8: Drop leaf schema ──────────────────────────────────────────────

    // // 12. Drop view and table at leaf level first (schema must be empty to drop).
    // executeTrinoDdl(String.format("DROP VIEW %s.\"%s\".v_leaf", TRINO_CATALOG, l1l2l3InTrino));
    // executeTrinoDdl(String.format("DROP TABLE %s.\"%s\".base_leaf", TRINO_CATALOG,
    // l1l2l3InTrino));

    // // 13. Drop the leaf schema.
    // executeTrinoDdl(String.format("DROP SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));

    // // 14. Verify leaf schema is gone.
    // Assertions.assertThrows(
    //     NoSuchSchemaException.class,
    //     () -> catalog.asSchemas().loadSchema(l1l2l3InGravitino),
    //     "Leaf schema should no longer exist after drop");

    // // 15. Verify middle and top levels still exist.
    // Assertions.assertNotNull(
    //     catalog.asSchemas().loadSchema(l1l2InGravitino),
    //     "Middle schema must still exist after dropping leaf");
    // Assertions.assertNotNull(
    //     catalog.asSchemas().loadSchema(l1InGravitino),
    //     "Top schema must still exist after dropping leaf");

    // // 16. Verify view and table at top level still work.
    // List<String> topViewFinal =
    //     queryFirstColumn(String.format("SELECT val FROM %s.\"%s\".v_top", TRINO_CATALOG,
    // level1));
    // Assertions.assertEquals(1, topViewFinal.size());
    // Assertions.assertEquals("b", topViewFinal.get(0));

    // // 17. Cleanup remaining objects.
    // executeTrinoDdl(String.format("DROP VIEW IF EXISTS %s.\"%s\".v_top", TRINO_CATALOG, level1));
    // executeTrinoDdl(
    //     String.format("DROP TABLE IF EXISTS %s.\"%s\".base_mid", TRINO_CATALOG, l1l2InTrino));
    // executeTrinoDdl(
    //     String.format("DROP TABLE IF EXISTS %s.\"%s\".base_top", TRINO_CATALOG, level1));
    // executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG,
    // l1l2InTrino));
    // executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, level1));
  }

  @Test
  @DisplayName("TC-9: Trino 4-level deep nested namespace with table operations")
  public void testTrino4LevelDeepNestedNamespace() {
    String l1 = testRunPrefix + "_tc9";
    String l2 = "l2";
    String l3 = "l3";
    String l4 = "l4";

    String l1l2InTrino = l1 + "." + l2;
    String l1l2l3InTrino = l1 + "." + l2 + "." + l3;
    String l1l2l3l4InTrino = l1 + "." + l2 + "." + l3 + "." + l4;

    String l1InGravitino = l1;
    String l1l2InGravitino = l1 + ":" + l2;
    String l1l2l3InGravitino = l1 + ":" + l2 + ":" + l3;
    String l1l2l3l4InGravitino = l1 + ":" + l2 + ":" + l3 + ":" + l4;

    // 1. Create 4-level nested schema via Trino (auto-creates all ancestors).
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3l4InTrino));

    // 2. Verify all 4 levels exist via Gravitino client.
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1InGravitino), "Level 1 should exist");
    Assertions.assertNotNull(
        catalog.asSchemas().loadSchema(l1l2InGravitino), "Level 2 should exist");
    Assertions.assertNotNull(
        catalog.asSchemas().loadSchema(l1l2l3InGravitino), "Level 3 should exist");
    Assertions.assertNotNull(
        catalog.asSchemas().loadSchema(l1l2l3l4InGravitino), "Level 4 should exist");

    // 3. Verify SHOW SCHEMAS lists all 4 levels.
    Set<String> schemas = queryFirstColumnAsSet("SHOW SCHEMAS IN " + TRINO_CATALOG);
    Assertions.assertTrue(schemas.contains(l1), "SHOW SCHEMAS should list level 1");
    Assertions.assertTrue(schemas.contains(l1l2InTrino), "SHOW SCHEMAS should list level 2");
    Assertions.assertTrue(schemas.contains(l1l2l3InTrino), "SHOW SCHEMAS should list level 3");
    Assertions.assertTrue(schemas.contains(l1l2l3l4InTrino), "SHOW SCHEMAS should list level 4");

    // 4. Create table at the deepest level (level 4).
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".deep_tbl (id bigint, val varchar)",
            TRINO_CATALOG, l1l2l3l4InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".deep_tbl VALUES (1, 'deep4'), (2, 'deep4b')",
            TRINO_CATALOG, l1l2l3l4InTrino));

    // 5. Query the table and verify data.
    List<String> result =
        queryFirstColumn(
            String.format(
                "SELECT val FROM %s.\"%s\".deep_tbl ORDER BY id", TRINO_CATALOG, l1l2l3l4InTrino));
    Assertions.assertEquals(2, result.size());
    Assertions.assertEquals("deep4", result.get(0));
    Assertions.assertEquals("deep4b", result.get(1));

    // 6. Verify table exists via Gravitino client at the correct schema.
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2l3l4InGravitino, "deep_tbl")),
        "deep_tbl should exist in 4th-level schema");

    // 7. Verify table is NOT visible at other levels.
    Set<String> tablesL3 =
        queryFirstColumnAsSet(
            String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertFalse(
        tablesL3.contains("deep_tbl"), "deep_tbl should NOT be visible at level 3");

    // 8. Cleanup: drop from deepest to shallowest.
    executeTrinoDdl(
        String.format("DROP TABLE IF EXISTS %s.\"%s\".deep_tbl", TRINO_CATALOG, l1l2l3l4InTrino));
    executeTrinoDdl(
        String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2l3l4InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA IF EXISTS %s.\"%s\"", TRINO_CATALOG, l1));
  }

  @Test
  @DisplayName(
      "TC-10: Cross-engine - table created via Spark in 3-level namespace readable via Trino")
  public void testCrossEngineSparkCreateTrinoRead() {
    String level1 = testRunPrefix + "_tc10";
    String level2 = "mid";
    String level3 = "engine";

    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;

    // 1. Create 3-level namespace and table via Spark.
    SparkSession spark = newSparkSession("tc10-spark");
    try {
      spark.sql(String.format("CREATE DATABASE %s.%s", SPARK_CATALOG, level1));
      spark.sql(String.format("CREATE DATABASE %s.%s.%s", SPARK_CATALOG, level1, level2));
      spark.sql(
          String.format("CREATE DATABASE %s.%s.%s.%s", SPARK_CATALOG, level1, level2, level3));
      spark.sql(
          String.format(
              "CREATE TABLE %s.%s.%s.%s.spark_tbl (id bigint, val string) USING iceberg",
              SPARK_CATALOG, level1, level2, level3));
      spark.sql(
          String.format(
              "INSERT INTO %s.%s.%s.%s.spark_tbl VALUES (1, 'from_spark'), (2, 'hello_spark')",
              SPARK_CATALOG, level1, level2, level3));
    } finally {
      spark.close();
    }

    // 2. Query via Trino and verify data matches.
    List<String> trinoResult =
        queryFirstColumn(
            String.format(
                "SELECT val FROM %s.\"%s\".spark_tbl ORDER BY id", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertEquals(
        2, trinoResult.size(), "Trino should see 2 rows from Spark-created table");
    Assertions.assertEquals("from_spark", trinoResult.get(0));
    Assertions.assertEquals("hello_spark", trinoResult.get(1));

    // 3. Verify via Gravitino client.
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2l3InGravitino, "spark_tbl")),
        "spark_tbl should exist in Gravitino at the 3-level schema");
  }

  @Test
  @DisplayName(
      "TC-11: Cross-engine - table created via Trino in 3-level namespace readable via Spark")
  public void testCrossEngineTrinoCreateSparkRead() {
    String level1 = testRunPrefix + "_tc11";
    String level2 = "mid";
    String level3 = "engine";

    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level namespace and table via Trino.
    executeTrinoDdl(String.format("CREATE SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".trino_tbl (id bigint, val varchar)",
            TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".trino_tbl VALUES (10, 'from_trino'), (20, 'hello_trino')",
            TRINO_CATALOG, l1l2l3InTrino));

    // 2. Query via Spark and verify data matches.
    SparkSession spark = newSparkSession("tc11-spark");
    try {
      List<Row> rows =
          spark
              .sql(
                  String.format(
                      "SELECT val FROM %s.%s.%s.%s.trino_tbl ORDER BY id",
                      SPARK_CATALOG, level1, level2, level3))
              .collectAsList();
      Assertions.assertEquals(2, rows.size(), "Spark should see 2 rows from Trino-created table");
      Assertions.assertEquals("from_trino", rows.get(0).getString(0));
      Assertions.assertEquals("hello_trino", rows.get(1).getString(0));
    } finally {
      spark.close();
    }

    // 3. Verify via Gravitino client.
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2l3InGravitino, "trino_tbl")),
        "trino_tbl should exist in Gravitino at the 3-level schema");
  }

  @Test
  @DisplayName("TC-12: Gravitino client creates 3-level namespace - Trino reads and creates table")
  public void testGravitinoClientCreatesNamespaceTrinoCreatesTable() {
    String level1 = testRunPrefix + "_tc12";
    String level2 = "mid";
    String level3 = "child";

    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;
    String l1InGravitino = level1;
    String l1l2InGravitino = level1 + ":" + level2;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level nested namespace via Gravitino Java client.
    catalog
        .asSchemas()
        .createSchema(l1l2l3InGravitino, "created by gravitino client", new HashMap<>());

    // 2. Verify all levels exist via Gravitino client.
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1InGravitino));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2InGravitino));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2l3InGravitino));

    // 3. Verify via Trino: SHOW SCHEMAS lists the top-level schema.
    Set<String> schemas = queryFirstColumnAsSet("SHOW SCHEMAS IN " + TRINO_CATALOG);
    Assertions.assertTrue(
        schemas.contains(level1),
        "Trino SHOW SCHEMAS should list the top-level schema created by Gravitino client");

    // 4. Trino creates a table in the Gravitino-client-created namespace.
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".trino_tbl (id bigint, name varchar)",
            TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".trino_tbl VALUES (1, 'from_trino'), (2, 'gravitino_ns')",
            TRINO_CATALOG, l1l2l3InTrino));

    // 5. Query via Trino and verify data.
    List<String> result =
        queryFirstColumn(
            String.format(
                "SELECT name FROM %s.\"%s\".trino_tbl ORDER BY id", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertEquals(2, result.size());
    Assertions.assertEquals("from_trino", result.get(0));
    Assertions.assertEquals("gravitino_ns", result.get(1));

    // 6. Verify via Gravitino client: table exists in the correct schema.
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2l3InGravitino, "trino_tbl")),
        "trino_tbl should exist in the Gravitino-client-created 3-level schema");
  }

  @Test
  @DisplayName("TC-13: Gravitino client creates 3-level namespace - Trino creates view")
  public void testGravitinoClientCreatesNamespaceTrinoCreatesView() {
    String level1 = testRunPrefix + "_tc13";
    String level2 = "mid";
    String level3 = "sub";

    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level nested namespace via Gravitino Java client.
    catalog.asSchemas().createSchema(l1l2l3InGravitino, "for view test", new HashMap<>());

    // 2. Create a base table via Trino in the Gravitino-client-created namespace.
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".base_tbl (id bigint, val varchar)",
            TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".base_tbl VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma')",
            TRINO_CATALOG, l1l2l3InTrino));

    // 3. Create a view via Trino in the same namespace.
    executeTrinoDdl(
        String.format(
            "CREATE VIEW %s.\"%s\".my_view AS "
                + "SELECT id, val FROM %s.\"%s\".base_tbl WHERE id > 1",
            TRINO_CATALOG, l1l2l3InTrino, TRINO_CATALOG, l1l2l3InTrino));

    // 4. Query the view via Trino and verify filtered results.
    List<String> viewResult =
        queryFirstColumn(
            String.format(
                "SELECT val FROM %s.\"%s\".my_view ORDER BY id", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertEquals(2, viewResult.size());
    Assertions.assertEquals("beta", viewResult.get(0));
    Assertions.assertEquals("gamma", viewResult.get(1));

    // 5. Verify via Gravitino client: view exists in the correct schema.
    Assertions.assertTrue(
        catalog.asViewCatalog().viewExists(NameIdentifier.of(l1l2l3InGravitino, "my_view")),
        "my_view should exist in the Gravitino-client-created 3-level schema");
  }

  @Test
  @DisplayName(
      "TC-14: Gravitino client creates 3-level namespace - Spark creates table and cross-level view")
  public void testGravitinoClientCreatesNamespaceSparkCreatesTableAndView() {
    String level1 = testRunPrefix + "_tc14";
    String level2 = "mid";
    String level3 = "child";

    String l1InGravitino = level1;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level nested namespace via Gravitino Java client.
    catalog.asSchemas().createSchema(l1l2l3InGravitino, "for spark test", new HashMap<>());

    // 2. Verify all levels exist via Gravitino client.
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1InGravitino));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(level1 + ":" + level2));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2l3InGravitino));

    SparkSession spark = newSparkSession("tc14-spark");
    try {
      // 3. Create table in the deepest level via Spark.
      spark.sql(
          String.format(
              "CREATE TABLE %s.%s.%s.%s.spark_tbl (id bigint, val string) USING iceberg",
              SPARK_CATALOG, level1, level2, level3));
      spark.sql(
          String.format(
              "INSERT INTO %s.%s.%s.%s.spark_tbl VALUES (1, 'from_spark'), (2, 'hello')",
              SPARK_CATALOG, level1, level2, level3));

      // 4. Query the table via Spark and verify data.
      List<Row> tableRows =
          spark
              .sql(
                  String.format(
                      "SELECT val FROM %s.%s.%s.%s.spark_tbl ORDER BY id",
                      SPARK_CATALOG, level1, level2, level3))
              .collectAsList();
      Assertions.assertEquals(2, tableRows.size());
      Assertions.assertEquals("from_spark", tableRows.get(0).getString(0));
      Assertions.assertEquals("hello", tableRows.get(1).getString(0));

      // 5. Create a view at the top level that references the table in the deepest level.
      spark.sql(
          String.format(
              "CREATE VIEW %s.%s.testview AS SELECT * FROM %s.%s.%s.%s.spark_tbl",
              SPARK_CATALOG, level1, SPARK_CATALOG, level1, level2, level3));

      // 6. Query the cross-level view and verify data.
      List<Row> viewRows =
          spark
              .sql(
                  String.format(
                      "SELECT val FROM %s.%s.testview ORDER BY id", SPARK_CATALOG, level1))
              .collectAsList();
      Assertions.assertEquals(2, viewRows.size());
      Assertions.assertEquals("from_spark", viewRows.get(0).getString(0));
      Assertions.assertEquals("hello", viewRows.get(1).getString(0));
    } finally {
      spark.close();
    }

    // 7. Verify via Gravitino client: table and view exist in their respective schemas.
    Assertions.assertTrue(
        catalog.asTableCatalog().tableExists(NameIdentifier.of(l1l2l3InGravitino, "spark_tbl")),
        "spark_tbl should exist in the 3-level schema");
    Assertions.assertTrue(
        catalog.asViewCatalog().viewExists(NameIdentifier.of(l1InGravitino, "testview")),
        "testview should exist in the top-level schema");
  }

  @Test
  @DisplayName(
      "TC-15/16/17: Drop leaf namespace, drop middle layer rejected then succeeds after cleanup")
  public void testDropLeafAndMiddleLayerNamespace() {
    String level1 = testRunPrefix + "_tc15";
    String level2 = "parent";
    String level3 = "child";

    String l1l2InTrino = level1 + "." + level2;
    String l1l2l3InTrino = level1 + "." + level2 + "." + level3;

    String l1InGravitino = level1;
    String l1l2InGravitino = level1 + ":" + level2;
    String l1l2l3InGravitino = level1 + ":" + level2 + ":" + level3;

    // 1. Create 3-level namespace via Gravitino client and create tables via Trino.
    catalog.asSchemas().createSchema(l1l2l3InGravitino, "for drop test", new HashMap<>());
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".tbl_mid (id bigint, val varchar)", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".tbl_mid VALUES (1, 'mid_data')", TRINO_CATALOG, l1l2InTrino));
    executeTrinoDdl(
        String.format(
            "CREATE TABLE %s.\"%s\".tbl_leaf (id bigint, val varchar)",
            TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(
        String.format(
            "INSERT INTO %s.\"%s\".tbl_leaf VALUES (2, 'leaf_data')",
            TRINO_CATALOG, l1l2l3InTrino));

    // ── TC-16: Drop middle layer rejected when children exist ───────────────

    // 2. Attempt to drop middle layer via Gravitino client — should fail because it has tables
    //    and/or child namespaces. Iceberg throws NonEmptySchemaException.
    Assertions.assertThrows(
        Exception.class,
        () -> catalog.asSchemas().dropSchema(l1l2InGravitino, false),
        "Dropping middle-layer schema should throw when it contains tables or child namespaces");

    // 3. Verify everything is still intact.
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2InGravitino));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2l3InGravitino));
    List<String> midData =
        queryFirstColumn(
            String.format("SELECT val FROM %s.\"%s\".tbl_mid", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertEquals("mid_data", midData.get(0), "tbl_mid data should still be accessible");
    List<String> leafData =
        queryFirstColumn(
            String.format("SELECT val FROM %s.\"%s\".tbl_leaf", TRINO_CATALOG, l1l2l3InTrino));
    Assertions.assertEquals(
        "leaf_data", leafData.get(0), "tbl_leaf data should still be accessible");

    // ── TC-15: Drop leaf namespace — Trino cannot access tables ─────────────

    // 4. Drop table in leaf, then drop the leaf schema.
    executeTrinoDdl(String.format("DROP TABLE %s.\"%s\".tbl_leaf", TRINO_CATALOG, l1l2l3InTrino));
    executeTrinoDdl(String.format("DROP SCHEMA %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino));

    // 5. Verify leaf schema is gone via Gravitino client.
    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> catalog.asSchemas().loadSchema(l1l2l3InGravitino),
        "Leaf schema should no longer exist after drop");

    // 6. Verify Trino cannot access the dropped leaf schema.
    Assertions.assertThrows(
        AssertionError.class,
        () ->
            queryFirstColumn(
                String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2l3InTrino)),
        "Trino should fail when accessing dropped leaf schema");

    // 7. Verify middle and top levels still exist and are functional.
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1l2InGravitino));
    Assertions.assertNotNull(catalog.asSchemas().loadSchema(l1InGravitino));
    List<String> midDataAfter =
        queryFirstColumn(
            String.format("SELECT val FROM %s.\"%s\".tbl_mid", TRINO_CATALOG, l1l2InTrino));
    Assertions.assertEquals(
        "mid_data", midDataAfter.get(0), "tbl_mid should still be accessible after leaf drop");

    // ── TC-17: Drop middle layer succeeds after children removed ────────────

    // 8. Now drop the table in middle layer, then drop the middle schema.
    executeTrinoDdl(String.format("DROP TABLE %s.\"%s\".tbl_mid", TRINO_CATALOG, l1l2InTrino));
    boolean dropMiddleResult = catalog.asSchemas().dropSchema(l1l2InGravitino, false);
    Assertions.assertTrue(
        dropMiddleResult,
        "Dropping middle-layer schema should succeed after child namespace is removed");

    // 9. Verify middle schema is gone.
    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () -> catalog.asSchemas().loadSchema(l1l2InGravitino),
        "Middle schema should no longer exist after drop");

    // 10. Verify Trino reflects the change.
    Assertions.assertThrows(
        AssertionError.class,
        () ->
            queryFirstColumn(String.format("SHOW TABLES IN %s.\"%s\"", TRINO_CATALOG, l1l2InTrino)),
        "Trino should fail when accessing dropped middle schema");

    // 11. Verify top-level schema still exists.
    Assertions.assertNotNull(
        catalog.asSchemas().loadSchema(l1InGravitino),
        "Top-level schema must still exist after dropping middle layer");
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Executes a Trino DDL/DML statement (no result set expected). */
  private static void executeTrinoDdl(String sql) {
    try (Statement stmt = trinoConnection.createStatement()) {
      stmt.execute(sql);
    } catch (SQLException e) {
      throw new AssertionError("Trino SQL failed: " + sql, e);
    }
  }

  /** Executes a Trino DDL/DML statement, swallowing any exception (cleanup helper). */
  private static void executeTrinoDdlQuietly(String sql) {
    try (Statement stmt = trinoConnection.createStatement()) {
      stmt.execute(sql);
    } catch (SQLException e) {
      LOG.warn("Trino SQL failed (ignored during cleanup): {} — {}", sql, e.getMessage());
    }
  }

  /** Executes a Trino query and returns all values from the first column as a list of strings. */
  private static List<String> queryFirstColumn(String sql) {
    List<String> results = new ArrayList<>();
    try (Statement stmt = trinoConnection.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        results.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new AssertionError("Trino query failed: " + sql, e);
    }
    return results;
  }

  /** Executes a Trino query and returns all values from the first column as a set of strings. */
  private static Set<String> queryFirstColumnAsSet(String sql) {
    return new HashSet<>(queryFirstColumn(sql));
  }

  /** Required env var; fails fast at @BeforeAll if missing. */
  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          "Required environment variable not set: "
              + name
              + ". Set AWS/OAuth2 env vars for the E2E test environment.");
    }
    return value;
  }

  /**
   * Builds a SparkSession with the Iceberg REST catalog registered under {@link #SPARK_CATALOG},
   * authenticated via OAuth2 client_credentials grant against the same Keycloak used by the test.
   */
  private static SparkSession newSparkSession(String appName) {
    String oauth2ServerUri = requireEnv("OAUTH2_SERVER_URI");
    String oauth2ClientId = requireEnv("OAUTH2_CLIENT_ID");
    String oauth2ClientSecret = requireEnv("OAUTH2_CLIENT_SECRET");
    String oauth2Realm = System.getenv().getOrDefault("OAUTH2_REALM", DEFAULT_OAUTH2_REALM);
    String oauth2Scope = System.getenv().getOrDefault("OAUTH2_SCOPE", DEFAULT_OAUTH2_SCOPE);
    String oauth2TokenUri =
        String.format("%s/realms/%s/protocol/openid-connect/token", oauth2ServerUri, oauth2Realm);

    String ircBaseUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");

    String base = "spark.sql.catalog." + SPARK_CATALOG;
    SparkConf conf =
        new SparkConf()
            .set(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .set("spark.sql.defaultCatalog", SPARK_CATALOG)
            .set(base, "org.apache.iceberg.spark.SparkCatalog")
            .set(base + ".type", "rest")
            .set(base + ".uri", ircBaseUri)
            .set(base + ".prefix", CATALOG_NAME)
            .set(base + ".warehouse", CATALOG_NAME)
            .set(base + ".header.X-Iceberg-Access-Delegation", "vended-credentials")
            .set(base + ".rest.auth.type", "oauth2")
            .set(base + ".credential", oauth2ClientId + ":" + oauth2ClientSecret)
            .set(base + ".oauth2-server-uri", oauth2TokenUri)
            .set(base + ".scope", oauth2Scope)
            .set(base + ".token-exchange-enabled", "false")
            .set("spark.locality.wait.node", "0");
    return SparkSession.builder().master("local[2]").appName(appName).config(conf).getOrCreate();
  }
}
