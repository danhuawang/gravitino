/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics.rest;

import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import org.glassfish.hk2.api.Factory;

public abstract class ServletRequestFactoryBase
    implements Factory<HttpServletRequest>, Supplier<HttpServletRequest> {

  @Override
  public HttpServletRequest provide() {
    return get();
  }

  @Override
  public void dispose(HttpServletRequest instance) {}
}
