/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.qa.common.oauth2;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.ProtocolMappersResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper for managing users and groups in a Keycloak realm during E2E tests.
 *
 * <p>Wraps the official {@code keycloak-admin-client} SDK so callers do not have to assemble Admin
 * REST requests by hand. Two admin-token strategies are supported:
 *
 * <ul>
 *   <li>{@code client_credentials} against a realm-level client that has been granted
 *       realm-management roles.
 *   <li>password grant against {@code master/admin-cli} using the bootstrap admin user. This is
 *       what the Keycloak {@code start-dev} container ships with by default.
 * </ul>
 *
 * <p>{@link #fetchUserToken(String, String)} mints a separate user access token via the
 * resource-owner password grant against the realm-level OIDC client, which is what downstream
 * Gravitino test clients consume.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * try (KeycloakAdminHelper keycloak =
 *     KeycloakAdminHelper.builder()
 *         .withBaseUrl(baseUrl)
 *         .withRealm("myrealm")
 *         .withMasterAdminPassword("admin", "admin")
 *         .withUserTokenClient("postman-client", "secret")
 *         .build()) {
 *   keycloak.authenticate();
 *   String userId = keycloak.createUser("alice", "secret");
 *   String groupId = keycloak.createGroup("dev");
 *   keycloak.addUserToGroup(userId, groupId);
 *   String aliceJwt = keycloak.fetchUserToken("alice", "secret");
 * }
 * }</pre>
 */
