#!/usr/bin/env python3
"""Draws the four original cartoon pups used by the dock's pop-up (res/drawable-nodpi/pop_char1..7.webp).
They are original artwork made for this project, not any TV show's characters. Needs Pillow:  pip install pillow
Run from the repo root:  python tools/make_chars.py"""
import os
from PIL import Image, ImageDraw

W, H = 800, 960
OUT = (62, 42, 32)
RES = os.path.join(os.path.dirname(__file__), '..', 'mods', 'files', 'app', 'src', 'main', 'res', 'drawable-nodpi')


def rot_ellipse(img, cx, cy, rx, ry, ang, fill, ow=8):
    t = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(t).ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill, outline=OUT, width=ow)
    img.alpha_composite(t.rotate(ang, center=(cx, cy), resample=Image.BICUBIC))


def ell(d, cx, cy, rx, ry, fill, ow=8):
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill, outline=OUT, width=ow)


def pup(fur, ear, belly, hat, patch=None, cross=False, wink=False):
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    rot_ellipse(img, 150, 440, 88, 180, 16, ear)        # ears, behind the head
    rot_ellipse(img, 650, 440, 88, 180, -16, ear)
    rot_ellipse(img, 625, 790, 58, 110, -28, fur)       # waving arm
    d = ImageDraw.Draw(img)
    ell(d, 400, 770, 215, 205, fur)                      # body
    ell(d, 400, 810, 130, 140, belly, 0)
    ell(d, 655, 665, 66, 60, fur)                        # waving paw
    ell(d, 215, 880, 78, 56, fur)
    ell(d, 585, 892, 78, 56, fur)
    ell(d, 400, 420, 280, 250, fur)                      # head
    if patch: ell(d, 520, 350, 125, 120, patch, 0)
    ell(d, 400, 525, 170, 118, belly, 6)                 # muzzle
    d.pieslice([292, 450, 508, 668], 0, 180, fill=(120, 34, 48), outline=OUT, width=8)   # open grin
    ell(d, 400, 618, 58, 36, (255, 120, 150), 6)         # tongue
    ell(d, 400, 468, 58, 40, (40, 32, 40), 6)            # nose
    ell(d, 385, 456, 14, 8, (255, 255, 255), 0)
    d.line([(400, 505), (400, 548)], fill=OUT, width=8)
    for cx, cy in ((190, 520), (610, 520)):              # cheeks
        ell(d, cx, cy, 44, 30, (255, 150, 160), 0)
    eyes = [(300, 345), (500, 345)]
    for k, (cx, cy) in enumerate(eyes):                  # eyes
        if wink and k == 1:
            d.arc([cx - 62, cy - 20, cx + 62, cy + 60], 200, 340, fill=OUT, width=12)
            continue
        ell(d, cx, cy, 70, 82, (255, 255, 255), 8)
        px = cx + (22 if k == 0 else -22) * (1 if cross else 0)
        ell(d, px, cy + 24, 30, 34, (40, 30, 30), 0)
        ell(d, px - 9, cy + 12, 9, 9, (255, 255, 255), 0)
    d.line([(225, 235), (345, 205)], fill=OUT, width=14)  # eyebrows
    d.line([(455, 205), (575, 235)], fill=OUT, width=14)
    if hat == 'party':
        d.polygon([(400, 8), (270, 205), (530, 205)], fill=(255, 90, 150), outline=OUT)
        for y, x0, x1 in ((90, 365, 435), (140, 335, 465)):
            d.line([(x0, y), (x1, y)], fill=(255, 235, 120), width=14)
        ell(d, 400, 14, 30, 30, (255, 220, 70))
    elif hat == 'prop':
        d.pieslice([265, 85, 535, 330], 180, 360, fill=(60, 140, 225), outline=OUT, width=8)
        ell(d, 400, 92, 18, 18, (255, 210, 60))
        ell(d, 320, 76, 80, 17, (230, 70, 70)); ell(d, 480, 76, 80, 17, (255, 205, 60))
    elif hat == 'shades':
        for cx in (300, 500):
            d.rounded_rectangle([cx - 85, 280, cx + 85, 405], 40, fill=(25, 25, 32), outline=OUT, width=8)
            d.line([(cx - 55, 305), (cx - 15, 300)], fill=(255, 255, 255), width=8)
        d.line([(385, 330), (415, 330)], fill=OUT, width=10)
    elif hat == 'bow':
        d.polygon([(400, 690), (280, 640), (280, 740)], fill=(235, 60, 70), outline=OUT)
        d.polygon([(400, 690), (520, 640), (520, 740)], fill=(235, 60, 70), outline=OUT)
        ell(d, 400, 690, 28, 32, (200, 40, 55))
    return img.resize((400, 480), Image.LANCZOS)


PUPS = [
    dict(fur=(244, 180, 72), ear=(200, 125, 42), belly=(255, 236, 200), hat='party', cross=True),
    dict(fur=(250, 247, 240), ear=(150, 96, 56), belly=(255, 255, 255), hat='prop', patch=(150, 96, 56)),
    dict(fur=(150, 172, 198), ear=(96, 116, 148), belly=(226, 236, 246), hat='shades'),
    dict(fur=(236, 202, 160), ear=(66, 54, 54), belly=(255, 240, 220), hat='bow', wink=True),
    dict(fur=(196, 170, 230), ear=(130, 100, 180), belly=(240, 230, 255), hat='party', wink=True),
    dict(fur=(170, 222, 190), ear=(90, 150, 120), belly=(235, 255, 240), hat='prop', cross=True),
    dict(fur=(250, 160, 140), ear=(190, 100, 90), belly=(255, 235, 225), hat='bow'),
]
if __name__ == '__main__':
    for i, p in enumerate(PUPS, 1):
        pup(**p).save(os.path.join(RES, f'pop_char{i}.webp'), 'WEBP', quality=90, method=6)
    print('wrote', len(PUPS), 'pups to', os.path.normpath(RES))
