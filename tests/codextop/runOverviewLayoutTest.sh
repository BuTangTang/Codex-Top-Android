#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
if [ -z "${JAVA:-}" ] || [ -z "${JAVAC:-}" ] || [ -z "${JP:-}" ] || [ -z "${GSON:-}" ]; then
  echo "JAVA, JAVAC, JP and GSON must be set" >&2
  exit 1
fi
TMP=$(mktemp -d "${TMPDIR:-/tmp}/overview-layout.XXXX")
trap 'rm -rf "$TMP"' EXIT
"$JAVAC" -encoding UTF-8 -cp "$JP:$GSON" -d "$TMP" "$ROOT/tests/codextop/SettingsOverviewLayoutTest.java"
"$JAVA" -Xmx32m -cp "$TMP:$JP:$GSON" com.butang.codextop.SettingsOverviewLayoutTest "$ROOT"
