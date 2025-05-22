/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.parser.Condition.AndCondition;
import com.datastrato.gravitino.search.parser.Condition.InCondition;
import com.datastrato.gravitino.search.parser.Condition.NotCondition;
import com.datastrato.gravitino.search.parser.Condition.OrCondition;
import com.datastrato.gravitino.search.parser.Condition.TermCondition;
import java.util.Map;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;

public class FilterConditionUtils {

  private FilterConditionUtils() {
    // Utility class, no instantiation
  }

  public static Query convert(
      Condition condition, Map<String, String> nestedFields, Map<String, String> keywordFields) {
    if (condition instanceof TermCondition) {
      return convertTerm((TermCondition) condition, nestedFields, keywordFields);
    } else if (condition instanceof InCondition) {
      return convertIn((InCondition) condition, nestedFields, keywordFields);
    } else if (condition instanceof NotCondition) {
      return convertNot((NotCondition) condition, nestedFields, keywordFields);
    } else if (condition instanceof AndCondition) {
      return convertAnd((AndCondition) condition, nestedFields, keywordFields);
    } else if (condition instanceof OrCondition) {
      return convertOr((OrCondition) condition, nestedFields, keywordFields);
    }

    throw new IllegalArgumentException("Unsupported condition type: " + condition.getClass());
  }

  private static Query wrapNestedIfNeeded(
      String field, String exactField, Query innerQuery, Map<String, String> nestedFieldMap) {
    String nestedRoot = nestedFieldMap.get(field);
    if (nestedRoot != null) {
      if (exactField.startsWith(nestedRoot + ".")) {
        String nestedPath = nestedFieldMap.get(field).split("\\.")[0];
        return new Query.Builder().nested(n -> n.path(nestedPath).query(innerQuery)).build();
      }

      throw new IllegalArgumentException(
          "Field " + field + " is not a nested field, but was used in a nested query");
    }

    return innerQuery;
  }

  private static Query convertTerm(
      TermCondition term, Map<String, String> nestedFieldMap, Map<String, String> keywordFieldMap) {
    String exactField = keywordFieldMap.getOrDefault(term.getField(), term.getField());
    Query termQuery =
        new Query.Builder()
            .term(t -> t.field(exactField).value(FieldValue.of(term.getValue())))
            .build();
    return wrapNestedIfNeeded(term.getField(), exactField, termQuery, nestedFieldMap);
  }

  private static Query convertIn(
      InCondition in, Map<String, String> nestedFieldMap, Map<String, String> keywordFieldMap) {
    String exactField = keywordFieldMap.getOrDefault(in.getField(), in.getField());

    BoolQuery.Builder bool = new BoolQuery.Builder();
    in.getValues()
        .forEach(
            value ->
                bool.should(s -> s.term(t -> t.field(exactField).value(FieldValue.of(value)))));
    Query inQuery = new Query.Builder().bool(bool.build()).build();
    return wrapNestedIfNeeded(in.getField(), exactField, inQuery, nestedFieldMap);
  }

  private static Query convertNot(
      NotCondition not, Map<String, String> nestedFieldMap, Map<String, String> keywordFieldMap) {
    return new Query.Builder()
        .bool(b -> b.mustNot(convert(not.getCondition(), nestedFieldMap, keywordFieldMap)))
        .build();
  }

  private static Query convertAnd(
      AndCondition and, Map<String, String> nestedFieldMap, Map<String, String> keywordFieldMap) {
    BoolQuery.Builder bool = new BoolQuery.Builder();
    and.getConditions().forEach(cond -> bool.must(convert(cond, nestedFieldMap, keywordFieldMap)));
    return new Query.Builder().bool(bool.build()).build();
  }

  private static Query convertOr(
      OrCondition or, Map<String, String> nestedFieldMap, Map<String, String> keywordFieldMap) {
    BoolQuery.Builder bool = new BoolQuery.Builder();
    or.getConditions().forEach(cond -> bool.should(convert(cond, nestedFieldMap, keywordFieldMap)));

    return new Query.Builder().bool(bool.minimumShouldMatch("1").build()).build();
  }
}
