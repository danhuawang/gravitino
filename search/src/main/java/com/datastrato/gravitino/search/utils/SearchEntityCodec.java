/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.EnumFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.rel.types.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchEntityCodec {

  private static final Logger LOG = LoggerFactory.getLogger(SearchEntityCodec.class);
  private final ObjectMapper objectMapper;

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
            .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
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
  public String serialize(SearchEntityPO entity) {
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
