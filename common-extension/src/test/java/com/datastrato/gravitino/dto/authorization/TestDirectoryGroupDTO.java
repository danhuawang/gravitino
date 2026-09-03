/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link DirectoryGroupDTO}. */
public class TestDirectoryGroupDTO {

  /** Verifies Local, Provisioned, and JIT mapping. */
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

    DirectoryGroupDTO jit =
        DirectoryGroupDTO.from(
            view("analysts", 0, IdentitySource.JIT, List.of("Contoso", "Northwind")));
    assertEquals("analysts", jit.name());
    assertEquals(0, jit.memberCount());
    assertEquals(IdentitySource.JIT, jit.origin());
    assertEquals(List.of("Contoso", "Northwind"), jit.metalakes());
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
