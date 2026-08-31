/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.storage.po;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScimGroupMemberPO {

  @Test
  void testBuilderAndEquality() {
    ScimGroupMemberPO member =
        ScimGroupMemberPO.builder().withUserId(100L).withUserName("alice@corp.com").build();
    ScimGroupMemberPO sameMember =
        ScimGroupMemberPO.builder().withUserId(100L).withUserName("alice@corp.com").build();

    Assertions.assertEquals(100L, member.getUserId());
    Assertions.assertEquals("alice@corp.com", member.getUserName());
    Assertions.assertEquals(member, sameMember);
  }
}
