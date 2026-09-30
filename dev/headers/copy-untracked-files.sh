#!/usr/bin/env bash

# Copyright 2026 Datastrato Inc.

# Copies local untracked changes into the isolated license-test worktree.
copy_untracked_files() {
  local repo_root="$1"
  local worktree="$2"
  local untracked_file source_file copy_error

  while IFS= read -r -d '' untracked_file; do
    # JVM attach markers can appear and disappear while other Gradle tests run.
    if [[ "${untracked_file##*/}" =~ ^\.attach_pid[0-9]+$ ]]; then
      continue
    fi
    source_file="$repo_root/$untracked_file"
    if [[ ! -e "$source_file" && ! -L "$source_file" ]]; then
      continue
    fi

    mkdir -p "$(dirname "$worktree/$untracked_file")" || return 1
    if copy_error="$(cp -p "$source_file" "$worktree/$untracked_file" 2>&1)"; then
      continue
    fi
    # The source can disappear after the existence check. Keep reporting other copy errors.
    if [[ -e "$source_file" || -L "$source_file" ]]; then
      printf '%s\n' "$copy_error" >&2
      return 1
    fi
  done < <(git -C "$repo_root" ls-files --others --exclude-standard -z)
}
