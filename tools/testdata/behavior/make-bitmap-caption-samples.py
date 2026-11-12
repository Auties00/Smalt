"""Writes the bitmap subtitle and caption samples of the I/O behavior harness and of the bitmap subtitle and caption
tests, and the inputs the other samples are made from with ffmpeg.

    python -I tools/testdata/behavior/make-bitmap-caption-samples.py sup OUT.sup
    python -I tools/testdata/behavior/make-bitmap-caption-samples.py dvb OUT.ts
    python -I tools/testdata/behavior/make-bitmap-caption-samples.py vobsub OUT.sub OUT.idx
    python -I tools/testdata/behavior/make-bitmap-caption-samples.py teletext OUT.ts
    python -I tools/testdata/behavior/make-bitmap-caption-samples.py captions IN.h264 OUT.h264 [--hevc] [--mpeg2]

"sup" writes a Blu-ray presentation graphic stream file (.sup) of six display sets on a 720x480 canvas: an epoch
start with two objects (a palette of opaque, half-transparent and transparent entries), a palette update, a forced
composition with a cropped object, an acquisition point, and clearing compositions, every segment behind its "PG"
header with a 90 kHz presentation timestamp.

"dvb" writes an MPEG transport stream with one DVB subtitle stream (stream_type 0x06, subtitling descriptor for
composition and ancillary page 1 in English) of four display sets on a 720x576 display: a mode change with a 4-bit
region on a defined full-range CLUT and a 2-bit region on the default CLUT, both filled and holding run-length coded
interlaced objects; a clearing acquisition point; a mode change with a new region version
and object; and a second clear, each PES packet (stream_id 0xBD) holding the 0x20 0x00 data identifiers, the
segments and the 0xFF end marker.

"vobsub" writes the MPEG program stream of a VobSub pair (.sub) with two subpicture streams (0x20 and 0x21) of three
subpicture units each, every unit with start and stop display commands, colors, contrast, a display area and
interlaced run-length coded fields, one unit spread over two packs; and its index (.idx) with a palette, the frame
size and the languages.

"teletext" writes an MPEG transport stream with one DVB teletext stream (stream_type 0x06, teletext descriptor for
subtitle page 888 in German) whose PES packets (stream_id 0xBD, 45-byte header, data_identifier 0x10, data units
0x03) carry three versions of page 888: boxed double-height text with alpha colors and German national option
characters, a page with an X/26 diacritical mark, and an erased page.

"captions" adds ATSC A/53 caption data to every access unit of an Annex B H.264 (or, with --hevc, HEVC) elementary
stream that ffmpeg wrote with access unit delimiters, as user_data_registered_itu_t_t35 SEI messages before the
first slice, or, with --mpeg2, to every picture of an MPEG-2 elementary stream as user data after the picture's
extensions. The caption data is a CEA-608 CC1 script (a pop-on caption, a two-row roll-up caption, a paint-on
caption, mid-row italics and colors, special and extended characters) with CC3 text on field 2, and a CEA-708
service 1 script in DTVCC packets, the frame's 608 pairs first, padded to 20 constructs per frame at 29.97 Hz.
"""
import struct
import sys




def pgs_rle(indices, width, height):
    out = bytearray()
    for row in range(height):
        line = indices[row * width:(row + 1) * width]
        x = 0
        while x < width:
            color = line[x]
            run = 1
            while x + run < width and line[x + run] == color and run < 16383:
                run += 1
            if color != 0 and run <= 2:
                out += bytes([color]) * run
            elif color == 0:
                if run < 64:
                    out += bytes([0, run])
                else:
                    out += bytes([0, 0x40 | (run >> 8), run & 0xFF])
            else:
                if run < 64:
                    out += bytes([0, 0x80 | run, color])
                else:
                    out += bytes([0, 0xC0 | (run >> 8), run & 0xFF, color])
            x += run
        out += bytes([0, 0])
    return bytes(out)


def segment(pts, kind, body):
    return b"PG" + struct.pack(">IIBH", pts & 0xFFFFFFFF, 0, kind, len(body)) + body


def pcs(width, height, number, state, palette_update, palette_id, objects):
    body = struct.pack(">HHBHBBBB", width, height, 0x10, number, state, 0x80 if palette_update else 0, palette_id,
                       len(objects))
    for object_id, window, x, y, forced, crop in objects:
        flags = (0x80 if crop else 0) | (0x40 if forced else 0)
        body += struct.pack(">HBBHH", object_id, window, flags, x, y)
        if crop:
            body += struct.pack(">HHHH", *crop)
    return body


