#!/bin/bash
# Release APK: R8-shrunk release build, Zopfli-recompressed (tools/repack_apk.py), zipaligned and signed with
# the key named in keystore.properties (outside git). Output: dist/roma-<version>.apk.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/JetBrains/Toolbox/apps/air/jbr}
export PATH="$JAVA_HOME/bin:$PATH"
tools=$(ls -d "$HOME/Android/Sdk/build-tools/"* | sort -V | tail -n 1)
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
./gradlew -q :core:test :app:assembleRelease
in=app/build/outputs/apk/release/app-release-unsigned.apk
tmp=$(mktemp -d)
python3 tools/repack_apk.py "$in" "$tmp/repacked.apk"
"$tools/zipalign" -f -P 16 4 "$tmp/repacked.apk" "$tmp/aligned.apk"
source <(sed 's/^\([a-zA-Z]*\)=\(.*\)$/\1="\2"/' keystore.properties)
mkdir -p dist
"$tools/apksigner" sign --ks "$storeFile" --ks-pass "pass:$storePassword" --ks-key-alias "$keyAlias" --key-pass "pass:$keyPassword" --out "dist/roma-$version.apk" "$tmp/aligned.apk"
rm -rf "$tmp" "dist/roma-$version.apk.idsig"
ls -l "dist/roma-$version.apk"
