#!/bin/bash
# Release APKs, signed with the key named in keystore.properties (outside git):
#   dist/roma-<version>.apk: R8-shrunk, Zopfli-recompressed (tools/repack_apk.py), zipaligned (arm64 and x86_64 in one).
#   dist/roma-dict-<dictionary version>.apk.gz: the Japanese dictionary app (:dict-app), only when that file is missing.
#     The APK keeps the dictionary uncompressed (roma maps it in place); the gzip is only for the download.
# roma fetches the dictionary from its GitHub release (dict-<version>), or from ROMA_DICT_BASE if set.
# Usage: release.sh [app] [dict] [test]; all three by default (test: the core tests, which need the slim dictionary).
# The GitHub workflow (.github/workflows/release.yml) runs them in separate jobs for each push to master, signed (with
# RELEASE set) when the version is new.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/JetBrains/Toolbox/apps/air/jbr}
export PATH="$JAVA_HOME/bin:$PATH"
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
tools=$(ls -d "$sdk/build-tools/"* | sort -V | tail -n 1)
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
dictVersion=$(sed -n 's/^val dictRelease = "\(.*\)"/\1/p' core/build.gradle.kts)
dict="dist/roma-dict-$dictVersion.apk.gz"
parts=" ${*:-app dict test} "
app=; buildDict=; test=
[[ $parts == *" app "* ]] && app=1
[[ $parts == *" dict "* && ! -f $dict ]] && buildDict=1
[[ $parts == *" test "* ]] && test=1
tasks=()
[ -n "$test" ] && tasks+=(:core:test)
[ -n "$app" ] && tasks+=(:app:assembleRelease)
[ -n "$buildDict" ] && tasks+=(:dict-app:assembleRelease)
./gradlew -q "${tasks[@]}" ${ROMA_DICT_BASE:+-Proma.dictBase=$ROMA_DICT_BASE}
[ -n "$app$buildDict" ] || exit 0 # tests only: nothing to sign
# No key (CI on a merge that isn't a release, or a checkout without one): built, not signed. A release must be signed.
if [ ! -f keystore.properties ]; then
  [ -z "${RELEASE:-}" ] || { echo "keystore.properties missing for a release" >&2; exit 1; }
  echo "No keystore.properties: built, not signed"; exit 0
fi
source <(sed 's/^\([a-zA-Z]*\)=\(.*\)$/\1="\2"/' keystore.properties)
sign() { "$tools/apksigner" sign --ks "$storeFile" --ks-pass "pass:$storePassword" --ks-key-alias "$keyAlias" --key-pass "pass:$keyPassword" --out "$2" "$1"; rm -f "$2.idsig"; }
mkdir -p dist
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
if [ -n "$app" ]; then
  out="dist/roma-$version.apk"
  python3 tools/repack_apk.py app/build/outputs/apk/release/app-release-unsigned.apk "$tmp/repacked.apk"
  "$tools/zipalign" -f -P 16 4 "$tmp/repacked.apk" "$tmp/aligned.apk"
  sign "$tmp/aligned.apk" "$out"
  ls -l "$out"
fi
if [ -n "$buildDict" ]; then
  "$tools/zipalign" -f -P 16 4 dict-app/build/outputs/apk/release/dict-app-release-unsigned.apk "$tmp/dict.apk"
  sign "$tmp/dict.apk" "$tmp/dict-signed.apk"
  pigz -11 -c "$tmp/dict-signed.apk" > "$dict.tmp" && mv "$dict.tmp" "$dict"
  ls -l "$dict"
fi
