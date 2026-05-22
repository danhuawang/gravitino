/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.function;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.CatalogChange;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.ForbiddenException;
import org.apache.gravitino.function.Function;
import org.apache.gravitino.function.FunctionChange;
import org.apache.gravitino.function.FunctionDefinition;
import org.apache.gravitino.function.FunctionDefinitions;
import org.apache.gravitino.function.FunctionImpl;
import org.apache.gravitino.function.FunctionImpls;
import org.apache.gravitino.function.FunctionParams;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.rel.types.Types;
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
 * E2E integration tests for Function Privilege control.
 *
 * <p>Tests run against a real Gravitino server deployed in Kubernetes with authorization enabled.
 * The server is deployed via Helm chart with authorization configuration in
 * qa/k8s/helm-values/env1-simple-auth-values.yaml
 *
 * <p>Unlike BaseIT's embedded mode, this test connects to an external Gravitino server running in
 * K8s, so it does NOT start a local MiniGravitino instance.
 */
@DisplayName("Function Privilege Integration Tests")
public class FunctionPrivilegeIT {

  private static final Logger LOG = LoggerFactory.getLogger(FunctionPrivilegeIT.class);

  private static String metalakeName = RandomNameUtils.genRandomName("metalake");
  private static GravitinoMetalake metalake;
  private static GravitinoAdminClient client;
  private static Catalog catalog;
  private static String schemaName = RandomNameUtils.genRandomName("test_schema");
  private static String serverUri;

  @BeforeAll
  public static void startIntegrationTest() throws Exception {
    // In K8s e2e tests, Gravitino server is already deployed with authorization enabled
    // via Helm chart (see qa/k8s/helm-values/env1-simple-auth-values.yaml)
    // We just need to connect to it using the URI from environment variable

    // Get Gravitino URI from system property (set by build.gradle.kts from env var)
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    String hiveMetastoreUri = System.getProperty("hive.metastore.uri", "thrift://localhost:30083");
    serverUri = gravitinoUri;

    // Create admin client to connect to K8s-deployed Gravitino server
    // Use "admin" user as configured in serviceAdmins (see
    // qa/k8s/helm-values/env1-simple-auth-values.yaml)
    client = GravitinoAdminClient.builder(gravitinoUri).withSimpleAuth("admin").build();

    // Create metalake and catalog
    metalake = client.createMetalake(metalakeName, "metalake comment", Collections.emptyMap());

    // Create Hive catalog with required metastore.uris property
    Map<String, String> catalogProperties = Maps.newHashMap();
    catalogProperties.put("metastore.uris", hiveMetastoreUri);

    catalog =
        metalake.createCatalog(
            "hive_catalog", Catalog.Type.RELATIONAL, "hive", "comment", catalogProperties);

    // Create schema
    catalog.asSchemas().createSchema(schemaName, "comment", Collections.emptyMap());

    // Register test functions
    registerTestFunction("func1");
    registerTestFunction("func2");
  }

  private static void registerTestFunction(String funcName) {
    FunctionDefinition sparkDef =
        FunctionDefinitions.of(
            FunctionParams.of(FunctionParams.of("input", Types.IntegerType.get())),
            Types.IntegerType.get(),
            FunctionImpls.of(
                FunctionImpls.ofJava(FunctionImpl.RuntimeType.SPARK, "com.example.TestFunction")));

    catalog
        .asFunctionCatalog()
        .registerFunction(
            NameIdentifier.of(schemaName, funcName),
            "test function",
            FunctionType.SCALAR,
            true,
            FunctionDefinitions.of(sparkDef));
  }

  @AfterEach
  public void cleanup() {
    // Clean up users and roles created in tests
    try {
      for (String userName : metalake.listUserNames()) {
        if (!userName.equals(AuthConstants.ANONYMOUS_USER)) {
          metalake.removeUser(userName);
        }
      }
      for (String roleName : metalake.listRoleNames()) {
        metalake.deleteRole(roleName);
      }
    } catch (Exception e) {
      // Ignore cleanup errors
    }
  }

  @AfterAll
  public static void stopIntegrationTest() {
    // Clean up metalake
    try {
      if (client != null && metalake != null) {
        client.dropMetalake(metalakeName);
      }
    } catch (Exception e) {
      LOG.warn("Failed to clean up metalake", e);
    }
  }

