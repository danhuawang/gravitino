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

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Schema;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.utils.RandomNameUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E integration tests for Glue catalog authentication and security.
 *
 * <p>Test plan section 5: Authentication and Security
 *
 * <ul>
 *   <li>5.1 Static credential authentication — aws-access-key-id + aws-secret-access-key connects
 *       successfully
 *   <li>5.4 testConnection verification — testConnection correctly validates connectivity when
 *       creating a catalog
 *   <li>5.7 Gravitino RBAC — verify USE_CATALOG, CREATE_SCHEMA, SELECT_TABLE and other privilege
 *       controls on Glue catalog
 * </ul>
 *
 * <p>Tests run against a real Gravitino server with a Glue catalog configured. The server is
 * expected to be deployed with the Glue catalog plugin available.
 */
@DisplayName("Glue Catalog Authentication Integration Tests")
public class GlueAuthenticationIT {

  private static final Logger LOG = LoggerFactory.getLogger(GlueAuthenticationIT.class);

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static String metalakeName;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String simpleUser = System.getProperty("gravitino.simple.user", "admin");

    adminClient = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth(simpleUser).build();

    metalakeName = RandomNameUtils.genRandomName("glue_auth_metalake");
    metalake =
        adminClient.createMetalake(
            metalakeName, "Metalake for Glue auth tests", Collections.emptyMap());

