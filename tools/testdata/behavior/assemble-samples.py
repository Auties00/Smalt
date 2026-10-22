"""Assembles the image samples ffmpeg cannot write, from files ffmpeg wrote, following the published layouts.

    python -I assemble-samples.py ani OUT [--rate N,...] [--seq N,...] [--display-rate N] [--title T]
                                       [--artist A] [--hotspot X,Y] ICON...
    python -I assemble-samples.py gif-icc IN.gif PROFILE.icc OUT.gif

"ani" writes a Windows animated cursor, RIFF('ACON' [LIST('INFO' INAM IART)] anih [rate] [seq ]
LIST('fram' icon...)), from icon files; --hotspot turns each icon into a cursor (directory type 2, the planes
and bit-count fields of every entry holding the hotspot). "gif-icc" inserts an ICCRGBG1 "012" application
extension holding the profile (ICC.1 Annex B) right after the logical screen descriptor and global color table.
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


def main():
    command = sys.argv[1]
    if command == "ani":
        ani(sys.argv[2:])
    elif command == "gif-icc":
        gif_icc(sys.argv[2:])
    else:
        raise SystemExit("unknown command " + command)


if __name__ == "__main__":
    main()
