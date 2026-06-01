/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.schema;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.function.FunctionCatalog;
import org.apache.gravitino.function.FunctionDefinition;
import org.apache.gravitino.function.FunctionDefinitions;
import org.apache.gravitino.function.FunctionImpl;
import org.apache.gravitino.function.FunctionImpls;
import org.apache.gravitino.function.FunctionParams;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E tests for hierarchical (multi-level) schema APIs. Covers corner cases such as:
 *
 * <ul>
 *   <li>Creating a hierarchical schema (e.g. "a:b:c") on a non-Iceberg catalog should fail.
 *   <li>Listing schemas with parentSchema on a non-Iceberg catalog should fail or return empty.
 *   <li>Iceberg catalog should support hierarchical schema creation and listing.
 *   <li>Creating a hierarchical schema without an existing parent should fail on Iceberg.
 *   <li>Listing schemas with a non-existent parentSchema should throw NoSuchSchemaException.
 * </ul>
 */
@DisplayName("Hierarchical Schema API Corner Case Tests")
public class TestHierarchicalSchemaIT {

  private static final Logger LOG = LoggerFactory.getLogger(TestHierarchicalSchemaIT.class);

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static String metalakeName;

  private static Catalog hiveCatalog;
  private static String hiveCatalogName;

  private static Catalog icebergCatalog;
  private static String icebergCatalogName;

  /** Unique prefix for this test run to avoid collisions across parallel runs. */
  private static String testRunPrefix;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String hiveMetastoreUri = System.getProperty("hive.metastore.uri", "thrift://localhost:30083");
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");

    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(simpleUser).build();

    metalakeName = System.getProperty("gravitino.metalake", "test");
    metalake = adminClient.loadMetalake(metalakeName);

    // Create Hive catalog for testing non-Iceberg behavior
    hiveCatalogName = RandomNameUtils.genRandomName("hive_cat");
    Map<String, String> hiveProps = Maps.newHashMap();
    hiveProps.put("metastore.uris", hiveMetastoreUri);
    hiveCatalog =
        metalake.createCatalog(
            hiveCatalogName,
            Catalog.Type.RELATIONAL,
            "hive",
            "Hive catalog for schema tests",
            hiveProps);

    // Load the pre-existing Iceberg catalog from the environment
    icebergCatalogName = System.getProperty("gravitino.irc.catalog", "catalog_1");
    icebergCatalog = metalake.loadCatalog(icebergCatalogName);