    LOG.info("GlueAuthenticationIT setup complete: metalake={}", metalakeName);
  }

  @AfterAll
  public static void teardown() {
    try {
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

  // ── 5.1 Static credential authentication ─────────────────────────────────

  @Test
  @DisplayName("5.1 Static credential authentication - aws-access-key-id + aws-secret-access-key")
  public void testStaticCredentialAuthentication() throws Exception {
    String accessKey = System.getProperty("glue.aws.access.key.id");
    String secretKey = System.getProperty("glue.aws.secret.access.key");

    // Skip if static credentials are not provided
    Assertions.assertNotNull(
        accessKey, "glue.aws.access.key.id system property must be set for static credential test");
    Assertions.assertNotNull(
        secretKey,
        "glue.aws.secret.access.key system property must be set for static credential test");

    // Build catalog properties with explicit static credentials
    Map<String, String> catalogProps = Maps.newHashMap();
    catalogProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    catalogProps.put(
        "aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    catalogProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));
    catalogProps.put("aws-access-key-id", accessKey);
    catalogProps.put("aws-secret-access-key", secretKey);

    String glueEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueEndpoint != null) {
      catalogProps.put("aws-glue-endpoint", glueEndpoint);
    }

    String catalogName = RandomNameUtils.genRandomName("glue_static_cred");

    // testConnection should succeed with valid static credentials
    Assertions.assertDoesNotThrow(
        () ->
            metalake.testConnection(
                catalogName, Catalog.Type.RELATIONAL, "glue", "static cred test", catalogProps),
        "testConnection should succeed with valid static credentials");

    LOG.info("testConnection with static credentials succeeded for catalog: {}", catalogName);

    // Create the catalog with static credentials and verify it works
    Catalog catalog =
        metalake.createCatalog(
            catalogName,
            Catalog.Type.RELATIONAL,
            "glue",
            "Glue catalog with static credentials",
            catalogProps);

    Assertions.assertNotNull(catalog, "Catalog should be created successfully");

    // Verify the catalog is functional by listing schemas (this exercises the AWS connection)
    String[] schemas = catalog.asSchemas().listSchemas();
    Assertions.assertNotNull(
        schemas, "listSchemas should return non-null result with valid credentials");

    LOG.info(
        "Static credential authentication verified: catalog={}, schemas found={}",
        catalogName,
        schemas.length);

    // Create a test schema to further verify write access
    String testSchemaName = RandomNameUtils.genRandomName("auth_test");
    catalog
        .asSchemas()
        .createSchema(testSchemaName, "auth verification schema", Collections.emptyMap());

    // Verify schema was created
    Assertions.assertTrue(
        catalog.asSchemas().schemaExists(testSchemaName),
        "Schema should be created successfully with static credentials");

    // Load the schema to confirm read-after-write consistency
    Schema loadedSchema = catalog.asSchemas().loadSchema(testSchemaName);
    Assertions.assertNotNull(loadedSchema, "Loaded schema should not be null");
    Assertions.assertEquals(testSchemaName, loadedSchema.name(), "Schema name should match");

    // Cleanup
    catalog.asSchemas().dropSchema(testSchemaName, false);
    metalake.dropCatalog(catalogName, true);

    LOG.info("Static credential authentication test completed successfully");
  }

  // ── 5.4 testConnection verification ──────────────────────────────────────

  @Test
  @DisplayName("5.4 testConnection correctly validates connectivity before catalog creation")
  public void testConnectionVerification() throws Exception {
    String accessKey = System.getProperty("glue.aws.access.key.id");
    String secretKey = System.getProperty("glue.aws.secret.access.key");

    Assertions.assertNotNull(
        accessKey,
        "glue.aws.access.key.id system property must be set for testConnection verification");
    Assertions.assertNotNull(
        secretKey,
        "glue.aws.secret.access.key system property must be set for testConnection verification");

    // --- Negative case: testConnection with invalid credentials should fail ---
    Map<String, String> invalidProps = Maps.newHashMap();
    invalidProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    invalidProps.put(
        "aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    invalidProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));
    invalidProps.put("aws-access-key-id", "AKIAINVALIDACCESSKEY00");
    invalidProps.put("aws-secret-access-key", "totally-wrong-secret-key");

    String glueEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueEndpoint != null) {
      invalidProps.put("aws-glue-endpoint", glueEndpoint);
    }

    String catalogName = RandomNameUtils.genRandomName("glue_test_conn");

    Assertions.assertThrows(
        Exception.class,
        () ->
            metalake.testConnection(
                catalogName, Catalog.Type.RELATIONAL, "glue", "test conn invalid", invalidProps),
        "testConnection should reject invalid credentials before catalog creation");

    LOG.info("testConnection correctly rejected invalid credentials");

    // --- Positive case: testConnection with valid credentials should succeed ---
    Map<String, String> validProps = Maps.newHashMap();
    validProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    validProps.put(
        "aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    validProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));
    validProps.put("aws-access-key-id", accessKey);
    validProps.put("aws-secret-access-key", secretKey);

    if (glueEndpoint != null) {
      validProps.put("aws-glue-endpoint", glueEndpoint);
    }

    Assertions.assertDoesNotThrow(
        () ->
            metalake.testConnection(
                catalogName, Catalog.Type.RELATIONAL, "glue", "test conn valid", validProps),
        "testConnection should succeed with valid credentials");

    LOG.info("testConnection correctly accepted valid credentials");

    // --- Verify that catalog creation succeeds after testConnection passes ---
    Catalog catalog =
        metalake.createCatalog(
            catalogName,
            Catalog.Type.RELATIONAL,
            "glue",
            "Catalog created after testConnection verification",
            validProps);

    Assertions.assertNotNull(
        catalog, "Catalog creation should succeed after testConnection passes");

    // Verify the catalog is functional
    String[] schemas = catalog.asSchemas().listSchemas();
    Assertions.assertNotNull(schemas, "listSchemas should work on the created catalog");

    // Cleanup
    metalake.dropCatalog(catalogName, true);

    LOG.info("testConnection verification test completed successfully");
  }

  // ── 5.7 Gravitino RBAC ───────────────────────────────────────────────────

  @Test
  @DisplayName("5.7 Gravitino RBAC - USE_CATALOG, CREATE_SCHEMA, SELECT_TABLE privilege controls")
  public void testGravitinoRbac() {
    String accessKey = System.getProperty("glue.aws.access.key.id");
    String secretKey = System.getProperty("glue.aws.secret.access.key");

    Assertions.assertNotNull(
        accessKey, "glue.aws.access.key.id system property must be set for RBAC test");
    Assertions.assertNotNull(
        secretKey, "glue.aws.secret.access.key system property must be set for RBAC test");

    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");

    // Create a Glue catalog for RBAC testing
    String catalogName = RandomNameUtils.genRandomName("glue_rbac");
    Map<String, String> catalogProps = Maps.newHashMap();
    catalogProps.put("aws-region", System.getProperty("glue.aws.region", "us-east-1"));
    catalogProps.put(
        "aws-glue-catalog-id", System.getProperty("glue.aws.catalog.id", "730335553010"));
    catalogProps.put(
        "warehouse",
        System.getProperty("glue.aws.warehouse", "s3://gravitino-glue-test/warehouse"));
    catalogProps.put("aws-access-key-id", accessKey);
    catalogProps.put("aws-secret-access-key", secretKey);

    String glueEndpoint = System.getProperty("glue.aws.endpoint");
    if (glueEndpoint != null) {
      catalogProps.put("aws-glue-endpoint", glueEndpoint);
    }

    Catalog catalog =
        metalake.createCatalog(
            catalogName, Catalog.Type.RELATIONAL, "glue", "Glue RBAC test catalog", catalogProps);

    // Create a schema and table as admin for testing
    String schemaName = RandomNameUtils.genRandomName("rbac_schema");
    catalog.asSchemas().createSchema(schemaName, "RBAC test schema", Collections.emptyMap());

    Column[] columns = {
      Column.of("id", Types.LongType.get(), "primary key"),
      Column.of("name", Types.StringType.get(), "user name")
    };
    String tableName = "rbac_test_table";
    Map<String, String> tableProps = Maps.newHashMap();
    tableProps.put("input-format", "org.apache.hadoop.mapred.TextInputFormat");
    tableProps.put("output-format", "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
    tableProps.put("serde-lib", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(schemaName, tableName), columns, "RBAC test table", tableProps);

    // Create a user and a role with no privileges
    String userName = "glue_rbac_user_" + RandomNameUtils.genRandomName("u");
    String roleName = "glue_rbac_role_" + RandomNameUtils.genRandomName("r");

    metalake.addUser(userName);
    metalake.createRole(roleName, Collections.emptyMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    try {
      // Build a user-specific client
      GravitinoClient userClient =
          GravitinoClient.builder(gravitinoUri)
              .withMetalake(metalakeName)
              .withSimpleAuth(userName)
              .build();
      GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

      // ── Negative: user without USE_CATALOG cannot load catalog ──
      Assertions.assertThrows(
          ForbiddenException.class,
          () -> userMetalake.loadCatalog(catalogName),
          "User without USE_CATALOG should be denied access to the catalog");

      LOG.info("RBAC: User without USE_CATALOG correctly denied");

      // ── Grant USE_CATALOG ──
      MetadataObject catalogObject =
          MetadataObjects.of(null, catalogName, MetadataObject.Type.CATALOG);
      metalake.grantPrivilegesToRole(
          roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

      // User can now load the catalog
      Catalog userCatalog = userMetalake.loadCatalog(catalogName);
      Assertions.assertNotNull(userCatalog, "User with USE_CATALOG should be able to load catalog");

      LOG.info("RBAC: User with USE_CATALOG can load catalog");

      // ── Negative: user without CREATE_SCHEMA cannot create schema ──
      Assertions.assertThrows(
          ForbiddenException.class,
          () ->
              userCatalog
                  .asSchemas()
                  .createSchema("unauthorized_schema", "should fail", Collections.emptyMap()),
          "User without CREATE_SCHEMA should be denied");

      LOG.info("RBAC: User without CREATE_SCHEMA correctly denied");

      // ── Grant USE_SCHEMA + CREATE_SCHEMA on catalog level ──
      metalake.grantPrivilegesToRole(
          roleName,
          catalogObject,
          Sets.newHashSet(Privileges.UseSchema.allow(), Privileges.CreateSchema.allow()));

      // User can now create a schema
      String userSchemaName = RandomNameUtils.genRandomName("user_schema");
      Schema userSchema =
          userCatalog
              .asSchemas()
              .createSchema(userSchemaName, "user-created schema", Collections.emptyMap());
      Assertions.assertNotNull(userSchema, "User with CREATE_SCHEMA should create schema");

      LOG.info("RBAC: User with CREATE_SCHEMA can create schema");

      // ── Negative: user without SELECT_TABLE cannot load table ──
      Assertions.assertThrows(
          ForbiddenException.class,
          () -> userCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName)),
          "User without SELECT_TABLE should be denied table access");

      LOG.info("RBAC: User without SELECT_TABLE correctly denied");

      // ── Grant SELECT_TABLE on the specific table ──
      MetadataObject tableObject =
          MetadataObjects.of(catalogName + "." + schemaName, tableName, MetadataObject.Type.TABLE);
      metalake.grantPrivilegesToRole(
          roleName, tableObject, Sets.newHashSet(Privileges.SelectTable.allow()));

      // User can now load the table
      Assertions.assertDoesNotThrow(
          () -> userCatalog.asTableCatalog().loadTable(NameIdentifier.of(schemaName, tableName)),
          "User with SELECT_TABLE should be able to load table");

      LOG.info("RBAC: User with SELECT_TABLE can load table");

      // Cleanup user-created schema
      catalog.asSchemas().dropSchema(userSchemaName, false);
      userClient.close();
    } finally {
      // Cleanup RBAC objects
      try {
        metalake.removeUser(userName);
      } catch (Exception e) {
        LOG.warn("Failed to remove user: {}", userName, e);
      }
      try {
        metalake.deleteRole(roleName);
      } catch (Exception e) {
        LOG.warn("Failed to delete role: {}", roleName, e);
      }

      // Cleanup catalog resources
      try {
        catalog.asTableCatalog().dropTable(NameIdentifier.of(schemaName, tableName));
        catalog.asSchemas().dropSchema(schemaName, true);
        metalake.dropCatalog(catalogName, true);
      } catch (Exception e) {
        LOG.warn("Failed to cleanup catalog resources", e);
      }

      LOG.info("Gravitino RBAC test completed successfully");
    }
  }
}
