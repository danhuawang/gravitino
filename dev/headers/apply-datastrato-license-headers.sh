#!/usr/bin/env bash
#
# Copyright 2026 Datastrato Inc.
#
# Stamp the Datastrato copyright-only header with the calendar year of
# generation. Replace YEAR in the sibling template. apply only inserts a header
# when the file has none. Existing Datastrato headers keep their year and entity.
# Never rewrite Apache ASF, SPDX, or Apache-2.0 headers.
#
# Usage:
#   apply-datastrato-license-headers.sh apply FILE...
#   apply-datastrato-license-headers.sh check-year FILE...
#
# Only the files you pass are considered. HEADER_YEAR overrides the calendar
# year (default: date +%Y).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"
TEMPLATE="${HERE}/datastrato-apache-2.txt"
YEAR="${HEADER_YEAR:-$(date +%Y)}"
DATASTRATO_COPYRIGHT_RE='Copyright[[:space:]]+[0-9]{4}[[:space:]]+Datastrato (Pvt Ltd|Inc)\.'
# Cap aligned with LicenseHeaderClassifier.HEADER_LINE_COUNT. Apache RAT reads
# the first 50 lines; Checkstyle and license-maven-plugin / HawkEye match the
# leading comment after skipLine (shebang / XML), not the body.
HEADER_LINE_COUNT=20

usage() {
  echo "Usage: $0 apply FILE..." >&2
  echo "       $0 check-year FILE..." >&2
  echo "Pass the files to stamp or check. Existing Datastrato headers keep their year; ASF headers are not rewritten." >&2
  exit 2
}

# Print the leading comment after an optional shebang or XML declaration.
header_region() {
  local file="$1"
  local style skip=0 first
  style="$(comment_style "${file}")"
  first="$(head -n 1 "${file}" || true)"
  if keep_leading_line "${style}" "${first}"; then
    skip=1
  fi
  awk -v style="${style}" -v skip="${skip}" -v max="${HEADER_LINE_COUNT}" '
    function blank() { return $0 ~ /^[[:space:]]*$/ }
    function is_hash() { return $0 ~ /^[[:space:]]*#/ }
    function is_sql() { return $0 ~ /^[[:space:]]*--/ }
    function star_start() { return $0 ~ /^[[:space:]]*\/\*/ }
    function star_end() { return $0 ~ /\*\// }
    function html_start() { return $0 ~ /^[[:space:]]*<!--/ }
    function html_end() { return $0 ~ /-->/ }
    function slash() { return $0 ~ /^[[:space:]]*\/\// }
    function starts() {
      if (style == "block") return star_start() || slash()
      if (style == "html") return html_start()
      if (style == "hash") return is_hash()
      if (style == "sql") return is_sql()
      return 0
    }
    function still_header() {
      if (style == "hash") return is_hash()
      if (style == "sql") return is_sql()
      if (style == "block" && in_block) return 1
      if (style == "block") return slash()
      if (style == "html" && in_block) return 1
      return 0
    }
    {
      if (NR > max) exit
      if (skip && NR == 1) next
      if (!in_header && !done_header) {
        if (blank()) next
        if (starts()) {
          in_header = 1
          if (style == "block" && star_start()) in_block = 1
          if (style == "html" && html_start()) in_block = 1
        } else {
          exit
        }
      }
      if (in_header) {
        if (!still_header()) exit
        print
        if (style == "block" && in_block && star_end()) exit
        if (style == "html" && in_block && html_end()) exit
      }
    }
  ' "${file}"
}

has_datastrato_copyright() {
  header_region "$1" | grep -Eq "${DATASTRATO_COPYRIGHT_RE}"
}

has_asf_or_spdx_header() {
  header_region "$1" | grep -Eq \
    'Licensed to the Apache Software Foundation|SPDX-License-Identifier:|Licensed under the Apache License, Version 2\.0'
}

datastrato_year() {
  header_region "$1" \
    | grep -oE "${DATASTRATO_COPYRIGHT_RE}" 2>/dev/null \
    | head -n 1 \
    | grep -oE '[0-9]{4}' \
    | head -n 1 \
    || true
}

comment_style() {
  local base
  base="$(basename "$1" | tr '[:upper:]' '[:lower:]')"
  case "${base}" in
    dockerfile|jenkinsfile) echo hash ;;
    *.java|*.scala|*.kt|*.kts) echo block ;;
    *.md|*.html|*.xml) echo html ;;
    *.sql) echo sql ;;
    *.py|*.sh|*.conf|*.yml|*.yaml|*.properties) echo hash ;;
    *) echo "" ;;
  esac
}

render_body() {
  awk 'BEGIN {p=0} /^Copyright YEAR / {p=1} p {print}' "${TEMPLATE}" \
    | sed "s/YEAR/${YEAR}/"
}

