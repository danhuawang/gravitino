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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** E2E tests for IDP authentication and request body error handling. */
@DisplayName("Built-in IDP Error Handling E2E Tests")
public class IdpErrorHandlingIT {

  private static final Logger LOG = LoggerFactory.getLogger(IdpErrorHandlingIT.class);
  private static final String ACCEPT = "application/vnd.gravitino.v1+json";
  private static final String ADMIN = "admin";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String adminPassword;
  private static String idpApiBase;
  private static HttpClient httpClient;

  @BeforeAll
  public static void setup() {
    String gravitinoUri = System.getProperty("gravitino.uri", "http://localhost:30090");
    adminPassword = System.getProperty("gravitino.admin.password", "Passw0rd-Admin12");
    idpApiBase = gravitinoUri + "/api/idp";
    httpClient = HttpClient.newHttpClient();
    LOG.info("IdpErrorHandlingIT setup: idpApiBase={}", idpApiBase);
  }

  // ==================== 4.1 Authentication Errors ====================

  @Test
  @DisplayName("#53 Wrong password - returns 401")
  void testWrongPassword() throws Exception {
    HttpResponse<String> resp = getWithAuth("/users/" + ADMIN, ADMIN, "WrongPassw0rd-99");
    Assertions.assertEquals(401, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#54 Non-existent user login - returns 401")
  void testNonExistentUserLogin() throws Exception {
    HttpResponse<String> resp =
        getWithAuth("/users/" + ADMIN, "no-such-user-xyz", "Passw0rd-Fake-01");
    Assertions.assertEquals(401, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#55 Malformed Authorization header (invalid base64) - returns 401")
  void testMalformedAuthHeader() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users/" + ADMIN))
            .header("Accept", ACCEPT)
            .header("Authorization", "Basic ???not-valid-base64!!!")
            .GET()
            .build();
    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(401, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#56 Authorization header missing colon - returns 401")
  void testAuthHeaderMissingColon() throws Exception {
    // base64 encode "usernameonly" (no colon separator)
    String encoded =
        Base64.getEncoder().encodeToString("usernameonly".getBytes(StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users/" + ADMIN))
            .header("Accept", ACCEPT)
            .header("Authorization", "Basic " + encoded)
            .GET()
            .build();
    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(401, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#57 Non-Basic auth scheme (Bearer) - returns 401")
  void testNonBasicAuthScheme() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users/" + ADMIN))
            .header("Accept", ACCEPT)
            .header("Authorization", "Bearer some-fake-token")
            .GET()
            .build();
    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(401, resp.statusCode(), resp.body());
  }

  // ==================== 4.2 Request Body Errors ====================

  @Test
  @DisplayName("#58 Empty JSON body - POST user {} returns 400, ILLEGAL_ARGUMENTS_CODE")
  void testEmptyJsonBody() throws Exception {
    HttpResponse<String> resp = postUsers("{}");
    Assertions.assertEquals(400, resp.statusCode(), resp.body());
    assertErrorCode(resp, 1001);
  }

  @Test
  @DisplayName("#59 Missing required field (no password) - returns 400, ILLEGAL_ARGUMENTS_CODE")
  void testMissingRequiredField() throws Exception {
    HttpResponse<String> resp = postUsers("{\"user\":\"alice\"}");
    Assertions.assertEquals(400, resp.statusCode(), resp.body());
    assertErrorCode(resp, 1001);
  }

  @Test
  @DisplayName("#60 Invalid JSON format - returns 400")
  void testInvalidJsonFormat() throws Exception {
    HttpResponse<String> resp = postUsers("not json");
    Assertions.assertEquals(400, resp.statusCode(), resp.body());
  }

  @Test
  @DisplayName("#61 Missing Content-Type header - returns 400 or 415")
  void testMissingContentType() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users"))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"user\":\"test\",\"password\":\"Passw0rd-Test-001\"}"))
            .build();
    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    int status = resp.statusCode();
    Assertions.assertTrue(status == 415, "Expected415 but got " + status + ": " + resp.body());
  }

  // ==================== Helper methods ====================

  private static HttpResponse<String> getWithAuth(String path, String user, String password)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + path))
            .header("Accept", ACCEPT)
            .header("Authorization", basicAuth(user, password))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> postUsers(String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(idpApiBase + "/users"))
            .header("Accept", ACCEPT)
            .header("Content-Type", ACCEPT)
            .header("Authorization", basicAuth(ADMIN, adminPassword))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String basicAuth(String username, String password) {
    String credentials = username + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertErrorCode(HttpResponse<String> resp, int expectedCode)
      throws Exception {
    JsonNode json = MAPPER.readTree(resp.body());
    Assertions.assertNotNull(json.get("code"), "Response missing 'code' field: " + resp.body());
    Assertions.assertEquals(expectedCode, json.get("code").asInt(), resp.body());
  }
}
