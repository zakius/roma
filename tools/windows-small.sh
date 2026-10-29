#!/bin/bash
# The small Windows build, dist/roma-<version>-x64.msi and dist/roma-<version>-windows-x64.zip: the full build's app
# without its Java runtime, started by packaging/windows/Launcher.cs, which finds Java 21+ on the machine or downloads
# Temurin's JRE once (about 49 MB). As finimal's small build, without libmpv. The image stays in
# desktop/build/windows-small for CI's checks.
# Runs on Windows (Git Bash) after :desktop:packageReleaseMsi :desktop:createReleaseDistributable, which leave the app
# image and the WiX toolset jpackage needs.
# Usage: tools/windows-small.sh
set -euo pipefail
cd "$(dirname "$0")/.."
jre_url=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip
jre_sha256=2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628
jre_dir=jdk-21.0.13+9-jre
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/Pins.cs" <<PINS
static class Pins
{
    public const string JreUrl = "$jre_url";
    public const string JreSha256 = "$jre_sha256";
    public const string JreDir = "$jre_dir";
}
PINS
/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe -nologo -optimize -target:winexe \
  -out:"$(cygpath -w "$tmp/roma.exe")" -win32icon:"$(cygpath -w packaging/roma.ico)" \
  -r:System.IO.Compression.dll -r:System.IO.Compression.FileSystem.dll -r:System.Windows.Forms.dll -r:System.Drawing.dll \
  "$(cygpath -w packaging/windows/Launcher.cs)" "$(cygpath -w "$tmp/Pins.cs")"

small=desktop/build/windows-small/roma
rm -rf "$small"
mkdir -p "$(dirname "$small")" dist
cp -r desktop/build/compose/binaries/main-release/app/roma "$small"
rm -rf "$small/runtime"
rm -f "$small/roma.exe" # jpackage's launcher, read-only
cp "$tmp/roma.exe" "$small/roma.exe"
(cd "$(dirname "$small")" && 7z a -tzip -mx=9 "$OLDPWD/dist/roma-$version-windows-x64.zip" roma > /dev/null)

# The installer around it, as packageReleaseMsi makes the full one (desktop/build.gradle.kts): the same upgrade code,
# so either build replaces the other.
candle=$(find desktop/build build ~/AppData/Local -name candle.exe 2> /dev/null | head -n 1)
[ -n "$candle" ] || { echo "No WiX toolset (candle.exe) found" >&2; exit 1; }
PATH="$(dirname "$candle"):$PATH" "$JAVA_HOME/bin/jpackage" --type msi --app-image "$(cygpath -w "$small")" \
  --name roma --app-version "$version" --vendor zakius --description "YouTube Music, every title and lyric romanized" \
  --license-file LICENSE --win-per-user-install --win-menu --win-menu-group roma --win-shortcut \
  --win-upgrade-uuid 6d0b7c3e-1f5a-4d7e-9a43-6f8f0b2c9e41 --dest "$(cygpath -w "$tmp/msi")"
cp "$tmp"/msi/*.msi "dist/roma-$version-x64.msi"
ls -l "dist/roma-$version-x64.msi" "dist/roma-$version-windows-x64.zip"
