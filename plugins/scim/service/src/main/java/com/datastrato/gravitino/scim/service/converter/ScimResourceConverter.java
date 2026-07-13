/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.converter;

import com.google.common.collect.ImmutableSet;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.gravitino.authorization.User;

/** Converts between Gravitino authorization entities and SCIMple user resources. */
public final class ScimResourceConverter {

  private static final String USER_SCHEMA = ScimUser.SCHEMA_URI;

  private ScimResourceConverter() {}

  /**
   * Converts a Gravitino user to a SCIM user resource.
   *
   * @param user Gravitino user
   * @return SCIM user
   */
  public static ScimUser toScimUser(User user) {
    String externalId = user.externalId();
    ScimUser scimUser = new ScimUser();
    scimUser.setSchemas(ImmutableSet.of(USER_SCHEMA));
    scimUser.setId(externalId);
    scimUser.setExternalId(externalId);
    scimUser.setUserName(user.name());
    scimUser.setActive(user.enabled());
    return scimUser;
  }
}
