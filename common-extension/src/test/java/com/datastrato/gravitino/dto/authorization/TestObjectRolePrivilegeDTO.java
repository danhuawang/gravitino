/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.dto.authorization.PrivilegeDTO;
import org.apache.gravitino.dto.tag.MetadataObjectDTO;
import org.apache.gravitino.json.JsonUtils;
import org.junit.jupiter.api.Test;

class TestObjectRolePrivilegeDTO {

  private static final Instant CREATED_AT = Instant.parse("2026-09-10T01:02:03Z");

  @Test
  void testRoundTripWithCatalogDetails() throws Exception {
    ObjectRolePrivilegeDTO objectRolePrivilege =
        ObjectRolePrivilegeDTO.builder()
            .withMetadataObject(
                MetadataObjectDTO.builder()
                    .withParent("catalog.schema")
                    .withName("table")
                    .withType(MetadataObject.Type.TABLE)
                    .build())
            .withCatalogProvider("lakehouse-iceberg")
            .withCatalogType(Catalog.Type.RELATIONAL)
            .withRolePrivileges(new RolePrivilegeDTO[] {rolePrivilege()})
            .build();

    String json = JsonUtils.objectMapper().writeValueAsString(objectRolePrivilege);
    assertTrue(json.contains("\"catalogType\":\"relational\""));
    ObjectRolePrivilegeDTO roundTrip =
        JsonUtils.objectMapper().readValue(json, ObjectRolePrivilegeDTO.class);

    assertEquals("catalog.schema.table", roundTrip.metadataObject().fullName());
    assertEquals("lakehouse-iceberg", roundTrip.catalogProvider());
    assertEquals(Catalog.Type.RELATIONAL, roundTrip.catalogType());
    assertEquals(1, roundTrip.rolePrivileges().size());
  }

  @Test
  void testCatalogDetailsAreOptional() {
    ObjectRolePrivilegeDTO objectRolePrivilege =
        ObjectRolePrivilegeDTO.builder()
            .withMetadataObject(
                MetadataObjectDTO.builder()
                    .withName("metalake")
                    .withType(MetadataObject.Type.METALAKE)
                    .build())
            .withRolePrivileges(new RolePrivilegeDTO[] {rolePrivilege()})
            .build();

    assertNull(objectRolePrivilege.catalogProvider());
    assertNull(objectRolePrivilege.catalogType());
  }

  private RolePrivilegeDTO rolePrivilege() {
    return RolePrivilegeDTO.builder()
        .withRole("admin")
        .withPrivileges(
            new PrivilegeDTO[] {
              PrivilegeDTO.builder()
                  .withName(Privilege.Name.SELECT_TABLE)
                  .withCondition(Privilege.Condition.ALLOW)
                  .build()
            })
        .withCreateTime(CREATED_AT)
        .withAssignCount(1)
        .build();
  }
}
