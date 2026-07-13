/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.basic.mapper;

import com.datastrato.gravitino.scim.service.ScimConfig;
import org.apache.gravitino.auth.PrincipalMapper;

/** Applies SCIM user name mappers from {@link ScimConfig}. */
public final class ScimNameMappers {

  private ScimNameMappers() {}

  /**
   * Maps a SCIM userName to a Gravitino user name.
   *
   * @param mapper configured user mapper
   * @param rawUserName SCIM userName
   * @return mapped Gravitino user name
   */
  public static String mapUserName(PrincipalMapper mapper, String rawUserName) {
    return mapper.map(rawUserName).getName();
  }
}
