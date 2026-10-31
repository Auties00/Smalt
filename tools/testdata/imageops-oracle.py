#!/usr/bin/env python3
"""Generates the Pillow reference outputs of the image operation tests (PillowOracleTest).

Writes into core/src/test/resources/com/github/auties00/smalt/image/ops/pillow:
  rgb.bin, rgba.bin : the two 61x47 inputs, raw interleaved RGB and RGBA (straight alpha)
  low.bin           : the RGB input with its range compressed to 40..190, for autocontrast
  post.bin, posta.bin : the RGB and RGBA inputs posterized to 2 bits, for the mode filter
  mask.bin          : a 61x47 L mask (a disc and a bar), for the masked histogram and equalization
                      and as the alpha of putalpha
  cases.tsv         : one line per case: name, input, operation, parameters, comparison, mode
  <name>.bin        : Pillow's output, raw interleaved (RGB, RGBA or L as the case says); for the
                      HIST mode, the histogram as little-endian 32-bit counts
  stats.tsv         : ImageStat.Stat of the inputs, unmasked and masked, one line per band, the
                      floating-point values written with repr so that they parse back exactly

The Java test runs the matching Smalt operation on the same input and compares as the comparison
column says: "exact", "max:T" (every sample within T), "interior:B:T" (samples at least B pixels
from the edges within T), "rotated:M:T" (samples whose source point lies at least M pixels inside
the input within T), "mapped:M:T" (the same for the perspective, quad and mesh transforms).

Mapping (Pillow -> Smalt):
  GaussianBlur(r)              -> ImageFilters.gaussianBlur(p, r, BOX)
  BoxBlur(r)                   -> ImageFilters.boxBlur(p, r)
  RGBA via convert("RGBa")     -> the same calls on the RGBA input (premultiplied internally)
  UnsharpMask(r, p, t)         -> ImageFilters.unsharpMask(p, r, p / 100, t, BOX)
  MedianFilter(s)              -> ImageFilters.median(p, (s - 1) / 2)
  Kernel filters               -> ImageFilters.convolve(p, ConvolutionKernel.<name>()) (Pillow
                                  leaves a 1-pixel border unfiltered: compared inside it)
  rotate(-a, resample, fill)   -> ImageOps.rotate(p, a, ..., KEEP, opaque black) (Pillow turns
                                  counter-clockwise; it truncates and replicates edges, so
                                  bilinear/bicubic are compared away from the edges)
  ImageEnhance.*, autocontrast, convert("L"), colorize, invert -> ImageAdjustments
  resize(size, BOX/BILINEAR/HAMMING/BICUBIC/LANCZOS) -> ImageOps.resize(p, w, h, ResizeFilter.<name>)
                                  (Pillow clips and renormalizes the taps at the edges and rounds
                                  to 8 bits between its passes: compared inside a border, within
                                  1; the output size is in the parameters)
  ImageOps.equalize(im, mask), posterize, solarize -> ImageAdjustments.equalize, posterize, solarize
  MinFilter/MaxFilter/RankFilter/ModeFilter(s) -> ImageFilters.minimum/maximum/rank/mode(p, (s - 1) / 2)
  transform(size, PERSPECTIVE, a) -> ImageOps.perspective(p, ofCoefficients(a).inverse(), ...)
  transform(size, QUAD, corners)  -> ImageOps.quad(p, Quad(nw, ne, se, sw), ...)
  transform(size, MESH, cells)    -> ImageOps.mesh(p, cells, ...) (nearest: exact except within
                                  rounding of a pixel boundary; bilinear: Pillow replicates edges and
                                  truncates, so compared one pixel inside the source, within 1)
  histogram(mask), ImageStat      -> ImageOps.histogram(p, mask), Histogram.statistics (exact)
  split(), merge("RGB", (b, g, r)), putalpha(mask or 128) -> ImageOps.split, merge, putAlpha
  convert("HSV"), shift H by k, convert("RGB") -> ImageAdjustments.rotateHue(p, k * 360 / 255)
                                  (Pillow quantizes H, S and V to 8 bits: within 8)
Reference formulas (no Pillow counterpart: the formulas the Smalt javadoc states, in double
precision, compared exactly, or within 1 where Smalt rounds single-precision linear values):
  hue-ref    -> ImageAdjustments.rotateHue
  vignette   -> ImageAdjustments.vignette (linear light)
  whitebal   -> ImageAdjustments.whiteBalance (Krystek locus, Bradford, linear light)

Usage: python tools/testdata/imageops-oracle.py   (checked with Pillow 12.0.0 and 12.3.0)
"""
import math
import struct
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter, ImageOps, ImageStat
import PIL

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "core/src/test/resources/com/github/auties00/smalt/image/ops/pillow"
WIDTH, HEIGHT = 61, 47


