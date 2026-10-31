"""Writes the OpenEXR test vectors of the image module with the reference OpenEXR library (the OpenEXR Python
bindings, BSD-3-Clause, version 3.5.2) and, next to each file, the samples that library decodes from it, so that
the tests compare Smalt's decoder with the reference one.

Each vector NAME.exr comes with NAME.f32: for each part in order, for each channel in the order OpenEXR sorts
them, the channel's samples (one per sampling period, rows top first) as little-endian float32, unsigned integer
channels converted to float. The vectors and their decodes are pinned in exr.sha256 next to them.

    python -I tools/testdata/behavior/make-exr-vectors.py [output directory]

The script needs numpy and OpenEXR (pip install OpenEXR==3.5.2 numpy).
"""
import hashlib
import os
import struct
import sys

import numpy as np
import OpenEXR as E


def pattern(height, width, seed, scale=4.0):
    """A smooth gradient with a noisy band and a flat band, values from about -1 to scale."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:height, 0:width]
    base = (x / max(1, width - 1)) * scale + np.sin(y * 0.3 + seed) * 0.5
    base[height // 3:2 * height // 3, :] += rng.normal(0, 0.3, (2 * height // 3 - height // 3, width))
    base[:height // 6, :] = 0.75
    base[-2:, :4] = -0.25
    return base


def color_channels(height, width, seed, dtype):
    return {
        "R": pattern(height, width, seed).astype(dtype),
        "G": pattern(height, width, seed + 1, 2.0).astype(dtype),
        "B": pattern(height, width, seed + 2, 1.0).astype(dtype),
        "A": np.clip(pattern(height, width, seed + 3, 1.0), 0, 1).astype(dtype),
    }


def tiles(x, y, mode, rounding):
    description = E.TileDescription()
    description.xSize = x
    description.ySize = y
    description.mode = mode
    description.roundingMode = rounding
    return description


def write(out, name, parts):
    path = os.path.join(out, name + ".exr")
    E.File(parts).write(path)
    decoded = E.File(path, separate_channels=True)
    samples = bytearray()
    for part in decoded.parts:
        for channel_name in sorted(part.channels.keys(), key=lambda n: n.encode("utf-8")):
            pixels = np.asarray(part.channels[channel_name].pixels)
            samples += pixels.astype("<f4").tobytes()
    with open(os.path.join(out, name + ".f32"), "wb") as file:
        file.write(bytes(samples))


def part(header, channels, name=""):
    return E.Part(header, {key: E.Channel(key, value) if not isinstance(value, E.Channel) else value
                           for key, value in channels.items()}, name)


def attribute(name, kind, value):
    return name.encode() + b"\0" + kind.encode() + b"\0" + struct.pack("<i", len(value)) + value


def channel_list(names, types):
    return b"".join(name.encode() + b"\0" + struct.pack("<iB3xii", kind, 0, 1, 1)
                    for name, kind in zip(names, types)) + b"\0"


def zip_filter(raw):
    """OpenEXR's ZIP filter, written independently: the even bytes then the odd ones, delta-coded, deflated."""
    import zlib
    split = raw[0::2] + raw[1::2]
    deltas = bytearray(split)
    for index in range(len(split) - 1, 0, -1):
        deltas[index] = (split[index] - split[index - 1] + 128 + 256) & 0xFF
    return zlib.compress(bytes(deltas), 9)


def zstd_filter(raw, grid, delta):
    """OpenEXR's ZSTD chunk framing (internal_zstd.c of 3.5.2), written independently. grid holds, for each line of
    the chunk, the (bytes per sample, sample count) of each channel in packed order; the half samples of every line
    move ahead of the 32-bit ones, delta coding optionally runs along each channel line, each half then splits into
    byte planes behind its u64 length, and the result is a zstd frame behind the 24-byte "zstd-exr" header (wire
    version 2 with flag 1 when delta coding ran, version 1 otherwise)."""
    import compression.zstd as zstd
    halves = bytearray()
    words = bytearray()
    position = 0
    for line in grid:
        for size, count in line:
            values = bytearray(raw[position:position + size * count])
            position += size * count
            if delta:
                mask = (1 << (8 * size)) - 1
                previous = [int.from_bytes(values[k * size:(k + 1) * size], "little") for k in range(count)]
                for k in range(count - 1, 0, -1):
                    values[k * size:(k + 1) * size] = ((previous[k] - previous[k - 1]) & mask).to_bytes(size, "little")
            (halves if size == 2 else words).extend(values)
    inner = b""
    if halves and words:
        segments = ((halves, 2), (words, 4))
    else:
        segments = ((bytes(halves) + bytes(words), 2 if halves else 4),)
    for segment, size in segments:
        inner += struct.pack("<Q", len(segment)) + b"".join(segment[plane::size] for plane in range(size))
    frame = zstd.compress(inner, 3)
    packed = struct.pack("<QIIQ", 8248453963162350458, 2 if delta else 1, 1 if delta else 0, len(frame)) + frame
    return packed if len(packed) < len(raw) else raw


