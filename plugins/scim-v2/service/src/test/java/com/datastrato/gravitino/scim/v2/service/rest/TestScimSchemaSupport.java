/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.directory.scim.spec.schema.Schema;
import org.junit.jupiter.api.Test;

class TestScimSchemaSupport {

  @Test
  void testRetainSupportedAttributesTrimsUserAndGroupSchemas() {
    SchemaRegistry registry = new SchemaRegistry();
    registry.addSchema(ScimUser.class, Collections.emptyList());
    registry.addSchema(ScimGroup.class, Collections.emptyList());
    GravitinoScimApplication.markCommonIdCaseExact(registry);
    ScimSchemaSupport.retainSupportedAttributes(registry);

    Schema userSchema = registry.getSchema(ScimUser.SCHEMA_URI);
    assertNotNull(userSchema);
    assertEquals(ScimSchemaSupport.USER_ATTRIBUTES, attributeNames(userSchema));
    assertFalse(attributeNames(userSchema).contains("emails"));
    assertFalse(attributeNames(userSchema).contains("name"));
    assertTrue(userSchema.getAttribute("id").isCaseExact());
    assertEquals(
        ScimSchemaSupport.META_SUB_ATTRIBUTES, subAttributeNames(userSchema.getAttribute("meta")));

    Schema groupSchema = registry.getSchema(ScimGroup.SCHEMA_URI);
    assertNotNull(groupSchema);
    assertEquals(ScimSchemaSupport.GROUP_ATTRIBUTES, attributeNames(groupSchema));
    assertFalse(attributeNames(groupSchema).contains("emails"));
    assertEquals(
        ScimSchemaSupport.MEMBERS_SUB_ATTRIBUTES,
        subAttributeNames(groupSchema.getAttribute("members")));
  }

  private static Set<String> attributeNames(Schema schema) {
    return schema.getAttributes().stream()
        .map(Schema.Attribute::getName)
        .collect(Collectors.toSet());
  }

  private static Set<String> subAttributeNames(Schema.Attribute attribute) {
    return attribute.getSubAttributes().stream()
        .map(Schema.Attribute::getName)
        .collect(Collectors.toSet());
  }
}
