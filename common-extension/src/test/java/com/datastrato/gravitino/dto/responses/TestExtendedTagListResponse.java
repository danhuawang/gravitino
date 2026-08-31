/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.responses;

import com.datastrato.gravitino.dto.tag.ExtendedTagDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Collections;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestExtendedTagListResponse {

  @Test
  public void testSerializationAndValidation() throws JsonProcessingException {
    TagDTO tag =
        TagDTO.builder()
            .withName("tag1")
            .withComment("comment")
            .withProperties(Collections.emptyMap())
            .withAudit(AuditDTO.builder().build())
            .build();
    OwnerDTO owner = OwnerDTO.builder().withName("user1").withType(Owner.Type.USER).build();
    ExtendedTagDTO extendedTag = ExtendedTagDTO.builder().withTag(tag).withOwner(owner).build();
    ExtendedTagListResponse response =
        new ExtendedTagListResponse(new ExtendedTagDTO[] {extendedTag});

    Assertions.assertDoesNotThrow(response::validate);
    String json = JsonUtils.objectMapper().writeValueAsString(response);
    Assertions.assertTrue(json.contains("\"name\":\"tag1\""));
    Assertions.assertEquals(
        "user1", JsonUtils.objectMapper().readTree(json).at("/tags/0/owner/name").asText());
    Assertions.assertEquals(
        "user", JsonUtils.objectMapper().readTree(json).at("/tags/0/owner/type").asText());

    ExtendedTagListResponse deserialized =
        JsonUtils.objectMapper().readValue(json, ExtendedTagListResponse.class);
    Assertions.assertDoesNotThrow(deserialized::validate);
    Assertions.assertEquals("tag1", deserialized.getTags()[0].tag().name());
    Assertions.assertEquals("user1", deserialized.getTags()[0].owner().name());
  }

  @Test
  public void testValidation() {
    ExtendedTagListResponse response = new ExtendedTagListResponse();
    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, response::validate);
    Assertions.assertEquals("\"tags\" cannot be null", exception.getMessage());

    Assertions.assertThrows(
        IllegalArgumentException.class, () -> ExtendedTagDTO.builder().withTag(null).build());
  }
}
