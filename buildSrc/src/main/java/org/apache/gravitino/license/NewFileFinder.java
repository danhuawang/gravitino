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
import javax.annotation.Nullable;

/**
 * Finds files added in a git repo vs a base ref.
 *
 * <p>Returns {@code null} when the base ref cannot be resolved (e.g. a shallow checkout without the
 * base branch), so callers can fail the build rather than silently skip enforcement.
 *
 * <p>Local builds without {@code -PlicenseBaseRef} or {@code GITHUB_BASE_REF} use the current
 * branch's configured remote upstream, falling back to the remote default when no upstream exists.
 * This lets Git provide {@code origin/branch-1.3} for a tracking release branch instead of guessing
 * from branch names.
 */
public final class NewFileFinder {

  private NewFileFinder() {}

  /**
   * Maps {@code GITHUB_BASE_REF} to {@code origin/<branch>}. Blank means no explicit ref.
   *
   * @param githubBaseRef {@code GITHUB_BASE_REF}, or {@code null} when unset
   * @return {@code origin/<branch>}, or {@code null} when there is no branch name
   */
  @Nullable
  public static String originRefFromGithubBranch(@Nullable String githubBaseRef) {
    if (githubBaseRef == null) {
      return null;
    }
    String branch = githubBaseRef.trim();
    if (branch.isEmpty()) {
      return null;
    }
    return "origin/" + branch;
  }

  /**
   * Resolves the base ref used to detect newly added files.
   *
   * @param repoDir git repository root
   * @param configuredBaseRef explicit override ({@code -PlicenseBaseRef} or {@code
   *     origin/$GITHUB_BASE_REF}); {@code null} or blank means infer
   * @return the configured ref, the current branch's remote upstream, or the remote default
   */
  public static String resolveBaseRef(File repoDir, @Nullable String configuredBaseRef) {
    if (configuredBaseRef != null && !configuredBaseRef.trim().isEmpty()) {
      return configuredBaseRef.trim();
    }
    return inferBaseRef(repoDir);
  }

  /**
   * Infers the base ref from the current branch's configured upstream.
   *
   * <p>Git records the tracking upstream for a local branch, so a checkout of {@code branch-1.3}
   * can use {@code origin/branch-1.3} without scanning branch-name patterns. Git does not record a
   * pull request's target branch; local feature branches that track their remote head must use
   * {@code -PlicenseBaseRef}, while CI supplies {@code GITHUB_BASE_REF}.
   *
   * @param repoDir git repository root
   * @return the configured remote upstream, or the remote default when no upstream exists
   */
  public static String inferBaseRef(File repoDir) {
    String upstream =
        git(repoDir, "rev-parse", "--symbolic-full-name", "@{upstream}");
    String remotePrefix = "refs/remotes/";
    if (upstream != null && upstream.startsWith(remotePrefix)) {
      return upstream.substring(remotePrefix.length());
    }

    String remoteHead =
        git(repoDir, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD");
    return remoteHead == null || remoteHead.isEmpty() ? "origin/HEAD" : remoteHead;
  }

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
