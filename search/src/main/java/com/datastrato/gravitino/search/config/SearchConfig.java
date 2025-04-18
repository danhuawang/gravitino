/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.config;

import java.util.Map;
import org.apache.gravitino.Config;

public class SearchConfig extends Config {
  // TODO Add more properties as needed
  public SearchConfig(Map<String, String> properties) {
    super(false);
    loadFromMap(properties, k -> true);
  }
}
