/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.storage.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.scim.storage.mapper.provider.ScimTokenMapperPackageProvider;
import java.util.List;
import java.util.ServiceLoader;
import org.apache.gravitino.storage.relational.mapper.provider.MapperPackageProvider;
import org.junit.jupiter.api.Test;

public class TestScimTokenMapperPackageProvider {

  @Test
  public void testGetMapperClasses() {
    MapperPackageProvider provider = new ScimTokenMapperPackageProvider();
    List<Class<?>> mapperClasses = provider.getMapperClasses();

    assertEquals(1, mapperClasses.size());
    assertTrue(mapperClasses.contains(ScimTokenMetaMapper.class));
  }

  @Test
  public void testServiceLoaderDiscoversProvider() {
    List<MapperPackageProvider> providers =
        ServiceLoader.load(MapperPackageProvider.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(provider -> provider instanceof ScimTokenMapperPackageProvider)
            .toList();

    assertEquals(1, providers.size());
    assertTrue(providers.get(0) instanceof ScimTokenMapperPackageProvider);
  }
}
