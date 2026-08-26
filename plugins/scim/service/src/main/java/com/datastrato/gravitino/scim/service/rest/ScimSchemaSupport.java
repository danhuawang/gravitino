/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service.rest;

import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.directory.scim.spec.schema.Schema;

/**
 * Trims SCIMple-reflected User/Group schemas to the attributes Gravitino actually synchronizes.
 *
 * <p>SCIMple {@link SchemaRegistry#addSchema} publishes the full RFC 7643 attribute set. Gravitino
 * only stores/returns a subset (design §8.3); leaving the full set in {@code /Schemas} overstates
 * capabilities to IdPs.
 */
final class ScimSchemaSupport {

  /** Common + User attributes returned or accepted by repository adapters. */
  static final Set<String> USER_ATTRIBUTES =
      ImmutableSet.of("id", "externalId", "meta", "userName", "displayName", "active");

  /** Common + Group attributes returned or accepted by repository adapters. */
  static final Set<String> GROUP_ATTRIBUTES =
      ImmutableSet.of("id", "externalId", "meta", "displayName", "members");

  /** {@code meta} sub-attributes filled by {@code ScimResourceConverter}. */
  static final Set<String> META_SUB_ATTRIBUTES =
      ImmutableSet.of("resourceType", "created", "lastModified", "location");

  /** Group {@code members} sub-attributes returned today ({@code value} = Gravitino user id). */
  static final Set<String> MEMBERS_SUB_ATTRIBUTES = ImmutableSet.of("value");

  private ScimSchemaSupport() {}

  /**
   * Retains only Gravitino-supported attributes on User/Group schemas in {@code schemaRegistry}.
   *
   * @param schemaRegistry registry after User/Group schemas are registered
   */
  static void retainSupportedAttributes(SchemaRegistry schemaRegistry) {
    for (Schema schema : schemaRegistry.getAllSchemas()) {
      if (ScimUser.SCHEMA_URI.equals(schema.getId())) {
        retainAttributes(schema, USER_ATTRIBUTES);
      } else if (ScimGroup.SCHEMA_URI.equals(schema.getId())) {
        retainAttributes(schema, GROUP_ATTRIBUTES);
      }
    }
  }

  private static void retainAttributes(Schema schema, Set<String> keep) {
    Set<Schema.Attribute> retained = new LinkedHashSet<>();
    for (Schema.Attribute attribute : schema.getAttributes()) {
      if (!keep.contains(attribute.getName())) {
        continue;
      }
      if ("meta".equals(attribute.getName())) {
        retainSubAttributes(attribute, META_SUB_ATTRIBUTES);
      } else if ("members".equals(attribute.getName())) {
        retainSubAttributes(attribute, MEMBERS_SUB_ATTRIBUTES);
      }
      retained.add(attribute);
    }
    schema.setAttributes(retained);
  }

  private static void retainSubAttributes(Schema.Attribute parent, Set<String> keep) {
    Set<Schema.Attribute> subAttributes = parent.getSubAttributes();
    if (subAttributes == null || subAttributes.isEmpty()) {
      return;
    }
    Set<Schema.Attribute> retained =
        subAttributes.stream()
            .filter(sub -> keep.contains(sub.getName()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    parent.setSubAttributes(retained, Schema.Attribute.AddAction.REPLACE);
  }
}
