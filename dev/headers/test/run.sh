#!/usr/bin/env bash
#
# Copyright 2026 Datastrato Inc.
#
# Copy fixture *.input files into a temp tree, run apply / check-year, and
# compare against golden expected/ files. Fixtures use .input so RAT and
# checkDatastratoLicenseHeaders do not treat them as real source.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HEADERS="$(cd "${HERE}/.." && pwd)"
APPLY="${HEADERS}/apply-datastrato-license-headers.sh"
FIXTURES="${HERE}/fixtures"
EXPECTED="${HERE}/expected"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

export HEADER_YEAR=2026

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  echo "PASS: $*"
}

copy_input() {
  local name="$1"
  local dest="$2"
  cp "${FIXTURES}/${name}" "${WORKDIR}/${dest}"
  echo "${WORKDIR}/${dest}"
}

assert_same() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if ! diff -u "${expected}" "${actual}"; then
    fail "${label} did not match ${expected}"
  fi
  pass "${label}"
}

assert_unchanged() {
  local actual="$1"
  local original="$2"
  local label="$3"
  if ! diff -u "${original}" "${actual}"; then
    fail "${label} was rewritten"
  fi
  pass "${label}"
}

stale_year="$(copy_input existing-2024.java.input StaleYear.java)"
check_err="${WORKDIR}/check.err"
if "${APPLY}" check-year "${stale_year}" >/dev/null 2>"${check_err}"; then
  fail "check-year should fail on 2024"
fi
grep -q 'Copyright 2024' "${check_err}" || fail "check-year missing 2024 detail"
pass "check-year fails stale 2024"

fresh="$(copy_input headerless.java.input Fresh.java)"
old="$(copy_input existing-2024.java.input Old.java)"
asf="$(copy_input asf.java.input Asf.java)"
script="$(copy_input shebang.sh.input run.sh)"
note="$(copy_input note.md.input note.md)"
sql="$(copy_input schema.sql.input schema.sql)"
xml="$(copy_input catalog.xml.input catalog.xml)"
current="$(copy_input current-2026.java.input Current.java)"
already_inc="$(copy_input already-inc.java.input AlreadyInc.java)"
unsupported="${WORKDIR}/notes.input"
cp "${FIXTURES}/headerless.java.input" "${unsupported}"
chmod 755 "${script}"

apply_out="${WORKDIR}/apply.out"
"${APPLY}" apply "${fresh}" "${old}" "${asf}" "${script}" "${note}" "${sql}" "${xml}" "${current}" "${already_inc}" "${unsupported}" \
  >"${apply_out}"

assert_same "${fresh}" "${EXPECTED}/headerless.java" "stamp headerless Java at ${HEADER_YEAR}"
assert_unchanged "${old}" "${FIXTURES}/existing-2024.java.input" "skip existing 2024 Datastrato Java"
assert_unchanged "${asf}" "${FIXTURES}/asf.java.input" "skip yearless ASF Java"
assert_same "${script}" "${EXPECTED}/shebang.sh" "stamp shell after shebang"
assert_same "${note}" "${EXPECTED}/note.md" "stamp markdown HTML comment"
assert_same "${sql}" "${EXPECTED}/schema.sql" "stamp SQL comment"
assert_same "${xml}" "${EXPECTED}/catalog.xml" "stamp XML after the declaration"
assert_unchanged "${current}" "${FIXTURES}/current-2026.java.input" "skip current-year Pvt Ltd Java"
assert_unchanged "${already_inc}" "${FIXTURES}/already-inc.java.input" "skip already-current US-entity Java"
assert_unchanged "${unsupported}" "${FIXTURES}/headerless.java.input" "skip unsupported .input"
octal_mode() {
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"
}

[[ "$(octal_mode "${script}")" == "755" ]] \
  || fail "stamped shell lost execute mode"
[[ "$(octal_mode "${fresh}")" =~ ^[0-7]{3,4}$ ]] \
  || fail "stamped Java mode is not octal: $(octal_mode "${fresh}")"
pass "stamped shell keeps execute mode"
pass "file_mode is octal-only after stamp"

grep -q 'Stamped 5 file(s); skipped 5' "${apply_out}" \
  || fail "expected 5 stamps and 5 skips, got: $(cat "${apply_out}")"
pass "apply stamped 5 headerless files and skipped existing headers"

for stamped in "${fresh}" "${script}" "${note}" "${sql}" "${xml}"; do
  if grep -q 'This software is licensed under the Apache License version 2.' "${stamped}"; then
    fail "stamped ${stamped} still has the Apache license sentence"
  fi
  grep -q 'Copyright 2026 Datastrato Inc.' "${stamped}" \
    || fail "stamped ${stamped} missing US-entity copyright"
done
pass "stamped files are copyright-only US entity"

grep -q 'Copyright 2024 Datastrato Pvt Ltd.' "${old}" \
  || fail "apply rewrote the 2024 year or entity"
