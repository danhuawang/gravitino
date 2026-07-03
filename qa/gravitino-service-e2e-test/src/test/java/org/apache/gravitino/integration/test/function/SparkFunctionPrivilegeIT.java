/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.integration.test.function;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.function.FunctionDefinition;
import org.apache.gravitino.function.FunctionDefinitions;
import org.apache.gravitino.function.FunctionImpl;
import org.apache.gravitino.function.FunctionImpls;
import org.apache.gravitino.function.FunctionParams;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.GravitinoSparkConfig;
import org.apache.gravitino.spark.connector.plugin.GravitinoSparkPlugin;
import org.apache.gravitino.utils.RandomNameUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.catalog.functions.BoundFunction;
import org.apache.spark.sql.connector.catalog.functions.ScalarFunction;
import org.apache.spark.sql.connector.catalog.functions.UnboundFunction;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.unsafe.types.UTF8String;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark Engine Integration Tests for Function Privilege control.
 *
 * <p>These tests verify that function privilege control works correctly when functions are accessed
 * through Spark SQL. Tests run against a real Gravitino server deployed in Kubernetes with
 * authorization enabled.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>T8: Spark call function requires EXECUTE_FUNCTION privilege
 *   <li>T9: Spark SHOW FUNCTIONS respects visibility filtering
 *   <li>T10: Schema owners can call all functions via Spark
 *   <li>T11: Catalog-level privilege inheritance works in Spark
 * </ul>
 */
@DisplayName("Spark Function Privilege Integration Tests")
public class SparkFunctionPrivilegeIT {

  private static final Logger LOG = LoggerFactory.getLogger(SparkFunctionPrivilegeIT.class);

  private static String metalakeName = RandomNameUtils.genRandomName("metalake");
  private static GravitinoMetalake metalake;
  private static GravitinoAdminClient client;
  private static Catalog catalog;
  private static String schemaName = RandomNameUtils.genRandomName("test_schema");
  private static String serverUri;

  // Spark session for user tests
  private SparkSession userSparkSession;

