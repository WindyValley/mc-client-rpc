#!/bin/bash
# fill-neoforge-libs.sh — Sequential curl downloader for NeoForge installer.
#
# The NeoForge installer's concurrent downloader is frequently connection-refused
# by Maven repositories over residential ISP links. This script downloads each
# library ONE AT A TIME via curl, which works reliably.
#
# Usage:
#   1. Run the installer once: java -jar neoforge-installer.jar --installServer 2>&1 | tee inst.log
#   2. Extract URLs: grep -oE 'https://[^ ]+\.jar' inst.log | sort -u > /tmp/urls.txt
#   3. Run this script: bash fill-neoforge-libs.sh /tmp/urls.txt
#   4. Check logs for any non-.jar failures (e.g. @zip, .lzma) and download manually
#   5. Re-run installer: java -jar neoforge-installer.jar --installServer
#
# The script maps Maven download URLs to the local libraries/ directory structure:
#   https://maven.neoforged.net/releases/net/neoforged/foo/1.0/foo-1.0.jar
#   -> libraries/net/neoforged/foo/1.0/foo-1.0.jar
#
# Generic tool (no host/password values). Run it ON the test server (paths are Linux).
set -euo pipefail

URL_FILE="${1:-/tmp/urls.txt}"
WORK_DIR="${2:-$PWD}"

if [ ! -f "$URL_FILE" ]; then
    echo "Usage: $0 <url-file> [work-dir]"
    echo "  url-file: file with download URLs (one per line)"
    echo "  work-dir: MC server directory (default: current dir)"
    exit 1
fi

echo "[fill-neoforge-libs] Work dir: $WORK_DIR"
echo "[fill-neoforge-libs] URL file: $URL_FILE ($(wc -l < "$URL_FILE") lines)"
cd "$WORK_DIR"

ok=0; skip=0; fail=0

while read -r url; do
    # Strip known Maven repository prefixes to get the relative path
    rel="${url#https://maven.neoforged.net/releases/}"
    rel="${rel#https://repo.maven.apache.org/maven2/}"
    rel="${rel#https://libraries.minecraft.net/}"

    dest="libraries/$rel"

    if [ -f "$dest" ]; then
        skip=$((skip+1))
        continue
    fi

    mkdir -p "$(dirname "$dest")"
    if curl -sL --max-time 90 --retry 2 -o "$dest" "$url" && [ -s "$dest" ]; then
        ok=$((ok+1))
    else
        rm -f "$dest"
        fail=$((fail+1))
        echo "[FAIL] $rel"
    fi
done < "$URL_FILE"

echo "[fill-neoforge-libs] Done: $ok downloaded, $skip already existed, $fail failed"

# Remind about non-.jar artifacts that need manual download
echo "[fill-neoforge-libs] Note: If the installer still fails, check for non-.jar artifacts"
echo "  (like @zip or .lzma files) that were NOT in the URL list above."
echo "  Run 'grep -oP 'Downloading library from \K\S+' inst.log | grep -v '\.jar'' to find them."
