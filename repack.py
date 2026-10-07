"""Insert classes.dex (stored) and assets/* into an aapt2-linked apk.

Keeps the aapt2-generated AndroidManifest.xml / resources.arsc untouched and
writes classes.dex uncompressed, which is what the runtime expects.
"""
import os
import sys
import zipfile

apk = sys.argv[1]
dex = sys.argv[2]
assets_dir = sys.argv[3] if len(sys.argv) > 3 else None

tmp = apk + '.tmp'

zin = zipfile.ZipFile(apk)
names = [i.filename for i in zin.infolist()]

with zipfile.ZipFile(tmp, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'classes.dex':
            continue
        zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        zi.compress_type = item.compress_type
        zi.external_attr = item.external_attr
        zi.internal_attr = item.internal_attr
        zout.writestr(zi, data)

    dex_bytes = open(dex, 'rb').read()
    zi = zipfile.ZipInfo('classes.dex', date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    zout.writestr(zi, dex_bytes)

    if assets_dir and os.path.isdir(assets_dir):
        for root, _, files in os.walk(assets_dir):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, assets_dir).replace('\\', '/')
                zi = zipfile.ZipInfo('assets/' + rel, date_time=(1980, 1, 1, 0, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(zi, open(full, 'rb').read())

zin.close()
os.replace(tmp, apk)
print('[repack] classes.dex %d bytes, assets from %s' % (len(dex_bytes), assets_dir))
