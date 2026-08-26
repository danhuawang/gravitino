/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service;

import com.datastrato.gravitino.scim.service.classloader.ScimAuxClassLoaders;
import com.google.common.annotations.VisibleForTesting;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.auxiliary.AuxiliaryServiceManager;
import org.apache.gravitino.auxiliary.GravitinoAuxiliaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCIM 2.0 auxiliary HTTP service SPI entry on port 9201.
 *
 * <p>Free of Jetty / Jersey / Jakarta imports so {@link
 * org.apache.gravitino.utils.IsolatedClassLoader} does not link those types from the main server
 * classpath. Bootstraps {@link ScimRESTServiceImpl} inside a child-first classloader from {@code
 * gravitino.scim.classpath}.
 */
public class ScimRESTService implements GravitinoAuxiliaryService {

  private static final Logger LOG = LoggerFactory.getLogger(ScimRESTService.class);

  /** Auxiliary service short name registered in {@code gravitino.auxService.names}. */
  public static final String SERVICE_NAME = "scim";

  // Literal name only: do not reference ScimRESTServiceImpl.class or the SPI class links
  // Jetty/Jersey/Jakarta through the implementation.
  private static final String IMPL_CLASS =
      "com.datastrato.gravitino.scim.service.ScimRESTServiceImpl";

  private URLClassLoader scimClassLoader;
  private Object impl;

  /** {@inheritDoc} */
  @Override
  public String shortName() {
    return SERVICE_NAME;
  }

  /** {@inheritDoc} */
  @Override
  public void serviceInit(Map<String, String> properties, boolean auxMode) {
    String classpath = properties.get(AuxiliaryServiceManager.AUX_SERVICE_CLASSPATH);
    if (StringUtils.isBlank(classpath)) {
      throw new IllegalArgumentException(
          "SCIM aux service requires classpath (gravitino.scim.classpath), e.g. scim-server/libs");
    }

    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      ClassLoader bridge = GravitinoEnv.class.getClassLoader();
      scimClassLoader = ScimAuxClassLoaders.create(bridge, classpath);
      Thread.currentThread().setContextClassLoader(scimClassLoader);

      Class<?> implClass = scimClassLoader.loadClass(IMPL_CLASS);
      impl = implClass.getDeclaredConstructor().newInstance();
      implClass
          .getMethod("serviceInit", Map.class, boolean.class)
          .invoke(impl, properties, auxMode);
      LOG.info("SCIM auxiliary service bootstrapped from classpath={}", classpath);
    } catch (RuntimeException e) {
      closeClassLoaderQuietly();
      throw e;
    } catch (Exception e) {
      closeClassLoaderQuietly();
      Throwable cause =
          e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
      throw new RuntimeException("Failed to initialize SCIM auxiliary service", cause);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void serviceStart() {
    if (impl == null) {
      return;
    }
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(scimClassLoader);
      impl.getClass().getMethod("serviceStart").invoke(impl);
    } catch (Exception e) {
      Throwable cause =
          e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
      throw new RuntimeException("Failed to start SCIM auxiliary service", cause);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void serviceStop() throws Exception {
    try {
      if (impl != null) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
          Thread.currentThread().setContextClassLoader(scimClassLoader);
          impl.getClass().getMethod("serviceStop").invoke(impl);
        } finally {
          Thread.currentThread().setContextClassLoader(previous);
        }
      }
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw e;
    } finally {
      closeClassLoaderQuietly();
    }
  }

  /**
   * Returns the child-first classloader that owns the SCIM HTTP stack.
   *
   * @return SCIM aux classloader, or {@code null} before init / after stop
   */
  @VisibleForTesting
  public URLClassLoader scimClassLoader() {
    return scimClassLoader;
  }

  /**
   * Returns the reflective {@code ScimRESTServiceImpl} instance.
   *
   * @return implementation object, or {@code null} before init / after stop
   */
  @VisibleForTesting
  public Object scimImplementation() {
    return impl;
  }

  private void closeClassLoaderQuietly() {
    if (scimClassLoader == null) {
      return;
    }
    try {
      scimClassLoader.close();
    } catch (Exception e) {
      LOG.warn("Failed to close SCIM auxiliary classloader", e);
    } finally {
      scimClassLoader = null;
      impl = null;
    }
  }
}
