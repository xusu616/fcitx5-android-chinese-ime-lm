#!/bin/sh
# SPDX-License-Identifier: MIT
# Host-side policy tests that need no Rime or Android. Run from any directory.
set -eu
cd "$(dirname "$0")/.."
for t in contextual-score-policy commit-context candidate-policy transposition; do
    [ -f "tests/$t.cpp" ] || continue
    g++ -std=c++17 -O1 -Wall -Wextra -UNDEBUG -I. "tests/$t.cpp" -o "/tmp/neural-$t"
    "/tmp/neural-$t"
    echo "$t OK"
done
