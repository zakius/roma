#!/bin/bash
# The desktop app for Debian/Ubuntu and Fedora: dist/roma_<version>_amd64.deb and dist/roma-<version>-1.x86_64.rpm,
# from the release app image's app files (ProGuard-shrunk). Both install to /opt/roma with /usr/bin/roma and run on
# the distribution's Java 21+, so its fixes come with system updates; as finimal's packages.
# rpmbuild comes from the host (Ubuntu: the rpm package) or, without it, a Fedora container.
# Usage: tools/linux-packages.sh [--no-gradle]
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ "${1:-}" = --no-gradle ] || ./gradlew -q :desktop:createReleaseDistributable
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
root=$stage/root
mkdir -p "$root/opt/roma" "$root/usr/bin" "$root/usr/share/applications" "$root/usr/share/icons/hicolor/scalable/apps" "$root/usr/share/metainfo" dist
app=$PWD/desktop/build/compose/binaries/main-release/app/roma/lib/app
cp -a "$app"/libskiko-*.so "$app"/*.jar "$root/opt/roma/"
cp -a "$app/resources" "$root/opt/roma/"
rm -rf "$root/opt/roma/resources/windows"
chmod 755 "$root/opt/roma/resources/roma-aac" # the app image loses its executable bit
sed -e 's|@APPDIR@|/opt/roma|' packaging/roma-system-java.sh > "$root/usr/bin/roma"
chmod 755 "$root/usr/bin/roma"
install -m644 packaging/io.github.zakius.roma.desktop "$root/usr/share/applications/"
install -m644 packaging/roma.svg "$root/usr/share/icons/hicolor/scalable/apps/io.github.zakius.roma.svg"
install -m644 packaging/io.github.zakius.roma.metainfo.xml "$root/usr/share/metainfo/"

mkdir "$root/DEBIAN"
cat > "$root/DEBIAN/control" <<CONTROL
Package: roma
Version: $version
Architecture: amd64
Maintainer: zakius <zakius@users.noreply.github.com>
Depends: java21-runtime, libgl1
Section: sound
Priority: optional
Installed-Size: $(du -sk "$root" | cut -f1)
Homepage: https://github.com/zakius/roma
Description: YouTube Music, every title and lyric romanized
 A YouTube Music client that romanizes every non-Latin title, artist and lyric line, with karaoke lyrics, and
 controls that never move.
CONTROL
dpkg-deb --root-owner-group -Zxz -b "$root" "dist/roma_${version}_amd64.deb" > /dev/null
rm -r "$root/DEBIAN"

cp packaging/roma.spec "$stage/"
build="rpmbuild -bb --define '_topdir /tmp/rpmbuild' --define 'stage $root' --define 'version $version' $stage/roma.spec && cp /tmp/rpmbuild/RPMS/x86_64/*.rpm $PWD/dist/"
if command -v rpmbuild > /dev/null; then bash -c "$build"
else
  docker run --rm -v "$stage:$stage" -v "$PWD/dist:$PWD/dist" fedora:latest bash -c \
    "dnf -q -y install rpm-build > /dev/null && $build && chown $(id -u):$(id -g) $PWD/dist/*.rpm"
fi
ls -l dist/roma_"$version"_amd64.deb dist/roma-"$version"-1.x86_64.rpm
