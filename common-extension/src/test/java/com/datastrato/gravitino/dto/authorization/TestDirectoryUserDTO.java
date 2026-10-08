/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link DirectoryUserDTO}. */
public class TestDirectoryUserDTO {

  /** Verifies Local, Provisioned, and metalake-only mapping. */
  @Test
  public void testFromDirectoryUserView() {
    DirectoryUserDTO local =
        DirectoryUserDTO.from(
            view(
                "sam.o",
                true,
                IdentitySource.LOCAL,
                List.of("governance"),
                List.of("Contoso"),
                true,
                true));
    assertEquals("sam.o", local.name());
    assertTrue(local.enabled());
    assertEquals(IdentitySource.LOCAL, local.origin());
    assertEquals(List.of("governance"), local.groups());
    assertEquals(List.of("Contoso"), local.metalakes());
    assertTrue(local.canDelete());
    assertTrue(local.canSuspend());

    DirectoryUserDTO provisioned =
        DirectoryUserDTO.from(
            view(
                "dana.k",
                false,
                IdentitySource.PROVISIONED,
                List.of("finance"),
                List.of("Acme", "Contoso"),
                false,
                false));
    assertEquals("dana.k", provisioned.name());
    assertFalse(provisioned.enabled());
    assertEquals(IdentitySource.PROVISIONED, provisioned.origin());
    assertEquals(List.of("finance"), provisioned.groups());
    assertEquals(List.of("Acme", "Contoso"), provisioned.metalakes());
    assertFalse(provisioned.canDelete());
    assertFalse(provisioned.canSuspend());

    DirectoryUserDTO metalakeOnly =
        DirectoryUserDTO.from(
            view("jordan.m", true, null, List.of(), List.of("Contoso", "Northwind"), false, false));
    assertEquals("jordan.m", metalakeOnly.name());
    assertTrue(metalakeOnly.enabled());
    assertNull(metalakeOnly.origin());
    assertTrue(metalakeOnly.groups().isEmpty());
    assertEquals(List.of("Contoso", "Northwind"), metalakeOnly.metalakes());
    assertFalse(metalakeOnly.canDelete());
    assertFalse(metalakeOnly.canSuspend());
  }

  private static DirectoryUserDTO.DirectoryUserView view(
      String name,
      boolean enabled,
      IdentitySource origin,
      List<String> groups,
      List<String> metalakes,
      boolean canDelete,
      boolean canSuspend) {
    return new DirectoryUserDTO.DirectoryUserView() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public boolean enabled() {
        return enabled;
      }

      @Override
      public IdentitySource origin() {
        return origin;
      }

      @Override
      public List<String> groups() {
        return groups;
      }

      @Override
      public List<String> metalakes() {
        return metalakes;
      }

      @Override
      public boolean canDelete() {
        return canDelete;
      }

      @Override
      public boolean canSuspend() {
        return canSuspend;
      }
    };
  }
}