def noise():
    state = 0x9E3779B97F4A7C15
    while True:
        state = (state * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        yield state >> 56


def inputs():
    source = noise()
    rgb = bytearray()
    rgba = bytearray()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            n = next(source)
            r = x * 255 // (WIDTH - 1)
            g = y * 255 // (HEIGHT - 1)
            b = (x * 7 + y * 13) % 256
            if 20 <= x < 40 and 10 <= y < 30:
                r, g, b = 230, 40, 30
            if (x - 45) ** 2 + (y - 33) ** 2 < 64:
                r, g, b = 20, 30, 200
            r = max(0, min(255, r + (n & 31) - 16))
            g = max(0, min(255, g + ((n >> 3) & 15) - 8))
            rgb += bytes((r, g, b))
            alpha = 255 if x < 30 else (0 if y < 10 else (x * 4 + y) % 256)
            rgba += bytes((r, g, b, alpha))
    return Image.frombytes("RGB", (WIDTH, HEIGHT), bytes(rgb)), Image.frombytes("RGBA", (WIDTH, HEIGHT), bytes(rgba))


def premultiplied(image, flt):
    return image.convert("RGBa").filter(flt).convert("RGBA")


def low_contrast(rgb):
    return rgb.point(lambda v: 40 + v * 150 // 255)


def mask_image():
    data = bytearray()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            inside = (x - 20) ** 2 + (y - 22) ** 2 < 225 or (40 <= x < 55 and 5 <= y < 40)
            data.append(200 if inside else 0)
    return Image.frombytes("L", (WIDTH, HEIGHT), bytes(data))


def posterized_rgba(rgba):
    return Image.merge("RGBA", [band.point(lambda v: v & 0xC0) for band in rgba.split()])


def f32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def srgb_decode(value):
    return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4


DECODE8 = [f32(srgb_decode(i / 255.0)) for i in range(256)]
THRESHOLDS8 = [float("-inf")] + [f32(srgb_decode((i - 0.5) / 255)) for i in range(1, 256)]


def srgb_encode8(linear):
    linear = f32(linear)
    low, high = 0, 255
    while low < high:
        mid = (low + high + 1) // 2
        if THRESHOLDS8[mid] <= linear:
            low = mid
        else:
            high = mid - 1
    return low


def hue_reference(image, degrees):
    turn = math.fmod(degrees, 360)
    shift = (turn + 360 if turn < 0 else turn) / 60
    out = bytearray()
    for r, g, b in image.getdata():
        high, low = max(r, g, b), min(r, g, b)
        d = high - low
        if d == 0:
            out += bytes((r, g, b))
            continue
        if high == r:
            h = (g - b) / d
            if h < 0:
                h += 6
        elif high == g:
            h = 2 + (b - r) / d
        else:
            h = 4 + (r - g) / d
        h += shift
        if h >= 6:
            h -= 6
        sector = int(h)
        f = h - sector
        rising = math.floor(low + d * f + 0.5)
        falling = math.floor(low + d * (1 - f) + 0.5)
        out += bytes(((high, rising, low), (falling, high, low), (low, high, rising), (low, falling, high),
                      (rising, low, high), (high, low, falling))[sector])
    return Image.frombytes("RGB", image.size, bytes(out))


def hue_pillow(image, steps):
    h, s, v = image.convert("HSV").split()
    h = h.point(lambda value: (value + steps) % 255)
    return Image.merge("HSV", (h, s, v)).convert("RGB")


def linear_rows(image, function):
    width, height = image.size
    pixels = list(image.getdata())
    out = bytearray()
    for y in range(height):
        for x in range(width):
            linear = [DECODE8[c] for c in pixels[y * width + x][:3]]
            out += bytes(srgb_encode8(c) for c in function(x, y, linear))
    return Image.frombytes("RGB", image.size, bytes(out))


def vignette_reference(image, amount, radius):
    width, height = image.size
    half_width, half_height = width / 2.0, height / 2.0

    def function(x, y, linear):
        dx = (x + 0.5 - half_width) / half_width
        dy = (y + 0.5 - half_height) / half_height
        d = math.sqrt((dx * dx + dy * dy) / 2)
        t = min(max((d - radius) / (1 - radius), 0.0), 1.0)
        factor = 1 - amount * t * t * (3 - 2 * t)
        return [c * factor for c in linear]

    return linear_rows(image, function)


RGB_TO_XYZ = [[0.4124564, 0.3575761, 0.1804375], [0.2126729, 0.7151522, 0.0721750],
              [0.0193339, 0.1191920, 0.9503041]]
BRADFORD = [[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]]


def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def matinv(m):
    det = (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
           + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]))
    return [[(m[(j + 1) % 3][(i + 1) % 3] * m[(j + 2) % 3][(i + 2) % 3]
              - m[(j + 1) % 3][(i + 2) % 3] * m[(j + 2) % 3][(i + 1) % 3]) / det for j in range(3)] for i in range(3)]


