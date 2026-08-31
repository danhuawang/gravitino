/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.license;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks the mixed-distribution LICENSE / NOTICE layout required by DAT-666.
 *
 * <p>A reader of the source tree or the binary package must see three named
 * categories: Apache Gravitino-derived code, bundled third-party OSS, and
 * Datastrato proprietary modules. Packaging must ship the files the statements
 * point at.
 */
class TestMixedDistributionLicenses {

  private static final String PROPRIETARY_NOTICE =
      "This distribution also includes proprietary software developed by";

  @Test
  void rootLicenseIsMixedStatementNotBareApache() throws IOException {
    String license = read("LICENSE");
    assertTrue(
        license.startsWith("Gravitino Enterprise is a mixed distribution."),
        "root LICENSE must open as the mixed-distribution statement");
    assertFalse(
        license.startsWith("Apache License"),
        "root LICENSE must not still be the bare Apache 2.0 body");
    assertContains(license, "LICENSE-APACHE");
    assertContains(license, "LICENSE-ENTERPRISE");
    assertContains(license, "LICENSE.iceberg");
    assertContains(license, "LICENSE.trino");
    assertContains(license, "LICENSE.lance");
    assertContains(license, "licenses/");
  }

  @Test
  void apacheLicenseTextRemainsInTree() throws IOException {
    String apache = read("LICENSE-APACHE");
    assertTrue(
        apache.contains("Apache License"), "LICENSE-APACHE must keep the Apache 2.0 title");
    assertTrue(
        apache.contains("Version 2.0, January 2004"),
        "LICENSE-APACHE must keep the Apache 2.0 version line");
    assertTrue(
        apache.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"),
        "LICENSE-APACHE must keep the Apache 2.0 terms");
  }

  @Test
  void enterpriseLicenseIsPlaceholderNotContract() throws IOException {
    String enterprise = read("LICENSE-ENTERPRISE");
    assertContains(enterprise, "not licensed under the Apache License");
    assertContains(enterprise, "Commercial terms");
    assertContains(enterprise, "datastrato-server");
    assertContains(enterprise, "authorization-jdbc-enterprise");
    assertContains(enterprise, "does not grant a license");
    assertFalse(
        enterprise.contains("GRANT OF LICENSE"),
        "LICENSE-ENTERPRISE must not invent commercial grant language");
  }

  @Test
  void licenseBinIsMixedStatementNotBareApache() throws IOException {
    String licenseBin = read("LICENSE.bin");
    assertTrue(
        licenseBin.startsWith("Gravitino Enterprise is a mixed distribution."),
        "LICENSE.bin must open as the mixed-distribution statement");
    assertFalse(
        licenseBin.startsWith("Apache License"),
        "LICENSE.bin must not still read as a bare Apache 2.0 artifact");
    assertContains(licenseBin, "LICENSE-APACHE");
    assertContains(licenseBin, "LICENSE-ENTERPRISE");
    assertContains(licenseBin, "licenses/");
    assertContains(licenseBin, "This product bundles various third-party components");
  }

  @Test
  void noticeFilesHaveAdditiveProprietaryParagraph() throws IOException {
    for (String name : List.of("NOTICE", "NOTICE.bin")) {
      String notice = read(name);
      assertContains(notice, "Apache Gravitino");
      assertContains(notice, "The Apache Software Foundation");
      assertContains(notice, PROPRIETARY_NOTICE);
      assertContains(notice, "LICENSE-ENTERPRISE");
    }
  }

  @Test
  void packagingCopiesSiblingLicenseFiles() throws IOException {
    String build = read("build.gradle.kts");
    assertContains(build, "include(\"LICENSE-APACHE\")");
    assertContains(build, "include(\"LICENSE-ENTERPRISE\")");
    assertContains(build, "from(projectDir.file(\"LICENSE-APACHE\")) { into(\"package\") }");
    assertContains(build, "from(projectDir.file(\"LICENSE-ENTERPRISE\")) { into(\"package\") }");
  }

  @Test
  void sourceTarballKeepsMixedDistributionFiles() throws IOException {
    String release = read("dev/release/release-build.sh");
    assertContains(release, "LICENSE-APACHE");
    assertContains(release, "LICENSE-ENTERPRISE");
    assertFalse(
        release.contains("rm -f gravitino-$GRAVITINO_VERSION-src/LICENSE-APACHE"),
        "source tarball must not strip LICENSE-APACHE");
    assertFalse(
        release.contains("rm -f gravitino-$GRAVITINO_VERSION-src/LICENSE-ENTERPRISE"),
        "source tarball must not strip LICENSE-ENTERPRISE");
  }

