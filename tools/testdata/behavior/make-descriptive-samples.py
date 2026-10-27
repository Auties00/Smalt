"""Regenerates the I/O behavior samples of descriptive container metadata.

Writes, under the given directory (core/src/test/resources/samples by default):

- matroska/editions.mkv: a hand-built Matroska file with a PCM audio track and an uncompressed video track
  declaring a StereoMode and an equirectangular Projection, two chapter editions (the second the ordered default),
  nested chapters, titles in several languages, a TIMECODE tag, and a font and a text attachment;
- mp4/timecode-spherical.mov: an ffmpeg QuickTime file with a drop-frame timecode track, whose visual sample
  entry this script extends with Spherical Video V2 st3d and sv3d boxes;
- wav/bext-cue.wav: a hand-built WAVE file of PCM with bext, iXML, chna and smpl chunks, cue points and their
  LIST adtl labels and regions;
- aiff/markers.aiff: a hand-built AIFF file of PCM with MARK, INST and COMT chunks;
- flac/cuesheet.flac: an ffmpeg FLAC stream with a CUESHEET block inserted after its STREAMINFO.

As for make-samples.sh, the committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-descriptive-samples.py [output directory]
"""
import math
import os
import struct
import subprocess
import sys
import tempfile


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact", *args], check=True)


def tone(rate, frames, channels=1, frequency=440.0):
    samples = bytearray()
    for index in range(frames):
        value = int(12000 * math.sin(2 * math.pi * frequency * index / rate))
        for _ in range(channels):
            samples += struct.pack("<h", value)
    return bytes(samples)


def ebml_id(value):
    length = 4 if value >= 0x1000000 else 3 if value >= 0x10000 else 2 if value >= 0x100 else 1
    return value.to_bytes(length, "big")


def ebml_size(value):
    length = 1
    while value >= (1 << (7 * length)) - 1:
        length += 1
    return (value | (1 << (7 * length))).to_bytes(length, "big")


def element(identifier, payload):
    return ebml_id(identifier) + ebml_size(len(payload)) + payload


def master(identifier, *children):
    return element(identifier, b"".join(children))


