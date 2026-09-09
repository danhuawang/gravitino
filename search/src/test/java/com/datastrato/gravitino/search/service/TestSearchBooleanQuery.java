/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static com.datastrato.gravitino.search.config.SearchConfig.GRAVITINO_SEARCH_STORAGE_IMPL;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.datastrato.gravitino.search.store.WriteContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestSearchBooleanQuery {
  @Test
  void testServicePreservesBooleanKeywords() throws Exception {
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.getAllConfig()).thenReturn(Map.of(GRAVITINO_SEARCH_STORAGE_IMPL, "memory"));
    Object previous = FieldUtils.readField(GravitinoEnv.getInstance(), "metalakeDispatcher", true);
    MetalakeDispatcher dispatcher = Mockito.mock(MetalakeDispatcher.class);
    Mockito.when(dispatcher.metalakeExists(NameIdentifier.of("booleansearch"))).thenReturn(true);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "metalakeDispatcher", dispatcher, true);
    try (SearchService service = new SearchService(config)) {
      SearchStorage storage = (SearchStorage) FieldUtils.readField(service, "storage", true);
      for (String name : List.of("alpha", "beta", "both", "neither")) {
        storage.write(
            List.of(
                SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
                    .withColumns(
                        List.of(
                            SearchTableEntityPO.SearchColumn.builder()
                                .withColumnName("id")
                                .build()))
                    .withEntityId(name.hashCode())
                    .withEntityType(EntityType.TABLE)
                    .withEntityName(name)
                    .withFullQualifiedName(name)
                    .withCatalogName("catalog")
                    .withMetalake("booleansearch")
                    .withInUse(true)
                    .withEntityComment(name.equals("both") ? "alpha beta" : "")
                    .build()),
            WriteContext.DEFAULT);
      }
      for (String query :
          List.of("alpha beta", "alpha  beta", "alpha\tbeta", "alpha\nbeta", "alpha AND beta")) {
        assertNames(service.query("booleansearch", query, 0, 100), "both");
      }
      assertNames(service.query("booleansearch", "alpha OR beta", 0, 100), "alpha", "beta", "both");
      assertNames(
          service.query("booleansearch", "alpha (beta OR entity_name:alpha)", 0, 100),
          "alpha",
          "both");
      assertNames(
          service.query("booleansearch", "alpha OR beta entity_name:beta", 0, 100),
          "alpha",
          "beta",
          "both");
      assertNames(service.query("booleansearch", "alpha -entity_name:both", 0, 100), "alpha");
      assertNames(service.query("booleansearch", "zznever wwnever", 0, 100));
      assertNames(service.query("booleansearch", " ", 0, 100), "alpha", "beta", "both", "neither");
    } finally {
      FieldUtils.writeField(GravitinoEnv.getInstance(), "metalakeDispatcher", previous, true);
    }
  }

  private static void assertNames(List<SearchEntitiesDTO> results, String... expected) {
    Assertions.assertEquals(
        Set.of(expected),
        results.stream()
            .flatMap(group -> group.getEntities().stream())
            .map(SearchEntityDTO::getEntityName)
            .collect(Collectors.toSet()));
  }
}
