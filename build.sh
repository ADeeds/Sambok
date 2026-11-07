#!/bin/sh
# Builds Sambok's APK from the sources in src/, with no Gradle: aapt packages the manifest and
# resources, javac compiles against the platform jar, d8 dexes for API 19, and apksigner signs
# v1. `dx` was removed from the SDK's build-tools; d8 is its replacement and produces the same
# dex 035 for minSdk 19, so it is used when dx is absent.
#
# The signing key lives beside the sources (and is git-ignored) so that a later build installs
# over the running one on the head unit. Deleting it means uninstalling before the next install.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
OUT="$ROOT/build"
APK="$OUT/sambok.apk"
KEYSTORE="$ROOT/.sambok.keystore"
STOREPASS=sambok
ALIAS=sambok

find_bin() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        printf '%s\n' "$JAVA_HOME/bin"
        return 0
    fi
    if command -v javac >/dev/null 2>&1; then
        dirname "$(command -v javac)"
        return 0
    fi
    for dir in "$HOME/bin/android-studio/jbr/bin" /usr/lib/jvm/*/bin "$HOME/bin/jdk"*/bin; do
        if [ -x "$dir/javac" ] && [ -x "$dir/java" ]; then
            printf '%s\n' "$dir"
            return 0
        fi
    done
    return 1
}

BIN=$(find_bin) || {
    echo "build.sh: no JDK found; set JAVA_HOME" >&2
    exit 2
}
JAVAC="$BIN/javac"
KEYTOOL="$BIN/keytool"
# apksigner is a script that runs plain `java` from the PATH.
PATH="$BIN:$PATH"
export PATH

[ -d "$SDK/build-tools" ] || {
    echo "build.sh: no build-tools under $SDK" >&2
    exit 2
}

# The newest build-tools, and the lowest platform jar: API 19 is not installed, so the lowest
# available jar is the closest the compiler gets to the unit's framework.
BUILD_TOOLS=$(ls -d "$SDK"/build-tools/* | sort -V | tail -n 1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | head -n 1)
PLATFORM_JAR="$PLATFORM/android.jar"
AAPT="$BUILD_TOOLS/aapt"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
D8="$BUILD_TOOLS/d8"

for tool in "$AAPT" "$ZIPALIGN" "$APKSIGNER"; do
    [ -x "$tool" ] || {
        echo "build.sh: missing $tool" >&2
        exit 2
    }
done

echo "build.sh: platform $PLATFORM_JAR"
echo "build.sh: build-tools $BUILD_TOOLS"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

VERSION_CODE=$(date +%s)
VERSION_NAME="1.0.$(date +%m%d.%H%M%S)"
printf '%s\n' "$VERSION_NAME" > "$OUT/version.txt"

# 1. Manifest and resources into an APK, and R.java out of it for the compiler.
"$AAPT" package -f -m \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    -J "$OUT/gen" \
    -M "$ROOT/AndroidManifest.xml" \
    -S "$ROOT/res" \
    -A "$ROOT/assets" \
    -I "$PLATFORM_JAR" \
    -F "$OUT/sambok-unsigned.apk" || exit 1

# 2. Compile. Java 8 bytecode is the floor this JDK offers (javac 20 removed -source 7); the
#    platform jar is the boot classpath so nothing from the host JDK can leak into the app.
# shellcheck disable=SC2086
"$JAVAC" -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$PLATFORM_JAR" \
    -d "$OUT/classes" \
    "$ROOT"/src/pure/*.java \
    "$ROOT"/src/car/*.java \
    "$ROOT"/src/app/*.java \
    "$OUT"/gen/com/sambok/R.java || exit 1

# 3. Dex for API 19.
# Every class file, nested ones and the generated R included: d8 only dexes what it is given.
CLASSES=$(find "$OUT/classes" -name '*.class' | sort)
if [ -x "$BUILD_TOOLS/dx" ]; then
    "$BUILD_TOOLS/dx" --dex --output="$OUT/dex/classes.dex" "$OUT/classes" || exit 1
else
    # shellcheck disable=SC2086
    "$D8" --min-api 19 --release --lib "$PLATFORM_JAR" --output "$OUT/dex" $CLASSES || exit 1
fi
[ -f "$OUT/dex/classes.dex" ] || {
    echo "build.sh: no classes.dex produced" >&2
    exit 1
}

# 4. Put the dex into the APK, align it, sign it v1.
(cd "$OUT/dex" && "$AAPT" add "$OUT/sambok-unsigned.apk" classes.dex >/dev/null) || exit 1
"$ZIPALIGN" -f 4 "$OUT/sambok-unsigned.apk" "$OUT/sambok-aligned.apk" || exit 1

if [ ! -f "$KEYSTORE" ]; then
    echo "build.sh: creating $KEYSTORE"
    "$KEYTOOL" -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass "$STOREPASS" \
        -keypass "$STOREPASS" \
        -alias "$ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Sambok" >/dev/null 2>&1 || {
        echo "build.sh: keytool failed" >&2
        exit 1
    }
fi

"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$STOREPASS" \
    --ks-key-alias "$ALIAS" \
    --v1-signing-enabled true \
    --v2-signing-enabled false \
    --v3-signing-enabled false \
    --min-sdk-version 19 \
    --out "$APK" \
    "$OUT/sambok-aligned.apk" || exit 1

rm -f "$OUT/sambok-unsigned.apk" "$OUT/sambok-aligned.apk"

echo "build.sh: $APK ($(wc -c < "$APK") bytes)"
