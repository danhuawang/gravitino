/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.directory.scim.spec.schema.Schema;
import org.junit.jupiter.api.Test;

class TestGravitinoScimApplication {

  @Test
  void testMarkCommonIdCaseExact() {
    SchemaRegistry registry = new SchemaRegistry();
    registry.addSchema(ScimUser.class, Collections.emptyList());
    registry.addSchema(ScimGroup.class, Collections.emptyList());

    Schema.Attribute userIdBefore = registry.getSchema(ScimUser.SCHEMA_URI).getAttribute("id");
    assertNotNull(userIdBefore);
    assertFalse(userIdBefore.isCaseExact());

    GravitinoScimApplication.markCommonIdCaseExact(registry);

    Schema.Attribute userId = registry.getSchema(ScimUser.SCHEMA_URI).getAttribute("id");
    Schema.Attribute groupId = registry.getSchema(ScimGroup.SCHEMA_URI).getAttribute("id");
    assertNotNull(userId);
    assertNotNull(groupId);
    assertTrue(userId.isCaseExact());
    assertTrue(groupId.isCaseExact());

    // Unrelated attributes keep SCIMple defaults.
    assertFalse(registry.getSchema(ScimUser.SCHEMA_URI).getAttribute("userName").isCaseExact());
    assertTrue(registry.getSchema(ScimUser.SCHEMA_URI).getAttribute("externalId").isCaseExact());
    assertFalse(registry.getSchema(ScimGroup.SCHEMA_URI).getAttribute("displayName").isCaseExact());
    assertTrue(registry.getSchema(ScimGroup.SCHEMA_URI).getAttribute("externalId").isCaseExact());
  }
}
