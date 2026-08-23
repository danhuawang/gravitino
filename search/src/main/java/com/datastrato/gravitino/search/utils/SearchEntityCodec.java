/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import com.datastrato.gravitino.search.dto.SearchCatalogEntityDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.dto.SearchModelEntityDTO;
import com.datastrato.gravitino.search.dto.SearchPolicyEntityDTO;
import com.datastrato.gravitino.search.dto.SearchTableEntityDTO;
import com.datastrato.gravitino.search.dto.SearchViewEntityDTO;
import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchModelEntityPO;
import com.datastrato.gravitino.search.po.SearchPolicyEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.datastrato.gravitino.search.po.SearchViewEntityPO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.EnumFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.rel.types.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchEntityCodec {

  public static final Map<EntityType, Class<? extends SearchEntityPO>> ENTITY_TYPE_TO_CLASS =
      ImmutableMap.<EntityType, Class<? extends SearchEntityPO>>builder()
          .put(EntityType.CATALOG, SearchCatalogEntityPO.class)
          .put(EntityType.SCHEMA, SearchEntityPO.class)
          .put(EntityType.FILESET, SearchEntityPO.class)
          .put(EntityType.MODEL, SearchModelEntityPO.class)
          .put(EntityType.TOPIC, SearchEntityPO.class)
          .put(EntityType.TAG, SearchEntityPO.class)
          .put(EntityType.POLICY, SearchPolicyEntityPO.class)
          .put(EntityType.TABLE, SearchTableEntityPO.class)
          .put(EntityType.VIEW, SearchViewEntityPO.class)
          .put(EntityType.USER, SearchEntityPO.class)
          .put(EntityType.GROUP, SearchEntityPO.class)
          .put(EntityType.FUNCTION, SearchEntityPO.class)
          .put(EntityType.ROLE, SearchEntityPO.class)
          .build();

  public static final Map<EntityType, Class<? extends SearchEntityDTO>> ENTITY_TYPE_TO_CLASS_DTO =
      ImmutableMap.<EntityType, Class<? extends SearchEntityDTO>>builder()
          .put(EntityType.CATALOG, SearchCatalogEntityDTO.class)
          .put(EntityType.SCHEMA, SearchEntityDTO.class)
          .put(EntityType.FILESET, SearchEntityDTO.class)
          .put(EntityType.MODEL, SearchModelEntityDTO.class)
          .put(EntityType.TOPIC, SearchEntityDTO.class)
          .put(EntityType.TAG, SearchEntityDTO.class)
          .put(EntityType.POLICY, SearchPolicyEntityDTO.class)
          .put(EntityType.TABLE, SearchTableEntityDTO.class)
          .put(EntityType.VIEW, SearchViewEntityDTO.class)
          .put(EntityType.USER, SearchEntityDTO.class)
          .put(EntityType.GROUP, SearchEntityDTO.class)
          .put(EntityType.FUNCTION, SearchEntityDTO.class)
          .put(EntityType.ROLE, SearchEntityDTO.class)
          .build();

  private static final Logger LOG = LoggerFactory.getLogger(SearchEntityCodec.class);
  private final ObjectMapper objectMapper;

  public static final SearchEntityCodec INSTANCE = new SearchEntityCodec();

  public SearchEntityCodec() {
    // Create a new ObjectMapper specifically configured with PropertyNamingStrategies.SNAKE_CASE to
    // handle JSON field naming conversions.
    this.objectMapper =
        JsonMapper.builder()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(EnumFeature.WRITE_ENUMS_TO_LOWERCASE, true)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .registerModule(
                new SimpleModule()
                    .addDeserializer(Type.class, new JsonUtils.TypeDeserializer())
                    .addSerializer(Type.class, new JsonUtils.TypeSerializer()));
  }

  /**
   * Serializes a SearchEntityPO object to a JSON string.
   *
   * @param entity The SearchEntityPO object to serialize.
   * @return The JSON string representation of the object.
   */
  public String serialize(Object entity) {
    try {
      return objectMapper.writeValueAsString(entity);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialize SearchEntityPO: {}", entity, e);
      throw new RuntimeException("Serialization error", e);
    }
  }

  /**
   * Deserializes a JSON string to a SearchEntityDTO object.
   *
   * @param json The JSON string to deserialize.
   * @param clazz The class type of the SearchEntityDTO.
   * @param <T> The type of the SearchEntityDTO.
   * @return The deserialized SearchEntityDTO object.
   */
  public <T extends SearchEntityPO> T deserialize(String json, Class<T> clazz) {
    try {
      return objectMapper.readValue(json, clazz);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to deserialize JSON: {}", json, e);
      throw new RuntimeException("Deserialization error", e);
    }
  }

  public <T extends SearchEntityDTO> T convert(SearchEntityPO po, Class<T> clazz) {
    return objectMapper.convertValue(po, clazz);
  }

  public ObjectMapper objectMapper() {
    return objectMapper;
  }
}
