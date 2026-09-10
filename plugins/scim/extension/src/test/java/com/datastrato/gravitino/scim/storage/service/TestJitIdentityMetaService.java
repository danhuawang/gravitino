/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.mapper.AbstractScimUserGroupRelStorageTest;
import com.datastrato.gravitino.scim.storage.mapper.JitIdentityMapper;
import com.datastrato.gravitino.scim.storage.po.JitGroupPO;
import com.datastrato.gravitino.scim.storage.po.JitUserPO;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.gravitino.storage.IdGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("gravitino-docker-test")
class TestJitIdentityMetaService extends AbstractScimUserGroupRelStorageTest {
  private static final long FIRST_OBSERVED_AT = 1000L;
  private static final long SECOND_OBSERVED_AT = 2000L;

  private final AtomicLong nextId = new AtomicLong(1000L);
  private final IdGenerator idGenerator = nextId::incrementAndGet;
  private JitIdentityMapper jitIdentityMapper;

  @Override
  protected void initializeMappers() {
    super.initializeMappers();
    jitIdentityMapper = sharedSession.getMapper(JitIdentityMapper.class);
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testCreatesAndUpdatesJitSnapshot(String type) throws IOException {
    init(type);

    synchronize("alice", List.of("engineering", "platform"), FIRST_OBSERVED_AT);

    JitUserPO user = jitIdentityMapper.selectUserByName("alice");
    assertEquals(
        Set.of("engineering", "platform"),
        activeGroups(List.of("engineering", "platform")).stream()
            .map(JitGroupPO::getGroupName)
            .collect(Collectors.toSet()));
    assertEquals(2, jitIdentityMapper.selectGroupIdsForUser(user.getUserId()).size());

    synchronize("ALICE", List.of("platform", "analytics"), SECOND_OBSERVED_AT);

    user = jitIdentityMapper.selectUserByName("alice");
    Set<Long> desiredGroupIds =
        activeGroups(List.of("platform", "analytics")).stream()
            .map(JitGroupPO::getGroupId)
            .collect(Collectors.toSet());
    assertEquals(
        desiredGroupIds, Set.copyOf(jitIdentityMapper.selectGroupIdsForUser(user.getUserId())));
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testScimUserSupersedesJitUser(String type) throws IOException {
    init(type);
    synchronize("alice", List.of("engineering"), FIRST_OBSERVED_AT);
    JitUserPO jitUser = jitIdentityMapper.selectUserByName("alice");

    insertUser(USER_ID, "ALICE");
    synchronize("alice", List.of("engineering"), SECOND_OBSERVED_AT);

    assertNull(jitIdentityMapper.selectUserByName("alice"));
    assertTrue(jitIdentityMapper.selectGroupIdsForUser(jitUser.getUserId()).isEmpty());
  }

  @ParameterizedTest
  @MethodSource("storageProvider")
  void testScimGroupIsNotRecordedAsJit(String type) throws IOException {
    init(type);
    insertGroup(GROUP_ID, "Engineering");

    synchronize("alice", List.of("engineering", "platform"), FIRST_OBSERVED_AT);

    assertTrue(activeGroups(List.of("engineering")).isEmpty());
    List<JitGroupPO> groups = activeGroups(List.of("platform"));
    assertEquals(1, groups.size());
    JitUserPO user = jitIdentityMapper.selectUserByName("alice");
    assertEquals(
        Set.of(groups.get(0).getGroupId()),
        Set.copyOf(jitIdentityMapper.selectGroupIdsForUser(user.getUserId())));
  }

  private List<JitGroupPO> activeGroups(List<String> groupNames) {
    return jitIdentityMapper.selectGroupsByNames(groupNames);
  }

  private void synchronize(String userName, List<String> groups, long observedAt)
      throws IOException {
    closeSession();
    JitIdentityMetaService.getInstance()
        .synchronize(userName, groups, observedAt, TEST_AUDIT_INFO, idGenerator);
    reopenSession();
  }
}
