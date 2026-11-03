#!/bin/sh
# Compiles the pure logic and the host tests, then runs every tests/*Test.java.
# No Android, no JUnit: plain javac/java so the logic runs on the workstation.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)

find_bin() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        printf '%s\n' "$JAVA_HOME/bin"
        return 0
    fi
    if command -v javac >/dev/null 2>&1; then
        dirname "$(command -v javac)"
        return 0
    fi
    for dir in /usr/lib/jvm/*/bin "$HOME/bin/android-studio/jbr/bin" /tmp/jdk*/bin; do
        if [ -x "$dir/javac" ] && [ -x "$dir/java" ]; then
            printf '%s\n' "$dir"
            return 0
        fi
    done
    return 1
}

BIN=$(find_bin) || {
    echo "run.sh: no JDK found; set JAVA_HOME" >&2
    exit 2
}
JAVAC="$BIN/javac"
JAVA="$BIN/java"

# The target is Java 7 (dx, API 19). JDK 9+ dropped -source 7; its floor is 8.
if "$JAVAC" --help 2>&1 | grep -q -e '--release'; then
    FLAGS="--release 8 -Xlint:-options"
else
    FLAGS="-source 7 -target 7"
fi

BUILD=$(mktemp -d) || exit 2
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM

SOURCES=""
for f in "$ROOT"/src/pure/*.java "$ROOT"/src/car/CarState.java; do
    [ -f "$f" ] && SOURCES="$SOURCES $f"
done
for f in "$ROOT"/tests/*.java; do
    [ -f "$f" ] && SOURCES="$SOURCES $f"
done

if [ -z "$SOURCES" ]; then
    echo "run.sh: nothing to compile" >&2
    exit 2
fi

# shellcheck disable=SC2086
"$JAVAC" $FLAGS -encoding UTF-8 -d "$BUILD" $SOURCES || exit 1

status=0
found=0
for test in "$ROOT"/tests/*Test.java; do
    [ -f "$test" ] || continue
    found=1
    class=$(basename "$test" .java)
    echo "== $class"
    "$JAVA" -cp "$BUILD" "$class" || status=1
done
# Shell tests cover what does not run in the JVM: the package the packer builds.
for test in "$ROOT"/tests/test_*.sh; do
    [ -f "$test" ] || continue
    found=1
    echo "== $(basename "$test" .sh)"
    sh "$test" || status=1
done
if [ "$found" -eq 0 ]; then
    echo "run.sh: no tests found" >&2
    exit 2
fi
exit $status
