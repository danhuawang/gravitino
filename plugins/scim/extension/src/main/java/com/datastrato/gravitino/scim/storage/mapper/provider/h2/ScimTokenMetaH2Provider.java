/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper.provider.h2;

import com.datastrato.gravitino.scim.storage.mapper.provider.base.ScimTokenMetaBaseSQLProvider;

/** SQL provider for SCIM token metadata statements on H2 backends. */
public class ScimTokenMetaH2Provider extends ScimTokenMetaBaseSQLProvider {

  @Override
  protected String currentTimeMillisExpression() {
    return "DATEDIFF('MILLISECOND', TIMESTAMP '1970-01-01 00:00:00', CURRENT_TIMESTAMP())";
  }
}
