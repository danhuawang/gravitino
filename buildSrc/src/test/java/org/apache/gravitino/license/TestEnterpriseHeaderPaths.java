/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Locks every {@code // keep-sorted} block under enterprise licensing, and requires exactly one
 * {@code name=enterprise-includes} list and one {@code name=generated-file-patterns} list.
 */
class TestEnterpriseHeaderPaths {

  private static final String KEEP_SORTED_START = "// keep-sorted start";
  private static final String KEEP_SORTED_END = "// keep-sorted end";
  private static final String ENTERPRISE_INCLUDES = "name=enterprise-includes";
  private static final String GENERATED_FILE_PATTERNS = "name=generated-file-patterns";
  private static final Pattern QUOTED_PATH = Pattern.compile("\"([^\"]+)\"");

  @Test
  void keepSortedBlocksAreSortedAndIncludesMatch() throws IOException {
    Path repoRoot = repoRoot();
    List<KeepSortedBlock> blocks = new ArrayList<>();
    blocks.addAll(blocksIn(repoRoot.resolve("enterprise-licensing.gradle.kts")));
    try (DirectoryStream<Path> children =
        Files.newDirectoryStream(repoRoot.resolve("enterprise-licensing"), "*.gradle.kts")) {
      for (Path child : children) {
        blocks.addAll(blocksIn(child));
      }
    }
    blocks.addAll(
        blocksIn(
            repoRoot.resolve(
                "buildSrc/src/main/java/org/apache/gravitino/license/LicenseHeaderClassifier.java")));

    assertFalse(blocks.isEmpty(), "no keep-sorted blocks found");

    List<KeepSortedBlock> enterpriseIncludes = new ArrayList<>();
    List<KeepSortedBlock> generatedPatterns = new ArrayList<>();
    for (KeepSortedBlock block : blocks) {
      assertEquals(
          sortedCopy(block.entries),
          block.entries,
          block.file.getFileName() + " keep-sorted block is not sorted");
      if (block.name.equals(ENTERPRISE_INCLUDES)) {
        enterpriseIncludes.add(block);
      } else if (block.name.equals(GENERATED_FILE_PATTERNS)) {
        generatedPatterns.add(block);
      }
    }

    assertEquals(1, enterpriseIncludes.size(), "expected one enterprise-includes list");
    assertFalse(enterpriseIncludes.get(0).entries.isEmpty(), "enterprise-includes is empty");
    assertEquals(1, generatedPatterns.size(), "expected one generated-file-patterns list");
    assertFalse(generatedPatterns.get(0).entries.isEmpty(), "generated-file-patterns is empty");
  }

  private static List<KeepSortedBlock> blocksIn(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<KeepSortedBlock> blocks = new ArrayList<>();
    String currentName = "";
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      String trimmed = lines.get(i).trim();
      if (trimmed.startsWith(KEEP_SORTED_START)) {
        currentName = trimmed.substring(KEEP_SORTED_START.length()).trim();
        start = i;
      } else if (trimmed.equals(KEEP_SORTED_END) && start >= 0) {
        List<String> entries = new ArrayList<>();
        for (int j = start + 1; j < i; j++) {
          Matcher matcher = QUOTED_PATH.matcher(lines.get(j));
          if (matcher.find()) {
            entries.add(matcher.group(1));
          }
        }
        blocks.add(new KeepSortedBlock(file, currentName, entries));
        start = -1;
        currentName = "";
      }
    }
    return blocks;
  }

  private static List<String> sortedCopy(List<String> paths) {
    List<String> sorted = new ArrayList<>(paths);
    sorted.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
    return sorted;
  }

  private static Path repoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("enterprise-licensing.gradle.kts"))) {
      dir = dir.getParent();
    }
    assertNotNull(dir, "could not find repo root from " + System.getProperty("user.dir"));
    return dir;
  }

  private static final class KeepSortedBlock {
    private final Path file;
    private final String name;
    private final List<String> entries;

    private KeepSortedBlock(Path file, String name, List<String> entries) {
      this.file = file;
      this.name = name;
      this.entries = entries;
    }
  }
}
