#!/usr/bin/env bash

# Copyright 2026 Datastrato Inc.

# Exercises the real checkEnterpriseFilesHaveEnterpriseOnlyHeader task in an isolated worktree.
# Each negative case must fail and identify every offending path.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
source "$repo_root/dev/headers/copy-untracked-files.sh"
bash "$script_dir/test_copy_untracked_license_headers.sh"
scratch_dir="$(mktemp -d -t enterprise-license-validation.XXXXXX)"
worktree="$scratch_dir/worktree"
working_tree_patch="$scratch_dir/working-tree.patch"

cleanup() {
  local cleanup_failed=0
  if [[ -d "$worktree" ]]; then
    if ! git -C "$repo_root" worktree remove --force "$worktree" >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  fi
  if ! rm -rf "$scratch_dir"; then
    cleanup_failed=1
  fi
  return "$cleanup_failed"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

run_check() {
  local log_file="$1"
  (
    cd "$worktree"
    ./gradlew --no-daemon checkEnterpriseFilesHaveEnterpriseOnlyHeader
  ) >"$log_file" 2>&1
}

assert_failure() {
  local case_name="$1"
  local log_file="$2"
  shift 2

  if run_check "$log_file"; then
    sed -n '1,240p' "$log_file" >&2
    fail "$case_name was accepted"
  fi

  local expected_count="$#"
  grep -F \
    "checkEnterpriseFilesHaveEnterpriseOnlyHeader failed for $expected_count file(s)" \
    "$log_file" >/dev/null || {
    sed -n '1,240p' "$log_file" >&2
    fail "$case_name did not fail through the enterprise license task"
  }

  local expected_file
  for expected_file in "$@"; do
    grep -F "$expected_file" "$log_file" >/dev/null || {
      sed -n '1,240p' "$log_file" >&2
      fail "$case_name failure did not identify $expected_file"
    }
  done
  printf 'PASS: %s rejected\n' "$case_name"
}

git -C "$repo_root" diff --binary HEAD >"$working_tree_patch"
git -C "$repo_root" worktree add --detach --quiet "$worktree" HEAD
if [[ -s "$working_tree_patch" ]]; then
  git -C "$worktree" apply "$working_tree_patch"
fi
copy_untracked_files "$repo_root" "$worktree"

baseline_log="$scratch_dir/baseline.log"
if ! run_check "$baseline_log"; then
  sed -n '1,240p' "$baseline_log" >&2
  fail "baseline enterprise license check failed"
fi
printf 'PASS: baseline enterprise license check\n'

pvt_file="docs-enterprise/live-license-test/PvtLtd.java"
mkdir -p "$(dirname "$worktree/$pvt_file")"
printf '%s\n' \
  '/*' \
  ' * Copyright 2026 Datastrato Pvt Ltd.' \
  ' */' \
  'class PvtLtd {}' >"$worktree/$pvt_file"

grant_file="docs-enterprise/live-license-test/ApacheGrant.java"
printf '%s\n' \
  '/*' \
  ' * Copyright 2026 Datastrato Inc.' \
  ' * This software is licensed under the Apache License version 2.' \
  ' */' \
  'class ApacheGrant {}' >"$worktree/$grant_file"

markdown_file="docs-enterprise/live-license-test/guide.md"
printf '%s\n' \
  '---' \
  'title: Negative license fixture' \
  'license: "Copyright 2026 Datastrato Pvt Ltd."' \
  '---' >"$worktree/$markdown_file"

policy_files=(
  "buildSrc/build.gradle.kts"
  "buildSrc/src/main/java/org/apache/gravitino/license/LicenseHeaderClassifier.java"
  "buildSrc/src/test/java/org/apache/gravitino/license/TestLicenseHeaderClassifier.java"
  "dev/ci/test_enterprise_license_headers.sh"
  "dev/ci/test_copy_untracked_license_headers.sh"
  "dev/ci/test_new_file_license_headers.sh"
  "dev/headers/apply-datastrato-license-headers.sh"
  "dev/headers/copy-untracked-files.sh"
  "dev/headers/rewrite-datastrato-headers.py"
  "dev/headers/test/run.sh"
  "enterprise-licensing.gradle.kts"
  "enterprise-licensing/check-added-enterprise-files-have-enterprise-only-header.gradle.kts"
  "enterprise-licensing/check-enterprise-files-have-enterprise-only-header.gradle.kts"
  "enterprise-licensing/check-non-enterprise-files-have-asf-header.gradle.kts"
  "enterprise-licensing/shared-paths.gradle.kts"
)
for policy_file in "${policy_files[@]}"; do
  sed -i.bak \
    's/Copyright 2026 Datastrato Inc\./Copyright 2026 Datastrato Pvt Ltd./' \
    "$worktree/$policy_file"
  rm -f "$worktree/$policy_file.bak"
done

assert_failure \
  "live enterprise license negative cases" \
  "$scratch_dir/negative.log" \
  "$pvt_file" \
  "$grant_file" \
  "$markdown_file" \
  "${policy_files[@]}"

if ! cleanup; then
  trap - EXIT
  fail "could not remove the temporary worktree"
fi
trap - EXIT
printf 'ASSERT_OK: live enterprise license task rejected every negative case\n'
