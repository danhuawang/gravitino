/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.dto.responses;

import static org.apache.gravitino.file.Fileset.Type.EXTERNAL;
import static org.apache.gravitino.file.Fileset.Type.MANAGED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.SchemaDTO;
import org.apache.gravitino.dto.file.FilesetDTO;
import org.apache.gravitino.dto.messaging.TopicDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestResponses {

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
    SchemaDTO[] schemas = new SchemaDTO[] {schema1, schema2};
    SchemaListResponse response = new SchemaListResponse(schemas);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    SchemaListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, SchemaListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(schemas, deserialized.getSchemas());

    SchemaListResponse illegalResp = new SchemaListResponse();
    Exception exception =
        Assertions.assertThrows(IllegalArgumentException.class, illegalResp::validate);
    Assertions.assertEquals("\"schemas\" cannot be null", exception.getMessage());
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
    TableDTO[] tables = new TableDTO[] {table1, table2};
    TableListResponse response = new TableListResponse(tables);
    Assertions.assertDoesNotThrow(response::validate);

    String serJson = JsonUtils.objectMapper().writeValueAsString(response);
    TableListResponse deserialized =
        JsonUtils.objectMapper().readValue(serJson, TableListResponse.class);
    Assertions.assertEquals(response, deserialized);
    Assertions.assertArrayEquals(tables, deserialized.getTables());

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
    FilesetDTO[] filesets = new FilesetDTO[] {fileset1, fileset2};
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
    TopicDTO[] topics = new TopicDTO[] {topic1, topic2};
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
}
