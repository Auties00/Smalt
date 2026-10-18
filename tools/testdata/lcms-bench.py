#!/usr/bin/env python3
"""Times Little CMS (through Pillow's ImageCms) on the 8-bit transforms of ColorTransformBenchmark.

Each case converts a 4032x3024 picture (Pillow holds RGB and CMYK as 4 bytes per pixel, like the
benchmark's RGBA and CMYK layouts) into a preallocated output, single-threaded, and prints the mean
and the 99.9% confidence half-width over the measured runs, in milliseconds. Run it under
bench-lock.sh, like every timing run. Needs Pillow built with Little CMS and the profiles in the
conformance cache (python tools/testdata/lcms-oracle.py fetches them).
"""
import io
import os
import statistics
import time
from pathlib import Path

from PIL import Image, ImageCms

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "core/src/test/resources/com/github/auties00/smalt/image/color/lcms"
WIDTH, HEIGHT = 4032, 3024


def cache(name):
    root = Path(os.environ.get("SMALT_CONFORMANCE_CACHE", Path.home() / ".smalt/cache/conformance"))
    return ImageCms.ImageCmsProfile(io.BytesIO((root / name).read_bytes()))


def main():
    srgb = ImageCms.ImageCmsProfile(io.BytesIO((DATA / "builtin-srgb.icc").read_bytes()))
    cases = [
        ("p3", "RGB", cache("compact-icc-DisplayP3-v4.icc")),
        ("lut", "RGB", cache("skia-icc-color.org-sRGB_ICC_v4_Appearance.icc")),
        ("cmyk", "CMYK", cache("skia-icc-misc-Coated_FOGRA39_CMYK.icc")),
    ]
    noise = os.urandom(WIDTH * HEIGHT * 4)
    for name, mode, profile in cases:
        source = Image.frombytes(mode, (WIDTH, HEIGHT), noise if mode == "CMYK" else noise[:WIDTH * HEIGHT * 3])
        target = Image.new("RGB", (WIDTH, HEIGHT))
        transform = ImageCms.buildTransform(profile, srgb, mode, "RGB", renderingIntent=ImageCms.Intent.PERCEPTUAL)
        for _ in range(3):
            transform.apply(source, target)
        times = []
        for _ in range(15):
            start = time.perf_counter()
            transform.apply(source, target)
            times.append((time.perf_counter() - start) * 1000)
        mean = statistics.mean(times)
        error = 3.29 * statistics.stdev(times) / len(times) ** 0.5
        print(f"lcms {name}: {mean:.2f} +- {error:.2f} ms ({len(times)} runs)")


if __name__ == "__main__":
    main()
