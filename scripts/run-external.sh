#!/bin/sh
# Run development commands with writable caches and temp files on external disk.
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export GRADLE_USER_HOME="$project_dir/.cache/gradle"
export CARGO_HOME="$project_dir/.cache/cargo"
export CARGO_TARGET_DIR="$project_dir/.cache/cargo-target"
export npm_config_cache="$project_dir/.cache/npm"
export TMPDIR="$project_dir/.cache/tmp"
mkdir -p "$GRADLE_USER_HOME" "$CARGO_HOME" "$CARGO_TARGET_DIR" "$npm_config_cache" "$TMPDIR"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=$TMPDIR"
exec "$@"
