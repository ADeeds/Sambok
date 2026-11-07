#!/bin/sh
# Packs build/sambok.apk and package/NEMESIS.data into dist/sambok.zip, the NEMESIS package.
#
# Re-runnable from clean: the staging tree is made fresh under mktemp and dist/ is replaced, so no
# file from an earlier build can survive into the package. The APK is copied, never rebuilt here;
# run build.sh first. Zip layout is NEMESIS.data at the root plus the APK at the path [app.0] names.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DESC="$ROOT/package/NEMESIS.data"
APK="$ROOT/build/sambok.apk"
DIST="$ROOT/dist"
ZIP="$DIST/sambok.zip"

[ -f "$DESC" ] || {
    echo "make_package.sh: missing $DESC" >&2
    exit 2
}
[ -f "$APK" ] || {
    echo "make_package.sh: missing $APK; run build.sh first" >&2
    exit 2
}

STAGE=$(mktemp -d) || exit 2
trap 'rm -rf "$STAGE"' EXIT HUP INT TERM

if [ -s "$ROOT/build/version.txt" ]; then
    PKG_VERSION=$(cat "$ROOT/build/version.txt")
else
    PKG_VERSION="1.0.$(date +%m%d.%H%M%S)"
fi

mkdir -p "$STAGE/app"
sed "s/^package-version=1\.0$/package-version=$PKG_VERSION/" "$DESC" > "$STAGE/NEMESIS.data"
cp "$APK" "$STAGE/app/Sambok.apk"

rm -rf "$DIST"
mkdir -p "$DIST"

# zip -D keeps directory entries out of the archive, so the members are exactly the two files. jar
# is the fallback for a machine with a JDK but no Info-ZIP. -X drops the per-file extra fields.
if command -v zip >/dev/null 2>&1; then
    (cd "$STAGE" && zip -q -X -D "$ZIP" NEMESIS.data app/Sambok.apk) || exit 1
else
    (cd "$STAGE" && jar cfM "$ZIP" NEMESIS.data app/Sambok.apk) || exit 1
fi

[ -s "$ZIP" ] || {
    echo "make_package.sh: $ZIP was not written" >&2
    exit 1
}

echo "make_package.sh: $ZIP ($(wc -c < "$ZIP") bytes)"