def uint(identifier, value):
    return element(identifier, value.to_bytes(max(1, (value.bit_length() + 7) // 8), "big"))


def string(identifier, value):
    return element(identifier, value.encode("utf-8"))


def floating(identifier, value):
    return element(identifier, struct.pack(">d", value))


def display(text, *languages, bcp47=()):
    children = [string(0x85, text)]
    children += [string(0x437C, language) for language in languages]
    children += [string(0x437D, language) for language in bcp47]
    return master(0x80, *children)


def atom(uid, start, end, *children):
    fields = [uint(0x73C4, uid), uint(0x91, start * 1_000_000)]
    if end is not None:
        fields.append(uint(0x92, end * 1_000_000))
    return master(0xB6, *fields, *children)


def matroska():
    header = master(0x1A45DFA3, uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
                    string(0x4282, "matroska"), uint(0x4287, 4), uint(0x4285, 2))
    info = master(0x1549A966, uint(0x2AD7B1, 1_000_000), floating(0x4489, 1000.0), string(0x7BA9, "editions"))
    projection = master(0x7670, uint(0x7671, 1),
                        element(0x7672, struct.pack(">IIIII", 0, 0x10000000, 0x20000000, 0, 0)),
                        floating(0x7673, 90.0), floating(0x7674, -45.0), floating(0x7675, 30.0))
    video = master(0xAE, uint(0xD7, 1), uint(0x73C5, 1), uint(0x83, 1), string(0x86, "V_UNCOMPRESSED"),
                   uint(0x23E383, 200_000_000),
                   master(0xE0, uint(0x53B8, 1), uint(0xB0, 16), uint(0xBA, 16), element(0x2EB524, b"YUY2"),
                          projection))
    audio = master(0xAE, uint(0xD7, 2), uint(0x73C5, 2), uint(0x83, 2), string(0x86, "A_PCM/INT/LIT"),
                   master(0xE1, floating(0xB5, 8000.0), uint(0x9F, 1), uint(0x6264, 16)))
    tracks = master(0x1654AE6B, video, audio)
    tags = master(0x1254C367, master(0x7373, master(0x63C0), master(0x67C8, string(0x45A3, "TIMECODE"),
                                                                     string(0x4487, "01:00:00;00"))))
    first = master(0x45B9, uint(0x45BC, 1), master(0x4520, string(0x4521, "Theatrical"), string(0x45E4, "en")),
                   atom(1, 0, 600, display("Intro", "eng"), display("Introduction", "fre", bcp47=("fr-CA",)),
                        atom(2, 100, 300, display("Inner", "und")), atom(3, 300, None, display("Second inner"))),
                   atom(4, 600, None, display("Rest", "eng", "ger")))
    second = master(0x45B9, uint(0x45BC, 2), uint(0x45DB, 1), uint(0x45DD, 1),
                    atom(5, 500, 1000, display("Late", "eng")), atom(6, 0, 500, display("Early", "eng")))
    hidden = master(0x45B9, uint(0x45BC, 3), uint(0x45BD, 1), atom(7, 0, 1000, display("Hidden", "eng")))
    chapters = master(0x1043A770, first, second, hidden)
    font = master(0x61A7, string(0x467E, "subtitle font"), string(0x466E, "Font.ttf"), string(0x4660, "font/ttf"),
                  element(0x465C, bytes(range(256)) * 2), uint(0x46AE, 11))
    notes = master(0x61A7, string(0x466E, "notes.txt"), string(0x4660, "text/plain"),
                   element(0x465C, b"descriptive samples\n"), uint(0x46AE, 12))
    attachments = master(0x1941A469, font, notes)
    clusters = []
    pcm = tone(8000, 8000)
    for cluster in range(5):
        blocks = [uint(0xE7, cluster * 200)]
        frame = bytes((cluster * 37 + index) & 0xFF for index in range(16 * 16 * 2))
        blocks.append(element(0xA3, b"\x81" + struct.pack(">h", 0) + b"\x80" + frame))
        for part in range(4):
            offset = (cluster * 4 + part) * 400
            blocks.append(element(0xA3, b"\x82" + struct.pack(">h", part * 50) + b"\x80"
                                  + pcm[offset * 2:(offset + 400) * 2]))
        clusters.append(master(0x1F43B675, *blocks))
    segment = master(0x18538067, info, tracks, tags, chapters, attachments, *clusters)
    return header + segment


def boxes(data, start, end):
    """Lists the (offset, size, type) of the boxes of a region."""
    result = []
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        result.append((position, size, kind.decode("latin-1")))
        position += size
    return result


def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind.encode("latin-1")) + payload


def spherical_mov(directory):
    source = os.path.join(directory, "timecode.mov")
    ffmpeg("-f", "lavfi", "-i", "testsrc=size=32x24:rate=30000/1001:duration=0.5", "-c:v", "mjpeg", "-q:v", "5",
           "-timecode", "01:00:00;00", source)
    data = bytearray(open(source, "rb").read())
    path = []
    position, end = 0, len(data)
    for wanted in ("moov", "trak", "mdia", "minf", "stbl", "stsd"):
        for offset, size, kind in boxes(data, position, end):
            if kind == wanted and (wanted != "trak" or b"vide" in data[offset:offset + size]):
                path.append(offset)
                position, end = offset + 8 + (8 if wanted == "stsd" else 0), offset + size
                break
        else:
            raise SystemExit("no " + wanted + " box")
    entry = boxes(data, position, end)[0]
    st3d = box("st3d", b"\0\0\0\0" + b"\x02")
    equi = box("equi", b"\0\0\0\0" + struct.pack(">IIII", 0, 0, 0x08000000, 0x08000000))
    prhd = box("prhd", b"\0\0\0\0" + struct.pack(">iii", 90 << 16, -45 << 16, 30 << 16))
    sv3d = box("sv3d", box("svhd", b"\0\0\0\0" + b"Smalt samples\0") + box("proj", prhd + equi))
    added = st3d + sv3d
    insert = entry[0] + entry[1]
    data[insert:insert] = added
    for offset in path + [entry[0]]:
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        data[offset:offset + 4] = struct.pack(">I", size + len(added))
    if any(kind == "mdat" for offset, _, kind in boxes(data, 0, len(data)) if offset > path[0]):
        raise SystemExit("the movie box must follow the media data")
    return bytes(data)


def chunk(identifier, payload, big=False):
    data = identifier.encode("latin-1") + struct.pack(">I" if big else "<I", len(payload)) + payload
    return data + (b"\0" if len(payload) % 2 else b"")


def wave():
    rate = 8000
    pcm = tone(rate, rate)
    fmt = struct.pack("<HHIIHH", 1, 1, rate, rate * 2, 2, 16)
    bext = bytearray(602)
    for offset, size, text in ((0, 256, "A take"), (256, 32, "Smalt"), (288, 32, "REF123"),
                               (320, 10, "2026-10-24"), (330, 8, "08:09:10")):
        bext[offset:offset + len(text)] = text.encode("ascii")
    bext[338:346] = struct.pack("<II", 172_800_000 & 0xFFFFFFFF, 172_800_000 >> 32)
    bext[346:348] = struct.pack("<H", 2)
    bext[348:380] = bytes(range(1, 33))
    bext[412:422] = struct.pack("<hhhhh", -2300, 0x7FFF, -150, 0x7FFF, 0x7FFF)
    bext += b"A=PCM,F=8000,W=16,M=mono\r\n"
    chna = struct.pack("<HH", 1, 1) + struct.pack("<H", 1) + b"ATU_00000001" + b"AT_00010001_01" + b"AP_00010002" + b"\0"
    smpl = struct.pack("<IIIIIIIII", 71, 2, 125000, 60, 0, 25, 0x01020304, 1, 0) + struct.pack("<IIIIII", 0, 0, 100,
                                                                                                    7999, 0, 0)
    cue = struct.pack("<I", 3)
    for identifier, sample in ((1, 0), (2, 2000), (3, 6000)):
        cue += struct.pack("<II", identifier, sample) + b"data" + struct.pack("<III", 0, 0, sample)
    adtl = b"adtl"
    adtl += chunk("labl", struct.pack("<I", 1) + b"Intro\0")
    adtl += chunk("ltxt", struct.pack("<II", 1, 1000) + b"rgn " + bytes(8))
    adtl += chunk("labl", struct.pack("<I", 3) + "Finé".encode("utf-8") + b"\0")
    info = b"INFO" + chunk("INAM", b"cue points\0")
    body = (b"WAVE" + chunk("fmt ", fmt) + chunk("bext", bytes(bext)) + chunk("iXML", b"<BWFXML><SCENE>12</SCENE></BWFXML>")
            + chunk("chna", chna) + chunk("smpl", smpl) + chunk("data", pcm) + chunk("cue ", cue)
            + chunk("LIST", adtl) + chunk("LIST", info))
    return b"RIFF" + struct.pack("<I", len(body)) + body


def extended(rate):
    exponent = 16383 + 15
    mantissa = rate << (63 - 15)
    while not mantissa & (1 << 63):
        mantissa <<= 1
        exponent -= 1
    return struct.pack(">HQ", exponent, mantissa)


def aiff():
    rate = 8000
    frames = rate
    pcm = b"".join(struct.pack(">h", struct.unpack("<h", tone(rate, frames)[index:index + 2])[0])
                   for index in range(0, frames * 2, 2))
    comm = struct.pack(">hIh", 1, frames, 16) + extended(rate)
    markers = [(1, 0, b"Start"), (2, 2000, b""), (3, 4000, b"Loop begin"), (4, 6000, b"Loop end"),
               (5, 7000, b"Late")]
    mark = struct.pack(">H", len(markers))
    for identifier, position, name in markers:
        mark += struct.pack(">hIB", identifier, position, len(name)) + name + (b"\0" if (1 + len(name)) % 2 else b"")
    inst = struct.pack(">bbbbbbhhhhhhh", 60, -3, 10, 100, 1, 127, -6, 1, 3, 4, 0, 0, 0)
    comments = [b"first note", b"second"]
    comt = struct.pack(">H", len(comments))
    for text in comments:
        comt += struct.pack(">IhH", 0, 0, len(text)) + text + (b"\0" if len(text) % 2 else b"")
    body = (b"AIFF" + chunk("COMM", comm, True) + chunk("MARK", mark, True) + chunk("INST", inst, True)
            + chunk("COMT", comt, True) + chunk("SSND", struct.pack(">II", 0, 0) + pcm, True))
    return b"FORM" + struct.pack(">I", len(body)) + body


def cuesheet_flac(directory):
    source = os.path.join(directory, "tone.flac")
    ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=1", "-c:a", "flac", "-sample_fmt",
           "s16", source)
    data = open(source, "rb").read()
    tracks = [(0, [(1, 0)]), (17640, [(0, 0), (1, 4410)]), (35280, [(1, 0)])]
    body = bytearray(396)
    body[395] = len(tracks) + 1
    for number, (offset, points) in enumerate(tracks, 1):
        body += struct.pack(">QB", offset, number) + bytes(12 + 14) + struct.pack(">B", len(points))
        for index, point in points:
            body += struct.pack(">QB", point, index) + bytes(3)
    body += struct.pack(">QB", 44100, 255) + bytes(12 + 14) + b"\0"
    last = data[4] & 0x80
    block = bytes([5 | last]) + len(body).to_bytes(3, "big") + bytes(body)
    return data[:4] + bytes([data[4] & 0x7F]) + data[5:42] + block + data[42:]


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repository = os.path.dirname(os.path.dirname(os.path.dirname(here)))
    output = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repository, "core", "src", "test", "resources",
                                                                "samples")
    with tempfile.TemporaryDirectory() as scratch:
        samples = {
            "matroska/editions.mkv": matroska(),
            "mp4/timecode-spherical.mov": spherical_mov(scratch),
            "wav/bext-cue.wav": wave(),
            "aiff/markers.aiff": aiff(),
            "flac/cuesheet.flac": cuesheet_flac(scratch),
        }
        for name, data in samples.items():
            path = os.path.join(output, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as file:
                file.write(data)


if __name__ == "__main__":
    main()