keep_leading_line() {
  local style="$1"
  local first="$2"
  if [[ "${first}" == "#!"* ]] && [[ "${style}" == "hash" || "${style}" == "sql" ]]; then
    return 0
  fi
  if [[ "${style}" == "html" ]] && [[ "${first}" == "<?xml"* ]]; then
    return 0
  fi
  return 1
}

# GNU first: on Linux, `stat -f` is `--file-system` and still prints, so a
# BSD-first probe feeds overlayfs metadata into chmod.
file_mode() {
  local mode
  mode="$(stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1")"
  if [[ ! "${mode}" =~ ^[0-7]{3,4}$ ]]; then
    echo "file_mode: expected an octal mode for $1, got: ${mode}" >&2
    return 1
  fi
  printf '%s\n' "${mode}"
}

replace_file() {
  local file="$1"
  local tmp="$2"
  local mode
  mode="$(file_mode "${file}")" || {
    rm -f "${tmp}"
    return 1
  }
  chmod "${mode}" "${tmp}" || {
    rm -f "${tmp}"
    return 1
  }
  mv "${tmp}" "${file}"
}

format_header() {
  local style="$1"
  local line
  case "${style}" in
    block)
      echo "/*"
      while IFS= read -r line; do
        [[ -n "${line}" ]] && echo " * ${line}"
      done
      echo " */"
      ;;
    hash)
      echo "#"
      while IFS= read -r line; do
        [[ -n "${line}" ]] && echo "# ${line}"
      done
      echo "#"
      ;;
    html)
      echo "<!--"
      while IFS= read -r line; do
        [[ -n "${line}" ]] && echo "  ${line}"
      done
      echo "-->"
      echo
      ;;
    sql)
      echo "--"
      while IFS= read -r line; do
        [[ -n "${line}" ]] && echo "-- ${line}"
      done
      echo "--"
      ;;
  esac
}

prepend_header() {
  local file="$1"
  local style="$2"
  local header tmp first
  header="$(render_body | format_header "${style}")"
  tmp="$(mktemp "${file}.XXXXXX")"
  first="$(head -n 1 "${file}" || true)"
  if keep_leading_line "${style}" "${first}"; then
    {
      echo "${first}"
      printf '%s\n' "${header}"
      tail -n +2 "${file}"
    } >"${tmp}" || {
      rm -f "${tmp}"
      return 1
    }
  else
    {
      printf '%s\n' "${header}"
      cat "${file}"
    } >"${tmp}" || {
      rm -f "${tmp}"
      return 1
    }
  fi
  replace_file "${file}" "${tmp}"
}

is_source_file() {
  [[ -f "$1" ]] && [[ -n "$(comment_style "$1")" ]]
}

require_files() {
  if (($# == 0)); then
    echo "Error: pass the new file(s) to process. The script does not scan the repo." >&2
    usage
  fi
}

cmd_apply() {
  require_files "$@"
  local stamped=0 skipped=0 file style
  for file in "$@"; do
    [[ -z "${file}" ]] && continue
    if [[ "${file}" != /* ]]; then
      file="${ROOT}/${file}"
    fi
    if ! is_source_file "${file}"; then
      skipped=$((skipped + 1))
      continue
    fi
    if has_datastrato_copyright "${file}"; then
      skipped=$((skipped + 1))
      continue
    fi
    if has_asf_or_spdx_header "${file}"; then
      skipped=$((skipped + 1))
      continue
    fi
    style="$(comment_style "${file}")"
    prepend_header "${file}" "${style}"
    echo "Stamped ${file}"
    stamped=$((stamped + 1))
  done
  echo "Stamped ${stamped} file(s); skipped ${skipped} (existing Datastrato header, ASF/SPDX, unsupported type, or missing file)."
}

cmd_check_year() {
  require_files "$@"
  local failed=0 file year
  for file in "$@"; do
    [[ -z "${file}" ]] && continue
    if [[ "${file}" != /* ]]; then
      file="${ROOT}/${file}"
    fi
    [[ -f "${file}" ]] || continue
    year="$(datastrato_year "${file}")"
    [[ -n "${year}" ]] || continue
    if [[ "${year}" != "${YEAR}" ]]; then
      echo "${file} has Copyright ${year} (expected ${YEAR})" >&2
      failed=$((failed + 1))
    fi
  done
  if ((failed > 0)); then
    echo "Datastrato license year check failed for ${failed} file(s)." >&2
    return 1
  fi
}

if [[ ! -f "${TEMPLATE}" ]]; then
  echo "Missing template: ${TEMPLATE}" >&2
  exit 1
fi
if [[ ! "${YEAR}" =~ ^[0-9]{4}$ ]]; then
  echo "HEADER_YEAR must be a 4-digit calendar year: ${YEAR}" >&2
  exit 2
fi

case "${1:-}" in
  apply)
    shift
    cmd_apply "$@"
    ;;
  check-year)
    shift
    cmd_check_year "$@"
    ;;
  *)
    usage
    ;;
esac
