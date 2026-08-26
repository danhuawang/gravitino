/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.parser.Condition;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
  void testMetalakeScopedEntitiesAreMatchedByName() {
    // Tags and policies live in the reserved system namespace and carry no full qualified name in
    // the index, so removing one has to match on the entity name.
    Condition condition =
        FilterConditionUtils.createEntityNameQueryCondition(
            NameIdentifierUtil.ofPolicy("metalake", "retention"), false);

    assertInstanceOf(Condition.TermCondition.class, condition);
    Condition.TermCondition term = (Condition.TermCondition) condition;
    assertEquals("entity_name.keyword", term.getField());
    assertEquals("retention", term.getValue());

    // Nothing lives below a tag or a policy, so cascade makes no difference.
    Condition tagCondition =
        FilterConditionUtils.createEntityNameQueryCondition(
            NameIdentifierUtil.ofTag("metalake", "pii"), true);
    assertInstanceOf(Condition.TermCondition.class, tagCondition);
    assertEquals("entity_name.keyword", ((Condition.TermCondition) tagCondition).getField());
    assertEquals("pii", ((Condition.TermCondition) tagCondition).getValue());
  }

  @Test
  void testHierarchicalEntitiesAreMatchedByFullQualifiedName() {
    Condition condition =
        FilterConditionUtils.createEntityNameQueryCondition(
            NameIdentifier.of("metalake", "catalog", "schema", "table"), false);

    assertInstanceOf(Condition.TermCondition.class, condition);
    Condition.TermCondition term = (Condition.TermCondition) condition;
    assertEquals("full_qualified_name.keyword", term.getField());
    assertEquals("catalog.schema.table", term.getValue());
  }
}
