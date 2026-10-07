"""Generate the launcher icon set.

Style follows WhatsApp itself: WhatsApp green, a white speech bubble with the
tail at the bottom-left, and the glyph 译 in WhatsApp's dark teal.
"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT_ROOT = r'E:\Andriod-Games-check\watrans\lt\res'

BUCKETS = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

FONT_CANDIDATES = [
    r'C:\Windows\Fonts\msyhbd.ttc',
    r'C:\Windows\Fonts\msyh.ttc',
    r'C:\Windows\Fonts\simhei.ttf',
    r'C:\Windows\Fonts\Deng.ttf',
]

# WhatsApp palette
GREEN_TOP = (0x3D, 0xDC, 0x84)     # bright, lifted a touch for the top of the gradient
GREEN_BOT = (0x0F, 0x8C, 0x6E)     # deep teal-green
GLYPH = (0x07, 0x5E, 0x54)         # WhatsApp dark teal


def load_font(px):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, px)
            except Exception:
                continue
    return ImageFont.load_default()


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(size):
    img = Image.new('RGB', (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        d.line([(0, y), (size, y)], fill=lerp(GREEN_TOP, GREEN_BOT, y / max(1, size - 1)))
    return img


def rounded_mask(size, radius_ratio=0.235):
    ss = size * 4
    m = Image.new('L', (ss, ss), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, ss - 1, ss - 1],
                                        radius=int(ss * radius_ratio), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def bubble_mask(size):
    """WhatsApp-style bubble: wide rounded rect, tail at the bottom-left."""
    ss = size * 4
    m = Image.new('L', (ss, ss), 0)
    d = ImageDraw.Draw(m)
    pad = int(ss * 0.205)
    box = [pad, int(ss * 0.245), ss - pad, int(ss * 0.705)]
    d.rounded_rectangle(box, radius=int(ss * 0.120), fill=255)
    d.polygon([
        (int(ss * 0.305), int(ss * 0.660)),
        (int(ss * 0.300), int(ss * 0.830)),
        (int(ss * 0.470), int(ss * 0.660)),
    ], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def make(size):
    icon = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    icon.paste(gradient(size).convert('RGBA'), (0, 0), rounded_mask(size))

    # gentle top sheen so the green does not look flat
    sheen = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).ellipse(
        [-int(size * 0.40), -int(size * 0.85), int(size * 1.30), int(size * 0.40)],
        fill=(255, 255, 255, 54))
    sheen = sheen.filter(ImageFilter.GaussianBlur(size * 0.07))
    empty = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    icon = Image.alpha_composite(icon, Image.composite(sheen, empty, rounded_mask(size)))

    # white bubble
    icon.paste(Image.new('RGBA', (size, size), (255, 255, 255, 255)),
               (0, 0), bubble_mask(size))

    # glyph
    glyph = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glyph)
    f = load_font(int(size * 0.310))
    text = '译'
    tb = gd.textbbox((0, 0), text, font=f)
    gw = tb[2] - tb[0]
    gd.text(((size - gw) / 2 - tb[0], int(size * 0.318) - tb[1]), text, font=f,
            fill=GLYPH + (255,))
    icon = Image.alpha_composite(icon, glyph)

    return icon


def main():
    for bucket, size in BUCKETS.items():
        d = os.path.join(OUT_ROOT, 'mipmap-' + bucket)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, 'ic_launcher.png')
        make(size).save(p, 'PNG', optimize=True)
        print('%-10s %3dpx -> %s' % (bucket, size, p))


if __name__ == '__main__':
    main()