  protected final String TIME_ZONE_UTC = "UTC";

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
    registerTestFunction("func3");
  }

  private static void registerTestFunction(String funcName) {
    // Use StringLengthFunction (an UnboundFunction implementation) as the
    // Spark UDF backing every test function. The Gravitino Spark connector
    // resolves a FunctionImpl of RuntimeType.SPARK by reflectively
    // instantiating the className as an UnboundFunction; the params/returnType
    // declared on the Gravitino metadata are descriptive and do not have to
    // match the impl's bind() signature exactly. We therefore declare the
    // Gravitino-side signature as STRING -> INTEGER so the test SQL
    //
    //   SELECT hive_catalog.<schema>.func1('abc') == 3
    //
    // exercises a real round-trip through the connector.
    FunctionDefinition sparkDef =
        FunctionDefinitions.of(
            FunctionParams.of(FunctionParams.of("input", Types.StringType.get())),
            Types.IntegerType.get(),
            FunctionImpls.of(
                FunctionImpls.ofJava(
                    FunctionImpl.RuntimeType.SPARK, StringLengthFunction.class.getName())));

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
    // Close user Spark session if exists
    if (userSparkSession != null) {
      userSparkSession.close();
      userSparkSession = null;
    }

    // Reset SPARK_USER environment variable
    setEnv("SPARK_USER", AuthConstants.ANONYMOUS_USER);

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
      LOG.warn("Failed to clean up users and roles", e);
    }
  }

  @AfterAll
  public static void stopIntegrationTest() {
    // Reset SPARK_USER environment variable
    setEnv("SPARK_USER", AuthConstants.ANONYMOUS_USER);

    // Clean up metalake
    try {
      if (client != null && metalake != null) {
        client.dropMetalake(metalakeName);
      }
    } catch (Exception e) {
      LOG.warn("Failed to clean up metalake", e);
    }
  }

  /**
   * Create a Spark session for a specific user. This is used to test privilege enforcement for
   * non-admin users.
   *
   * <p>Authentication is done via SPARK_USER environment variable, which is read by the Gravitino
   * Spark connector to authenticate the user.
   */
  private SparkSession createUserSparkSession(String userName) {
    // Set SPARK_USER environment variable for authentication
    setEnv("SPARK_USER", userName);

    SparkConf userConf =
        new SparkConf()
            .set("spark.plugins", GravitinoSparkPlugin.class.getName())
            .set(GravitinoSparkConfig.GRAVITINO_URI, serverUri)
            .set(GravitinoSparkConfig.GRAVITINO_METALAKE, metalakeName)
            .set("spark.sql.session.timeZone", TIME_ZONE_UTC);

    SparkSession session =
        SparkSession.builder()
            .master("local[1]")
            .appName("Spark Function Privilege Test - " + userName)
            .config(userConf)
            .enableHiveSupport()
            .getOrCreate();

    // Fail fast with an actionable message if the Gravitino driver plugin did not register
    // hive_catalog as a v2 Spark catalog. Without this, any "SELECT hive_catalog.<schema>.func()"
    // or "USE hive_catalog.<schema>" falls through Spark's v1 ResolveSessionCatalog rule and
    // throws "Catalog hive_catalog does not support functions" 100 frames deep, hiding the real
    // cause (typically: Gravitino server unreachable, wrong GRAVITINO_E2E_URI, or auth failure
    // for SPARK_USER=" + userName + ").
    String catalogClass =
        session
            .sparkContext()
            .conf()
            .getOption("spark.sql.catalog.hive_catalog")
            .getOrElse(() -> null);
    Assertions.assertNotNull(
        catalogClass,
        "GravitinoSparkPlugin did not register 'hive_catalog' as a v2 Spark catalog."
            + " Server URI = "
            + serverUri
            + ", metalake = "
            + metalakeName
            + ", SPARK_USER = "
            + userName
            + "."
            + " Check that the Gravitino server is reachable from the test JVM and that the"
            + " catalog provider is supported by the Spark connector.");

    return session;
  }

  /**
   * Set environment variable for the current process. This is used to set SPARK_USER for
   * authentication.
   */
  private static void setEnv(String key, String value) {
    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      java.lang.reflect.Field field = cl.getDeclaredField("m");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> writableEnv = (Map<String, String>) field.get(env);
      if (value == null) {
        writableEnv.remove(key);
      } else {
        writableEnv.put(key, value);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to set environment variable", e);
    }
  }

  @Test
  @DisplayName("T8: Spark call function requires EXECUTE_FUNCTION privilege")
  public void testSparkCallFunctionRequiresExecutePrivilege() {
    String userName = "user_spark1";
    String roleName = "role_spark1";

    // 1. Create user with USE_CATALOG + USE_SCHEMA but NO EXECUTE_FUNCTION.
    //    USE_CATALOG/USE_SCHEMA are required for the GravitinoDriverPlugin to discover and
    //    register hive_catalog as a v2 Spark catalog at SparkContext startup. Without them,
    //    listCatalogsInfo() is filtered to empty server-side and no catalog is registered.
    metalake.addUser(userName);
    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Collections.singleton(Privileges.UseCatalog.allow()));
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Collections.singleton(Privileges.UseSchema.allow()));
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // 2. Create Spark session for user
    userSparkSession = createUserSparkSession(userName);

    // 3. User tries to call function → should fail (no EXECUTE_FUNCTION).
    //    StringLengthFunction expects a STRING argument; the auth check fires
    //    before bind(), so this fails on authorization, not on type checking.
    String functionCall = String.format("SELECT hive_catalog.%s.func1('abc')", schemaName);

    Assertions.assertThrows(
        Exception.class, // Could be AnalysisException or ForbiddenException
        () -> userSparkSession.sql(functionCall).collect(),
        "User without EXECUTE_FUNCTION should not be able to call function");

    // 4. Grant EXECUTE_FUNCTION privilege on func1
    MetadataObject funcObject =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, funcObject, Collections.singleton(Privileges.ExecuteFunction.allow()));

    // 5. Re-create Spark session to refresh privileges
    userSparkSession.close();
    userSparkSession = createUserSparkSession(userName);

    // 6. User calls function → should succeed and return the string length (3).
    //    func1 is backed by StringLengthFunction, so func1('abc') == 3.
    List<Row> result = userSparkSession.sql(functionCall).collectAsList();
    Assertions.assertEquals(1, result.size());
    Assertions.assertEquals(3, result.get(0).getInt(0));

    // 7. Verify SHOW FUNCTIONS only shows functions with privileges
    userSparkSession.sql("USE hive_catalog." + schemaName);
    List<Row> functions = userSparkSession.sql("SHOW FUNCTIONS").collectAsList();

    // Should only see func1 (the one with EXECUTE_FUNCTION privilege)
    boolean hasFunc1 = functions.stream().anyMatch(row -> row.getString(0).contains("func1"));
    Assertions.assertTrue(hasFunc1, "User should see func1 in SHOW FUNCTIONS");

    userSparkSession.close();
    userSparkSession = null;
  }

  @Test
  @DisplayName("T9: Spark SHOW FUNCTIONS respects visibility filtering")
  public void testSparkShowFunctionsRespectsVisibility() {
    String userName = "user_spark2";
    String roleName = "role_spark2";

    // 1. Create user with EXECUTE_FUNCTION on func1 and func2 only
    metalake.addUser(userName);
    metalake.createRole(roleName, Maps.newHashMap(), Collections.emptyList());
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // Grant USE privileges
    MetadataObject catalogObject =
        MetadataObjects.of(null, "hive_catalog", MetadataObject.Type.CATALOG);
    metalake.grantPrivilegesToRole(
        roleName, catalogObject, Collections.singleton(Privileges.UseCatalog.allow()));

    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.grantPrivilegesToRole(
        roleName, schemaObject, Collections.singleton(Privileges.UseSchema.allow()));

    // Grant EXECUTE_FUNCTION on func1 and func2
    MetadataObject func1Object =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, func1Object, Collections.singleton(Privileges.ExecuteFunction.allow()));

    MetadataObject func2Object =
        MetadataObjects.of("hive_catalog." + schemaName, "func2", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, func2Object, Collections.singleton(Privileges.ExecuteFunction.allow()));

    // 2. Create Spark session for user
    userSparkSession = createUserSparkSession(userName);
    userSparkSession.sql("USE hive_catalog." + schemaName);

    // 3. Execute SHOW FUNCTIONS → should only return func1 and func2
    List<Row> functions = userSparkSession.sql("SHOW FUNCTIONS").collectAsList();

    boolean hasFunc1 = functions.stream().anyMatch(row -> row.getString(0).contains("func1"));
    boolean hasFunc2 = functions.stream().anyMatch(row -> row.getString(0).contains("func2"));
    boolean hasFunc3 = functions.stream().anyMatch(row -> row.getString(0).contains("func3"));

    Assertions.assertTrue(hasFunc1, "User should see func1");
    Assertions.assertTrue(hasFunc2, "User should see func2");
    Assertions.assertFalse(hasFunc3, "User should NOT see func3 (no privilege)");

    // 4. Attempt to call func3 → should fail (no EXECUTE_FUNCTION on func3)
    String func3Call = String.format("SELECT hive_catalog.%s.func3('hi')", schemaName);
    Assertions.assertThrows(
        Exception.class,
        () -> userSparkSession.sql(func3Call).collect(),
        "User should not be able to call func3 without privilege");

    userSparkSession.close();
    userSparkSession = null;
  }

  @Test
  @DisplayName("T10: Schema owner can call all functions via Spark")
  public void testSparkSchemaOwnerCanCallAllFunctions() {
    String userName = "user_spark_owner";
    String roleName = "role_spark_owner";

    // 1. Create user as schema owner
    metalake.addUser(userName);
    MetadataObject schemaObject =
        MetadataObjects.of("hive_catalog", schemaName, MetadataObject.Type.SCHEMA);
    metalake.setOwner(schemaObject, userName, Owner.Type.USER);

    // Grant USE_CATALOG and USE_SCHEMA privileges
    SecurableObject catalogSecurable =
        SecurableObjects.ofCatalog(
            "hive_catalog", Lists.newArrayList(Privileges.UseCatalog.allow()));
    SecurableObject schemaSecurable =
        SecurableObjects.ofSchema(
            catalogSecurable, schemaName, Lists.newArrayList(Privileges.UseSchema.allow()));
    metalake.createRole(
        roleName, Maps.newHashMap(), Lists.newArrayList(catalogSecurable, schemaSecurable));
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);

    // 2. Create Spark session for schema owner
    userSparkSession = createUserSparkSession(userName);
    userSparkSession.sql("USE hive_catalog." + schemaName);

    // 3. Schema owner can call any function. func1 is backed by
    //    StringLengthFunction, so func1('abcd') == 4.
    String func1Call = String.format("SELECT hive_catalog.%s.func1('abcd')", schemaName);
    List<Row> result1 = userSparkSession.sql(func1Call).collectAsList();
    Assertions.assertEquals(1, result1.size());
    Assertions.assertEquals(4, result1.get(0).getInt(0));

    // 4. SHOW FUNCTIONS should return all functions
    List<Row> functions = userSparkSession.sql("SHOW FUNCTIONS").collectAsList();

    boolean hasFunc1 = functions.stream().anyMatch(row -> row.getString(0).contains("func1"));
    boolean hasFunc2 = functions.stream().anyMatch(row -> row.getString(0).contains("func2"));
    boolean hasFunc3 = functions.stream().anyMatch(row -> row.getString(0).contains("func3"));

    Assertions.assertTrue(hasFunc1, "Schema owner should see func1");
    Assertions.assertTrue(hasFunc2, "Schema owner should see func2");
    Assertions.assertTrue(hasFunc3, "Schema owner should see func3");

    userSparkSession.close();
    userSparkSession = null;
  }

  @Test
  @DisplayName("T11: Catalog-level privilege inheritance works in Spark")
  public void testSparkCatalogLevelPrivilegeInheritance() {
    String userName = "user_spark_catalog";
    String roleName = "role_spark_catalog";

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

    // 2. Create Spark session for user
    userSparkSession = createUserSparkSession(userName);
    userSparkSession.sql("USE hive_catalog." + schemaName);

    // 3. User can call all functions (catalog-level privilege applies).
    //    func1 is backed by StringLengthFunction, so func1('abc') == 3.
    String func1CallAllow = String.format("SELECT hive_catalog.%s.func1('abc')", schemaName);
    List<Row> result1 = userSparkSession.sql(func1CallAllow).collectAsList();
    Assertions.assertEquals(1, result1.size());
    Assertions.assertEquals(3, result1.get(0).getInt(0));

    // 4. SHOW FUNCTIONS should return all functions
    List<Row> functions = userSparkSession.sql("SHOW FUNCTIONS").collectAsList();

    boolean hasFunc1 = functions.stream().anyMatch(row -> row.getString(0).contains("func1"));
    boolean hasFunc2 = functions.stream().anyMatch(row -> row.getString(0).contains("func2"));

    Assertions.assertTrue(hasFunc1, "User should see func1");
    Assertions.assertTrue(hasFunc2, "User should see func2");

    // 5. Grant function-level DENY on func1
    MetadataObject func1Object =
        MetadataObjects.of("hive_catalog." + schemaName, "func1", MetadataObject.Type.FUNCTION);
    metalake.grantPrivilegesToRole(
        roleName, func1Object, Collections.singleton(Privileges.ExecuteFunction.deny()));

    // 6. Re-create Spark session to refresh privileges
    userSparkSession.close();
    userSparkSession = createUserSparkSession(userName);
    userSparkSession.sql("USE hive_catalog." + schemaName);

    // 7. User cannot call func1 (DENY overrides ALLOW)
    String func1Call = String.format("SELECT hive_catalog.%s.func1('abc')", schemaName);
    Assertions.assertThrows(
        Exception.class,
        () -> userSparkSession.sql(func1Call).collect(),
        "User should not be able to call func1 (denied)");

    // 8. But can still call func2. func2 is backed by StringLengthFunction,
    //    so func2('hi') == 2.
    String func2Call = String.format("SELECT hive_catalog.%s.func2('hi')", schemaName);
    List<Row> result2 = userSparkSession.sql(func2Call).collectAsList();
    Assertions.assertEquals(1, result2.size());
    Assertions.assertEquals(2, result2.get(0).getInt(0));

    // 9. SHOW FUNCTIONS should not show func1 (denied)
    functions = userSparkSession.sql("SHOW FUNCTIONS").collectAsList();

    hasFunc1 = functions.stream().anyMatch(row -> row.getString(0).contains("func1"));
    hasFunc2 = functions.stream().anyMatch(row -> row.getString(0).contains("func2"));

    Assertions.assertFalse(hasFunc1, "User should NOT see func1 (denied)");
    Assertions.assertTrue(hasFunc2, "User should still see func2");

    userSparkSession.close();
    userSparkSession = null;
  }

  // ──────────────────────────────────────────────────────────────────────────
  //  Test UDF
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Spark V2 UDF used as the implementation for every test function registered in
   * {@code @BeforeAll}. Returns the length of a string.
   *
   * <p>Mirrors {@code StringLengthFunction} from {@code spark-connector/spark-common}'s test
   * sources. Inlined here because that class lives in another module's {@code src/test} and is not
   * exposed via a published artifact, so we cannot import it directly.
   */
  public static class StringLengthFunction implements UnboundFunction {

    @Override
    public String name() {
      return "string_length";
    }

    @Override
    public BoundFunction bind(StructType inputType) {
      if (inputType.fields().length != 1) {
        throw new UnsupportedOperationException("string_length expects exactly one argument");
      }
      if (!inputType.fields()[0].dataType().equals(DataTypes.StringType)) {
        throw new UnsupportedOperationException(
            "string_length expects a string argument, got: " + inputType.fields()[0].dataType());
      }
      return new BoundStringLengthFunction();
    }

    @Override
    public String description() {
      return "Returns the length of a string";
    }

    private static class BoundStringLengthFunction implements ScalarFunction<Integer> {
      @Override
      public String name() {
        return "string_length";
      }

      @Override
      public DataType[] inputTypes() {
        return new DataType[] {DataTypes.StringType};
      }

      @Override
      public DataType resultType() {
        return DataTypes.IntegerType;
      }

      @Override
      public String canonicalName() {
        return "gravitino.string_length";
      }

      @Override
      public Integer produceResult(InternalRow input) {
        if (input.isNullAt(0)) {
          return null;
        }
        UTF8String str = input.getUTF8String(0);
        return str.numChars();
      }
    }
  }
}
