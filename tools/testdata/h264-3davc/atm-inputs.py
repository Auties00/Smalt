"""Writes synthetic texture and depth YUV sequences of two camera views for the 3DV-ATM 14.0 encoder.

Usage: atm-inputs.py <dir> <width> <height> <frames>

Texture: a smooth background with three rectangles moving over time; in the second view every rectangle is shifted
left by a disparity that grows with its nearness, and the second view is brighter, so that inter-view prediction,
view synthesis and luminance compensation all have something to find. Depth (half resolution): the nearness of
each rectangle over a far background. Also writes the per-frame near and far depth range files.
"""
import os
import sys

out, width, height, frames = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
objects = [  # x, y, w, h, depth (0 far .. 255 near), luma, cb, cr, dx per frame, dy per frame
    (10, 12, 40, 30, 200, 210, 90, 160, 2, 1),
    (70, 40, 34, 36, 120, 60, 170, 100, -1, 0),
    (30, 60, 28, 20, 240, 140, 128, 200, 1, -1),
]


def disparity(depth, view):
    return 0 if view == 0 else (depth * 12) // 255


def render(frame, view):
    luma = bytearray(width * height)
    cb = bytearray((width // 2) * (height // 2))
    cr = bytearray((width // 2) * (height // 2))
    depth = bytearray((width // 2) * (height // 2))
    gain = 0 if view == 0 else 14
    for y in range(height):
        for x in range(width):
            luma[y * width + x] = min(255, 40 + ((x * 3 + y * 2 + frame * 2) % 90) + gain)
    for y in range(height // 2):
        for x in range(width // 2):
            cb[y * (width // 2) + x] = 120 + (x % 16)
            cr[y * (width // 2) + x] = 130 - (y % 16)
            depth[y * (width // 2) + x] = 30 + (y * 20) // (height // 2)
    for (ox, oy, w, h, d, l, u, v, dx, dy) in objects:
        x0 = ox + dx * frame - disparity(d, view)
        y0 = oy + dy * frame
        for y in range(max(0, y0), min(height, y0 + h)):
            for x in range(max(0, x0), min(width, x0 + w)):
                luma[y * width + x] = min(255, l + ((x - x0) * 2 + (y - y0)) % 24 + gain)
                if x % 2 == 0 and y % 2 == 0:
                    cb[(y // 2) * (width // 2) + x // 2] = u
                    cr[(y // 2) * (width // 2) + x // 2] = v
                    depth[(y // 2) * (width // 2) + x // 2] = d
    return luma, cb, cr, depth


os.makedirs(out, exist_ok=True)
for view, name in ((0, '3'), (1, '1')):
    with open(os.path.join(out, 't' + name + '.yuv'), 'wb') as t, open(os.path.join(out, 'd' + name + '.yuv'), 'wb') as dfile:
        for frame in range(frames):
            luma, cb, cr, depth = render(frame, view)
            t.write(luma + cb + cr)
            dfile.write(depth + bytes([128]) * (len(depth) // 2))
    with open(os.path.join(out, 'znear' + name + '.txt'), 'w') as f:
        for frame in range(frames):
            f.write('%f\n' % (448.25 + 40.0 * frame + 13.0 * view))
    with open(os.path.join(out, 'trans' + name + '.txt'), 'w') as f:
        for frame in range(frames):
            f.write('%f\n' % (15.0 if view == 0 else 5.0))
    with open(os.path.join(out, 'zfar' + name + '.txt'), 'w') as f:
        for frame in range(frames):
            f.write('%f\n' % (11206.28 - 300.0 * frame))