def planck(t):
    u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t)
    v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)
    return u, v


def white(kelvin, tint):
    u, v = planck(kelvin)
    du = planck(kelvin + 0.5)[0] - planck(kelvin - 0.5)[0]
    dv = planck(kelvin + 0.5)[1] - planck(kelvin - 0.5)[1]
    length = math.hypot(du, dv)
    u += tint * dv / length
    v -= tint * du / length
    denominator = 2 * u - 8 * v + 4
    x, y = 3 * u / denominator, 2 * v / denominator
    return [x / y, 1.0, (1 - x - y) / y]


def white_balance_reference(image, kelvin, tint):
    source = [sum(BRADFORD[i][k] * white(kelvin, tint)[k] for k in range(3)) for i in range(3)]
    target = [sum(BRADFORD[i][k] * white(6504, 0)[k] for k in range(3)) for i in range(3)]
    cones = matmul(BRADFORD, RGB_TO_XYZ)
    scale = [[target[i] / source[i] if i == j else 0.0 for j in range(3)] for i in range(3)]
    matrix = matmul(matinv(cones), matmul(scale, cones))
    return linear_rows(image, lambda x, y, linear: [sum(matrix[i][k] * linear[k] for k in range(3))
                                                    for i in range(3)])


def histogram_bytes(image, mask=None):
    return struct.pack("<%dI" % (len(image.getbands()) * 256), *image.histogram(mask))


def numbers(values):
    return " ".join(repr(v) for v in values)


