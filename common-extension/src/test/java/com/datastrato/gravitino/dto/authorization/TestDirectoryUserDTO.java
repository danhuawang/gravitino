/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link DirectoryUserDTO}. */
public class TestDirectoryUserDTO {

  /** Verifies Local, Provisioned, and JIT mapping. */
  @Test
  public void testFromDirectoryUserView() {
    DirectoryUserDTO local =
        DirectoryUserDTO.from(
            view("sam.o", true, IdentitySource.LOCAL, List.of("governance"), List.of("Contoso")));
    assertEquals("sam.o", local.name());
    assertTrue(local.enabled());
    assertEquals(IdentitySource.LOCAL, local.origin());
    assertEquals(List.of("governance"), local.groups());
    assertEquals(List.of("Contoso"), local.metalakes());

    DirectoryUserDTO provisioned =
        DirectoryUserDTO.from(
            view(
                "dana.k",
                false,
                IdentitySource.PROVISIONED,
                List.of("finance"),
                List.of("Acme", "Contoso")));
    assertEquals("dana.k", provisioned.name());
    assertFalse(provisioned.enabled());
    assertEquals(IdentitySource.PROVISIONED, provisioned.origin());
    assertEquals(List.of("finance"), provisioned.groups());
    assertEquals(List.of("Acme", "Contoso"), provisioned.metalakes());

    DirectoryUserDTO jit =
        DirectoryUserDTO.from(
            view("jordan.m", true, IdentitySource.JIT, List.of(), List.of("Contoso")));
    assertEquals("jordan.m", jit.name());
    assertTrue(jit.enabled());
    assertEquals(IdentitySource.JIT, jit.origin());
    assertTrue(jit.groups().isEmpty());
    assertEquals(List.of("Contoso"), jit.metalakes());
  }

  private static DirectoryUserDTO.DirectoryUserView view(
      String name,
      boolean enabled,
      IdentitySource origin,
      List<String> groups,
      List<String> metalakes) {
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
    };
  }
}
