#!/bin/sh
# Builds the USB stick layout in usb/, ready to copy onto a blank FAT32 or exFAT drive.
#
# NEMESIS reads one action per insertion from NEMESIS.data at the root, so the stick carries the
# package zips side by side in packages/ and the alternative descriptors in alternatives/.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ZIP="$ROOT/dist/sambok.zip"
OUT="$ROOT/usb"

if [ ! -f "$ZIP" ]; then
    echo "tools/make_usb.sh: no $ZIP — run tools/make_package.sh first" >&2
    exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/packages" "$OUT/alternatives"
cp "$ZIP" "$OUT/packages/sambok.zip"

printf '[nemesis]\nversion=1.0\ntype=install\npath=packages/sambok.zip\n' > "$OUT/NEMESIS.data"
cp "$OUT/NEMESIS.data" "$OUT/alternatives/NEMESIS.data.sambok"

printf '[nemesis]\nversion=1.0\ntype=install\npath=packages/adb.tcp.zip\n' \
    > "$OUT/alternatives/NEMESIS.data.adb"

printf '[nemesis]\nversion=1.0\ntype=list\npath=packages\n' \
    > "$OUT/alternatives/NEMESIS.data.list"

cat > "$OUT/README.txt" <<'TEXT'
One insertion runs one action: the root NEMESIS.data names it.

  NEMESIS.data            installs packages/sambok.zip  (the default)
  packages/               every package zip lives here, side by side

To install the ADB package first
  1. put adb.tcp.zip in packages/
  2. copy alternatives/NEMESIS.data.adb over NEMESIS.data
  3. insert, confirm, remove the stick when it finishes
  4. copy alternatives/NEMESIS.data.sambok back over NEMESIS.data
  5. insert again for Sambok

Or list mode
  copy alternatives/NEMESIS.data.list over NEMESIS.data and insert with Android
  running: Manager opens its store on this stick and lists what is in packages/.
  If it does not list, use the single-install route above.
TEXT

echo "tools/make_usb.sh: $OUT"
