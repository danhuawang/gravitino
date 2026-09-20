/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.scim.ScimFieldLimits;
import com.datastrato.gravitino.scim.storage.mapper.AbstractScimMetaStorageTest;
import com.datastrato.gravitino.scim.storage.mapper.JitIdentityMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H2 checks that JIT identity names are validated before a snapshot is stored. */
class TestJitStoredFieldLength extends AbstractScimMetaStorageTest {
  private static final String TEST_AUDIT_INFO = "{}";

  private final AtomicLong nextId = new AtomicLong(1000L);
  private final IdGenerator idGenerator = nextId::incrementAndGet;
  private JitIdentityMapper mapper;

  @BeforeEach
  void setUp() throws IOException {
    init("h2");
  }

  @Override
  protected void initializeMappers() {
    mapper = sharedSession.getMapper(JitIdentityMapper.class);
  }

  @Test
  void testExactLimitNamesAreStored() {
    String userName = "u".repeat(ScimFieldLimits.MAX_NAME_LENGTH);
    String groupName = "g".repeat(ScimFieldLimits.MAX_NAME_LENGTH);

    synchronize(userName, List.of(groupName));

    assertEquals(userName, mapper.selectUserByName(userName).getUserName());
    assertEquals(groupName, mapper.selectGroupsByNames(List.of(groupName)).get(0).getGroupName());
  }

  @Test
  void testTooLongUserNameIsRejectedBeforeInsert() {
    String userName = "u".repeat(ScimFieldLimits.MAX_NAME_LENGTH + 1);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> synchronize(userName, List.of()));
    assertEquals(
        "The userName of the JIT identity must not exceed 128 characters", exception.getMessage());
    assertNull(mapper.selectUserByName(userName));
  }

  @Test
  void testBlankUserNameIsRejectedBeforeInsert() {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> synchronize(" \t ", List.of("group")));
    assertEquals("The userName of the JIT identity must not be blank", exception.getMessage());
    assertEquals(0, mapper.selectGroupsByNames(List.of("group")).size());
  }

  @Test
  void testUntrimmedUserNameIsRejectedBeforeInsert() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> synchronize(" alice ", List.of("group")));
    assertEquals(
        "The userName of the JIT identity must not have leading or trailing whitespace",
        exception.getMessage());
    assertNull(mapper.selectUserByName(" alice "));
    assertNull(mapper.selectUserByName("alice"));
    assertEquals(0, mapper.selectGroupsByNames(List.of("group")).size());
  }

  @Test
  void testTooLongGroupNameDoesNotChangeSnapshot() {
    synchronize("alice", List.of("existing"));
    String groupName = "g".repeat(ScimFieldLimits.MAX_NAME_LENGTH + 1);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> synchronize("alice", List.of(groupName)));
    assertEquals(
        "The groupName of the JIT identity must not exceed 128 characters", exception.getMessage());
    assertEquals(1, mapper.selectGroupsByNames(List.of("existing")).size());
    assertEquals(0, mapper.selectGroupsByNames(List.of(groupName)).size());
    assertEquals(
        1, mapper.selectGroupIdsForUser(mapper.selectUserByName("alice").getUserId()).size());
  }

  private void synchronize(String userName, List<String> groupNames) {
    JitIdentityMetaService.getInstance()
        .synchronize(userName, groupNames, 1000L, TEST_AUDIT_INFO, idGenerator);
  }
}
