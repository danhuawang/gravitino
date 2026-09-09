/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.ConditionBuilderVisitor;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class TestInMemoryBooleanQuery {
  @ParameterizedTest(name = "query={0}")
  @MethodSource("booleanQueries")
  void testBooleanKeywords(String query, Set<String> expected) throws Exception {
    try (InMemorySearchStorage storage = new InMemorySearchStorage()) {
      storage.initialize(Mockito.mock(Config.class));
      List<String> names = List.of("alpha", "beta", "both", "neither");
      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i);
        storage.write(
            List.of(
                SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
                    .withEntityId(i + 1)
                    .withEntityType(EntityType.TABLE)
                    .withEntityName(name)
                    .withFullQualifiedName(name)
                    .withCatalogName("catalog")
                    .withMetalake("booleansearch")
                    .withInUse(true)
                    // A shared searchable field must not bypass the other keyword in an AND
                    // expression.
                    .withEntityComment(name.equals("both") ? "alpha beta xyz" : "b xyz")
                    .withColumns(
                        List.of(
                            SearchTableEntityPO.SearchColumn.builder()
                                .withColumnName("id")
                                .build()))
                    .build()),
            WriteContext.DEFAULT);
      }
      List<SearchEntitiesDTO> results =
          storage.search(
              "booleansearch",
              null,
              ConditionBuilderVisitor.buildQueryCondition(query).getCondition(),
              List.of(),
              100,
              0);
      Assertions.assertEquals(
          expected,
          results.stream()
              .flatMap(group -> group.getEntities().stream())
              .map(SearchEntityDTO::getEntityName)
              .collect(Collectors.toSet()));
      Assertions.assertEquals(
          expected.size(), results.stream().mapToLong(SearchEntitiesDTO::getTotalSize).sum());
    }
  }

  private static Stream<Arguments> booleanQueries() {
    return Stream.of(
        Arguments.of("alpha beta", Set.of("both")),
        Arguments.of("alpha  beta", Set.of("both")),
        Arguments.of("alpha\tbeta", Set.of("both")),
        Arguments.of("alpha\nbeta", Set.of("both")),
        Arguments.of("alpha AND beta", Set.of("both")),
        Arguments.of("alpha OR beta", Set.of("alpha", "beta", "both")),
        Arguments.of("alpha (beta OR entity_name:alpha)", Set.of("alpha", "both")),
        Arguments.of("alpha OR beta entity_name:beta", Set.of("alpha", "beta", "both")),
        Arguments.of("alpha -entity_name:both", Set.of("alpha")),
        Arguments.of("alpha xyz", Set.of("alpha", "both")),
        Arguments.of("a b", Set.of("alpha", "beta", "both")),
        Arguments.of("zznever wwnever", Set.of()),
        Arguments.of("", Set.of("alpha", "beta", "both", "neither")),
        Arguments.of(" ", Set.of("alpha", "beta", "both", "neither")));
  }
}
