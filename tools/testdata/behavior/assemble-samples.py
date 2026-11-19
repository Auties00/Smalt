"""Assembles the image samples ffmpeg cannot write, from files ffmpeg wrote, following the published layouts.

    python -I assemble-samples.py ani OUT [--rate N,...] [--seq N,...] [--display-rate N] [--title T]
                                       [--artist A] [--hotspot X,Y] ICON...
    python -I assemble-samples.py gif-icc IN.gif PROFILE.icc OUT.gif
    python -I assemble-samples.py jpx FIRST.j2k SECOND.j2k OUT.jpx
    python -I assemble-samples.py mct IN.j2k OUT.j2k

"ani" writes a Windows animated cursor, RIFF('ACON' [LIST('INFO' INAM IART)] anih [rate] [seq ]
LIST('fram' icon...)), from icon files; --hotspot turns each icon into a cursor (directory type 2, the planes
and bit-count fields of every entry holding the hotspot). "gif-icc" inserts an ICCRGBG1 "012" application
extension holding the profile (ICC.1 Annex B) right after the logical screen descriptor and global color table.
"jpx" writes a JPX file (ISO/IEC 15444-2 annex M) of two RGB codestreams: the first split into three fragments
held out of order in two media data boxes and located by a fragment table, the second contiguous; two compositing
layer header boxes, the second with a color group; and a composition that shows the first layer for 100 ms, then
the second at a quarter size over it for 200 ms, twice. "mct" adds to a three-component codestream coded without a
component transformation the CBD, MCT, MCC and MCO segments (ISO/IEC 15444-2 A.3.6 to A.3.9) of an irreversible
YCbCr to RGB decorrelation (one stage, one collection, 32-bit float arrays), and marks the extension in Rsiz and in the COD segment.
"""
import struct
import sys


def chunk(identifier, data):
    padding = b"\0" if len(data) % 2 else b""
    return identifier.encode("ascii") + struct.pack("<I", len(data)) + data + padding


def cursor(icon, x, y):
    data = bytearray(icon)
    if data[0:4] != b"\0\0\1\0":
        raise SystemExit("not an icon file")
    data[2] = 2
    count = struct.unpack_from("<H", data, 4)[0]
    for entry in range(count):
        struct.pack_into("<HH", data, 6 + 16 * entry + 4, x, y)
    return bytes(data)


def ani(arguments):
    out = arguments.pop(0)
    options = {}
    icons = []
    while arguments:
        argument = arguments.pop(0)
        if argument.startswith("--"):
            options[argument[2:]] = arguments.pop(0)
        else:
            icons.append(open(argument, "rb").read())
    if "hotspot" in options:
        x, y = (int(value) for value in options["hotspot"].split(","))
        icons = [cursor(icon, x, y) for icon in icons]
    rate = [int(value) for value in options["rate"].split(",")] if "rate" in options else None
    seq = [int(value) for value in options["seq"].split(",")] if "seq" in options else None
    steps = len(seq) if seq else len(rate) if rate else len(icons)
    display = int(options.get("display-rate", rate[0] if rate else 6))
    body = b"ACON"
    if "title" in options or "artist" in options:
        info = b"INFO"
        if "title" in options:
            info += chunk("INAM", options["title"].encode("cp1252") + b"\0")
        if "artist" in options:
            info += chunk("IART", options["artist"].encode("cp1252") + b"\0")
        body += chunk("LIST", info)
    flags = 1 | (2 if seq else 0)
    body += chunk("anih", struct.pack("<9I", 36, len(icons), steps, 0, 0, 0, 0, display, flags))
    if rate:
        body += chunk("rate", struct.pack("<%dI" % len(rate), *rate))
    if seq:
        body += chunk("seq ", struct.pack("<%dI" % len(seq), *seq))
    body += chunk("LIST", b"fram" + b"".join(chunk("icon", icon) for icon in icons))
    open(out, "wb").write(chunk("RIFF", body))


def gif_icc(arguments):
    source, profile, out = arguments
    data = open(source, "rb").read()
    icc = open(profile, "rb").read()
    packed = data[10]
    start = 13 + (3 * (2 << (packed & 7)) if packed & 0x80 else 0)
    extension = b"\x21\xff\x0bICCRGBG1012"
    for offset in range(0, len(icc), 255):
        block = icc[offset:offset + 255]
        extension += bytes([len(block)]) + block
    extension += b"\0"
    open(out, "wb").write(data[:start] + extension + data[start:])