def wds(windows):
    body = bytes([len(windows)])
    for window, x, y, w, h in windows:
        body += struct.pack(">BHHHH", window, x, y, w, h)
    return body


def pds(palette_id, version, entries):
    body = bytes([palette_id, version])
    for index, y, cr, cb, alpha in entries:
        body += bytes([index, y, cr, cb, alpha])
    return body


def ods(object_id, version, width, height, rle):
    data = struct.pack(">HH", width, height) + rle
    return struct.pack(">HB", object_id, version) + bytes([0xC0]) + struct.pack(">I", len(data))[1:] + data


def picture(width, height, pattern):
    pixels = bytearray(width * height)
    for y in range(height):
        for x in range(width):
            pixels[y * width + x] = pattern(x, y)
    return bytes(pixels)


def write_sup(path):
    width, height = 720, 480
    palette = [(1, 235, 128, 128, 255), (2, 16, 128, 128, 255), (3, 81, 240, 90, 255), (4, 145, 34, 54, 128),
               (5, 41, 110, 240, 64)]
    first = picture(96, 24, lambda x, y: 1 if 4 <= y < 20 and (x // 8) % 2 == 0 else (2 if y < 4 else 0))
    second = picture(64, 16, lambda x, y: 3 + (x + y) % 3 if (x + y) % 5 else 0)
    third = picture(80, 20, lambda x, y: 1 if x % 10 < 5 else 4)
    stream = b""
    t = 90_000 // 2
    stream += segment(t, 0x16, pcs(width, height, 0, 0x80, False, 0,
                                   [(0, 0, 100, 380, False, None), (1, 1, 400, 40, False, None)]))
    stream += segment(t, 0x17, wds([(0, 100, 380, 96, 24), (1, 400, 40, 64, 16)]))
    stream += segment(t, 0x14, pds(0, 0, palette))
    stream += segment(t, 0x15, ods(0, 0, 96, 24, pgs_rle(first, 96, 24)))
    stream += segment(t, 0x15, ods(1, 0, 64, 16, pgs_rle(second, 64, 16)))
    stream += segment(t, 0x80, b"")
    t = 90_000
    stream += segment(t, 0x16, pcs(width, height, 1, 0x00, True, 0,
                                   [(0, 0, 100, 380, False, None), (1, 1, 400, 40, False, None)]))
    stream += segment(t, 0x14, pds(0, 1, [(1, 210, 146, 16, 255), (3, 81, 90, 240, 200)]))
    stream += segment(t, 0x80, b"")
    t = 90_000 * 3 // 2
    stream += segment(t, 0x16, pcs(width, height, 2, 0x00, False, 0, []))
    stream += segment(t, 0x17, wds([(0, 100, 380, 96, 24)]))
    stream += segment(t, 0x80, b"")
    t = 90_000 * 2
    stream += segment(t, 0x16, pcs(width, height, 3, 0x80, False, 0, [(2, 0, 300, 200, True, (10, 2, 60, 16))]))
    stream += segment(t, 0x17, wds([(0, 300, 200, 60, 16)]))
    stream += segment(t, 0x14, pds(0, 0, palette))
    stream += segment(t, 0x15, ods(2, 0, 80, 20, pgs_rle(third, 80, 20)))
    stream += segment(t, 0x80, b"")
    t = 90_000 * 5 // 2
    stream += segment(t, 0x16, pcs(width, height, 4, 0x40, False, 0, [(2, 0, 300, 200, False, None)]))
    stream += segment(t, 0x17, wds([(0, 300, 200, 80, 20)]))
    stream += segment(t, 0x14, pds(0, 0, palette))
    stream += segment(t, 0x15, ods(2, 0, 80, 20, pgs_rle(third, 80, 20)))
    stream += segment(t, 0x80, b"")
    t = 90_000 * 3
    stream += segment(t, 0x16, pcs(width, height, 5, 0x00, False, 0, []))
    stream += segment(t, 0x17, wds([(0, 300, 200, 80, 20)]))
    stream += segment(t, 0x80, b"")
    with open(path, "wb") as file:
        file.write(stream)




def spu_rle_line(line):
    nibbles = []
    x = 0
    while x < len(line):
        color = line[x]
        run = 1
        while x + run < len(line) and line[x + run] == color and run < 255:
            run += 1
        if run < 4:
            nibbles += [(run << 2) | color]
        elif run < 16:
            value = (run << 2) | color
            nibbles += [value >> 4, value & 0xF]
        elif run < 64:
            value = (run << 2) | color
            nibbles += [0, value >> 4, value & 0xF]
        else:
            value = (run << 2) | color
            nibbles += [0, value >> 8, (value >> 4) & 0xF, value & 0xF]
        x += run
    if len(nibbles) % 2:
        nibbles.append(0)
    return bytes((nibbles[i] << 4) | nibbles[i + 1] for i in range(0, len(nibbles), 2))


def spu(width, height, pixels, x, y, start, stop, colors, contrast, forced=False):
    top = b"".join(spu_rle_line(pixels[row * width:(row + 1) * width]) for row in range(0, height, 2))
    bottom = b"".join(spu_rle_line(pixels[row * width:(row + 1) * width]) for row in range(1, height, 2))
    data = top + bottom
    control = 4 + len(data)
    first = bytes([0x00 if forced else 0x01, 0x03]) + bytes(colors) + bytes([0x04]) + bytes(contrast)
    first += bytes([0x05, x >> 4, ((x & 0xF) << 4) | ((x + width - 1) >> 8), (x + width - 1) & 0xFF,
                    y >> 4, ((y & 0xF) << 4) | ((y + height - 1) >> 8), (y + height - 1) & 0xFF])
    first += bytes([0x06]) + struct.pack(">HH", 4, 4 + len(top)) + bytes([0xFF])
    second_offset = control + 4 + len(first)
    sequence = struct.pack(">HH", start, second_offset) + first
    sequence += struct.pack(">HH", stop, second_offset) + bytes([0x02, 0xFF])
    unit = data + sequence
    return struct.pack(">HH", 4 + len(unit), control) + unit


def pack_header(scr):
    return bytes([0, 0, 1, 0xBA, 0x44 | ((scr >> 27) & 0x38) | ((scr >> 28) & 3), (scr >> 20) & 0xFF,
                  ((scr >> 12) & 0xF8) | 4 | ((scr >> 13) & 3), (scr >> 5) & 0xFF, ((scr << 3) & 0xF8) | 4, 1,
                  0x01, 0x89, 0xC3, 0xF8])


def pes_timestamp(pts):
    return bytes([0x21 | ((pts >> 29) & 0x0E), (pts >> 22) & 0xFF, ((pts >> 14) & 0xFE) | 1, (pts >> 7) & 0xFF,
                  ((pts << 1) & 0xFE) | 1])


def write_vobsub(sub_path, idx_path):
    entries = {0: [], 1: []}
    out = bytearray()
    units = []
    for stream in (0, 1):
        for index in range(3):
            width, height = 40 + 8 * index, 12 + 2 * stream
            pixels = bytes(((x // 4 + y + stream) % 4) if (x + y) % 7 else 0 for y in range(height)
                           for x in range(width))
            pts = 90_000 * (index + 1) + 45_000 * stream
            if stream == 0 and index == 1:
                width, height = 160, 48
                pixels = bytes((x * 7 + y) % 4 for y in range(height) for x in range(width))
            unit = spu(width, height, pixels, 60 + 20 * index, 400 - 40 * stream - (100 if width > 100 else 0), 0,
                       50 + 10 * index, [0x32, 0x10], [0xFF, 0xF0], forced=(index == 2 and stream == 1))
            units.append((pts, stream, unit))
    units.sort()
    for pts, stream, unit in units:
        entries[stream].append((pts, len(out)))
        position = 0
        first = True
        while position < len(unit) or first:
            header = pes_timestamp(pts) if first else b""
            room = 2048 - 14 - 9 - len(header) - 1
            chunk = unit[position:position + room]
            position += len(chunk)
            pes = bytes([0x81, 0x80 if first else 0, len(header)]) + header + bytes([0x20 + stream]) + chunk
            packet = pack_header(pts) + bytes([0, 0, 1, 0xBD]) + struct.pack(">H", len(pes)) + pes
            padding = 2048 - len(packet)
            if padding >= 6:
                packet += bytes([0, 0, 1, 0xBE]) + struct.pack(">H", padding - 6) + b"\xff" * (padding - 6)
            else:
                packet += b"\xff" * padding
            out += packet
            first = False
    with open(sub_path, "wb") as file:
        file.write(out)
    palette = ["000000", "ffffff", "808080", "ff0000", "00ff00", "0000ff", "ffff00", "ff00ff", "00ffff",
               "404040", "c0c0c0", "800000", "008000", "000080", "808000", "800080"]
    lines = ["# VobSub index file, v7 (do not modify this line!)", "size: 720x480", "org: 0, 0", "scale: 100%, 100%",
             "alpha: 100%", "smooth: OFF", "fadein/out: 0, 0", "align: OFF at LEFT TOP", "time offset: 0",
             "forced subs: OFF", "palette: " + ", ".join(palette), "custom colors: OFF, tridx: 0000, colors: 000000,"
             " 000000, 000000, 000000", "langidx: 0", ""]
    for stream, language in ((0, "en"), (1, "de")):
        lines.append("id: %s, index: %d" % (language, stream))
        for pts, offset in entries[stream]:
            millis = pts // 90
            lines.append("timestamp: %02d:%02d:%02d:%03d, filepos: %09x" % (
                millis // 3_600_000, millis // 60_000 % 60, millis // 1000 % 60, millis % 1000, offset))
        lines.append("")
    with open(idx_path, "w", newline="\n") as file:
        file.write("\n".join(lines))




def reverse(value):
    return int("{:08b}".format(value)[::-1], 2)


def hamming84(value):
    d1, d2, d3, d4 = value & 1, (value >> 1) & 1, (value >> 2) & 1, (value >> 3) & 1
    p1 = 1 ^ d1 ^ d3 ^ d4
    p2 = 1 ^ d1 ^ d2 ^ d4
    p3 = 1 ^ d1 ^ d2 ^ d3
    p4 = 1 ^ p1 ^ d1 ^ p2 ^ d2 ^ p3 ^ d3 ^ d4
    return p1 | d1 << 1 | p2 << 2 | d2 << 3 | p3 << 4 | d3 << 5 | p4 << 6 | d4 << 7


def hamming2418(value):
    bits = [0] * 25
    data_positions = [3, 5, 6, 7] + list(range(9, 16)) + list(range(17, 24))
    for index, position in enumerate(data_positions):
        bits[position] = (value >> index) & 1
    for parity in (1, 2, 4, 8, 16):
        total = 1
        for position in range(1, 24):
            if position & parity and position != parity:
                total ^= bits[position]
        bits[parity] = total
    overall = 1
    for position in range(1, 24):
        overall ^= bits[position]
    bits[24] = overall
    word = 0
    for position in range(1, 25):
        word |= bits[position] << (position - 1)
    return [word & 0xFF, (word >> 8) & 0xFF, (word >> 16) & 0xFF]


def odd(value):
    return value | (0 if bin(value).count("1") % 2 else 0x80)


def data_unit(magazine, row, payload):
    m = magazine & 7
    address = [hamming84(m | ((row & 1) << 3)), hamming84(row >> 1)]
    body = [0xE4] + address + payload
    return bytes([0x03, 44, 0xC0 | 0x20 | 7]) + bytes(reverse(value) if index else 0xE4 for index, value
                                                        in enumerate(body))


def header_unit(magazine, page, erase, subtitle, national):
    units, tens = page & 0xF, (page >> 4) & 0xF
    subcode = [0, 8 if erase else 0, 0, 8 if subtitle else 0]
    control = [1, (national >> 2 & 1) << 1 | (national >> 1 & 1) << 2 | (national & 1) << 3]
    payload = [hamming84(units), hamming84(tens)] + [hamming84(v) for v in subcode + control]
    payload += [odd(0x20)] * 32
    return data_unit(magazine, 0, payload)


def row_unit(magazine, row, text):
    codes = list(text) + [0x20] * (40 - len(text))
    return data_unit(magazine, row, [odd(code) for code in codes[:40]])


def x26_unit(magazine, triplets):
    payload = [hamming84(0)]
    for triplet in triplets:
        payload += hamming2418(triplet)
    while len(payload) < 40:
        payload += hamming2418(63 | (0x1F << 6))
    return data_unit(magazine, 26, payload[:40])


def ts_packets(pid, payload, counter, start):
    packets = []
    position = 0
    while position < len(payload):
        chunk = payload[position:position + 184]
        position += len(chunk)
        header = bytes([0x47, (0x40 if start and not packets else 0) | (pid >> 8), pid & 0xFF,
                        0x10 | (counter & 0xF)])
        counter += 1
        if len(chunk) < 184:
            stuffing = 184 - len(chunk)
            header = header[:3] + bytes([0x30 | ((counter - 1) & 0xF)])
            adaptation = bytes([stuffing - 1]) + (bytes([0]) + b"\xff" * (stuffing - 2) if stuffing > 1 else b"")
            packets.append(header + adaptation + chunk)
        else:
            packets.append(header + chunk)
    return packets, counter


def crc32_mpeg(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def section(table, body):
    data = bytes([table, 0xB0 | ((len(body) + 4) >> 8), (len(body) + 4) & 0xFF]) + body
    return data + struct.pack(">I", crc32_mpeg(data))


def write_teletext(path):
    pid = 0x101
    descriptor = bytes([0x56, 5]) + b"ger" + bytes([(0x02 << 3) | 0, 0x88])
    pat = section(0x00, struct.pack(">HBBB", 1, 0xC1, 0, 0) + struct.pack(">HH", 1, 0xE000 | 0x1000))
    pmt = section(0x02, struct.pack(">HBBB", 1, 0xC1, 0, 0) + struct.pack(">HH", 0xE000 | pid, 0xF000)
                  + bytes([0x06]) + struct.pack(">HH", 0xE000 | pid, 0xF000 | len(descriptor)) + descriptor)
    out = bytearray()
    counters = {0: 0, 0x1000: 0, pid: 0}

    def psi(target, data):
        packets, counters[target] = ts_packets(target, b"\x00" + data, counters[target], True)
        for packet in packets:
            out.extend(packet)

    german = 0b001
    pages = [
        (90_000, [header_unit(8, 0x88, True, True, german),
                  row_unit(8, 20, [0x0D, 0x0B, 0x0B, 0x07] + list(b"Gr") + [0x7E] + list(b"n und ") + [0x7B]
                           + list(b"bel") + [0x0A, 0x0A]),
                  row_unit(8, 22, [0x0B, 0x0B, 0x03] + list(b"Gelber Text") + [0x0A, 0x0A] + list(b" not boxed"))]),
        (180_000, [header_unit(8, 0x88, True, True, german),
                   row_unit(8, 22, [0x0B, 0x0B] + list(b"Accent e") + [0x0A, 0x0A]),
                   x26_unit(8, [(22 + 40) | (0x04 << 6), 9 | (0x12 << 6) | (ord("e") << 11), 63 | (0x1F << 6)])]),
        (270_000, [header_unit(8, 0x88, True, True, german)]),
    ]
    psi(0, pat)
    psi(0x1000, pmt)
    for pts, units in pages:
        data = bytes([0x10]) + b"".join(units)
        header_length = 0x24
        stuffing_needed = (184 - (9 + header_length + len(data)) % 184) % 184
        while stuffing_needed >= 46:
            data += bytes([0xFF, 0x2C]) + b"\xff" * 44
            stuffing_needed -= 46
        data += b"\xff" * stuffing_needed
        header = bytes([0x84, 0x80, header_length]) + pes_timestamp(pts) + b"\xff" * (header_length - 5)
        pes = bytes([0, 0, 1, 0xBD]) + struct.pack(">H", len(header) + len(data)) + header + data
        psi(0, pat)
        psi(0x1000, pmt)
        packets, counters[pid] = ts_packets(pid, pes, counters[pid], True)
        for packet in packets:
            out.extend(packet)
    with open(path, "wb") as file:
        file.write(out)




def parity(value):
    return value | (0 if bin(value).count("1") % 2 else 0x80)


def control(channel, first, second):
    return (parity(first | (0x08 if channel == 2 else 0)), parity(second))


def text_pairs(text):
    codes = [ord(c) for c in text]
    if len(codes) % 2:
        codes.append(0)
    return [(parity(codes[i]), parity(codes[i + 1])) for i in range(0, len(codes), 2)]


def pac(row, indent=0, color=0, underline=False):
    rows = {1: (0x11, 0x40), 2: (0x11, 0x60), 3: (0x12, 0x40), 4: (0x12, 0x60), 5: (0x15, 0x40), 6: (0x15, 0x60),
            7: (0x16, 0x40), 8: (0x16, 0x60), 9: (0x17, 0x40), 10: (0x17, 0x60), 11: (0x10, 0x40), 12: (0x13, 0x40),
            13: (0x13, 0x60), 14: (0x14, 0x40), 15: (0x14, 0x60)}
    first, second = rows[row]
    attribute = (0x10 | (indent // 4) << 1) if indent else color << 1
    return first, second | attribute | (1 if underline else 0)


def cc1_script():
    """Returns (frame, b1, b2) pairs of field 1, CC1."""
    events = []

    def at(frame, pairs, twice=False):
        for index, pair in enumerate(pairs):
            events.append((frame + index, pair))

    def command(frame, code):
        pair = control(1, 0x14, code)
        at(frame, [pair, pair])

    command(28, 0x20)
    command(30, 0x2E)
    at(32, [control(1, *pac(15, indent=4)), control(1, *pac(15, indent=4))])
    at(34, text_pairs("Hello pop-on"))
    at(42, [control(1, 0x11, 0x2E), control(1, 0x11, 0x2E)])
    at(44, text_pairs("italic"))
    command(62, 0x2F)
    command(120, 0x2C)
    command(150, 0x25)
    at(152, [control(1, *pac(15)), control(1, *pac(15))])
    at(154, text_pairs("Roll one"))
    command(170, 0x2D)
    at(172, text_pairs("Roll two"))
    at(180, [control(1, 0x11, 0x28), control(1, 0x11, 0x28)])
    at(182, text_pairs("red"))
    command(200, 0x2D)
    command(240, 0x2C)
    command(260, 0x29)
    at(262, [control(1, *pac(2, indent=8)), control(1, *pac(2, indent=8))])
    at(264, text_pairs("Paint "))
    at(268, [control(1, 0x11, 0x37), control(1, 0x11, 0x37)])
    at(270, text_pairs("e"))
    at(271, [control(1, 0x12, 0x30), control(1, 0x12, 0x30)])
    command(300, 0x2C)
    return events


def cc3_script():
    events = []
    for frame, code in ((40, 0x20), (64, 0x2F), (130, 0x2C)):
        pair = control(1, 0x15, code)
        events.append((frame, pair))
        events.append((frame + 1, pair))
    events.append((44, control(1, *pac(14))))
    for index, pair in enumerate(text_pairs("Field two")):
        events.append((46 + index, pair))
    return events


def dtvcc_script():
    """Returns {frame: service block bytes} for service 1."""
    blocks = {}
    define = bytes([0x98, 0x38, 0x4A, 0x00, 0x02, 0x1F, 0x00])
    blocks[31] = define + bytes([0x90, 0x00, 0x00]) + b"Seven oh eight" + bytes([0x0D]) + bytes([0x90, 0x00, 0x80]) \
        + b"italic"
    blocks[90] = bytes([0x88, 0x01]) + bytes([0x92, 0x00, 0x00]) + b"Second" + bytes([0x10, 0x35])
    blocks[150] = bytes([0x8A, 0x01])
    return blocks


def dtvcc_packets(blocks):
    packets = {}
    sequence = 0
    for frame, block in blocks.items():
        service = bytes([(1 << 5) | len(block)]) + block
        size = len(service) + 1
        if size % 2:
            service += b"\x00"
            size += 1
        packets[frame] = bytes([(sequence << 6) | (size // 2)]) + service
        sequence = (sequence + 1) & 3
    return packets


def cc_data(frames):
    field1 = {}
    for frame, pair in cc1_script():
        field1.setdefault(frame, []).append(pair)
    field2 = {}
    for frame, pair in cc3_script():
        field2.setdefault(frame, []).append(pair)
    dtvcc = dtvcc_packets(dtvcc_script())
    queue = []
    result = []
    for frame in range(frames):
        constructs = []
        pairs1 = field1.get(frame, [(0x80, 0x80)])
        for pair in pairs1[:1]:
            constructs.append(bytes([0xFC, pair[0], pair[1]]))
        if len(pairs1) > 1:
            field1.setdefault(frame + 1, [])
            field1[frame + 1] = pairs1[1:] + field1[frame + 1]
        pairs2 = field2.get(frame, [(0x80, 0x80)])
        constructs.append(bytes([0xFD, pairs2[0][0], pairs2[0][1]]))
        if len(pairs2) > 1:
            field2[frame + 1] = pairs2[1:] + field2.get(frame + 1, [])
        if frame in dtvcc:
            packet = dtvcc[frame]
            queue.append(bytes([0xFF, packet[0], packet[1]]))
            for index in range(2, len(packet), 2):
                queue.append(bytes([0xFE, packet[index], packet[index + 1] if index + 1 < len(packet) else 0]))
        while len(constructs) < 20 and queue:
            constructs.append(queue.pop(0))
        while len(constructs) < 20:
            constructs.append(bytes([0xFA, 0, 0]))
        result.append(b"".join(constructs))
    return result


def escape(payload):
    out = bytearray()
    zeros = 0
    for byte in payload:
        if zeros >= 2 and byte <= 3:
            out.append(3)
            zeros = 0
        out.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(out)


def ga94(data):
    count = len(data) // 3
    return b"GA94" + bytes([0x03, 0xC0 | count, 0xFF]) + data + b"\xff"


def sei_unit(data, hevc):
    message = bytes([0xB5, 0x00, 0x31]) + ga94(data)
    payload = bytes([4, len(message)]) + message + b"\x80"
    header = bytes([0x4E, 0x01]) if hevc else bytes([0x06])
    return b"\x00\x00\x00\x01" + header + escape(payload)


def split_annexb(stream):
    starts = []
    index = 0
    while True:
        index = stream.find(b"\x00\x00\x01", index)
        if index < 0:
            break
        begin = index - 1 if index > 0 and stream[index - 1] == 0 else index
        starts.append((begin, index + 3))
        index += 3
    units = []
    for number, (begin, body) in enumerate(starts):
        end = starts[number + 1][0] if number + 1 < len(starts) else len(stream)
        units.append(stream[body:end])
    return units


def write_captions(source, target, hevc, mpeg2):
    with open(source, "rb") as file:
        stream = file.read()
    units = split_annexb(stream)
    out = bytearray()
    if mpeg2:
        frames = cc_data(4000)
        pictures = 0
        pending = False
        for unit in units:
            code = unit[0]
            if 0x01 <= code <= 0xAF and pending:
                out += b"\x00\x00\x01\xB2" + ga94(frames[pictures - 1])
                pending = False
            if code == 0x00:
                pictures += 1
                pending = True
            out += b"\x00\x00\x01" + unit
        with open(target, "wb") as file:
            file.write(out)
        return
    frames = cc_data(4000)
    picture = 0
    pending = False
    for unit in units:
        kind = (unit[0] >> 1) & 0x3F if hevc else unit[0] & 0x1F
        delimiter = kind == (35 if hevc else 9)
        vcl = kind < 32 if hevc else 1 <= kind <= 5
        if delimiter:
            pending = True
        if vcl and pending:
            out += sei_unit(frames[picture], hevc)
            picture += 1
            pending = False
        out += b"\x00\x00\x00\x01" + unit
    with open(target, "wb") as file:
        file.write(out)


class Bits:
    def __init__(self):
        self.out = bytearray()
        self.value = 0
        self.count = 0

    def put(self, value, width):
        for shift in range(width - 1, -1, -1):
            self.value = (self.value << 1) | ((value >> shift) & 1)
            self.count += 1
            if self.count == 8:
                self.out.append(self.value)
                self.value = 0
                self.count = 0

    def align(self):
        while self.count:
            self.put(0, 1)
        return bytes(self.out)


def dvb_runs(line):
    runs = []
    x = 0
    while x < len(line):
        n = 1
        while x + n < len(line) and line[x + n] == line[x]:
            n += 1
        runs.append((line[x], n))
        x += n
    return runs


def dvb_string4(line):
    bits = Bits()
    for color, n in dvb_runs(line):
        while n > 0:
            if color != 0 and n <= 3:
                bits.put(color, 4)
                n -= 1
            elif n >= 25:
                m = min(n, 280)
                bits.put(0, 4), bits.put(0b1111, 4), bits.put(m - 25, 8), bits.put(color, 4)
                n -= m
            elif n >= 9:
                m = min(n, 24)
                bits.put(0, 4), bits.put(0b1110, 4), bits.put(m - 9, 4), bits.put(color, 4)
                n -= m
            elif color != 0:
                m = min(n, 7)
                bits.put(0, 4), bits.put(0b10, 2), bits.put(m - 4, 2), bits.put(color, 4)
                n -= m
            elif n >= 3:
                bits.put(0, 4), bits.put(0, 1), bits.put(n - 2, 3)
                n = 0
            else:
                bits.put(0, 4), bits.put(0b1100 | (n - 1), 4)
                n = 0
    bits.put(0, 8)
    return bytes([0x11]) + bits.align()


def dvb_string2(line):
    bits = Bits()
    for color, n in dvb_runs(line):
        while n > 0:
            if color != 0 and n <= 2:
                bits.put(color, 2)
                n -= 1
            elif n >= 29:
                m = min(n, 284)
                bits.put(0, 2), bits.put(0b0011, 4), bits.put(m - 29, 8), bits.put(color, 2)
                n -= m
            elif n >= 12:
                m = min(n, 27)
                bits.put(0, 2), bits.put(0b0010, 4), bits.put(m - 12, 4), bits.put(color, 2)
                n -= m
            elif n >= 3:
                m = min(n, 10)
                bits.put(0, 2), bits.put(1, 1), bits.put(m - 3, 3), bits.put(color, 2)
                n -= m
            elif n == 2:
                bits.put(0, 2), bits.put(0b0001, 4)
                n = 0
            else:
                bits.put(0, 2), bits.put(0b01, 2)
                n = 0
    bits.put(0, 6)
    return bytes([0x10]) + bits.align()


def dvb_object(object_id, version, width, height, pattern, depth):
    string = dvb_string4 if depth == 4 else dvb_string2
    fields = []
    for first in (0, 1):
        data = bytearray()
        for y in range(first, height, 2):
            data += string([pattern(x, y) for x in range(width)]) + b"\xf0"
        fields.append(bytes(data))
    body = struct.pack(">HBHH", object_id, version << 4, len(fields[0]), len(fields[1])) + fields[0] + fields[1]
    return body


def dvb_segment(kind, page, body):
    return bytes([0x0F, kind]) + struct.pack(">HH", page, len(body)) + body


def dvb_display_sets():
    page = 1
    clut = bytes([1, 0x00])
    for entry, (y, cr, cb, t) in enumerate([(235, 128, 128, 0), (81, 240, 90, 0), (145, 34, 54, 64),
                                            (41, 110, 240, 128)], start=1):
        clut += bytes([entry, 0x40 | 0x01, y, cr, cb, t])
    region0 = struct.pack(">BBHHBBBB", 0, 0x08, 120, 20, (2 << 5) | (2 << 2), 1, 0, 0) + struct.pack(">HHH", 0, 0, 0)
    region1 = struct.pack(">BBHHBBBB", 1, 0x08, 64, 10, (1 << 5) | (1 << 2), 2, 0, 0) + struct.pack(">HHH", 1, 0, 0)
    region0b = struct.pack(">BBHHBBBB", 0, 0x18, 120, 20, (2 << 5) | (2 << 2), 1, 0, 0) + struct.pack(">HHH", 2, 0, 0)
    display = struct.pack(">BHH", 0x00, 719, 575)
    show = b"".join([
        dvb_segment(0x14, page, display),
        dvb_segment(0x10, page, bytes([10, 0x08]) + struct.pack(">BBHH", 0, 0, 100, 400)
                    + struct.pack(">BBHH", 1, 0, 300, 100)),
        dvb_segment(0x11, page, region0),
        dvb_segment(0x11, page, region1),
        dvb_segment(0x12, page, clut),
        dvb_segment(0x13, page, dvb_object(0, 0, 120, 20, lambda x, y: (x // 8 + y // 2) % 5, 4)),
        dvb_segment(0x13, page, dvb_object(1, 0, 64, 10, lambda x, y: 0 if x < 30 else 1 + (x + y) % 3, 2)),
        dvb_segment(0x80, page, b""),
    ])
    clear = dvb_segment(0x14, page, display) + dvb_segment(0x10, page, bytes([10, 0x14])) + dvb_segment(0x80, page, b"")
    again = b"".join([
        dvb_segment(0x14, page, display),
        dvb_segment(0x10, page, bytes([10, 0x28]) + struct.pack(">BBHH", 0, 0, 200, 450)),
        dvb_segment(0x11, page, region0b),
        dvb_segment(0x12, page, clut),
        dvb_segment(0x13, page, dvb_object(2, 0, 120, 20, lambda x, y: 4 if 20 <= x < 100 else 0, 4)),
        dvb_segment(0x80, page, b""),
    ])
    clear2 = dvb_segment(0x14, page, display) + dvb_segment(0x10, page, bytes([10, 0x34])) + dvb_segment(0x80, page, b"")
    return [(90_000, show), (270_000, clear), (360_000, again), (540_000, clear2)]


def write_dvb(path):
    pid = 0x102
    descriptor = bytes([0x59, 8]) + b"eng" + bytes([0x10]) + struct.pack(">HH", 1, 1)
    pat = section(0x00, struct.pack(">HBBB", 1, 0xC1, 0, 0) + struct.pack(">HH", 1, 0xE000 | 0x1000))
    pmt = section(0x02, struct.pack(">HBBB", 1, 0xC1, 0, 0) + struct.pack(">HH", 0xE000 | pid, 0xF000)
                  + bytes([0x06]) + struct.pack(">HH", 0xE000 | pid, 0xF000 | len(descriptor)) + descriptor)
    out = bytearray()
    counters = {0: 0, 0x1000: 0, pid: 0}

    def emit(target, data):
        packets, counters[target] = ts_packets(target, data, counters[target], True)
        for packet in packets:
            out.extend(packet)

    for pts, segments in dvb_display_sets():
        emit(0, b"\x00" + pat)
        emit(0x1000, b"\x00" + pmt)
        data = bytes([0x20, 0x00]) + segments + b"\xff"
        header = bytes([0x84, 0x80, 5]) + pes_timestamp(pts)
        emit(pid, bytes([0, 0, 1, 0xBD]) + struct.pack(">H", len(header) + len(data)) + header + data)
    with open(path, "wb") as file:
        file.write(out)


def main():
    command = sys.argv[1]
    if command == "sup":
        write_sup(sys.argv[2])
    elif command == "dvb":
        write_dvb(sys.argv[2])
    elif command == "vobsub":
        write_vobsub(sys.argv[2], sys.argv[3])
    elif command == "teletext":
        write_teletext(sys.argv[2])
    elif command == "captions":
        write_captions(sys.argv[2], sys.argv[3], "--hevc" in sys.argv, "--mpeg2" in sys.argv)
    else:
        raise SystemExit("unknown command " + command)


main()
