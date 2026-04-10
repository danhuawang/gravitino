/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle;

import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.connector.capability.CapabilityResult;

public class OracleCatalogCapability implements Capability {
  /**
   * Regular expression explanation: ^[A-Za-z][A-Za-z0-9_$#]{0,29}$
   *
   * <p>^ - Start of the string
   *
   * <p>[A-Za-z] - Must start with a letter (upper or lower case)
   *
   * <p>[A-Za-z0-9_$#]{0,29} - Followed by 0 to 29 characters of letters, digits, underscores,
   * dollar signs, or hash signs
   *
   * <p>$ - End of the string
   *
   * <p>Total length: 1 to 30 characters (Oracle's maximum identifier length for versions &lt; 12.2)
   */
  public static final String ORACLE_NAME_PATTERN = "^[A-Za-z][A-Za-z0-9_$#]{0,29}$";

  @Override
  public CapabilityResult specificationOnName(Scope scope, String name) {
    if (!name.matches(ORACLE_NAME_PATTERN)) {
      return CapabilityResult.unsupported(
          String.format("The %s name '%s' is illegal.", scope, name));
    }
    return CapabilityResult.SUPPORTED;
  }
}