  @Test
  void assemblePackageCopyProducesRequiredFiles() throws IOException {
    Path root = repoRoot();
    Path packageDir = Files.createTempDirectory("gravitino-package-licenses");
    copyRenamingBin(root.resolve("LICENSE.bin"), packageDir.resolve("LICENSE"));
    copyRenamingBin(root.resolve("NOTICE.bin"), packageDir.resolve("NOTICE"));
    Files.copy(root.resolve("LICENSE-APACHE"), packageDir.resolve("LICENSE-APACHE"));
    Files.copy(root.resolve("LICENSE-ENTERPRISE"), packageDir.resolve("LICENSE-ENTERPRISE"));
    Path licensesDir = packageDir.resolve("licenses");
    Files.createDirectories(licensesDir);
    try (Stream<Path> licenses = Files.list(root.resolve("licenses"))) {
      licenses.filter(Files::isRegularFile).forEach(src -> {
        try {
          Files.copy(src, licensesDir.resolve(src.getFileName()));
        } catch (IOException e) {
          fail("Failed to copy " + src + ": " + e.getMessage());
        }
      });
    }

    assertTrue(Files.isRegularFile(packageDir.resolve("LICENSE")));
    assertTrue(Files.isRegularFile(packageDir.resolve("NOTICE")));
    assertTrue(Files.isRegularFile(packageDir.resolve("LICENSE-APACHE")));
    assertTrue(Files.isRegularFile(packageDir.resolve("LICENSE-ENTERPRISE")));
    assertTrue(Files.isDirectory(packageDir.resolve("licenses")));
    try (Stream<Path> shipped = Files.list(packageDir.resolve("licenses"))) {
      assertTrue(shipped.findAny().isPresent(), "package/licenses/ must not be empty");
    }

    String shippedLicense =
        Files.readString(packageDir.resolve("LICENSE"), StandardCharsets.UTF_8);
    assertTrue(
        shippedLicense.startsWith("Gravitino Enterprise is a mixed distribution."),
        "assembled LICENSE must be the mixed statement, not bare Apache 2.0");
    assertTrue(
        Files.readString(packageDir.resolve("NOTICE"), StandardCharsets.UTF_8)
            .contains(PROPRIETARY_NOTICE));
  }

  @Test
  void sourceTarballSimulationKeepsRootMixedFiles() throws IOException {
    Path root = repoRoot();
    Path srcDir = Files.createTempDirectory("gravitino-src-licenses");
    for (String name :
        List.of("LICENSE", "NOTICE", "LICENSE-APACHE", "LICENSE-ENTERPRISE", "LICENSE.bin",
            "NOTICE.bin")) {
      Files.copy(root.resolve(name), srcDir.resolve(name));
    }
    Files.deleteIfExists(srcDir.resolve("LICENSE.bin"));
    Files.deleteIfExists(srcDir.resolve("NOTICE.bin"));

    assertTrue(Files.isRegularFile(srcDir.resolve("LICENSE")));
    assertTrue(Files.isRegularFile(srcDir.resolve("NOTICE")));
    assertTrue(Files.isRegularFile(srcDir.resolve("LICENSE-APACHE")));
    assertTrue(Files.isRegularFile(srcDir.resolve("LICENSE-ENTERPRISE")));
    assertFalse(Files.exists(srcDir.resolve("LICENSE.bin")));
    assertFalse(Files.exists(srcDir.resolve("NOTICE.bin")));
  }

  private static void assertContains(String haystack, String needle) {
    assertTrue(haystack.contains(needle), "expected to find '" + needle + "'");
  }

  private static void copyRenamingBin(Path source, Path target) throws IOException {
    Files.copy(source, target);
  }

  private static String read(String relative) throws IOException {
    return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
  }

  private static Path repoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 6; i++) {
      if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
          && Files.isRegularFile(dir.resolve("enterprise-licenses.gradle.kts"))) {
        return dir;
      }
      dir = dir.getParent();
      if (dir == null) {
        break;
      }
    }
    throw new IllegalStateException(
        "Could not locate repo root from " + System.getProperty("user.dir"));
  }
}
