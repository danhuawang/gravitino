/*
 * Copyright 2026 Datastrato Pvt Ltd.
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
