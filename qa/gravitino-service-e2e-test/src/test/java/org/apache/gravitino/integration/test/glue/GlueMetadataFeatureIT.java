/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.integration.test.glue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.NoSuchTagException;
import org.apache.gravitino.exceptions.TagAlreadyAssociatedException;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagChange;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration tests for Glue catalog Gravitino metadata feature integration.
 *
 * <p>Test plan section 7: Gravitino Metadata Feature Integration
 *
 * <ul>
 *   <li>7.1 Tag association with Glue schema/table — verify tag operations work correctly on Glue
 *       catalog
 *   <li>7.2 Policy association with Glue schema/table — verify policy operations work correctly on
 *       Glue catalog
 *   <li>7.3 Owner setting — verify setOwner works correctly on Glue schema/table
 *   <li>7.4 Audit information — verify auditInfo (creator, createTime) is recorded correctly
 * </ul>
 *
 * <p>Tests run against a real Gravitino server with a Glue catalog configured.
 */
@DisplayName("Glue Catalog Metadata Feature Integration Tests")
public class GlueMetadataFeatureIT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueMetadataFeatureIT.class);

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static Catalog glueCatalog;
  private static String metalakeName;
  private static String glueCatalogName;

  /** Unique prefix for this test run to avoid collisions across parallel runs. */
  private static String testRunPrefix;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");

    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(simpleUser).build();

    metalakeName = RandomNameUtils.genRandomName("glue_meta_metalake");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Metalake for Glue metadata feature tests", Collections.emptyMap());

    glueCatalogName = RandomNameUtils.genRandomName("glue_meta");
    Map<String, String> glueProps = Maps.newHashMap();
    glueProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    glueProps.put("aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    glueProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));

    String accessKey = System.getProperty("glue.aws.access.key.id");
    String secretKey = System.getProperty("glue.aws.secret.access.key");
    if (accessKey != null && secretKey != null) {
      glueProps.put("aws-access-key-id", accessKey);
      glueProps.put("aws-secret-access-key", secretKey);
    }

    String glueEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueEndpoint != null) {
      glueProps.put("aws-glue-endpoint", glueEndpoint);
    }

    glueCatalog =
        metalake.createCatalog(
            glueCatalogName,
            Catalog.Type.RELATIONAL,
            "glue",
            "Glue catalog for metadata feature tests",
            glueProps);

    testRunPrefix = RandomNameUtils.genRandomName("gm");
    LOG.info(
        "GlueMetadataFeatureIT setup complete: metalake={}, glueCatalog={}, prefix={}",
        metalakeName,
        glueCatalogName,
        testRunPrefix);
  }

  @AfterAll
  public static void teardown() {
    try {
      if (metalake != null && glueCatalogName != null) {
        metalake.dropCatalog(glueCatalogName, true);
      }
      if (adminClient != null && metalakeName != null) {
        adminClient.dropMetalake(metalakeName, true);
      }
    } catch (Exception e) {
      LOG.warn("Teardown failed, proceeding anyway", e);
    } finally {
      if (adminClient != null) {
        adminClient.close();
      }
    }
  }

  @AfterEach
  public void cleanupSchemas() {
    try {
      String[] schemas = glueCatalog.asSchemas().listSchemas();
      for (String schema : schemas) {
        if (schema.startsWith(testRunPrefix)) {
          try {
            glueCatalog.asSchemas().dropSchema(schema, true);
          } catch (Exception e) {
            LOG.warn("Failed to cleanup schema: {}", schema, e);
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to list schemas during cleanup", e);
    }
  }

  // ── 7.1 Tag association with Glue schema/table ─────────────────────────────

  @Test
  @DisplayName("7.1.1 Create, get, list, alter, and delete tags at metalake level")
  public void testTagCrudOnMetalake() {
    // Create tags
    String tagName1 = testRunPrefix + "_tag1";
    String tagName2 = testRunPrefix + "_tag2";

    Tag tag1 = metalake.createTag(tagName1, "First test tag", Collections.emptyMap());
    Assertions.assertEquals(tagName1, tag1.name());
    Assertions.assertEquals("First test tag", tag1.comment());

    Tag tag2 =
        metalake.createTag(tagName2, "Second test tag", Map.of("priority", "high", "env", "test"));
    Assertions.assertEquals(tagName2, tag2.name());
    Assertions.assertEquals("Second test tag", tag2.comment());
    Assertions.assertEquals("high", tag2.properties().get("priority"));

    // Get tag
    Tag fetched = metalake.getTag(tagName1);
    Assertions.assertEquals(tag1, fetched);

    // List tags
    String[] tagNames = metalake.listTags();
    Set<String> tagNameSet = Sets.newHashSet(tagNames);
    Assertions.assertTrue(tagNameSet.contains(tagName1));
    Assertions.assertTrue(tagNameSet.contains(tagName2));

    // List tags info
    Tag[] tagInfos = metalake.listTagsInfo();
    Assertions.assertEquals(2, tagInfos.length);

    // Alter tag — rename and update comment
    String newTagName = testRunPrefix + "_tag1_renamed";
    Tag altered =
        metalake.alterTag(
            tagName1, TagChange.rename(newTagName), TagChange.updateComment("Updated comment"));
    Assertions.assertEquals(newTagName, altered.name());
    Assertions.assertEquals("Updated comment", altered.comment());

    // Alter tag — set and remove property
    Tag alteredProps =
        metalake.alterTag(
            tagName2,
            TagChange.setProperty("newKey", "newValue"),
            TagChange.removeProperty("priority"));
    Assertions.assertEquals("newValue", alteredProps.properties().get("newKey"));
    Assertions.assertNull(alteredProps.properties().get("priority"));
    Assertions.assertEquals("test", alteredProps.properties().get("env"));

    // Delete tags
    Assertions.assertTrue(metalake.deleteTag(newTagName));
    Assertions.assertFalse(metalake.deleteTag(newTagName));
    Assertions.assertTrue(metalake.deleteTag(tagName2));

    // Verify deleted
    Assertions.assertThrows(NoSuchTagException.class, () -> metalake.getTag(newTagName));
  }

  @Test
  @DisplayName("7.1.2 Associate and disassociate tags to Glue catalog")
  public void testAssociateTagsToGlueCatalog() {
    String tagName1 = testRunPrefix + "_cat_tag1";
    String tagName2 = testRunPrefix + "_cat_tag2";
    metalake.createTag(tagName1, "catalog tag 1", Collections.emptyMap());
    metalake.createTag(tagName2, "catalog tag 2", Collections.emptyMap());

    try {
      // Associate tags to catalog
      String[] associated =
          glueCatalog.supportsTags().associateTags(new String[] {tagName1, tagName2}, null);
      Assertions.assertEquals(2, associated.length);
      Set<String> associatedSet = Sets.newHashSet(associated);
      Assertions.assertTrue(associatedSet.contains(tagName1));
      Assertions.assertTrue(associatedSet.contains(tagName2));

      // List tags on catalog
      String[] listedTags = glueCatalog.supportsTags().listTags();
      Assertions.assertEquals(2, listedTags.length);

      // Get tag details
      Tag[] tagInfos = glueCatalog.supportsTags().listTagsInfo();
      Assertions.assertEquals(2, tagInfos.length);
      for (Tag t : tagInfos) {
        Assertions.assertFalse(t.inherited().get(), "Catalog tags should not be inherited");
      }

      // Get specific tag
      Tag fetchedTag = glueCatalog.supportsTags().getTag(tagName1);
      Assertions.assertEquals(tagName1, fetchedTag.name());
      Assertions.assertFalse(fetchedTag.inherited().get());

      // Get non-existent tag should throw
      Assertions.assertThrows(
          NoSuchTagException.class, () -> glueCatalog.supportsTags().getTag("non_existent_tag"));

      // Disassociate one tag
      String[] remaining = glueCatalog.supportsTags().associateTags(null, new String[] {tagName1});
      Assertions.assertEquals(1, remaining.length);
      Assertions.assertEquals(tagName2, remaining[0]);

      // Disassociate the other
      String[] empty = glueCatalog.supportsTags().associateTags(null, new String[] {tagName2});
      Assertions.assertEquals(0, empty.length);
    } finally {
      // Cleanup tags
      try {
        glueCatalog.supportsTags().associateTags(null, new String[] {tagName1, tagName2});
      } catch (Exception e) {
        // ignore
      }
      metalake.deleteTag(tagName1);
      metalake.deleteTag(tagName2);
    }
  }

  @Test
  @DisplayName("7.1.3 Associate and disassociate tags to Glue schema")
  public void testAssociateTagsToGlueSchema() {
    String schemaName = testRunPrefix + "_tag_schema";
    Schema glueSchema =
        glueCatalog
            .asSchemas()
            .createSchema(schemaName, "Schema for tag tests", Collections.emptyMap());

    String tagName1 = testRunPrefix + "_schema_tag1";
    String tagName2 = testRunPrefix + "_schema_tag2";
    metalake.createTag(tagName1, "schema tag 1", Collections.emptyMap());
    metalake.createTag(tagName2, "schema tag 2", Collections.emptyMap());

    try {
      // Associate tags to schema
      String[] associated =
          glueSchema.supportsTags().associateTags(new String[] {tagName1, tagName2}, null);
      Assertions.assertEquals(2, associated.length);

      // List tags on schema
      String[] listedTags = glueSchema.supportsTags().listTags();
      Assertions.assertEquals(2, listedTags.length);

      // Get tag details — should show non-inherited
      Tag[] tagInfos = glueSchema.supportsTags().listTagsInfo();
      Assertions.assertEquals(2, tagInfos.length);
      for (Tag t : tagInfos) {
        Assertions.assertFalse(t.inherited().get(), "Schema-level tags should not be inherited");
      }

      // Disassociate tags
      String[] afterRemove =
          glueSchema.supportsTags().associateTags(null, new String[] {tagName1, tagName2});
      Assertions.assertEquals(0, afterRemove.length);
    } finally {
      // Cleanup
      try {
        glueSchema.supportsTags().associateTags(null, new String[] {tagName1, tagName2});
      } catch (Exception e) {
        // ignore
      }
      metalake.deleteTag(tagName1);
      metalake.deleteTag(tagName2);
    }
  }

  @Test
  @DisplayName("7.1.4 Associate and disassociate tags to Glue table")
  public void testAssociateTagsToGlueTable() {
    String schemaName = testRunPrefix + "_tag_tbl";
    glueCatalog
        .asSchemas()
        .createSchema(schemaName, "Schema for table tag tests", Collections.emptyMap());

    String tableName = "tag_test_table";
    Column[] columns = {
      Column.of("id", Types.IntegerType.get(), "id column"),
      Column.of("name", Types.StringType.get(), "name column")
    };
    Table glueTable =
        glueCatalog
            .asTableCatalog()
            .createTable(
                NameIdentifier.of(schemaName, tableName),
                columns,
                "Table for tag tests",
                Collections.emptyMap());

    String tagName1 = testRunPrefix + "_table_tag1";
    String tagName2 = testRunPrefix + "_table_tag2";
    metalake.createTag(tagName1, "table tag 1", Collections.emptyMap());
    metalake.createTag(tagName2, "table tag 2", Collections.emptyMap());

    try {
      // Associate tags to table
      String[] associated =
          glueTable.supportsTags().associateTags(new String[] {tagName1, tagName2}, null);
      Assertions.assertEquals(2, associated.length);

      // List tags on table
      String[] listedTags = glueTable.supportsTags().listTags();
      Assertions.assertEquals(2, listedTags.length);

      // Get tag details — should show non-inherited
      Tag[] tagInfos = glueTable.supportsTags().listTagsInfo();
      Assertions.assertEquals(2, tagInfos.length);
      for (Tag t : tagInfos) {
        Assertions.assertFalse(t.inherited().get(), "Table-level tags should not be inherited");
      }

      // Disassociate one tag
      String[] remaining = glueTable.supportsTags().associateTags(null, new String[] {tagName1});
      Assertions.assertEquals(1, remaining.length);
      Assertions.assertEquals(tagName2, remaining[0]);

      // Verify after removing the last tag
      String[] empty = glueTable.supportsTags().associateTags(null, new String[] {tagName2});
      Assertions.assertEquals(0, empty.length);
    } finally {
      // Cleanup
      try {
        glueTable.supportsTags().associateTags(null, new String[] {tagName1, tagName2});
      } catch (Exception e) {
        // ignore
      }
      metalake.deleteTag(tagName1);
      metalake.deleteTag(tagName2);
    }
  }

  @Test
  @DisplayName("7.1.5 Tag inheritance: catalog tag is inherited by schema and table")
  public void testTagInheritanceFromCatalogToSchemaAndTable() {
    String schemaName = testRunPrefix + "_tag_inh";
    Schema glueSchema =
        glueCatalog
            .asSchemas()
            .createSchema(schemaName, "Schema for tag inheritance", Collections.emptyMap());

    String tableName = "tag_inherit_table";
    Column[] columns = {
      Column.of("id", Types.IntegerType.get(), "id column"),
      Column.of("name", Types.StringType.get(), "name column")
    };
    Table glueTable =
        glueCatalog
            .asTableCatalog()
            .createTable(
                NameIdentifier.of(schemaName, tableName),
                columns,
                "Table for tag inheritance",
                Collections.emptyMap());

    String catalogTag = testRunPrefix + "_inherit_cat";
    String schemaTag = testRunPrefix + "_inherit_schema";
    String tableTag = testRunPrefix + "_inherit_table";
    metalake.createTag(catalogTag, "catalog-level tag", Collections.emptyMap());
    metalake.createTag(schemaTag, "schema-level tag", Collections.emptyMap());
    metalake.createTag(tableTag, "table-level tag", Collections.emptyMap());

    try {
      // Associate tags at different levels
      glueCatalog.supportsTags().associateTags(new String[] {catalogTag}, null);
      glueSchema.supportsTags().associateTags(new String[] {schemaTag}, null);
      glueTable.supportsTags().associateTags(new String[] {tableTag}, null);

      // Verify table sees all 3 tags (1 direct + 2 inherited)
      String[] tableTags = glueTable.supportsTags().listTags();
      Assertions.assertEquals(3, tableTags.length);
      Set<String> tableTagSet = Sets.newHashSet(tableTags);
      Assertions.assertTrue(tableTagSet.contains(catalogTag));
      Assertions.assertTrue(tableTagSet.contains(schemaTag));
      Assertions.assertTrue(tableTagSet.contains(tableTag));

      // Verify inheritance flags on table
      Tag[] tableTagInfos = glueTable.supportsTags().listTagsInfo();
      Assertions.assertEquals(3, tableTagInfos.length);

      Set<Tag> inheritedTags =
          Arrays.stream(tableTagInfos).filter(t -> t.inherited().get()).collect(Collectors.toSet());
      Set<Tag> directTags =
          Arrays.stream(tableTagInfos)
              .filter(t -> !t.inherited().get())
              .collect(Collectors.toSet());

      Assertions.assertEquals(2, inheritedTags.size(), "Table should have 2 inherited tags");
      Assertions.assertEquals(1, directTags.size(), "Table should have 1 direct tag");

      Set<String> inheritedNames =
          inheritedTags.stream().map(Tag::name).collect(Collectors.toSet());
      Assertions.assertTrue(inheritedNames.contains(catalogTag));
      Assertions.assertTrue(inheritedNames.contains(schemaTag));

      Set<String> directNames = directTags.stream().map(Tag::name).collect(Collectors.toSet());
      Assertions.assertTrue(directNames.contains(tableTag));

      // Verify schema sees 2 tags (1 direct + 1 inherited from catalog)
      String[] schemaTagsList = glueSchema.supportsTags().listTags();
      Assertions.assertEquals(2, schemaTagsList.length);

      Tag[] schemaTagInfos = glueSchema.supportsTags().listTagsInfo();
      Set<Tag> schemaInherited =
          Arrays.stream(schemaTagInfos)
              .filter(t -> t.inherited().get())
              .collect(Collectors.toSet());
      Set<Tag> schemaDirect =
          Arrays.stream(schemaTagInfos)
              .filter(t -> !t.inherited().get())
              .collect(Collectors.toSet());

      Assertions.assertEquals(1, schemaInherited.size());
      Assertions.assertEquals(1, schemaDirect.size());
      Assertions.assertEquals(catalogTag, schemaInherited.iterator().next().name());
      Assertions.assertEquals(schemaTag, schemaDirect.iterator().next().name());

      // Verify catalog has only its own tag (not inherited)
      Tag[] catalogTagInfos = glueCatalog.supportsTags().listTagsInfo();
      Assertions.assertEquals(1, catalogTagInfos.length);
      Assertions.assertFalse(catalogTagInfos[0].inherited().get());
      Assertions.assertEquals(catalogTag, catalogTagInfos[0].name());
    } finally {
      // Cleanup
      try {
        glueTable.supportsTags().associateTags(null, new String[] {tableTag});
      } catch (Exception e) {
        // ignore
      }
      try {
        glueSchema.supportsTags().associateTags(null, new String[] {schemaTag});
      } catch (Exception e) {
        // ignore
      }
      try {
        glueCatalog.supportsTags().associateTags(null, new String[] {catalogTag});
      } catch (Exception e) {
        // ignore
      }
      metalake.deleteTag(catalogTag);
      metalake.deleteTag(schemaTag);
      metalake.deleteTag(tableTag);
    }
  }

  @Test
  @DisplayName(
      "7.1.6 Associating an already-associated tag should throw TagAlreadyAssociatedException")
  public void testDuplicateTagAssociationThrows() {
    String schemaName = testRunPrefix + "_tag_dup";
    Schema glueSchema =
        glueCatalog
            .asSchemas()
            .createSchema(schemaName, "Schema for dup tag test", Collections.emptyMap());

    String tagName = testRunPrefix + "_dup_tag";
    metalake.createTag(tagName, "duplicate test tag", Collections.emptyMap());

    try {
      // First association should succeed
      glueSchema.supportsTags().associateTags(new String[] {tagName}, null);

      // Second association of the same tag should throw
      Assertions.assertThrows(
          TagAlreadyAssociatedException.class,
          () -> glueSchema.supportsTags().associateTags(new String[] {tagName}, null),
          "Associating an already-associated tag should throw TagAlreadyAssociatedException");
    } finally {
      try {
        glueSchema.supportsTags().associateTags(null, new String[] {tagName});
      } catch (Exception e) {
        // ignore
      }
      metalake.deleteTag(tagName);
    }
  }

  @Test
  @DisplayName("7.1.7 Associating a non-existent tag should be ignored (not throw)")
  public void testAssociateNonExistentTagIsIgnored() {
    // Associating a tag that does not exist in the metalake should be silently ignored
    String[] result =
        glueCatalog.supportsTags().associateTags(new String[] {"non_existent_tag_xyz"}, null);
    Assertions.assertEquals(0, result.length, "Non-existent tag association should be ignored");
  }

  @Test
  @DisplayName("7.1.8 Deleting a tag at metalake level removes it from all associated objects")
  public void testDeleteTagRemovesFromAssociatedObjects() {
    String schemaName = testRunPrefix + "_tag_del";
    Schema glueSchema =
        glueCatalog
            .asSchemas()
            .createSchema(schemaName, "Schema for tag delete test", Collections.emptyMap());

    String tagName = testRunPrefix + "_del_assoc";
    metalake.createTag(tagName, "tag to delete", Collections.emptyMap());

    // Associate tag to schema
    glueSchema.supportsTags().associateTags(new String[] {tagName}, null);

    // Verify tag is associated
    String[] beforeDelete = glueSchema.supportsTags().listTags();
    Assertions.assertEquals(1, beforeDelete.length);
    Assertions.assertEquals(tagName, beforeDelete[0]);

    // Delete tag at metalake level
    Assertions.assertTrue(metalake.deleteTag(tagName));

    // Verify tag is removed from schema
    String[] afterDelete = glueSchema.supportsTags().listTags();
    Assertions.assertEquals(0, afterDelete.length);
  }

  // ── 7.2 Policy association with Glue schema/table ────────────────────────

  @Test
  @DisplayName("7.2 Policy association - associate/list/get/disassociate on Glue schema and table")
  public void testPolicyAssociationWithGlueSchemaAndTable() {
    String schemaName = testRunPrefix + "_policy";

    // Create schema and table
    glueCatalog.asSchemas().createSchema(schemaName, "policy test schema", Collections.emptyMap());

    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("data", Types.StringType.get(), "data column")
    };
    String tableName = "policy_test_table";
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, "policy test table", tableProps);

    // Create a policy on the metalake
    String policyName = testRunPrefix + "_custom_policy";
    Set<MetadataObject.Type> supportedTypes =
        ImmutableSet.of(MetadataObject.Type.SCHEMA, MetadataObject.Type.TABLE);
    metalake.createPolicy(
        policyName,
        "custom",
        "test policy for Glue catalog",
        true,
        PolicyContents.custom(
            Collections.singletonMap("retention_days", "30"),
            supportedTypes,
            Collections.emptyMap()));

    try {
      // ── Associate policy with schema ──
      Schema schema = glueCatalog.asSchemas().loadSchema(schemaName);
      String[] schemaPolicies =
          schema.supportsPolicies().associatePolicies(new String[] {policyName}, new String[] {});
      Assertions.assertNotNull(
          schemaPolicies, "associatePolicies on schema should return non-null");

      LOG.info("Associated policy '{}' with Glue schema '{}'", policyName, schemaName);

      // Verify policy is listed on schema
      String[] schemaPolicyList = schema.supportsPolicies().listPolicies();
      Assertions.assertTrue(
          Arrays.asList(schemaPolicyList).contains(policyName),
          "Schema should have the policy associated, got: " + Arrays.toString(schemaPolicyList));

      // Verify policy can be retrieved from schema
      Policy schemaPolicy = schema.supportsPolicies().getPolicy(policyName);
      Assertions.assertNotNull(schemaPolicy, "Should be able to get policy from schema");
      Assertions.assertEquals(policyName, schemaPolicy.name());

      LOG.info("Policy verified on Glue schema: name={}", schemaPolicy.name());

      // ── Associate policy with table ──
      Table table =
          glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
      String[] tablePolicies =
          table.supportsPolicies().associatePolicies(new String[] {policyName}, new String[] {});
      Assertions.assertNotNull(tablePolicies, "associatePolicies on table should return non-null");

      LOG.info("Associated policy '{}' with Glue table '{}.{}'", policyName, schemaName, tableName);

      // Verify policy is listed on table
      String[] tablePolicyList = table.supportsPolicies().listPolicies();
      Assertions.assertTrue(
          Arrays.asList(tablePolicyList).contains(policyName),
          "Table should have the policy associated, got: " + Arrays.toString(tablePolicyList));

      // Verify policy can be retrieved from table
      Policy tablePolicy = table.supportsPolicies().getPolicy(policyName);
      Assertions.assertNotNull(tablePolicy, "Should be able to get policy from table");
      Assertions.assertEquals(policyName, tablePolicy.name());

      LOG.info("Policy verified on Glue table: name={}", tablePolicy.name());

      // ── Disassociate policy from schema ──
      schema.supportsPolicies().associatePolicies(new String[] {}, new String[] {policyName});
      String[] schemaPoliciesAfter = schema.supportsPolicies().listPolicies();
      Assertions.assertFalse(
          Arrays.asList(schemaPoliciesAfter).contains(policyName),
          "Schema should no longer have the policy after disassociation");

      // ── Disassociate policy from table ──
      table.supportsPolicies().associatePolicies(new String[] {}, new String[] {policyName});
      String[] tablePoliciesAfter = table.supportsPolicies().listPolicies();
      Assertions.assertFalse(
          Arrays.asList(tablePoliciesAfter).contains(policyName),
          "Table should no longer have the policy after disassociation");

      LOG.info("7.2 verified: policy association/disassociation works on Glue schema and table");
    } finally {
      // Cleanup policy
      try {
        metalake.deletePolicy(policyName);
      } catch (Exception e) {
        LOG.warn("Failed to delete policy: {}", policyName, e);
      }
    }
  }

  // ── 7.3 Owner setting ────────────────────────────────────────────────────

  @Test
  @DisplayName("7.3 Owner setting - setOwner/getOwner works correctly on Glue schema and table")
  public void testOwnerSettingOnGlueSchemaAndTable() {
    String schemaName = testRunPrefix + "_owner";

    // Create schema and table
    glueCatalog.asSchemas().createSchema(schemaName, "owner test schema", Collections.emptyMap());

    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("value", Types.StringType.get(), "value column")
    };
    String tableName = "owner_test_table";
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    glueCatalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, "owner test table", tableProps);

    // Add a user to be set as owner
    String ownerUser = "glue_owner_user_" + RandomNameUtils.genRandomName("u");
    metalake.addUser(ownerUser);

    try {
      // ── Set owner on schema ──
      MetadataObject schemaObject =
          MetadataObjects.of(
              Arrays.asList(glueCatalogName, schemaName), MetadataObject.Type.SCHEMA);
      metalake.setOwner(schemaObject, ownerUser, Owner.Type.USER);

      // Verify owner on schema
      Optional<Owner> schemaOwner = metalake.getOwner(schemaObject);
      Assertions.assertTrue(schemaOwner.isPresent(), "Schema should have an owner after setOwner");
      Assertions.assertEquals(
          ownerUser, schemaOwner.get().name(), "Schema owner name should match");
      Assertions.assertEquals(
          Owner.Type.USER, schemaOwner.get().type(), "Schema owner type should be USER");

      LOG.info("Set owner '{}' on Glue schema '{}'", ownerUser, schemaName);

      // ── Set owner on table ──
      MetadataObject tableObject =
          MetadataObjects.of(
              Arrays.asList(glueCatalogName, schemaName, tableName), MetadataObject.Type.TABLE);
      metalake.setOwner(tableObject, ownerUser, Owner.Type.USER);

      // Verify owner on table
      Optional<Owner> tableOwner = metalake.getOwner(tableObject);
      Assertions.assertTrue(tableOwner.isPresent(), "Table should have an owner after setOwner");
      Assertions.assertEquals(ownerUser, tableOwner.get().name(), "Table owner name should match");
      Assertions.assertEquals(
          Owner.Type.USER, tableOwner.get().type(), "Table owner type should be USER");

      LOG.info("Set owner '{}' on Glue table '{}.{}'", ownerUser, schemaName, tableName);

      // ── Change owner to a different user ──
      String newOwner = "glue_owner_user2_" + RandomNameUtils.genRandomName("u");
      metalake.addUser(newOwner);

      metalake.setOwner(schemaObject, newOwner, Owner.Type.USER);
      Optional<Owner> updatedSchemaOwner = metalake.getOwner(schemaObject);
      Assertions.assertTrue(
          updatedSchemaOwner.isPresent(), "Schema should have owner after ownership transfer");
      Assertions.assertEquals(
          newOwner, updatedSchemaOwner.get().name(), "Schema owner should be updated to new user");

      metalake.setOwner(tableObject, newOwner, Owner.Type.USER);
      Optional<Owner> updatedTableOwner = metalake.getOwner(tableObject);
      Assertions.assertTrue(
          updatedTableOwner.isPresent(), "Table should have owner after ownership transfer");
      Assertions.assertEquals(
          newOwner, updatedTableOwner.get().name(), "Table owner should be updated to new user");

      LOG.info("7.3 verified: setOwner/getOwner works correctly on Glue schema and table");

      // Cleanup extra user
      metalake.removeUser(newOwner);
    } finally {
      try {
        metalake.removeUser(ownerUser);
      } catch (Exception e) {
        LOG.warn("Failed to remove user: {}", ownerUser, e);
      }
    }
  }

  // ── 7.4 Audit information ────────────────────────────────────────────────

  @Test
  @DisplayName("7.4 Audit information - auditInfo (creator, createTime) recorded correctly")
  public void testAuditInfoOnGlueSchemaAndTable() {
    String schemaName = testRunPrefix + "_audit";

    // Create schema
    Schema createdSchema =
        glueCatalog
            .asSchemas()
            .createSchema(schemaName, "audit test schema", Collections.emptyMap());

    // Verify audit info on created schema
    Assertions.assertNotNull(createdSchema.auditInfo(), "Schema auditInfo should not be null");
    Assertions.assertNotNull(
        createdSchema.auditInfo().creator(), "Schema creator should not be null");
    Assertions.assertNotNull(
        createdSchema.auditInfo().createTime(), "Schema createTime should not be null");

    LOG.info(
        "Schema audit: creator={}, createTime={}",
        createdSchema.auditInfo().creator(),
        createdSchema.auditInfo().createTime());

    // Load schema and verify audit info persists
    Schema loadedSchema = glueCatalog.asSchemas().loadSchema(schemaName);
    Assertions.assertNotNull(
        loadedSchema.auditInfo(), "Loaded schema auditInfo should not be null");
    Assertions.assertNotNull(
        loadedSchema.auditInfo().creator(), "Loaded schema creator should not be null");
    Assertions.assertNotNull(
        loadedSchema.auditInfo().createTime(), "Loaded schema createTime should not be null");
    Assertions.assertEquals(
        createdSchema.auditInfo().creator(),
        loadedSchema.auditInfo().creator(),
        "Creator should be consistent between create and load");

    // Create table
    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("data", Types.StringType.get(), "data column")
    };
    String tableName = "audit_test_table";
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");

    Table createdTable =
        glueCatalog
            .asTableCatalog()
            .createTable(
                NameIdentifier.of(schemaName, tableName), columns, "audit test table", tableProps);

    // Verify audit info on created table
    Assertions.assertNotNull(createdTable.auditInfo(), "Table auditInfo should not be null");
    Assertions.assertNotNull(
        createdTable.auditInfo().creator(), "Table creator should not be null");
    Assertions.assertNotNull(
        createdTable.auditInfo().createTime(), "Table createTime should not be null");

    LOG.info(
        "Table audit: creator={}, createTime={}",
        createdTable.auditInfo().creator(),
        createdTable.auditInfo().createTime());

    // Load table and verify audit info persists
    Table loadedTable =
        glueCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName));
    Assertions.assertNotNull(loadedTable.auditInfo(), "Loaded table auditInfo should not be null");
    Assertions.assertNotNull(
        loadedTable.auditInfo().creator(), "Loaded table creator should not be null");
    Assertions.assertNotNull(
        loadedTable.auditInfo().createTime(), "Loaded table createTime should not be null");
    Assertions.assertEquals(
        createdTable.auditInfo().creator(),
        loadedTable.auditInfo().creator(),
        "Table creator should be consistent between create and load");

    LOG.info(
        "7.4 verified: auditInfo (creator, createTime) recorded correctly on schema and table");
  }
}
