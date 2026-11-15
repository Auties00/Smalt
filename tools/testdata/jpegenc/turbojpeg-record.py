"""Records the MD5 of the files libjpeg-turbo's TurboJPEG API writes for CMYK, YCCK and JFIF density settings
that cjpeg cannot express, for JpegEncoderModesTest of smalt-image.

Usage: python -I turbojpeg-record.py <libturbojpeg shared library> <output csv>

The pixels are the synthetic pattern of the test (pattern() below, also in JpegEncoderModesTest). The CMYK
samples given to TurboJPEG are the stored (Adobe-inverted) samples; the test hands Smalt the ink amounts, which
are their complements. 12-bit samples are the stored 16-bit pattern rescaled to 12 bits the way Smalt rescales
its 16-bit input. Rows: name, width, height, colorspace, subsampling, quality, optimize, progressive,
arithmetic, restart blocks, precision, density units, x density, y density, md5.
"""
import ctypes
import hashlib
import sys

TJINIT_COMPRESS = 0
TJSAMP = {'444': 0, '422': 1, '420': 2, 'GRAY': 3, '440': 4, '411': 5}
TJPF_RGB = 0
TJPF_CMYK = 11
TJCS = {'RGB': 0, 'YCBCR': 1, 'GRAY': 2, 'CMYK': 3, 'YCCK': 4}
TJPARAM_QUALITY = 3
TJPARAM_SUBSAMP = 4
TJPARAM_PRECISION = 7
TJPARAM_COLORSPACE = 8
TJPARAM_OPTIMIZE = 11
TJPARAM_PROGRESSIVE = 12
TJPARAM_ARITHMETIC = 14
TJPARAM_RESTARTBLOCKS = 18
TJPARAM_XDENSITY = 20
TJPARAM_YDENSITY = 21
TJPARAM_DENSITYUNITS = 22


