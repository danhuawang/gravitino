/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.integration.test.idp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2E test verifying IDP users with correct credentials can create and query objects in Gravitino
 * via Spark + Iceberg REST Catalog (IRC) with Basic auth.
 *
 * <p>The IRC endpoint supports Basic auth natively. An IDP user authenticates with their
 * username:password, and the IRC resolves authorization via the Gravitino server.
 */
@DisplayName("IDP User via Spark + Iceberg REST Catalog E2E Tests")
public class IdpSparkIcebergRestCatalogIT {

  private static final Logger LOG = LoggerFactory.getLogger(IdpSparkIcebergRestCatalogIT.class);
  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String ADMIN = "admin";
  private static final String SPARK_CATALOG_NAME = "irc_idp";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String USER_NAME = "idp-irc-user";
  private static final String USER_PASSWORD = "Passw0rd-IRC-User1";
  private static final String ROLE_NAME = "idp_irc_full_role";

  private static String adminPassword;
  private static String gravitinoUri;
  private static String ircUri;
  private static String idpApiBase;
  private static HttpClient httpClient;

  private static GravitinoAdminClient adminClient;
  private static GravitinoMetalake metalake;
  private static String metalakeName;
  private static String catalogName;

  private SparkSession spark;

  @BeforeAll
  public static void setup() throws Exception {
    gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    ircUri = System.getProperty("gravitino.irc.uri", "http://localhost:30001/iceberg/");
    adminPassword = System.getProperty("gravitino.admin.password", "Passw0rd-Admin12");
    metalakeName = System.getProperty("gravitino.metalake", "test");
    catalogName = System.getProperty("gravitino.irc.catalog", "catalog_1");
    idpApiBase = gravitinoUri + "/api/idp";
    httpClient = HttpClient.newHttpClient();

    adminClient =
        GravitinoAdminClient.builder(gravitinoUri).withBasicAuth(ADMIN, adminPassword).build();
    metalake = adminClient.loadMetalake(metalakeName);

    // Create IDP user
    createIdpUser(USER_NAME, USER_PASSWORD);

    // Add user to Gravitino metalake and grant privileges for catalog operations
    metalake.addUser(USER_NAME);

    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(
            catalogName,
            Lists.newArrayList(
                Privileges.UseCatalog.allow(),
                Privileges.CreateSchema.allow(),
                Privileges.UseSchema.allow(),
                Privileges.CreateTable.allow()));
    metalake.createRole(ROLE_NAME, Maps.newHashMap(), Lists.newArrayList(catalogObject));
    metalake.grantRolesToUser(Lists.newArrayList(ROLE_NAME), USER_NAME);

    LOG.info(
        "IdpIcebergRestCatalogIT setup: metalake={}, catalog={}, ircUri={}, user={}",
        metalakeName,
        catalogName,
        ircUri,
        USER_NAME);
  }

  @AfterEach
  public void cleanupSpark() {
    if (spark != null) {
      try {
        spark.close();
      } catch (Exception e) {
        LOG.warn("Failed to close SparkSession", e);
      }
      spark = null;
    }
  }

  @AfterAll
  public static void tearDown() {
    try {
      if (metalake != null) {
        metalake.removeUser(USER_NAME);
      }
    } catch (Exception e) {
      LOG.warn("Failed to remove Gravitino user: {}", e.getMessage());
    }
    try {
      if (metalake != null) {
        metalake.deleteRole(ROLE_NAME);
      }
    } catch (Exception e) {
      LOG.warn("Failed to delete role: {}", e.getMessage());
    }
    try {
      deleteIdpUser(USER_NAME);
    } catch (Exception e) {
      LOG.warn("Failed to delete IDP user: {}", e.getMessage());
    }
    if (adminClient != null) {
      adminClient.close();
    }
  }

  @Test
  @DisplayName("IDP user with correct credentials can create table via Spark + IRC")
  void testIdpUserCanCreateTableViaSpark() {
    spark = newSparkSessionWithBasicAuth("idp-create-table", USER_NAME, USER_PASSWORD);

    String schemaName = "idp_test_schema_" + System.currentTimeMillis();
    String tableName = "idp_test_table";
    String schemaFqn = SPARK_CATALOG_NAME + "." + schemaName;
    String tableFqn = schemaFqn + "." + tableName;

    try {
      // Create schema
      spark.sql("CREATE NAMESPACE IF NOT EXISTS " + schemaFqn).collect();

      // Create table
      spark
          .sql(String.format("CREATE TABLE %s (id BIGINT, name STRING) USING iceberg", tableFqn))
          .collect();

      // Verify table exists via SHOW TABLES
      List<Row> tables = spark.sql("SHOW TABLES IN " + schemaFqn).collectAsList();
      boolean found =
          tables.stream().anyMatch(r -> tableName.equals(r.getAs("tableName").toString()));
      Assertions.assertTrue(found, "Table should exist after creation. Tables: " + tables);

      LOG.info("IDP user successfully created table via Spark + IRC: {}", tableFqn);
    } finally {
      // Cleanup
      try {
        spark.sql("DROP TABLE IF EXISTS " + tableFqn).collect();
        spark.sql("DROP NAMESPACE IF EXISTS " + schemaFqn).collect();
      } catch (Exception e) {
        LOG.warn("Cleanup failed: {}", e.getMessage());
      }
    }
  }

  @Test
  @DisplayName("IDP user with wrong password cannot access IRC")
  void testIdpUserWithWrongPasswordFails() {
    spark = newSparkSessionWithBasicAuth("idp-wrong-pwd", USER_NAME, "WrongPassw0rd-01");

    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SHOW NAMESPACES IN " + SPARK_CATALOG_NAME).collect(),
        "User with wrong password should not be able to access IRC");
  }

  @Test
  @DisplayName("Non-existent IDP user cannot access IRC")
  void testNonExistentUserFails() {
    spark = newSparkSessionWithBasicAuth("idp-no-user", "no-such-user-xyz", "Passw0rd-Fake-01");

    Assertions.assertThrows(
        Exception.class,
        () -> spark.sql("SHOW NAMESPACES IN " + SPARK_CATALOG_NAME).collect(),
        "Non-existent user should not be able to access IRC");
  }

  // ==================== Helper methods ====================

  private static SparkSession newSparkSessionWithBasicAuth(
      String appName, String username, String password) {
    String base = "spark.sql.catalog." + SPARK_CATALOG_NAME;
    SparkConf conf =
        new SparkConf()
            .set(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .set("spark.sql.defaultCatalog", SPARK_CATALOG_NAME)
            .set(base, "org.apache.iceberg.spark.SparkCatalog")
            .set(base + ".type", "rest")
            .set(base + ".uri", ircUri)
            .set(base + ".cache-enabled", "false")
            .set(base + ".rest.auth.type", "basic")
            .set(base + ".rest.auth.basic.username", username)
            .set(base + ".rest.auth.basic.password", password)
            .set("spark.locality.wait.node", "0");
    return SparkSession.builder().master("local[2]").appName(appName).config(conf).getOrCreate();
  }

  private static void createIdpUser(String username, String password) throws Exception {
    String body =
        MAPPER.createObjectNode().put("user", username).put("password", password).toString();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users"))
            .header("Accept", ACCEPT)
            .header("Content-Type", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(200, resp.statusCode(), "Failed to create IDP user: " + resp.body());
  }

  private static void deleteIdpUser(String username) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users/" + username))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .DELETE()
            .build();
    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String basicAuth(String username, String password) {
    String credentials = username + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
