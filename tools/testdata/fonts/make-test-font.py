#!/usr/bin/env python3
"""Writes the synthetic TrueType font of the subtitle rendering tests of smalt-core.

The font is made from scratch, so it carries no third-party design: 1000 units per em, ascender 800,
descender -200, line gap 0, underline at -100 with thickness 50. Its glyphs:

  0 .notdef  a 600x700 box with a square hole (outer contour clockwise, inner counter-clockwise)
  1 space    no contours, advance 500
  2 square   the unit em square (0,0)-(1000,1000), advance 1000; mapped from '#' and U+1F600
  3 triangle the right triangle (0,0) (1000,0) (0,1000), advance 1000; mapped from 'T'
  4 circle   a circle of radius 400 around (500,400) made of eight off-curve points only; mapped from 'O'
  5 ring     the circle with a hole of radius 200 (inner contour reversed); mapped from 'o'
  6 pair     composite: the square scaled by 0.5 at (0,0) and again at (500,500) (word offsets, scale);
             mapped from 'B'
  7 matched  composite: the triangle, then the square scaled by 0.25 placed by point matching so that its
             point 0 lands on the triangle's point 2 (1000,0); mapped from 'C'
  8 bar      a 100x800 bar at x 0 to 100, advance 200; mapped from 'l', 'H', 'e', 'i' and every other ASCII
             letter not listed above

cmap holds a format 4 subtable (3,1) for the BMP and a format 12 subtable (3,10) that also maps U+1F600.
A name table gives the family "Smalt Test". The output is deterministic (fixed head dates).

Usage: make-test-font.py <output.ttf>
"""
import struct
import sys

UPM = 1000


