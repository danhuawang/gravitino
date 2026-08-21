/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.search.parser.Condition;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
}
