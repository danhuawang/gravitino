/*
 * Copyright 2026 Datastrato Inc.
 */
/**
 * Build-time license logic for the enterprise licensing policy.
 *
 * <p>This package lives in {@code buildSrc} and is used by the applied
 * {@code enterprise-licenses.gradle.kts} script (not by runtime code). It is kept
 * separate from the upstream-owned {@code build.gradle.kts} so upstream syncs
 * do not touch Datastrato license policy.
 *
 * <p>Contents:
 * <ul>
 *   <li>{@link LicenseHeaderClassifier} - pure header classification for the
 *       "new Datastrato files must be copyright-only (no Apache license sentence)" rule.
 *       Reads only the header region (first {@link LicenseHeaderClassifier#HEADER_LINE_COUNT}
 *       lines) so a copyright string later in the body cannot satisfy the check.
 *   <li>{@link NewFileFinder} - git plumbing that lists files added vs a base ref
 *       (e.g. {@code origin/main}, or a release branch such as {@code branch-1.3}
 *       for cherry-pick PRs). Local builds without an explicit base ask Git for
 *       the current branch's remote upstream, falling back to the remote default.
 *       Returns {@code null} when the base ref cannot be resolved so the build fails
 *       instead of silently skipping enforcement.
 * </ul>
 */
package org.apache.gravitino.license;
