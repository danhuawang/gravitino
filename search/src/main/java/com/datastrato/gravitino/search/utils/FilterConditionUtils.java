/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.utils;

import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.parser.Condition.AndCondition;
import com.datastrato.gravitino.search.parser.Condition.InCondition;
import com.datastrato.gravitino.search.parser.Condition.NotCondition;
import com.datastrato.gravitino.search.parser.Condition.OrCondition;
import com.datastrato.gravitino.search.parser.Condition.PrefixCondition;
import com.datastrato.gravitino.search.parser.Condition.RangeCondition;
import com.datastrato.gravitino.search.parser.Condition.RangeType;
import com.datastrato.gravitino.search.parser.Condition.TermCondition;
import com.google.common.collect.ImmutableList;
import java.util.Map;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.PrefixQuery;
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
    } else if (condition instanceof PrefixCondition) {
      return convertPrefix((PrefixCondition) condition, nestedFields, keywordFields);
    } else if (condition instanceof RangeCondition) {
      return convertRange((RangeCondition) condition, nestedFields, keywordFields);
    }

    throw new IllegalArgumentException("Unsupported condition type: " + condition.getClass());
  }

  private static Query wrapNestedIfNeeded(
      String field, String exactField, Query innerQuery, Map<String, String> nestedFieldMap) {
    String nestedRoot = nestedFieldMap.get(field);
    if (nestedRoot != null) {
      if (exactField.startsWith(nestedRoot + ".")) {
        String nestedPath = nestedFieldMap.get(field).split("\\.")[0];
        return new Query.Builder()
            .nested(n -> n.path(nestedPath).ignoreUnmapped(true).query(innerQuery))
            .build();
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

  private static Query convertRange(
      RangeCondition range,
      Map<String, String> nestedFieldMap,
      Map<String, String> keywordFieldMap) {
    String exactField = keywordFieldMap.getOrDefault(range.getField(), range.getField());

    BoolQuery.Builder bool = new BoolQuery.Builder();

    RangeType rangeType = range.getRangeType();
    long value = Long.valueOf(range.getValue());
    switch (rangeType) {
      case GREATER:
        bool.must(m -> m.range(r -> r.field(exactField).gt(JsonData.of(value))));
        break;
      case GRATER_EQUAL:
        bool.must(m -> m.range(r -> r.field(exactField).gte(JsonData.of(value))));
        break;
      case LESS:
        bool.must(m -> m.range(r -> r.field(exactField).lt(JsonData.of(value))));
        break;
      case LESS_EQUAL:
        bool.must(m -> m.range(r -> r.field(exactField).lte(JsonData.of(value))));
        break;
      default:
        throw new IllegalArgumentException("Unsupported range type: " + rangeType);
    }

    Query rangeQuery = new Query.Builder().bool(bool.build()).build();
    return wrapNestedIfNeeded(range.getField(), exactField, rangeQuery, nestedFieldMap);
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

  private static Query convertPrefix(
      PrefixCondition prefixCondition,
      Map<String, String> nestedFieldMap,
      Map<String, String> keywordFieldMap) {
    String exactField =
        keywordFieldMap.getOrDefault(prefixCondition.getField(), prefixCondition.getField());
    PrefixQuery.Builder prefix = new PrefixQuery.Builder();
    prefix
        .field(keywordFieldMap.getOrDefault(prefixCondition.getField(), prefixCondition.getField()))
        .value(prefixCondition.getValue());
    return wrapNestedIfNeeded(
        prefixCondition.getField(), exactField, prefix.build().toQuery(), nestedFieldMap);
  }

  public static Condition createEntityNameQueryCondition(
      NameIdentifier nameIdentifier, boolean cascade) {
    if (isMetalakeScoped(nameIdentifier)) {
      // Tags, policies and the other entities living directly under a metalake have no
      // catalog/schema hierarchy, so their documents carry no meaningful full qualified name and
      // the index does not map one. They are identified by their name instead. There is nothing
      // below them either, so the cascade flag makes no difference.
      return new Condition.TermCondition("entity_name.keyword", nameIdentifier.name());
    }

    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    String fqName = nameIdentifier.toString().replace(metalake + ".", "");

    Condition fullQualifiedNameCondition =
        new Condition.TermCondition("full_qualified_name.keyword", fqName);

    if (!cascade) {
      // search entity with cascade false
      return fullQualifiedNameCondition;
    } else {
      // search entity with cascade true
      Condition prefixCondition =
          new Condition.PrefixCondition("full_qualified_name.keyword", fqName + ".");
      return new Condition.OrCondition(
          ImmutableList.of(fullQualifiedNameCondition, prefixCondition));
    }
  }

  public static Condition createUpdateTimeCondition(long updateTime) {
    return Condition.less("update_time", String.valueOf(updateTime));
  }

  /**
   * Returns whether the identifier addresses an entity that lives directly under a metalake, such
   * as a tag or a policy. Those are held in the reserved system namespace, {@code
   * <metalake>.system.<kind>.<name>}, rather than under a catalog.
   *
   * @param nameIdentifier The entity identifier.
   * @return Whether the entity is scoped to the metalake itself.
   */
  private static boolean isMetalakeScoped(NameIdentifier nameIdentifier) {
    Namespace namespace = nameIdentifier.namespace();
    return namespace.length() == 3
        && Entity.SYSTEM_CATALOG_RESERVED_NAME.equals(namespace.level(1));
  }

  public static Condition createRemovedEntityCondition(
      NameIdentifier nameIdentifier, boolean cascade, long lastUpdateTime) {
    Condition entityCondition = createEntityNameQueryCondition(nameIdentifier, cascade);
    return new Condition.AndCondition(
        ImmutableList.of(entityCondition, createUpdateTimeCondition(lastUpdateTime)));
  }
}
