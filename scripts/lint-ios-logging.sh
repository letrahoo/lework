#!/usr/bin/env bash
# Kotlin/Native cannot check Objective-C format arguments at a variadic boundary.
# Ban the Foundation logger in iOS Kotlin; use Kotlin logging or a typed bridge.
set -eu
cd "$(git rev-parse --show-toplevel)"
if git grep --untracked -n -w -e NSLog -- ':(glob)app/shared/src/iosMain/**/*.kt'; then
  printf '%s\n' 'Use logRuntimeEvent (or println inside the logger), not Foundation NSLog in iOS Kotlin.' >&2
  exit 1
else
  status=$?
  [[ $status == 1 ]] || exit "$status"  # No match is success; a git error is not.
fi
