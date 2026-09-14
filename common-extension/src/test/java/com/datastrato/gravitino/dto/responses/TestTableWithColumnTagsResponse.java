/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.rel.ExtendedColumnDTO;
import com.datastrato.gravitino.dto.rel.TableWithColumnTagsDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.Optional;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.rel.TableDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests {@link TableWithColumnTagsResponse}. */
public class TestTableWithColumnTagsResponse {

  /** Tests that the response replaces base columns with tagged columns without duplicate fields. */
  @Test
  public void testSerializationAndValidation() throws JsonProcessingException {
    ColumnDTO firstColumn =
        ColumnDTO.builder().withName("id").withDataType(Types.IntegerType.get()).build();
    ColumnDTO secondColumn =
        ColumnDTO.builder().withName("email").withDataType(Types.StringType.get()).build();
    TableDTO table =
        TableDTO.builder()
            .withName("users")
            .withComment("user table")
            .withColumns(new ColumnDTO[] {firstColumn, secondColumn})
            .withProperties(Collections.singletonMap("key", "value"))
            .withAudit(AuditDTO.builder().build())
            .build();
    TableWithColumnTagsResponse response =
        new TableWithColumnTagsResponse(
            new TableWithColumnTagsDTO(
                table,
                new ExtendedColumnDTO[] {
                  new ExtendedColumnDTO(firstColumn, null),
                  new ExtendedColumnDTO(
                      secondColumn, new TagDTO[] {tag("PII", false), tag("Sensitive", true)})
                }));

    Assertions.assertDoesNotThrow(response::validate);
    String json = JsonUtils.objectMapper().writeValueAsString(response);
    JsonNode root = JsonUtils.objectMapper().readTree(json);
    Assertions.assertEquals(json.indexOf("\"columns\""), json.lastIndexOf("\"columns\""));
    Assertions.assertEquals(0, root.path("code").asInt());
    Assertions.assertEquals("users", root.at("/table/name").asText());
    Assertions.assertEquals(2, root.at("/table/columns").size());
    Assertions.assertTrue(root.at("/table/columns/0/tags").isArray());
    Assertions.assertEquals(0, root.at("/table/columns/0/tags").size());
    Assertions.assertEquals("PII", root.at("/table/columns/1/tags/0/name").asText());
    Assertions.assertFalse(root.at("/table/columns/1/tags/0/inherited").asBoolean());
    Assertions.assertEquals("Sensitive", root.at("/table/columns/1/tags/1/name").asText());
    Assertions.assertTrue(root.at("/table/columns/1/tags/1/inherited").asBoolean());

    TableWithColumnTagsResponse restored =
        JsonUtils.objectMapper().readValue(json, TableWithColumnTagsResponse.class);
    Assertions.assertDoesNotThrow(restored::validate);
    Assertions.assertEquals(response, restored);
  }

  /** Tests validation failures for incomplete deserialized responses. */
  @Test
  public void testValidation() throws JsonProcessingException {
    TableWithColumnTagsResponse response =
        JsonUtils.objectMapper().readValue("{\"code\":0}", TableWithColumnTagsResponse.class);
    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, response::validate);
    Assertions.assertEquals("table cannot be null", exception.getMessage());
  }

  private static TagDTO tag(String name, boolean inherited) {
    return TagDTO.builder()
        .withName(name)
        .withAudit(AuditDTO.builder().build())
        .withInherited(Optional.of(inherited))
        .build();
  }
}