def extra_cases(rgb, rgba, low):
    mask = mask_image()
    post = ImageOps.posterize(rgb, 2)
    posta = posterized_rgba(rgba)
    for source, image in (("rgb", rgb), ("low", low)):
        yield f"equalize-{source}", source, "equalize", "", "exact", ImageOps.equalize(image)
    yield "equalize-mask", "rgb", "equalize", "mask", "exact", ImageOps.equalize(rgb, mask)
    for bits in (1, 3, 5, 8):
        yield f"posterize-{bits}", "rgb", "posterize", str(bits), "exact", ImageOps.posterize(rgb, bits)
    for threshold in (0, 77, 128, 256):
        yield f"solarize-{threshold}", "rgb", "solarize", str(threshold), "exact", ImageOps.solarize(rgb, threshold)
    for size in (3, 5, 9):
        yield f"min-{size}", "rgb", "min", str(size), "exact", rgb.filter(ImageFilter.MinFilter(size))
        yield f"max-{size}", "rgb", "max", str(size), "exact", rgb.filter(ImageFilter.MaxFilter(size))
    yield "min-rgba-3", "rgba", "min", "3", "exact", rgba.filter(ImageFilter.MinFilter(3))
    yield "max-rgba-5", "rgba", "max", "5", "exact", rgba.filter(ImageFilter.MaxFilter(5))
    for size, rank in ((3, 2), (3, 7), (5, 3), (5, 20), (7, 30)):
        yield f"rank-{size}-{rank}", "rgb", "rank", f"{size} {rank}", "exact", \
            rgb.filter(ImageFilter.RankFilter(size, rank))
    yield "rank-rgba-5-10", "rgba", "rank", "5 10", "exact", rgba.filter(ImageFilter.RankFilter(5, 10))
    for size in (3, 5, 7):
        yield f"mode-{size}", "post", "mode", str(size), "exact", post.filter(ImageFilter.ModeFilter(size))
    yield "mode-rgb-3", "rgb", "mode", "3", "exact", rgb.filter(ImageFilter.ModeFilter(3))
    yield "mode-rgba-5", "posta", "mode", "5", "exact", posta.filter(ImageFilter.ModeFilter(5))
    perspectives = (("tilt", (61, 47), (1.1, 0.2, -3.0, 0.05, 0.9, 2.0, 0.002, -0.001)),
                    ("wide", (90, 60), (0.6, -0.1, 4.0, 0.08, 0.7, -1.5, 0.0015, 0.003)),
                    ("keystone", (61, 47), (1.0, 0.25, -6.0, 0.0, 1.2, -4.0, 0.0, 0.006)))
    for name, size, data in perspectives:
        parameters = f"{size[0]} {size[1]} {numbers(data)}"
        yield f"perspective-nearest-{name}", "rgb", "perspective-nearest", parameters, "mapped:0:0", \
            rgb.transform(size, Image.Transform.PERSPECTIVE, data, Image.Resampling.NEAREST)
        yield f"perspective-bilinear-{name}", "rgb", "perspective-bilinear", parameters, "mapped:1:1", \
            rgb.transform(size, Image.Transform.PERSPECTIVE, data, Image.Resampling.BILINEAR)
    yield "perspective-nearest-rgba", "rgba", "perspective-nearest", f"61 47 {numbers(perspectives[0][2])}", \
        "mapped:0:0", rgba.transform((61, 47), Image.Transform.PERSPECTIVE, perspectives[0][2],
                                     Image.Resampling.NEAREST)
    quads = (("skew", (50, 40), (5.0, 3.0, 2.0, 44.0, 58.0, 40.0, 55.0, 6.0)),
             ("twist", (70, 52), (10.5, 2.25, 0.0, 30.0, 40.0, 46.0, 60.0, 0.0)),
             ("flip", (61, 47), (61.0, 0.0, 61.0, 47.0, 0.0, 47.0, 0.0, 0.0)))
    for name, size, data in quads:
        parameters = f"{size[0]} {size[1]} {numbers(data)}"
        yield f"quad-nearest-{name}", "rgb", "quad-nearest", parameters, "mapped:0:0", \
            rgb.transform(size, Image.Transform.QUAD, data, Image.Resampling.NEAREST)
        yield f"quad-bilinear-{name}", "rgb", "quad-bilinear", parameters, "mapped:1:1", \
            rgb.transform(size, Image.Transform.QUAD, data, Image.Resampling.BILINEAR)
    meshes = (("halves", (60, 47), [((0, 0, 30, 47), (0.0, 0.0, 0.0, 47.0, 20.0, 40.0, 25.0, 5.0)),
                                    ((30, 0, 60, 47), (25.0, 5.0, 20.0, 40.0, 61.0, 47.0, 61.0, 0.0))]),
              ("overlap", (64, 50), [((0, 0, 40, 30), (3.0, 2.0, 1.0, 30.0, 40.0, 35.0, 45.0, 0.0)),
                                     ((20, 10, 64, 50), (30.0, 10.0, 20.0, 47.0, 61.0, 40.0, 58.0, 12.0)),
                                     ((5, 35, 15, 45), (0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0))]))
    for name, size, cells in meshes:
        parameters = f"{size[0]} {size[1]} " + " ".join(
            " ".join(str(v) for v in box) + " " + numbers(quad) for box, quad in cells)
        yield f"mesh-nearest-{name}", "rgb", "mesh-nearest", parameters, "mapped:0:0", \
            rgb.transform(size, Image.Transform.MESH, cells, Image.Resampling.NEAREST)
        yield f"mesh-bilinear-{name}", "rgb", "mesh-bilinear", parameters, "mapped:1:1", \
            rgb.transform(size, Image.Transform.MESH, cells, Image.Resampling.BILINEAR)
    yield "histogram-rgb", "rgb", "histogram", "", "exact", histogram_bytes(rgb)
    yield "histogram-rgba", "rgba", "histogram", "", "exact", histogram_bytes(rgba)
    yield "histogram-mask", "rgb", "histogram", "mask", "exact", histogram_bytes(rgb, mask)
    for band, image in enumerate(rgba.split()):
        yield f"split-{band}", "rgba", "split", str(band), "exact", image
    r, g, b = rgb.split()
    yield "merge-bgr", "rgb", "merge", "", "exact", Image.merge("RGB", (b, g, r))
    with_alpha = rgb.copy()
    with_alpha.putalpha(mask)
    yield "putalpha-mask", "rgb", "putalpha", "mask", "exact", with_alpha
    constant = rgba.copy()
    constant.putalpha(128)
    yield "putalpha-128", "rgba", "putalpha", "128", "exact", constant
    for steps in (17, 85, 170, 230):
        yield f"hue-pillow-{steps}", "rgb", "hue", repr(steps * 360 / 255), "max:8", hue_pillow(rgb, steps)
    for degrees in (30.0, 120.0, -75.5, 200.0, 359.9):
        yield f"hue-ref-{degrees}", "rgb", "hue", repr(degrees), "exact", hue_reference(rgb, degrees)
    for amount, radius in ((0.8, 0.3), (1.0, 0.0), (-0.5, 0.5)):
        yield f"vignette-{amount}-{radius}", "rgb", "vignette", f"{amount!r} {radius!r}", "max:1", \
            vignette_reference(rgb, amount, radius)
    for kelvin, tint in ((3200.0, 0.0), (9000.0, 0.0), (5000.0, 0.01), (6504.0, -0.02), (1500.0, 0.0)):
        yield f"whitebal-{kelvin}-{tint}", "rgb", "whitebalance", f"{kelvin!r} {tint!r}", "max:1", \
            white_balance_reference(rgb, kelvin, tint)