def box(kind, *contents):
    data = b"".join(contents)
    return struct.pack(">I", 8 + len(data)) + kind.encode("ascii") + data


def jpx(arguments):
    first, second, out = (arguments[0], arguments[1], arguments[2])
    a = open(first, "rb").read()
    b = open(second, "rb").read()
    width, height = struct.unpack_from(">II", a, 8)
    header = box("jp2h", box("ihdr", struct.pack(">IIHBBBB", height, width, 3, 7, 7, 0, 0)),
                 box("colr", struct.pack(">BbBI", 1, 0, 1, 16)))
    start = (b"\0\0\0\x0cjP  \r\n\x87\n" + box("ftyp", b"jpx \0\0\0\0jpx jp2 ") + header)
    third = len(a) // 3
    pieces = [a[:third], a[third:2 * third], a[2 * third:]]

    def table(offsets):
        entries = b"".join(struct.pack(">QIH", offset, len(piece), 0) for offset, piece in zip(offsets, pieces))
        return box("ftbl", box("flst", struct.pack(">H", 3) + entries))

    layers = (box("jplh") + box("jplh", box("cgrp", box("colr", struct.pack(">BbBI", 1, 0, 1, 16)))))
    instruction = struct.pack(">IIII", 0, 0, 0x80000000 | 1, 0)
    small = struct.pack(">IIIIII", width // 4, height // 4, width // 2, height // 2, 0x80000000 | 2, 0)
    composition = box("comp", box("copt", struct.pack(">IIB", height, width, 1)),
                      box("inst", struct.pack(">HHI", 9, 0, 100), instruction),
                      box("inst", struct.pack(">HHI", 11, 0, 100), small))
    placeholder = table([0, 0, 0])
    media_second = box("mdat", pieces[1])
    prefix = start + placeholder + box("jp2c", b) + layers + composition
    second_offset = len(prefix) + 8
    first_offset = len(prefix) + len(media_second) + 8
    offsets = [first_offset, second_offset, first_offset + len(pieces[0])]
    data = (start + table(offsets) + box("jp2c", b) + layers + composition + media_second
            + box("mdat", pieces[0] + pieces[2]))
    open(out, "wb").write(data)


def mct(arguments):
    source, out = arguments
    data = bytearray(open(source, "rb").read())
    matrix = [1.0, 0.0, 1.402, 1.0, -0.344136, -0.714136, 1.0, 1.772, 0.0]
    offsets = [0.0, 0.0, 0.0]

    def segment(marker, body):
        return struct.pack(">HH", marker, len(body) + 2) + body

    def array(index, kind, values):
        return segment(0xFF74, struct.pack(">HHH", 0, index | (kind << 8) | (2 << 10), 0)
                       + b"".join(struct.pack(">f", value) for value in values))

    collection = struct.pack(">HBHH", 0, 1, 0, 1) + struct.pack(">BH", 1, 3) + bytes([0, 1, 2]) \
        + struct.pack(">H", 3) + bytes([0, 1, 2]) + bytes([0, 2, 1])
    segments = (segment(0xFF78, struct.pack(">H", 3) + bytes([7, 7, 7])) + array(1, 1, matrix)
                + array(2, 2, offsets) + segment(0xFF75, collection) + segment(0xFF77, bytes([1, 1])))
    position = 2
    while struct.unpack_from(">H", data, position)[0] != 0xFF90:
        position += 2 + struct.unpack_from(">H", data, position + 2)[0]
    data[position:position] = segments
    struct.pack_into(">H", data, 6, 0x8100)
    position = 2
    while struct.unpack_from(">H", data, position)[0] != 0xFF52:
        position += 2 + struct.unpack_from(">H", data, position + 2)[0]
    data[position + 8] = 2
    open(out, "wb").write(bytes(data))


def main():
    command = sys.argv[1]
    if command == "ani":
        ani(sys.argv[2:])
    elif command == "gif-icc":
        gif_icc(sys.argv[2:])
    elif command == "jpx":
        jpx(sys.argv[2:])
    elif command == "mct":
        mct(sys.argv[2:])
    else:
        raise SystemExit("unknown command " + command)


if __name__ == "__main__":
    main()
