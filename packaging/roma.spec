# Fedora package around the tree tools/linux-packages.sh stages. Java comes from Fedora, so its fixes arrive with
# system updates. As finimal's.
# xz, as the .deb: Ubuntu's rpmbuild (CI) defaults to gzip, which leaves the package much bigger.
%global _binary_payload w9.xzdio

Name:           roma
Version:        %{version}
Release:        1
Summary:        YouTube Music, every title and lyric romanized
License:        GPL-3.0-only
URL:            https://github.com/zakius/roma
ExclusiveArch:  x86_64
# Skia's library would otherwise pull its every link as a requirement; Java's full JRE brings X11, fonts and sound.
AutoReqProv:    no
Requires:       (java-25-openjdk or java-21-openjdk or java-latest-openjdk)
Requires:       libglvnd-glx

%description
A YouTube Music client that romanizes every non-Latin title, artist and lyric line, with karaoke lyrics, and
controls that never move.

%install
cp -a %{stage}/. %{buildroot}/

%files
/opt/roma
/usr/bin/roma
/usr/share/applications/io.github.zakius.roma.desktop
/usr/share/icons/hicolor/scalable/apps/io.github.zakius.roma.svg
/usr/share/metainfo/io.github.zakius.roma.metainfo.xml
