#!/usr/bin/env python3
"""Generates the Pillow reference outputs of the image operation tests (PillowOracleTest).

Writes into core/src/test/resources/com/github/auties00/smalt/image/ops/pillow:
  rgb.bin, rgba.bin : the two 61x47 inputs, raw interleaved RGB and RGBA (straight alpha)
  low.bin           : the RGB input with its range compressed to 40..190, for autocontrast
  cases.tsv         : one line per case: name, input, operation, parameters, comparison
  <name>.bin        : Pillow's output, raw interleaved (RGB, RGBA or L as the case says)

The Java test runs the matching Smalt operation on the same input and compares as the comparison
column says: "exact", "max:T" (every sample within T), "interior:B:T" (samples at least B pixels
from the edges within T), "rotated:M:T" (samples whose source point lies at least M pixels inside
the input within T).

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

Usage: python tools/testdata/imageops-oracle.py   (checked with Pillow 12.3.0)
"""
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter, ImageOps
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


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rgb, rgba = inputs()
    (OUT / "rgb.bin").write_bytes(rgb.tobytes())
    (OUT / "rgba.bin").write_bytes(rgba.tobytes())
    (OUT / "low.bin").write_bytes(low_contrast(rgb).tobytes())
    lines = ["# name\tinput\toperation\tparameters\tcomparison\tmode (generated by tools/testdata/imageops-oracle.py, Pillow "
             + PIL.__version__ + ")"]
    for name, source, operation, parameters, comparison, result in cases(rgb, rgba):
        (OUT / (name + ".bin")).write_bytes(result.tobytes())
        lines.append("\t".join((name, source, operation, parameters, comparison, result.mode)))
    (OUT / "cases.tsv").write_text("\n".join(lines) + "\n", newline="\n")
    print(f"wrote {len(lines) - 1} cases to {OUT}")


if __name__ == "__main__":
    main()
