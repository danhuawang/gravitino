/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.parser.ConditionBuilderVisitor;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.InMemorySearchStorage;
import com.datastrato.gravitino.search.store.WriteContext;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.meta.JobTemplateEntity;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;

class TestJobSearch {
  @Test
  void testTemplateProjectionCarriesOnlyPublicFields() {
    SearchEntityPO projection =
        EntityConverterUtils.toJobTemplateSearchEntityPO(template(), "lake");

    assertEquals("builtin-sparkpi", projection.getEntityName());
    assertEquals("current description", projection.getEntityComment());
    assertNull(projection.getEntityProperties());

    // The projection has to survive a round trip through the codec unchanged.
    String json = SearchEntityCodec.INSTANCE.serialize(projection);
    assertEquals(
        "builtin-sparkpi",
        SearchEntityCodec.INSTANCE.deserialize(json, SearchEntityPO.class).getEntityName());
  }

  @Test
  void testNameCommentBooleanQueriesAndIsolation() throws Exception {
    try (InMemorySearchStorage storage = new InMemorySearchStorage()) {
      storage.initialize(mock(Config.class));
      JobTemplateEntity otherTemplate = template();
      when(otherTemplate.id()).thenReturn(3L);
      storage.write(
          List.of(
              EntityConverterUtils.toJobTemplateSearchEntityPO(template(), "lake"),
              EntityConverterUtils.toJobTemplateSearchEntityPO(otherTemplate, "other")),
          WriteContext.DEFAULT);
      // The other template belongs to a different metalake and must stay out of these results.
      assertNames(storage, "sparkpi", Set.of("builtin-sparkpi"));
      assertNames(storage, "current description", Set.of("builtin-sparkpi"));
      assertNames(storage, "sparkpi entity_type:job_template", Set.of("builtin-sparkpi"));
      assertNames(storage, "secret-password", Set.of());

      // A rename reuses the entity id, so indexing the new projection replaces the old name.
      storage.write(
          List.of(EntityConverterUtils.toJobTemplateSearchEntityPO(renamedTemplate(), "lake")),
          WriteContext.DEFAULT);
      assertNames(storage, "builtin-sparkpi entity_type:job_template", Set.of());
      assertNames(storage, "renamed", Set.of("renamed"));
    }
  }

  @Test
  void testSourceLoadsTemplatesDuringMetalakeSync() throws Exception {
    Object previous =
        FieldUtils.readField(GravitinoEnv.getInstance(), "internalJobOperationDispatcher", true);
    JobOperationDispatcher dispatcher = mock(JobOperationDispatcher.class);
    JobTemplateEntity template = template();
    when(dispatcher.getJobTemplate("lake", "builtin-sparkpi")).thenReturn(template);
    try {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "internalJobOperationDispatcher", dispatcher, true);
      NameIdentifier ident = NameIdentifierUtil.ofJobTemplate("lake", "builtin-sparkpi");
      SearchEntitySource source =
          SearchEntitySource.createSearchEntitySource(
              SearchEntityIdentifier.of(ident, EntityType.JOB_TEMPLATE), false);

      List<SearchEntityPO> entities = source.nextBatch(10);

      assertEquals(1, entities.size());
      assertEquals(EntityType.JOB_TEMPLATE, entities.get(0).getEntityType());
      assertEquals(ident.name(), entities.get(0).getFullQualifiedName());
      assertTrue(source.getProcessFailedEntities().isEmpty());
    } finally {
      FieldUtils.writeField(
          GravitinoEnv.getInstance(), "internalJobOperationDispatcher", previous, true);
    }
  }

  private static JobTemplateEntity template() {
    JobTemplateEntity template = mock(JobTemplateEntity.class);
    when(template.id()).thenReturn(2L);
    when(template.name()).thenReturn("builtin-sparkpi");
    when(template.comment()).thenReturn("current description");
    return template;
  }

  private static JobTemplateEntity renamedTemplate() {
    JobTemplateEntity template = template();
    when(template.name()).thenReturn("renamed");
    return template;
  }

  private static void assertNames(
      InMemorySearchStorage storage, String query, Set<String> expected) {
    List<SearchEntitiesDTO> result =
        storage.search(
            "lake",
            null,
            ConditionBuilderVisitor.buildQueryCondition(query).getCondition(),
            List.of(),
            10,
            0);
    assertEquals(
        expected,
        result.stream()
            .flatMap(group -> group.getEntities().stream())
            .map(entity -> entity.getEntityName())
            .collect(Collectors.toSet()));
    assertEquals(expected.size(), result.stream().mapToInt(SearchEntitiesDTO::getTotalSize).sum());
  }
}
