/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.spark.connector.jdbc;

import org.apache.gravitino.spark.connector.plugin.SparkCatalogExtension;
import org.apache.spark.package$;
import org.apache.spark.util.VersionUtils$;

/**
 * Base class for the extensions this jar registers. Every supported Spark version has its own
 * catalog subclass, so the class name is derived from the running Spark version the same way the
 * connector resolves its built-in catalogs.
 */
abstract class VersionedSparkCatalogExtension implements SparkCatalogExtension {

  private final String provider;
  private final String catalogClassNamePrefix;

  VersionedSparkCatalogExtension(String provider, String catalogClassNamePrefix) {
    this.provider = provider;
    this.catalogClassNamePrefix = catalogClassNamePrefix;
  }

  @Override
  public String provider() {
    return provider;
  }

  /**
   * Resolves the catalog class for the running Spark version.
   *
   * @return the fully qualified catalog class name
   * @throws IllegalStateException if this jar has no catalog for the running Spark version; the
   *     connector logs it and skips this extension instead of registering a class that cannot be
   *     loaded
   */
  @Override
  public String catalogClassName() {
    String sparkVersion = package$.MODULE$.SPARK_VERSION();
    String className =
        catalogClassNamePrefix
            + VersionUtils$.MODULE$.majorVersion(sparkVersion)
            + VersionUtils$.MODULE$.minorVersion(sparkVersion);
    try {
      Class.forName(className, false, getClass().getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          String.format(
              "Spark %s is not supported by the Enterprise extension for provider %s (%s)",
              sparkVersion, provider, className),
          e);
    }
    return className;
  }
}
