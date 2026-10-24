#!/usr/bin/env python3
"""Recompresses an unsigned APK with Zopfli (pigz -11), the strongest deflate: Android reads only
stored and deflate entries. Deflated entries and the native libraries (useLegacyPackaging, so
extractNativeLibs) are recompressed; stored ones (resources.arsc) stay stored. zipalign and
signing come after. Taken from tennessine's tools/repack_apk.py.

Usage: repack_apk.py IN OUT
"""
import os, subprocess, sys, zipfile


class Zopfli:
    """A compressor for zipfile: collects an entry, then deflates it all at once with pigz."""

    def __init__(self):
        self.parts = []

    def compress(self, data):
        self.parts.append(data)
        return b''

    def flush(self):
        out = subprocess.run(['pigz', '-11', '-z', '-p', str(os.cpu_count()), '-c'],
                             input=b''.join(self.parts), capture_output=True, check=True).stdout
        return out[2:-4]  # The raw deflate stream inside the zlib header and Adler-32.


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    stock = zipfile._get_compressor
    zipfile._get_compressor = lambda kind, level=None: (
        Zopfli() if kind == zipfile.ZIP_DEFLATED else stock(kind, level))
    with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], 'w') as dst:
        for info in src.infolist():
            deflate = info.compress_type == zipfile.ZIP_DEFLATED or (
                info.filename.startswith('lib/') and info.filename.endswith('.so'))
            out = zipfile.ZipInfo(info.filename, info.date_time)
            out.external_attr = info.external_attr
            out.compress_type = zipfile.ZIP_DEFLATED if deflate else zipfile.ZIP_STORED
            dst.writestr(out, src.read(info))


if __name__ == '__main__':
    main()