public class KeycloakAdminHelper implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(KeycloakAdminHelper.class);

  private static final String MASTER_REALM = "master";
  private static final String MASTER_ADMIN_CLIENT_ID = "admin-cli";

  private static final String DEFAULT_USER_TOKEN_SCOPE = "openid profile email";

  private static final String GROUPS_MAPPER_NAME = "gravitino-groups-mapper";

  private final String baseUrl;
  private final String realm;

  // Strategy used to mint the admin token. Exactly one pair is non-null:
  //   - adminClientId / adminClientSecret  (client_credentials against `realm`)
  //   - masterAdminUser / masterAdminPassword  (password grant against master/admin-cli)
  private final String adminClientId;
  private final String adminClientSecret;
  private final String masterAdminUser;
  private final String masterAdminPassword;

  // Realm-level client used to mint user tokens via the password grant in fetchUserToken().
  // May coincide with adminClientId when one client both has admin privileges and is the OIDC
  // client end-users authenticate against.
  private final String userTokenClientId;
  private final String userTokenClientSecret;

  private Keycloak adminKeycloak;

  /**
   * Creates a new {@code KeycloakAdminHelper} that mints both the admin token and user tokens via
   * the same realm-level client using the {@code client_credentials} and password grants
   * respectively. Use this when the client has been granted realm-management roles in Keycloak. For
   * deployments where admin operations require the master realm bootstrap admin, use {@link
   * #builder()} with {@link Builder#withMasterAdminPassword(String, String)} instead.
   *
   * @param baseUrl Keycloak base URL, e.g. {@code http://keycloak:8080}
   * @param realm The realm name, e.g. {@code myrealm}
   * @param clientId The client ID with admin privileges
   * @param clientSecret The client secret
   */
  public KeycloakAdminHelper(String baseUrl, String realm, String clientId, String clientSecret) {
    this(
        normalizeBaseUrl(baseUrl),
        realm,
        clientId,
        clientSecret,
        null,
        null,
        clientId,
        clientSecret);
  }

  private KeycloakAdminHelper(
      String baseUrl,
      String realm,
      String adminClientId,
      String adminClientSecret,
      String masterAdminUser,
      String masterAdminPassword,
      String userTokenClientId,
      String userTokenClientSecret) {
    this.baseUrl = baseUrl;
    this.realm = realm;
    this.adminClientId = adminClientId;
    this.adminClientSecret = adminClientSecret;
    this.masterAdminUser = masterAdminUser;
    this.masterAdminPassword = masterAdminPassword;
    this.userTokenClientId = userTokenClientId;
    this.userTokenClientSecret = userTokenClientSecret;
  }

  private static String normalizeBaseUrl(String baseUrl) {
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /**
   * Returns a new {@link Builder} for constructing a {@code KeycloakAdminHelper}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Initializes the underlying admin Keycloak client. Must be called before any other API method.
   * The SDK lazily mints and refreshes the admin token, so this method just verifies that
   * connectivity and credentials are correct by issuing a trivial realm lookup.
   */
  public void authenticate() {
    KeycloakBuilder builder = KeycloakBuilder.builder().serverUrl(baseUrl);

    String identity;
    if (masterAdminUser != null) {
      // Password grant against master/admin-cli — used when the realm-level client lacks
      // realm-management roles, which is the default for Keycloak's start-dev deployments.
      builder
          .realm(MASTER_REALM)
          .grantType(OAuth2Constants.PASSWORD)
          .clientId(MASTER_ADMIN_CLIENT_ID)
          .username(masterAdminUser)
          .password(masterAdminPassword);
      identity = masterAdminUser + "@" + MASTER_ADMIN_CLIENT_ID;
    } else {
      builder
          .realm(realm)
          .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
          .clientId(adminClientId)
          .clientSecret(adminClientSecret);
      identity = "client=" + adminClientId;
    }

    adminKeycloak = builder.build();

    // Trigger a token mint + lightweight call so misconfiguration surfaces here rather than
    // deep inside the first user/group operation.
    adminKeycloak.realm(realm).toRepresentation();
    LOG.info("Successfully authenticated with Keycloak as {}", identity);
  }

  /**
   * Creates a user in the realm with the given username and password. If the user already exists,
   * the existing user ID is returned and no error is thrown.
   *
   * @param username The username to create
   * @param password The password to set
   * @return The user ID (UUID) of the created or existing user
   */
  public String createUser(String username, String password) {
    String existingId = getUserId(username);
    if (existingId != null) {
      LOG.info("User '{}' already exists with id={}, skipping creation", username, existingId);
      return existingId;
    }

    UserRepresentation user = new UserRepresentation();
    user.setUsername(username);
    user.setEnabled(true);
    user.setEmailVerified(true);
    // The seeded `myrealm` realm enforces a default required-action policy on new users
    // (typically UPDATE_PASSWORD / VERIFY_EMAIL). With any required action pending, Keycloak
    // refuses the resource-owner password grant with `invalid_grant: Account is not fully set
    // up`. Pre-populate an email and clear required actions so password-grant logins work
    // immediately for E2E tests.
    user.setEmail(username + "@example.com");
    user.setFirstName(username);
    user.setLastName("test");
    user.setRequiredActions(Collections.emptyList());

    CredentialRepresentation credential = new CredentialRepresentation();
    credential.setType(CredentialRepresentation.PASSWORD);
    credential.setValue(password);
    credential.setTemporary(false);
    user.setCredentials(Collections.singletonList(credential));

    try (Response response = realm().users().create(user)) {
      int status = response.getStatus();
      if (status == Response.Status.CREATED.getStatusCode()) {
        String userId = CreatedResponseUtil.getCreatedId(response);
        LOG.info("Created user '{}' with id={}", username, userId);
        clearRequiredActions(userId, username);
        return userId;
      }
      if (status == Response.Status.CONFLICT.getStatusCode()) {
        LOG.info("User '{}' already exists (409), fetching ID", username);
        String existingUserId = getUserId(username);
        if (existingUserId != null) {
          clearRequiredActions(existingUserId, username);
        }
        return existingUserId;
      }
      throw new RuntimeException(
          "Failed to create user '"
              + username
              + "': HTTP "
              + status
              + " - "
              + response.readEntity(String.class));
    }
  }

  /**
   * Looks up a user ID by username.
   *
   * @param username The username to look up
   * @return The user ID, or {@code null} if the user does not exist
   */
  public String getUserId(String username) {
    List<UserRepresentation> users = realm().users().searchByUsername(username, true);
    if (users != null && !users.isEmpty()) {
      return users.get(0).getId();
    }
    return null;
  }

  /**
   * Creates a group in the realm. If the group already exists, the existing group ID is returned
   * and no error is thrown.
   *
   * @param groupName The group name to create
   * @return The group ID (UUID) of the created or existing group
   */
  public String createGroup(String groupName) {
    String existingId = getGroupId(groupName);
    if (existingId != null) {
      LOG.info("Group '{}' already exists with id={}, skipping creation", groupName, existingId);
      return existingId;
    }

    GroupRepresentation group = new GroupRepresentation();
    group.setName(groupName);

    try (Response response = realm().groups().add(group)) {
      int status = response.getStatus();
      if (status == Response.Status.CREATED.getStatusCode()) {
        String groupId = CreatedResponseUtil.getCreatedId(response);
        LOG.info("Created group '{}' with id={}", groupName, groupId);
        return groupId;
      }
      if (status == Response.Status.CONFLICT.getStatusCode()) {
        LOG.info("Group '{}' already exists (409), fetching ID", groupName);
        return getGroupId(groupName);
      }
      throw new RuntimeException(
          "Failed to create group '"
              + groupName
              + "': HTTP "
              + status
              + " - "
              + response.readEntity(String.class));
    }
  }

  /**
   * Looks up a group ID by group name.
   *
   * @param groupName The group name to look up
   * @return The group ID, or {@code null} if the group does not exist
   */
  public String getGroupId(String groupName) {
    // searchByName() with exact=true returns top-level groups whose name matches exactly.
    List<GroupRepresentation> groups = realm().groups().groups(groupName, true, 0, 1, false);
    if (groups != null) {
      for (GroupRepresentation group : groups) {
        if (groupName.equals(group.getName())) {
          return group.getId();
        }
      }
    }
    return null;
  }

  /**
   * Adds a user to a group.
   *
   * @param userId The user ID (UUID)
   * @param groupId The group ID (UUID)
   */
  public void addUserToGroup(String userId, String groupId) {
    userResource(userId).joinGroup(groupId);
    LOG.info("Added user {} to group {}", userId, groupId);
  }

  /**
   * Removes a user from a group.
   *
   * @param userId The user ID (UUID)
   * @param groupId The group ID (UUID)
   */
  public void removeUserFromGroup(String userId, String groupId) {
    userResource(userId).leaveGroup(groupId);
    LOG.info("Removed user {} from group {}", userId, groupId);
  }

  /**
   * Returns the names of the groups a user belongs to.
   *
   * @param userId The user ID (UUID)
   * @return List of group names (possibly empty)
   */
  public List<String> getUserGroups(String userId) {
    List<GroupRepresentation> groups = userResource(userId).groups();
    List<String> names = new ArrayList<>();
    if (groups != null) {
      for (GroupRepresentation group : groups) {
        names.add(group.getName());
      }
    }
    return names;
  }

  /**
   * Deletes a user from the realm. Failures are logged but do not throw, since this method is
   * primarily used for test cleanup.
   *
   * @param userId The user ID (UUID)
   */
  public void deleteUser(String userId) {
    try (Response response = realm().users().delete(userId)) {
      int status = response.getStatus();
      if (status != Response.Status.NO_CONTENT.getStatusCode()) {
        LOG.warn(
            "Failed to delete user {}: HTTP {} - {}",
            userId,
            status,
            response.readEntity(String.class));
      } else {
        LOG.info("Deleted user {}", userId);
      }
    } catch (WebApplicationException e) {
      LOG.warn("Failed to delete user {}: {}", userId, e.getMessage());
    }
  }

  /**
   * Ensures the realm-level OIDC client has a Group Membership protocol mapper that emits a {@code
   * groups} claim with full paths (e.g. {@code "/dev_team"}) into the access token. The Gravitino
   * server expects this shape (its env2 regex {@code ^/(.*)} strips the leading slash).
   *
   * <p>Idempotent: if a mapper named {@code GROUPS_MAPPER_NAME} already exists on the client this
   * method is a no-op. Designed for E2E tests that need to make a baked Keycloak Docker image
   * compatible with Gravitino's group-claim extraction without rebuilding the image.
   *
   * @param clientId The client ID (e.g. {@code postman-client})
   */
  public void ensureGroupsMapperOnClient(String clientId) {
    List<ClientRepresentation> clients = realm().clients().findByClientId(clientId);
    if (clients == null || clients.isEmpty()) {
      throw new IllegalStateException(
          "Cannot configure groups mapper: client '" + clientId + "' not found in realm");
    }
    ClientResource clientRes = realm().clients().get(clients.get(0).getId());
    ProtocolMappersResource mappers = clientRes.getProtocolMappers();
    for (ProtocolMapperRepresentation existing : mappers.getMappers()) {
      if (GROUPS_MAPPER_NAME.equals(existing.getName())) {
        LOG.info(
            "Groups mapper already present on client '{}' (id={}); skipping creation",
            clientId,
            existing.getId());
        return;
      }
    }

    ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
    mapper.setName(GROUPS_MAPPER_NAME);
    mapper.setProtocol("openid-connect");
    mapper.setProtocolMapper("oidc-group-membership-mapper");
    Map<String, String> config = new HashMap<>();
    // "full.path=true" emits "/dev_team" (matches Gravitino regex `^/(.*)`).
    config.put("full.path", "true");
    config.put("claim.name", "groups");
    config.put("jsonType.label", "String");
    config.put("id.token.claim", "true");
    config.put("access.token.claim", "true");
    config.put("userinfo.token.claim", "true");
    mapper.setConfig(config);

    try (Response response = mappers.createMapper(mapper)) {
      int status = response.getStatus();
      if (status != Response.Status.CREATED.getStatusCode()
          && status != Response.Status.NO_CONTENT.getStatusCode()) {
        throw new RuntimeException(
            "Failed to create groups mapper on client '"
                + clientId
                + "': HTTP "
                + status
                + " - "
                + response.readEntity(String.class));
      }
      LOG.info("Created groups protocol mapper on client '{}'", clientId);
    }
  }

  /**
   * Deletes a group from the realm. Failures are logged but do not throw, since this method is
   * primarily used for test cleanup.
   *
   * @param groupId The group ID (UUID)
   */
  public void deleteGroup(String groupId) {
    try {
      groupResource(groupId).remove();
      LOG.info("Deleted group {}", groupId);
    } catch (WebApplicationException e) {
      LOG.warn("Failed to delete group {}: {}", groupId, e.getMessage());
    }
  }

  /**
   * Fetches an access token for a user using the resource-owner password grant against the
   * realm-level OIDC client.
   *
   * <p>Uses a raw {@code application/x-www-form-urlencoded} POST instead of the keycloak-admin SDK
   * so a non-2xx response surfaces Keycloak's {@code error} / {@code error_description} fields
   * verbatim (the SDK collapses them into a generic {@code HTTP 400}). For confidential clients,
   * the client secret is sent in the form body; for public clients leave the secret unset (null or
   * empty) when constructing the helper.
   *
   * @param username The username
   * @param password The password
   * @return The access token string
   */
  public String fetchUserToken(String username, String password) {
    String tokenEndpoint = baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";

    StringBuilder body = new StringBuilder();
    appendForm(body, "grant_type", OAuth2Constants.PASSWORD);
    appendForm(body, "client_id", userTokenClientId);
    if (userTokenClientSecret != null && !userTokenClientSecret.isEmpty()) {
      appendForm(body, "client_secret", userTokenClientSecret);
    }
    appendForm(body, "username", username);
    appendForm(body, "password", password);
    appendForm(body, "scope", DEFAULT_USER_TOKEN_SCOPE);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(tokenEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

    HttpResponse<String> response;
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to call token endpoint " + tokenEndpoint + " for user '" + username + "'", e);
    }

    int status = response.statusCode();
    String responseBody = response.body() == null ? "" : response.body();
    if (status / 100 != 2) {
      throw new RuntimeException(
          "Password grant for user '"
              + username
              + "' against client '"
              + userTokenClientId
              + "' failed: HTTP "
              + status
              + " "
              + responseBody);
    }
    String token = extractJsonString(responseBody, "access_token");
    if (token == null) {
      throw new RuntimeException(
          "Password grant for user '" + username + "' returned no access_token: " + responseBody);
    }
    return token;
  }

  /** Form-encodes a {@code key=value} pair onto {@code sb}, prefixing with {@code &} as needed. */
  private static void appendForm(StringBuilder sb, String key, String value) {
    if (sb.length() > 0) {
      sb.append('&');
    }
    sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
        .append('=')
        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  /**
   * Minimal JSON string-field extractor for top-level Keycloak token responses. Avoids pulling in a
   * JSON dependency for what is structurally a flat envelope ({@code access_token}, {@code error},
   * {@code error_description}, ...).
   */
  private static String extractJsonString(String json, String field) {
    String key = "\"" + field + "\"";
    int keyIdx = json.indexOf(key);
    if (keyIdx < 0) {
      return null;
    }
    int colonIdx = json.indexOf(':', keyIdx + key.length());
    if (colonIdx < 0) {
      return null;
    }
    int quoteOpen = json.indexOf('"', colonIdx + 1);
    if (quoteOpen < 0) {
      return null;
    }
    int quoteClose = json.indexOf('"', quoteOpen + 1);
    if (quoteClose < 0) {
      return null;
    }
    return json.substring(quoteOpen + 1, quoteClose);
  }

  @Override
  public void close() {
    if (adminKeycloak != null) {
      adminKeycloak.close();
    }
    LOG.info("KeycloakAdminHelper closed");
  }

  // --- private helpers -----------------------------------------------------------------------

  private RealmResource realm() {
    if (adminKeycloak == null) {
      throw new IllegalStateException("authenticate() must be called before using the helper");
    }
    return adminKeycloak.realm(realm);
  }

  private UserResource userResource(String userId) {
    return realm().users().get(userId);
  }

  /**
   * Defensively clears any required actions Keycloak's default realm policy may have attached to a
   * freshly-created user. Without this, the realm's "Update Password" (or similar) default flow
   * leaves the account in a half-configured state that the resource-owner password grant rejects
   * with {@code invalid_grant: Account is not fully set up}.
   */
  private void clearRequiredActions(String userId, String username) {
    try {
      UserResource userRes = userResource(userId);
      UserRepresentation current = userRes.toRepresentation();
      List<String> required = current.getRequiredActions();
      if (required == null || required.isEmpty()) {
        return;
      }
      LOG.info(
          "Clearing required actions {} on user '{}' (id={}) so password grant succeeds",
          required,
          username,
          userId);
      current.setRequiredActions(Collections.emptyList());
      userRes.update(current);
    } catch (Exception e) {
      LOG.warn("Failed to clear required actions for user '{}': {}", username, e.getMessage());
    }
  }

  private GroupResource groupResource(String groupId) {
    return realm().groups().group(groupId);
  }

  /**
   * Builder for {@link KeycloakAdminHelper}. Lets the caller pick how the admin token is minted
   * (master-realm password grant or realm-level {@code client_credentials}) and which realm-level
   * client is used to mint user tokens via the password grant.
   */
  public static final class Builder {

    private String baseUrl;
    private String realm;
    private String adminClientId;
    private String adminClientSecret;
    private String masterAdminUser;
    private String masterAdminPassword;
    private String userTokenClientId;
    private String userTokenClientSecret;

    private Builder() {}

    /**
     * Sets the Keycloak base URL.
     *
     * @param baseUrl Keycloak base URL, e.g. {@code http://keycloak:8080}
     * @return this builder
     */
    public Builder withBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Sets the realm to manage users and groups in.
     *
     * @param realm The realm name, e.g. {@code myrealm}
     * @return this builder
     */
    public Builder withRealm(String realm) {
      this.realm = realm;
      return this;
    }

    /**
     * Configures the admin token to be minted via {@code client_credentials} against a realm-level
     * client. Mutually exclusive with {@link #withMasterAdminPassword(String, String)}.
     *
     * @param clientId Realm-level client id with admin privileges
     * @param clientSecret Realm-level client secret
     * @return this builder
     */
    public Builder withClientCredentials(String clientId, String clientSecret) {
      this.adminClientId = clientId;
      this.adminClientSecret = clientSecret;
      return this;
    }

    /**
     * Configures the admin token to be minted via the password grant against the {@code master}
     * realm's {@code admin-cli} client using the bootstrap admin user. Mutually exclusive with
     * {@link #withClientCredentials(String, String)}.
     *
     * @param adminUser Bootstrap admin username, typically {@code admin}
     * @param adminPassword Bootstrap admin password
     * @return this builder
     */
    public Builder withMasterAdminPassword(String adminUser, String adminPassword) {
      this.masterAdminUser = adminUser;
      this.masterAdminPassword = adminPassword;
      return this;
    }

    /**
     * Sets the realm-level client used by {@link KeycloakAdminHelper#fetchUserToken(String,
     * String)} to mint user tokens via the password grant. Required when the admin auth strategy is
     * {@link #withMasterAdminPassword(String, String)}; otherwise defaults to the client supplied
     * to {@link #withClientCredentials(String, String)}.
     *
     * @param clientId Realm-level OIDC client id
     * @param clientSecret Realm-level OIDC client secret
     * @return this builder
     */
    public Builder withUserTokenClient(String clientId, String clientSecret) {
      this.userTokenClientId = clientId;
      this.userTokenClientSecret = clientSecret;
      return this;
    }

    /**
     * Builds a {@link KeycloakAdminHelper} configured per the builder.
     *
     * @return a new {@code KeycloakAdminHelper}
     * @throws IllegalStateException if required fields are missing or both admin-auth strategies
     *     are configured
     */
    public KeycloakAdminHelper build() {
      if (baseUrl == null || baseUrl.isEmpty()) {
        throw new IllegalStateException("baseUrl is required");
      }
      if (realm == null || realm.isEmpty()) {
        throw new IllegalStateException("realm is required");
      }
      boolean usingClientCredentials = adminClientId != null;
      boolean usingMasterPassword = masterAdminUser != null;
      if (usingClientCredentials == usingMasterPassword) {
        throw new IllegalStateException(
            "Exactly one of withClientCredentials() or withMasterAdminPassword() must be set");
      }

      String resolvedUserTokenClientId = userTokenClientId;
      String resolvedUserTokenClientSecret = userTokenClientSecret;
      if (resolvedUserTokenClientId == null) {
        if (!usingClientCredentials) {
          throw new IllegalStateException(
              "withUserTokenClient(...) is required when using withMasterAdminPassword(...)");
        }
        resolvedUserTokenClientId = adminClientId;
        resolvedUserTokenClientSecret = adminClientSecret;
      }

      return new KeycloakAdminHelper(
          normalizeBaseUrl(baseUrl),
          realm,
          adminClientId,
          adminClientSecret,
          masterAdminUser,
          masterAdminPassword,
          resolvedUserTokenClientId,
          resolvedUserTokenClientSecret);
    }
  }
}
