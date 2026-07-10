/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.po;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScimGroupMemberPO {

  @Test
  void testBuilderAndEquality() {
    ScimGroupMemberPO member =
        ScimGroupMemberPO.builder()
            .withExternalId("756b18d2-023a-4fa8-845e-9ac8b524100f")
            .withUserName("alice@corp.com")
            .build();
    ScimGroupMemberPO sameMember =
        ScimGroupMemberPO.builder()
            .withExternalId("756b18d2-023a-4fa8-845e-9ac8b524100f")
            .withUserName("alice@corp.com")
            .build();

    Assertions.assertEquals("756b18d2-023a-4fa8-845e-9ac8b524100f", member.getExternalId());
    Assertions.assertEquals("alice@corp.com", member.getUserName());
    Assertions.assertEquals(member, sameMember);
  }
}
