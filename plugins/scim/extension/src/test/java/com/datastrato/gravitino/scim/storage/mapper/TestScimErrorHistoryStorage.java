/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.scim.storage.po.ScimErrorHistoryPO;
import java.io.IOException;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.po.MetalakePO;
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
    assertEquals(0L, mapper.countByMetalake("test_metalake"));
    insertMetalake();
    mapper.insert(row(1L, "test_metalake", System.currentTimeMillis()));
    mapper.insert(row(2L, "missing", System.currentTimeMillis()));
    ScimErrorHistoryPO stored = mapper.selectByErrorId(1L);
    assertEquals(10L, stored.getMetalakeId());
    assertEquals("POST", stored.getHttpMethod());
    assertEquals("/scim/v2/metalakes/test_metalake/Users", stored.getRequestPath());
    assertEquals(409, stored.getHttpStatus());
    assertEquals("uniqueness", stored.getScimType());
    assertEquals("already exists", stored.getErrorDetail());
    assertEquals("entra", stored.getPrincipal());
    assertEquals(0L, mapper.selectByErrorId(2L).getMetalakeId());
    assertEquals(1L, mapper.countByMetalake("test_metalake"));
    assertEquals(0L, mapper.countByMetalake("missing"));
  }

  private void insertMetalake() {
    sharedSession
        .getMapper(MetalakeMetaMapper.class)
        .insertMetalakeMeta(
            MetalakePO.builder()
                .withMetalakeId(10L)
                .withMetalakeName("test_metalake")
                .withAuditInfo("{}")
                .withSchemaVersion("1.0")
                .withCurrentVersion(1L)
                .withLastVersion(0L)
                .withDeletedAt(0L)
                .build());
  }

  private static ScimErrorHistoryPO row(long errorId, String metalakeName, long createdAt) {
    return ScimErrorHistoryPO.builder()
        .withErrorId(errorId)
        .withMetalakeId(0L)
        .withMetalakeName(metalakeName)
        .withHttpMethod("POST")
        .withRequestPath("/scim/v2/metalakes/" + metalakeName + "/Users")
        .withHttpStatus(409)
        .withScimType("uniqueness")
        .withErrorDetail("already exists")
        .withPrincipal("entra")
        .withCreatedAt(createdAt)
        .build();
  }
}
