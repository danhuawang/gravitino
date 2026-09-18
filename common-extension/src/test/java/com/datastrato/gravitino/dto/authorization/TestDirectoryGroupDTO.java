/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link DirectoryGroupDTO}. */
public class TestDirectoryGroupDTO {

  /** Verifies Local, Provisioned, and metalake-only mapping. */
  @Test
  public void testFromDirectoryGroupView() {
    DirectoryGroupDTO local =
        DirectoryGroupDTO.from(
            view("governance", 2, IdentitySource.LOCAL, List.of("Acme", "Contoso")));
    assertEquals("governance", local.name());
    assertEquals(2, local.memberCount());
    assertEquals(IdentitySource.LOCAL, local.origin());
    assertEquals(List.of("Acme", "Contoso"), local.metalakes());

    DirectoryGroupDTO provisioned =
        DirectoryGroupDTO.from(view("platform", 1, IdentitySource.PROVISIONED, List.of("Contoso")));
    assertEquals("platform", provisioned.name());
    assertEquals(1, provisioned.memberCount());
    assertEquals(IdentitySource.PROVISIONED, provisioned.origin());

    DirectoryGroupDTO metalakeOnly =
        DirectoryGroupDTO.from(view("analysts", 0, null, List.of("Contoso", "Northwind")));
    assertEquals("analysts", metalakeOnly.name());
    assertEquals(0, metalakeOnly.memberCount());
    assertNull(metalakeOnly.origin());
    assertEquals(List.of("Contoso", "Northwind"), metalakeOnly.metalakes());
  }

  private static DirectoryGroupDTO.DirectoryGroupView view(
      String name, int memberCount, IdentitySource origin, List<String> metalakes) {
    return new DirectoryGroupDTO.DirectoryGroupView() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public int memberCount() {
        return memberCount;
      }

      @Override
      public IdentitySource origin() {
        return origin;
      }

      @Override
      public List<String> metalakes() {
        return metalakes;
      }
    };
  }
}