    testRunPrefix = RandomNameUtils.genRandomName("hs");
    LOG.info(
        "TestHierarchicalSchemaIT setup complete: metalake={}, hiveCatalog={}, icebergCatalog={},"
            + " prefix={}",
        metalakeName,
        hiveCatalogName,
        icebergCatalogName,
        testRunPrefix);
  }

  @AfterAll
  public static void teardown() {
    try {
      if (metalake != null) {
        // Only drop the Hive catalog we created; the Iceberg catalog is pre-existing
        metalake.dropCatalog(hiveCatalogName, true);
      }
    } catch (Exception e) {
      LOG.warn("Teardown failed, proceeding anyway", e);
    } finally {
      if (adminClient != null) {
        adminClient.close();
      }
    }
  }

  // ── Non-Iceberg catalog: hierarchical schema creation should fail ─────────

  @Test
  @DisplayName("Creating schema with colon separator on Hive catalog should fail")
  public void testCreateHierarchicalSchemaOnHiveCatalogShouldFail() {
    String schemaName = testRunPrefix + "_a:b:c";

    // Creating a schema with ":" in the name on a Hive catalog should fail because
    // the default name pattern (^\w[\w/=-]{0,63}$) does not allow ":" characters.
    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                hiveCatalog
                    .asSchemas()
                    .createSchema(schemaName, "should fail", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating hierarchical schema on Hive: {}", exception.getMessage());
    // The error should indicate the name is illegal or not supported
    Assertions.assertTrue(
        exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("not supported")
            || exception.getMessage().contains("unsupported"),
        "Error message should indicate the schema name is illegal, but got: "
            + exception.getMessage());
  }

  // ── Fileset catalog: hierarchical schema creation should fail ─────────────

  @Test
  @DisplayName("Creating schema with colon separator on Fileset catalog should fail")
  public void testCreateHierarchicalSchemaOnFilesetCatalogShouldFail() {
    // Create a fileset catalog
    String filesetCatalogName = RandomNameUtils.genRandomName("fileset_cat");
    Catalog filesetCatalog =
        metalake.createCatalog(
            filesetCatalogName,
            Catalog.Type.FILESET,
            "Fileset catalog for schema tests",
            Collections.emptyMap());

    try {
      String schemaName = testRunPrefix + "_a:b:c";

      // Creating a schema with ":" in the name on a Fileset catalog should fail because
      // the default name pattern does not allow ":" characters.
      Exception exception =
          Assertions.assertThrows(
              Exception.class,
              () ->
                  filesetCatalog
                      .asSchemas()
                      .createSchema(schemaName, "should fail", Collections.emptyMap()));
      LOG.info(
          "Expected exception when creating hierarchical schema on Fileset: {}",
          exception.getMessage());
      Assertions.assertTrue(
          exception.getMessage().contains("illegal")
              || exception.getMessage().contains("Illegal")
              || exception.getMessage().contains("not supported")
              || exception.getMessage().contains("unsupported"),
          "Error message should indicate the schema name is illegal, but got: "
              + exception.getMessage());
    } finally {
      metalake.dropCatalog(filesetCatalogName, true);
    }
  }

  // ── Iceberg catalog: hierarchical schema creation positive cases ──────────

  @Test
  @DisplayName("Iceberg catalog supports creating and loading hierarchical schemas")
  public void testCreateHierarchicalSchemaOnIcebergCatalog() {
    String level1 = testRunPrefix + "_l1";
    String level2 = level1 + ":child";
    String level3 = level2 + ":grandchild";

    // Create level-1 schema
    icebergCatalog.asSchemas().createSchema(level1, "top level", Collections.emptyMap());
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(level1));

    // Create level-2 schema (parent exists)
    icebergCatalog.asSchemas().createSchema(level2, "second level", Collections.emptyMap());
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(level2));

    // Create level-3 schema (parent exists)
    icebergCatalog.asSchemas().createSchema(level3, "third level", Collections.emptyMap());
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(level3));

    // Clean up — dropping parent directly removes all empty children
    icebergCatalog.asSchemas().dropSchema(level1, false);
  }

  // ── Iceberg catalog: listSchemas with parentSchema ────────────────────────

  @Test
  @DisplayName("Iceberg catalog lists direct children of a parent schema")
  public void testListSchemasWithParentSchemaOnIcebergCatalog() {
    String parent = testRunPrefix + "_lp";
    String child1 = parent + ":child1";
    String child2 = parent + ":child2";
    String grandchild = child1 + ":gc";

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
  @DisplayName("Listing top-level schemas on Iceberg should not include nested schemas")
  public void testListTopLevelSchemasExcludesNestedOnIceberg() {
    String topLevel = testRunPrefix + "_top";
    String nested = topLevel + ":nested";

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

    // Clean up — dropping parent directly removes all empty children
    icebergCatalog.asSchemas().dropSchema(topLevel, false);
  }

  @Test
  @DisplayName("Comprehensive list schemas at every level of a deep hierarchy")
  public void testListSchemasAtEveryLevel() {
    // Build a hierarchy:
    //   root1
    //   root1:a
    //   root1:b
    //   root1:a:x
    //   root1:a:y
    //   root1:a:x:deep
    //   root2
    String root1 = testRunPrefix + "_r1";
    String root2 = testRunPrefix + "_r2";
    String root1A = root1 + ":a";
    String root1B = root1 + ":b";
    String root1AX = root1A + ":x";
    String root1AY = root1A + ":y";
    String root1AXDeep = root1AX + ":deep";

    icebergCatalog.asSchemas().createSchema(root1, "root1", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(root2, "root2", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(root1A, "root1:a", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(root1B, "root1:b", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(root1AX, "root1:a:x", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(root1AY, "root1:a:y", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(root1AXDeep, "root1:a:x:deep", Collections.emptyMap());

    // ── Level 0: top-level listing (no parentSchema) ──
    String[] topLevel = icebergCatalog.asSchemas().listSchemas();
    Set<String> topSet = Arrays.stream(topLevel).collect(Collectors.toSet());
    LOG.info("Top-level schemas: {}", topSet);

    Assertions.assertTrue(topSet.contains(root1), "Top-level should contain root1");
    Assertions.assertTrue(topSet.contains(root2), "Top-level should contain root2");
    Assertions.assertFalse(topSet.contains(root1A), "Top-level should NOT contain root1:a");
    Assertions.assertFalse(topSet.contains(root1B), "Top-level should NOT contain root1:b");
    Assertions.assertFalse(topSet.contains(root1AX), "Top-level should NOT contain root1:a:x");
    Assertions.assertFalse(topSet.contains(root1AY), "Top-level should NOT contain root1:a:y");
    Assertions.assertFalse(
        topSet.contains(root1AXDeep), "Top-level should NOT contain root1:a:x:deep");

    // ── Level 1: children of root1 ──
    String[] root1Children = icebergCatalog.asSchemas().listSchemas(root1);
    Set<String> root1ChildSet = Arrays.stream(root1Children).collect(Collectors.toSet());
    LOG.info("Children of '{}': {}", root1, root1ChildSet);

    Assertions.assertTrue(root1ChildSet.contains(root1A), "root1 children should contain root1:a");
    Assertions.assertTrue(root1ChildSet.contains(root1B), "root1 children should contain root1:b");
    Assertions.assertFalse(
        root1ChildSet.contains(root1AX), "root1 children should NOT contain root1:a:x");
    Assertions.assertFalse(
        root1ChildSet.contains(root1AY), "root1 children should NOT contain root1:a:y");
    Assertions.assertFalse(
        root1ChildSet.contains(root1AXDeep), "root1 children should NOT contain root1:a:x:deep");
    Assertions.assertEquals(2, root1ChildSet.size(), "root1 should have exactly 2 children");

    // ── Level 2: children of root1:a ──
    String[] root1AChildren = icebergCatalog.asSchemas().listSchemas(root1A);
    Set<String> root1AChildSet = Arrays.stream(root1AChildren).collect(Collectors.toSet());
    LOG.info("Children of '{}': {}", root1A, root1AChildSet);

    Assertions.assertTrue(
        root1AChildSet.contains(root1AX), "root1:a children should contain root1:a:x");
    Assertions.assertTrue(
        root1AChildSet.contains(root1AY), "root1:a children should contain root1:a:y");
    Assertions.assertFalse(
        root1AChildSet.contains(root1AXDeep), "root1:a children should NOT contain root1:a:x:deep");
    Assertions.assertEquals(2, root1AChildSet.size(), "root1:a should have exactly 2 children");

    // ── Level 3: children of root1:a:x ──
    String[] root1AXChildren = icebergCatalog.asSchemas().listSchemas(root1AX);
    Set<String> root1AXChildSet = Arrays.stream(root1AXChildren).collect(Collectors.toSet());
    LOG.info("Children of '{}': {}", root1AX, root1AXChildSet);

    Assertions.assertTrue(
        root1AXChildSet.contains(root1AXDeep), "root1:a:x children should contain root1:a:x:deep");
    Assertions.assertEquals(1, root1AXChildSet.size(), "root1:a:x should have exactly 1 child");

    // ── Level 4: children of root1:a:x:deep (leaf — should be empty) ──
    String[] deepChildren = icebergCatalog.asSchemas().listSchemas(root1AXDeep);
    Assertions.assertEquals(
        0, deepChildren.length, "Leaf schema root1:a:x:deep should have no children");

    // ── Level 1: children of root1:b (leaf — should be empty) ──
    String[] root1BChildren = icebergCatalog.asSchemas().listSchemas(root1B);
    Assertions.assertEquals(0, root1BChildren.length, "root1:b should have no children");

    // ── Level 1: children of root2 (leaf — should be empty) ──
    String[] root2Children = icebergCatalog.asSchemas().listSchemas(root2);
    Assertions.assertEquals(0, root2Children.length, "root2 should have no children");

    // Clean up
    icebergCatalog.asSchemas().dropSchema(root1, false);
    icebergCatalog.asSchemas().dropSchema(root2, false);
  }

  // ── Iceberg catalog: update (alter) hierarchical schemas ──────────────────

  @Test
  @DisplayName("Alter properties on hierarchical schemas at each level")
  public void testAlterHierarchicalSchemaProperties() {
    String level1 = testRunPrefix + "_alt";
    String level2 = level1 + ":child";
    String level3 = level2 + ":grandchild";

    // Create 3-level hierarchy
    icebergCatalog.asSchemas().createSchema(level1, "level1", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level2, "level2", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level3, "level3", Collections.emptyMap());

    // ── Set property on level1 ──
    Schema altered1 =
        icebergCatalog.asSchemas().alterSchema(level1, SchemaChange.setProperty("key1", "val1"));
    Assertions.assertEquals("val1", altered1.properties().get("key1"));

    // ── Set property on level2 ──
    Schema altered2 =
        icebergCatalog.asSchemas().alterSchema(level2, SchemaChange.setProperty("key2", "val2"));
    Assertions.assertEquals("val2", altered2.properties().get("key2"));

    // ── Set property on level3 ──
    Schema altered3 =
        icebergCatalog.asSchemas().alterSchema(level3, SchemaChange.setProperty("key3", "val3"));
    Assertions.assertEquals("val3", altered3.properties().get("key3"));

    // ── Verify properties are isolated per level (not inherited) ──
    Schema reloaded1 = icebergCatalog.asSchemas().loadSchema(level1);
    Assertions.assertEquals("val1", reloaded1.properties().get("key1"));
    Assertions.assertNull(
        reloaded1.properties().get("key2"), "Level1 should NOT have level2's property");
    Assertions.assertNull(
        reloaded1.properties().get("key3"), "Level1 should NOT have level3's property");

    Schema reloaded2 = icebergCatalog.asSchemas().loadSchema(level2);
    Assertions.assertEquals("val2", reloaded2.properties().get("key2"));
    Assertions.assertNull(
        reloaded2.properties().get("key1"), "Level2 should NOT have level1's property");
    Assertions.assertNull(
        reloaded2.properties().get("key3"), "Level2 should NOT have level3's property");

    Schema reloaded3 = icebergCatalog.asSchemas().loadSchema(level3);
    Assertions.assertEquals("val3", reloaded3.properties().get("key3"));
    Assertions.assertNull(
        reloaded3.properties().get("key1"), "Level3 should NOT have level1's property");
    Assertions.assertNull(
        reloaded3.properties().get("key2"), "Level3 should NOT have level2's property");

    // ── Set multiple properties and remove property ──
    icebergCatalog
        .asSchemas()
        .alterSchema(
            level2,
            SchemaChange.setProperty("extra1", "e1"),
            SchemaChange.setProperty("extra2", "e2"));
    Schema level2WithExtras = icebergCatalog.asSchemas().loadSchema(level2);
    Assertions.assertEquals("e1", level2WithExtras.properties().get("extra1"));
    Assertions.assertEquals("e2", level2WithExtras.properties().get("extra2"));

    // Remove a property
    icebergCatalog.asSchemas().alterSchema(level2, SchemaChange.removeProperty("extra1"));
    Schema level2AfterRemove = icebergCatalog.asSchemas().loadSchema(level2);
    Assertions.assertNull(
        level2AfterRemove.properties().get("extra1"),
        "Property 'extra1' should be removed from level2");
    Assertions.assertEquals(
        "e2",
        level2AfterRemove.properties().get("extra2"),
        "Property 'extra2' should still exist on level2");

    // ── Verify altering one level does not affect other levels ──
    Schema level1Final = icebergCatalog.asSchemas().loadSchema(level1);
    Assertions.assertNull(
        level1Final.properties().get("extra1"),
        "Level1 should not be affected by level2 property changes");
    Assertions.assertNull(
        level1Final.properties().get("extra2"),
        "Level1 should not be affected by level2 property changes");

    // Clean up
    icebergCatalog.asSchemas().dropSchema(level1, false);
  }

  @Test
  @DisplayName("Alter non-existent hierarchical schema should throw NoSuchSchemaException")
  public void testAlterNonExistentHierarchicalSchema() {
    String nonExistent = testRunPrefix + "_noalt:child:gc";

    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () ->
            icebergCatalog
                .asSchemas()
                .alterSchema(nonExistent, SchemaChange.setProperty("k", "v")));
  }

  // ── REST API: load/create/alter/drop hierarchical schema via HTTP ─────────

  @Test
  @DisplayName("Load hierarchical schema via REST API with URL-encoded colon in path")
  public void testLoadHierarchicalSchemaViaRestApi() throws Exception {
    String level1 = testRunPrefix + "_rest";
    String level2 = level1 + ":child";
    String level3 = level2 + ":gc";

    // Create hierarchy
    icebergCatalog.asSchemas().createSchema(level1, "level1", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level2, "level2", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level3, "level3", Collections.emptyMap());

    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String metalakePath = metalakeName;
    String catalogPath = icebergCatalogName;

    try {
      // Load level1 (flat name, no encoding needed)
      String url1 =
          String.format(
              "%s/api/metalakes/%s/catalogs/%s/schemas/%s",
              gravitinoUri, metalakePath, catalogPath, URLEncoder.encode(level1, "UTF-8"));
      HttpResponse<String> resp1 = sendGetRequest(url1);
      Assertions.assertEquals(
          200, resp1.statusCode(), "Load level1 should return 200, body: " + resp1.body());
      Assertions.assertTrue(
          resp1.body().contains(level1), "Response should contain schema name: " + level1);

      // Load level2 (contains ':', must be URL-encoded)
      String url2 =
          String.format(
              "%s/api/metalakes/%s/catalogs/%s/schemas/%s",
              gravitinoUri, metalakePath, catalogPath, URLEncoder.encode(level2, "UTF-8"));
      HttpResponse<String> resp2 = sendGetRequest(url2);
      Assertions.assertEquals(
          200, resp2.statusCode(), "Load level2 should return 200, body: " + resp2.body());
      Assertions.assertTrue(
          resp2.body().contains(level2), "Response should contain schema name: " + level2);

      // Load level3 (contains multiple ':', must be URL-encoded)
      String url3 =
          String.format(
              "%s/api/metalakes/%s/catalogs/%s/schemas/%s",
              gravitinoUri, metalakePath, catalogPath, URLEncoder.encode(level3, "UTF-8"));
      HttpResponse<String> resp3 = sendGetRequest(url3);
      Assertions.assertEquals(
          200, resp3.statusCode(), "Load level3 should return 200, body: " + resp3.body());
      Assertions.assertTrue(
          resp3.body().contains(level3), "Response should contain schema name: " + level3);

      // Load non-existent hierarchical schema should return 404
      String nonExistent = level1 + ":nonexistent";
      String url404 =
          String.format(
              "%s/api/metalakes/%s/catalogs/%s/schemas/%s",
              gravitinoUri, metalakePath, catalogPath, URLEncoder.encode(nonExistent, "UTF-8"));
      HttpResponse<String> resp404 = sendGetRequest(url404);
      Assertions.assertEquals(
          404, resp404.statusCode(), "Load non-existent schema should return 404");
    } finally {
      // Clean up
      icebergCatalog.asSchemas().dropSchema(level1, false);
    }
  }

  @Test
  @DisplayName("Create hierarchical schema via REST API")
  public void testCreateHierarchicalSchemaViaRestApi() throws Exception {
    String parent = testRunPrefix + "_restcreate";
    String child = parent + ":child";

    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String url =
        String.format(
            "%s/api/metalakes/%s/catalogs/%s/schemas",
            gravitinoUri, metalakeName, icebergCatalogName);

    try {
      // Create parent via REST
      String createParentBody =
          String.format(
              "{\"name\":\"%s\",\"comment\":\"created via REST\",\"properties\":{}}", parent);
      HttpResponse<String> respParent = sendPostRequest(url, createParentBody);
      Assertions.assertEquals(
          200,
          respParent.statusCode(),
          "Create parent should return 200, body: " + respParent.body());

      // Create child via REST (name contains ':')
      String createChildBody =
          String.format(
              "{\"name\":\"%s\",\"comment\":\"child via REST\",\"properties\":{}}", child);
      HttpResponse<String> respChild = sendPostRequest(url, createChildBody);
      Assertions.assertEquals(
          200, respChild.statusCode(), "Create child should return 200, body: " + respChild.body());

      // Verify via Java client
      Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(parent));
      Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(child));
    } finally {
      // Clean up
      try {
        icebergCatalog.asSchemas().dropSchema(parent, false);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  @DisplayName("List schemas with parentSchema query parameter via REST API")
  public void testListSchemasWithParentSchemaViaRestApi() throws Exception {
    String parent = testRunPrefix + "_restlist";
    String child1 = parent + ":c1";
    String child2 = parent + ":c2";

    icebergCatalog.asSchemas().createSchema(parent, "parent", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child1, "child1", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child2, "child2", Collections.emptyMap());

    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");

    try {
      // List with parentSchema query param
      String url =
          String.format(
              "%s/api/metalakes/%s/catalogs/%s/schemas?parentSchema=%s",
              gravitinoUri, metalakeName, icebergCatalogName, URLEncoder.encode(parent, "UTF-8"));
      HttpResponse<String> resp = sendGetRequest(url);
      Assertions.assertEquals(
          200, resp.statusCode(), "List with parentSchema should return 200, body: " + resp.body());
      Assertions.assertTrue(
          resp.body().contains(child1), "Response should contain child1: " + child1);
      Assertions.assertTrue(
          resp.body().contains(child2), "Response should contain child2: " + child2);
      Assertions.assertFalse(
          resp.body().contains("\"name\":\"" + parent + "\""),
          "Response should NOT contain parent itself as a listed schema");
    } finally {
      icebergCatalog.asSchemas().dropSchema(parent, false);
    }
  }

  /** Sends a GET request and returns the response. */
  private static HttpResponse<String> sendGetRequest(String url) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");
    String authToken =
        "Basic "
            + java.util.Base64.getEncoder()
                .encodeToString(
                    (simpleUser + ":dummy").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.gravitino.v1+json")
            .header("Authorization", authToken)
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** Sends a POST request with JSON body and returns the response. */
  private static HttpResponse<String> sendPostRequest(String url, String jsonBody)
      throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");
    String authToken =
        "Basic "
            + java.util.Base64.getEncoder()
                .encodeToString(
                    (simpleUser + ":dummy").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.gravitino.v1+json")
            .header("Content-Type", "application/json")
            .header("Authorization", authToken)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  // ── Iceberg catalog: corner cases for hierarchical schema ─────────────────

  @Test
  @DisplayName("Creating hierarchical schema without existing parent should succeed on Iceberg")
  public void testCreateHierarchicalSchemaWithoutParentShouldSucceed() {
    // Iceberg implicitly creates parent namespaces, so creating "parent:child" without
    // explicitly creating "parent" first should succeed.
    String parent = testRunPrefix + "_orphan";
    String child = parent + ":child";

    // Create child directly without creating parent first
    icebergCatalog.asSchemas().createSchema(child, "orphan child", Collections.emptyMap());

    // Both parent and child should now exist
    Assertions.assertNotNull(
        icebergCatalog.asSchemas().loadSchema(child), "Child schema should exist");
    Assertions.assertNotNull(
        icebergCatalog.asSchemas().loadSchema(parent),
        "Parent schema should be implicitly created");

    // Clean up — dropping parent directly removes all empty children
    icebergCatalog.asSchemas().dropSchema(parent, false);
  }

  @Test
  @DisplayName("Listing schemas with non-existent parentSchema should throw on Iceberg")
  public void testListSchemasWithNonExistentParentShouldFail() {
    String nonExistentParent = testRunPrefix + "_ghost";

    // Listing children of a non-existent parent should throw NoSuchSchemaException
    Exception exception =
        Assertions.assertThrows(
            Exception.class, () -> icebergCatalog.asSchemas().listSchemas(nonExistentParent));
    LOG.info(
        "Expected exception when listing with non-existent parent: {}", exception.getMessage());
  }

  @Test
  @DisplayName("Creating schema with empty segment (e.g. 'a::b') should fail on Iceberg")
  public void testCreateSchemaWithEmptySegmentShouldFail() {
    String invalidName = testRunPrefix + "_a::b";

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
  @DisplayName("Creating schema with leading separator (e.g. ':a:b') should fail on Iceberg")
  public void testCreateSchemaWithLeadingSeparatorShouldFail() {
    String invalidName = ":a:" + testRunPrefix;

    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                icebergCatalog
                    .asSchemas()
                    .createSchema(invalidName, "leading separator", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating schema with leading separator: {}",
        exception.getMessage());
    Assertions.assertTrue(
        exception.getMessage().contains("empty segment")
            || exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("unsupported"),
        "Should indicate leading separator is invalid, but got: " + exception.getMessage());
  }

  @Test
  @DisplayName("Creating schema with trailing separator (e.g. 'a:b:') should fail on Iceberg")
  public void testCreateSchemaWithTrailingSeparatorShouldFail() {
    String invalidName = testRunPrefix + "_a:b:";

    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                icebergCatalog
                    .asSchemas()
                    .createSchema(invalidName, "trailing separator", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating schema with trailing separator: {}",
        exception.getMessage());
    Assertions.assertTrue(
        exception.getMessage().contains("empty segment")
            || exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("unsupported"),
        "Should indicate trailing separator is invalid, but got: " + exception.getMessage());
  }

  @Test
  @DisplayName(
      "Creating schema with '#' as separator should fail on Iceberg (default separator is ':')")
  public void testCreateSchemaWithHashSeparatorShouldFail() {
    // The default hierarchical schema separator is ':'. Using '#' as a separator
    // means the schema name "a#b#c" is treated as a flat name, not hierarchical.
    // The '#' character is not in the allowed name pattern (^\w[\w/=-]{0,63}$),
    // so creating a schema with '#' in the name should fail with an illegal name error.
    String invalidName = testRunPrefix + "_a#b#c";

    Exception exception =
        Assertions.assertThrows(
            Exception.class,
            () ->
                icebergCatalog
                    .asSchemas()
                    .createSchema(invalidName, "hash separator", Collections.emptyMap()));
    LOG.info(
        "Expected exception when creating schema with '#' separator on Iceberg: {}",
        exception.getMessage());
    Assertions.assertTrue(
        exception.getMessage().contains("illegal")
            || exception.getMessage().contains("Illegal")
            || exception.getMessage().contains("not supported")
            || exception.getMessage().contains("unsupported"),
        "Error message should indicate the schema name is illegal, but got: "
            + exception.getMessage());
  }

  // ── Iceberg catalog: drop schema corner cases ─────────────────────────────

  @Test
  @DisplayName("Dropping parent schema when child contains a table should fail on Iceberg")
  public void testDropParentSchemaWithNonEmptyChildShouldFail() {
    String parent = testRunPrefix + "_dp";
    String child = parent + ":child";

    icebergCatalog.asSchemas().createSchema(parent, "parent", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child, "child", Collections.emptyMap());

    // Create a table in the child schema so it is non-empty
    icebergCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(child, "tbl"),
            new Column[] {
              Column.of("id", Types.IntegerType.get(), "id column"),
            },
            "table in child schema",
            Collections.emptyMap());

    Assertions.assertNotNull(
        icebergCatalog.asTableCatalog().loadTable(NameIdentifier.of(child, "tbl")));

    // Dropping parent without cascade should fail because child schema has a table
    Exception exception =
        Assertions.assertThrows(
            Exception.class, () -> icebergCatalog.asSchemas().dropSchema(parent, false));
    LOG.info(
        "Expected exception when dropping parent with non-empty child: {}", exception.getMessage());

    // Verify both schemas still exist
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(parent));
    Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(child));

    // Clean up properly: drop table first, then drop parent directly
    icebergCatalog.asTableCatalog().dropTable(NameIdentifier.of(child, "tbl"));
    icebergCatalog.asSchemas().dropSchema(parent, false);
  }

  @Test
  @DisplayName("Dropping parent schema when child is empty may succeed on Iceberg")
  public void testDropParentSchemaWithEmptyChildMaySucceed() {
    String parent = testRunPrefix + "_dpe";
    String child = parent + ":emptychild";

    icebergCatalog.asSchemas().createSchema(parent, "parent", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child, "empty child", Collections.emptyMap());

    // Dropping parent when child schema is empty — Iceberg may allow this
    // (it drops empty child namespaces). We verify the behavior is consistent:
    // either it succeeds and both are gone, or it throws and both remain.
    try {
      boolean dropped = icebergCatalog.asSchemas().dropSchema(parent, false);
      if (dropped) {
        LOG.info("Iceberg allowed dropping parent with empty child — both should be gone");
        Assertions.assertThrows(
            NoSuchSchemaException.class, () -> icebergCatalog.asSchemas().loadSchema(parent));
        Assertions.assertThrows(
            NoSuchSchemaException.class, () -> icebergCatalog.asSchemas().loadSchema(child));
      }
    } catch (Exception e) {
      LOG.info(
          "Iceberg rejected dropping parent with empty child: {} — both should remain",
          e.getMessage());
      Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(parent));
      Assertions.assertNotNull(icebergCatalog.asSchemas().loadSchema(child));
      // Clean up — dropping parent directly removes empty children
      icebergCatalog.asSchemas().dropSchema(parent, false);
    }
  }

  @Test
  @DisplayName("Loading a non-existent hierarchical schema should throw NoSuchSchemaException")
  public void testLoadNonExistentHierarchicalSchema() {
    String nonExistent = testRunPrefix + "_ne:child:grandchild";

    Assertions.assertThrows(
        NoSuchSchemaException.class, () -> icebergCatalog.asSchemas().loadSchema(nonExistent));
  }

  // ── Policy association with hierarchical schemas ──────────────────────────

  @Test
  @DisplayName("Associate policy with hierarchical schemas (parent and child)")
  public void testAssociatePolicyForHierarchicalSchema() {
    String parent = testRunPrefix + "_pol";
    String child = parent + ":child";

    // Create hierarchical schemas
    icebergCatalog.asSchemas().createSchema(parent, "parent for policy", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child, "child for policy", Collections.emptyMap());

    // Create a policy on the metalake
    String policyName = testRunPrefix + "_test_policy";
    Set<MetadataObject.Type> supportedTypes =
        ImmutableSet.of(MetadataObject.Type.SCHEMA, MetadataObject.Type.TABLE);
    metalake.createPolicy(
        policyName,
        "custom",
        "test policy for hierarchical schema",
        true,
        PolicyContents.custom(
            Collections.singletonMap("rule1", "value1"), supportedTypes, Collections.emptyMap()));

    try {
      // Associate policy with parent schema
      Schema parentSchema = icebergCatalog.asSchemas().loadSchema(parent);
      String[] parentPolicies =
          parentSchema
              .supportsPolicies()
              .associatePolicies(new String[] {policyName}, new String[] {});
      Assertions.assertNotNull(parentPolicies, "associatePolicies should return non-null");
      LOG.info("Associated policy '{}' with parent schema '{}'", policyName, parent);

      // Verify policy is listed on parent schema
      String[] parentPolicyList = parentSchema.supportsPolicies().listPolicies();
      Assertions.assertTrue(
          Arrays.asList(parentPolicyList).contains(policyName),
          "Parent schema should have the policy associated, got: "
              + Arrays.toString(parentPolicyList));

      // Verify policy can be retrieved from parent schema
      Policy parentPolicy = parentSchema.supportsPolicies().getPolicy(policyName);
      Assertions.assertNotNull(parentPolicy, "Should be able to get policy from parent schema");
      Assertions.assertEquals(policyName, parentPolicy.name());

      // Associate the same policy with child schema
      Schema childSchema = icebergCatalog.asSchemas().loadSchema(child);
      String[] childPolicies =
          childSchema
              .supportsPolicies()
              .associatePolicies(new String[] {policyName}, new String[] {});
      Assertions.assertNotNull(childPolicies, "associatePolicies should return non-null");
      LOG.info("Associated policy '{}' with child schema '{}'", policyName, child);

      // Verify policy is listed on child schema
      String[] childPolicyList = childSchema.supportsPolicies().listPolicies();
      Assertions.assertTrue(
          Arrays.asList(childPolicyList).contains(policyName),
          "Child schema should have the policy associated, got: "
              + Arrays.toString(childPolicyList));

      // Verify policy can be retrieved from child schema
      Policy childPolicy = childSchema.supportsPolicies().getPolicy(policyName);
      Assertions.assertNotNull(childPolicy, "Should be able to get policy from child schema");
      Assertions.assertEquals(policyName, childPolicy.name());

      // Disassociate policy from both schemas
      parentSchema.supportsPolicies().associatePolicies(new String[] {}, new String[] {policyName});
      childSchema.supportsPolicies().associatePolicies(new String[] {}, new String[] {policyName});

      // Verify policy is removed
      String[] parentPoliciesAfter = parentSchema.supportsPolicies().listPolicies();
      Assertions.assertFalse(
          Arrays.asList(parentPoliciesAfter).contains(policyName),
          "Parent schema should no longer have the policy");
      String[] childPoliciesAfter = childSchema.supportsPolicies().listPolicies();
      Assertions.assertFalse(
          Arrays.asList(childPoliciesAfter).contains(policyName),
          "Child schema should no longer have the policy");
    } finally {
      // Clean up
      metalake.deletePolicy(policyName);
      icebergCatalog.asSchemas().dropSchema(parent, false);
    }
  }

  @Test
  @DisplayName("List metadata objects for a policy associated with hierarchical schemas")
  public void testListMetadataObjectsForPolicyWithHierarchicalSchemas() {
    String level1 = testRunPrefix + "_pobj";
    String level2 = level1 + ":child";
    String level3 = level2 + ":gc";

    // Create 3-level hierarchy
    icebergCatalog.asSchemas().createSchema(level1, "level1", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level2, "level2", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level3, "level3", Collections.emptyMap());

    // Create a policy that supports SCHEMA type
    String policyName = testRunPrefix + "_obj_policy";
    Set<MetadataObject.Type> supportedTypes = ImmutableSet.of(MetadataObject.Type.SCHEMA);
    metalake.createPolicy(
        policyName,
        "custom",
        "policy for listing objects test",
        true,
        PolicyContents.custom(
            Collections.singletonMap("rule1", "value1"), supportedTypes, Collections.emptyMap()));

    try {
      // Associate the policy with all three levels
      Schema schema1 = icebergCatalog.asSchemas().loadSchema(level1);
      schema1.supportsPolicies().associatePolicies(new String[] {policyName}, new String[] {});

      Schema schema2 = icebergCatalog.asSchemas().loadSchema(level2);
      schema2.supportsPolicies().associatePolicies(new String[] {policyName}, new String[] {});

      Schema schema3 = icebergCatalog.asSchemas().loadSchema(level3);
      schema3.supportsPolicies().associatePolicies(new String[] {policyName}, new String[] {});

      // List metadata objects for the policy
      Policy policy = metalake.getPolicy(policyName);
      MetadataObject[] objects = policy.associatedObjects().objects();
      Assertions.assertNotNull(objects, "associatedObjects().objects() should return non-null");
      LOG.info("Metadata objects for policy '{}': count={}", policyName, objects.length);

      // Verify all three hierarchical schemas are listed
      Set<String> objectNames =
          Arrays.stream(objects).map(MetadataObject::fullName).collect(Collectors.toSet());
      LOG.info("Object full names: {}", objectNames);

      String expectedLevel1FullName = icebergCatalogName + "." + level1;
      String expectedLevel2FullName = icebergCatalogName + "." + level2;
      String expectedLevel3FullName = icebergCatalogName + "." + level3;

      Assertions.assertTrue(
          objectNames.contains(expectedLevel1FullName),
          "Should contain level1 schema '" + expectedLevel1FullName + "', got: " + objectNames);
      Assertions.assertTrue(
          objectNames.contains(expectedLevel2FullName),
          "Should contain level2 schema '" + expectedLevel2FullName + "', got: " + objectNames);
      Assertions.assertTrue(
          objectNames.contains(expectedLevel3FullName),
          "Should contain level3 schema '" + expectedLevel3FullName + "', got: " + objectNames);

      // Verify all objects are of type SCHEMA
      for (MetadataObject obj : objects) {
        Assertions.assertEquals(
            MetadataObject.Type.SCHEMA,
            obj.type(),
            "All associated objects should be SCHEMA type, got: " + obj.type());
      }

      // Disassociate from level2 and verify it's no longer listed
      schema2.supportsPolicies().associatePolicies(new String[] {}, new String[] {policyName});

      MetadataObject[] objectsAfter = policy.associatedObjects().objects();
      Set<String> objectNamesAfter =
          Arrays.stream(objectsAfter).map(MetadataObject::fullName).collect(Collectors.toSet());
      LOG.info("Object full names after disassociate level2: {}", objectNamesAfter);

      Assertions.assertTrue(
          objectNamesAfter.contains(expectedLevel1FullName),
          "Should still contain level1 after disassociating level2");
      Assertions.assertFalse(
          objectNamesAfter.contains(expectedLevel2FullName),
          "Should NOT contain level2 after disassociating it");
      Assertions.assertTrue(
          objectNamesAfter.contains(expectedLevel3FullName),
          "Should still contain level3 after disassociating level2");
    } finally {
      // Clean up
      metalake.deletePolicy(policyName);
      icebergCatalog.asSchemas().dropSchema(level1, false);
    }
  }

  // ── Tag association with hierarchical schemas ─────────────────────────────

  @Test
  @DisplayName("Associate tags with hierarchical schemas (parent and child)")
  public void testAssociateTagForHierarchicalSchema() {
    String parent = testRunPrefix + "_tag";
    String child = parent + ":child";

    // Create hierarchical schemas
    icebergCatalog.asSchemas().createSchema(parent, "parent for tag", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child, "child for tag", Collections.emptyMap());

    // Create tags on the metalake
    String tag1Name = testRunPrefix + "_tag1";
    String tag2Name = testRunPrefix + "_tag2";
    metalake.createTag(tag1Name, "tag1 for hierarchical schema test", Collections.emptyMap());
    metalake.createTag(tag2Name, "tag2 for hierarchical schema test", Collections.emptyMap());

    try {
      // Associate tag1 with parent schema
      Schema parentSchema = icebergCatalog.asSchemas().loadSchema(parent);
      String[] parentTags =
          parentSchema.supportsTags().associateTags(new String[] {tag1Name}, null);
      Assertions.assertNotNull(parentTags, "associateTags should return non-null");
      Assertions.assertTrue(
          Arrays.asList(parentTags).contains(tag1Name),
          "Parent schema should have tag1 associated, got: " + Arrays.toString(parentTags));
      LOG.info("Associated tag '{}' with parent schema '{}'", tag1Name, parent);

      // Associate tag2 with child schema
      Schema childSchema = icebergCatalog.asSchemas().loadSchema(child);
      String[] childTags = childSchema.supportsTags().associateTags(new String[] {tag2Name}, null);
      Assertions.assertNotNull(childTags, "associateTags should return non-null");
      Assertions.assertTrue(
          Arrays.asList(childTags).contains(tag2Name),
          "Child schema should have tag2 associated, got: " + Arrays.toString(childTags));
      LOG.info("Associated tag '{}' with child schema '{}'", tag2Name, child);

      // Verify tags are listed correctly on each schema
      String[] parentTagList = parentSchema.supportsTags().listTags();
      Assertions.assertTrue(
          Arrays.asList(parentTagList).contains(tag1Name),
          "Parent schema listTags should contain tag1, got: " + Arrays.toString(parentTagList));
      Assertions.assertFalse(
          Arrays.asList(parentTagList).contains(tag2Name),
          "Parent schema listTags should NOT contain tag2 (associated with child)");

      String[] childTagList = childSchema.supportsTags().listTags();
      Assertions.assertTrue(
          Arrays.asList(childTagList).contains(tag2Name),
          "Child schema listTags should contain tag2, got: " + Arrays.toString(childTagList));
      Assertions.assertFalse(
          Arrays.asList(childTagList).contains(tag1Name),
          "Child schema listTags should NOT contain tag1 (associated with parent)");

      // Associate both tags with child schema
      childSchema.supportsTags().associateTags(new String[] {tag1Name}, null);
      String[] childTagListUpdated = childSchema.supportsTags().listTags();
      Assertions.assertTrue(
          Arrays.asList(childTagListUpdated).contains(tag1Name),
          "Child schema should now also have tag1");
      Assertions.assertTrue(
          Arrays.asList(childTagListUpdated).contains(tag2Name),
          "Child schema should still have tag2");

      // Disassociate tags
      parentSchema.supportsTags().associateTags(null, new String[] {tag1Name});
      childSchema.supportsTags().associateTags(null, new String[] {tag1Name, tag2Name});

      // Verify tags are removed
      String[] parentTagsAfter = parentSchema.supportsTags().listTags();
      Assertions.assertFalse(
          Arrays.asList(parentTagsAfter).contains(tag1Name),
          "Parent schema should no longer have tag1");
      String[] childTagsAfter = childSchema.supportsTags().listTags();
      Assertions.assertEquals(
          0, childTagsAfter.length, "Child schema should have no tags after disassociation");
    } finally {
      // Clean up
      metalake.deleteTag(tag1Name);
      metalake.deleteTag(tag2Name);
      icebergCatalog.asSchemas().dropSchema(parent, false);
    }
  }

  @Test
  @DisplayName(
      "List metadata objects for tag and verify tag inheritance does not cross schema levels")
  public void testListMetadataObjectsForTagAndInheritance() {
    String parent = testRunPrefix + "_tobj";
    String child = parent + ":child";
    String grandchild = child + ":gc";

    // Create 3-level hierarchy
    icebergCatalog.asSchemas().createSchema(parent, "parent", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(child, "child", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(grandchild, "grandchild", Collections.emptyMap());

    // Create a tag and associate it with the parent schema only
    String tagName = testRunPrefix + "_inherit_tag";
    metalake.createTag(tagName, "tag for inheritance test", Collections.emptyMap());

    try {
      Schema parentSchema = icebergCatalog.asSchemas().loadSchema(parent);
      parentSchema.supportsTags().associateTags(new String[] {tagName}, null);

      // ── Verify: list metadata objects for the tag ──
      // The tag should list the parent schema as an associated object
      Tag tag = metalake.getTag(tagName);
      MetadataObject[] tagObjects = tag.associatedObjects().objects();
      Assertions.assertNotNull(tagObjects, "Tag associatedObjects should return non-null");

      Set<String> tagObjectNames =
          Arrays.stream(tagObjects).map(MetadataObject::fullName).collect(Collectors.toSet());
      LOG.info("Metadata objects for tag '{}': {}", tagName, tagObjectNames);

      String expectedParentFullName = icebergCatalogName + "." + parent;
      Assertions.assertTrue(
          tagObjectNames.contains(expectedParentFullName),
          "Tag should list parent schema as associated object, got: " + tagObjectNames);

      // Child and grandchild should NOT be in the tag's associated objects
      // (they are not directly tagged)
      String expectedChildFullName = icebergCatalogName + "." + child;
      String expectedGrandchildFullName = icebergCatalogName + "." + grandchild;
      Assertions.assertFalse(
          tagObjectNames.contains(expectedChildFullName),
          "Tag should NOT list child schema (not directly tagged), got: " + tagObjectNames);
      Assertions.assertFalse(
          tagObjectNames.contains(expectedGrandchildFullName),
          "Tag should NOT list grandchild schema (not directly tagged), got: " + tagObjectNames);

      // ── Verify: tag inheritance behavior on child schemas ──
      // In Gravitino, tag inheritance goes schema → catalog → metalake (via
      // MetadataObjects.parent).
      // It does NOT go child_schema → parent_schema. So child should NOT inherit parent's tag.
      Schema childSchema = icebergCatalog.asSchemas().loadSchema(child);
      String[] childTags = childSchema.supportsTags().listTags();
      LOG.info("Tags on child schema '{}': {}", child, Arrays.toString(childTags));
      Assertions.assertFalse(
          Arrays.asList(childTags).contains(tagName),
          "Child schema should NOT inherit tag from parent schema (inheritance is schema→catalog"
              + " only), got: "
              + Arrays.toString(childTags));

      Schema grandchildSchema = icebergCatalog.asSchemas().loadSchema(grandchild);
      String[] grandchildTags = grandchildSchema.supportsTags().listTags();
      LOG.info("Tags on grandchild schema '{}': {}", grandchild, Arrays.toString(grandchildTags));
      Assertions.assertFalse(
          Arrays.asList(grandchildTags).contains(tagName),
          "Grandchild schema should NOT inherit tag from parent schema, got: "
              + Arrays.toString(grandchildTags));

      // ── Verify: listTagsInfo also confirms no inheritance ──
      Tag[] childTagsInfo = childSchema.supportsTags().listTagsInfo();
      boolean hasInheritedParentTag =
          Arrays.stream(childTagsInfo)
              .anyMatch(t -> t.name().equals(tagName) && t.inherited().orElse(false));
      Assertions.assertFalse(
          hasInheritedParentTag,
          "Child schema listTagsInfo should NOT show parent schema's tag as inherited");

      // ── Verify: directly tagging child works independently ──
      String childTagName = testRunPrefix + "_child_own_tag";
      metalake.createTag(childTagName, "child's own tag", Collections.emptyMap());
      childSchema.supportsTags().associateTags(new String[] {childTagName}, null);

      // Now list objects for childTag — should only contain child
      Tag childTag = metalake.getTag(childTagName);
      MetadataObject[] childTagObjects = childTag.associatedObjects().objects();
      Set<String> childTagObjectNames =
          Arrays.stream(childTagObjects).map(MetadataObject::fullName).collect(Collectors.toSet());
      Assertions.assertTrue(
          childTagObjectNames.contains(expectedChildFullName),
          "Child's own tag should list child schema, got: " + childTagObjectNames);
      Assertions.assertFalse(
          childTagObjectNames.contains(expectedParentFullName),
          "Child's own tag should NOT list parent schema, got: " + childTagObjectNames);

      // Clean up child tag
      childSchema.supportsTags().associateTags(null, new String[] {childTagName});
      metalake.deleteTag(childTagName);
    } finally {
      // Clean up
      Schema parentSchemaForCleanup = icebergCatalog.asSchemas().loadSchema(parent);
      parentSchemaForCleanup.supportsTags().associateTags(null, new String[] {tagName});
      metalake.deleteTag(tagName);
      icebergCatalog.asSchemas().dropSchema(parent, false);
    }
  }

  // ── Function registration in hierarchical schemas ─────────────────────────

  @Test
  @DisplayName("Register functions in hierarchical schemas at each level")
  public void testRegisterFunctionInHierarchicalSchemas() {
    String level1 = testRunPrefix + "_fn";
    String level2 = level1 + ":child";
    String level3 = level2 + ":grandchild";

    // Create 3-level hierarchical schemas
    icebergCatalog.asSchemas().createSchema(level3, "level3 for function", Collections.emptyMap());

    FunctionCatalog functionCatalog = icebergCatalog.asFunctionCatalog();

    // Build a function definition
    FunctionDefinition sparkDef =
        FunctionDefinitions.of(
            FunctionParams.of(FunctionParams.of("input", Types.IntegerType.get())),
            Types.IntegerType.get(),
            FunctionImpls.of(
                FunctionImpls.ofJava(FunctionImpl.RuntimeType.SPARK, "com.example.TestFunction")));

    // Register function in level-1 schema
    String func1Name = "func_level1";
    functionCatalog.registerFunction(
        NameIdentifier.of(level1, func1Name),
        "function in level1",
        FunctionType.SCALAR,
        true,
        FunctionDefinitions.of(sparkDef));
    Assertions.assertNotNull(
        functionCatalog.getFunction(NameIdentifier.of(level1, func1Name)),
        "Function in level1 should be loadable");

    // Register function in level-2 schema
    String func2Name = "func_level2";
    functionCatalog.registerFunction(
        NameIdentifier.of(level2, func2Name),
        "function in level2",
        FunctionType.SCALAR,
        true,
        FunctionDefinitions.of(sparkDef));
    Assertions.assertNotNull(
        functionCatalog.getFunction(NameIdentifier.of(level2, func2Name)),
        "Function in level2 should be loadable");

    // Register function in level-3 schema
    String func3Name = "func_level3";
    functionCatalog.registerFunction(
        NameIdentifier.of(level3, func3Name),
        "function in level3",
        FunctionType.SCALAR,
        true,
        FunctionDefinitions.of(sparkDef));
    Assertions.assertNotNull(
        functionCatalog.getFunction(NameIdentifier.of(level3, func3Name)),
        "Function in level3 should be loadable");

    // Verify listing functions in each schema only returns its own functions
    Set<String> level1Funcs =
        Arrays.stream(functionCatalog.listFunctions(Namespace.of(level1)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        level1Funcs.contains(func1Name), "Level1 should list its function, got: " + level1Funcs);
    Assertions.assertFalse(
        level1Funcs.contains(func2Name),
        "Level1 should NOT list level2's function, got: " + level1Funcs);
    Assertions.assertFalse(
        level1Funcs.contains(func3Name),
        "Level1 should NOT list level3's function, got: " + level1Funcs);

    Set<String> level2Funcs =
        Arrays.stream(functionCatalog.listFunctions(Namespace.of(level2)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        level2Funcs.contains(func2Name), "Level2 should list its function, got: " + level2Funcs);
    Assertions.assertFalse(
        level2Funcs.contains(func1Name),
        "Level2 should NOT list level1's function, got: " + level2Funcs);

    Set<String> level3Funcs =
        Arrays.stream(functionCatalog.listFunctions(Namespace.of(level3)))
            .map(NameIdentifier::name)
            .collect(Collectors.toSet());
    Assertions.assertTrue(
        level3Funcs.contains(func3Name), "Level3 should list its function, got: " + level3Funcs);
    Assertions.assertFalse(
        level3Funcs.contains(func2Name),
        "Level3 should NOT list level2's function, got: " + level3Funcs);

    // Clean up: drop functions, then schemas
    functionCatalog.dropFunction(NameIdentifier.of(level3, func3Name));
    functionCatalog.dropFunction(NameIdentifier.of(level2, func2Name));
    functionCatalog.dropFunction(NameIdentifier.of(level1, func1Name));
    icebergCatalog.asSchemas().dropSchema(level1, false);
  }

  // ── Role binding with hierarchical schemas ────────────────────────────────

  @Test
  @DisplayName("Grant privileges to roles for each level of hierarchical schema and verify binding")
  public void testRoleBindingForHierarchicalSchema() {
    String level1 = testRunPrefix + "_role_l1";
    String level2 = level1 + ":l2";
    String level3 = level2 + ":l3";

    // Create 3-level hierarchy
    icebergCatalog.asSchemas().createSchema(level1, "level1 for role", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level2, "level2 for role", Collections.emptyMap());
    icebergCatalog.asSchemas().createSchema(level3, "level3 for role", Collections.emptyMap());

    // Create distinct roles for each level
    String role1Name = testRunPrefix + "_role_level1";
    String role2Name = testRunPrefix + "_role_level2";
    String role3Name = testRunPrefix + "_role_level3";

    metalake.createRole(role1Name, Collections.emptyMap(), Collections.emptyList());
    metalake.createRole(role2Name, Collections.emptyMap(), Collections.emptyList());
    metalake.createRole(role3Name, Collections.emptyMap(), Collections.emptyList());

    try {
      // Grant UseSchema privilege to role1 on level1 schema
      MetadataObject level1Object =
          MetadataObjects.of(icebergCatalogName, level1, MetadataObject.Type.SCHEMA);
      metalake.grantPrivilegesToRole(
          role1Name, level1Object, ImmutableSet.of(Privileges.UseSchema.allow()));

      // Grant UseSchema privilege to role2 on level2 schema
      MetadataObject level2Object =
          MetadataObjects.of(icebergCatalogName, level2, MetadataObject.Type.SCHEMA);
      metalake.grantPrivilegesToRole(
          role2Name, level2Object, ImmutableSet.of(Privileges.UseSchema.allow()));

      // Grant UseSchema + CreateTable privileges to role3 on level3 schema
      MetadataObject level3Object =
          MetadataObjects.of(icebergCatalogName, level3, MetadataObject.Type.SCHEMA);
      metalake.grantPrivilegesToRole(
          role3Name,
          level3Object,
          ImmutableSet.of(Privileges.UseSchema.allow(), Privileges.CreateTable.allow()));

      // Verify listBindingRoleNames on each level
      Schema schema1 = icebergCatalog.asSchemas().loadSchema(level1);
      String[] level1Roles = schema1.supportsRoles().listBindingRoleNames();
      LOG.info("Roles bound to '{}': {}", level1, Arrays.toString(level1Roles));
      Assertions.assertTrue(
          Arrays.asList(level1Roles).contains(role1Name),
          "Level1 schema should have role1 bound, got: " + Arrays.toString(level1Roles));
      Assertions.assertFalse(
          Arrays.asList(level1Roles).contains(role2Name),
          "Level1 schema should NOT have role2 bound");
      Assertions.assertFalse(
          Arrays.asList(level1Roles).contains(role3Name),
          "Level1 schema should NOT have role3 bound");

      Schema schema2 = icebergCatalog.asSchemas().loadSchema(level2);
      String[] level2Roles = schema2.supportsRoles().listBindingRoleNames();
      LOG.info("Roles bound to '{}': {}", level2, Arrays.toString(level2Roles));
      Assertions.assertTrue(
          Arrays.asList(level2Roles).contains(role2Name),
          "Level2 schema should have role2 bound, got: " + Arrays.toString(level2Roles));
      Assertions.assertFalse(
          Arrays.asList(level2Roles).contains(role1Name),
          "Level2 schema should NOT have role1 bound");
      Assertions.assertFalse(
          Arrays.asList(level2Roles).contains(role3Name),
          "Level2 schema should NOT have role3 bound");

      Schema schema3 = icebergCatalog.asSchemas().loadSchema(level3);
      String[] level3Roles = schema3.supportsRoles().listBindingRoleNames();
      LOG.info("Roles bound to '{}': {}", level3, Arrays.toString(level3Roles));
      Assertions.assertTrue(
          Arrays.asList(level3Roles).contains(role3Name),
          "Level3 schema should have role3 bound, got: " + Arrays.toString(level3Roles));
      Assertions.assertFalse(
          Arrays.asList(level3Roles).contains(role1Name),
          "Level3 schema should NOT have role1 bound");
      Assertions.assertFalse(
          Arrays.asList(level3Roles).contains(role2Name),
          "Level3 schema should NOT have role2 bound");

      // Revoke privileges and verify roles are unbound
      metalake.revokePrivilegesFromRole(
          role1Name, level1Object, ImmutableSet.of(Privileges.UseSchema.allow()));
      String[] level1RolesAfter = schema1.supportsRoles().listBindingRoleNames();
      Assertions.assertFalse(
          Arrays.asList(level1RolesAfter).contains(role1Name),
          "Level1 schema should no longer have role1 after revoke");
    } finally {
      // Clean up
      metalake.deleteRole(role1Name);
      metalake.deleteRole(role2Name);
      metalake.deleteRole(role3Name);
      icebergCatalog.asSchemas().dropSchema(level1, false);
    }
  }

  @Test
  @DisplayName(
      "Control test: grant/revoke on a flat (non-hierarchical) schema to isolate cache behavior")
  public void testRoleBindingRevokeOnFlatSchema() {
    // This is a control test to determine whether the revoke+listBindingRoleNames issue
    // is specific to hierarchical schemas or a general caching/revoke problem.
    String flatSchema = testRunPrefix + "_flat_role";

    icebergCatalog
        .asSchemas()
        .createSchema(flatSchema, "flat schema for role", Collections.emptyMap());

    String roleName = testRunPrefix + "_role_flat";
    metalake.createRole(roleName, Collections.emptyMap(), Collections.emptyList());

    try {
      // Grant UseSchema privilege to role on the flat schema
      MetadataObject schemaObject =
          MetadataObjects.of(icebergCatalogName, flatSchema, MetadataObject.Type.SCHEMA);
      metalake.grantPrivilegesToRole(
          roleName, schemaObject, ImmutableSet.of(Privileges.UseSchema.allow()));

      // Verify role is bound
      Schema schema = icebergCatalog.asSchemas().loadSchema(flatSchema);
      String[] rolesAfterGrant = schema.supportsRoles().listBindingRoleNames();
      LOG.info(
          "Roles bound to flat schema '{}' after grant: {}",
          flatSchema,
          Arrays.toString(rolesAfterGrant));
      Assertions.assertTrue(
          Arrays.asList(rolesAfterGrant).contains(roleName),
          "Flat schema should have role bound after grant, got: "
              + Arrays.toString(rolesAfterGrant));

      // Revoke the privilege
      metalake.revokePrivilegesFromRole(
          roleName, schemaObject, ImmutableSet.of(Privileges.UseSchema.allow()));

      // Verify role is unbound after revoke
      String[] rolesAfterRevoke = schema.supportsRoles().listBindingRoleNames();
      LOG.info(
          "Roles bound to flat schema '{}' after revoke: {}",
          flatSchema,
          Arrays.toString(rolesAfterRevoke));
      Assertions.assertFalse(
          Arrays.asList(rolesAfterRevoke).contains(roleName),
          "Flat schema should no longer have role after revoke, got: "
              + Arrays.toString(rolesAfterRevoke));
    } finally {
      // Clean up
      metalake.deleteRole(roleName);
      icebergCatalog.asSchemas().dropSchema(flatSchema, false);
    }
  }
}