grep -q 'This software is licensed under the Apache License version 2.' "${old}" \
  || fail "apply dropped the legacy Apache sentence on an old file"
grep -q 'Copyright 2026 Datastrato Pvt Ltd.' "${current}" \
  || fail "apply rewrote Pvt Ltd on a current-year file"
pass "apply leaves existing Datastrato headers unchanged"

"${APPLY}" check-year "${asf}" >/dev/null || fail "check-year should ignore ASF"
pass "check-year ignores ASF"

"${APPLY}" check-year "${fresh}" "${current}" "${already_inc}" >/dev/null \
  || fail "check-year should accept 2026 on new and existing files"
pass "check-year accepts 2026"

if "${APPLY}" check-year "${old}" >/dev/null 2>"${WORKDIR}/old-check.err"; then
  fail "check-year should still fail a 2024 header that apply left alone"
fi
pass "check-year still reports a 2024 header that apply skipped"

export HEADER_YEAR=2027
override="${WORKDIR}/Override.java"
printf 'class Override {}\n' >"${override}"
"${APPLY}" apply "${override}" >/dev/null
grep -q 'Copyright 2027 Datastrato Inc.' "${override}" || fail "HEADER_YEAR override did not stamp 2027"
if grep -q 'This software is licensed under the Apache License version 2.' "${override}"; then
  fail "HEADER_YEAR override stamped the Apache license sentence"
fi
pass "HEADER_YEAR override stamps 2027"

year_keep="${WORKDIR}/YearKeep.java"
cp "${FIXTURES}/already-inc.java.input" "${year_keep}"
"${APPLY}" apply "${year_keep}" >/dev/null
assert_unchanged "${year_keep}" "${FIXTURES}/already-inc.java.input" \
  "HEADER_YEAR override does not bump an existing US-entity year"
pass "apply does not bump an existing US-entity year"

unset HEADER_YEAR
calendar_year="$(date +%Y)"
default_year="${WORKDIR}/DefaultYear.java"
printf 'class DefaultYear {}\n' >"${default_year}"
"${APPLY}" apply "${default_year}" >/dev/null
grep -q "Copyright ${calendar_year} Datastrato Inc." "${default_year}" \
  || fail "unset HEADER_YEAR did not stamp ${calendar_year}"
if grep -q 'This software is licensed under the Apache License version 2.' "${default_year}"; then
  fail "unset HEADER_YEAR stamped the Apache license sentence"
fi
pass "unset HEADER_YEAR stamps ${calendar_year}"

export HEADER_YEAR=2026
no_args_err="${WORKDIR}/no-args.err"
if "${APPLY}" apply >/dev/null 2>"${no_args_err}"; then
  fail "apply with no files should exit"
fi
grep -q 'does not scan the repo' "${no_args_err}" || fail "apply no-args missing explicit-file error"
pass "apply refuses to run without file arguments"

old_only="$(copy_input existing-2024.java.input OldOnly.java)"
old_only_out="${WORKDIR}/old-only.out"
"${APPLY}" apply "${old_only}" >"${old_only_out}"
assert_unchanged "${old_only}" "${FIXTURES}/existing-2024.java.input" \
  "apply on an old 2024 file leaves the year alone"
grep -q 'Stamped 0 file(s); skipped 1' "${old_only_out}" \
  || fail "expected 0 stamps and 1 skip on old file, got: $(cat "${old_only_out}")"
pass "apply on a given old file skips it"

raw_buried="$(copy_input buried-copyright.java.input RawBuried.java)"
"${APPLY}" check-year "${raw_buried}" >/dev/null \
  || fail "check-year should ignore a body copyright with no header"
pass "check-year ignores body copyright without a header"

buried="$(copy_input buried-copyright.java.input Buried.java)"
buried_asf="$(copy_input buried-asf.java.input BuriedAsf.java)"
both="$(copy_input header-plus-body.java.input Both.java)"
buried_out="${WORKDIR}/buried.out"
"${APPLY}" apply "${buried}" "${buried_asf}" "${both}" >"${buried_out}"
assert_same "${buried}" "${EXPECTED}/buried-copyright.java" \
  "stamp headerless Java that quotes copyright in the body"
assert_same "${buried_asf}" "${EXPECTED}/buried-asf.java" \
  "stamp headerless Java that quotes ASF text in the body"
assert_unchanged "${both}" "${FIXTURES}/header-plus-body.java.input" \
  "leave an existing header and a body copyright string alone"
grep -q 'Stamped 2 file(s); skipped 1' "${buried_out}" \
  || fail "expected 2 stamps and 1 skip, got: $(cat "${buried_out}")"
pass "body copyright and ASF strings are not treated as headers"

check_year_buried="${WORKDIR}/check-buried.err"
if ! "${APPLY}" check-year "${buried}" >/dev/null 2>"${check_year_buried}"; then
  fail "check-year should accept a stamped header and ignore the body year"
fi
pass "check-year ignores a copyright year in the body"

echo "OK"