def common_header(flags, names, types, compression, width, height):
    header = b"\x76\x2f\x31\x01" + struct.pack("<I", 2 | flags)
    header += attribute("channels", "chlist", channel_list(names, types))
    header += attribute("compression", "compression", bytes([compression]))
    header += attribute("dataWindow", "box2i", struct.pack("<4i", 0, 0, width - 1, height - 1))
    header += attribute("displayWindow", "box2i", struct.pack("<4i", 0, 0, width - 1, height - 1))
    header += attribute("lineOrder", "lineOrder", b"\0")
    header += attribute("pixelAspectRatio", "float", struct.pack("<f", 1))
    header += attribute("screenWindowCenter", "v2f", struct.pack("<2f", 0, 0))
    header += attribute("screenWindowWidth", "float", struct.pack("<f", 1))
    return header


def finish(out, name, header, chunks, samples):
    offset = len(header) + 8 * len(chunks)
    table = b""
    for chunk in chunks:
        table += struct.pack("<Q", offset)
        offset += len(chunk)
    with open(os.path.join(out, name + ".exr"), "wb") as file:
        file.write(header + table + b"".join(chunks))
    with open(os.path.join(out, name + ".f32"), "wb") as file:
        file.write(bytes(samples))


def level_count(size, rounding):
    log = size.bit_length() - 1
    if rounding and size & (size - 1):
        log += 1
    return log + 1


def level_size(size, level, rounding):
    return max(1, (size + (1 << level) - 1) >> level if rounding else size >> level)


