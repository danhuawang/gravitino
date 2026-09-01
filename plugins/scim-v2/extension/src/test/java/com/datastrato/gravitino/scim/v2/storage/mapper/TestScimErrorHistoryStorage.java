/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.scim.v2.storage.po.ScimErrorHistoryPO;
import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimErrorHistoryStorage extends AbstractScimMetaStorageTest {

  private ScimErrorHistoryMapper mapper;

  @Override
  protected void initializeMappers() {
    mapper = sharedSession.getMapper(ScimErrorHistoryMapper.class);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testInsertAndCount(String type) throws IOException {
    init(type);
    assertEquals(0L, mapper.countAll());
    mapper.insert(row(1L, System.currentTimeMillis()));
    mapper.insert(row(2L, System.currentTimeMillis()));
    assertEquals(2L, mapper.countAll());
  }

  private static ScimErrorHistoryPO row(long errorId, long createdAt) {
    return ScimErrorHistoryPO.builder()
        .withErrorId(errorId)
        .withHttpMethod("POST")
        .withRequestPath("/scim/v2/Users")
        .withHttpStatus(409)
        .withScimType("uniqueness")
        .withErrorDetail("already exists")
        .withPrincipal("entra")
        .withCreatedAt(createdAt)
        .build();
  }
}
