/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package org.apache.gravitino.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hermetic tests for the build-time license enforcement in {@code enterprise-licenses.gradle.kts}.
 *
 * <p>Covers the two scenarios raised in review of PR #1314:
 * <ol>
 *   <li>Cherry-pick PR into a release branch (e.g. {@code branch-1.3}) - the base ref
 *       follows the PR target (not hardcoded to {@code main}), so a cherry-picked
 *       Datastrato file carrying the Apache sentence is still caught, while an
 *       ASF port is not false-positive'd.
 *   <li>Upstream-sync PR - ASF-headered files pass, a coincidental Datastrato
 *       mention buried past the header region does not trigger, and a real
 *       Datastrato+Apache header is still caught.
 *   <li>Local checkout of a release branch - Git's configured upstream supplies
 *       {@code origin/branch-1.3}, not {@code origin/main}, so historical release-branch
 *       files are not treated as new.
 * </ol>
 *
 * <p>The git plumbing is exercised against a temporary git repo (no real repo
 * or remote needed), mirroring what {@code checkNewFileLicenseHeaders} does.
 */
class TestLicenseHeaderClassifier {

  @Test
  void detectsDatastratoCopyright() {
    assertTrue(
        LicenseHeaderClassifier.hasDatastratoCopyright(
            "/*\n * Copyright 2026 Datastrato Pvt Ltd.\n */"));
    assertFalse(
        LicenseHeaderClassifier.hasDatastratoCopyright(
            "/*\n * Licensed to the Apache Software Foundation (ASF) under one\n */"));
  }

  @Test
  void detectsApacheSentence() {
    assertTrue(LicenseHeaderClassifier.hasApacheSentence(LicenseHeaderClassifier.APACHE_SENTENCE));
    assertFalse(LicenseHeaderClassifier.hasApacheSentence("Copyright 2026 Datastrato Pvt Ltd."));
  }

  @Test
  void readsOnlyHeaderRegion(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("Buried.java");
    List<String> lines = new ArrayList<>();
    lines.add("/*");
    lines.add(" * Licensed to the Apache Software Foundation (ASF) under one");
    lines.add(" */");
    lines.add("package pkg;");
    for (int i = 0; i < 45; i++) {
      lines.add("// pad " + i);
    }
    // line 50: a coincidental Datastrato mention must NOT satisfy the check
    lines.add("// Copyright 2026 Datastrato Pvt Ltd.");
    Files.write(file, lines);
    String header = LicenseHeaderClassifier.readHeader(file);
    assertTrue(header.contains("Licensed to the Apache Software Foundation"));
    assertFalse(
        header.contains("Copyright 2026 Datastrato Pvt Ltd."),
        "header region must not include line 50");
    assertFalse(LicenseHeaderClassifier.isViolation(file));
  }

  @Test
  void flagsDatastratoWithApacheSentence(@TempDir Path dir)
      throws IOException {
    Path file = dir.resolve("A.java");
    Files.write(
        file,
        Arrays.asList(
            "/*",
            " * Copyright 2026 Datastrato Pvt Ltd.",
            " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
            " */",
            "package pkg;"));
    assertTrue(LicenseHeaderClassifier.isViolation(file));
  }

  @Test
  void passesCopyrightOnlyAndAsfPorts(@TempDir Path dir)
      throws IOException {
    Path copyrightOnly = dir.resolve("CopyrightOnly.java");
    Files.write(
        copyrightOnly,
        Arrays.asList("/*", " * Copyright 2026 Datastrato Pvt Ltd.", " */", "package pkg;"));
    assertFalse(LicenseHeaderClassifier.isViolation(copyrightOnly));

    Path asf = dir.resolve("AsfPort.java");
    Files.write(
        asf,
        Arrays.asList(
            "/*",
            " * Licensed to the Apache Software Foundation (ASF) under one",
            " * or more contributor license agreements.",
            " */",
            "package pkg;"));
    assertFalse(LicenseHeaderClassifier.isViolation(asf));
  }

