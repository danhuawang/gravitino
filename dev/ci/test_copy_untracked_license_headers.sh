#!/usr/bin/env bash

# Copyright 2026 Datastrato Inc.

# Deterministic regression tests for untracked-file copying during concurrent builds.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../headers/copy-untracked-files.sh"
scratch_dir="$(mktemp -d -t license-copy-test.XXXXXX)"
trap 'rm -rf "$scratch_dir"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

new_case() {
  repo_root="$scratch_dir/$1/repo"
  worktree="$scratch_dir/$1/worktree"
  mkdir -p "$repo_root" "$worktree"
  git -C "$repo_root" init --quiet
}

(
  new_case ordinary
  mkdir -p "$repo_root/src with spaces"
  filename=$'new\nfile.sh'
  printf '#!/usr/bin/env bash\n' >"$repo_root/src with spaces/$filename"
  chmod +x "$repo_root/src with spaces/$filename"
  printf 'keep\n' >"$repo_root/.attach_pid-not-a-marker"
  printf 'ignored.txt\n' >"$repo_root/.gitignore"
  touch "$repo_root/ignored.txt"
  copy_untracked_files "$repo_root" "$worktree"
  cmp "$repo_root/src with spaces/$filename" "$worktree/src with spaces/$filename"
  [[ -x "$worktree/src with spaces/$filename" ]] || fail 'executable mode was lost'
  [[ -f "$worktree/.attach_pid-not-a-marker" ]] || fail 'ordinary dotfile was skipped'
  [[ ! -e "$worktree/ignored.txt" ]] || fail 'ignored file was copied'
)
printf 'PASS: ordinary files, unusual paths, modes, and Git ignores\n'

(
  new_case attach
  mkdir -p "$repo_root/authorizations/authorization-ranger"
  touch "$repo_root/.attach_pid123" "$repo_root/authorizations/authorization-ranger/.attach_pid19913"
  cp() {
    fail 'JVM attach marker reached cp'
  }
  copy_untracked_files "$repo_root" "$worktree"
  [[ ! -e "$worktree/.attach_pid123" ]] || fail 'root attach marker was copied'
  [[ ! -e "$worktree/authorizations/authorization-ranger/.attach_pid19913" ]] ||
    fail 'nested attach marker was copied'
)
printf 'PASS: JVM attach markers are excluded before copying\n'

(
  new_case vanished_before_copy
  touch "$repo_root/vanishing.java"
  git() {
    command git "$@" >"$scratch_dir/list-before-removal"
    rm "$repo_root/vanishing.java"
    cat "$scratch_dir/list-before-removal"
  }
  cp() {
    fail 'missing source reached cp'
  }
  copy_untracked_files "$repo_root" "$worktree"
  [[ ! -e "$worktree/vanishing.java" ]] || fail 'missing source was copied'
)
printf 'PASS: source disappearing after enumeration is skipped\n'

(
  new_case vanished_during_copy
  touch "$repo_root/vanishing.java"
  cp() {
    rm "$2"
    printf 'simulated missing source\n' >&2
    return 1
  }
  copy_untracked_files "$repo_root" "$worktree" 2>"$scratch_dir/vanished.err"
  [[ ! -s "$scratch_dir/vanished.err" ]] || fail 'expected disappearance reported an error'
  [[ ! -e "$worktree/vanishing.java" ]] || fail 'vanishing source was copied'
)
printf 'PASS: source disappearing during cp does not fail the snapshot\n'

(
  new_case copy_failure
  touch "$repo_root/Keep.java"
  cp() {
    printf 'simulated permission denied\n' >&2
    return 1
  }
  if copy_untracked_files "$repo_root" "$worktree" 2>"$scratch_dir/copy-failure.err"; then
    fail 'copy failure for an existing source was ignored'
  fi
  grep -F 'simulated permission denied' "$scratch_dir/copy-failure.err" >/dev/null ||
    fail 'copy failure diagnostic was lost'
)
printf 'PASS: real copy failures remain errors\n'
