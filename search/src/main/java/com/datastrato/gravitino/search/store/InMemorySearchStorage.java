/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.store;

import static com.datastrato.gravitino.search.utils.SearchEntityCodec.ENTITY_TYPE_TO_CLASS_DTO;
import static java.util.stream.Collectors.toList;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.parser.Condition.RangeType;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.SearchEntityCodec;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
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

  private final Map<Long, SearchEntityPO> searchEntityMap = new ConcurrentHashMap<>();
  private SearchEntityCodec codec;

  public List<SearchEntityPO> getSearchEntities() {
    return new ArrayList<>(searchEntityMap.values());
  }

  @Override
  public void initialize(Config config) {
    this.codec = SearchEntityCodec.INSTANCE;
  }

  @Override
  public void write(List<SearchEntityPO> entities, WriteContext context) {
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
              .collect(toList());

      result.add(
          SearchEntitiesDTO.builder()
              .withEntities(groupedEntityDTOs)
              .withType(entityType)
              .withTotalSize(groupedEntityDTOs.size())
              .build());
    }
    return result;
  }

  @Override
  public SearchDataSource search(
      String metalake, String keyword, Condition filter, List<String> fields) {
    return new InMemorySearchDataSource(
        search(metalake, keyword, filter, fields, Integer.MAX_VALUE, 0));
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
      if (field.equals("full_qualified_name") || field.equals("full_qualified_name.keyword")) {
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
      if (field.equals("full_qualified_name") || field.equals("full_qualified_name.keyword")) {
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

    if (condition instanceof Condition.RangeCondition) {
      Condition.RangeCondition rangeCondition = (Condition.RangeCondition) condition;
      String field = rangeCondition.getField();
      RangeType rangeType = rangeCondition.getRangeType();

      if (field.equals("update_time")) {
        long value = Long.valueOf(rangeCondition.getValue());
        return entity -> {
          switch (rangeType) {
            case GRATER_EQUAL:
              return entity.getUpdateTime() >= value;
            case GREATER:
              return entity.getUpdateTime() > value;
            case LESS_EQUAL:
              return entity.getUpdateTime() <= value;
            case LESS:
              return entity.getUpdateTime() < value;
            default:
              throw new IllegalArgumentException("Unsupported range type: " + rangeType);
          }
        };
      }
    }

    throw new IllegalArgumentException("Unsupported condition type: " + condition.getClass());
  }

  static class InMemorySearchDataSource implements SearchDataSource {

    private final List<SearchEntitiesDTO> searchResults;
    private final Iterator<SearchEntitiesDTO> iterator;

    public InMemorySearchDataSource(List<SearchEntitiesDTO> dtoList) {
      this.searchResults = dtoList;
      iterator = searchResults.iterator();
    }

    @Override
    public Result nextBatch() {
      if (iterator.hasNext()) {
        SearchEntitiesDTO next = iterator.next();
        return new Result(
            next.getEntities().stream().map(dto -> (SearchEntityDTO) dto).collect(toList()),
            next.getType());
      }
      return Result.empty();
    }
  }
}