def checksum(data):
    data += b'\0' * ((4 - len(data) % 4) % 4)
    return sum(struct.unpack('>%dI' % (len(data) // 4), data)) & 0xFFFFFFFF


def simple_glyph(contours):
    """contours: list of lists of (x, y, on_curve)."""
    points = [p for c in contours for p in c]
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    out = struct.pack('>hhhhh', len(contours), min(xs), min(ys), max(xs), max(ys))
    end = -1
    for c in contours:
        end += len(c)
        out += struct.pack('>H', end)
    out += struct.pack('>H', 0)
    # flags: on-curve bit, every coordinate as a signed 16-bit delta (no short or repeat flags)
    out += bytes(1 if p[2] else 0 for p in points)
    prev = 0
    for x in xs:
        out += struct.pack('>h', x - prev)
        prev = x
    prev = 0
    for y in ys:
        out += struct.pack('>h', y - prev)
        prev = y
    return out


def composite_glyph(bbox, components):
    """components: list of (flags, glyph, arg1, arg2, scale or None)."""
    out = struct.pack('>hhhhh', -1, *bbox)
    for index, (flags, glyph, a1, a2, scale) in enumerate(components):
        more = 0x20 if index < len(components) - 1 else 0
        f = flags | more
        out += struct.pack('>HH', f, glyph)
        if f & 1:
            out += struct.pack('>hh' if f & 2 else '>HH', a1, a2)
        else:
            out += struct.pack('>bb' if f & 2 else '>BB', a1, a2)
        if scale is not None:
            out += struct.pack('>h', int(round(scale * 16384)))
    return out


def circle(cx, cy, r, clockwise):
    # eight off-curve points on the circumscribed octagon give a close quadratic approximation of a circle
    import math
    k = r / math.cos(math.pi / 8)
    pts = []
    for i in range(8):
        angle = math.pi / 8 + i * math.pi / 4
        pts.append((int(round(cx + k * math.cos(angle))), int(round(cy + k * math.sin(angle))), False))
    return list(reversed(pts)) if clockwise else pts


def glyphs():
    notdef = simple_glyph([
        [(0, 0, True), (0, 700, True), (600, 700, True), (600, 0, True)],
        [(100, 100, True), (500, 100, True), (500, 600, True), (100, 600, True)],
    ])
    square = simple_glyph([[(0, 0, True), (0, 1000, True), (1000, 1000, True), (1000, 0, True)]])
    triangle = simple_glyph([[(0, 0, True), (0, 1000, True), (1000, 0, True)]])
    disc = simple_glyph([circle(500, 400, 400, True)])
    ring = simple_glyph([circle(500, 400, 400, True), circle(500, 400, 200, False)])
    # word offsets (1) | xy values (2) | have a scale (8)
    pair = composite_glyph((0, 0, 1000, 1000), [(0x0001 | 0x0002 | 0x0008, 2, 0, 0, 0.5),
                                                (0x0001 | 0x0002 | 0x0008, 2, 500, 500, 0.5)])
    # byte point indices (no flag 1), no xy values (point matching), a scale: square point 0 onto triangle point 2
    matched = composite_glyph((0, 0, 1250, 1000), [(0x0002, 3, 0, 0, None), (0x0008, 2, 2, 0, 0.25)])
    bar = simple_glyph([[(0, 0, True), (0, 800, True), (100, 800, True), (100, 0, True)]])
    return [notdef, b'', square, triangle, disc, ring, pair, matched, bar]


ADVANCES = [700, 500, 1000, 1000, 1000, 1000, 1000, 1250, 200]


def mapping():
    m = {0x20: 1, ord('#'): 2, ord('T'): 3, ord('O'): 4, ord('o'): 5, ord('B'): 6, ord('C'): 7}
    for c in range(ord('A'), ord('Z') + 1):
        m.setdefault(c, 8)
    for c in range(ord('a'), ord('z') + 1):
        m.setdefault(c, 8)
    return m


def cmap_format4(m):
    codes = sorted(c for c in m if c <= 0xFFFF)
    segments = []
    for c in codes:
        if segments and segments[-1][1] == c - 1 and m[c] - c == segments[-1][2]:
            segments[-1][1] = c
        else:
            segments.append([c, c, m[c] - c])
    segments.append([0xFFFF, 0xFFFF, 1])
    n = len(segments)
    ends = b''.join(struct.pack('>H', s[1]) for s in segments)
    starts = b''.join(struct.pack('>H', s[0]) for s in segments)
    deltas = b''.join(struct.pack('>h', ((s[2] + 0x8000) & 0xFFFF) - 0x8000) for s in segments)
    ranges = b'\0\0' * n
    search = 1
    while search * 2 <= n:
        search *= 2
    body = struct.pack('>HHHH', 2 * n, 2 * search, search.bit_length() - 1, 2 * n - 2 * search)
    body += ends + b'\0\0' + starts + deltas + ranges
    return struct.pack('>HHH', 4, 6 + len(body), 0) + body


def cmap_format12(m):
    groups = []
    for c in sorted(m):
        if groups and groups[-1][1] == c - 1 and groups[-1][2] + (c - groups[-1][0]) == m[c]:
            groups[-1][1] = c
        else:
            groups.append([c, c, m[c]])
    body = b''.join(struct.pack('>III', *g) for g in groups)
    return struct.pack('>HHIII', 12, 0, 16 + len(body), 0, len(groups)) + body


def name_table():
    """The family, style, full and PostScript names, as Windows Unicode records."""
    names = [(1, 'Smalt Test'), (2, 'Regular'), (4, 'Smalt Test Regular'), (6, 'SmaltTest-Regular')]
    strings = b''
    records = b''
    for name_id, text in names:
        data = text.encode('utf-16-be')
        records += struct.pack('>HHHHHH', 3, 1, 0x409, name_id, len(data), len(strings))
        strings += data
    return struct.pack('>HHH', 0, len(names), 6 + len(records)) + records + strings


def build():
    m = mapping()
    m12 = dict(m)
    m12[0x1F600] = 2
    sub4 = cmap_format4(m)
    sub12 = cmap_format12(m12)
    cmap = struct.pack('>HH', 0, 2) + struct.pack('>HHI', 3, 1, 4 + 16) + struct.pack('>HHI', 3, 10, 4 + 16 + len(sub4))
    cmap += sub4 + sub12
    glyf = b''
    loca = []
    for g in glyphs():
        loca.append(len(glyf))
        glyf += g + b'\0' * ((4 - len(g) % 4) % 4)
    loca.append(len(glyf))
    count = len(ADVANCES)
    tables = {
        b'head': struct.pack('>IIIIHHqqhhhhHHhhh', 0x00010000, 0x00010000, 0, 0x5F0F3CF5, 0, UPM, 0, 0,
                             -200, -200, 1250, 1000, 0, 8, 2, 1, 0),
        b'hhea': struct.pack('>Ihhh' + 'H' + 'hhh' + 'hhh' + 'hhhh' + 'hH', 0x00010000, 800, -200, 0, 1250,
                             0, 0, 1250, 1, 0, 0, 0, 0, 0, 0, 0, count),
        b'maxp': struct.pack('>IH', 0x00005000, count),
        b'hmtx': b''.join(struct.pack('>Hh', a, 0) for a in ADVANCES),
        b'loca': b''.join(struct.pack('>I', o) for o in loca),
        b'glyf': glyf,
        b'cmap': cmap,
        b'post': struct.pack('>IIhhI', 0x00030000, 0, -100, 50, 0) + b'\0' * 16,
        b'name': name_table(),
    }
    names = sorted(tables)
    offset = 12 + 16 * len(names)
    directory = b''
    body = b''
    for name in names:
        data = tables[name]
        directory += name + struct.pack('>III', checksum(data), offset + len(body), len(data))
        body += data + b'\0' * ((4 - len(data) % 4) % 4)
    search = 1
    while search * 2 <= len(names):
        search *= 2
    header = struct.pack('>IHHHH', 0x00010000, len(names), 16 * search, search.bit_length() - 1,
                         16 * len(names) - 16 * search)
    font = bytearray(header + directory + body)
    head = 12 + 16 * names.index(b'head')
    head_offset = struct.unpack('>I', font[head + 8:head + 12])[0]
    adjust = (0xB1B0AFBA - checksum(bytes(font))) & 0xFFFFFFFF
    font[head_offset + 8:head_offset + 12] = struct.pack('>I', adjust)
    return bytes(font)


if __name__ == '__main__':
    with open(sys.argv[1], 'wb') as out:
        out.write(build())
