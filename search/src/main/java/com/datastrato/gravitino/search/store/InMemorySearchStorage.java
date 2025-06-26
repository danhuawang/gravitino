/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store;

import com.datastrato.gravitino.search.dto.SearchCatalogEntityDTO;
import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchTableEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;

/**
 * InMemorySearchStorage is an in-memory implementation of SearchStorage. It is used for development
 * and tests.
 */
public class InMemorySearchStorage implements SearchStorage {

  private static final Map<Entity.EntityType, Class<? extends SearchEntityDTO>>
      ENTITY_TYPE_TO_CLASS_DTO =
          ImmutableMap.of(
              Entity.EntityType.CATALOG, SearchCatalogEntityDTO.class,
              Entity.EntityType.SCHEMA, SearchEntityDTO.class,
              Entity.EntityType.FILESET, SearchEntityDTO.class,
              Entity.EntityType.MODEL, SearchEntityDTO.class,
              Entity.EntityType.TOPIC, SearchEntityDTO.class,
              Entity.EntityType.TABLE, SearchTableEntityDTO.class);

  private Map<Long, SearchEntityPO> searchEntityMap = new ConcurrentHashMap<>();
  private SearchEntityCodec codec;

  public List<SearchEntityPO> getSearchEntities() {
    return new ArrayList<>(searchEntityMap.values());
  }

  @Override
  public void initialize(Config config) {
    this.codec = new SearchEntityCodec();
  }

  @Override
  public void write(List<SearchEntityPO> entities) {
    for (SearchEntityPO entity : entities) {
      searchEntityMap.put(entity.getEntityId(), entity);
    }
  }

  @Override
  public void delete(String metalake, List<Long> entityIds, Entity.EntityType entityType) {
    for (Long entityId : entityIds) {
      searchEntityMap.remove(entityId);
    }
  }

  @Override
  public List<SearchEntitiesDTO> search(
      String metalake,
      String keyword,
      Condition filter,
      List<String> fields,
      int pageSize,
      int pageNum) {
    int offset = pageNum * pageSize;

    Predicate<SearchEntityPO> predicate = buildFilter(filter);
    Map<Entity.EntityType, List<SearchEntityPO>> groupEntities =
        searchEntityMap.values().stream()
            .filter(predicate)
            .filter(
                en -> {
                  if (metalake != null && !metalake.isEmpty()) {
                    return en.getMetalake().equals(metalake);
                  }
                  return true;
                })
            .filter(
                en -> {
                  if (keyword != null && !keyword.isEmpty()) {
                    return en.getEntityName().contains(keyword)
                        || en.getEntityComment().contains(keyword)
                        || en.getFullQualifiedName().contains(keyword);
                  }
                  return true;
                })
            .collect(Collectors.groupingBy(SearchEntityPO::getEntityType));

    List<SearchEntitiesDTO> result = new ArrayList<>();
    for (Map.Entry<Entity.EntityType, List<SearchEntityPO>> entry : groupEntities.entrySet()) {
      Entity.EntityType entityType = entry.getKey();
      List<SearchEntityPO> entities = entry.getValue();

      List<? extends SearchEntityDTO> groupedEntityDTOs =
          entities.stream()
              .map(
                  en -> {
                    try {
                      return codec.convert(en, ENTITY_TYPE_TO_CLASS_DTO.get(entityType));
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .skip(offset)
              .limit(pageSize)
              .collect(Collectors.toList());

      result.add(
          SearchEntitiesDTO.builder()
              .withEntities(groupedEntityDTOs)
              .withType(entityType)
              .withTotalSize(groupedEntityDTOs.size())
              .build());
    }
    return result;
  }

  @VisibleForTesting
  public void clear() {
    searchEntityMap.clear();
  }

  @Override
  public void close() throws IOException {}

  Predicate<SearchEntityPO> buildFilter(Condition condition) {
    if (condition == null) {
      return entity -> true;
    }

    if (condition instanceof Condition.AndCondition) {
      List<Condition> conditions = ((Condition.AndCondition) condition).getConditions();
      return conditions.stream()
          .map(this::buildFilter)
          .reduce(Predicate::and)
          .orElse(entity -> true);
    }

    if (condition instanceof Condition.OrCondition) {
      List<Condition> conditions = ((Condition.OrCondition) condition).getConditions();
      return conditions.stream()
          .map(this::buildFilter)
          .reduce(Predicate::or)
          .orElse(entity -> true);
    }

    if (condition instanceof Condition.TermCondition) {
      Condition.TermCondition termCondition = (Condition.TermCondition) condition;
      String field = termCondition.getField();
      String value = termCondition.getValue();
      if (field.equals("full_qualified_name")) {
        return entity -> value.equals(entity.getFullQualifiedName());
      }
      if (field.equals("entity_type")) {
        return entity -> value.equals(entity.getEntityType());
      }
      if (field.equals("metalake")) {
        return entity -> value.equals(entity.getMetalake());
      }
    }

    if (condition instanceof Condition.PrefixCondition) {
      Condition.PrefixCondition prefixCondition = (Condition.PrefixCondition) condition;
      String field = prefixCondition.getField();
      String value = prefixCondition.getValue();
      if (field.equals("full_qualified_name")) {
        return entity -> entity.getFullQualifiedName().startsWith(value);
      }
    }

    if (condition instanceof Condition.InCondition) {
      Condition.InCondition inCondition = (Condition.InCondition) condition;
      String field = inCondition.getField();
      List<String> values = inCondition.getValues();
      if (field.equals("tag_name")) {
        return entity -> {
          Set<String> tags =
              entity.getTags().stream()
                  .map(SearchEntityPO.SearchTagPO::getTagName)
                  .collect(Collectors.toSet());
          return tags.containsAll(values);
        };
      }
    }

    throw new IllegalArgumentException("Unsupported condition type: " + condition.getClass());
  }
}
