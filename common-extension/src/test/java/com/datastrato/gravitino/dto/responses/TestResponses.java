/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import static org.apache.gravitino.file.Fileset.Type.EXTERNAL;
import static org.apache.gravitino.file.Fileset.Type.MANAGED;

import com.datastrato.gravitino.dto.ConnectionDTO;
import com.datastrato.gravitino.dto.DirectChildCountDTO;
import com.datastrato.gravitino.dto.DirectChildCountState;
import com.datastrato.gravitino.dto.ExtendedCatalogDTO;
import com.datastrato.gravitino.dto.ExtendedMetalakeDTO;
import com.datastrato.gravitino.dto.ExtendedSchemaDTO;
import com.datastrato.gravitino.dto.file.ExtendedFilesetDTO;
import com.datastrato.gravitino.dto.function.ExtendedFunctionDTO;
import com.datastrato.gravitino.dto.messaging.ExtendedTopicDTO;
import com.datastrato.gravitino.dto.model.ExtendedModelDTO;
import com.datastrato.gravitino.dto.policy.ExtendedPolicyDTO;
import com.datastrato.gravitino.dto.rel.ExtendedTableDTO;
import com.datastrato.gravitino.dto.rel.ExtendedViewDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.CatalogDTO;
import org.apache.gravitino.dto.MetalakeDTO;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.function.FunctionDTO;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.model.ModelDTO;
import org.apache.gravitino.dto.policy.PolicyContentDTO;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.RepresentationDTO;
import org.apache.gravitino.dto.rel.SQLRepresentationDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.rel.ViewDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestResponses {

  /** Tests metalake summary validation and JSON serialization. */
  @Test
  public void testMetalakeSummaryResponse() throws JsonProcessingException {
    MetalakeSummaryResponse response = new MetalakeSummaryResponse(2L, 3L, 1L);
    Assertions.assertDoesNotThrow(response::validate);

    String serialized = JsonUtils.objectMapper().writeValueAsString(response);
    MetalakeSummaryResponse deserialized =
        JsonUtils.objectMapper().readValue(serialized, MetalakeSummaryResponse.class);
    Assertions.assertEquals(response, deserialized);

    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new MetalakeSummaryResponse(null, 0L, 0L));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new MetalakeSummaryResponse(-1L, 0L, 0L));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new MetalakeSummaryResponse(0L, -1L, 0L));
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new MetalakeSummaryResponse(0L, 0L, -1L));

    Assertions.assertThrows(
        JsonProcessingException.class,
        () ->
            JsonUtils.objectMapper()
                .readValue(
                    "{\"code\":0,\"catalogCount\":-1,\"userCount\":0,\"roleCount\":0}",
                    MetalakeSummaryResponse.class));
    Assertions.assertThrows(
        JsonProcessingException.class,
        () ->
            JsonUtils.objectMapper()
                .readValue(
                    "{\"code\":0,\"catalogCount\":null,\"userCount\":0,\"roleCount\":0}",
                    MetalakeSummaryResponse.class));
    Assertions.assertThrows(
        JsonProcessingException.class,
        () ->
            JsonUtils.objectMapper()
                .readValue(
                    "{\"code\":0,\"userCount\":0,\"roleCount\":0}", MetalakeSummaryResponse.class));
  }

  @Test
  public void testMetalakeListResponse() throws JsonProcessingException {
    MetalakeDTO metalake =
        MetalakeDTO.builder().withName("metalake1").withAudit(AuditDTO.builder().build()).build();
    ExtendedMetalakeDTO extendedMetalake = new ExtendedMetalakeDTO(metalake, 2L);
    ExtendedMetalakeDTO extendedMetalakeWithUnavailableCount =
        new ExtendedMetalakeDTO(metalake, null);
    ExtendedMetalakeDTO[] metalakes =
        new ExtendedMetalakeDTO[] {extendedMetalake, extendedMetalakeWithUnavailableCount};
    MetalakeListResponse response = new MetalakeListResponse(metalakes);
    Assertions.assertDoesNotThrow(response::validate);

    String serialized = JsonUtils.objectMapper().writeValueAsString(response);
    MetalakeListResponse deserialized =
        JsonUtils.objectMapper().readValue(serialized, MetalakeListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(metalakes, deserialized.getMetalakes());
    Assertions.assertNull(deserialized.getMetalakes()[1].getDirectChildCounts());

    MetalakeListResponse illegalResponse = new MetalakeListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResponse::validate);
    Assertions.assertEquals("\"metalakes\" cannot be null", exception.getMessage());

    ExtendedMetalakeDTO negativeCountMetalake = new ExtendedMetalakeDTO(metalake, -1L);
    exception =
        Assertions.assertThrows(IllegalArgumentException.class, negativeCountMetalake::validate);
    Assertions.assertEquals("directChildCounts cannot be negative", exception.getMessage());
  }

  @Test
  public void testCatalogListResponse() throws JsonProcessingException {
    CatalogDTO catalog =
        CatalogDTO.builder()
            .withName("catalog1")
            .withType(Catalog.Type.RELATIONAL)
            .withProvider("provider")
            .withAudit(AuditDTO.builder().build())
            .build();
    ExtendedCatalogDTO extendedCatalog =
        new ExtendedCatalogDTO(
            catalog,
            new TagDTO[0],
            new PolicyDTO[0],
            new DirectChildCountDTO(2L, DirectChildCountState.COMPLETE, 100L, false));
    ExtendedCatalogDTO extendedCatalogWithUnavailableCount =
        new ExtendedCatalogDTO(
            catalog,
            new TagDTO[0],
            new PolicyDTO[0],
            new DirectChildCountDTO(null, DirectChildCountState.UNAVAILABLE, null, true));
    ExtendedCatalogDTO[] catalogs =
        new ExtendedCatalogDTO[] {extendedCatalog, extendedCatalogWithUnavailableCount};
    CatalogListResponse response = new CatalogListResponse(catalogs);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    CatalogListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, CatalogListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(catalogs, deserialized.getCatalogs());
    Assertions.assertNull(deserialized.getCatalogs()[1].getDirectChildCount().getValue());
    Assertions.assertEquals(
        DirectChildCountState.UNAVAILABLE,
        deserialized.getCatalogs()[1].getDirectChildCount().getState());
    Assertions.assertTrue(deserialized.getCatalogs()[1].getDirectChildCount().isRefreshPending());
    Assertions.assertTrue(serJson.contains("\"directChildCount\""));
    Assertions.assertTrue(serJson.contains("\"value\":null"));
    Assertions.assertTrue(serJson.contains("\"updatedAt\":null"));
    Assertions.assertFalse(serJson.contains("\"directChildCounts\""));

    CatalogListResponse illegalResp = new CatalogListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"catalogs\" cannot be null", exception.getMessage());
  }

  @Test
  public void testSchemaListResponse() throws JsonProcessingException {
    SchemaDTO schema1 =
        SchemaDTO.builder()
            .withName("schema1")
            .withComment("comment1")
            .withAudit(AuditDTO.builder().build())
            .build();
    SchemaDTO schema2 =
        SchemaDTO.builder().withName("schema2").withAudit(AuditDTO.builder().build()).build();
    ExtendedSchemaDTO extendedSchema1 =
        new ExtendedSchemaDTO(
            schema1,
            new TagDTO[0],
            new PolicyDTO[0],
            new DirectChildCountDTO(3L, DirectChildCountState.COMPLETE, 200L, false));
    ExtendedSchemaDTO extendedSchema2 =
        new ExtendedSchemaDTO(
            schema2,
            new TagDTO[0],
            new PolicyDTO[0],
            new DirectChildCountDTO(null, DirectChildCountState.PARTIAL, 201L, true));
    ExtendedSchemaDTO[] schemas = new ExtendedSchemaDTO[] {extendedSchema1, extendedSchema2};
    SchemaListResponse response = new SchemaListResponse(schemas);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    SchemaListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, SchemaListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(schemas, deserialized.getSchemas());
    Assertions.assertNull(deserialized.getSchemas()[1].getDirectChildCount().getValue());
    Assertions.assertEquals(
        DirectChildCountState.PARTIAL,
        deserialized.getSchemas()[1].getDirectChildCount().getState());
    Assertions.assertEquals(
        201L, deserialized.getSchemas()[1].getDirectChildCount().getUpdatedAt());
    Assertions.assertTrue(serJson.contains("\"directChildCount\""));
    Assertions.assertFalse(serJson.contains("\"directChildCounts\""));

    SchemaListResponse illegalResp = new SchemaListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"schemas\" cannot be null", exception.getMessage());
  }

  @Test
  public void testDirectChildCountValidation() {
    Assertions.assertDoesNotThrow(
        () -> new DirectChildCountDTO(0L, DirectChildCountState.COMPLETE, 0L, false).validate());
    Assertions.assertDoesNotThrow(
        () ->
            new DirectChildCountDTO(null, DirectChildCountState.UNAVAILABLE, null, true)
                .validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new DirectChildCountDTO(null, DirectChildCountState.COMPLETE, 1L, false).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new DirectChildCountDTO(-1L, DirectChildCountState.COMPLETE, 1L, false).validate());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new DirectChildCountDTO(1L, DirectChildCountState.PARTIAL, 1L, true).validate());
  }

  @Test
  public void testTableListResponse() throws JsonProcessingException {
    TableDTO table1 =
        TableDTO.builder()
            .withName("table1")
            .withComment("comment1")
            .withColumns(
                new ColumnDTO[] {DTOConverters.toDTO(Column.of("a", Types.ByteType.get()))})
            .withAudit(AuditDTO.builder().build())
            .build();
    TableDTO table2 =
        TableDTO.builder()
            .withName("table2")
            .withColumns(
                new ColumnDTO[] {DTOConverters.toDTO(Column.of("b", Types.IntegerType.get()))})
            .withAudit(AuditDTO.builder().build())
            .build();
    ExtendedTableDTO extTable1 = new ExtendedTableDTO(table1, new TagDTO[0], new PolicyDTO[0]);
    ExtendedTableDTO extTable2 = new ExtendedTableDTO(table2, new TagDTO[0], new PolicyDTO[0]);
    ExtendedTableDTO[] tables = new ExtendedTableDTO[] {extTable1, extTable2};
    ExtendedFunctionDTO[] functions = buildFunctions();
    ExtendedViewDTO[] views = buildViews();
    TableListResponse response = new TableListResponse(tables, functions, views);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    TableListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, TableListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(tables, deserialized.getTables());
    Assertions.assertArrayEquals(functions, deserialized.getFunctions());
    Assertions.assertArrayEquals(views, deserialized.getViews());

    TableListResponse illegalResp = new TableListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"tables\" cannot be null", exception.getMessage());
  }

  @Test
  public void testFilesetListResponse() throws JsonProcessingException {
    FilesetDTO fileset1 =
        FilesetDTO.builder()
            .name("fileset1")
            .comment("comment1")
            .type(MANAGED)
            .audit(AuditDTO.builder().build())
            .storageLocations(ImmutableMap.of("location", "location1"))
            .build();
    FilesetDTO fileset2 =
        FilesetDTO.builder()
            .name("fileset2")
            .type(EXTERNAL)
            .audit(AuditDTO.builder().build())
            .storageLocations(ImmutableMap.of("location", "location2"))
            .build();
    ExtendedFilesetDTO extFileset1 =
        new ExtendedFilesetDTO(fileset1, new TagDTO[0], new PolicyDTO[0]);
    ExtendedFilesetDTO extFileset2 =
        new ExtendedFilesetDTO(fileset2, new TagDTO[0], new PolicyDTO[0]);
    ExtendedFilesetDTO[] filesets = new ExtendedFilesetDTO[] {extFileset1, extFileset2};
    FilesetListResponse response = new FilesetListResponse(filesets);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    FilesetListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, FilesetListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(filesets, deserialized.getFilesets());

    FilesetListResponse illegalResp = new FilesetListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"filesets\" cannot be null", exception.getMessage());
  }

  @Test
  public void testTopicListResponse() throws JsonProcessingException {
    TopicDTO topic1 =
        TopicDTO.builder()
            .withName("topic1")
            .withComment("comment1")
            .withAudit(AuditDTO.builder().build())
            .build();
    TopicDTO topic2 =
        TopicDTO.builder().withName("topic2").withAudit(AuditDTO.builder().build()).build();
    ExtendedTopicDTO extTopic1 = new ExtendedTopicDTO(topic1, new TagDTO[0], new PolicyDTO[0]);
    ExtendedTopicDTO extTopic2 = new ExtendedTopicDTO(topic2, new TagDTO[0], new PolicyDTO[0]);
    ExtendedTopicDTO[] topics = new ExtendedTopicDTO[] {extTopic1, extTopic2};
    TopicListResponse response = new TopicListResponse(topics);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    TopicListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, TopicListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(topics, deserialized.getTopics());

    TopicListResponse illegalResp = new TopicListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"topics\" cannot be null", exception.getMessage());
  }

  @Test
  public void testModelListResponse() throws JsonProcessingException {
    ModelDTO model1 =
        ModelDTO.builder()
            .withName("model1")
            .withComment("comment1")
            .withAudit(AuditDTO.builder().build())
            .build();
    ExtendedModelDTO extModel1 = new ExtendedModelDTO(model1, new TagDTO[0], new PolicyDTO[0]);
    ExtendedModelDTO[] models = new ExtendedModelDTO[] {extModel1};
    ModelListResponse response = new ModelListResponse(models);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    ModelListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, ModelListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(models, deserialized.getModels());
  }

  @Test
  public void testPreviewResponse() throws JsonProcessingException {
    DataPreviewResponse response =
        new DataPreviewResponse(
            1,
            "ok",
            DTOConverters.toDTOs(new Column[] {Column.of("a", Types.ByteType.get())}),
            new Map[] {});
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    DataPreviewResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, DataPreviewResponse.class);
    Assertions.assertEquals(response, deserialized);

    DataPreviewResponse illegalResp = new DataPreviewResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"message\" can't be blank", exception.getMessage());
  }

  private ExtendedFunctionDTO[] buildFunctions() {
    FunctionDTO function =
        FunctionDTO.builder()
            .withName("test_function")
            .withFunctionType(FunctionType.SCALAR)
            .withDeterministic(true)
            .withComment("test function")
            .withAudit(AuditDTO.builder().build())
            .build();
    return new ExtendedFunctionDTO[] {
      new ExtendedFunctionDTO(function, new TagDTO[0], new PolicyDTO[0])
    };
  }

  private ExtendedViewDTO[] buildViews() {
    ViewDTO view =
        ViewDTO.builder()
            .withName("test_view")
            .withComment("test view")
            .withColumns(
                new ColumnDTO[] {DTOConverters.toDTO(Column.of("a", Types.ByteType.get()))})
            .withRepresentations(
                new RepresentationDTO[] {
                  SQLRepresentationDTO.builder().withDialect("spark").withSql("SELECT 1").build()
                })
            .withAudit(AuditDTO.builder().build())
            .build();
    return new ExtendedViewDTO[] {new ExtendedViewDTO(view, new TagDTO[0], new PolicyDTO[0])};
  }

  @Test
  public void testConnectionListResponse() throws JsonProcessingException {
    ConnectionDTO connection =
        new ConnectionDTO(
            "sales_catalog", "Iceberg REST", "https://irc.acme.internal/iceberg/", "s3-token", 4L);
    Assertions.assertDoesNotThrow(connection::validate);

    ConnectionDTO connectionWithUnavailableCount =
        new ConnectionDTO(
            "events", "Iceberg REST", "https://irc.acme.internal/iceberg/", "s3-token", null);
    Assertions.assertNull(connectionWithUnavailableCount.getSchemaCount());
    Assertions.assertDoesNotThrow(connectionWithUnavailableCount::validate);

    ConnectionDTO connectionWithInvalidCount =
        new ConnectionDTO("invalid", "Hive", "thrift://hive:9083", "kerberos-keytab", -1L);
    Assertions.assertThrows(IllegalArgumentException.class, connectionWithInvalidCount::validate);

    ConnectionDTO[] connections = new ConnectionDTO[] {connection};
    ConnectionListResponse response = new ConnectionListResponse(connections, 1, 1);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    ConnectionListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, ConnectionListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(connections, deserialized.getConnections());
    Assertions.assertEquals(1, deserialized.getCatalogCount());
    Assertions.assertEquals(1, deserialized.getSystemCount());

    ConnectionListResponse illegalResp = new ConnectionListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"connections\" cannot be null", exception.getMessage());
  }

  @Test
  public void testExtendedPolicyListResponse() throws JsonProcessingException {
    PolicyContentDTO content =
        PolicyContentDTO.CustomContentDTO.builder()
            .withCustomRules(ImmutableMap.of("rule1", "val1"))
            .withSupportedObjectTypes(ImmutableSet.of(MetadataObject.Type.TABLE))
            .build();

    PolicyDTO policy =
        PolicyDTO.builder()
            .withName("policy1")
            .withPolicyType("custom")
            .withComment("comment")
            .withEnabled(true)
            .withContent(content)
            .withAudit(AuditDTO.builder().build())
            .build();

    ExtendedPolicyDTO extendedPolicy =
        ExtendedPolicyDTO.builder().withPolicy(policy).withAssociatedObjectsCount(5).build();

    ExtendedPolicyListResponse response =
        new ExtendedPolicyListResponse(new ExtendedPolicyDTO[] {extendedPolicy});
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    Assertions.assertTrue(serJson.contains("\"name\":\"policy1\""));
    Assertions.assertTrue(serJson.contains("\"policyType\":\"custom\""));
    Assertions.assertTrue(serJson.contains("\"associatedObjectsCount\":5"));

    ExtendedPolicyListResponse illegalResp = new ExtendedPolicyListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"policies\" cannot be null", exception.getMessage());
  }
}
