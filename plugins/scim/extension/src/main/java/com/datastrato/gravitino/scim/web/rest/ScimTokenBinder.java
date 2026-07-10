/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.datastrato.gravitino.scim.ScimTokenManager;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

/** Registers SCIM token REST dependencies for Jersey/HK2 injection. */
public class ScimTokenBinder extends AbstractBinder {

  @Override
  protected void configure() {
    bind(ScimTokenManager.getInstance()).to(ScimTokenManager.class);
  }
}
