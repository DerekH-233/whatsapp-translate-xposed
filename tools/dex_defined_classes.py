"""Report which classes an APK actually *defines* in its dex.

A dex lists every referenced class in its type table, so a plain string scan
cannot tell "uses the API" from "ships the API". This walks class_defs, which
is what matters here: the libxposed API must be supplied by the framework while
the service artifact has to be bundled.

Usage:  python tools/dex_defined_classes.py <apk> [prefix ...]
Exit code 1 if no APK was readable.
"""
import io
import struct
import sys
import zipfile

DEFAULT_PREFIXES = [
    'io/github/libxposed/api/',
    'io/github/libxposed/service/',
    'de/robv/android/xposed/',
    'com/littlesauce/',
]


def uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


def class_defs(data):
    if data[:4] not in (b'dex\n', b'cdex'):
        return []
    string_ids_size, string_ids_off = struct.unpack_from('<II', data, 0x38)
    type_ids_size, type_ids_off = struct.unpack_from('<II', data, 0x40)
    class_defs_size, class_defs_off = struct.unpack_from('<II', data, 0x60)

    def string_at(idx):
        off = struct.unpack_from('<I', data, string_ids_off + idx * 4)[0]
        n, p = uleb128(data, off)
        return data[p:p + n].decode('utf-8', 'replace')

    out = []
    for i in range(class_defs_size):
        class_idx = struct.unpack_from('<I', data, class_defs_off + i * 32)[0]
        str_idx = struct.unpack_from('<I', data, type_ids_off + class_idx * 4)[0]
        out.append(string_at(str_idx))
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    prefixes = sys.argv[2:] or DEFAULT_PREFIXES

    z = zipfile.ZipFile(apk)
    defined = set()
    for name in z.namelist():
        if name.endswith('.dex'):
            defined |= set(class_defs(z.read(name)))

    print('%s: %d class(es) defined across %d dex'
          % (apk, len(defined), sum(1 for n in z.namelist() if n.endswith('.dex'))))
    bad = 0
    for p in prefixes:
        hits = sorted(c for c in defined if c.startswith('L' + p))
        label = 'DEFINED (bundled)' if hits else 'not defined'
        print('   %-34s %-18s %d' % (p, label, len(hits)))
        for h in hits[:8]:
            print('        ', h)
        if p.startswith('io/github/libxposed/api') and hits:
            bad = 1
        if p.startswith('de/robv') and hits:
            bad = 1
    if bad:
        print('\nFAIL: the libxposed API must not be bundled, and the legacy '
              'de.robv API must be gone entirely.')
    return bad


if __name__ == '__main__':
    sys.exit(main())
