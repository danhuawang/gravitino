#!/usr/bin/env bash
#
# Copyright 2026 Datastrato Inc.
#
# Compatibility wrapper. The fixture suite lives in test/run.sh.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${HERE}/test/run.sh"