def pattern(x, y, c, width, height):
    """The stored 16-bit sample of channel c at (x, y)."""
    smooth = (x * 65535 // max(1, width - 1) + y * 40000 // max(1, height - 1) + c * 9000) % 65536
    detail = ((x * 7 + y * 13 + c * 61 + (x * y) % 29) & 0xFF) << 4
    return (smooth + detail) & 0xFFFF


def rescale(value, depth, precision):
    max_in = (1 << depth) - 1
    max_out = (1 << precision) - 1
    return (value * max_out + max_in // 2) // max_in


CASES = [
    # name, width, height, colorspace, subsampling, quality, optimize, progressive, arithmetic, restart,
    # precision, density units, x, y
    ('cmyk-444', 61, 37, 'CMYK', '444', 75, 0, 0, 0, 0, 8, 0, 1, 1),
    ('cmyk-444-opt', 61, 37, 'CMYK', '444', 90, 1, 0, 0, 0, 8, 0, 1, 1),
    ('cmyk-444-prog', 61, 37, 'CMYK', '444', 75, 0, 1, 0, 0, 8, 0, 1, 1),
    ('cmyk-444-ari', 61, 37, 'CMYK', '444', 75, 0, 0, 1, 0, 8, 0, 1, 1),
    ('cmyk-444-progari-rst', 61, 37, 'CMYK', '444', 60, 0, 1, 1, 3, 8, 0, 1, 1),
    ('cmyk-420', 61, 37, 'CMYK', '420', 75, 1, 0, 0, 0, 8, 0, 1, 1),
    ('cmyk-444-12', 61, 37, 'CMYK', '444', 85, 0, 0, 0, 0, 12, 0, 1, 1),
    ('cmyk-444-12-prog', 33, 20, 'CMYK', '444', 85, 0, 1, 0, 0, 12, 0, 1, 1),
    ('ycck-420', 61, 37, 'YCCK', '420', 75, 0, 0, 0, 0, 8, 0, 1, 1),
    ('ycck-422-opt', 61, 37, 'YCCK', '422', 80, 1, 0, 0, 0, 8, 0, 1, 1),
    ('ycck-444-rst', 61, 37, 'YCCK', '444', 75, 1, 0, 0, 5, 8, 0, 1, 1),
    ('ycck-440', 61, 37, 'YCCK', '440', 75, 1, 0, 0, 0, 8, 0, 1, 1),
    ('ycck-420-prog', 61, 37, 'YCCK', '420', 75, 0, 1, 0, 0, 8, 0, 1, 1),
    ('ycck-420-ari', 61, 37, 'YCCK', '420', 75, 0, 0, 1, 0, 8, 0, 1, 1),
    ('ycck-420-12', 61, 37, 'YCCK', '420', 75, 0, 0, 0, 0, 12, 0, 1, 1),
    ('ycck-411', 64, 16, 'YCCK', '411', 75, 1, 0, 0, 0, 8, 0, 1, 1),
    ('ycbcr-density-dpi', 61, 37, 'YCBCR', '420', 75, 1, 0, 0, 0, 8, 1, 300, 150),
    ('ycbcr-density-dpcm', 61, 37, 'YCBCR', '444', 75, 0, 1, 0, 0, 8, 2, 118, 118),
    ('gray-density-aspect', 61, 37, 'GRAY', 'GRAY', 75, 0, 0, 1, 0, 8, 0, 4, 3),
]


def main():
    lib = ctypes.CDLL(sys.argv[1])
    lib.tj3Init.restype = ctypes.c_void_p
    lib.tj3Init.argtypes = [ctypes.c_int]
    lib.tj3Set.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    lib.tj3Compress8.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                 ctypes.c_int, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_size_t)]
    lib.tj3Compress12.argtypes = lib.tj3Compress8.argtypes
    lib.tj3Free.argtypes = [ctypes.c_void_p]
    lib.tj3Destroy.argtypes = [ctypes.c_void_p]
    lib.tj3GetErrorStr.restype = ctypes.c_char_p
    lib.tj3GetErrorStr.argtypes = [ctypes.c_void_p]
    rows = []
    for case in CASES:
        name, width, height, space, samp, quality, optimize, progressive, arithmetic, restart, precision, \
            units, xd, yd = case
        cmyk = space in ('CMYK', 'YCCK')
        channels = 4 if cmyk else 3
        samples = []
        for y in range(height):
            for x in range(width):
                for c in range(channels):
                    stored16 = pattern(x, y, c, width, height)
                    if cmyk:
                        # the test gives Smalt the ink 65535 - stored16 (or its 8-bit form); Smalt stores
                        # max - rescale(ink)
                        if precision == 8:
                            ink8 = 255 - (stored16 >> 8)
                            samples.append(255 - ink8)
                        else:
                            samples.append(4095 - rescale(65535 - stored16, 16, 12))
                    else:
                        samples.append(stored16 >> 8)
        handle = lib.tj3Init(TJINIT_COMPRESS)
        for param, value in ((TJPARAM_QUALITY, quality), (TJPARAM_SUBSAMP, TJSAMP[samp]),
                             (TJPARAM_PRECISION, precision), (TJPARAM_COLORSPACE, TJCS[space]),
                             (TJPARAM_OPTIMIZE, optimize), (TJPARAM_PROGRESSIVE, progressive),
                             (TJPARAM_ARITHMETIC, arithmetic), (TJPARAM_RESTARTBLOCKS, restart),
                             (TJPARAM_XDENSITY, xd), (TJPARAM_YDENSITY, yd), (TJPARAM_DENSITYUNITS, units)):
            if lib.tj3Set(handle, param, value) != 0:
                raise SystemExit('tj3Set %d failed: %s' % (param, lib.tj3GetErrorStr(handle)))
        out = ctypes.c_void_p()
        size = ctypes.c_size_t()
        pixel_format = TJPF_CMYK if cmyk else TJPF_RGB
        if precision == 8:
            buffer = (ctypes.c_ubyte * len(samples))(*samples)
            status = lib.tj3Compress8(handle, buffer, width, 0, height, pixel_format, ctypes.byref(out),
                                      ctypes.byref(size))
        else:
            buffer = (ctypes.c_short * len(samples))(*samples)
            status = lib.tj3Compress12(handle, buffer, width, 0, height, pixel_format, ctypes.byref(out),
                                       ctypes.byref(size))
        if status != 0:
            raise SystemExit('%s: %s' % (name, lib.tj3GetErrorStr(handle)))
        data = ctypes.string_at(out, size.value)
        lib.tj3Free(out)
        lib.tj3Destroy(handle)
        rows.append(','.join(str(v) for v in case) + ',' + hashlib.md5(data).hexdigest())
    with open(sys.argv[2], 'w', newline='\n') as f:
        f.write('# Generated by tools/testdata/jpegenc/turbojpeg-record.py with libjpeg-turbo 3.1.3\n')
        f.write('\n'.join(rows) + '\n')


main()
