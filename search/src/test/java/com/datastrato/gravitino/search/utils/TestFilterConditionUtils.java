/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.parser.Condition;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.query_dsl.Query;

class TestFilterConditionUtils {

  @Test
  void testNestedFilterIgnoresUnmappedPaths() {
    Query query =
        FilterConditionUtils.convert(
            new Condition.InCondition("tag_name", ImmutableList.of("pii")),
            ImmutableMap.of("tag_name", "tags"),
            ImmutableMap.of("tag_name", "tags.tag_name.keyword"));

    assertTrue(query.isNested());
    assertTrue(query.nested().ignoreUnmapped());
  }

  @Test
  void testMetalakeScopedEntitiesAreMatchedByNameAndType() {
    // Tags, policies, jobs and job templates live in the reserved system namespace and carry no
    // full qualified name in the index, so removing one has to match on the entity name. They all
    // share the metalake's index scope and may share a name, so the type has to be matched too.
    assertNameAndType(
        FilterConditionUtils.createEntityNameQueryCondition(
            NameIdentifierUtil.ofPolicy("metalake", "retention"), false, EntityType.POLICY),
        "policy",
        "retention");

    // Nothing lives below a tag or a policy, so cascade makes no difference.
    assertNameAndType(
        FilterConditionUtils.createEntityNameQueryCondition(
            NameIdentifierUtil.ofTag("metalake", "pii"), true, EntityType.TAG),
        "tag",
        "pii");

    // A job template named like the tag above must not be matched by the tag's condition.
    assertNameAndType(
        FilterConditionUtils.createEntityNameQueryCondition(
            NameIdentifierUtil.ofJobTemplate("metalake", "pii"), false, EntityType.JOB_TEMPLATE),
        "job_template",
        "pii");
  }

  private static void assertNameAndType(
      Condition condition, String expectedType, String expectedName) {
    assertInstanceOf(Condition.AndCondition.class, condition);
    List<Condition> parts = ((Condition.AndCondition) condition).getConditions();
    assertEquals(2, parts.size());

    Condition.TermCondition type = (Condition.TermCondition) parts.get(0);
    assertEquals("entity_type", type.getField());
    assertEquals(expectedType, type.getValue());

    Condition.TermCondition name = (Condition.TermCondition) parts.get(1);
    assertEquals("entity_name.keyword", name.getField());
    assertEquals(expectedName, name.getValue());
  }

  @Test
  void testHierarchicalEntitiesAreMatchedByFullQualifiedName() {
    Condition condition =
        FilterConditionUtils.createEntityNameQueryCondition(
            NameIdentifier.of("metalake", "catalog", "schema", "table"), false, EntityType.TABLE);

    assertInstanceOf(Condition.TermCondition.class, condition);
    Condition.TermCondition term = (Condition.TermCondition) condition;
    assertEquals("full_qualified_name.keyword", term.getField());
    assertEquals("catalog.schema.table", term.getValue());
  }
}
