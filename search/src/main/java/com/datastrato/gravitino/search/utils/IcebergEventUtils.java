/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.utils;

import java.lang.reflect.Method;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.OperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility helpers for identifying and interpreting Iceberg events in isolated class loaders. */
public final class IcebergEventUtils {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergEventUtils.class);

  public static final String ICEBERG_TABLE_EVENT_CLASS =
      "org.apache.gravitino.listener.api.event.IcebergTableEvent";
  public static final String ICEBERG_NAMESPACE_EVENT_CLASS =
      "org.apache.gravitino.listener.api.event.IcebergNamespaceEvent";

  private IcebergEventUtils() {}

  /**
   * Returns the {@link OperationType} for the given event by invoking {@code operationType()} via
   * reflection.
   */
  public static OperationType getOperationType(Event event) {
    try {
      Method operationTypeMethod = event.getClass().getMethod("operationType");
      operationTypeMethod.setAccessible(true);
      return (OperationType) operationTypeMethod.invoke(event);
    } catch (NoSuchMethodException e) {
      return null;
    } catch (Exception e) {
      LOG.warn("Failed to get operationType from event {}", event.getClass().getName(), e);
      return null;
    }
  }

  /**
   * Returns {@code true} if {@code event}'s runtime class or any of its superclasses has the given
   * fully-qualified name.
   */
  public static boolean isSubclassOf(Event event, String className) {
    Class<?> clazz = event.getClass();
    while (clazz != null) {
      if (className.equals(clazz.getName())) {
        return true;
      }
      clazz = clazz.getSuperclass();
    }
    return false;
  }
}
