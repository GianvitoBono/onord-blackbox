#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export GRADLE_USER_HOME="$project_dir/.cache/gradle"
export TMPDIR="$project_dir/.cache/tmp"
mkdir -p "$GRADLE_USER_HOME" "$TMPDIR"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=$TMPDIR"
exec "$project_dir/gradlew" "$@"
