/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.relational;

import static org.apache.gravitino.Configs.STORE_DELETE_AFTER_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datastrato.gravitino.scim.storage.mapper.AbstractScimMetaStorageTest;
import com.datastrato.gravitino.scim.storage.mapper.ScimTokenMetaMapper;
import com.datastrato.gravitino.scim.storage.po.ScimTokenMetaPO;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.po.MetalakePO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestScimGarbageCollector extends AbstractScimMetaStorageTest {
  private static final String METALAKE_NAME = "test_metalake";
  private static final long METALAKE_ID = 10L;

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCollectAndClean(String type) throws Exception {
    init(type);
    insertMetalake();
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(1L)
                .withMetalakeId(METALAKE_ID)
                .withTokenName("legacy-token")
                .withTokenHash("hash-a")
                .withExpiresAt(0L)
                .withAuditInfo("{}")
                .withDeletedAt(System.currentTimeMillis() - 700_000L)
                .withUpdatedAt(0L)
                .build());
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(2L)
                .withMetalakeId(METALAKE_ID)
                .withTokenName("active-token")
                .withTokenHash("hash-b")
                .withExpiresAt(0L)
                .withAuditInfo("{}")
                .withDeletedAt(0L)
                .withUpdatedAt(0L)
                .build());

    getConfig().set(STORE_DELETE_AFTER_TIME, 600_000L);
    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.collectAndClean();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertNull(scimTokenMetaMapper().selectByTokenHash("hash-a"));
    assertEquals("active-token", scimTokenMetaMapper().selectByTokenHash("hash-b").getTokenName());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testSoftDeleteExpiredTokens(String type) throws Exception {
    init(type);
    insertMetalake();
    scimTokenMetaMapper()
        .insert(
            ScimTokenMetaPO.builder()
                .withTokenId(1L)
                .withMetalakeId(METALAKE_ID)
                .withTokenName("expired")
                .withTokenHash("hash-expired")
                .withExpiresAt(System.currentTimeMillis() - 1L)
                .withAuditInfo("{}")
                .withDeletedAt(0L)
                .withUpdatedAt(0L)
                .build());

    closeSession();

    ScimGarbageCollector garbageCollector = new ScimGarbageCollector(getConfig());
    try {
      garbageCollector.softDeleteExpiredTokens();
    } finally {
      garbageCollector.close();
    }

    reopenSession();
    assertNull(scimTokenMetaMapper().selectByTokenHash("hash-expired"));
  }

  private ScimTokenMetaMapper scimTokenMetaMapper() {
    return sharedSession.getMapper(ScimTokenMetaMapper.class);
  }

  private void insertMetalake() {
    MetalakeMetaMapper metalakeMetaMapper = sharedSession.getMapper(MetalakeMetaMapper.class);
    metalakeMetaMapper.insertMetalakeMeta(
        MetalakePO.builder()
            .withMetalakeId(METALAKE_ID)
            .withMetalakeName(METALAKE_NAME)
            .withAuditInfo("{}")
            .withSchemaVersion("1.0")
            .withCurrentVersion(1L)
            .withLastVersion(0L)
            .withDeletedAt(0L)
            .build());
  }
}
