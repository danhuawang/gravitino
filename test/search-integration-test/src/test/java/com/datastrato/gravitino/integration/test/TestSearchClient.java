/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.integration.test;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchPolicyEntityDTO;
import com.datastrato.gravitino.search.rest.SearchQueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.client.ObjectMapperProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests search response parsing in {@link SearchClient}. */
public class TestSearchClient {

  @Test
  void testParseSearchResponsePreservesPolicyEntityType() throws Exception {
    SearchPolicyEntityDTO policy =
        SearchPolicyEntityDTO.Builder.builder()
            .withPolicyType("custom")
            .withEnabled(true)
            .withContent("{\"retentionDays\":30}")
            .withEntityId(1L)
            .withEntityType(EntityType.POLICY)
            .withMetalake("metalake")
            .withEntityName("retention_policy")
            .build();
    SearchQueryResponse response =
        new SearchQueryResponse(
            ImmutableList.of(
                SearchEntitiesDTO.builder()
                    .withTotalSize(1)
                    .withType(EntityType.POLICY)
                    .withEntities(ImmutableList.of(policy))
                    .build()));
    ObjectMapper mapper = ObjectMapperProvider.objectMapper();

    List<SearchEntitiesDTO> parsed =
        SearchClient.parseSearchResponse(mapper, mapper.writeValueAsString(response));

    Assertions.assertEquals(1, parsed.size());
    SearchEntityDTO entity = parsed.get(0).getEntities().get(0);
    Assertions.assertInstanceOf(SearchPolicyEntityDTO.class, entity);
    SearchPolicyEntityDTO parsedPolicy = (SearchPolicyEntityDTO) entity;
    Assertions.assertEquals("custom", parsedPolicy.getPolicyType());
    Assertions.assertTrue(parsedPolicy.isEnabled());
    Assertions.assertEquals("{\"retentionDays\":30}", parsedPolicy.getContent());
  }
}
