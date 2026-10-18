#!/usr/bin/env python3
"""Generates the Little CMS reference outputs of the color management tests.

Reads core/src/test/resources/com/github/auties00/smalt/image/color/lcms/{profiles,cases}.tsv,
downloads each profile into the conformance cache (verifying its SHA-256), runs every case through
Little CMS via Pillow's ImageCms (8-bit, Little CMS default flags plus black point compensation when
asked), and writes one <case>.bin per case next to the manifests: the raw interleaved output samples
for the deterministic input set the Java test regenerates (TestProfiles.inputs).

Then reads image/src/test/resources/com/github/auties00/smalt/image/icc/images.tsv, decodes each
image with Pillow (libjpeg-turbo, libpng, libwebp; Adobe CMYK JPEGs un-inverted), converts the images
that embed a profile to core's builtin-srgb.icc with the perceptual intent, and fills in the size and
the SHA-256 of the RGB samples (in place; "-" for images without a profile).

Input sets, all 8-bit:
  RGB  : a 17x17x17 lattice of round(i * 255 / 16), red slowest, then 2048 pseudo-random pixels
  GRAY : every value 0..255
  CMYK : a 9x9x9x9 lattice of round(i * 255 / 8), cyan slowest, then 2048 pseudo-random pixels
The pseudo-random bytes come from the 64-bit LCG x = x * 6364136223846793005 + 1442695040888963407
seeded with 0x9E3779B97F4A7C15, taking the top byte of each state.

Usage: python tools/testdata/lcms-oracle.py   (needs Pillow built with Little CMS; checked: 12.0 / lcms 2.17)
"""
import hashlib
import io
import os
import sys
import urllib.request
from pathlib import Path

from PIL import Image, ImageCms

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "core/src/test/resources/com/github/auties00/smalt/image/color/lcms"
IMAGES = ROOT / "image/src/test/resources/com/github/auties00/smalt/image/icc/images.tsv"


def cache_dir():
    configured = os.environ.get("SMALT_CONFORMANCE_CACHE")
    if configured:
        return Path(configured)
    return Path.home() / ".smalt/cache/conformance"


def read_tsv(name):
    rows = []
    for line in (DATA / name).read_text().splitlines():
        if line and not line.startswith("#"):
            rows.append(line.split("\t"))
    return rows


def fetch(name, sha256, url):
    target = cache_dir() / name
    if not target.is_file():
        target.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url) as response:
            target.write_bytes(response.read())
    data = target.read_bytes()
    if hashlib.sha256(data).hexdigest() != sha256:
        sys.exit(f"checksum mismatch for {name}")
    return data


def noise(count):
    state = 0x9E3779B97F4A7C15
    out = bytearray(count)
    for i in range(count):
        state = (state * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        out[i] = state >> 56
    return bytes(out)


def inputs(space):
    if space == "GRAY":
        return bytes(range(256))
    channels, levels = (3, 17) if space == "RGB" else (4, 9)
    values = [(i * 255 + (levels - 1) // 2) // (levels - 1) for i in range(levels)]
    out = bytearray()
    total = levels ** channels
    for index in range(total):
        pixel = []
        rest = index
        for _ in range(channels):
            pixel.append(values[rest % levels])
            rest //= levels
        out.extend(reversed(pixel))
    out.extend(noise(2048 * channels))
    return bytes(out)


TAB = chr(9)
NEWLINE = chr(10)
MODES = {"RGB": "RGB", "GRAY": "L", "CMYK": "CMYK"}
CHANNELS = {"RGB": 3, "GRAY": 1, "CMYK": 4}
INTENTS = {"P": ImageCms.Intent.PERCEPTUAL, "R": ImageCms.Intent.RELATIVE_COLORIMETRIC,
           "S": ImageCms.Intent.SATURATION, "A": ImageCms.Intent.ABSOLUTE_COLORIMETRIC}


def space_of(profile_bytes):
    signature = profile_bytes[16:20].decode("ascii")
    return {"RGB ": "RGB", "GRAY": "GRAY", "CMYK": "CMYK"}[signature]


def main():
    profiles = {name: fetch(name, sha, url) for name, sha, url in read_tsv("profiles.tsv")}
    for name, source, target, intent, bpc in read_tsv("cases.tsv"):
        src_bytes, dst_bytes = profiles[source], profiles[target]
        in_space, out_space = space_of(src_bytes), space_of(dst_bytes)
        data = inputs(in_space)
        pixels = len(data) // CHANNELS[in_space]
        image = Image.frombytes(MODES[in_space], (pixels, 1), data)
        flags = ImageCms.Flags.BLACKPOINTCOMPENSATION if bpc == "1" else ImageCms.Flags.NONE
        transform = ImageCms.buildTransform(ImageCms.ImageCmsProfile(io.BytesIO(src_bytes)),
                                            ImageCms.ImageCmsProfile(io.BytesIO(dst_bytes)),
                                            MODES[in_space], MODES[out_space], renderingIntent=INTENTS[intent],
                                            flags=flags)
        result = ImageCms.applyTransform(image, transform).tobytes()
        (DATA / f"{name}.bin").write_bytes(result)
        print(f"{name}: {in_space} -> {out_space}, {pixels} pixels")
    images()


def images():
    target = ImageCms.ImageCmsProfile(io.BytesIO((DATA / "builtin-srgb.icc").read_bytes()))
    lines = IMAGES.read_text().splitlines()
    out = []
    for line in lines:
        if not line or line.startswith("#"):
            out.append(line)
            continue
        name, sha, url = line.split(TAB)[:3]
        image = Image.open(io.BytesIO(fetch(name, sha, url)))
        image.load()
        icc = image.info.get("icc_profile")
        digest = "-"
        if icc:
            mode = "CMYK" if image.mode == "CMYK" else "RGB"
            image = image.convert(mode)
            converted = ImageCms.profileToProfile(image, ImageCms.ImageCmsProfile(io.BytesIO(icc)), target,
                                                  renderingIntent=ImageCms.Intent.PERCEPTUAL, outputMode="RGB")
            digest = hashlib.sha256(converted.convert("RGB").tobytes()).hexdigest()
        out.append(TAB.join([name, sha, url, str(image.size[0]), str(image.size[1]), digest]))
        print(f"{name}: {image.size}, {digest}")
    IMAGES.write_text(NEWLINE.join(out) + NEWLINE)


if __name__ == "__main__":
    main()