def stats_lines(images, mask):
    lines = ["# input\tmask\tband\tcount\tmin\tmax\tmedian\tsum\tsum2\tmean\trms\tvar\tstddev"]
    for name, image in images:
        for masked in (False, True):
            stat = ImageStat.Stat(image, mask if masked else None)
            for band in stat.bands:
                lines.append("\t".join((name, "mask" if masked else "", str(band), str(stat.count[band]),
                                        str(stat.extrema[band][0]), str(stat.extrema[band][1]),
                                        str(stat.median[band]), repr(stat.sum[band]), repr(stat.sum2[band]),
                                        repr(stat.mean[band]), repr(stat.rms[band]), repr(stat.var[band]),
                                        repr(stat.stddev[band]))))
    return lines


def cases(rgb, rgba):
    yield "gauss-box-0.5", "rgb", "gauss-box", "0.5", "exact", rgb.filter(ImageFilter.GaussianBlur(0.5))
    yield "gauss-box-1", "rgb", "gauss-box", "1", "exact", rgb.filter(ImageFilter.GaussianBlur(1))
    yield "gauss-box-2.5", "rgb", "gauss-box", "2.5", "exact", rgb.filter(ImageFilter.GaussianBlur(2.5))
    yield "gauss-box-7", "rgb", "gauss-box", "7", "exact", rgb.filter(ImageFilter.GaussianBlur(7))
    yield "gauss-box-40", "rgb", "gauss-box", "40", "exact", rgb.filter(ImageFilter.GaussianBlur(40))
    yield "gauss-box-rgba-3", "rgba", "gauss-box", "3", "exact", premultiplied(rgba, ImageFilter.GaussianBlur(3))
    yield "box-1", "rgb", "box", "1", "exact", rgb.filter(ImageFilter.BoxBlur(1))
    yield "box-2.3", "rgb", "box", "2.3", "exact", rgb.filter(ImageFilter.BoxBlur(2.3))
    yield "box-30", "rgb", "box", "30", "exact", rgb.filter(ImageFilter.BoxBlur(30))
    yield "unsharp-2-150-3", "rgb", "unsharp", "2 150 3", "exact", rgb.filter(ImageFilter.UnsharpMask(2, 150, 3))
    yield "unsharp-1-80-0", "rgb", "unsharp", "1 80 0", "exact", rgb.filter(ImageFilter.UnsharpMask(1, 80, 0))
    yield "median-3", "rgb", "median", "3", "exact", rgb.filter(ImageFilter.MedianFilter(3))
    yield "median-5", "rgb", "median", "5", "exact", rgb.filter(ImageFilter.MedianFilter(5))
    yield "median-rgba-3", "rgba", "median", "3", "exact", rgba.filter(ImageFilter.MedianFilter(3))
    for name, flt in (("sharpen", ImageFilter.SHARPEN), ("smooth", ImageFilter.SMOOTH), ("detail", ImageFilter.DETAIL),
                      ("edgeEnhance", ImageFilter.EDGE_ENHANCE), ("findEdges", ImageFilter.FIND_EDGES),
                      ("emboss", ImageFilter.EMBOSS)):
        yield "kernel-" + name, "rgb", "kernel", name, "interior:1:1", rgb.filter(flt)
    for angle in (30, -17.5, 123):
        yield f"rotate-nearest-{angle}", "rgb", "rotate-nearest", str(angle), "rotated:0:0", \
            rgb.rotate(-angle, resample=Image.Resampling.NEAREST, expand=False, fillcolor=(0, 0, 0))
        yield f"rotate-bilinear-{angle}", "rgb", "rotate-bilinear", str(angle), "rotated:1:1", \
            rgb.rotate(-angle, resample=Image.Resampling.BILINEAR, expand=False, fillcolor=(0, 0, 0))
    for factor in (0.6, 1.4):
        yield f"brightness-{factor}", "rgb", "brightness", str(factor), "exact", ImageEnhance.Brightness(rgb).enhance(factor)
    for factor in (0.5, 1.8):
        yield f"contrast-{factor}", "rgb", "contrast", str(factor), "exact", ImageEnhance.Contrast(rgb).enhance(factor)
    for factor in (0.3, 2.0):
        yield f"saturation-{factor}", "rgb", "saturation", str(factor), "exact", ImageEnhance.Color(rgb).enhance(factor)
    yield "brightness-rgba-0.7", "rgba", "brightness", "0.7", "exact", ImageEnhance.Brightness(rgba).enhance(0.7)
    low = low_contrast(rgb)
    for cutoff in (0, 2):
        yield f"autolevels-{cutoff}", "rgb", "autolevels", str(cutoff), "exact", ImageOps.autocontrast(rgb, cutoff=cutoff)
        yield f"autocontrast-{cutoff}", "rgb", "autocontrast", str(cutoff), "exact", \
            ImageOps.autocontrast(rgb, cutoff=cutoff, preserve_tone=True)
    yield "autolevels-low", "low", "autolevels", "0", "exact", ImageOps.autocontrast(low)
    yield "gray", "rgb", "gray", "", "exact", rgb.convert("L")
    yield "colorize", "rgb", "colorize", "141450 fae6c8", "exact", \
        ImageOps.colorize(rgb.convert("L"), (0x14, 0x14, 0x50), (0xFA, 0xE6, 0xC8))
    yield "invert", "rgb", "invert", "", "exact", ImageOps.invert(rgb)
    for name, flt, support in (("BOX", Image.Resampling.BOX, 0.5), ("BILINEAR", Image.Resampling.BILINEAR, 1),
                               ("HAMMING", Image.Resampling.HAMMING, 1), ("BICUBIC", Image.Resampling.BICUBIC, 2),
                               ("LANCZOS3", Image.Resampling.LANCZOS, 3)):
        # odd output sizes keep every box edge off the source sample centers, where Pillow's box
        # weights one side fully and Smalt's both sides by half
        for width, height in ((29, 21), (39, 31), (122, 94), (101, 79)):
            # Pillow renormalizes the taps its edges clip where Smalt replicates the edge samples, so
            # the samples whose taps reach past the edges are not compared; the low-contrast input
            # keeps the ringing of the sharper filters inside 0..255, which Pillow clips between its
            # passes
            border = math.ceil(support * max(1.0, width / WIDTH, height / HEIGHT)) + 2
            # Pillow rounds to 8 bits between its passes; enlarging with the box copies pixels exactly
            tolerance = 0 if name == "BOX" and width > WIDTH else 1
            yield f"resize-{name.lower()}-{width}x{height}", "low", "resize", f"{name} {width} {height}", \
                f"interior:{border}:{tolerance}", low.resize((width, height), flt)
    yield from extra_cases(rgb, rgba, low)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rgb, rgba = inputs()
    (OUT / "rgb.bin").write_bytes(rgb.tobytes())
    (OUT / "rgba.bin").write_bytes(rgba.tobytes())
    (OUT / "low.bin").write_bytes(low_contrast(rgb).tobytes())
    (OUT / "post.bin").write_bytes(ImageOps.posterize(rgb, 2).tobytes())
    (OUT / "posta.bin").write_bytes(posterized_rgba(rgba).tobytes())
    mask = mask_image()
    (OUT / "mask.bin").write_bytes(mask.tobytes())
    lines = ["# name\tinput\toperation\tparameters\tcomparison\tmode (generated by tools/testdata/imageops-oracle.py, Pillow "
             + PIL.__version__ + ")"]
    for name, source, operation, parameters, comparison, result in cases(rgb, rgba):
        if isinstance(result, bytes):
            (OUT / (name + ".bin")).write_bytes(result)
            mode = "HIST"
        else:
            (OUT / (name + ".bin")).write_bytes(result.tobytes())
            mode = result.mode
        lines.append("\t".join((name, source, operation, parameters, comparison, mode)))
    (OUT / "cases.tsv").write_text("\n".join(lines) + "\n", newline="\n")
    stats = stats_lines((("rgb", rgb), ("rgba", rgba), ("low", low_contrast(rgb))), mask)
    (OUT / "stats.tsv").write_text("\n".join(stats) + "\n", newline="\n")
    print(f"wrote {len(lines) - 1} cases to {OUT}")


if __name__ == "__main__":
    main()
