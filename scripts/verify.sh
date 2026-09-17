#!/bin/sh
set -eu

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/course-release-diagnostics.XXXXXX")
trap 'rm -rf "$BUILD_DIR"' EXIT

find "$REPO_DIR/src/main/java" "$REPO_DIR/src/test/java" -name '*.java' -print > "$BUILD_DIR/sources.txt"
javac -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
java -cp "$BUILD_DIR/classes" learning.agenterrors.ReleaseFailureServiceTest
