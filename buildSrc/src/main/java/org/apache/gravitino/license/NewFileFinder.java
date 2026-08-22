/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.license;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds files added in a git repo vs a base ref.
 *
 * <p>Returns {@code null} when the base ref cannot be resolved (e.g. a shallow checkout without the
 * base branch), so callers can fail the build rather than silently skip enforcement.
 */
public final class NewFileFinder {

  private NewFileFinder() {}

  /**
   * Returns the relative paths of files added vs {@code baseRef}, or {@code null} if the base ref
   * cannot be resolved.
   */
  public static List<String> addedFiles(File repoDir, String baseRef) {
    String mergeBase = git(repoDir, "merge-base", "HEAD", baseRef);
    if (mergeBase == null) {
      return null;
    }
    String out = git(repoDir, "diff", "--diff-filter=A", "--name-only", "-z", mergeBase);
    if (out == null) {
      return new ArrayList<>();
    }
    List<String> files = new ArrayList<>();
    // NUL-delimited output (-z) preserves exact paths, including those
    // containing newlines or quotes that --name-only would C-quote.
    for (String path : out.split("\0")) {
      if (!path.isEmpty()) {
        files.add(path);
      }
    }
    return files;
  }

  private static String git(File repoDir, String... args) {
    try {
      List<String> cmd = new ArrayList<>();
      cmd.add("git");
      cmd.addAll(Arrays.asList(args));
      Process process = new ProcessBuilder(cmd).directory(repoDir).redirectErrorStream(true).start();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int read;
      while ((read = process.getInputStream().read(chunk)) > 0) {
        buffer.write(chunk, 0, read);
      }
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        return null;
      }
      return buffer.toString(StandardCharsets.UTF_8.name()).trim();
    } catch (Exception e) {
      return null;
    }
  }
}
