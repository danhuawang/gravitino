/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.apache.gravitino.Configs.CACHE_ENABLED;
import static org.apache.gravitino.Configs.ENABLE_AUTHORIZATION;
import static org.apache.gravitino.Configs.GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE;
import static org.apache.gravitino.Configs.SCHEMA_SEPARATOR;
import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;
import static org.apache.gravitino.file.Fileset.LOCATION_NAME_UNKNOWN;
import static org.apache.gravitino.file.Fileset.Type.MANAGED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.catalog.DatastratoFilesetDispatcher;
import com.datastrato.gravitino.catalog.DatastratoModelDispatcher;
import com.datastrato.gravitino.catalog.DatastratoSchemaOperationDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTableDispatcher;
import com.datastrato.gravitino.catalog.DatastratoTopicDispatcher;
import com.datastrato.gravitino.catalog.DatastratoViewDispatcher;
import com.datastrato.gravitino.dto.ExtendedCatalogDTO;
import com.datastrato.gravitino.dto.ExtendedMetalakeDTO;
import com.datastrato.gravitino.dto.ExtendedSchemaDTO;
import com.datastrato.gravitino.dto.file.ExtendedFilesetDTO;
import com.datastrato.gravitino.dto.function.ExtendedFunctionDTO;
import com.datastrato.gravitino.dto.messaging.ExtendedTopicDTO;
import com.datastrato.gravitino.dto.model.ExtendedModelDTO;
import com.datastrato.gravitino.dto.rel.ExtendedTableDTO;
import com.datastrato.gravitino.dto.rel.ExtendedViewDTO;
import com.datastrato.gravitino.dto.responses.CatalogListResponse;
import com.datastrato.gravitino.dto.responses.FilesetListResponse;
import com.datastrato.gravitino.dto.responses.MetalakeListResponse;
import com.datastrato.gravitino.dto.responses.ModelListResponse;
import com.datastrato.gravitino.dto.responses.SchemaListResponse;
import com.datastrato.gravitino.dto.responses.TableListResponse;
import com.datastrato.gravitino.dto.responses.TopicListResponse;
import com.datastrato.gravitino.tag.mapper.DatastratoTagPolicyMetadataObjectMapper;
import com.datastrato.gravitino.tag.po.DatastratoPolicyRelPO;
import com.datastrato.gravitino.tag.po.DatastratoTagRelPO;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.CatalogManager;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.rel.ViewDTO;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.function.Function;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authorization.GravitinoAuthorizerProvider;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestEntityOperations extends JerseyTest {

  private static EntityStore previousEntityStore;
  private static SqlSessionFactory previousSqlSessionFactory;
  private static EntityStore entityStore;
  private static DatastratoTagPolicyMetadataObjectMapper tagPolicyMapper;

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  private static class TestFailureTracker {
    private Optional<Throwable> testFailure = Optional.empty();

    private void record(Throwable failure) {
      testFailure = Optional.of(failure);
    }

    private void handleRestoreFailure(IllegalAccessException failure) {
      RuntimeException restoreFailure =
          new RuntimeException("Failed to restore GravitinoEnv state after test", failure);
      if (testFailure.isPresent()) {
        testFailure.get().addSuppressed(restoreFailure);
      } else {
        throw restoreFailure;
      }
    }
  }

  private final MetalakeDispatcher metalakeDispatcher = mock(MetalakeDispatcher.class);
  private final CatalogDispatcher catalogDispatcher = mock(CatalogManager.class);
  private final DatastratoSchemaOperationDispatcher schemaDispatcher =
      mock(DatastratoSchemaOperationDispatcher.class);
  private final DatastratoTableDispatcher tableDispatcher = mock(DatastratoTableDispatcher.class);
  private final DatastratoFilesetDispatcher filesetDispatcher =
      mock(DatastratoFilesetDispatcher.class);
  private final DatastratoTopicDispatcher topicDispatcher = mock(DatastratoTopicDispatcher.class);
  private final DatastratoModelDispatcher modelDispatcher = mock(DatastratoModelDispatcher.class);
  private final FunctionDispatcher functionDispatcher = mock(FunctionDispatcher.class);
  private final DatastratoViewDispatcher viewDispatcher = mock(DatastratoViewDispatcher.class);
  private final TagDispatcher tagDispatcher = mock(TagDispatcher.class);
  private final PolicyDispatcher policyDispatcher = mock(PolicyDispatcher.class);

  @BeforeAll
  public static void setup() throws IllegalAccessException {
    Config config = mock(Config.class);
    Mockito.doReturn(100000L).when(config).get(TREE_LOCK_MAX_NODE_IN_MEMORY);
    Mockito.doReturn(1000L).when(config).get(TREE_LOCK_MIN_NODE_IN_MEMORY);
    Mockito.doReturn(36000L).when(config).get(TREE_LOCK_CLEAN_INTERVAL);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);

    previousEntityStore =
        (EntityStore) FieldUtils.readField(GravitinoEnv.getInstance(), "entityStore", true);
    entityStore = mock(EntityStore.class);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "entityStore", entityStore, true);

    previousSqlSessionFactory =
        (SqlSessionFactory)
            FieldUtils.readStaticField(SqlSessionFactoryHelper.class, "sqlSessionFactory", true);
    SqlSessionFactory mockSqlSessionFactory = mock(SqlSessionFactory.class);
    SqlSession mockSqlSession = mock(SqlSession.class);
    when(mockSqlSessionFactory.openSession(any(TransactionIsolationLevel.class)))
        .thenReturn(mockSqlSession);
    when(mockSqlSessionFactory.openSession(anyBoolean())).thenReturn(mockSqlSession);
    when(mockSqlSessionFactory.openSession()).thenReturn(mockSqlSession);
    tagPolicyMapper = mock(DatastratoTagPolicyMetadataObjectMapper.class);
    when(mockSqlSession.getMapper(DatastratoTagPolicyMetadataObjectMapper.class))
        .thenReturn(tagPolicyMapper);
    FieldUtils.writeStaticField(
        SqlSessionFactoryHelper.class, "sqlSessionFactory", mockSqlSessionFactory, true);
  }

  @AfterAll
  public static void tearDownClass() throws IllegalAccessException {
    FieldUtils.writeStaticField(
        SqlSessionFactoryHelper.class, "sqlSessionFactory", previousSqlSessionFactory, true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "entityStore", previousEntityStore, true);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(EntityOperations.class);
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(metalakeDispatcher).to(MetalakeDispatcher.class).ranked(2);
            bind(catalogDispatcher).to(CatalogDispatcher.class).ranked(2);
            bind(schemaDispatcher).to(SchemaDispatcher.class).ranked(2);
            bind(tableDispatcher).to(TableDispatcher.class).ranked(2);
            bind(topicDispatcher).to(TopicDispatcher.class).ranked(2);
            bind(filesetDispatcher).to(FilesetDispatcher.class).ranked(2);
            bind(modelDispatcher).to(ModelDispatcher.class).ranked(2);
            bind(functionDispatcher).to(FunctionDispatcher.class).ranked(2);
            bind(viewDispatcher).to(ViewDispatcher.class).ranked(2);
            bind(tagDispatcher).to(TagDispatcher.class).ranked(2);
            bind(policyDispatcher).to(PolicyDispatcher.class).ranked(2);
            bindFactory(TestEntityOperations.MockServletRequestFactory.class)
                .to(HttpServletRequest.class);
          }
        });

    return resourceConfig;
  }

  @Test
  public void testListMetalakes() {
    BaseMetalake metalakeZ = buildMetalake(1L, "metalake-z");
    BaseMetalake metalakeA = buildMetalake(2L, "metalake-a");
    BaseMetalake metalakeM = buildMetalake(3L, "metalake-m");
    when(metalakeDispatcher.listMetalakes())
        .thenReturn(new Metalake[] {metalakeZ, metalakeA, metalakeM});

    Namespace metalakeANamespace = Namespace.of("metalake-a");
    when(catalogDispatcher.listCatalogs(metalakeANamespace))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of(metalakeANamespace, "catalog-a1"),
              NameIdentifier.of(metalakeANamespace, "catalog-a2")
            });
    when(catalogDispatcher.listCatalogs(Namespace.of("metalake-m")))
        .thenReturn(new NameIdentifier[0]);

    Response response =
        target("/web/entities")
            .queryParam("resultLimit", 2)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
    MetalakeListResponse metalakeResponse = response.readEntity(MetalakeListResponse.class);
    Assertions.assertEquals(0, metalakeResponse.getCode());
    ExtendedMetalakeDTO[] metalakes = metalakeResponse.getMetalakes();
    Assertions.assertEquals(2, metalakes.length);
    Assertions.assertEquals("metalake-a", metalakes[0].name());
    Assertions.assertEquals(2L, metalakes[0].getDirectChildCounts());
    Assertions.assertEquals("metalake-m", metalakes[1].name());
    Assertions.assertEquals(0L, metalakes[1].getDirectChildCounts());

    Response emptyNamespaceResponse =
        target("/web/entities")
            .queryParam("namespace", "")
            .queryParam("resultLimit", 2)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), emptyNamespaceResponse.getStatus());
    MetalakeListResponse emptyNamespaceMetalakeResponse =
        emptyNamespaceResponse.readEntity(MetalakeListResponse.class);
    Assertions.assertArrayEquals(metalakes, emptyNamespaceMetalakeResponse.getMetalakes());
    Mockito.verify(catalogDispatcher, Mockito.never()).listCatalogs(Namespace.of("metalake-z"));
  }

  @Test
  public void testListMetalakeDirectChildCountUnavailable() {
    BaseMetalake metalakeA = buildMetalake(1L, "metalake-a");
    BaseMetalake metalakeB = buildMetalake(2L, "metalake-b");
    when(metalakeDispatcher.listMetalakes()).thenReturn(new Metalake[] {metalakeA, metalakeB});
    when(catalogDispatcher.listCatalogs(Namespace.of("metalake-a")))
        .thenThrow(new IllegalStateException("Catalog count is unavailable"));
    Namespace metalakeBNamespace = Namespace.of("metalake-b");
    when(catalogDispatcher.listCatalogs(metalakeBNamespace))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(metalakeBNamespace, "catalog-b")});

    Response response =
        target("/web/entities")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    MetalakeListResponse metalakeResponse = response.readEntity(MetalakeListResponse.class);
    Assertions.assertNull(metalakeResponse.getMetalakes()[0].getDirectChildCounts());
    Assertions.assertEquals(1L, metalakeResponse.getMetalakes()[1].getDirectChildCounts());
  }

  @Test
  public void testListEntitiesException() {
    // test root-level-only query parameters without a namespace
    Response resp =
        target("/web/entities")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ErrorResponse errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals(
        "Query params catalogType and parentSchema cannot be set when namespace is empty",
        errorResp.getMessage());

    // test parentSchema without a namespace
    resp =
        target("/web/entities")
            .queryParam("parentSchema", "parent")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals(
        "Query params catalogType and parentSchema cannot be set when namespace is empty",
        errorResp.getMessage());

    // test namespace does not exist
    doThrow(new NoSuchSchemaException("Schema metalake.catalog.schema does not exist"))
        .when(tableDispatcher)
        .listTables(any());
    resp =
        target("/web/entities")
            .queryParam("namespace", "metalake.catalog.schema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResp.getCode());
    Assertions.assertEquals(NoSuchSchemaException.class.getSimpleName(), errorResp.getType());
    Assertions.assertTrue(
        errorResp.getMessage().contains("Schema metalake.catalog.schema does not exist"),
        errorResp.getMessage());

    // test catalogType error
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals("Query param catalogType cannot be empty", errorResp.getMessage());

    // test result limit error
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake")
            .queryParam("catalogType", "relational")
            .queryParam("resultLimit", "0")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals("Result limit should be greater than 0", errorResp.getMessage());

    // test parentSchema with invalid namespace levels (only 2 or 3 levels are allowed)
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake")
            .queryParam("catalogType", "relational")
            .queryParam("parentSchema", "a:b")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertEquals(
        "Query param namespace should have 2 or 3 levels when parentSchema is set",
        errorResp.getMessage());

    // test malformed parentSchema
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog")
            .queryParam("catalogType", "relational")
            .queryParam("parentSchema", "A::B")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    errorResp = resp.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.ILLEGAL_ARGUMENTS_CODE, errorResp.getCode());
    Assertions.assertEquals(IllegalArgumentException.class.getSimpleName(), errorResp.getType());
    Assertions.assertTrue(
        errorResp.getMessage().contains("contains an empty segment after splitting"),
        errorResp.getMessage());
  }

  @Test
  public void testListEntities() {
    // test list catalogs
    TestCatalog catalog1 = buildCatalog("testMetalake", "relCatalog1");
    TestCatalog catalog2 = buildCatalog("testMetalake", "relCatalog2");
    Catalog[] mockedCatalogs = {catalog1, catalog2};
    when(catalogDispatcher.listCatalogsInfo(Namespace.of("testMetalake")))
        .thenReturn(mockedCatalogs);
    when(schemaDispatcher.listSchemas(Namespace.of("testMetalake", "relCatalog1")))
        .thenReturn(new NameIdentifier[0]);
    when(schemaDispatcher.listSchemas(Namespace.of("testMetalake", "relCatalog2")))
        .thenReturn(new NameIdentifier[0]);

    Response resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    CatalogListResponse catalogResponse = resp.readEntity(CatalogListResponse.class);
    Assertions.assertEquals(0, catalogResponse.getCode());

    ExtendedCatalogDTO[] catalogDTOs = catalogResponse.getCatalogs();
    Assertions.assertEquals(2, catalogDTOs.length);
    assertCatalogs(catalogDTOs);
    Assertions.assertEquals(0L, catalogDTOs[0].getDirectChildCounts());
    Assertions.assertEquals(0L, catalogDTOs[1].getDirectChildCounts());

    // test list all catalogs
    when(catalogDispatcher.listCatalogsInfo(Namespace.of("testMetalake")))
        .thenReturn(mockedCatalogs);

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
    catalogResponse = resp.readEntity(CatalogListResponse.class);
    Assertions.assertEquals(0, catalogResponse.getCode());

    catalogDTOs = catalogResponse.getCatalogs();
    Assertions.assertEquals(2, catalogDTOs.length);
    assertCatalogs(catalogDTOs);

    // test list schemas
    Namespace catalogNs = Namespace.of("testMetalake", "relCatalog");
    Namespace namespace = catalogNs;
    NameIdentifier schemaIdent = NameIdentifier.of(namespace, "relSchema");
    NameIdentifier[] schemaIdents = {schemaIdent};
    when(schemaDispatcher.listSchemas(namespace)).thenReturn(schemaIdents);
    when(schemaDispatcher.listEntities(namespace)).thenReturn(buildSchemaEntity(schemaIdents));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    SchemaListResponse schemaResp = resp.readEntity(SchemaListResponse.class);
    Assertions.assertEquals(0, schemaResp.getCode());
    Assertions.assertEquals(1, schemaResp.getSchemas().length);
    assertSchemas(schemaResp.getSchemas());

    // test list tables
    namespace = Namespace.of("testMetalake", "relCatalog", "relSchema");
    NameIdentifier tableIdent = NameIdentifier.of(namespace, "relTable");
    NameIdentifier[] tableIdents = {tableIdent};
    when(tableDispatcher.listTables(namespace)).thenReturn(tableIdents);
    when(tableDispatcher.listEntities(namespace)).thenReturn(buildTableEntity(tableIdents));
    when(functionDispatcher.listFunctionInfos(namespace)).thenReturn(buildFunctionInfos(namespace));
    mockViews(namespace);

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog.relSchema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    TableListResponse tableResp = resp.readEntity(TableListResponse.class);
    Assertions.assertEquals(0, tableResp.getCode());
    Assertions.assertEquals(1, tableResp.getTables().length);
    assertTables(tableResp.getTables());
    Assertions.assertEquals(1, tableResp.getFunctions().length);
    assertFunctions(tableResp.getFunctions());
    Assertions.assertEquals(1, tableResp.getViews().length);
    assertViews(tableResp.getViews());

    // test list schemas with parentSchema query parameter for hierarchical schema
    Namespace parentSchemaNs = Namespace.of("testMetalake", "relCatalog", "level1:level2");
    NameIdentifier childSchemaIdent = NameIdentifier.of(catalogNs, "level1:level2:child");
    NameIdentifier[] childSchemaIdents = {childSchemaIdent};
    when(schemaDispatcher.listSchemas(parentSchemaNs)).thenReturn(childSchemaIdents);
    when(schemaDispatcher.listEntities(catalogNs))
        .thenReturn(buildSchemaEntity(new NameIdentifier[] {schemaIdent, childSchemaIdent}));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog")
            .queryParam("catalogType", "relational")
            .queryParam("parentSchema", "level1:level2")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    schemaResp = resp.readEntity(SchemaListResponse.class);
    Assertions.assertEquals(0, schemaResp.getCode());
    Assertions.assertEquals(1, schemaResp.getSchemas().length);
    Assertions.assertEquals("level1:level2:child", schemaResp.getSchemas()[0].name());
    Assertions.assertEquals(
        "creator", schemaResp.getSchemas()[0].getSchemaDTO().auditInfo().creator());

    // test list tables with parentSchema: returns tables + sub-schemas
    Namespace tableNs = Namespace.of("testMetalake", "relCatalog", "level1:level2");
    NameIdentifier hTableIdent = NameIdentifier.of(tableNs, "hTable");
    NameIdentifier[] hTableIdents = {hTableIdent};
    when(tableDispatcher.listTables(tableNs)).thenReturn(hTableIdents);
    when(tableDispatcher.listEntities(tableNs)).thenReturn(buildTableEntity(hTableIdents));
    when(functionDispatcher.listFunctionInfos(tableNs)).thenReturn(buildFunctionInfos(tableNs));
    mockViews(tableNs);
    // child schemas are already mocked above for parentSchemaNs

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog.level1:level2")
            .queryParam("catalogType", "relational")
            .queryParam("parentSchema", "level1:level2")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    tableResp = resp.readEntity(TableListResponse.class);
    Assertions.assertEquals(0, tableResp.getCode());
    Assertions.assertEquals(1, tableResp.getTables().length);
    Assertions.assertEquals("hTable", tableResp.getTables()[0].name());
    Assertions.assertEquals(1, tableResp.getFunctions().length);
    Assertions.assertEquals(1, tableResp.getViews().length);
    Assertions.assertEquals(1, tableResp.getSchemas().length);
    Assertions.assertEquals("level1:level2:child", tableResp.getSchemas()[0].name());
    Assertions.assertEquals(
        "creator", tableResp.getSchemas()[0].getSchemaDTO().auditInfo().creator());
    Mockito.verify(schemaDispatcher, Mockito.never()).listEntities(parentSchemaNs);

    // test list tables with schema not found in store
    doThrow(new NoSuchSchemaException("Schema testMetalake.relCatalog.relSchema does not exist"))
        .when(tableDispatcher)
        .listEntities(any());
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog.relSchema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    tableResp = resp.readEntity(TableListResponse.class);
    Assertions.assertEquals(0, tableResp.getCode());
    Assertions.assertEquals(1, tableResp.getTables().length);
    ExtendedTableDTO tableDTO = tableResp.getTables()[0];
    Assertions.assertEquals("relTable", tableDTO.name());
    Assertions.assertNull(tableDTO.getTableDTO().comment());
    Assertions.assertNull(tableDTO.getTableDTO().properties());
    Assertions.assertNull(tableDTO.getTableDTO().auditInfo().creator());
    Assertions.assertEquals(0, tableDTO.getTags().length);
    Assertions.assertEquals(0, tableDTO.getPolicies().length);
    Assertions.assertEquals(1, tableResp.getViews().length);
    assertViews(tableResp.getViews());

    // test list tables with schema not found in store for views.
    doThrow(new NoSuchSchemaException("Schema testMetalake.relCatalog.relSchema does not exist"))
        .when(viewDispatcher)
        .listEntities(namespace);

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.relCatalog.relSchema")
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

    tableResp = resp.readEntity(TableListResponse.class);
    Assertions.assertEquals(1, tableResp.getViews().length);
    ExtendedViewDTO fallbackView = tableResp.getViews()[0];
    Assertions.assertEquals("testView", fallbackView.name());
    Assertions.assertNull(fallbackView.getViewDTO().comment());
    Assertions.assertNull(fallbackView.getViewDTO().auditInfo().creator());
    Assertions.assertEquals(1, fallbackView.getViewDTO().representations().length);
    Assertions.assertInstanceOf(
        SQLRepresentation.class, fallbackView.getViewDTO().representations()[0]);
    SQLRepresentation representation =
        (SQLRepresentation) fallbackView.getViewDTO().representations()[0];
    Assertions.assertEquals("unavailable", representation.dialect());
    Assertions.assertEquals("UNAVAILABLE", representation.sql());

    // test list topics
    namespace = Namespace.of("testMetalake", "messagingCatalog", "messagingSchema");
    NameIdentifier topicIdent = NameIdentifier.of(namespace, "messagingTopic");
    NameIdentifier[] topicIdents = {topicIdent};
    when(topicDispatcher.listTopics(namespace)).thenReturn(topicIdents);
    when(topicDispatcher.listEntities(namespace)).thenReturn(buildTopicEntity(topicIdents));
    when(functionDispatcher.listFunctionInfos(namespace)).thenReturn(buildFunctionInfos(namespace));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.messagingCatalog.messagingSchema")
            .queryParam("catalogType", "messaging")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    TopicListResponse topicResp = resp.readEntity(TopicListResponse.class);
    Assertions.assertEquals(0, topicResp.getCode());
    Assertions.assertEquals(1, topicResp.getTopics().length);
    assertTopics(topicResp.getTopics());
    Assertions.assertEquals(1, topicResp.getFunctions().length);
    assertFunctions(topicResp.getFunctions());

    // test list topics with schema not found in store
    doThrow(
            new NoSuchSchemaException(
                "Schema testMetalake.messagingCatalog.messagingSchema does not exist"))
        .when(topicDispatcher)
        .listEntities(any());
    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.messagingCatalog.messagingSchema")
            .queryParam("catalogType", "messaging")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    topicResp = resp.readEntity(TopicListResponse.class);
    Assertions.assertEquals(0, topicResp.getCode());
    Assertions.assertEquals(1, topicResp.getTopics().length);
    ExtendedTopicDTO topicDTO = topicResp.getTopics()[0];
    Assertions.assertEquals("messagingTopic", topicDTO.name());
    Assertions.assertNull(topicDTO.getTopicDTO().comment());
    Assertions.assertNull(topicDTO.getTopicDTO().properties());
    Assertions.assertNull(topicDTO.getTopicDTO().auditInfo().creator());

    // test list filesets
    namespace = Namespace.of("testMetalake", "filesetCatalog", "filesetSchema");
    NameIdentifier filesetIdent = NameIdentifier.of(namespace, "fileset");
    NameIdentifier[] filesetIdents = {filesetIdent};
    when(filesetDispatcher.listEntities(namespace)).thenReturn(buildFilesetEntity(filesetIdents));
    when(functionDispatcher.listFunctionInfos(namespace)).thenReturn(buildFunctionInfos(namespace));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.filesetCatalog.filesetSchema")
            .queryParam("catalogType", "fileset")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    FilesetListResponse filesetResp = resp.readEntity(FilesetListResponse.class);
    Assertions.assertEquals(0, filesetResp.getCode());
    Assertions.assertEquals(1, filesetResp.getFilesets().length);
    assertFilesets(filesetResp.getFilesets());
    Assertions.assertEquals(1, filesetResp.getFunctions().length);
    assertFunctions(filesetResp.getFunctions());

    // test list models
    Namespace modelNamespace = Namespace.of("testMetalake", "modelCatalog", "modelSchema");
    NameIdentifier modelIdent = NameIdentifier.of(modelNamespace, "model");
    NameIdentifier[] modelIdents = {modelIdent};
    when(modelDispatcher.listEntities(modelNamespace)).thenReturn(buildModelEntity(modelIdents));
    when(functionDispatcher.listFunctionInfos(modelNamespace))
        .thenReturn(buildFunctionInfos(modelNamespace));

    resp =
        target("/web/entities")
            .queryParam("namespace", "testMetalake.modelCatalog.modelSchema")
            .queryParam("catalogType", "model")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());

    ModelListResponse modelResp = resp.readEntity(ModelListResponse.class);
    Assertions.assertEquals(0, modelResp.getCode());
    Assertions.assertEquals(1, modelResp.getModels().length);
    assertModels(modelResp.getModels());
    Assertions.assertEquals(1, modelResp.getFunctions().length);
    assertFunctions(modelResp.getFunctions());
  }

  @Test
  public void testHierarchicalSchemaInheritsTagsAndPoliciesFromAllAncestors() throws Exception {
    String metalake = "inheritanceMetalake";
    String catalog = "inheritanceCatalog";
    Namespace metalakeNamespace = Namespace.of(metalake);
    Namespace catalogNamespace = Namespace.of(metalake, catalog);
    Namespace parentSchemaNamespace = Namespace.of(metalake, catalog, "a:b");
    Namespace childSchemaNamespace = Namespace.of(metalake, catalog, "a:b:c");

    NameIdentifier childSchemaIdentifier = NameIdentifier.of(catalogNamespace, "a:b:c");
    NameIdentifier parentSchemaIdentifier = NameIdentifier.of(catalogNamespace, "a:b");
    NameIdentifier outerSchemaIdentifier = NameIdentifier.of(catalogNamespace, "a");
    List<NameIdentifier> schemaIdentifiers =
        List.of(childSchemaIdentifier, parentSchemaIdentifier, outerSchemaIdentifier);

    List<SchemaEntity> schemaEntities =
        List.of(
            buildSchemaEntity(30L, childSchemaIdentifier),
            buildSchemaEntity(20L, parentSchemaIdentifier),
            buildSchemaEntity(10L, outerSchemaIdentifier));
    CatalogEntity catalogEntity =
        CatalogEntity.builder()
            .withId(40L)
            .withName(catalog)
            .withNamespace(metalakeNamespace)
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withProperties(Collections.emptyMap())
            .withAuditInfo(
                AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
            .build();

    when(schemaDispatcher.listSchemas(parentSchemaNamespace))
        .thenReturn(new NameIdentifier[] {childSchemaIdentifier});
    when(schemaDispatcher.listEntities(catalogNamespace)).thenReturn(schemaEntities);
    when(schemaDispatcher.supportsHierarchicalSchema(catalogNamespace)).thenReturn(true);
    when(schemaDispatcher.listSchemas(childSchemaNamespace)).thenReturn(new NameIdentifier[0]);
    when(tableDispatcher.listTables(childSchemaNamespace)).thenReturn(new NameIdentifier[0]);
    when(functionDispatcher.listFunctionInfos(childSchemaNamespace)).thenReturn(new Function[0]);
    when(viewDispatcher.listViews(childSchemaNamespace)).thenReturn(new NameIdentifier[0]);

    when(entityStore.batchGet(
            List.of(NameIdentifier.of(metalakeNamespace, catalog)),
            Entity.EntityType.CATALOG,
            CatalogEntity.class))
        .thenReturn(List.of(catalogEntity));

    List<DatastratoTagRelPO> schemaTagRelations =
        List.of(
            mockTagRelation(30L, 301L, "child-tag", "child"),
            mockTagRelation(20L, 201L, "parent-tag", "parent"),
            mockTagRelation(20L, 999L, "shared-tag", "parent-shared"),
            mockTagRelation(10L, 101L, "outer-tag", "outer"),
            mockTagRelation(10L, 999L, "shared-tag", "outer-shared"));
    List<DatastratoTagRelPO> catalogTagRelations =
        List.of(
            mockTagRelation(40L, 401L, "catalog-tag", "catalog", MetadataObject.Type.CATALOG),
            mockTagRelation(
                40L, 999L, "shared-tag", "catalog-shared", MetadataObject.Type.CATALOG));
    List<Long> metadataObjectIds = List.of(30L, 20L, 10L, 40L);
    when(tagPolicyMapper.batchListTagRelPOsByMetadataObjectIds(metadataObjectIds))
        .thenReturn(
            ImmutableList.<DatastratoTagRelPO>builder()
                .addAll(schemaTagRelations)
                .addAll(catalogTagRelations)
                .build());

    List<DatastratoPolicyRelPO> schemaPolicyRelations =
        List.of(
            mockPolicyRelation(30L, 301L, "child-policy", "child"),
            mockPolicyRelation(20L, 201L, "parent-policy", "parent"),
            mockPolicyRelation(20L, 999L, "shared-policy", "parent-shared"),
            mockPolicyRelation(10L, 101L, "outer-policy", "outer"),
            mockPolicyRelation(10L, 999L, "shared-policy", "outer-shared"));
    List<DatastratoPolicyRelPO> catalogPolicyRelations =
        List.of(
            mockPolicyRelation(40L, 401L, "catalog-policy", "catalog", MetadataObject.Type.CATALOG),
            mockPolicyRelation(
                40L, 999L, "shared-policy", "catalog-shared", MetadataObject.Type.CATALOG));
    when(tagPolicyMapper.batchListPolicyRelPOsByMetadataObjectIds(metadataObjectIds))
        .thenReturn(
            ImmutableList.<DatastratoPolicyRelPO>builder()
                .addAll(schemaPolicyRelations)
                .addAll(catalogPolicyRelations)
                .build());

    Config oldConfig = GravitinoEnv.getInstance().config();
    Config config = mock(Config.class);
    when(config.get(ENABLE_AUTHORIZATION)).thenReturn(false);
    when(config.get(SCHEMA_SEPARATOR)).thenReturn(":");
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
      Response response =
          target("/web/entities")
              .queryParam("namespace", metalake + "." + catalog)
              .queryParam("catalogType", "relational")
              .queryParam("parentSchema", "a:b")
              .request(MediaType.APPLICATION_JSON_TYPE)
              .accept("application/vnd.gravitino.v1+json")
              .get();

      Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
      SchemaListResponse schemaResponse = response.readEntity(SchemaListResponse.class);
      Assertions.assertEquals(1, schemaResponse.getSchemas().length);
      ExtendedSchemaDTO childSchema = schemaResponse.getSchemas()[0];

      Map<String, TagDTO> tags =
          Arrays.stream(childSchema.getTags()).collect(Collectors.toMap(TagDTO::name, tag -> tag));
      Assertions.assertEquals(5, tags.size());
      Assertions.assertTrue(tags.get("catalog-tag").inherited().orElseThrow());
      Assertions.assertTrue(tags.get("outer-tag").inherited().orElseThrow());
      Assertions.assertTrue(tags.get("parent-tag").inherited().orElseThrow());
      Assertions.assertFalse(tags.get("child-tag").inherited().orElseThrow());
      Assertions.assertEquals("parent-shared", tags.get("shared-tag").comment());
      Assertions.assertTrue(tags.get("shared-tag").inherited().orElseThrow());

      Map<String, PolicyDTO> policies =
          Arrays.stream(childSchema.getPolicies())
              .collect(Collectors.toMap(PolicyDTO::name, policy -> policy));
      Assertions.assertEquals(5, policies.size());
      Assertions.assertTrue(policies.get("catalog-policy").inherited().orElseThrow());
      Assertions.assertTrue(policies.get("outer-policy").inherited().orElseThrow());
      Assertions.assertTrue(policies.get("parent-policy").inherited().orElseThrow());
      Assertions.assertFalse(policies.get("child-policy").inherited().orElseThrow());
      Assertions.assertEquals("parent-shared", policies.get("shared-policy").comment());
      Assertions.assertTrue(policies.get("shared-policy").inherited().orElseThrow());
      Mockito.verify(entityStore, Mockito.never())
          .batchGet(schemaIdentifiers, Entity.EntityType.SCHEMA, SchemaEntity.class);
      Mockito.verify(tagPolicyMapper).batchListTagRelPOsByMetadataObjectIds(metadataObjectIds);
      Mockito.verify(tagPolicyMapper).batchListPolicyRelPOsByMetadataObjectIds(metadataObjectIds);
    } finally {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
    }
  }

  @Test
  public void testTagAndPolicyAuthorizationIsDeduplicatedByName() throws Throwable {
    String metalake = "dedupMetalake";
    String catalog = "dedupCatalog";
    Namespace metalakeNamespace = Namespace.of(metalake);
    Namespace catalogNamespace = Namespace.of(metalake, catalog);
    NameIdentifier schema1Identifier = NameIdentifier.of(catalogNamespace, "schema1");
    NameIdentifier schema2Identifier = NameIdentifier.of(catalogNamespace, "schema2");
    List<SchemaEntity> schemaEntities =
        List.of(
            buildSchemaEntity(101L, schema1Identifier), buildSchemaEntity(102L, schema2Identifier));
    CatalogEntity catalogEntity =
        CatalogEntity.builder()
            .withId(200L)
            .withName(catalog)
            .withNamespace(metalakeNamespace)
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withProperties(Collections.emptyMap())
            .withAuditInfo(
                AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
            .build();

    when(schemaDispatcher.listSchemas(catalogNamespace))
        .thenReturn(new NameIdentifier[] {schema1Identifier, schema2Identifier});
    when(schemaDispatcher.listEntities(catalogNamespace)).thenReturn(schemaEntities);
    when(schemaDispatcher.supportsHierarchicalSchema(catalogNamespace)).thenReturn(false);
    for (String schema : List.of("schema1", "schema2")) {
      Namespace schemaNamespace = Namespace.of(metalake, catalog, schema);
      when(tableDispatcher.listTables(schemaNamespace)).thenReturn(new NameIdentifier[0]);
      when(functionDispatcher.listFunctionInfos(schemaNamespace)).thenReturn(new Function[0]);
      when(viewDispatcher.listViews(schemaNamespace)).thenReturn(new NameIdentifier[0]);
    }
    when(entityStore.batchGet(
            List.of(NameIdentifier.of(metalakeNamespace, catalog)),
            Entity.EntityType.CATALOG,
            CatalogEntity.class))
        .thenReturn(List.of(catalogEntity));
    List<DatastratoTagRelPO> tagRelations =
        List.of(
            mockTagRelation(101L, 501L, "shared-tag", "schema1"),
            mockTagRelation(102L, 501L, "shared-tag", "schema2"));
    List<Long> metadataObjectIds = List.of(101L, 102L, 200L);
    when(tagPolicyMapper.batchListTagRelPOsByMetadataObjectIds(metadataObjectIds))
        .thenReturn(tagRelations);
    List<DatastratoPolicyRelPO> policyRelations =
        List.of(
            mockPolicyRelation(101L, 601L, "shared-policy", "schema1"),
            mockPolicyRelation(102L, 601L, "shared-policy", "schema2"));
    when(tagPolicyMapper.batchListPolicyRelPOsByMetadataObjectIds(metadataObjectIds))
        .thenReturn(policyRelations);

    Config oldConfig = GravitinoEnv.getInstance().config();
    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);
    when(mockConfig.get(SCHEMA_SEPARATOR)).thenReturn(":");

    AtomicInteger tagOwnerChecks = new AtomicInteger();
    AtomicInteger policyOwnerChecks = new AtomicInteger();
    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq(metalake), any(), any(), any()))
        .thenReturn(true);
    lenient().when(mockAuthorizer.deny(any(), eq(metalake), any(), any(), any())).thenReturn(false);
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq(metalake), anySet(), any()))
        .thenReturn(false);
    lenient().when(mockAuthorizer.isMetalakeUser(eq(metalake), any())).thenReturn(true);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq(metalake), any(), any()))
        .thenAnswer(
            invocation -> {
              MetadataObject object = invocation.getArgument(2);
              if (object.type() == MetadataObject.Type.TAG) {
                tagOwnerChecks.incrementAndGet();
                return true;
              }
              if (object.type() == MetadataObject.Type.POLICY) {
                policyOwnerChecks.incrementAndGet();
                return true;
              }
              return false;
            });

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      oldMetadataAuthzExecutor = replaceMetadataAuthzExecutor(Runnable::run);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);
      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response response =
            target("/web/entities")
                .queryParam("namespace", metalake + "." + catalog)
                .queryParam("catalogType", "relational")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        SchemaListResponse schemaResponse = response.readEntity(SchemaListResponse.class);
        Assertions.assertEquals(2, schemaResponse.getSchemas().length);
        Arrays.stream(schemaResponse.getSchemas())
            .forEach(
                schema -> {
                  Assertions.assertEquals(1, schema.getTags().length);
                  Assertions.assertEquals("shared-tag", schema.getTags()[0].name());
                  Assertions.assertEquals(1, schema.getPolicies().length);
                  Assertions.assertEquals("shared-policy", schema.getPolicies()[0].name());
                });
        Assertions.assertEquals(1, tagOwnerChecks.get());
        Assertions.assertEquals(1, policyOwnerChecks.get());
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  @Test
  public void testListDirectChildCounts() {
    String metalake = "testMetalake";
    String catalog = "relCatalog";
    Namespace metalakeNs = Namespace.of(metalake);
    Namespace catalogNs = Namespace.of(metalake, catalog);
    Namespace schema1Ns = Namespace.of(metalake, catalog, "schema1");
    Namespace schema2Ns = Namespace.of(metalake, catalog, "schema2");

    TestCatalog testCatalog = buildCatalog(metalake, catalog);
    when(catalogDispatcher.listCatalogsInfo(metalakeNs)).thenReturn(new Catalog[] {testCatalog});

    NameIdentifier schema1 = NameIdentifier.of(catalogNs, "schema1");
    NameIdentifier schema2 = NameIdentifier.of(catalogNs, "schema2");
    NameIdentifier[] schemas = new NameIdentifier[] {schema1, schema2};
    when(schemaDispatcher.listSchemas(catalogNs)).thenReturn(schemas);
    when(schemaDispatcher.listEntities(catalogNs)).thenReturn(buildSchemaEntity(schemas));
    when(schemaDispatcher.supportsHierarchicalSchema(catalogNs)).thenReturn(true);

    NameIdentifier childSchema = NameIdentifier.of(catalogNs, "schema1:child");
    when(schemaDispatcher.listSchemas(schema1Ns)).thenReturn(new NameIdentifier[] {childSchema});
    when(schemaDispatcher.listSchemas(schema2Ns)).thenReturn(new NameIdentifier[0]);

    NameIdentifier table1 = NameIdentifier.of(schema1Ns, "table1");
    NameIdentifier table2 = NameIdentifier.of(schema1Ns, "table2");
    when(tableDispatcher.listTables(schema1Ns)).thenReturn(new NameIdentifier[] {table1, table2});
    when(tableDispatcher.listTables(schema2Ns)).thenReturn(new NameIdentifier[0]);
    when(functionDispatcher.listFunctionInfos(schema1Ns)).thenReturn(buildFunctionInfos(schema1Ns));
    when(functionDispatcher.listFunctionInfos(schema2Ns))
        .thenThrow(new UnsupportedOperationException("Functions are not supported"));

    NameIdentifier view = NameIdentifier.of(schema1Ns, "view1");
    when(viewDispatcher.listViews(schema1Ns)).thenReturn(new NameIdentifier[] {view});
    when(viewDispatcher.listViews(schema2Ns))
        .thenThrow(new UnsupportedOperationException("Views are not supported"));

    Response catalogResponse =
        target("/web/entities")
            .queryParam("namespace", metalake)
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), catalogResponse.getStatus());
    CatalogListResponse catalogListResponse = catalogResponse.readEntity(CatalogListResponse.class);
    Assertions.assertEquals(2L, catalogListResponse.getCatalogs()[0].getDirectChildCounts());

    Response schemaResponse =
        target("/web/entities")
            .queryParam("namespace", metalake + "." + catalog)
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), schemaResponse.getStatus());
    SchemaListResponse schemaListResponse = schemaResponse.readEntity(SchemaListResponse.class);
    Assertions.assertEquals(5L, schemaListResponse.getSchemas()[0].getDirectChildCounts());
    Assertions.assertEquals(0L, schemaListResponse.getSchemas()[1].getDirectChildCounts());

    Response limitedSchemaResponse =
        target("/web/entities")
            .queryParam("namespace", metalake + "." + catalog)
            .queryParam("catalogType", "relational")
            .queryParam("resultLimit", 1)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), limitedSchemaResponse.getStatus());
    SchemaListResponse limitedSchemaListResponse =
        limitedSchemaResponse.readEntity(SchemaListResponse.class);
    Assertions.assertEquals(1, limitedSchemaListResponse.getSchemas().length);
    Assertions.assertEquals(5L, limitedSchemaListResponse.getSchemas()[0].getDirectChildCounts());
  }

  @Test
  public void testListCatalogDirectChildCountUnavailable() {
    String metalake = "testMetalake";
    String catalog = "relCatalog";
    Namespace metalakeNamespace = Namespace.of(metalake);
    Namespace catalogNamespace = Namespace.of(metalake, catalog);
    when(catalogDispatcher.listCatalogsInfo(metalakeNamespace))
        .thenReturn(new Catalog[] {buildCatalog(metalake, catalog)});
    when(schemaDispatcher.listSchemas(catalogNamespace))
        .thenThrow(new IllegalStateException("Schema count is unavailable"));

    Response response =
        target("/web/entities")
            .queryParam("namespace", metalake)
            .queryParam("catalogType", "relational")
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    CatalogListResponse catalogResponse = response.readEntity(CatalogListResponse.class);
    Assertions.assertNull(catalogResponse.getCatalogs()[0].getDirectChildCounts());
  }

  @Test
  public void testListSchemaDirectChildCountsByCatalogType() {
    String metalake = "testMetalake";

    Namespace messagingCatalogNs = Namespace.of(metalake, "messagingCatalog");
    Namespace messagingSchemaNs = mockSchemaListing(messagingCatalogNs, "messagingSchema");
    when(topicDispatcher.listTopics(messagingSchemaNs))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of(messagingSchemaNs, "topic1"),
              NameIdentifier.of(messagingSchemaNs, "topic2")
            });
    when(functionDispatcher.listFunctionInfos(messagingSchemaNs))
        .thenReturn(buildFunctionInfos(messagingSchemaNs));
    Assertions.assertEquals(
        3L, listSchemas(messagingCatalogNs, "messaging").getSchemas()[0].getDirectChildCounts());

    Namespace filesetCatalogNs = Namespace.of(metalake, "filesetCatalog");
    Namespace filesetSchemaNs = mockSchemaListing(filesetCatalogNs, "filesetSchema");
    when(filesetDispatcher.listEntities(filesetSchemaNs))
        .thenReturn(
            buildFilesetEntity(
                new NameIdentifier[] {
                  NameIdentifier.of(filesetSchemaNs, "fileset1"),
                  NameIdentifier.of(filesetSchemaNs, "fileset2")
                }));
    when(functionDispatcher.listFunctionInfos(filesetSchemaNs))
        .thenReturn(buildFunctionInfos(filesetSchemaNs));
    Assertions.assertEquals(
        3L, listSchemas(filesetCatalogNs, "fileset").getSchemas()[0].getDirectChildCounts());

    Namespace modelCatalogNs = Namespace.of(metalake, "modelCatalog");
    Namespace modelSchemaNs = mockSchemaListing(modelCatalogNs, "modelSchema");
    when(modelDispatcher.listEntities(modelSchemaNs))
        .thenReturn(
            buildModelEntity(
                new NameIdentifier[] {
                  NameIdentifier.of(modelSchemaNs, "model1"),
                  NameIdentifier.of(modelSchemaNs, "model2")
                }));
    when(functionDispatcher.listFunctionInfos(modelSchemaNs))
        .thenReturn(buildFunctionInfos(modelSchemaNs));
    Assertions.assertEquals(
        3L, listSchemas(modelCatalogNs, "model").getSchemas()[0].getDirectChildCounts());

    Mockito.verify(schemaDispatcher, Mockito.never()).listSchemas(messagingSchemaNs);
    Mockito.verify(schemaDispatcher, Mockito.never()).listSchemas(filesetSchemaNs);
    Mockito.verify(schemaDispatcher, Mockito.never()).listSchemas(modelSchemaNs);
  }

  @Test
  public void testListSchemaDirectChildCountUnavailable() {
    Namespace catalogNamespace = Namespace.of("testMetalake", "relCatalog");
    Namespace schemaNamespace = mockSchemaListing(catalogNamespace, "relSchema");
    when(schemaDispatcher.supportsHierarchicalSchema(catalogNamespace))
        .thenThrow(new IllegalStateException("Capability is unavailable"));

    SchemaListResponse response = listSchemas(catalogNamespace, "relational");

    Assertions.assertNull(response.getSchemas()[0].getDirectChildCounts());
    Mockito.verify(tableDispatcher, Mockito.never()).listTables(schemaNamespace);
  }

  @Test
  public void testListSchemaDirectChildCountOmitsFailedFunctionAndViewCounts() {
    Namespace catalogNamespace = Namespace.of("testMetalake", "relCatalog");
    NameIdentifier functionFailureSchema =
        NameIdentifier.of(catalogNamespace, "functionFailureSchema");
    NameIdentifier viewFailureSchema = NameIdentifier.of(catalogNamespace, "viewFailureSchema");
    NameIdentifier[] schemas = {functionFailureSchema, viewFailureSchema};
    when(schemaDispatcher.listSchemas(catalogNamespace)).thenReturn(schemas);
    when(schemaDispatcher.listEntities(catalogNamespace)).thenReturn(buildSchemaEntity(schemas));
    when(schemaDispatcher.supportsHierarchicalSchema(catalogNamespace)).thenReturn(false);

    Namespace functionFailureNamespace =
        Namespace.of(
            catalogNamespace.level(0), catalogNamespace.level(1), functionFailureSchema.name());
    Namespace viewFailureNamespace =
        Namespace.of(
            catalogNamespace.level(0), catalogNamespace.level(1), viewFailureSchema.name());
    when(tableDispatcher.listTables(functionFailureNamespace)).thenReturn(new NameIdentifier[0]);
    when(tableDispatcher.listTables(viewFailureNamespace)).thenReturn(new NameIdentifier[0]);
    when(functionDispatcher.listFunctionInfos(functionFailureNamespace))
        .thenThrow(new IllegalStateException("Function count is unavailable"));
    when(viewDispatcher.listViews(functionFailureNamespace)).thenReturn(new NameIdentifier[0]);
    when(functionDispatcher.listFunctionInfos(viewFailureNamespace)).thenReturn(new Function[0]);
    when(viewDispatcher.listViews(viewFailureNamespace))
        .thenThrow(new IllegalStateException("View count is unavailable"));

    SchemaListResponse response = listSchemas(catalogNamespace, "relational");

    Assertions.assertEquals(2, response.getSchemas().length);
    Assertions.assertNull(response.getSchemas()[0].getDirectChildCounts());
    Assertions.assertNull(response.getSchemas()[1].getDirectChildCounts());
  }

  @Test
  public void testListCatalogsWithAuthorizationFilter() throws Throwable {
    Config oldConfig = GravitinoEnv.getInstance().config();
    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);

    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq("testMetalake"), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Object metadataObject = invocation.getArgument(2);
              if (!(metadataObject instanceof MetadataObject)) {
                return false;
              }
              MetadataObject object = (MetadataObject) metadataObject;
              Privilege.Name privilege = invocation.getArgument(3);
              return (object.type() == MetadataObject.Type.CATALOG
                      && "relCatalog1".equals(object.name())
                      && privilege == Privilege.Name.USE_CATALOG)
                  || (object.type() == MetadataObject.Type.SCHEMA
                      && "schema1".equals(object.name())
                      && privilege == Privilege.Name.USE_SCHEMA);
            });
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq("testMetalake"), anySet(), any()))
        .thenReturn(true);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq("testMetalake"), any(), any()))
        .thenReturn(false);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("testMetalake"), any())).thenReturn(true);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("hiddenMetalake"), any())).thenReturn(false);

    BaseMetalake visibleMetalake = buildMetalake(1L, "testMetalake");
    BaseMetalake hiddenMetalake = buildMetalake(2L, "hiddenMetalake");
    when(metalakeDispatcher.listMetalakes())
        .thenReturn(new Metalake[] {hiddenMetalake, visibleMetalake});
    TestCatalog catalog1 = buildCatalog("testMetalake", "relCatalog1");
    TestCatalog catalog2 = buildCatalog("testMetalake", "relCatalog2");
    TestCatalog[] mockedCatalogs = new TestCatalog[] {catalog1, catalog2};
    Namespace metalakeNamespace = Namespace.of("testMetalake");
    when(catalogDispatcher.listCatalogsInfo(metalakeNamespace)).thenReturn(mockedCatalogs);
    when(catalogDispatcher.listCatalogs(metalakeNamespace))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of(metalakeNamespace, "relCatalog1"),
              NameIdentifier.of(metalakeNamespace, "relCatalog2")
            });
    Namespace visibleCatalogNs = Namespace.of("testMetalake", "relCatalog1");
    when(schemaDispatcher.listSchemas(visibleCatalogNs))
        .thenReturn(
            new NameIdentifier[] {
              NameIdentifier.of(visibleCatalogNs, "schema1"),
              NameIdentifier.of(visibleCatalogNs, "schema2")
            });

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      oldMetadataAuthzExecutor = replaceMetadataAuthzExecutor(Runnable::run);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);
      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response metalakeResponse =
            target("/web/entities")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), metalakeResponse.getStatus());
        MetalakeListResponse metalakeListResponse =
            metalakeResponse.readEntity(MetalakeListResponse.class);
        ExtendedMetalakeDTO[] metalakeDTOs = metalakeListResponse.getMetalakes();
        Assertions.assertEquals(1, metalakeDTOs.length);
        Assertions.assertEquals("testMetalake", metalakeDTOs[0].name());
        Assertions.assertEquals(1L, metalakeDTOs[0].getDirectChildCounts());

        Response resp =
            target("/web/entities")
                .queryParam("namespace", "testMetalake")
                .queryParam("catalogType", "relational")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
        CatalogListResponse catalogResponse = resp.readEntity(CatalogListResponse.class);
        Assertions.assertEquals(0, catalogResponse.getCode());
        ExtendedCatalogDTO[] catalogDTOs = catalogResponse.getCatalogs();
        Assertions.assertEquals(1, catalogDTOs.length);
        Assertions.assertEquals("relCatalog1", catalogDTOs[0].name());
        Assertions.assertEquals(1L, catalogDTOs[0].getDirectChildCounts());
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  @Test
  public void testListSchemasWithAuthorizationFilter() throws Throwable {
    Config oldConfig = GravitinoEnv.getInstance().config();
    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);

    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq("testMetalake"), any(), any(), any()))
        .thenReturn(false);
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq("testMetalake"), anySet(), any()))
        .thenReturn(false);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("testMetalake"), any())).thenReturn(true);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq("testMetalake"), any(), any()))
        .thenAnswer(
            invocation -> {
              Object metadataObject = invocation.getArgument(2);
              if (!(metadataObject instanceof MetadataObject)) {
                return false;
              }
              MetadataObject object = (MetadataObject) metadataObject;
              return object.type() == MetadataObject.Type.SCHEMA
                  && "relSchema1".equals(object.name());
            });

    Namespace namespace = Namespace.of("testMetalake", "relCatalog");
    NameIdentifier schemaIdent1 = NameIdentifier.of(namespace, "relSchema1");
    NameIdentifier schemaIdent2 = NameIdentifier.of(namespace, "relSchema2");
    NameIdentifier[] schemaIdents = {schemaIdent1, schemaIdent2};
    when(schemaDispatcher.listSchemas(namespace)).thenReturn(schemaIdents);
    when(schemaDispatcher.listEntities(namespace)).thenReturn(buildSchemaEntity(schemaIdents));
    Namespace visibleSchemaNs = Namespace.of("testMetalake", "relCatalog", "relSchema1");
    when(schemaDispatcher.listSchemas(visibleSchemaNs)).thenReturn(new NameIdentifier[0]);
    when(tableDispatcher.listTables(visibleSchemaNs))
        .thenReturn(new NameIdentifier[] {NameIdentifier.of(visibleSchemaNs, "visibleTable")});
    when(functionDispatcher.listFunctionInfos(visibleSchemaNs)).thenReturn(new Function[0]);
    when(viewDispatcher.listViews(visibleSchemaNs)).thenReturn(new NameIdentifier[0]);

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      oldMetadataAuthzExecutor = replaceMetadataAuthzExecutor(Runnable::run);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);
      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response resp =
            target("/web/entities")
                .queryParam("namespace", "testMetalake.relCatalog")
                .queryParam("catalogType", "relational")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
        SchemaListResponse schemaResp = resp.readEntity(SchemaListResponse.class);
        Assertions.assertEquals(0, schemaResp.getCode());
        Assertions.assertEquals(1, schemaResp.getSchemas().length);
        Assertions.assertEquals("relSchema1", schemaResp.getSchemas()[0].name());
        Assertions.assertEquals(1L, schemaResp.getSchemas()[0].getDirectChildCounts());
        Mockito.verify(schemaDispatcher, Mockito.never()).listSchemas(visibleSchemaNs);
        Mockito.verify(tableDispatcher, Mockito.never())
            .listTables(Namespace.of("testMetalake", "relCatalog", "relSchema2"));
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  @Test
  public void testListCatalogsSkipAuthorizationFilterWhenMetadataAuthzNotReady() throws Throwable {
    Config oldConfig = GravitinoEnv.getInstance().config();
    Object oldAccessControlDispatcher;
    try {
      oldAccessControlDispatcher =
          FieldUtils.readField(GravitinoEnv.getInstance(), "accessControlDispatcher", true);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(
          "Failed to read GravitinoEnv accessControlDispatcher before test", e);
    }

    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);

    TestCatalog catalog1 = buildCatalog("testMetalake", "relCatalog1");
    TestCatalog catalog2 = buildCatalog("testMetalake", "relCatalog2");
    Catalog[] mockedCatalogs = {catalog1, catalog2};
    when(catalogDispatcher.listCatalogsInfo(Namespace.of("testMetalake")))
        .thenReturn(mockedCatalogs);
    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("testMetalake"), any())).thenReturn(true);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq("testMetalake"), any(), any(), any()))
        .thenReturn(false);
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq("testMetalake"), anySet(), any()))
        .thenReturn(false);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq("testMetalake"), any(), any()))
        .thenReturn(false);

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();

    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      FieldUtils.writeField(GravitinoEnv.getInstance(), "accessControlDispatcher", null, true);
      oldMetadataAuthzExecutor =
          replaceMetadataAuthzExecutor(
              command -> {
                throw new IllegalArgumentException("Metadata authorization executor is not ready");
              });
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);

      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response resp =
            target("/web/entities")
                .queryParam("namespace", "testMetalake")
                .queryParam("catalogType", "relational")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
        CatalogListResponse catalogResponse = resp.readEntity(CatalogListResponse.class);
        Assertions.assertEquals(0, catalogResponse.getCode());
        ExtendedCatalogDTO[] catalogDTOs = catalogResponse.getCatalogs();
        Assertions.assertEquals(2, catalogDTOs.length);
        assertCatalogs(catalogDTOs);
        // Upstream list short-circuit now consults the authorizer for a parent-scope grant before
        // falling through to the per-object path. With access-control uninitialized that attempt
        // fails and MetadataListingHelper still returns the unfiltered list; authorize may have
        // been invoked once for the short-circuit probe.
        Mockito.verify(mockAuthorizer, Mockito.atMostOnce())
            .authorize(any(), eq("testMetalake"), any(), any(), any());
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(
            GravitinoEnv.getInstance(),
            "accessControlDispatcher",
            oldAccessControlDispatcher,
            true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  @Test
  public void testListTablesWithAuthorizationFilter() throws Throwable {
    Config oldConfig = GravitinoEnv.getInstance().config();
    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);

    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq("testMetalake"), any(), any(), any()))
        .thenReturn(false);
    lenient()
        .when(mockAuthorizer.deny(any(), eq("testMetalake"), any(), any(), any()))
        .thenReturn(false);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("testMetalake"), any())).thenReturn(true);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq("testMetalake"), any(), any()))
        .thenAnswer(
            invocation -> {
              Object metadataObject = invocation.getArgument(2);
              if (!(metadataObject instanceof MetadataObject)) {
                return false;
              }
              MetadataObject object = (MetadataObject) metadataObject;
              return (object.type() == MetadataObject.Type.TABLE && "table1".equals(object.name()))
                  || (object.type() == MetadataObject.Type.FUNCTION
                      && "testFunction2".equals(object.name()))
                  || (object.type() == MetadataObject.Type.VIEW
                      && "testView2".equals(object.name()));
            });
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq("testMetalake"), anySet(), any()))
        .thenReturn(false);

    Namespace namespace = Namespace.of("testMetalake", "relCatalog", "relSchema");
    NameIdentifier tableIdent1 = NameIdentifier.of(namespace, "table1");
    NameIdentifier tableIdent2 = NameIdentifier.of(namespace, "table2");
    when(tableDispatcher.listTables(namespace))
        .thenReturn(new NameIdentifier[] {tableIdent1, tableIdent2});
    when(tableDispatcher.listEntities(namespace))
        .thenReturn(buildTableEntity(new NameIdentifier[] {tableIdent1, tableIdent2}));
    when(functionDispatcher.listFunctionInfos(namespace))
        .thenReturn(buildFunctionInfos(namespace, "testFunction1", "testFunction2"));
    NameIdentifier viewIdent1 = NameIdentifier.of(namespace, "testView1");
    NameIdentifier viewIdent2 = NameIdentifier.of(namespace, "testView2");
    when(viewDispatcher.listViews(namespace))
        .thenReturn(new NameIdentifier[] {viewIdent1, viewIdent2});
    when(viewDispatcher.listEntities(namespace))
        .thenReturn(buildViewEntity(new NameIdentifier[] {viewIdent1, viewIdent2}));

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      oldMetadataAuthzExecutor = replaceMetadataAuthzExecutor(Runnable::run);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);
      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response resp =
            target("/web/entities")
                .queryParam("namespace", "testMetalake.relCatalog.relSchema")
                .queryParam("catalogType", "relational")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
        TableListResponse tableResp = resp.readEntity(TableListResponse.class);
        Assertions.assertEquals(0, tableResp.getCode());
        Assertions.assertEquals(1, tableResp.getTables().length);
        Assertions.assertEquals("table1", tableResp.getTables()[0].name());
        Assertions.assertEquals(1, tableResp.getFunctions().length);
        Assertions.assertEquals("testFunction2", tableResp.getFunctions()[0].name());
        Assertions.assertEquals(1, tableResp.getViews().length);
        Assertions.assertEquals("testView2", tableResp.getViews()[0].name());
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  @Test
  public void testListTopicsWithAuthorizationFilter() throws Throwable {
    Config oldConfig = GravitinoEnv.getInstance().config();
    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);

    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq("testMetalake"), any(), any(), any()))
        .thenReturn(false);
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq("testMetalake"), anySet(), any()))
        .thenReturn(false);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("testMetalake"), any())).thenReturn(true);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq("testMetalake"), any(), any()))
        .thenAnswer(
            invocation -> {
              Object metadataObject = invocation.getArgument(2);
              if (!(metadataObject instanceof MetadataObject)) {
                return false;
              }
              MetadataObject object = (MetadataObject) metadataObject;
              return (object.type() == MetadataObject.Type.TOPIC && "topic1".equals(object.name()))
                  || (object.type() == MetadataObject.Type.FUNCTION
                      && "testFunction2".equals(object.name()));
            });

    Namespace namespace = Namespace.of("testMetalake", "messagingCatalog", "messagingSchema");
    NameIdentifier topicIdent1 = NameIdentifier.of(namespace, "topic1");
    NameIdentifier topicIdent2 = NameIdentifier.of(namespace, "topic2");
    when(topicDispatcher.listTopics(namespace))
        .thenReturn(new NameIdentifier[] {topicIdent1, topicIdent2});
    when(topicDispatcher.listEntities(namespace))
        .thenReturn(buildTopicEntity(new NameIdentifier[] {topicIdent1, topicIdent2}));
    when(functionDispatcher.listFunctionInfos(namespace))
        .thenReturn(buildFunctionInfos(namespace, "testFunction1", "testFunction2"));

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      oldMetadataAuthzExecutor = replaceMetadataAuthzExecutor(Runnable::run);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);
      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response resp =
            target("/web/entities")
                .queryParam("namespace", "testMetalake.messagingCatalog.messagingSchema")
                .queryParam("catalogType", "messaging")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
        TopicListResponse topicResp = resp.readEntity(TopicListResponse.class);
        Assertions.assertEquals(0, topicResp.getCode());
        Assertions.assertEquals(1, topicResp.getTopics().length);
        Assertions.assertEquals("topic1", topicResp.getTopics()[0].name());
        Assertions.assertEquals(1, topicResp.getFunctions().length);
        Assertions.assertEquals("testFunction2", topicResp.getFunctions()[0].name());
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  @Test
  public void testListFilesetsWithAuthorizationFilter() throws Throwable {
    Config oldConfig = GravitinoEnv.getInstance().config();
    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);

    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq("testMetalake"), any(), any(), any()))
        .thenReturn(false);
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq("testMetalake"), anySet(), any()))
        .thenReturn(false);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("testMetalake"), any())).thenReturn(true);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq("testMetalake"), any(), any()))
        .thenAnswer(
            invocation -> {
              Object metadataObject = invocation.getArgument(2);
              if (!(metadataObject instanceof MetadataObject)) {
                return false;
              }
              MetadataObject object = (MetadataObject) metadataObject;
              return (object.type() == MetadataObject.Type.FILESET
                      && "fileset1".equals(object.name()))
                  || (object.type() == MetadataObject.Type.FUNCTION
                      && "testFunction2".equals(object.name()));
            });

    Namespace namespace = Namespace.of("testMetalake", "filesetCatalog", "filesetSchema");
    NameIdentifier filesetIdent1 = NameIdentifier.of(namespace, "fileset1");
    NameIdentifier filesetIdent2 = NameIdentifier.of(namespace, "fileset2");
    when(filesetDispatcher.listEntities(namespace))
        .thenReturn(buildFilesetEntity(new NameIdentifier[] {filesetIdent1, filesetIdent2}));
    when(functionDispatcher.listFunctionInfos(namespace))
        .thenReturn(buildFunctionInfos(namespace, "testFunction1", "testFunction2"));

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      oldMetadataAuthzExecutor = replaceMetadataAuthzExecutor(Runnable::run);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);
      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response resp =
            target("/web/entities")
                .queryParam("namespace", "testMetalake.filesetCatalog.filesetSchema")
                .queryParam("catalogType", "fileset")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
        FilesetListResponse filesetResp = resp.readEntity(FilesetListResponse.class);
        Assertions.assertEquals(0, filesetResp.getCode());
        Assertions.assertEquals(1, filesetResp.getFilesets().length);
        Assertions.assertEquals("fileset1", filesetResp.getFilesets()[0].name());
        Assertions.assertEquals(1, filesetResp.getFunctions().length);
        Assertions.assertEquals("testFunction2", filesetResp.getFunctions()[0].name());
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  @Test
  public void testListModelsWithAuthorizationFilter() throws Throwable {
    Config oldConfig = GravitinoEnv.getInstance().config();
    Config mockConfig = mock(Config.class);
    when(mockConfig.get(ENABLE_AUTHORIZATION)).thenReturn(true);
    when(mockConfig.get(CACHE_ENABLED)).thenReturn(false);
    when(mockConfig.get(GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE)).thenReturn(2);

    GravitinoAuthorizer mockAuthorizer = mock(GravitinoAuthorizer.class);
    lenient()
        .when(mockAuthorizer.authorize(any(), eq("testMetalake"), any(), any(), any()))
        .thenReturn(false);
    lenient()
        .when(mockAuthorizer.hasDenyPolicy(any(), eq("testMetalake"), anySet(), any()))
        .thenReturn(false);
    lenient().when(mockAuthorizer.isMetalakeUser(eq("testMetalake"), any())).thenReturn(true);
    lenient()
        .when(mockAuthorizer.isOwner(any(), eq("testMetalake"), any(), any()))
        .thenAnswer(
            invocation -> {
              Object metadataObject = invocation.getArgument(2);
              if (!(metadataObject instanceof MetadataObject)) {
                return false;
              }
              MetadataObject object = (MetadataObject) metadataObject;
              return (object.type() == MetadataObject.Type.MODEL && "model1".equals(object.name()))
                  || (object.type() == MetadataObject.Type.FUNCTION
                      && "testFunction2".equals(object.name()));
            });

    Namespace namespace = Namespace.of("testMetalake", "modelCatalog", "modelSchema");
    NameIdentifier modelIdent1 = NameIdentifier.of(namespace, "model1");
    NameIdentifier modelIdent2 = NameIdentifier.of(namespace, "model2");
    when(modelDispatcher.listEntities(namespace))
        .thenReturn(buildModelEntity(new NameIdentifier[] {modelIdent1, modelIdent2}));
    when(functionDispatcher.listFunctionInfos(namespace))
        .thenReturn(buildFunctionInfos(namespace, "testFunction1", "testFunction2"));

    GravitinoAuthorizer oldGravitinoAuthorizer = GravitinoEnv.getInstance().gravitinoAuthorizer();
    GravitinoAuthorizer oldProviderAuthorizer = null;
    Executor oldMetadataAuthzExecutor = null;
    TestFailureTracker failureTracker = new TestFailureTracker();
    try {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "config", mockConfig, true);
      oldMetadataAuthzExecutor = replaceMetadataAuthzExecutor(Runnable::run);
      GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
      oldProviderAuthorizer =
          (GravitinoAuthorizer)
              FieldUtils.readField(authorizerProvider, "gravitinoAuthorizer", true);
      GravitinoEnv.getInstance().setGravitinoAuthorizer(mockAuthorizer);
      FieldUtils.writeField(authorizerProvider, "gravitinoAuthorizer", mockAuthorizer, true);
      try (MockedStatic<PrincipalUtils> principalUtilsStatic = mockStatic(PrincipalUtils.class)) {
        principalUtilsStatic
            .when(PrincipalUtils::getCurrentPrincipal)
            .thenReturn(new UserPrincipal("tester"));
        principalUtilsStatic.when(() -> PrincipalUtils.doAs(any(), any())).thenCallRealMethod();

        Response resp =
            target("/web/entities")
                .queryParam("namespace", "testMetalake.modelCatalog.modelSchema")
                .queryParam("catalogType", "model")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .accept("application/vnd.gravitino.v1+json")
                .get();

        Assertions.assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON_TYPE, resp.getMediaType());
        ModelListResponse modelResp = resp.readEntity(ModelListResponse.class);
        Assertions.assertEquals(0, modelResp.getCode());
        Assertions.assertEquals(1, modelResp.getModels().length);
        Assertions.assertEquals("model1", modelResp.getModels()[0].name());
        Assertions.assertEquals(1, modelResp.getFunctions().length);
        Assertions.assertEquals("testFunction2", modelResp.getFunctions()[0].name());
      }
    } catch (Throwable failure) {
      failureTracker.record(failure);
      throw failure;
    } finally {
      try {
        restoreMetadataAuthzExecutor(oldMetadataAuthzExecutor);
        GravitinoEnv.getInstance().setGravitinoAuthorizer(oldGravitinoAuthorizer);
        GravitinoAuthorizerProvider authorizerProvider = GravitinoAuthorizerProvider.getInstance();
        FieldUtils.writeField(
            authorizerProvider, "gravitinoAuthorizer", oldProviderAuthorizer, true);
        FieldUtils.writeField(GravitinoEnv.getInstance(), "config", oldConfig, true);
      } catch (IllegalAccessException restoreFailure) {
        failureTracker.handleRestoreFailure(restoreFailure);
      }
    }
  }

  private void assertViews(ExtendedViewDTO[] views) {
    ViewDTO viewDTO = views[0].getViewDTO();
    Assertions.assertEquals("testView", views[0].name());
    Assertions.assertNull(viewDTO.comment());
    Assertions.assertTrue(viewDTO.properties().isEmpty());
    Assertions.assertEquals("creator", viewDTO.auditInfo().creator());
    Assertions.assertEquals(1, viewDTO.representations().length);
    Assertions.assertInstanceOf(SQLRepresentation.class, viewDTO.representations()[0]);
    SQLRepresentation representation = (SQLRepresentation) viewDTO.representations()[0];
    Assertions.assertEquals("unavailable", representation.dialect());
    Assertions.assertEquals("UNAVAILABLE", representation.sql());
  }

  private void assertModels(ExtendedModelDTO[] models) {
    ModelDTO modelDTO = models[0].getModelDTO();
    Assertions.assertEquals("model", models[0].name());
    Assertions.assertEquals("comment", modelDTO.comment());
    Assertions.assertEquals(1, modelDTO.latestVersion());
    Assertions.assertEquals("creator", modelDTO.auditInfo().creator());
    Assertions.assertEquals("value", modelDTO.properties().get("key"));
  }

  private void assertFunctions(ExtendedFunctionDTO[] functions) {
    FunctionDTO functionDTO = functions[0].getFunctionDTO();
    Assertions.assertEquals("testFunction", functions[0].name());
    Assertions.assertEquals(FunctionType.SCALAR, functionDTO.functionType());
    Assertions.assertTrue(functionDTO.deterministic());
    Assertions.assertEquals("test function comment", functionDTO.comment());
    Assertions.assertEquals("creator", functionDTO.auditInfo().creator());
  }

  private void assertFilesets(ExtendedFilesetDTO[] filesets) {
    FilesetDTO filesetDTO = filesets[0].getFilesetDTO();
    Assertions.assertEquals("fileset", filesets[0].name());
    Assertions.assertEquals("comment", filesetDTO.comment());
    Assertions.assertEquals("location", filesetDTO.storageLocation());
    Assertions.assertEquals(MANAGED, filesetDTO.type());
    Assertions.assertEquals("value", filesetDTO.properties().get("key"));
    Assertions.assertEquals("creator", filesetDTO.auditInfo().creator());
  }

  private void assertTopics(ExtendedTopicDTO[] topics) {
    TopicDTO topicDTO = topics[0].getTopicDTO();
    Assertions.assertEquals("messagingTopic", topics[0].name());
    Assertions.assertEquals("comment", topicDTO.comment());
    Assertions.assertNull(topicDTO.properties());
    Assertions.assertEquals("creator", topicDTO.auditInfo().creator());
  }

  private Executor replaceMetadataAuthzExecutor(Executor replacement)
      throws IllegalAccessException {
    Executor oldExecutor =
        (Executor) FieldUtils.readStaticField(MetadataAuthzHelper.class, "executor", true);
    FieldUtils.writeStaticField(MetadataAuthzHelper.class, "executor", replacement, true);
    return oldExecutor;
  }

  private void restoreMetadataAuthzExecutor(Executor executor) throws IllegalAccessException {
    FieldUtils.writeStaticField(MetadataAuthzHelper.class, "executor", executor, true);
  }

  private Namespace mockSchemaListing(Namespace catalogNamespace, String schemaName) {
    NameIdentifier schemaIdentifier = NameIdentifier.of(catalogNamespace, schemaName);
    when(schemaDispatcher.listSchemas(catalogNamespace))
        .thenReturn(new NameIdentifier[] {schemaIdentifier});
    when(schemaDispatcher.listEntities(catalogNamespace))
        .thenReturn(buildSchemaEntity(new NameIdentifier[] {schemaIdentifier}));
    return Namespace.of(catalogNamespace.level(0), catalogNamespace.level(1), schemaName);
  }

  private SchemaListResponse listSchemas(Namespace catalogNamespace, String catalogType) {
    Response response =
        target("/web/entities")
            .queryParam("namespace", catalogNamespace.level(0) + "." + catalogNamespace.level(1))
            .queryParam("catalogType", catalogType)
            .request(MediaType.APPLICATION_JSON_TYPE)
            .accept("application/vnd.gravitino.v1+json")
            .get();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    return response.readEntity(SchemaListResponse.class);
  }

  private void assertTables(ExtendedTableDTO[] tableDTOs) {
    TableDTO tableDTO = tableDTOs[0].getTableDTO();
    Assertions.assertEquals("relTable", tableDTOs[0].name());
    Assertions.assertNull(tableDTO.comment());
    Assertions.assertNull(tableDTO.properties());
    Assertions.assertEquals("creator", tableDTO.auditInfo().creator());
  }

  private void assertSchemas(ExtendedSchemaDTO[] schemaDTOs) {
    SchemaDTO schemaDTO = schemaDTOs[0].getSchemaDTO();
    Assertions.assertEquals("relSchema", schemaDTOs[0].name());
    Assertions.assertNull(schemaDTO.comment());
    Assertions.assertNull(schemaDTO.properties());
    Assertions.assertEquals("creator", schemaDTO.auditInfo().creator());
  }

  private void assertCatalogs(ExtendedCatalogDTO[] catalogDTOs) {
    ExtendedCatalogDTO catalogDTO1 = catalogDTOs[0];
    Assertions.assertEquals("relCatalog1", catalogDTO1.name());
    Assertions.assertEquals(Catalog.Type.RELATIONAL, catalogDTO1.getCatalogDTO().type());
    Assertions.assertEquals("comment", catalogDTO1.getCatalogDTO().comment());
    Assertions.assertEquals(
        ImmutableMap.of("key", "value", "in-use", "true"),
        catalogDTO1.getCatalogDTO().properties());
    Assertions.assertEquals("creator", catalogDTO1.getCatalogDTO().auditInfo().creator());

    ExtendedCatalogDTO catalogDTO2 = catalogDTOs[1];
    Assertions.assertEquals("relCatalog2", catalogDTO2.name());
    Assertions.assertEquals(Catalog.Type.RELATIONAL, catalogDTO2.getCatalogDTO().type());
    Assertions.assertEquals("comment", catalogDTO2.getCatalogDTO().comment());
    Assertions.assertEquals(
        ImmutableMap.of("key", "value", "in-use", "true"),
        catalogDTO2.getCatalogDTO().properties());
    Assertions.assertEquals("creator", catalogDTO2.getCatalogDTO().auditInfo().creator());
  }

  private Function[] buildFunctionInfos(Namespace namespace) {
    return new Function[] {
      FunctionEntity.builder()
          .withId(1L)
          .withName("testFunction")
          .withNamespace(namespace)
          .withComment("test function comment")
          .withFunctionType(FunctionType.SCALAR)
          .withDeterministic(true)
          .withDefinitions(new org.apache.gravitino.function.FunctionDefinition[0])
          .withAuditInfo(
              AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
          .build()
    };
  }

  private Function[] buildFunctionInfos(Namespace namespace, String... functionNames) {
    return Arrays.stream(functionNames)
        .map(
            functionName ->
                FunctionEntity.builder()
                    .withId(1L)
                    .withName(functionName)
                    .withNamespace(namespace)
                    .withComment("test function comment")
                    .withFunctionType(FunctionType.SCALAR)
                    .withDeterministic(true)
                    .withDefinitions(new org.apache.gravitino.function.FunctionDefinition[0])
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .toArray(Function[]::new);
  }

  private void mockViews(Namespace namespace) {
    NameIdentifier[] viewIdents = {NameIdentifier.of(namespace, "testView")};
    when(viewDispatcher.listViews(namespace)).thenReturn(viewIdents);
    when(viewDispatcher.listEntities(namespace)).thenReturn(buildViewEntity(viewIdents));
  }

  private List<ViewEntity> buildViewEntity(NameIdentifier[] viewIdents) {
    return Arrays.stream(viewIdents)
        .map(
            ident ->
                ViewEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("test view comment")
                    .withColumns(new Column[] {Column.of("view_col", Types.IntegerType.get())})
                    .withRepresentations(
                        new Representation[] {
                          SQLRepresentation.builder()
                              .withDialect("spark")
                              .withSql("SELECT 1")
                              .build()
                        })
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<ModelEntity> buildModelEntity(NameIdentifier[] modelIdents) {
    return Arrays.stream(modelIdents)
        .map(
            ident ->
                ModelEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("comment")
                    .withLatestVersion(1)
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<FilesetEntity> buildFilesetEntity(NameIdentifier[] filesetIdents) {
    return Arrays.stream(filesetIdents)
        .map(
            ident ->
                FilesetEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("comment")
                    .withFilesetType(MANAGED)
                    .withStorageLocations(ImmutableMap.of(LOCATION_NAME_UNKNOWN, "location"))
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<TopicEntity> buildTopicEntity(NameIdentifier[] topicIdents) {
    return Arrays.stream(topicIdents)
        .map(
            ident ->
                TopicEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withComment("comment")
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private List<TableEntity> buildTableEntity(NameIdentifier[] tableIdents) {
    return Arrays.stream(tableIdents)
        .map(
            ident ->
                TableEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withNamespace(ident.namespace())
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .withColumns(
                        ImmutableList.of(
                            org.apache.gravitino.meta.ColumnEntity.builder()
                                .withName("col1")
                                .withId(1L)
                                .withPosition(1)
                                .withDataType(Types.IntegerType.get())
                                .withNullable(true)
                                .withAuditInfo(
                                    AuditInfo.builder()
                                        .withCreator("creator")
                                        .withCreateTime(Instant.now())
                                        .build())
                                .withComment("comment")
                                .build()))
                    .build())
        .collect(Collectors.toList());
  }

  private DatastratoTagRelPO mockTagRelation(
      long metadataObjectId, long tagId, String tagName, String comment) throws IOException {
    return mockTagRelation(metadataObjectId, tagId, tagName, comment, MetadataObject.Type.SCHEMA);
  }

  private DatastratoTagRelPO mockTagRelation(
      long metadataObjectId,
      long tagId,
      String tagName,
      String comment,
      MetadataObject.Type metadataObjectType)
      throws IOException {
    DatastratoTagRelPO relation = mock(DatastratoTagRelPO.class);
    when(relation.getMetadataObjectId()).thenReturn(metadataObjectId);
    when(relation.getMetadataObjectType()).thenReturn(metadataObjectType.name());
    when(relation.getTagId()).thenReturn(tagId);
    when(relation.getTagName()).thenReturn(tagName);
    when(relation.getMetalakeId()).thenReturn(1L);
    when(relation.getComment()).thenReturn(comment);
    when(relation.getProperties())
        .thenReturn(JsonUtils.anyFieldMapper().writeValueAsString(Map.of("source", comment)));
    when(relation.getAuditInfo())
        .thenReturn(
            JsonUtils.anyFieldMapper()
                .writeValueAsString(
                    AuditInfo.builder()
                        .withCreator("creator")
                        .withCreateTime(Instant.now())
                        .build()));
    when(relation.getCurrentVersion()).thenReturn(1L);
    when(relation.getLastVersion()).thenReturn(1L);
    when(relation.getDeletedAt()).thenReturn(0L);
    return relation;
  }

  private DatastratoPolicyRelPO mockPolicyRelation(
      long metadataObjectId, long policyId, String policyName, String comment) throws IOException {
    return mockPolicyRelation(
        metadataObjectId, policyId, policyName, comment, MetadataObject.Type.SCHEMA);
  }

  private DatastratoPolicyRelPO mockPolicyRelation(
      long metadataObjectId,
      long policyId,
      String policyName,
      String comment,
      MetadataObject.Type metadataObjectType)
      throws IOException {
    DatastratoPolicyRelPO relation = mock(DatastratoPolicyRelPO.class);
    when(relation.getMetadataObjectId()).thenReturn(metadataObjectId);
    when(relation.getMetadataObjectType()).thenReturn(metadataObjectType.name());
    when(relation.getPolicyId()).thenReturn(policyId);
    when(relation.getPolicyName()).thenReturn(policyName);
    when(relation.getMetalakeId()).thenReturn(1L);
    when(relation.getPolicyType()).thenReturn(Policy.BuiltInType.CUSTOM.policyType());
    when(relation.getAuditInfo())
        .thenReturn(
            JsonUtils.anyFieldMapper()
                .writeValueAsString(
                    AuditInfo.builder()
                        .withCreator("creator")
                        .withCreateTime(Instant.now())
                        .build()));
    when(relation.getCurrentVersion()).thenReturn(1L);
    when(relation.getLastVersion()).thenReturn(1L);
    when(relation.getDeletedAt()).thenReturn(0L);
    when(relation.getVersionId()).thenReturn(policyId);
    when(relation.getVersionMetalakeId()).thenReturn(1L);
    when(relation.getVersionPolicyId()).thenReturn(policyId);
    when(relation.getVersion()).thenReturn(1L);
    when(relation.getPolicyComment()).thenReturn(comment);
    when(relation.getEnabled()).thenReturn(true);
    when(relation.getContent())
        .thenReturn(
            JsonUtils.anyFieldMapper()
                .writeValueAsString(
                    PolicyContents.custom(
                        Collections.emptyMap(),
                        Set.of(MetadataObject.Type.SCHEMA),
                        Collections.emptyMap())));
    when(relation.getVersionDeletedAt()).thenReturn(0L);
    return relation;
  }

  private SchemaEntity buildSchemaEntity(long id, NameIdentifier schemaIdent) {
    return SchemaEntity.builder()
        .withId(id)
        .withName(schemaIdent.name())
        .withComment("comment")
        .withNamespace(schemaIdent.namespace())
        .withProperties(ImmutableMap.of("key", "value"))
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }

  private List<SchemaEntity> buildSchemaEntity(NameIdentifier[] schemaIdents) {
    return Arrays.stream(schemaIdents)
        .map(
            ident ->
                SchemaEntity.builder()
                    .withId(1L)
                    .withName(ident.name())
                    .withComment("comment")
                    .withNamespace(ident.namespace())
                    .withProperties(ImmutableMap.of("key", "value"))
                    .withAuditInfo(
                        AuditInfo.builder()
                            .withCreator("creator")
                            .withCreateTime(Instant.now())
                            .build())
                    .build())
        .collect(Collectors.toList());
  }

  private BaseMetalake buildMetalake(long id, String name) {
    return BaseMetalake.builder()
        .withId(id)
        .withName(name)
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .withVersion(SchemaVersion.V_0_1)
        .build();
  }

  private TestCatalog buildCatalog(String metalake, String catalogName) {
    CatalogEntity entity =
        CatalogEntity.builder()
            .withId(1L)
            .withName(catalogName)
            .withComment("comment")
            .withNamespace(Namespace.of(metalake))
            .withProperties(ImmutableMap.of("key", "value"))
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("test")
            .withAuditInfo(
                AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
            .build();

    return new TestCatalog().withCatalogConf(Collections.emptyMap()).withCatalogEntity(entity);
  }
}
