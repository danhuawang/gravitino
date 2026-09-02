/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.classloader;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

/**
 * Child-first classloader for the SCIM HTTP stack (Jetty 11 / Jersey 3 / Jakarta).
 *
 * <p>{@link org.apache.gravitino.utils.IsolatedClassLoader} treats non-catalog classes as shared,
 * so Jetty/Jersey would come from the main server classpath. This loader prefers {@code
 * scim-server/libs} for the SCIMple stack and delegates Gravitino / SCIM extension types so {@code
 * GravitinoEnv} and {@code ScimTokenManager} stay shared. Types not present in {@code
 * scim-server/libs} (for example {@code java.*} / shared logging) fall through to the bridge
 * loader.
 */
public final class ScimAuxClassLoaders {

  private static final Splitter CLASSPATH_SPLITTER =
      Splitter.on(',').omitEmptyStrings().trimResults();

  private ScimAuxClassLoaders() {}

  /**
   * Creates a child-first classloader from comma-separated lib directories ({@code
   * gravitino.scim.classpath}).
   *
   * @param gravitinoBridge classloader that can see Gravitino server + SCIM extension types
   * @param classpathCsv directories absolute or relative to {@code GRAVITINO_HOME}
   * @return classloader for Jetty 11 / Jersey 3 / SCIMple
   */
  public static URLClassLoader create(ClassLoader gravitinoBridge, String classpathCsv)
      throws IOException {
    Preconditions.checkNotNull(gravitinoBridge, "gravitinoBridge");
    Preconditions.checkArgument(StringUtils.isNotBlank(classpathCsv), "classpath must be set");

    List<URL> jarUrls = new ArrayList<>();
    for (String pathString : CLASSPATH_SPLITTER.split(classpathCsv)) {
      Path path = Paths.get(pathString);
      if (!Files.isDirectory(path) && !path.isAbsolute()) {
        String gravitinoHome = System.getenv("GRAVITINO_HOME");
        if (StringUtils.isNotBlank(gravitinoHome)) {
          path = Paths.get(gravitinoHome, pathString);
        }
      }
      if (!Files.isDirectory(path)) {
        throw new IllegalArgumentException(
            String.format(
                "SCIM classpath directory does not exist: %s (GRAVITINO_HOME=%s)",
                pathString, System.getenv("GRAVITINO_HOME")));
      }
      Path libsDir = path.toAbsolutePath().normalize();
      try (Stream<Path> paths = Files.list(libsDir)) {
        paths
            .filter(jar -> jar.getFileName().toString().endsWith(".jar"))
            .forEach(
                jar -> {
                  try {
                    jarUrls.add(jar.toUri().toURL());
                  } catch (MalformedURLException e) {
                    throw new IllegalStateException("Invalid SCIM lib URL: " + jar, e);
                  }
                });
      }
    }
    Preconditions.checkArgument(!jarUrls.isEmpty(), "No SCIM jars found under: %s", classpathCsv);
    return new ScimChildFirstClassLoader(jarUrls.toArray(URL[]::new), gravitinoBridge);
  }

  /**
   * Child-first loader for {@code scim-server/libs}.
   *
   * <p>Package-private; unit tests in this package cover delegation rules. Integration tests assert
   * isolation via class name.
   */
  static final class ScimChildFirstClassLoader extends URLClassLoader {

    private final ClassLoader gravitinoBridge;

    ScimChildFirstClassLoader(URL[] scimJarUrls, ClassLoader gravitinoBridge) {
      super(scimJarUrls, ClassLoader.getPlatformClassLoader());
      this.gravitinoBridge = gravitinoBridge;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (shouldDelegateToGravitinoBridge(name)) {
        return gravitinoBridge.loadClass(name);
      }

      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          try {
            loaded = findClass(name);
          } catch (ClassNotFoundException notInScimLibs) {
            loaded = gravitinoBridge.loadClass(name);
          }
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }

    /** Returns whether {@code name} must load from the Gravitino bridge classloader. */
    static boolean shouldDelegateToGravitinoBridge(String name) {
      return name.startsWith("org.apache.gravitino.")
          || (name.startsWith("com.datastrato.gravitino.scim.")
              && !name.startsWith("com.datastrato.gravitino.scim.service."));
    }
  }
}
