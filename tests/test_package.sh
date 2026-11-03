#!/bin/sh
# Package tests: dist/sambok.zip must be a NEMESIS package that carries exactly the descriptor and
# the built APK, and the APK inside it must be the file build.sh produced, byte for byte.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ZIP="$ROOT/dist/sambok.zip"
APK="$ROOT/build/sambok.apk"

command -v unzip >/dev/null 2>&1 || {
    echo "test_package.sh: no unzip; cannot inspect the package" >&2
    exit 2
}

passed=0
failed=0

t() {
    # t <name> <actual> <expected>
    if [ "$2" = "$3" ]; then
        echo "ok $((passed + failed + 1)) - $1"
        passed=$((passed + 1))
    else
        echo "not ok $((passed + failed + 1)) - $1"
        echo "  expected: $3"
        echo "  actual:   $2"
        failed=$((failed + 1))
    fi
}

# zip_list prints one member per line; an absent or unreadable zip prints nothing.
zip_list() {
    [ -f "$ZIP" ] || return 0
    unzip -Z1 "$ZIP" 2>/dev/null || true
}

t "package has descriptor" "$(zip_list | grep -c NEMESIS.data)" "1"
t "package carries the apk" "$(zip_list | grep -c Sambok.apk)" "1"

# Nothing stale: the descriptor and the APK it names, and no leftovers from an earlier build.
members=$(zip_list | LC_ALL=C sort | tr '\n' ' ')
t "package holds only the descriptor and the apk" "${members% }" "NEMESIS.data app/Sambok.apk"

same=no
if [ -f "$ZIP" ] && [ -f "$APK" ] && unzip -p "$ZIP" app/Sambok.apk 2>/dev/null | cmp -s - "$APK"; then
    same=yes
fi
t "apk inside the package is the built apk" "$same" "yes"

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
