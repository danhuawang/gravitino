/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E tests for hierarchical schema APIs when the Gravitino server is configured with {@code
 * gravitino.schema.separator=#}.
 *
 * <p><b>Prerequisites</b>: Before running these tests, the Gravitino server must be configured with
 * the following property:
 *
 * <pre>
 *   gravitino.schema.separator = #
 * </pre>
 *
 * <p>This test class verifies that hierarchical schema operations work correctly with {@code #} as
 * the separator instead of the default {@code :}.
 */
@DisplayName("Hierarchical Schema Tests with '#' Separator")
@Disabled(
    "Requires Gravitino server configured with gravitino.schema.separator=#. Enable manually.")
public class TestHierarchicalSchemaHashSeparatorIT {

  private static final Logger LOG =
      LoggerFactory.getLogger(TestHierarchicalSchemaHashSeparatorIT.class);

  /** The separator configured on the Gravitino server for this test. */
  private static final String SEPARATOR = "#";

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog icebergCatalog;
  private static String icebergCatalogName;

  /** Unique prefix for this test run to avoid collisions across parallel runs. */
  private static String testRunPrefix;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");
    String metalakeName = System.getProperty("gravitino.metalake", "test");

    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(simpleUser).build();
    metalake = adminClient.loadMetalake(metalakeName);

    // Load the pre-existing Iceberg catalog from the environment
    icebergCatalogName = System.getProperty("gravitino.irc.catalog", "catalog_1");
    icebergCatalog = metalake.loadCatalog(icebergCatalogName);

    testRunPrefix = RandomNameUtils.genRandomName("hash");
    LOG.info(
        "TestHierarchicalSchemaHashSeparatorIT setup complete: metalake={}, catalog={}, prefix={}",
        metalakeName,
        icebergCatalogName,
        testRunPrefix);
  }

  @AfterAll
  public static void teardown() {
    if (adminClient != null) {
      adminClient.close();
    }
  }

  // ── Basic hierarchical schema creation with '#' separator ─────────────────

  @Test
  @DisplayName("Creating hierarchical schema with '#' separator should succeed on Iceberg")
  public void testCreateHierarchicalSchemaWithHashSeparator() {
    String level1 = testRunPrefix + "_l1";
    String level2 = level1 + SEPARATOR + "child";
    String level3 = level2 + SEPARATOR + "grandchild";

    // Create level-1 schema
    icebergCatalog.asSchemas().createSchema(level1, "top level", Collections.emptyMap());
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(level1));

    // Create level-2 schema (parent exists)
    icebergCatalog.asSchemas().createSchema(level2, "second level", Collections.emptyMap());
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(level2));

    // Create level-3 schema (parent exists)
    icebergCatalog.asSchemas().createSchema(level3, "third level", Collections.emptyMap());
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(level3));

    // Clean up
    icebergCatalog.asSchemas().dropSchema(level1, false);
  }

  @Test
  @DisplayName("Creating hierarchical schema without parent should implicitly create parent")
  public void testCreateHierarchicalSchemaWithoutParentImplicitCreation() {
    String parent = testRunPrefix + "_imp";
    String child = parent + SEPARATOR + "child";

    // Create child directly without creating parent first
    icebergCatalog.asSchemas().createSchema(child, "child without parent", Collections.emptyMap());

    // Both parent and child should now exist
    Assertions.assertNotNull(
        icebergCatalog.asSchemas().loadSchema(child), "Child schema should exist");
    Assertions.assertNotNull(
        icebergCatalog.asSchemas().loadSchema(parent),
        "Parent schema should be implicitly created");

    // Clean up
    icebergCatalog.asSchemas().dropSchema(parent, false);
  }

  // ── Listing schemas with '#' separator ────────────────────────────────────

  @Test
  @DisplayName("Listing direct children of a parent schema with '#' separator")
  public void testListSchemasWithParentSchemaHashSeparator() {
    String parent = testRunPrefix + "_lp";
    String child1 = parent + SEPARATOR + "child1";
    String child2 = parent + SEPARATOR + "child2";
    String grandchild = child1 + SEPARATOR + "gc";

    // Create hierarchy
    icebergCatalog.asSchemas().createSchema(parent, "parent", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child1, "child1", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child2, "child2", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(grandchild, "grandchild", Collections.emptyMap());

    // List direct children of parent — should return child1 and child2, NOT grandchild
    String[] children = icebergCatalog.asSchemas().listSchemas(parent);
    Set<String> childSet = Arrays.stream(children).collect(Collectors.toSet());
    LOG.info("Children of '{}': {}", parent, childSet);

    Assertions.assertTrue(childSet.contains(child1), "Should contain child1, got: " + childSet);
    Assertions.assertTrue(childSet.contains(child2), "Should contain child2, got: " + childSet);
    Assertions.assertFalse(
        childSet.contains(grandchild),
        "Should NOT contain grandchild (not a direct child), got: " + childSet);

    // List direct children of child1 — should return grandchild only
    String[] grandchildren = icebergCatalog.asSchemas().listSchemas(child1);
    Set<String> gcSet = Arrays.stream(grandchildren).collect(Collectors.toSet());
    Assertions.assertTrue(gcSet.contains(grandchild), "Should contain grandchild, got: " + gcSet);
    Assertions.assertEquals(1, gcSet.size(), "Should have exactly 1 grandchild");

    // Clean up
    icebergCatalog.asSchemas().dropSchema(parent, false);
  }

  @Test
  @DisplayName("Top-level listing should not include nested schemas with '#' separator")
  public void testListTopLevelSchemasExcludesNested() {
    String topLevel = testRunPrefix + "_top";
    String nested = topLevel + SEPARATOR + "nested";

    icebergCatalog.asSchemas().createSchema(topLevel, "top", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(nested, "nested", Collections.emptyMap());

    // List top-level schemas (no parentSchema) — should include topLevel but NOT nested
    String[] topSchemas = icebergCatalog.asSchemas().listSchemas();
    Set<String> topSet = Arrays.stream(topSchemas).collect(Collectors.toSet());

    Assertions.assertTrue(
        topSet.contains(topLevel), "Top-level list should contain '" + topLevel + "'");
    Assertions.assertFalse(
        topSet.contains(nested),
        "Top-level list should NOT contain nested schema '" + nested + "'");

    // Clean up
    icebergCatalog.asSchemas().dropSchema(topLevel, false);
  }

  // ── Invalid name patterns with '#' separator ──────────────────────────────

  @Test
  @DisplayName("Creating schema with empty segment (e.g. 'a##b') should fail")
  public void testCreateSchemaWithEmptySegmentHashSeparator() {
    String invalidName = testRunPrefix + "_a" + SEPARATOR + SEPARATOR + "b";

    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                icebergCatalog
                    .asSchemas()
                    .createSchema(invalidName, "empty segment", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating schema with empty segment: {}", exception.getMessage());
    Assertions.assertTrue(
        exception.getMessage().contains("empty segment")
            || exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("unsupported"),
        "Should indicate empty segment is invalid, but got: " + exception.getMessage());
  }

  @Test
  @DisplayName("Creating schema with leading '#' separator should fail")
  public void testCreateSchemaWithLeadingHashSeparator() {
    String invalidName = SEPARATOR + "a" + SEPARATOR + testRunPrefix;

    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                icebergCatalog
                    .asSchemas()
                    .createSchema(invalidName, "leading separator", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating schema with leading '#': {}", exception.getMessage());
    Assertions.assertTrue(
        exception.getMessage().contains("empty segment")
            || exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("unsupported"),
        "Should indicate leading separator is invalid, but got: " + exception.getMessage());
  }

  @Test
  @DisplayName("Creating schema with trailing '#' separator should fail")
  public void testCreateSchemaWithTrailingHashSeparator() {
    String invalidName = testRunPrefix + "_a" + SEPARATOR + "b" + SEPARATOR;

    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                icebergCatalog
                    .asSchemas()
                    .createSchema(invalidName, "trailing separator", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating schema with trailing '#': {}", exception.getMessage());
    Assertions.assertTrue(
        exception.getMessage().contains("empty segment")
            || exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("unsupported"),
        "Should indicate trailing separator is invalid, but got: " + exception.getMessage());
  }

  // ── Colon in schema name should be treated as regular character ────────────

  @Test
  @DisplayName("Schema name with ':' should be treated as flat name when separator is '#'")
  public void testColonInSchemaNameTreatedAsFlatWhenSeparatorIsHash() {
    // When separator is '#', the colon ':' is just a regular character in the name.
    // However, ':' is not in the default name pattern (^\w[\w/=-]{0,63}$),
    // so this should fail with an illegal name error.
    String nameWithColon = testRunPrefix + "_a:b:c";

    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                icebergCatalog
                    .asSchemas()
                    .createSchema(nameWithColon, "colon as flat char", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating schema with ':' (non-separator): {}",
        exception.getMessage());
    Assertions.assertTrue(
        exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("not supported")
            || exception.getMessage().contains("unsupported"),
        "Error message should indicate the schema name is illegal, but got: "
            + exception.getMessage());
  }

  // ── Load and drop with '#' separator ──────────────────────────────────────

  @Test
  @DisplayName("Loading a non-existent hierarchical schema should throw NoSuchSchemaException")
  public void testLoadNonExistentHierarchicalSchema() {
    String nonExistent = testRunPrefix + "_ne" + SEPARATOR + "child" + SEPARATOR + "grandchild";

    Assertions.assertThrows(
        NoSuchSchemaException.class, () -> icebergCatalog.asSchemas().loadSchema(nonExistent));
  }

  @Test
  @DisplayName("Listing schemas with non-existent parentSchema should throw")
  public void testListSchemasWithNonExistentParent() {
    String nonExistentParent = testRunPrefix + "_ghost";

    Exception exception =
        Assertions.assertThrows(
            Exception.class, () -> icebergCatalog.asSchemas().listSchemas(nonExistentParent));
    LOG.info(
        "Expected exception when listing with non-existent parent: {}", exception.getMessage());
  }

  // ── Tables and views in hierarchical schemas with '#' separator ────────────

  @Test
  @DisplayName("Create tables and views in both parent and child schemas with '#' separator")
  public void testCreateTablesAndViewsInParentAndChildSchemas() {
    String parent = testRunPrefix + "_tv";
    String child = parent + SEPARATOR + "child";

    // Create parent and child schemas
    icebergCatalog.asSchemas().createSchema(parent, "parent schema", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child, "child schema", Collections.emptyMap());

    // ── Create table in parent schema ──
    String parentTableName = "parent_tbl";
    icebergCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(parent, parentTableName),
            new Column[] {
              Column.of("id", Types.IntegerType.get(), "id column"),
              Column.of("name", Types.StringType.get(), "name column"),
            },
            "table in parent schema",
            Collections.emptyMap());
    Assertions.assertNotNull(
        icebergCatalog.asTableCatalog().loadTable(NameIdentifier.of(parent, parentTableName)),
        "Table in parent schema should be loadable");

    // ── Create view in parent schema ──
    String parentViewName = "parent_view";
    icebergCatalog
        .asViewCatalog()
        .createView(
            NameIdentifier.of(parent, parentViewName),
            "view in parent schema",
            new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
            new SQLRepresentation[] {
              SQLRepresentation.builder()
                  .withDialect("spark")
                  .withSql("SELECT id FROM " + parentTableName)
                  .build()
            },
            null,
            null,
            Collections.emptyMap());
    Assertions.assertNotNull(
        icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(parent, parentViewName)),
        "View in parent schema should be loadable");

    // ── Create table in child schema ──
    String childTableName = "child_tbl";
    icebergCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(child, childTableName),
            new Column[] {
              Column.of("id", Types.IntegerType.get(), "id column"),
              Column.of("value", Types.StringType.get(), "value column"),
            },
            "table in child schema",
            Collections.emptyMap());
    Assertions.assertNotNull(
        icebergCatalog.asTableCatalog().loadTable(NameIdentifier.of(child, childTableName)),
        "Table in child schema should be loadable");

    // ── Create view in child schema ──
    String childViewName = "child_view";
    icebergCatalog
        .asViewCatalog()
        .createView(
            NameIdentifier.of(child, childViewName),
            "view in child schema",
            new Column[] {Column.of("id", Types.IntegerType.get(), "id column")},
            new SQLRepresentation[] {
              SQLRepresentation.builder()
                  .withDialect("spark")
                  .withSql("SELECT id FROM " + childTableName)
                  .build()
            },
            null,
            null,
            Collections.emptyMap());
    Assertions.assertNotNull(
        icebergCatalog.asViewCatalog().loadView(NameIdentifier.of(child, childViewName)),
        "View in child schema should be loadable");

    // ── Verify listing tables and views in each schema ──
    Set<String> parentTables =
        Arrays.stream(icebergCatalog.asTableCatalog().listTables(Namespace.of(parent)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        parentTables.contains(parentTableName),
        "Parent schema should list its table, got: " + parentTables);
    Assertions.assertFalse(
        parentTables.contains(childTableName),
        "Parent schema should NOT list child's table, got: " + parentTables);

    Set<String> parentViews =
        Arrays.stream(icebergCatalog.asViewCatalog().listViews(Namespace.of(parent)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        parentViews.contains(parentViewName),
        "Parent schema should list its view, got: " + parentViews);
    Assertions.assertFalse(
        parentViews.contains(childViewName),
        "Parent schema should NOT list child's view, got: " + parentViews);

    Set<String> childTables =
        Arrays.stream(icebergCatalog.asTableCatalog().listTables(Namespace.of(child)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        childTables.contains(childTableName),
        "Child schema should list its table, got: " + childTables);
    Assertions.assertFalse(
        childTables.contains(parentTableName),
        "Child schema should NOT list parent's table, got: " + childTables);

    Set<String> childViews =
        Arrays.stream(icebergCatalog.asViewCatalog().listViews(Namespace.of(child)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        childViews.contains(childViewName),
        "Child schema should list its view, got: " + childViews);
    Assertions.assertFalse(
        childViews.contains(parentViewName),
        "Child schema should NOT list parent's view, got: " + childViews);

    // ── Clean up ──
    icebergCatalog.asViewCatalog().dropView(NameIdentifier.of(child, childViewName));
    icebergCatalog.asTableCatalog().dropTable(NameIdentifier.of(child, childTableName));
    icebergCatalog.asViewCatalog().dropView(NameIdentifier.of(parent, parentViewName));
    icebergCatalog.asTableCatalog().dropTable(NameIdentifier.of(parent, parentTableName));
    icebergCatalog.asSchemas().dropSchema(child, false);
    icebergCatalog.asSchemas().dropSchema(parent, false);
  }
}
