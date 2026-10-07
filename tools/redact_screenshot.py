"""Redact personal detail from the chat screenshot before publishing.

Blurs (a) the conversation header, which carries the group name and the member
list, and (b) the outgoing bubble that contains a real name and room number.
Regions are given as fractions of the image so the script survives a resize.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

SRC = r'E:\Andriod-Games-check\watrans\lt\docs\_chat720.png'
OUT = r'E:\Andriod-Games-check\watrans\lt\docs\screenshot-chat.jpg'

# (x0, y0, x1, y1) as fractions of width/height.
# The outgoing band was located by scanning for the green bubble pixels
# (see tools/find_redact_bands.py), so it stays tight and does not clip the
# neighbouring message.
REGIONS = [
    (0.0, 0.038, 1.0, 0.118),   # header: group name + member list
    (0.0, 0.740, 1.0, 0.835),   # outgoing bubble: real name + room number
]


def redact(path, out, regions):
    im = Image.open(path).convert('RGB')
    w, h = im.size
    for (x0, y0, x1, y1) in regions:
        box = (int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h))
        crop = im.crop(box)
        # strong blur + slight darkening so it reads as intentionally redacted
        blurred = crop.filter(ImageFilter.GaussianBlur(radius=max(6, w // 40)))
        dark = Image.new('RGB', crop.size, (0, 0, 0))
        crop = Image.blend(blurred, dark, 0.18)
        im.paste(crop, box)
    im.save(out, 'JPEG', quality=86, optimize=True, progressive=True)
    print('%s  %dx%d  %d KB' % (out, im.width, im.height, os.path.getsize(out) // 1024))


if __name__ == '__main__':
    redact(SRC, OUT, REGIONS)