  // --- Integration: NewFileFinder against a temp git repo (Rory's two cases) ---

  @Test
  void cherryPickIntoReleaseBranchCatchesViolation(@TempDir Path dir)
      throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "branch-1.3");
    runGit(repo, "checkout", "branch-1.3");
    runGit(repo, "checkout", "-b", "pr/cherry-pick");
    writeFile(
        repo,
        "pkg/A.java",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/A.java");
    commit(repo, "-m", "add A (Datastrato + Apache sentence)");

    List<String> added = NewFileFinder.addedFiles(repo.toFile(), "branch-1.3");
    assertNotNull(added, "base ref branch-1.3 must be resolvable");
    assertEquals(1, added.size());
    assertTrue(added.contains("pkg/A.java"));
    assertTrue(LicenseHeaderClassifier.isViolation(repo.resolve("pkg/A.java")));
  }

  @Test
  void cherryPickCopyrightOnlyAndAsfPortsPass(@TempDir Path dir)
      throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "branch-1.3");
    runGit(repo, "checkout", "branch-1.3");
    runGit(repo, "checkout", "-b", "pr/cherry-pick");
    writeFile(
        repo,
        "pkg/CopyrightOnly.java",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " */",
        "package pkg;");
    writeFile(
        repo,
        "pkg/AsfPort.java",
        "/*",
        " * Licensed to the Apache Software Foundation (ASF) under one",
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg");
    commit(repo, "-m", "add copyright-only + ASF port");
    List<String> added = NewFileFinder.addedFiles(repo.toFile(), "branch-1.3");
    assertNotNull(added);
    assertEquals(2, added.size());
    for (String f : added) {
      assertFalse(LicenseHeaderClassifier.isViolation(repo.resolve(f)));
    }
  }

  @Test
  void upstreamSyncPassesAsfFilesAndCatchesRealViolation(
      @TempDir Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "upstream-main");
    runGit(repo, "checkout", "upstream-main");
    runGit(repo, "checkout", "-b", "pr/upstream-sync");
    writeFile(
        repo,
        "pkg/Asf.java",
        "/*",
        " * Licensed to the Apache Software Foundation (ASF) under one",
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/Asf.java");
    commit(repo, "-m", "sync ASF file");
    // a real violation among synced files is still caught
    writeFile(
        repo,
        "pkg/Real.java",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/Real.java");
    commit(repo, "-m", "add real Datastrato+Apache");
    List<String> added = NewFileFinder.addedFiles(repo.toFile(), "upstream-main");
    assertNotNull(added);
    assertEquals(2, added.size());
    int violations = 0;
    for (String f : added) {
      if (LicenseHeaderClassifier.isViolation(repo.resolve(f))) {
        violations++;
      }
    }
    assertEquals(1, violations, "only the real Datastrato+Apache file violates");
  }

  @Test
  void returnsNullWhenBaseRefUnresolvable(@TempDir Path dir)
      throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "checkout", "-b", "pr/no-base");
    writeFile(
        repo,
        "pkg/A.java",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/A.java");
    commit(repo, "-m", "add A");
    // origin/missing-branch is not fetched -> merge-base fails -> null
    assertNull(NewFileFinder.addedFiles(repo.toFile(), "origin/missing-branch"));
  }

  // --- File-type coverage (CUJ-6/7/8) ---

  @Test
  void supportedKotlinFileIsChecked(@TempDir Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "branch-1.3");
    runGit(repo, "checkout", "branch-1.3");
    runGit(repo, "checkout", "-b", "pr/kt");
    writeFile(
        repo,
        "pkg/Build.kt",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/Build.kt");
    commit(repo, "-m", "add kt (Datastrato + Apache sentence)");
    List<String> added = NewFileFinder.addedFiles(repo.toFile(), "branch-1.3");
    assertNotNull(added);
    assertEquals(1, added.size());
    assertTrue(added.contains("pkg/Build.kt"));
    assertTrue(LicenseHeaderClassifier.isViolation(repo.resolve("pkg/Build.kt")));
  }

  @Test
  void dockerfileAndSuffixedVariantAreChecked(@TempDir Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "upstream-main");
    runGit(repo, "checkout", "upstream-main");
    runGit(repo, "checkout", "-b", "pr/docker");
    writeFile(
        repo,
        "Dockerfile.iceberg-rest",
        "#",
        "# Copyright 2026 Datastrato Pvt Ltd.",
        "# " + LicenseHeaderClassifier.APACHE_SENTENCE,
        "#");
    runGit(repo, "add", "Dockerfile.iceberg-rest");
    commit(repo, "-m", "add Dockerfile.iceberg-rest");
    List<String> added = NewFileFinder.addedFiles(repo.toFile(), "upstream-main");
    assertNotNull(added);
    assertEquals(1, added.size());
    assertTrue(added.contains("Dockerfile.iceberg-rest"));
    assertTrue(LicenseHeaderClassifier.isViolation(repo.resolve("Dockerfile.iceberg-rest")));
  }

  @Test
  void nonSourceFileIsSkipped(@TempDir Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "upstream-main");
    runGit(repo, "checkout", "upstream-main");
    runGit(repo, "checkout", "-b", "pr/docs");
    // a .md file is out of scope: even with both markers it must NOT be flagged
    writeFile(
        repo,
        "README.md",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
        " */",
        "# README");
    runGit(repo, "add", "README.md");
    commit(repo, "-m", "add README.md");
    List<String> added = NewFileFinder.addedFiles(repo.toFile(), "upstream-main");
    assertNotNull(added);
    assertEquals(1, added.size());
    assertTrue(added.contains("README.md"));
    // out of scope by file type - the task skips it before isViolation is considered
    assertFalse(LicenseHeaderClassifier.isSupportedFile("README.md"));
    // the header carries both markers, so isViolation would be true if it were in scope;
    // the file-type skip is what protects non-source files from being flagged
    assertTrue(LicenseHeaderClassifier.isViolation(repo.resolve("README.md")));
  }

  @Test
  void preservesExactPathWithNewline(@TempDir Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "upstream-main");
    runGit(repo, "checkout", "upstream-main");
    runGit(repo, "checkout", "-b", "pr/quoted");
    // a filename containing a newline; --name-only would C-quote it, -z preserves it raw
    Path weird = repo.resolve("pkg").resolve("A\nB.java");
    Files.createDirectories(weird.getParent());
    Files.write(
        weird,
        Arrays.asList("/*", " * Copyright 2026 Datastrato Pvt Ltd.", " */", "package pkg;"));
    runGit(repo, "add", "pkg/A\nB.java");
    commit(repo, "-m", "add file with newline in name");
    List<String> added = NewFileFinder.addedFiles(repo.toFile(), "upstream-main");
    assertNotNull(added);
    assertEquals(1, added.size());
    assertTrue(
        added.contains("pkg/A\nB.java"),
        "exact path with newline must be preserved (NUL-delimited output)");
  }

  // --- Local release-branch base inference (Jerry's branch-1.3 local build) ---

  @Test
  void inferBaseRefUsesConfiguredReleaseUpstream(@TempDir Path dir)
      throws Exception {
    Path repo = divergedMainAndRelease(dir);

    assertEquals("origin/branch-1.3", NewFileFinder.inferBaseRef(repo.toFile()));
    assertEquals(
        "origin/branch-1.3", NewFileFinder.resolveBaseRef(repo.toFile(), null));
    assertEquals(
        "origin/main", NewFileFinder.resolveBaseRef(repo.toFile(), "origin/main"));

    List<String> vsInferred =
        NewFileFinder.addedFiles(repo.toFile(), NewFileFinder.inferBaseRef(repo.toFile()));
    assertNotNull(vsInferred);
    assertTrue(vsInferred.isEmpty(), "historical 1.3 files must not look new vs the release tip");

    List<String> vsMain = NewFileFinder.addedFiles(repo.toFile(), "origin/main");
    assertNotNull(vsMain);
    assertEquals(1, vsMain.size());
    assertTrue(vsMain.contains("pkg/Legacy.java"));
  }

  @Test
  void inferBaseRefOnFeatureBranchUsesConfiguredReleaseUpstream(@TempDir Path dir)
      throws Exception {
    Path repo = divergedMainAndRelease(dir);
    runGit(repo, "checkout", "-b", "pr/fix");
    runGit(repo, "branch", "--set-upstream-to=origin/branch-1.3", "pr/fix");
    writeFile(
        repo,
        "pkg/New.java",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/New.java");
    commit(repo, "-m", "add new file");

    assertEquals("origin/branch-1.3", NewFileFinder.inferBaseRef(repo.toFile()));
    List<String> added =
        NewFileFinder.addedFiles(repo.toFile(), NewFileFinder.inferBaseRef(repo.toFile()));
    assertNotNull(added);
    assertEquals(1, added.size());
    assertTrue(added.contains("pkg/New.java"));
    assertTrue(LicenseHeaderClassifier.isViolation(repo.resolve("pkg/New.java")));
  }

  @Test
  void inferBaseRefOnReleaseBranchWithLocalCommitsUsesRemoteRelease(@TempDir Path dir)
      throws Exception {
    Path repo = divergedMainAndRelease(dir);
    writeFile(
        repo,
        "pkg/New.java",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/New.java");
    commit(repo, "-m", "add new file directly to release branch");

    assertEquals("origin/branch-1.3", NewFileFinder.inferBaseRef(repo.toFile()));
    List<String> added =
        NewFileFinder.addedFiles(repo.toFile(), NewFileFinder.inferBaseRef(repo.toFile()));
    assertNotNull(added);
    assertEquals(1, added.size());
    assertTrue(added.contains("pkg/New.java"));
    assertTrue(LicenseHeaderClassifier.isViolation(repo.resolve("pkg/New.java")));
  }

  @Test
  void inferBaseRefOnMainUsesConfiguredUpstream(@TempDir Path dir) throws Exception {
    Path repo = divergedMainAndRelease(dir);
    runGit(repo, "checkout", "main");
    assertEquals("origin/main", NewFileFinder.inferBaseRef(repo.toFile()));
  }

  @Test
  void inferBaseRefWithoutUpstreamUsesRemoteDefault(@TempDir Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    runGit(repo, "remote", "add", "origin", ".");
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "-M", "local-work");
    runGit(repo, "update-ref", "refs/remotes/origin/main", revParse(repo, "HEAD"));
    runGit(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main");

    assertEquals("origin/main", NewFileFinder.inferBaseRef(repo.toFile()));
  }

  @Test
  void inferBaseRefOnDetachedHeadUsesRemoteDefault(@TempDir Path dir) throws Exception {
    Path repo = divergedMainAndRelease(dir);
    runGit(repo, "checkout", "--detach");

    assertEquals("origin/main", NewFileFinder.inferBaseRef(repo.toFile()));
    assertEquals("origin/main", NewFileFinder.resolveBaseRef(repo.toFile(), null));
  }

  @Test
  void inferBaseRefWithoutRemoteHeadUsesOriginHead(@TempDir Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    runGit(repo, "remote", "add", "origin", ".");
    commit(repo, "--allow-empty", "-m", "base");
    runGit(repo, "branch", "-M", "local-work");

    assertEquals("origin/HEAD", NewFileFinder.inferBaseRef(repo.toFile()));
  }

  @Test
  void originRefFromGithubBranchIgnoresBlankValues() {
    assertNull(NewFileFinder.originRefFromGithubBranch(null));
    assertNull(NewFileFinder.originRefFromGithubBranch(""));
    assertNull(NewFileFinder.originRefFromGithubBranch("   "));
    assertEquals("origin/main", NewFileFinder.originRefFromGithubBranch("main"));
    assertEquals("origin/branch-1.3", NewFileFinder.originRefFromGithubBranch(" branch-1.3 "));
  }

  @Test
  void resolveBaseRefTreatsBlankConfiguredRefAsMissing(@TempDir Path dir) throws Exception {
    Path repo = divergedMainAndRelease(dir);

    assertEquals("origin/branch-1.3", NewFileFinder.resolveBaseRef(repo.toFile(), ""));
    assertEquals("origin/branch-1.3", NewFileFinder.resolveBaseRef(repo.toFile(), "  "));
  }

  // --- helpers ---

  /**
   * Temp repo where {@code origin/main} and {@code origin/branch-1.3} diverged: main has extra
   * empty commits, and branch-1.3 added a legacy Datastrato+Apache file. HEAD is left on
   * {@code branch-1.3}.
   */
  private static Path divergedMainAndRelease(Path dir) throws Exception {
    Path repo = dir.resolve("repo");
    gitInit(repo);
    runGit(repo, "remote", "add", "origin", ".");
    commit(repo, "--allow-empty", "-m", "fork");
    runGit(repo, "branch", "-M", "main");
    String fork = revParse(repo, "HEAD");

    commit(repo, "--allow-empty", "-m", "main-1");
    commit(repo, "--allow-empty", "-m", "main-2");
    runGit(repo, "update-ref", "refs/remotes/origin/main", revParse(repo, "HEAD"));
    runGit(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main");
    runGit(repo, "branch", "--set-upstream-to=origin/main", "main");

    runGit(repo, "checkout", "-b", "branch-1.3", fork);
    writeFile(
        repo,
        "pkg/Legacy.java",
        "/*",
        " * Copyright 2026 Datastrato Pvt Ltd.",
        " * " + LicenseHeaderClassifier.APACHE_SENTENCE,
        " */",
        "package pkg;");
    runGit(repo, "add", "pkg/Legacy.java");
    commit(repo, "-m", "1.3 historical file");
    runGit(repo, "update-ref", "refs/remotes/origin/branch-1.3", revParse(repo, "HEAD"));
    runGit(repo, "branch", "--set-upstream-to=origin/branch-1.3", "branch-1.3");
    return repo;
  }

  private static String revParse(Path repo, String rev) throws Exception {
    return runGitOutput(repo, "rev-parse", rev);
  }

  private static String runGitOutput(Path repo, String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.addAll(Arrays.asList(args));
    Process process = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int read;
    while ((read = process.getInputStream().read(chunk)) > 0) {
      buffer.write(chunk, 0, read);
    }
    int exit = process.waitFor();
    assertEquals(0, exit, "git " + String.join(" ", args) + " failed: " + buffer);
    return buffer.toString(StandardCharsets.UTF_8.name()).trim();
  }

  private static void gitInit(Path repo) throws Exception {
    Files.createDirectories(repo);
    runGit(repo, "init");
    runGit(repo, "config", "user.email", "t@t.t");
    runGit(repo, "config", "user.name", "t");
  }

  private static void runGit(Path repo, String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.addAll(Arrays.asList(args));
    Process process = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
    drain(process);
    int exit = process.waitFor();
    assertEquals(0, exit, "git " + String.join(" ", args) + " failed");
  }

  private static void commit(Path repo, String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.add("commit");
    cmd.addAll(Arrays.asList(args));
    Process process = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
    drain(process);
    int exit = process.waitFor();
    assertEquals(0, exit, "git commit failed");
  }

  private static void writeFile(Path repo, String rel, String... lines) throws IOException {
    Path file = repo.resolve(rel);
    Files.createDirectories(file.getParent());
    Files.write(file, Arrays.asList(lines));
  }

  private static void drain(Process process) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int read;
    while ((read = process.getInputStream().read(chunk)) > 0) {
      buffer.write(chunk, 0, read);
    }
  }
}
