#!/usr/bin/env bash

# Copyright 2026 Datastrato Pvt Ltd.

# Applies one change as an uncommitted patch to isolated worktrees, then verifies that
# checkNewFileLicenseHeaders uses each tracking branch's remote upstream without CI input or a
# Gradle base-ref override. A valid committed file must pass and an invalid committed file must fail.
#
# Usage:
#   dev/ci/test_new_file_license_headers.sh [remote branch ...]
#
# Defaults to validating origin/main and origin/branch-1.3. Override the patch range when needed:
#   PATCH_BASE_REF=<base> PATCH_HEAD_REF=<head> dev/ci/test_new_file_license_headers.sh

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
patch_base_ref="${PATCH_BASE_REF:-HEAD^}"
patch_head_ref="${PATCH_HEAD_REF:-HEAD}"
targets=()
scratch_dir="$(mktemp -d -t new-file-license-validation.XXXXXX)"
patch_file="$scratch_dir/change.patch"
worktrees=()
branches=()

cleanup() {
  local worktree branch cleanup_failed=0
  for worktree in "${worktrees[@]:-}"; do
    if [[ -n "$worktree" ]]; then
      if ! git -C "$repo_root" worktree remove --force "$worktree" >/dev/null 2>&1; then
        cleanup_failed=1
      fi
    fi
  done
  for branch in "${branches[@]:-}"; do
    if [[ -n "$branch" ]]; then
      if ! git -C "$repo_root" branch -D "$branch" >/dev/null 2>&1; then
        cleanup_failed=1
      fi
    fi
  done
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
  local worktree="$1"
  local log_file="$2"
  (
    cd "$worktree"
    env -u GITHUB_BASE_REF -u ORG_GRADLE_PROJECT_licenseBaseRef \
      ./gradlew checkNewFileLicenseHeaders
  ) >"$log_file" 2>&1
}

assert_selected_base() {
  local log_file="$1"
  local expected_ref="$2"
  grep -F "checkNewFileLicenseHeaders: using base ref '$expected_ref'" "$log_file" >/dev/null || {
    sed -n '1,240p' "$log_file" >&2
    fail "license check did not use $expected_ref"
  }
}

assert_added_file() {
  local worktree="$1"
  local base_ref="$2"
  local expected_path="$3"
  local merge_base
  merge_base="$(git -C "$worktree" merge-base HEAD "$base_ref")"
  git -C "$worktree" diff --diff-filter=A --name-only "$merge_base" |
    grep -Fx "$expected_path" >/dev/null ||
    fail "$expected_path is not visible as added versus $base_ref"
}

patch_base="$(git -C "$repo_root" rev-parse "$patch_base_ref")"
patch_head="$(git -C "$repo_root" rev-parse "$patch_head_ref")"
git -C "$repo_root" diff --binary "$patch_base" "$patch_head" >"$patch_file"
[[ -s "$patch_file" ]] || fail "patch range $patch_base_ref..$patch_head_ref is empty"

if [[ "$#" -eq 0 ]]; then
  targets=(main branch-1.3)
else
  targets=("$@")
fi

printf 'Local new-file license validation\n'
printf 'Patch: %s..%s\n' "$patch_base" "$patch_head"
printf 'CI base variable: unset\n'
printf 'Gradle base override: none\n'

for target in "${targets[@]}"; do
  remote_ref="origin/$target"
  git -C "$repo_root" show-ref --verify --quiet "refs/remotes/$remote_ref" ||
    fail "missing $remote_ref; fetch it before running this validation"

  safe_target="${target//\//-}"
  safe_target="${safe_target//./-}"
  worktree="$scratch_dir/$safe_target"
  branch="$safe_target-local-license-validation-$$"

  if git -C "$repo_root" show-ref --verify --quiet "refs/heads/$branch"; then
    fail "temporary branch $branch already exists"
  fi

  printf '\n[%s] create temporary worktree from %s\n' "$target" "$remote_ref"
  git -C "$repo_root" worktree add --detach --quiet "$worktree" "$remote_ref"
  worktrees+=("$worktree")
  git -C "$worktree" switch --quiet -c "$branch"
  branches+=("$branch")
  git -C "$worktree" branch --set-upstream-to="$remote_ref" "$branch" >/dev/null

  actual_upstream="$(git -C "$worktree" rev-parse --abbrev-ref --symbolic-full-name '@{upstream}')"
  [[ "$actual_upstream" == "$remote_ref" ]] ||
    fail "$target worktree tracks $actual_upstream instead of $remote_ref"

  git -C "$worktree" apply --check "$patch_file"
  git -C "$worktree" apply "$patch_file"
  printf '[%s] applied patch cleanly\n' "$target"

  valid_rel="local-license-validation/$safe_target/Valid.java"
  mkdir -p "$(dirname "$worktree/$valid_rel")"
  printf '%s\n' \
    '/*' \
    ' * Copyright 2026 Datastrato Pvt Ltd.' \
    ' */' \
    'package local.license;' >"$worktree/$valid_rel"
  git -C "$worktree" add "$valid_rel"
  git -C "$worktree" \
    -c user.name='Local License Validation' \
    -c user.email='local-license-validation@invalid' \
    commit --quiet --no-verify -m 'test: add valid local license file'

  assert_added_file "$worktree" "$remote_ref" "$valid_rel"

  valid_log="$scratch_dir/$safe_target-valid.log"
  if ! run_check "$worktree" "$valid_log"; then
    sed -n '1,240p' "$valid_log" >&2
    fail "$target rejected a valid committed new file"
  fi
  assert_selected_base "$valid_log" "$remote_ref"
  printf '[%s] PASS valid committed file; selected %s\n' "$target" "$remote_ref"

  invalid_rel="local-license-validation/$safe_target/Invalid.java"
  printf '%s\n' \
    '/*' \
    ' * Copyright 2026 Datastrato Pvt Ltd.' \
    ' * This software is licensed under the Apache License version 2.' \
    ' */' \
    'package local.license;' >"$worktree/$invalid_rel"
  git -C "$worktree" add "$invalid_rel"
  git -C "$worktree" \
    -c user.name='Local License Validation' \
    -c user.email='local-license-validation@invalid' \
    commit --quiet --no-verify -m 'test: add invalid local license file'

  assert_added_file "$worktree" "$remote_ref" "$invalid_rel"

  invalid_log="$scratch_dir/$safe_target-invalid.log"
  if run_check "$worktree" "$invalid_log"; then
    sed -n '1,240p' "$invalid_log" >&2
    fail "$target accepted an invalid committed new file"
  fi
  assert_selected_base "$invalid_log" "$remote_ref"
  grep -F "$invalid_rel" "$invalid_log" >/dev/null || {
    sed -n '1,240p' "$invalid_log" >&2
    fail "$target failure did not identify $invalid_rel"
  }
  printf '[%s] PASS invalid committed file was rejected\n' "$target"
done

if ! cleanup; then
  trap - EXIT
  fail 'could not remove every temporary worktree and branch'
fi
trap - EXIT
printf '\nASSERT_OK: local validation passed for %s\n' "${targets[*]}"
