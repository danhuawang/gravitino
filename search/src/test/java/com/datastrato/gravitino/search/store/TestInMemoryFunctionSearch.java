/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Covers indexing and querying functions on the in-memory search backend. */
public class TestInMemoryFunctionSearch {

  @Test
  void testSearchFunction() {
    InMemorySearchStorage storage = new InMemorySearchStorage();
    storage.initialize(Mockito.mock(Config.class));

    SearchEntityPO function =
        SearchEntityPO.SearchEntityPOBuilder.builder()
            .withEntityId(2)
            .withEntityType(EntityType.FUNCTION)
            .withMetalake("metalake-a")
            .withCatalogName("catalog")
            .withEntityName("mask_email")
            .withEntityComment("masks an email")
            .withFullQualifiedName("catalog.s1.mask_email")
            .build();
    storage.write(ImmutableList.of(function), WriteContext.DEFAULT);

    List<SearchEntitiesDTO> result =
        storage.search("metalake-a", "mask_email", null, ImmutableList.of(), 10, 0);
    Assertions.assertEquals(1, result.size());
    Assertions.assertEquals(EntityType.FUNCTION, result.get(0).getType());
    SearchEntityDTO functionDTO = result.get(0).getEntities().get(0);
    Assertions.assertEquals("catalog.s1.mask_email", functionDTO.getFullQualifiedName());
  }
}
