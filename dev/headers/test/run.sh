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
REWRITE="${HEADERS}/rewrite-datastrato-headers.py"
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

rewrite_old="$(copy_input existing-2024.java.input RewriteOld.java)"
rewrite_current="$(copy_input current-2026.java.input RewriteCurrent.java)"
rewrite_both="$(copy_input header-plus-body.java.input RewriteBoth.java)"
rewrite_asf="$(copy_input asf.java.input RewriteAsf.java)"
rewrite_inc="$(copy_input already-inc.java.input RewriteInc.java)"
rewrite_headerless="$(copy_input headerless.java.input RewriteHeaderless.java)"
rewrite_spdx="$(copy_input rewrite-spdx.java.input RewriteSpdx.java)"
rewrite_buried20="$(copy_input rewrite-buried-after-20.java.input RewriteBuried20.java)"
rewrite_script="$(copy_input rewrite-shebang.sh.input rewrite-run.sh)"
rewrite_sql="$(copy_input rewrite-schema.sql.input rewrite-schema.sql)"
rewrite_note="$(copy_input rewrite-note.md.input rewrite-note.md)"
rewrite_xml="$(copy_input rewrite-catalog.xml.input rewrite-catalog.xml)"
rewrite_inc_grant="$(copy_input rewrite-inc-plus-grant.java.input RewriteIncGrant.java)"
rewrite_out="${WORKDIR}/rewrite.out"
python3 "${REWRITE}" "${rewrite_old}" "${rewrite_current}" "${rewrite_both}" \
  "${rewrite_asf}" "${rewrite_inc}" "${rewrite_headerless}" "${rewrite_spdx}" \
  "${rewrite_buried20}" "${rewrite_script}" "${rewrite_sql}" "${rewrite_note}" \
  "${rewrite_xml}" "${rewrite_inc_grant}" >"${rewrite_out}"
assert_same "${rewrite_old}" "${EXPECTED}/rewrite-existing-2024.java" \
  "rewrite 2024 Java to Inc. copyright-only"
assert_same "${rewrite_current}" "${EXPECTED}/rewrite-current-2026.java" \
  "rewrite current-year Pvt Ltd to Inc. and drop the Apache sentence"
assert_same "${rewrite_both}" "${EXPECTED}/rewrite-header-plus-body.java" \
  "rewrite the header without touching a body copyright string"
assert_same "${rewrite_script}" "${EXPECTED}/rewrite-shebang.sh" \
  "rewrite shell header after shebang and keep 2024"
assert_same "${rewrite_sql}" "${EXPECTED}/rewrite-schema.sql" \
  "rewrite SQL header and keep 2025"
assert_same "${rewrite_note}" "${EXPECTED}/rewrite-note.md" \
  "rewrite Markdown HTML-comment header"
assert_same "${rewrite_xml}" "${EXPECTED}/rewrite-catalog.xml" \
  "rewrite XML comment after the declaration"
assert_same "${rewrite_inc_grant}" "${EXPECTED}/rewrite-inc-plus-grant.java" \
  "drop the Apache grant on an already-Inc. header and keep 2024"
assert_unchanged "${rewrite_asf}" "${FIXTURES}/asf.java.input" "rewrite skips ASF Java"
assert_unchanged "${rewrite_inc}" "${FIXTURES}/already-inc.java.input" \
  "rewrite skips already-Inc. Java"
assert_unchanged "${rewrite_headerless}" "${FIXTURES}/headerless.java.input" \
  "rewrite does not stamp a headerless file"
assert_unchanged "${rewrite_spdx}" "${FIXTURES}/rewrite-spdx.java.input" \
  "rewrite skips SPDX Java"
assert_unchanged "${rewrite_buried20}" "${FIXTURES}/rewrite-buried-after-20.java.input" \
  "rewrite leaves copyright after line 20 alone"
grep -q 'Rewrote 8 file(s); skipped 5' "${rewrite_out}" \
  || fail "expected 8 rewrites and 5 skips, got: $(cat "${rewrite_out}")"
pass "rewrite converts existing Datastrato headers and skips ASF/SPDX/body"

if python3 "${REWRITE}" >/dev/null 2>"${WORKDIR}/rewrite-no-args.err"; then
  fail "rewrite with no files should exit"
fi
grep -q 'pass the files to rewrite' "${WORKDIR}/rewrite-no-args.err" \
  || fail "rewrite no-args missing explicit-file error"
pass "rewrite refuses to run without file arguments"

if python3 "${REWRITE}" --scan "${rewrite_old}" >/dev/null 2>"${WORKDIR}/rewrite-scan-and-files.err"; then
  fail "rewrite --scan with files should exit"
fi
grep -q 'pass files or --scan, not both' "${WORKDIR}/rewrite-scan-and-files.err" \
  || fail "rewrite --scan with files missing exclusive-mode error"
pass "rewrite refuses to mix --scan with file arguments"

scan_root="${WORKDIR}/scan-root"
mkdir -p \
  "${scan_root}/src" \
  "${scan_root}/docs-enterprise" \
  "${scan_root}/dev/headers/test/fixtures" \
  "${scan_root}/dev/ci" \
  "${scan_root}/buildSrc/src/test/java/org/apache/gravitino/license"
cp "${FIXTURES}/existing-2024.java.input" "${scan_root}/src/Keep.java"
cp "${FIXTURES}/existing-2024.java.input" "${scan_root}/docs-enterprise/page.md"
cp "${FIXTURES}/existing-2024.java.input" "${scan_root}/dev/headers/test/fixtures/old.java.input"
cp "${FIXTURES}/existing-2024.java.input" "${scan_root}/dev/ci/test_new_file_license_headers.sh"
cp "${FIXTURES}/existing-2024.java.input" \
  "${scan_root}/buildSrc/src/test/java/org/apache/gravitino/license/TestLicenseHeaderClassifier.java"
python_bin="$(command -v python3)"
PATH="/usr/bin:/bin" "${python_bin}" "${REWRITE}" --scan --root "${scan_root}" \
  >"${WORKDIR}/scan.out"
assert_same "${scan_root}/src/Keep.java" "${EXPECTED}/rewrite-existing-2024.java" \
  "--scan rewrites a source file"
assert_unchanged "${scan_root}/docs-enterprise/page.md" "${FIXTURES}/existing-2024.java.input" \
  "--scan skips docs-enterprise"
assert_unchanged "${scan_root}/dev/headers/test/fixtures/old.java.input" \
  "${FIXTURES}/existing-2024.java.input" "--scan skips stamper fixtures"
assert_unchanged \
  "${scan_root}/buildSrc/src/test/java/org/apache/gravitino/license/TestLicenseHeaderClassifier.java" \
  "${FIXTURES}/existing-2024.java.input" "--scan skips classifier test strings"
assert_unchanged "${scan_root}/dev/ci/test_new_file_license_headers.sh" \
  "${FIXTURES}/existing-2024.java.input" "--scan skips new-file CI header strings"
grep -q 'Rewrote 1 file(s); skipped 0' "${WORKDIR}/scan.out" \
  || fail "expected --scan to rewrite 1 file, got: $(cat "${WORKDIR}/scan.out")"
pass "rewrite --scan honors skip paths"

echo "OK"
