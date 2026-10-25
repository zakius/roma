#!/bin/bash
# Release APKs, signed with the key named in keystore.properties (outside git):
#   dist/roma-<version>.apk: R8-shrunk, Zopfli-recompressed (tools/repack_apk.py), zipaligned (arm64 and x86_64 in one).
#   dist/roma-dict-<dictionary version>.apk.gz: the Japanese dictionary app (:dict), only when that file is missing.
#     The APK keeps the dictionary uncompressed (roma maps it in place); the gzip is only for the download.
# roma fetches the dictionary from its GitHub release (dict-<version>), or from ROMA_DICT_BASE if set.
# The GitHub workflow (.github/workflows/release.yml) runs this for a v* tag.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/JetBrains/Toolbox/apps/air/jbr}
export PATH="$JAVA_HOME/bin:$PATH"
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
tools=$(ls -d "$sdk/build-tools/"* | sort -V | tail -n 1)
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
dictVersion=$(sed -n 's/^val dictRelease = "\(.*\)"/\1/p' core/build.gradle.kts)
dict="dist/roma-dict-$dictVersion.apk.gz"
tasks=(:core:test :app:assembleRelease)
[ -f "$dict" ] || tasks+=(:dict:assembleRelease)
./gradlew -q "${tasks[@]}" ${ROMA_DICT_BASE:+-Proma.dictBase=$ROMA_DICT_BASE}
source <(sed 's/^\([a-zA-Z]*\)=\(.*\)$/\1="\2"/' keystore.properties)
sign() { "$tools/apksigner" sign --ks "$storeFile" --ks-pass "pass:$storePassword" --ks-key-alias "$keyAlias" --key-pass "pass:$keyPassword" --out "$2" "$1"; rm -f "$2.idsig"; }
mkdir -p dist
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
out="dist/roma-$version.apk"
python3 tools/repack_apk.py app/build/outputs/apk/release/app-release-unsigned.apk "$tmp/repacked.apk"
"$tools/zipalign" -f -P 16 4 "$tmp/repacked.apk" "$tmp/aligned.apk"
sign "$tmp/aligned.apk" "$out"
ls -l "$out"
if [ ! -f "$dict" ]; then
  "$tools/zipalign" -f -P 16 4 dict/build/outputs/apk/release/dict-release-unsigned.apk "$tmp/dict.apk"
  sign "$tmp/dict.apk" "$tmp/dict-signed.apk"
  pigz -11 -c "$tmp/dict-signed.apk" > "$dict.tmp" && mv "$dict.tmp" "$dict"
  ls -l "$dict"
fi