def write_levels(out, name, width, height, tile_x, tile_y, mode, rounding, compression):
    """Writes a tiled mipmap (mode 1) or ripmap (mode 2) image of B, G and R halves with this script's own tile
    writer, since the bindings write the first level only; the .f32 holds every level in offset table order."""
    if mode == 1:
        levels = [(level, level) for level in range(level_count(max(width, height), rounding))]
    else:
        levels = [(lx, ly) for ly in range(level_count(height, rounding)) for lx in range(level_count(width, rounding))]
    names = ["B", "G", "R"]
    header = common_header(0x200, names, [1, 1, 1], compression, width, height)
    header += attribute("tiles", "tiledesc", struct.pack("<IIB", tile_x, tile_y, mode | (rounding << 4))) + b"\0"
    chunks = []
    samples = bytearray()
    for lx, ly in levels:
        level_width = level_size(width, lx, rounding)
        level_height = level_size(height, ly, rounding)
        planes = {n: pattern(level_height, level_width, 20 + 7 * lx + ly + ord(n)).astype("<f2") for n in names}
        for n in names:
            samples += planes[n].astype("<f4").tobytes()
        for ty in range((level_height + tile_y - 1) // tile_y):
            for tx in range((level_width + tile_x - 1) // tile_x):
                raw = bytearray()
                for y in range(ty * tile_y, min(level_height, ty * tile_y + tile_y)):
                    for n in names:
                        raw += planes[n][y, tx * tile_x:min(level_width, tx * tile_x + tile_x)].tobytes()
                data = bytes(raw)
                if compression == 3:
                    packed = zip_filter(data)
                    data = packed if len(packed) < len(data) else data
                chunks.append(struct.pack("<5i", tx, ty, lx, ly, len(data)) + data)
    finish(out, name, header, chunks, samples)


def write_deep(out, name, width, height, compression=2, lines=1):
    """Writes a deep scanline image (ZIPS or ZIP, one or 16 lines per chunk) of A, B, G, R halves and a Z float with
    this script's own writer, as OpenEXR lays deep chunks out: per line cumulative sample counts, then the samples
    line by line, each line channel after channel. Pixel (x, y) holds (x + 2 y) % 4 samples in random depth order;
    the .f32 holds the flattened planes in channel order, sorted front to back by depth and composited over as
    ExrDecoder documents, Z the nearest depth."""
    names = ["A", "B", "G", "R", "Z"]
    rng = np.random.default_rng(31)
    header = common_header(0x800, names, [1, 1, 1, 1, 2], compression, width, height)
    header += attribute("type", "string", b"deepscanline")
    header += attribute("version", "int", struct.pack("<i", 1)) + b"\0"
    flat = {n: np.zeros((height, width), dtype=np.float32) for n in names}
    chunks = []
    for start in range(0, height, lines):
        table = b""
        data = b""
        for y in range(start, min(height, start + lines)):
            counts = []
            values = {n: [] for n in names}
            for x in range(width):
                count = (x + 2 * y) % 4
                counts.append(count)
                pixel = []
                for _ in range(count):
                    z = float(np.float32(rng.uniform(1, 10)))
                    a = float(np.float16(rng.uniform(0.2, 0.9)))
                    color = [float(np.float16(rng.uniform(0, 1) * a)) for _ in range(3)]
                    pixel.append((z, a, color))
                    values["A"].append(a)
                    values["B"].append(color[2])
                    values["G"].append(color[1])
                    values["R"].append(color[0])
                    values["Z"].append(z)
                ordered = sorted(pixel, key=lambda sample: sample[0])
                acc = [np.float32(0)] * 4
                for z, a, color in ordered:
                    weight = np.float32(1) - acc[3]
                    acc = [np.float32(acc[0] + weight * np.float32(color[0])),
                           np.float32(acc[1] + weight * np.float32(color[1])),
                           np.float32(acc[2] + weight * np.float32(color[2])),
                           np.float32(acc[3] + weight * np.float32(a))]
                    if acc[3] >= 1:
                        break
                flat["R"][y, x], flat["G"][y, x], flat["B"][y, x], flat["A"][y, x] = acc
                flat["Z"][y, x] = ordered[0][0] if ordered else 0
            table += struct.pack("<%di" % width, *[int(v) for v in np.cumsum(counts)])
            data += b"".join(np.array(values[n], dtype="<f4" if n == "Z" else "<f2").tobytes() for n in names)
        if compression == 13:
            lines_here = min(height, start + lines) - start
            packed_table = zstd_filter(table, [[(4, width)]] * lines_here, False)
            totals = [sum((x + 2 * y) % 4 for x in range(width)) for y in range(start, start + lines_here)]
            packed_data = zstd_filter(data, [[(4 if n == "Z" else 2, total) for n in names] for total in totals],
                                      False) if data else b""
        else:
            packed_table = zip_filter(table)
            packed_table = packed_table if len(packed_table) < len(table) else table
            packed_data = zip_filter(data) if data else b""
            packed_data = packed_data if len(packed_data) < len(data) else data
        chunks.append(struct.pack("<iqqq", start, len(packed_table), len(packed_data), len(data)) + packed_table
                      + packed_data)
    finish(out, name, header, chunks, b"".join(flat[n].astype("<f4").tobytes() for n in names))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "image/src/test/resources/com/github/auties00/smalt/image/exr"
    os.makedirs(out, exist_ok=True)
    height, width = 40, 23
    methods = {
        "none": E.NO_COMPRESSION, "rle": E.RLE_COMPRESSION, "zips": E.ZIPS_COMPRESSION, "zip": E.ZIP_COMPRESSION,
        "piz": E.PIZ_COMPRESSION, "pxr24": E.PXR24_COMPRESSION, "b44": E.B44_COMPRESSION,
        "b44a": E.B44A_COMPRESSION, "dwaa": E.DWAA_COMPRESSION, "dwab": E.DWAB_COMPRESSION,
        "htj2k256": E.HTJ2K256_COMPRESSION, "htj2k32": E.HTJ2K32_COMPRESSION, "lj2k": E.LJ2K_COMPRESSION,
        "zstd": E.ZSTD_COMPRESSION,
    }
    for label, method in methods.items():
        channels = color_channels(height, width, 1, np.float16)
        channels["Z"] = pattern(height, width, 9, 100.0).astype(np.float32)
        channels["id"] = (np.arange(height * width, dtype=np.uint32).reshape(height, width) * 2654435761) & 0xFFFFF
        write(out, "scanline-" + label, [part({"compression": method, "type": E.scanlineimage}, channels)])
    floats = color_channels(height, width, 5, np.float32)
    for label, method in (("piz", E.PIZ_COMPRESSION), ("pxr24", E.PXR24_COMPRESSION), ("dwab", E.DWAB_COMPRESSION),
                          ("zip", E.ZIP_COMPRESSION), ("htj2k256", E.HTJ2K256_COMPRESSION),
                          ("lj2k", E.LJ2K_COMPRESSION), ("zstd", E.ZSTD_COMPRESSION)):
        write(out, "float-" + label, [part({"compression": method, "type": E.scanlineimage}, floats)])
    # small half chunks code larger than they are and stay uncompressed, so the lossy half vector is larger and smooth
    y, x = np.mgrid[0:300, 0:97]
    smooth = {"R": (x / 40.0 + np.sin(y / 17.0)).astype(np.float16),
              "G": (np.cos(x / 23.0) * np.sin(y / 31.0) + 0.5).astype(np.float16),
              "B": ((x + y) / 200.0).astype(np.float16),
              "A": np.full((300, 97), 1.0, dtype=np.float16)}
    write(out, "smooth-lj2k", [part({"compression": E.LJ2K_COMPRESSION, "type": E.scanlineimage}, smooth)])
    write(out, "smooth-htj2k256", [part({"compression": E.HTJ2K256_COMPRESSION, "type": E.scanlineimage}, smooth)])
    # values past the half range make the lossy float codec build a transfer table for the chunk's own range
    bright = color_channels(height, width, 21, np.float32)
    bright["R"] = (bright["R"] * 30000).astype(np.float32)
    write(out, "float-bright-lj2k", [part({"compression": E.LJ2K_COMPRESSION, "type": E.scanlineimage}, bright)])
    for label, method in (("htj2k32", E.HTJ2K32_COMPRESSION), ("zstd", E.ZSTD_COMPRESSION),
                          ("lj2k", E.LJ2K_COMPRESSION)):
        write(out, "tiled-" + label, [part({"compression": method, "type": E.tiledimage,
                                            "tiles": tiles(16, 12, E.ONE_LEVEL, E.ROUND_DOWN)},
                                           color_channels(height, width, 22, np.float16))])
    write_levels(out, "tiled-mipmap-zip", width, height, 16, 12, 1, 0, 3)
    write_levels(out, "tiled-mipmap-up-none", width, height, 8, 8, 1, 1, 0)
    write_levels(out, "tiled-ripmap-zip", width, height, 10, 10, 2, 0, 3)
    write_deep(out, "deep-scanline-zips", 9, 7)
    write_deep(out, "deep-scanline-zstd", 9, 7, compression=13)
    tile = tiles(32, 32, E.ONE_LEVEL, E.ROUND_DOWN)
    write(out, "tiled-dwaa", [part({"compression": E.DWAA_COMPRESSION, "type": E.tiledimage, "tiles": tile},
                                   color_channels(height, width, 6, np.float16))])
    write(out, "multipart", [
        part({"compression": E.ZIP_COMPRESSION, "type": E.scanlineimage},
             color_channels(height, width, 7, np.float16), "beauty"),
        part({"compression": E.PIZ_COMPRESSION, "type": E.tiledimage,
              "tiles": tiles(16, 16, E.ONE_LEVEL, E.ROUND_DOWN)},
             {"Z": pattern(height, width, 8, 10.0).astype(np.float32)}, "depth")])
    layered = {"diffuse.R": pattern(height, width, 10).astype(np.float16),
               "diffuse.G": pattern(height, width, 11).astype(np.float16),
               "diffuse.B": pattern(height, width, 12).astype(np.float16),
               "spec.R": pattern(height, width, 13).astype(np.float16)}
    write(out, "layers-zips", [part({"compression": E.ZIPS_COMPRESSION, "type": E.scanlineimage}, layered)])
    even = width + 1
    luma = {"Y": np.abs(pattern(height, even, 14)).astype(np.float16),
            "RY": E.Channel("RY", pattern(height, even, 15, 0.5).astype(np.float16), 2, 2),
            "BY": E.Channel("BY", pattern(height, even, 16, 0.5).astype(np.float16), 2, 2)}
    for label, method in (("piz", E.PIZ_COMPRESSION), ("htj2k256", E.HTJ2K256_COMPRESSION),
                          ("zstd", E.ZSTD_COMPRESSION)):
        write(out, "luminance-chroma-" + label, [part({"compression": method, "type": E.scanlineimage}, luma)])
    windowed = color_channels(20, 30, 17, np.float16)
    for suffix, method in (("", E.ZIP_COMPRESSION), ("-zstd", E.ZSTD_COMPRESSION),
                           ("-htj2k32", E.HTJ2K32_COMPRESSION)):
        write(out, "windows-decreasing" + suffix, [part({"compression": method, "type": E.scanlineimage,
                                                         "dataWindow": ((5, 3), (34, 22)),
                                                         "displayWindow": ((0, 0), (39, 29)),
                                                         "lineOrder": E.DECREASING_Y}, windowed)])
    # a negative data window origin
    for label, method in (("htj2k32", E.HTJ2K32_COMPRESSION), ("zstd", E.ZSTD_COMPRESSION)):
        write(out, "luminance-chroma-negative-" + label, [part({"compression": method, "type": E.scanlineimage,
                                                                "dataWindow": ((-6, -4), (even - 7, height - 5)),
                                                                "displayWindow": ((0, 0), (even - 1, height - 1))},
                                                               luma)])
    write(out, "gray-b44-linear", [part({"compression": E.B44_COMPRESSION, "type": E.scanlineimage},
                                        {"Y": E.Channel("Y", np.abs(pattern(height, width, 18)).astype(np.float16),
                                                        1, 1, True)})])
    with open(os.path.join(out, "exr.sha256"), "w", newline="\n") as manifest:
        for name in sorted(os.listdir(out)):
            if name.endswith(".exr") or name.endswith(".f32"):
                with open(os.path.join(out, name), "rb") as file:
                    manifest.write(hashlib.sha256(file.read()).hexdigest() + "  " + name + "\n")


if __name__ == "__main__":
    main()
