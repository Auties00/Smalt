#!/usr/bin/env python3
"""Measures how Windows user32 treats animated cursors with raw frames and regenerates AniUser32Test's fixtures.

Usage: python -I make-fixtures.py <scratch directory> <output directory>
Windows only; see README.md. It fails when user32 accepts any raw shape, which would mean the layout can be measured.
"""
import ctypes
import hashlib
import itertools
import os
import platform
import shutil
import struct
import sys
from ctypes import wintypes as w

WIDTH, HEIGHT = 16, 16
BACKGROUND = 0x00FF00FF
LR_LOADFROMFILE = 0x10
IMAGE_ICON, IMAGE_CURSOR = 1, 2
DI_NORMAL = 3

user32 = ctypes.WinDLL('user32', use_last_error=True)
gdi32 = ctypes.WinDLL('gdi32', use_last_error=True)
user32.LoadCursorFromFileW.restype = w.HANDLE
user32.LoadCursorFromFileW.argtypes = [w.LPCWSTR]
user32.LoadImageW.restype = w.HANDLE
user32.LoadImageW.argtypes = [w.HINSTANCE, w.LPCWSTR, w.UINT, ctypes.c_int, ctypes.c_int, w.UINT]
user32.CreateIconFromResourceEx.restype = w.HANDLE
user32.CreateIconFromResourceEx.argtypes = [ctypes.c_char_p, w.DWORD, w.BOOL, w.DWORD, ctypes.c_int, ctypes.c_int, w.UINT]
user32.DrawIconEx.argtypes = [w.HDC, ctypes.c_int, ctypes.c_int, w.HANDLE, ctypes.c_int, ctypes.c_int, w.UINT, w.HANDLE, w.UINT]
user32.DestroyCursor.argtypes = [w.HANDLE]
user32.DestroyIcon.argtypes = [w.HANDLE]
gdi32.CreateCompatibleDC.restype = w.HDC
gdi32.CreateCompatibleDC.argtypes = [w.HDC]
gdi32.CreateDIBSection.restype = w.HANDLE
gdi32.CreateDIBSection.argtypes = [w.HDC, ctypes.c_void_p, w.UINT, ctypes.POINTER(ctypes.c_void_p), w.HANDLE, w.DWORD]
gdi32.SelectObject.argtypes = [w.HDC, w.HANDLE]
gdi32.SelectObject.restype = w.HANDLE
gdi32.DeleteObject.argtypes = [w.HANDLE]
gdi32.DeleteDC.argtypes = [w.HDC]


def ani(frames, cx, cy, bits, planes, flags):
    header = struct.pack('<9I', 36, len(frames), len(frames), cx, cy, bits, planes, 10, flags)
    body = b'anih' + struct.pack('<I', 36) + header
    list_body = b'fram'
    for frame in frames:
        list_body += b'icon' + struct.pack('<I', len(frame)) + frame + (b'\0' if len(frame) & 1 else b'')
    body += b'LIST' + struct.pack('<I', len(list_body)) + list_body
    return b'RIFF' + struct.pack('<I', len(body) + 4) + b'ACON' + body


def info_header(width, height, bits):
    return struct.pack('<IiiHHIIiiII', 40, width, height, 1, bits, 0, 0, 0, 0, 0, 0)