  @Test
  @DisplayName("User with REGISTER_FUNCTION privilege can register new functions in schema")
  public void testRegisterFunctionWithPrivilege() {
    String userName = "user_register";
    String roleName = "role_register";

    // 1. Create user without privilege
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // 2. User tries to register function → should fail
    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    Assertions.assertThrows(
        ForbiddenException.class,
        () ->
            userMetalake
                .loadCatalog("hive_catalog")
                .asFunctionCatalog()
                .registerFunction(
                    NameIdentifier.of(schemaName, "new_func"),
                    "comment",
                    FunctionType.SCALAR,
                    true,
                    FunctionDefinitions.of()));

    // 3. Grant REGISTER_FUNCTION privilege on schema, and USE privileges on catalog/schema
    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName,
        schemaObject,
        Sets.newHashSet(Privileges.RegisterFunction.allow(), Privileges.UseSchema.allow()));

    // 4. User registers function → should succeed
    FunctionDefinition sparkDef =
        FunctionDefinitions.of(
            FunctionParams.of(FunctionParams.of("input", Types.IntegerType.get())),
            Types.IntegerType.get(),
            FunctionImpls.of(
                FunctionImpls.ofJava(FunctionImpl.RuntimeType.SPARK, "com.example.TestFunction")));

    Function func =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .registerFunction(
                NameIdentifier.of(schemaName, "new_func"),
                "comment",
                FunctionType.SCALAR,
                true,
                FunctionDefinitions.of(sparkDef));
    Assertions.assertEquals("new_func", func.name());
  }

  @Test
  @DisplayName("User with EXECUTE_FUNCTION privilege can get and execute functions")
  public void testGetFunctionWithExecutePrivilege() {
    String userName = "user_execute";
    String roleName = "role_execute";

    // 1. Create user without privilege
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // 2. User tries to get function → should fail
    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    Assertions.assertThrows(
        Exception.class,
        () ->
            userMetalake
                .loadCatalog("hive_catalog")
                .asFunctionCatalog()
                .getFunction(NameIdentifier.of(schemaName, "func1")));

    // 3. Grant EXECUTE_FUNCTION privilege on function, and USE privileges on catalog/schema
    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Sets.newHashSet(Privileges.UseSchema.allow()));

    MetadataObject funcObject =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, funcObject, Sets.newHashSet(Privileges.ExecuteFunction.allow()));

    // 4. User gets function → should succeed
    Function func =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, "func1"));
    Assertions.assertEquals("func1", func.name());
  }

  @Test
  @DisplayName("List functions returns only functions user has privileges on (visibility filter)")
  public void testListFunctionsVisibilityFilter() {
    String userName = "user_list";
    String roleName = "role_list";

    // 1. Create user with EXECUTE_FUNCTION on func1 only
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // Grant USE privileges on catalog and schema
    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Sets.newHashSet(Privileges.UseSchema.allow()));

    // Grant EXECUTE_FUNCTION on func1
    MetadataObject func1Object =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, func1Object, Sets.newHashSet(Privileges.ExecuteFunction.allow()));

    // 2. User lists functions → should only see func1
    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    NameIdentifier[] functions =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .listFunctions(Namespace.of(schemaName));
    Assertions.assertEquals(1, functions.length);
    Assertions.assertEquals("func1", functions[0].name());

    // 3. Grant MODIFY_FUNCTION on func2
    MetadataObject func2Object =
        MetadataObjects.of("hive_catalog." + schemaName, "func2", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, func2Object, Sets.newHashSet(Privileges.ModifyFunction.allow()));

    // 4. User lists functions → should see both func1 and func2
    functions =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .listFunctions(Namespace.of(schemaName));
    Assertions.assertEquals(2, functions.length);

    // 5. Revoke EXECUTE_FUNCTION from func1 and grant MODIFY_FUNCTION instead.
    //    After this, the role should only carry MODIFY_FUNCTION on both func1
    //    and func2 — proving that MODIFY_FUNCTION alone grants visibility,
    //    independent of EXECUTE_FUNCTION.
    metalake.revokePrivilegesFromRole(
        roleName, func1Object, Sets.newHashSet(Privileges.ExecuteFunction.allow()));
    metalake.grantPrivilegesToRole(
        roleName, func1Object, Sets.newHashSet(Privileges.ModifyFunction.allow()));

    // Reload the role and assert the only function-level privileges left are
    // MODIFY_FUNCTION on both func1 and func2 (no EXECUTE_FUNCTION lingering).
    Role roleAfter = metalake.getRole(roleName);
    List<SecurableObject> funcSecurables =
        roleAfter.securableObjects().stream()
            .filter(o -> o.type() == MetadataObject.Type.FUNCTION)
            .collect(Collectors.toList());
    Assertions.assertEquals(2, funcSecurables.size(), "Expected privileges on func1 and func2");
    for (SecurableObject so : funcSecurables) {
      List<Privilege> privileges = so.privileges();
      Assertions.assertEquals(
          1,
          privileges.size(),
          "Each function should carry exactly one privilege (MODIFY_FUNCTION) after the swap");
      Assertions.assertEquals(
          Privileges.ModifyFunction.allow().name(),
          privileges.get(0).name(),
          "Only MODIFY_FUNCTION should remain on " + so.fullName());
    }

    // 6. User lists functions → should still see both func1 and func2,
    //    now via MODIFY_FUNCTION-derived visibility on both.
    functions =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .listFunctions(Namespace.of(schemaName));
    Assertions.assertEquals(2, functions.length);

    // 7. Replace the user's privileges with REGISTER_FUNCTION at schema level only.
    //    REGISTER_FUNCTION is a CREATE-style privilege scoped to the schema; it
    //    must NOT grant visibility on already-existing functions, even though the
    //    user could create new ones.
    metalake.revokePrivilegesFromRole(
        roleName, func1Object, Sets.newHashSet(Privileges.ModifyFunction.allow()));
    metalake.revokePrivilegesFromRole(
        roleName, func2Object, Sets.newHashSet(Privileges.ModifyFunction.allow()));
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Sets.newHashSet(Privileges.RegisterFunction.allow()));

    // Reload the role and assert no FUNCTION-level securable objects remain;
    // only USE_CATALOG (catalog) and USE_SCHEMA + REGISTER_FUNCTION (schema).
    Role roleRegisterOnly = metalake.getRole(roleName);
    List<SecurableObject> remainingFunc =
        roleRegisterOnly.securableObjects().stream()
            .filter(o -> o.type() == MetadataObject.Type.FUNCTION)
            .collect(Collectors.toList());
    Assertions.assertTrue(
        remainingFunc.isEmpty(),
        "REGISTER_FUNCTION-only role must not carry function-level securable objects");

    // 8. User lists functions → should see 0. REGISTER_FUNCTION alone is a
    //    schema-level CREATE-style privilege and must NOT grant visibility on
    //    existing functions, even though the user could register new ones.
    functions =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .listFunctions(Namespace.of(schemaName));
    Assertions.assertEquals(
        0,
        functions.length,
        "REGISTER_FUNCTION alone must not make existing functions visible to the user");
  }

  @Test
  @DisplayName("User with MODIFY_FUNCTION privilege can alter function properties")
  public void testAlterFunctionWithModifyPrivilege() {
    String userName = "user_alter";
    String roleName = "role_alter";

    // 1. Create user with EXECUTE_FUNCTION only
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // Grant USE privileges on catalog and schema
    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Sets.newHashSet(Privileges.UseSchema.allow()));

    // Grant EXECUTE_FUNCTION on func1
    MetadataObject funcObject =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, funcObject, Sets.newHashSet(Privileges.ExecuteFunction.allow()));

    // 2. User tries to alter function → should fail
    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    Assertions.assertThrows(
        ForbiddenException.class,
        () ->
            userMetalake
                .loadCatalog("hive_catalog")
                .asFunctionCatalog()
                .alterFunction(
                    NameIdentifier.of(schemaName, "func1"),
                    FunctionChange.updateComment("new comment")));

    // 3. Grant MODIFY_FUNCTION privilege
    metalake.grantPrivilegesToRole(
        roleName, funcObject, Sets.newHashSet(Privileges.ModifyFunction.allow()));

    // 4. User alters function → should succeed
    Function func =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .alterFunction(
                NameIdentifier.of(schemaName, "func1"),
                FunctionChange.updateComment("new comment"));
    Assertions.assertEquals("new comment", func.comment());
  }

  @Test
  @DisplayName("Drop function requires ownership - MODIFY_FUNCTION privilege is not sufficient")
  public void testDropFunctionRequiresOwnership() {
    String userName = "user_drop";
    String roleName = "role_drop";

    // 1. Admin registers a function (owner = admin)
    FunctionDefinition sparkDef =
        FunctionDefinitions.of(
            FunctionParams.of(FunctionParams.of("input", Types.IntegerType.get())),
            Types.IntegerType.get(),
            FunctionImpls.of(
                FunctionImpls.ofJava(FunctionImpl.RuntimeType.SPARK, "com.example.TestFunction")));

    catalog
        .asFunctionCatalog()
        .registerFunction(
            NameIdentifier.of(schemaName, "func_to_drop"),
            "comment",
            FunctionType.SCALAR,
            true,
            FunctionDefinitions.of(sparkDef));

    // 2. Create user with MODIFY_FUNCTION privilege
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // Grant USE privileges on catalog and schema
    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Sets.newHashSet(Privileges.UseSchema.allow()));

    // Grant MODIFY_FUNCTION on func_to_drop
    MetadataObject funcObject =
        MetadataObjects.of(
            "hive_catalog." + schemaName, "func_to_drop", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, funcObject, Sets.newHashSet(Privileges.ModifyFunction.allow()));

    // 3. User tries to drop function → should fail (not owner)
    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    Assertions.assertThrows(
        ForbiddenException.class,
        () ->
            userMetalake
                .loadCatalog("hive_catalog")
                .asFunctionCatalog()
                .dropFunction(NameIdentifier.of(schemaName, "func_to_drop")));

    // 4. Admin (owner) drops function → should succeed
    boolean dropped =
        catalog.asFunctionCatalog().dropFunction(NameIdentifier.of(schemaName, "func_to_drop"));
    Assertions.assertTrue(dropped);
  }

  @Test
  @DisplayName(
      "Schema owner can perform all function operations (register, list, get, alter, drop)")
  public void testSchemaOwnerCanPerformAllFunctionOperations() {
    String userName = "user_schema_owner";
    String roleName = "role_schema_owner";

    // 1. Create user with schema ownership + USE_CATALOG / USE_SCHEMA
    metalake.addUser(userName);
    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.setOwner(schemaObject, userName, Owner.Type.USER);

    SecurableObject catalogSecurable =
        SecurableObjects.ofCatalog(
            "hive_catalog", Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaSecurable =
        SecurableObjects.ofSchema(
            catalogSecurable, schemaName, Lists.newArrayList(Privileges.UseSchema.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogSecurable, schemaSecurable));
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    // 2. User can register new function
    FunctionDefinition sparkDef =
        FunctionDefinitions.of(
            FunctionParams.of(FunctionParams.of("input", Types.IntegerType.get())),
            Types.IntegerType.get(),
            FunctionImpls.of(
                FunctionImpls.ofJava(FunctionImpl.RuntimeType.SPARK, "com.example.TestFunction")));

    Function newFunc =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .registerFunction(
                NameIdentifier.of(schemaName, "func_by_schema_owner"),
                "comment",
                FunctionType.SCALAR,
                true,
                FunctionDefinitions.of(sparkDef));
    Assertions.assertEquals("func_by_schema_owner", newFunc.name());

    // 3. User can list all functions
    NameIdentifier[] functions =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .listFunctions(Namespace.of(schemaName));
    Assertions.assertTrue(functions.length >= 1);

    // 4. User can get any function
    Function func1 =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, "func1"));
    Assertions.assertEquals("func1", func1.name());

    // 5. User can alter any function
    Function alteredFunc =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .alterFunction(
                NameIdentifier.of(schemaName, "func1"),
                FunctionChange.updateComment("updated by schema owner"));
    Assertions.assertEquals("updated by schema owner", alteredFunc.comment());

    // 6. User can drop any function
    boolean dropped =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .dropFunction(NameIdentifier.of(schemaName, "func_by_schema_owner"));
    Assertions.assertTrue(dropped);
  }

  @Test
  @DisplayName(
      "Privilege inheritance: catalog-level privileges apply to all functions, DENY overrides ALLOW")
  public void testPrivilegeInheritance() {
    String userName = "user_inheritance";
    String roleName = "role_inheritance";

    // 1. Create user with catalog-level EXECUTE_FUNCTION
    metalake.addUser(userName);
    SecurableObject catalogSecurable =
        SecurableObjects.ofCatalog(
            "hive_catalog",
            Lists.newArrayList(
                Privileges.ExecuteFunction.allow(),
                Privileges.UseSchema.allow(),
                Privileges.UseCatalog.allow()));

    metalake.createRole(roleName, Maps.newHashMap(), Lists.newArrayList(catalogSecurable));
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    // 2. User can access all functions in the catalog
    Function func1 =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, "func1"));
    Assertions.assertEquals("func1", func1.name());

    Function func2 =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, "func2"));
    Assertions.assertEquals("func2", func2.name());

    // 3. DENY at function level overrides catalog-level ALLOW
    MetadataObject func1Object =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, func1Object, Sets.newHashSet(Privileges.ExecuteFunction.deny()));

    // 4. User cannot access func1 (denied)
    Assertions.assertThrows(
        ForbiddenException.class,
        () ->
            userMetalake
                .loadCatalog("hive_catalog")
                .asFunctionCatalog()
                .getFunction(NameIdentifier.of(schemaName, "func1")));

    // 5. But can still access func2
    Function func2Again =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, "func2"));
    Assertions.assertEquals("func2", func2Again.name());
  }

  @Test
  @DisplayName(
      "Renaming the parent catalog updates function-level securable objects in role"
          + " (Case A: cache-coherent metadata rename)")
  public void testRenameParentCatalogUpdatesFunctionSecurableObjectsInRole() {
    String userName = "user_rename_parent";
    String roleName = "role_rename_parent";
    String renamedCatalog = RandomNameUtils.genRandomName("hive_renamed");

    // 1. Create user with EXECUTE_FUNCTION on hive_catalog.<schema>.func1
    metalake.addUser(userName);

    SecurableObject catalogSecurable =
        SecurableObjects.ofCatalog(
            "hive_catalog",
            Lists.newArrayList(Privileges.UseCatalog.allow(), Privileges.UseSchema.allow()));
    SecurableObject funcSecurable =
        SecurableObjects.parse(
            "hive_catalog." + schemaName + ".func1",
            MetadataObject.Type.FUNCTION,
            Lists.newArrayList(Privileges.ExecuteFunction.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogSecurable, funcSecurable));
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();

    // Sanity: user can access func1 via the original catalog name.
    Function func1Before =
        userClient
            .loadMetalake(metalakeName)
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, "func1"));
    Assertions.assertEquals("func1", func1Before.name());

    try {
      // 2. Rename the parent catalog. This should propagate through
      //    AuthorizationUtils.authorizationPluginRenamePrivileges and update the role's
      //    securable objects in place (CatalogHookDispatcher emits MetadataObjectChange.rename).
      metalake.alterCatalog("hive_catalog", CatalogChange.rename(renamedCatalog));

      // 3. Reload the role and verify the function-level securable object now points at
      //    the new catalog name. We do not rely on list ordering: search by type + suffix.
      Role updatedRole = metalake.getRole(roleName);
      List<SecurableObject> funcObjects =
          updatedRole.securableObjects().stream()
              .filter(o -> o.type() == MetadataObject.Type.FUNCTION)
              .collect(Collectors.toList());
      Assertions.assertEquals(
          1, funcObjects.size(), "Expected exactly one FUNCTION securable object in the role");

      SecurableObject updatedFuncObject = funcObjects.get(0);
      Assertions.assertEquals(
          renamedCatalog + "." + schemaName + ".func1",
          updatedFuncObject.fullName(),
          "Function securable object's fullName should reflect the renamed parent catalog");
      Assertions.assertEquals("func1", updatedFuncObject.name());

      // 4. The catalog-level securable object should also have been updated.
      List<SecurableObject> catalogObjects =
          updatedRole.securableObjects().stream()
              .filter(o -> o.type() == MetadataObject.Type.CATALOG)
              .collect(Collectors.toList());
      Assertions.assertEquals(1, catalogObjects.size());
      Assertions.assertEquals(renamedCatalog, catalogObjects.get(0).name());

      // 5. The user must still be able to access func1 via the new catalog name. This
      //    confirms the authorization cache was refreshed end-to-end (server-side roles
      //    + jcasbin role/owner caches).
      GravitinoClient userClientAfter =
          GravitinoClient.builder(serverUri)
              .withMetalake(metalakeName)
              .withSimpleAuth(userName)
              .build();
      Function func1After =
          userClientAfter
              .loadMetalake(metalakeName)
              .loadCatalog(renamedCatalog)
              .asFunctionCatalog()
              .getFunction(NameIdentifier.of(schemaName, "func1"));
      Assertions.assertEquals("func1", func1After.name());
    } finally {
      // Restore the catalog name so subsequent tests in this class still work.
      try {
        metalake.alterCatalog(renamedCatalog, CatalogChange.rename("hive_catalog"));
      } catch (Exception e) {
        LOG.warn("Failed to restore catalog name after rename test", e);
      }
    }
  }

  @Test
  @DisplayName(
      "Revoking EXECUTE_FUNCTION immediately denies access on next call"
          + " (Case B: role/privilege cache coherence)")
  public void testRevokeExecuteFunctionInvalidatesAuthorizationCache() {
    String userName = "user_revoke_cache";
    String roleName = "role_revoke_cache";

    // 1. Grant the user EXECUTE_FUNCTION on func1 (plus parent USE_* privileges).
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Sets.newHashSet(Privileges.UseSchema.allow()));

    MetadataObject funcObject =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, funcObject, Sets.newHashSet(Privileges.ExecuteFunction.allow()));

    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    // 2. First call should succeed and populate the server-side role/privilege caches.
    Function funcBefore =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, "func1"));
    Assertions.assertEquals("func1", funcBefore.name());

    // 3. Revoke EXECUTE_FUNCTION. The cache must be invalidated server-side; if it
    //    were stale, the next call would still incorrectly succeed.
    metalake.revokePrivilegesFromRole(
        roleName, funcObject, Sets.newHashSet(Privileges.ExecuteFunction.allow()));

    // 4. The very next call from the same user must be rejected. We do not sleep
    //    or recreate the client, so any failure here points to a stale auth cache.
    Assertions.assertThrows(
        Exception.class,
        () ->
            userMetalake
                .loadCatalog("hive_catalog")
                .asFunctionCatalog()
                .getFunction(NameIdentifier.of(schemaName, "func1")),
        "After revoke, EXECUTE_FUNCTION must not be served from a stale cache");

    // 5. The role's stored securable objects should also reflect the revoke.
    Role roleAfter = metalake.getRole(roleName);
    boolean stillHasFuncExecute =
        roleAfter.securableObjects().stream()
            .filter(o -> o.type() == MetadataObject.Type.FUNCTION)
            .filter(o -> ("hive_catalog." + schemaName + ".func1").equals(o.fullName()))
            .flatMap(o -> o.privileges().stream())
            .anyMatch(p -> p.name() == Privileges.ExecuteFunction.allow().name());
    Assertions.assertFalse(
        stillHasFuncExecute,
        "Role must no longer carry an ALLOW EXECUTE_FUNCTION privilege on func1 after revoke");
  }

  @Test
  @DisplayName(
      "Recreated function requires a fresh grant; the old role's ALLOW does not carry over"
          + " (Case C: entity-id-bound authorization, cache cleared on drop)")
  public void testRecreatedFunctionRequiresFreshGrant() {
    String userName = "user_recreate_func";
    String roleName = "role_recreate_func";
    String funcName = RandomNameUtils.genRandomName("func_recreate").toLowerCase();

    FunctionDefinition sparkDef =
        FunctionDefinitions.of(
            FunctionParams.of(FunctionParams.of("input", Types.IntegerType.get())),
            Types.IntegerType.get(),
            FunctionImpls.of(
                FunctionImpls.ofJava(FunctionImpl.RuntimeType.SPARK, "com.example.TestFunction")));

    // 1. Admin registers func_recreate (entity id = X, comment = "original").
    Function original =
        catalog
            .asFunctionCatalog()
            .registerFunction(
                NameIdentifier.of(schemaName, funcName),
                "original",
                FunctionType.SCALAR,
                true,
                FunctionDefinitions.of(sparkDef));
    Assertions.assertEquals("original", original.comment());

    // 2. Grant the user EXECUTE_FUNCTION on the path hive_catalog.<schema>.<funcName>.
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Sets.newHashSet(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Sets.newHashSet(Privileges.UseSchema.allow()));

    MetadataObject funcObject =
        MetadataObjects.of("hive_catalog." + schemaName, funcName, MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, funcObject, Sets.newHashSet(Privileges.ExecuteFunction.allow()));

    GravitinoClient userClient =
        GravitinoClient.builder(serverUri)
            .withMetalake(metalakeName)
            .withSimpleAuth(userName)
            .build();
    GravitinoMetalake userMetalake = userClient.loadMetalake(metalakeName);

    // Sanity: user can access the original function. This also primes server-side
    // role/owner caches so a stale ALLOW would be observable later.
    Function originalAccess =
        userMetalake
            .loadCatalog("hive_catalog")
            .asFunctionCatalog()
            .getFunction(NameIdentifier.of(schemaName, funcName));
    Assertions.assertEquals("original", originalAccess.comment());

    try {
      // 3. Admin drops the function. Per Gravitino's authorization model, dropping
      //    the entity also removes the path-bound privilege entries from the role
      //    (the function-level securable object disappears).
      boolean dropped =
          catalog.asFunctionCatalog().dropFunction(NameIdentifier.of(schemaName, funcName));
      Assertions.assertTrue(dropped);

      // 4. Admin re-registers a NEW function with the same name (entity id = Y,
      //    comment = "recreated"). The old role grant must NOT carry over.
      Function recreated =
          catalog
              .asFunctionCatalog()
              .registerFunction(
                  NameIdentifier.of(schemaName, funcName),
                  "recreated",
                  FunctionType.SCALAR,
                  true,
                  FunctionDefinitions.of(sparkDef));
      Assertions.assertEquals(
          "recreated",
          recreated.comment(),
          "Sanity check that the function was actually re-registered with new metadata");

      // 5. Without any re-grant, the user must be REJECTED. This proves:
      //      a) authorization is bound to the (path, entityId) pair, not just the path;
      //      b) the cache entry for the old entityId was invalidated on drop;
      //      c) the role's function-level securable object was cleaned up on drop.
      Assertions.assertThrows(
          Exception.class,
          () ->
              userMetalake
                  .loadCatalog("hive_catalog")
                  .asFunctionCatalog()
                  .getFunction(NameIdentifier.of(schemaName, funcName)),
          "After drop+recreate, the previous grant must NOT carry over to the new function");

      // 6. The role's stored securable objects should no longer reference the
      //    function path (it was cleaned up when the original entity was dropped).
      Role roleAfterDrop = metalake.getRole(roleName);
      boolean stillReferencesFunc =
          roleAfterDrop.securableObjects().stream()
              .filter(o -> o.type() == MetadataObject.Type.FUNCTION)
              .anyMatch(o -> ("hive_catalog." + schemaName + "." + funcName).equals(o.fullName()));
      Assertions.assertFalse(
          stillReferencesFunc,
          "Role must not retain a function-level securable object after the function is dropped");

      // 7. Re-grant EXECUTE_FUNCTION on the same path against the new entity.
      metalake.grantPrivilegesToRole(
          roleName, funcObject, Sets.newHashSet(Privileges.ExecuteFunction.allow()));

      // 8. Now the user can access the recreated function and observes the NEW
      //    metadata (comment = "recreated"), not a cached copy of the original.
      Function reaccess =
          userMetalake
              .loadCatalog("hive_catalog")
              .asFunctionCatalog()
              .getFunction(NameIdentifier.of(schemaName, funcName));
      Assertions.assertEquals(funcName, reaccess.name());
      Assertions.assertEquals(
          "recreated",
          reaccess.comment(),
          "User should be served the NEW function entity, not a cached copy of the old one");
    } finally {
      // Cleanup: drop the (re-)registered function if it still exists.
      try {
        catalog.asFunctionCatalog().dropFunction(NameIdentifier.of(schemaName, funcName));
      } catch (Exception ignore) {
        // Already gone.
      }
    }
  }
}
