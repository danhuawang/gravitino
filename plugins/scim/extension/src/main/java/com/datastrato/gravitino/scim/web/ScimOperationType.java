/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.web;

/** Operation types for SCIM token REST error handling. */
public enum ScimOperationType {
  CREATE,
  ROTATE,
  DELETE,
  LIST,
  LIST_PROVISIONING
}