def pixels(bits, seed=0):
    stride = ((WIDTH * bits + 31) // 32) * 4
    out = bytearray(stride * HEIGHT)
    for y in range(HEIGHT):
        for x in range(WIDTH):
            if bits == 32:
                out[y * stride + x * 4:y * stride + x * 4 + 4] = bytes([(x * 16 + seed) & 255, (y * 16) & 255, (x * y) & 255, 255])
            elif bits == 24:
                out[y * stride + x * 3:y * stride + x * 3 + 3] = bytes([(x * 16 + seed) & 255, (y * 16) & 255, (x * y) & 255])
            elif bits == 8:
                out[y * stride + x] = (x * 16 + y + seed) & 255
            elif bits == 4:
                out[y * stride + x // 2] |= ((x + y) & 15) << (0 if x & 1 else 4)
            elif (x + y) & 1:
                out[y * stride + x // 8] |= 0x80 >> (x & 7)
    return bytes(out)


def palette(bits):
    if bits > 8:
        return b''
    last = (1 << bits) - 1
    return b''.join(bytes([i * 255 // last, 255 - i * 255 // last, 128, 0]) for i in range(1 << bits))


def mask():
    return bytes(((WIDTH + 31) // 32) * 4 * HEIGHT)


def icon_file(bits=32, kind=2, seed=0):
    image = info_header(WIDTH, 2 * HEIGHT, bits) + palette(bits) + pixels(bits, seed) + mask()
    hot = (3, 5) if kind == 2 else (1, bits)
    return struct.pack('<HHH', 0, kind, 1) + struct.pack('<BBBBHHII', WIDTH, HEIGHT, 0, 0, *hot, len(image), 22) + image


def raw_shapes(bits):
    """Every form of a frame without an icon directory a raw layout could plausibly take."""
    p, c, m = pixels(bits), palette(bits), mask()
    file_header = lambda size, offset: b'BM' + struct.pack('<IHHI', size, 0, 0, offset)
    yield 'bits', p
    yield 'palette-bits', c + p
    yield 'bits-mask', p + m
    yield 'palette-bits-mask', c + p + m
    yield 'dib', info_header(WIDTH, HEIGHT, bits) + c + p
    yield 'dib-mask', info_header(WIDTH, HEIGHT, bits) + c + p + m
    yield 'icon-image', info_header(WIDTH, 2 * HEIGHT, bits) + c + p + m
    yield 'icon-image-no-mask', info_header(WIDTH, 2 * HEIGHT, bits) + c + p
    yield 'bmp', file_header(54 + len(c) + len(p), 54 + len(c)) + info_header(WIDTH, HEIGHT, bits) + c + p
    yield 'bmp-icon-image', file_header(54 + len(c) + len(p) + len(m), 54 + len(c)) + info_header(WIDTH, 2 * HEIGHT, bits) + c + p + m


def accepted_by(path, data):
    """{return the entry points that load the file}"""
    out = []
    handle = user32.LoadCursorFromFileW(path)
    if handle:
        out.append('LoadCursorFromFile')
        user32.DestroyCursor(handle)
    for name, kind, destroy in (('LoadImage-cursor', IMAGE_CURSOR, user32.DestroyCursor), ('LoadImage-icon', IMAGE_ICON, user32.DestroyIcon)):
        handle = user32.LoadImageW(None, path, kind, 0, 0, LR_LOADFROMFILE)
        if handle:
            out.append(name)
            destroy(handle)
    for name, is_icon, destroy in (('CreateIconFromResourceEx-cursor', 0, user32.DestroyCursor), ('CreateIconFromResourceEx-icon', 1, user32.DestroyIcon)):
        handle = user32.CreateIconFromResourceEx(data, len(data), is_icon, 0x30000, 0, 0, 0)
        if handle:
            out.append(name)
            destroy(handle)
    return out


def draw(path, step):
    """{return the BGRA pixels of an animation step as DrawIconEx puts them in a 32-bit DIB}"""
    # LoadCursorFromFile scales to the system cursor size; this keeps the stored size.
    cursor = user32.LoadImageW(None, path, IMAGE_CURSOR, WIDTH, HEIGHT, LR_LOADFROMFILE)
    dc = gdi32.CreateCompatibleDC(None)
    info = struct.pack('<IiiHHIIiiII', 40, WIDTH, -HEIGHT, 1, 32, 0, 0, 0, 0, 0, 0)
    bits = ctypes.c_void_p()
    bitmap = gdi32.CreateDIBSection(dc, info, 0, ctypes.byref(bits), None, 0)
    old = gdi32.SelectObject(dc, bitmap)
    buffer = (ctypes.c_uint32 * (WIDTH * HEIGHT)).from_address(bits.value)
    for i in range(WIDTH * HEIGHT):
        buffer[i] = BACKGROUND
    if not user32.DrawIconEx(dc, 0, 0, cursor, WIDTH, HEIGHT, step, None, DI_NORMAL):
        raise RuntimeError('DrawIconEx failed')
    out = bytes(buffer)
    gdi32.SelectObject(dc, old)
    gdi32.DeleteObject(bitmap)
    gdi32.DeleteDC(dc)
    user32.DestroyCursor(cursor)
    return out


def main():
    scratch, out_dir = sys.argv[1:3]
    os.makedirs(scratch, exist_ok=True)
    os.makedirs(out_dir, exist_ok=True)
    temp = os.path.join(scratch, 'probe.ani')

    def verdict(data):
        with open(temp, 'wb') as f:
            f.write(data)
        return accepted_by(temp, data)

    # The positive controls: the harness must accept a normal file, on every entry point.
    frames = [icon_file(seed=0), icon_file(seed=40)]
    control = ani(frames, WIDTH, HEIGHT, 32, 1, 1)
    if len(verdict(control)) != 5:
        raise RuntimeError('user32 rejects the control')
    with open(os.path.join(out_dir, 'control.ani'), 'wb') as f:
        f.write(control)
    control_path = os.path.join(out_dir, 'control.ani')
    for step, seed in enumerate((0, 40)):
        drawn = draw(control_path, step)
        for y in range(HEIGHT):
            stored = pixels(32, seed)[(HEIGHT - 1 - y) * WIDTH * 4:(HEIGHT - y) * WIDTH * 4]
            row = drawn[y * WIDTH * 4:(y + 1) * WIDTH * 4]
            if any(row[i:i + 3] != stored[i:i + 3] for i in range(0, WIDTH * 4, 4)):
                raise RuntimeError(f'DrawIconEx does not draw step {step} as stored: {row[:12].hex()} {stored[:12].hex()}')
        with open(os.path.join(out_dir, f'control-{step}.bgra'), 'wb') as f:
            f.write(drawn)

    # The grid: no raw shape loads, whatever the header says; without AF_ICON not even icon frames load.
    tried = 0
    for bits in (32, 24, 8, 4, 1):
        for (name, frame), (cx, cy), planes, flags, declared in itertools.product(
                raw_shapes(bits), ((WIDTH, HEIGHT), (0, 0), (WIDTH, 2 * HEIGHT)), (1, 0), (0, 2), (bits, 0)):
            accepted = verdict(ani([frame], cx, cy, declared, planes, flags))
            tried += 1
            if accepted:
                raise RuntimeError(f'user32 loads raw {name} {bits} anih {cx}x{cy} planes {planes} flags {flags}: {accepted}')
    expected = []
    for bits in (32, 8):
        for name, frame in raw_shapes(bits):
            data = ani([frame], WIDTH, HEIGHT, bits, 1, 0)
            if verdict(data):
                raise RuntimeError(name)
            file = f'raw-{bits}-{name}.ani'
            with open(os.path.join(out_dir, file), 'wb') as f:
                f.write(data)
            expected.append((file, 'rejected', 'unsupported'))
            # The same frames declared as icon files.
            data = ani([frame], WIDTH, HEIGHT, bits, 1, 1)
            if verdict(data):
                raise RuntimeError(name)
            file = f'flagged-{bits}-{name}.ani'
            with open(os.path.join(out_dir, file), 'wb') as f:
                f.write(data)
            expected.append((file, 'rejected', 'malformed'))
    for flags in (0, 2, 4, 0x80000000, 0xFFFFFFFE):
        data = ani(frames, WIDTH, HEIGHT, 32, 1, flags)
        if verdict(data):
            raise RuntimeError(f'user32 loads icon frames with flags {flags:#x}')
        file = f'unflagged-{flags:x}.ani'
        with open(os.path.join(out_dir, file), 'wb') as f:
            f.write(data)
        expected.append((file, 'rejected', 'icon-frames'))
    with open(os.path.join(out_dir, 'expected.tsv'), 'w', newline='\n') as f:
        f.write(f'# file\tuser32 of Windows {platform.version()}\tsmalt-image\n')
        for row in expected:
            f.write('\t'.join(row) + '\n')
    with open(os.path.join(out_dir, 'SHA256SUMS'), 'w', newline='\n') as f:
        for name in sorted(os.listdir(out_dir)):
            if name != 'SHA256SUMS':
                with open(os.path.join(out_dir, name), 'rb') as source:
                    f.write(f'{hashlib.sha256(source.read()).hexdigest()}  {name}\n')
    shutil.rmtree(scratch)
    print('windows', platform.version(), 'raw shapes tried', tried, 'all rejected')


if __name__ == '__main__':
    main()
