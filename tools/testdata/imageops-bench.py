#!/usr/bin/env python3
"""Times Pillow and libvips (through pyvips) on the operations of ImageEffectsBenchmark.

The input is a 4032x3024 opaque RGBA photo of gradients plus uniform noise, like the Java
benchmark's (the content does not affect these operations' cost). Each case runs 3 warm-up and 15
measured times and prints the mean and the 99.9% confidence half-width in milliseconds. libvips
runs with its operation cache off, once on one thread (VIPS_CONCURRENCY=1, set before pyvips
loads) and once with its default thread count: run the script twice, with "--vips-threads 1" and
without. Pillow is single-threaded. Run it under bench-lock.sh, like every timing run.

Pillow -> libvips equivalents:
  GaussianBlur(s)                       -> gaussblur(s)
  rotate(-12, BICUBIC, expand=True)     -> similarity(angle=12, interpolate=bicubic)
  resize(1600x1200, LANCZOS)
    .filter(UnsharpMask(1, 80, 2))      -> resize(1600/4032, kernel=lanczos3).sharpen(sigma=1)
  alpha_composite of an 800x300 mark    -> composite2(mark, over)
  sepia via convert("RGB", matrix)      -> recomb(matrix) (Pillow converts only RGB, so its input is RGB)
  MedianFilter(3)                       -> median(3)

Usage: python tools/testdata/imageops-bench.py [--vips-threads N] [--vips-only]   (needs Pillow, numpy, pyvips)
"""
import os
import statistics
import sys
import time

if "--vips-threads" in sys.argv:
    os.environ["VIPS_CONCURRENCY"] = sys.argv[sys.argv.index("--vips-threads") + 1]

import numpy as np
import pyvips
from PIL import Image, ImageFilter

WIDTH, HEIGHT = 4032, 3024
T999 = {15: 4.140}


def photo():
    random = np.random.default_rng(12)
    x = np.arange(WIDTH)[None, :]
    y = np.arange(HEIGHT)[:, None]
    noise = lambda: random.integers(-12, 13, size=(HEIGHT, WIDTH))
    rgba = np.empty((HEIGHT, WIDTH, 4), dtype=np.uint8)
    rgba[..., 0] = np.clip(x * 255 // WIDTH + noise(), 0, 255)
    rgba[..., 1] = np.clip(y * 255 // HEIGHT + noise(), 0, 255)
    rgba[..., 2] = np.clip((x + y) * 255 // (WIDTH + HEIGHT) + noise(), 0, 255)
    rgba[..., 3] = 255
    return rgba


def mark():
    x = np.arange(800)[None, :]
    y = np.arange(300)[:, None]
    rgba = np.empty((300, 800, 4), dtype=np.uint8)
    rgba[..., 0] = x * 255 // 799
    rgba[..., 1] = np.broadcast_to(y * 255 // 299, (300, 800))
    rgba[..., 2] = np.where(((x // 7 + y // 5) & 1) == 0, 40, 220)
    rgba[..., 3] = (x + y) * 255 // 1098
    return rgba


def measure(name, action):
    for _ in range(3):
        action()
    times = []
    for _ in range(15):
        start = time.perf_counter()
        action()
        times.append((time.perf_counter() - start) * 1000)
    mean = statistics.mean(times)
    half = T999[15] * statistics.stdev(times) / len(times) ** 0.5
    print(f"{name:32s} {mean:9.1f} +- {half:6.1f} ms")


def main():
    pyvips.cache_set_max(0)
    data = photo()
    pil = Image.fromarray(data, "RGBA")
    pil_mark = Image.fromarray(mark(), "RGBA")
    vips = pyvips.Image.new_from_memory(data.tobytes(), WIDTH, HEIGHT, 4, "uchar").copy(interpretation="srgb")
    vips_mark = pyvips.Image.new_from_memory(mark().tobytes(), 800, 300, 4, "uchar").copy(interpretation="srgb")
    sepia = [0.393, 0.769, 0.189, 0, 0.349, 0.686, 0.168, 0, 0.272, 0.534, 0.131, 0]
    bicubic = pyvips.Interpolate.new("bicubic")
    threads = os.environ.get("VIPS_CONCURRENCY", "default")
    print(f"Pillow {Image.__version__ if hasattr(Image, '__version__') else ''}, libvips {pyvips.version(0)}.{pyvips.version(1)}."
          f"{pyvips.version(2)} with {threads} threads")

    if "--vips-only" in sys.argv:
        vips_cases(vips, vips_mark, sepia, bicubic)
        return
    measure("pillow blur 2", lambda: pil.filter(ImageFilter.GaussianBlur(2)))
    measure("pillow blur 10", lambda: pil.filter(ImageFilter.GaussianBlur(10)))
    measure("pillow rotate bicubic 12", lambda: pil.rotate(-12, Image.Resampling.BICUBIC, expand=True))
    measure("pillow rotate bilinear 12", lambda: pil.rotate(-12, Image.Resampling.BILINEAR, expand=True))
    measure("pillow resize+sharpen", lambda: pil.resize((1600, 1200), Image.Resampling.LANCZOS)
            .filter(ImageFilter.UnsharpMask(1, 80, 2)))

    def pil_watermark():
        out = pil.copy()
        out.alpha_composite(pil_mark, (WIDTH - 816, HEIGHT - 316))
        return out

    measure("pillow watermark", pil_watermark)
    pil_rgb = pil.convert("RGB")
    measure("pillow sepia (RGB)", lambda: pil_rgb.convert("RGB", sepia))
    measure("pillow median 3", lambda: pil.filter(ImageFilter.MedianFilter(3)))

    vips_cases(vips, vips_mark, sepia, bicubic)


def vips_cases(vips, vips_mark, sepia, bicubic):
    measure("vips blur 2", lambda: vips.gaussblur(2).write_to_memory())
    measure("vips blur 10", lambda: vips.gaussblur(10).write_to_memory())
    measure("vips rotate bicubic 12", lambda: vips.similarity(angle=12, interpolate=bicubic).write_to_memory())
    measure("vips rotate bilinear 12", lambda: vips.similarity(angle=12).write_to_memory())
    measure("vips resize+sharpen", lambda: vips.resize(1600 / WIDTH, kernel="lanczos3").sharpen(sigma=1).write_to_memory())
    measure("vips watermark", lambda: vips.composite2(vips_mark, "over", x=WIDTH - 816, y=HEIGHT - 316).write_to_memory())
    measure("vips sepia", lambda: vips.extract_band(0, n=3).recomb([sepia[0:3], sepia[4:7], sepia[8:11]])
            .cast("uchar").bandjoin(255).write_to_memory())
    measure("vips median 3", lambda: vips.median(3).write_to_memory())


if __name__ == "__main__":
    main()
