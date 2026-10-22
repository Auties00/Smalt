"""Writes the AVIF samples of the I/O behavior harness (video/src/test/resources/samples/avif) and the matching
seeds of the AVIF fuzz target, which no encoder writes: derived images and a sample transform.

ffmpeg 7.1 (libaom) encodes the coded items from its synthetic testsrc2 and color sources; this script takes their
AV1 payloads and av1C records apart and assembles them, box by box, into:

  derived.avif  an image overlay (iovl) of an identity derivation (iden) of a rotated tile and of a second tile,
                over a fill color, with a thumbnail (thmb) item
  sato.avif     a 16-bit image split into its 8 most significant bits (the primary item) and its 8 least
                significant bits (hidden), coded losslessly, and the sample transform (sato) that joins them,
                preferred to the primary item by an alternatives group (altr)

As for make-samples.sh, the committed files are the reference their golden dumps are tied to; pin them with
pin-samples.py.

    python -I tools/testdata/behavior/make-avif-samples.py [repository root]
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile


def ffmpeg(arguments, output):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact"] + arguments + [output],
                   check=True)


def boxes(data, start, end):
    offset = start
    while offset + 8 <= end:
        size, kind = struct.unpack(">I4s", data[offset:offset + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[offset + 8:offset + 16])[0]
            header = 16
        elif size == 0:
            size = end - offset
        yield kind.decode("latin-1"), offset + header, offset + size
        offset += size


def coded_item(path):
    """Returns the payload and the av1C record of the primary item of an AVIF file written by ffmpeg."""
    data = open(path, "rb").read()
    meta = next((s, e) for kind, s, e in boxes(data, 0, len(data)) if kind == "meta")
    primary = None
    locations = {}
    properties = []
    associations = {}
    for kind, start, end in boxes(data, meta[0] + 4, meta[1]):
        if kind == "pitm":
            version = data[start]
            primary = struct.unpack(">H" if version == 0 else ">I", data[start + 4:start + (6 if version == 0 else 8)])[0]
        elif kind == "iloc":
            version = data[start]
            sizes = data[start + 4]
            offset_size, length_size = sizes >> 4, sizes & 15
            base_size = data[start + 5] >> 4
            index_size = data[start + 5] & 15 if version in (1, 2) else 0
            position = start + 6
            count = struct.unpack(">H", data[position:position + 2])[0]
            position += 2

            def read(size):
                nonlocal position
                value = int.from_bytes(data[position:position + size], "big") if size else 0
                position += size
                return value
            for _ in range(count):
                item = read(2 if version < 2 else 4)
                if version in (1, 2):
                    read(2)
                read(2)
                base = read(base_size)
                extents = []
                for _ in range(read(2)):
                    read(index_size)
                    extents.append((base + read(offset_size), read(length_size)))
                locations[item] = extents
        elif kind == "iprp":
            for child, child_start, child_end in boxes(data, start, end):
                if child == "ipco":
                    properties = [(k, data[s:e]) for k, s, e in boxes(data, child_start, child_end)]
                elif child == "ipma":
                    version, flags = data[child_start], int.from_bytes(data[child_start + 1:child_start + 4], "big")
                    position = child_start + 4
                    count = struct.unpack(">I", data[position:position + 4])[0]
                    position += 4
                    for _ in range(count):
                        size = 2 if version < 1 else 4
                        item = int.from_bytes(data[position:position + size], "big")
                        position += size
                        links = data[position]
                        position += 1
                        indexes = []
                        for _ in range(links):
                            if flags & 1:
                                value = struct.unpack(">H", data[position:position + 2])[0] & 0x7FFF
                                position += 2
                            else:
                                value = data[position] & 0x7F
                                position += 1
                            indexes.append(value)
                        associations[item] = indexes
    payload = b"".join(data[o:o + n] for o, n in locations[primary])
    config = next(payload_bytes for index in associations[primary]
                  for kind, payload_bytes in [properties[index - 1]] if kind == "av1C")
    return payload, config


def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind.encode("latin-1")) + payload


def full_box(kind, version, flags, payload):
    return box(kind, struct.pack(">I", (version << 24) | flags) + payload)


def ispe(width, height):
    return full_box("ispe", 0, 0, struct.pack(">II", width, height))


def pixi(*depths):
    return full_box("pixi", 0, 0, bytes([len(depths)] + list(depths)))


def av1c(record):
    return box("av1C", record)


def irot(turns):
    return box("irot", bytes([turns]))


class Heif:
    """One file: items with their data, properties and references, written as HeifBuilder writes them."""

    def __init__(self):
        self.items = []
        self.references = []
        self.extra = []
        self.primary = 0

    def item(self, kind, data, properties, idat=False, hidden=False):
        self.items.append({"type": kind, "data": data, "properties": properties, "idat": idat, "hidden": hidden})
        return len(self.items)

    def reference(self, kind, source, *targets):
        self.references.append(box(kind, struct.pack(">HH", source, len(targets))
                                   + b"".join(struct.pack(">H", t) for t in targets)))

    def build(self):
        ftyp = box("ftyp", b"avif" + struct.pack(">I", 0) + b"avifmif1miaf")
        hdlr = full_box("hdlr", 0, 0, struct.pack(">I", 0) + b"pict" + bytes(12) + b"\0")
        pitm = full_box("pitm", 0, 0, struct.pack(">H", self.primary))
        infe = b"".join(full_box("infe", 2, 1 if item["hidden"] else 0, struct.pack(">HH", index + 1, 0)
                                 + item["type"].encode("latin-1") + b"\0")
                        for index, item in enumerate(self.items))
        iinf = full_box("iinf", 0, 0, struct.pack(">H", len(self.items)) + infe)
        properties = []
        ipma = b""
        for index, item in enumerate(self.items):
            links = b""
            for property_box, essential in item["properties"]:
                properties.append(property_box)
                links += bytes([(0x80 if essential else 0) | len(properties)])
            ipma += struct.pack(">H", index + 1) + bytes([len(item["properties"])]) + links
        ipco = box("ipco", b"".join(properties))
        iprp = box("iprp", ipco + full_box("ipma", 0, 0, struct.pack(">I", len(self.items)) + ipma))
        iref = full_box("iref", 0, 0, b"".join(self.references))
        idat_data = b"".join(item["data"] for item in self.items if item["idat"])
        mdat_data = b"".join(item["data"] for item in self.items if not item["idat"])

        def meta(mdat_start):
            entries = b""
            idat_offset = 0
            mdat_offset = mdat_start
            for index, item in enumerate(self.items):
                if item["idat"]:
                    offset, method = idat_offset, 1
                    idat_offset += len(item["data"])
                else:
                    offset, method = mdat_offset, 0
                    mdat_offset += len(item["data"])
                entries += struct.pack(">HHHHII", index + 1, method, 0, 1, offset, len(item["data"]))
            iloc = full_box("iloc", 1, 0, bytes([0x44, 0x00]) + struct.pack(">H", len(self.items)) + entries)
            return full_box("meta", 0, 0, hdlr + pitm + iloc + iinf + iref + iprp + b"".join(self.extra)
                            + box("idat", idat_data))
        mdat_start = len(ftyp) + len(meta(0)) + 8
        return ftyp + meta(mdat_start) + box("mdat", mdat_data)


def overlay(fill, width, height, *offsets):
    return (bytes([0, 0]) + b"".join(struct.pack(">H", v) for v in fill) + struct.pack(">HH", width, height)
            + b"".join(struct.pack(">hh", x, y) for x, y in offsets))


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    samples = os.path.join(root, "video", "src", "test", "resources", "samples", "avif")
    seeds = os.path.join(root, "video", "src", "test", "resources", "com", "github", "auties00", "smalt", "video",
                         "fuzz", "VideoFuzzTestInputs", "avif")
    os.makedirs(samples, exist_ok=True)
    work = tempfile.mkdtemp()
    try:
        still = ["-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "8", "-frames:v", "1"]
        source = ["-f", "lavfi", "-i", "testsrc2=size=128x96:rate=1"]
        ffmpeg(source + ["-vf", "crop=64:48:0:0", "-pix_fmt", "yuv420p"] + still + ["-crf", "30"],
               os.path.join(work, "a.avif"))
        ffmpeg(source + ["-vf", "crop=48:48:64:32", "-pix_fmt", "yuv420p"] + still + ["-crf", "30"],
               os.path.join(work, "b.avif"))
        ffmpeg(source + ["-vf", "scale=48:40", "-pix_fmt", "yuv420p"] + still + ["-crf", "40"],
               os.path.join(work, "thumb.avif"))
        black = ["-f", "lavfi", "-i", "color=black:size=48x32:rate=1"]
        lossless = still + ["-aom-params", "lossless=1", "-pix_fmt", "gray"]
        ffmpeg(black + ["-vf", "format=gray,geq=lum='2*X+Y'"] + lossless, os.path.join(work, "msb.avif"))
        ffmpeg(black + ["-vf", "format=gray,geq=lum='mod(7*X+13*Y,256)'"] + lossless, os.path.join(work, "lsb.avif"))
        a, b, thumb = (coded_item(os.path.join(work, n + ".avif")) for n in ("a", "b", "thumb"))
        msb, lsb = (coded_item(os.path.join(work, n + ".avif")) for n in ("msb", "lsb"))
    finally:
        shutil.rmtree(work)

    derived = Heif()
    tile_a = derived.item("av01", a[0], [(ispe(64, 48), False), (av1c(a[1]), True), (pixi(8, 8, 8), False),
                                         (irot(1), True)], hidden=True)
    tile_b = derived.item("av01", b[0], [(ispe(48, 48), False), (av1c(b[1]), True), (pixi(8, 8, 8), False)],
                          hidden=True)
    identity = derived.item("iden", b"", [(ispe(48, 64), False)], idat=True, hidden=True)
    derived.reference("dimg", identity, tile_a)
    canvas = derived.item("iovl", overlay((0x2000, 0x4000, 0x6000, 0xFFFF), 96, 80, (4, 8), (44, 30)),
                          [(ispe(96, 80), False)], idat=True)
    derived.reference("dimg", canvas, identity, tile_b)
    thumbnail = derived.item("av01", thumb[0], [(ispe(48, 40), False), (av1c(thumb[1]), True),
                                                (pixi(8, 8, 8), False)])
    derived.reference("thmb", thumbnail, canvas)
    derived.primary = canvas

    sato = Heif()
    high = sato.item("av01", msb[0], [(ispe(48, 32), False), (av1c(msb[1]), True), (pixi(8), False)])
    low = sato.item("av01", lsb[0], [(ispe(48, 32), False), (av1c(lsb[1]), True), (pixi(8), False)], hidden=True)
    expression = bytes([2, 5, 0]) + struct.pack(">i", 256) + bytes([1, 130, 2, 133])
    transform = sato.item("sato", expression, [(ispe(48, 32), False), (pixi(16), False)], idat=True)
    sato.reference("dimg", transform, high, low)
    sato.primary = high
    sato.extra.append(box("grpl", full_box("altr", 0, 0, struct.pack(">IIII", 1, 2, transform, high))))

    for name, heif in (("derived.avif", derived), ("sato.avif", sato)):
        data = heif.build()
        open(os.path.join(samples, name), "wb").write(data)
        open(os.path.join(seeds, "valid-" + name), "wb").write(data)


if __name__ == "__main__":
    main()
