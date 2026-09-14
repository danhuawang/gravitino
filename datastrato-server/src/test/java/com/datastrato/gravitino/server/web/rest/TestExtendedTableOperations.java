/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.responses.TableWithColumnTagsResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.annotations.AuthorizationRequest;
import org.apache.gravitino.server.authorization.annotations.ExpressionCondition;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests {@link ExtendedTableOperations}. */
public class TestExtendedTableOperations extends JerseyTest {

  private static final String METALAKE = "metalake";
  private static final String CATALOG = "catalog";
  private static final String SCHEMA = "schema";
  private static final String TABLE = "users";

  private static Config previousConfig;

  private static final TableDispatcher TABLE_DISPATCHER = mock(TableDispatcher.class);
  private static final Map<MetadataObject, TagEntity[]> DIRECT_TAGS = new LinkedHashMap<>();
  private static final ColumnTagResolver COLUMN_TAG_RESOLVER =
      new ColumnTagResolver(
          mock(EntityStore.class),
          (metalake, objects, knownEntityIds) -> new LinkedHashMap<>(DIRECT_TAGS));

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {

    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  /** Disables authorization while preserving the previous shared configuration. */
  @BeforeAll
  public static void setup() throws IllegalAccessException {
    previousConfig = GravitinoEnv.getInstance().config();
    Config config = mock(Config.class);
    when(config.get(ENABLE_AUTHORIZATION)).thenReturn(false);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
  }

  /** Restores the shared configuration. */
  @AfterAll
  public static void restoreConfig() throws IllegalAccessException {
    FieldUtils.writeField(GravitinoEnv.getInstance(), "config", previousConfig, true);
  }

  /** Resets mocks and tag fixtures before each test. */
  @BeforeEach
  public void resetFixtures() {
    reset(TABLE_DISPATCHER);
    DIRECT_TAGS.clear();
  }

  /** {@inheritDoc} */
  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(ObjectMapperProvider.class);
    resourceConfig.register(new ExtendedTableOperations(TABLE_DISPATCHER, COLUMN_TAG_RESOLVER));
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  /** Tests the Enterprise response contract and explicit empty tag arrays. */
  @Test
  public void testLoadTableWithColumnTags() throws IOException {
    TableDTO table = table();
    when(TABLE_DISPATCHER.loadTable(any())).thenReturn(table);
    MetadataObject email =
        MetadataObjects.of(List.of(CATALOG, SCHEMA, TABLE, "email"), MetadataObject.Type.COLUMN);
    DIRECT_TAGS.put(email, new TagEntity[] {tag("PII"), tag("Sensitive")});

    Response response = request();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    String json = response.readEntity(String.class);
    JsonNode root = JsonUtils.objectMapper().readTree(json);
    Assertions.assertEquals(0, root.path("code").asInt());
    Assertions.assertEquals(TABLE, root.at("/table/name").asText());
    Assertions.assertEquals("PII", root.at("/table/columns/0/tags/0/name").asText());
    Assertions.assertFalse(root.at("/table/columns/0/tags/0/inherited").asBoolean());
    Assertions.assertEquals("Sensitive", root.at("/table/columns/0/tags/1/name").asText());
    Assertions.assertFalse(root.at("/table/columns/0/tags/1/inherited").asBoolean());
    Assertions.assertTrue(root.at("/table/columns/1/tags").isArray());
    Assertions.assertEquals(0, root.at("/table/columns/1/tags").size());
    Assertions.assertFalse(json.contains("policies"));

    TableWithColumnTagsResponse body =
        JsonUtils.objectMapper().readValue(json, TableWithColumnTagsResponse.class);
    Assertions.assertDoesNotThrow(body::validate);
    verify(TABLE_DISPATCHER)
        .loadTable(NameIdentifierUtil.ofTable(METALAKE, CATALOG, SCHEMA, TABLE));
  }

  /** Tests standard table-not-found exception mapping. */
  @Test
  public void testLoadMissingTable() {
    when(TABLE_DISPATCHER.loadTable(any())).thenThrow(new NoSuchTableException("missing"));

    Response response = request();

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse error = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, error.getCode());
    Assertions.assertEquals(NoSuchTableException.class.getSimpleName(), error.getType());
  }

  /** Tests that the Enterprise endpoint mirrors the OSS load-table authorization contract. */
  @Test
  public void testAuthorizationAnnotations() throws NoSuchMethodException {
    Method method =
        ExtendedTableOperations.class.getMethod(
            "loadTable", String.class, String.class, String.class, String.class, String.class);
    AuthorizationExpression expression = method.getAnnotation(AuthorizationExpression.class);
    Assertions.assertNotNull(expression);
    Assertions.assertEquals(
        AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION,
        expression.expression());
    Assertions.assertEquals("", expression.allowCheckExistence());
    Assertions.assertEquals(
        AuthorizationExpressionConstants.MODIFY_TABLE_AUTHORIZATION_EXPRESSION,
        expression.secondaryExpression());
    Assertions.assertEquals(
        ExpressionCondition.REQUIRED_MODIFY_PRIVILEGES, expression.secondaryExpressionCondition());
    Assertions.assertEquals(MetadataObject.Type.TABLE, expression.accessMetadataType());

    Parameter[] parameters = method.getParameters();
    Entity.EntityType[] expectedTypes =
        new Entity.EntityType[] {
          Entity.EntityType.METALAKE,
          Entity.EntityType.CATALOG,
          Entity.EntityType.SCHEMA,
          Entity.EntityType.TABLE
        };
    for (int i = 0; i < expectedTypes.length; i++) {
      AuthorizationMetadata metadata = parameters[i].getAnnotation(AuthorizationMetadata.class);
      Assertions.assertNotNull(metadata);
      Assertions.assertEquals(expectedTypes[i], metadata.type());
    }
    AuthorizationRequest request = parameters[4].getAnnotation(AuthorizationRequest.class);
    Assertions.assertNotNull(request);
    Assertions.assertEquals(AuthorizationRequest.RequestType.LOAD_TABLE, request.type());
  }

  private Response request() {
    return target(
            "/web/metalakes/"
                + METALAKE
                + "/catalogs/"
                + CATALOG
                + "/schemas/"
                + SCHEMA
                + "/tables/"
                + TABLE)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .get();
  }

  private static TableDTO table() {
    return TableDTO.builder()
        .withName(TABLE)
        .withComment("users")
        .withColumns(
            new ColumnDTO[] {
              ColumnDTO.builder().withName("email").withDataType(Types.StringType.get()).build(),
              ColumnDTO.builder().withName("region").withDataType(Types.StringType.get()).build()
            })
        .withProperties(Collections.emptyMap())
        .withAudit(AuditDTO.builder().build())
        .build();
  }

  private static TagEntity tag(String name) {
    return TagEntity.builder()
        .withId(1L)
        .withName(name)
        .withNamespace(Namespace.of(METALAKE))
        .withComment("")
        .withProperties(Collections.emptyMap())
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }
}
