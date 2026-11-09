"""Regenerates the MACE decoder fixtures and I/O behavior samples with QuickTime as the reference.

The streams are crafted here or written by QuickTime's own MACE encoder; every expected sample comes out of QuickTime.
A model of the codec, the same arithmetic as MaceDecoder, steers the crafted streams to every table entry and checks
each fixture on the way. See README.md for the oracle's provenance and how to set it up.

    python -I tools/testdata/mace/make-fixtures.py [repository root]
"""
import hashlib
import math
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from qt import run

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "..")
OUT = os.path.join(ROOT, "audio", "src", "test", "resources", "com", "github", "auties00", "smalt", "audio", "mace")
SAMPLES = os.path.join(ROOT, "core", "src", "test", "resources", "samples")
with open(os.path.join(HERE, "tables.txt")) as tables:
    STEPS = [list(map(int, line.split())) for line in tables]
ADAPT_3 = [-13, 8, 76, 222, 222, 76, 8, -13]
ADAPT_2 = [-18, 140, 140, -18]
UNIT = {"MAC3": 2, "MAC6": 1}


class Channel:
    """One channel of the model; `used` collects the table entries it read."""

    def __init__(self, used=None):
        self.state = 0
        self.a = self.b = self.c = 0
        self.factor = 0
        self.used = used

    def step(self, wide, code):
        row = self.state >> 4 & 127
        if self.used is not None:
            self.used.add((wide, row, code))
        self.state = max(0, self.state + (ADAPT_3 if wide else ADAPT_2)[code] - (self.state >> 5))
        return STEPS[row][code] if wide else STEPS[row][8 + code]

    def three(self, wide, code):
        step = self.step(wide, code)
        self.a = max(-32767, min(32767, self.a - (self.a >> 3) + step))
        return [self.a >> 8]

    def six(self, wide, code):
        step = self.step(wide, code)
        sample = max(-32767, min(32767, (self.factor * self.a >> 15) + step))
        self.factor = max(-32767, min(32767, self.factor + (506 if (self.a < 0) == (step < 0) else -314)))
        self.c, self.b, self.a = self.b, self.a, sample
        a, b, c = self.a, self.b, self.c
        return [((a >> 3) + (b >> 1) + (3 * c >> 3)) >> 8, ((3 * a >> 3) + (b >> 1) + (c >> 3)) >> 8]


def decode(fmt, data, channels=1, trial=0):
    """The model's unsigned 8-bit interleaved output; `trial` bytes per fresh decoder, 0 for one stream."""
    output = bytearray()
    unit = UNIT[fmt] * channels
    trial = trial or len(data)
    for start in range(0, len(data), trial):
        states = [Channel() for _ in range(channels)]
        chunk = data[start:start + trial]
        for frame in range(0, len(chunk), unit):
            parts = []
            for c in range(channels):
                samples = []
                for value in chunk[frame + c * UNIT[fmt]:frame + (c + 1) * UNIT[fmt]]:
                    if fmt == "MAC3":
                        samples += states[c].three(True, value & 7)
                        samples += states[c].three(False, value >> 3 & 3)
                        samples += states[c].three(True, value >> 5)
                    else:
                        samples += states[c].six(True, value >> 5)
                        samples += states[c].six(False, value >> 3 & 3)
                        samples += states[c].six(True, value & 7)
                parts.append(samples)
            for k in range(6):
                for c in range(channels):
                    output.append(parts[c][k] + 128)
    return bytes(output)


def field_of(fmt, position):
    """The wide flag and bit shift of a code position within a byte."""
    slot = position % 3
    if fmt == "MAC3":
        return (slot != 1), (0 if slot == 0 else 3 if slot == 1 else 5)
    return (slot != 1), (5 if slot == 0 else 3 if slot == 1 else 0)


def coverage(fmt, seed):
    """Trials of 256 bytes that together read every table entry, each walk steering to rows not yet covered."""
    rng = random.Random(seed)
    used = set()
    trial = bytearray()
    size = 256
    per = 3 * size
    wanted = {(True, r, c) for r in range(128) for c in range(8)} | {(False, r, c) for r in range(128) for c in range(4)}
    while not wanted <= used:
        channel = Channel(used)
        codes = []
        missing = sorted(wanted - used, key=lambda e: e[1])
        target = rng.choice(missing)[1]
        for position in range(per):
            wide, _ = field_of(fmt, position)
            row = channel.state >> 4 & 127
            unseen = [c for c in range(8 if wide else 4) if (wide, row, c) not in used]
            if unseen:
                code = rng.choice(unseen)
            else:
                # the code moving the state nearest the target row, either sign
                count = 8 if wide else 4
                table = ADAPT_3 if wide else ADAPT_2
                goal = (target << 4) + 8
                code = min(range(count), key=lambda c: abs(channel.state + table[c] - (channel.state >> 5) - goal)
                           + rng.random() * 40)
                missing = [e for e in wanted - used if e[1] == target]
                if not missing and wanted - used:
                    target = rng.choice(sorted(wanted - used, key=lambda e: e[1]))[1]
            (channel.three if fmt == "MAC3" else channel.six)(wide, code)
            codes.append(code)
        bytes_ = bytearray(size)
        for position, code in enumerate(codes):
            _, shift = field_of(fmt, position)
            bytes_[position // 3] |= code << shift
        trial += bytes_
    return bytes(trial), size


def boundaries(seed):
    """3:1 trials of 128 bytes in which every table entry lands the sample on an 8-bit boundary from both sides, so
    that an entry off by one changes an output sample."""
    rng = random.Random(seed)
    wanted = {(True, r, c, side) for r in range(128) for c in range(8) for side in (0, 255)}
    wanted |= {(False, r, c, side) for r in range(128) for c in range(4) for side in (0, 255)}
    size = 128
    data = bytearray()
    stalled = 0
    while wanted and stalled < 3000:
        channel = Channel()
        bytes_ = bytearray(size)
        before = len(wanted)
        target = rng.choice(sorted(wanted))[1]
        for position in range(size * 3):
            wide, shift = field_of("MAC3", position)
            row = channel.state >> 4 & 127
            count = 8 if wide else 4
            predicted = channel.a - (channel.a >> 3)
            hits = []
            for c in range(count):
                value = predicted + (STEPS[row][c] if wide else STEPS[row][8 + c])
                if -32767 <= value <= 32767 and (wide, row, c, value & 255) in wanted:
                    hits.append((c, value & 255))
            if hits:
                code, side = rng.choice(hits)
                wanted.discard((wide, row, code, side))
            else:
                # keep moving through the rows still wanted, with the state away from the rails
                table = ADAPT_3 if wide else ADAPT_2
                goal = (target << 4) + 8
                halves = range(count // 2)
                cls = min(halves, key=lambda c: abs(channel.state + table[c] - (channel.state >> 5) - goal)
                          + rng.random() * (120 if target > 8 else 12))
                code = count - 1 - cls if channel.a > 0 else cls
                if target <= 8 and rng.random() < .3:
                    code = rng.choice((cls, count - 1 - cls))
            channel.three(wide, code)
            bytes_[position // 3] |= code << shift
        if len(wanted) == before:
            stalled += 1
        else:
            data += bytes_
            stalled = 0
    return bytes(data), size, wanted


def stress(fmt, seed, trials, size):
    """Random trials: skewed code distributions, sign runs that saturate the state, opposing signs that drive the
    6:1 coefficient to its floor."""
    rng = random.Random(seed)
    data = bytearray()
    for _ in range(trials):
        w3 = [rng.random() ** rng.choice((1, 3, 8)) for _ in range(4)]
        w2 = [rng.random() ** rng.choice((1, 3, 8)) for _ in range(2)]
        mean = rng.choice((1, 2, 4, 8, 20, 60))
        oppose = rng.random() < .3
        sign = rng.randrange(2)
        left = 1 + int(rng.expovariate(1 / mean))
        channel = Channel()
        bytes_ = bytearray(size)
        for position in range(size * 3):
            wide, shift = field_of(fmt, position)
            count = 8 if wide else 4
            cls = rng.choices(range(count // 2), w3 if wide else w2)[0]
            left -= 1
            if left <= 0:
                sign ^= 1
                left = 1 + int(rng.expovariate(1 / mean))
            negative = sign == 1
            if oppose and rng.random() < .95:
                negative = channel.a >= 0
            code = count - 1 - cls if negative else cls
            (channel.three if fmt == "MAC3" else channel.six)(wide, code)
            bytes_[position // 3] |= code << shift
        data += bytes_
    return bytes(data), size


def music(rate, seconds, channels, seed):
    """Unsigned 8-bit audio with moving partials, level changes and noise, for QuickTime's encoder."""
    rng = random.Random(seed)
    out = bytearray()
    for k in range(int(rate * seconds)):
        t = k / rate
        envelope = 0.55 + 0.45 * math.sin(2 * math.pi * 2.5 * t)
        left = 80 * envelope * (.6 * math.sin(2 * math.pi * (220 + 700 * t) * t) + .3 * math.sin(2 * math.pi * 1760 * t))
        right = 70 * (1 - envelope) * math.sin(2 * math.pi * 440 * t) + 30 * math.sin(2 * math.pi * 3000 * t)
        for value in ((left, right) if channels == 2 else (left,)):
            out.append(max(0, min(255, int(128.5 + value + rng.gauss(0, 2)))))
    return bytes(out)


def write_fixture(name, fmt, channels, data, trial, table, digest_only=False):
    expected = run(data, fmt=fmt, ch=channels, mode="B" if trial else "S", trial=trial // (UNIT[fmt] * channels))
    assert expected == decode(fmt, data, channels, trial), name + ": the model differs from QuickTime"
    with open(os.path.join(OUT, name + ".mace"), "wb") as file:
        file.write(data)
    if digest_only:
        with open(os.path.join(OUT, name + ".pcm.sha256"), "w", newline="\n") as file:
            file.write(hashlib.sha256(expected).hexdigest() + "\n")
    else:
        with open(os.path.join(OUT, name + ".pcm"), "wb") as file:
            file.write(expected)
    table.append(f"{name}\t{fmt}\t{channels}\t{trial}")
    print(name, len(data), len(expected))


def ieee_extended(value):
    exponent = 16383 + 63 - 0
    mantissa = int(value)
    while mantissa < (1 << 63):
        mantissa <<= 1
        exponent -= 1
    return struct.pack(">HQ", exponent, mantissa)


def aifc(fmt, channels, rate, data):
    unit = UNIT[fmt] * channels
    name = {"MAC3": b"MACE 3-to-1", "MAC6": b"MACE 6-to-1"}[fmt]
    text = bytes([len(name)]) + name
    if len(text) % 2:
        text += b"\0"
    comm = struct.pack(">hIh", channels, len(data) // unit, 8) + ieee_extended(rate) + fmt.encode() + text
    version = struct.pack(">I", 0xA2805140)
    ssnd = struct.pack(">II", 0, 0) + data
    body = b"AIFC" + b"FVER" + struct.pack(">I", 4) + version
    body += b"COMM" + struct.pack(">I", len(comm)) + comm
    body += b"SSND" + struct.pack(">I", len(ssnd)) + ssnd + (b"\0" if len(ssnd) % 2 else b"")
    return b"FORM" + struct.pack(">I", len(body)) + body


def box(kind, *parts):
    payload = b"".join(parts)
    return struct.pack(">I", len(payload) + 8) + kind + payload


def mov(fmt, channels, rate, data):
    """A QuickTime movie of one sound track with a version 1 description, chunks of 256 frames."""
    unit = UNIT[fmt] * channels
    frames = len(data) // unit
    chunk_frames = 256
    chunks = [data[k:k + chunk_frames * unit] for k in range(0, len(data), chunk_frames * unit)]
    ftyp = box(b"ftyp", b"qt  ", struct.pack(">I", 0x20050300), b"qt  ")
    mdat = box(b"mdat", data)
    base = len(ftyp) + 8
    offsets = [base + k * chunk_frames * unit for k in range(len(chunks))]
    samples = frames * 6
    matrix = struct.pack(">9I", 0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)
    mvhd = box(b"mvhd", struct.pack(">IIIIIIH", 0, 0, 0, rate, samples, 0x10000, 0x100), b"\0" * 10, matrix, b"\0" * 24,
               struct.pack(">I", 2))
    tkhd = box(b"tkhd", struct.pack(">IIIIIII", 15, 0, 0, 1, 0, samples, 0), b"\0" * 8,
               struct.pack(">HHHH", 0, 0, 0x100, 0), matrix, struct.pack(">II", 0, 0))
    mdhd = box(b"mdhd", struct.pack(">IIIIIHH", 0, 0, 0, rate, samples, 0, 0))
    hdlr = box(b"hdlr", struct.pack(">I", 0), b"mhlrsoun", b"\0" * 12, b"\x00")
    smhd = box(b"smhd", b"\0" * 8)
    dhlr = box(b"hdlr", struct.pack(">I", 0), b"dhlralis", b"\0" * 12, b"\x00")
    dref = box(b"dref", struct.pack(">II", 0, 1), box(b"alis", struct.pack(">I", 1)))
    dinf = box(b"dinf", dref)
    entry = fmt.encode() + b"\0" * 6 + struct.pack(">H", 1)
    entry += struct.pack(">HHI", 1, 0, 0)
    entry += struct.pack(">HHhHI", channels, 16, -3, 0, rate << 16)
    # version 1: samples per packet, bytes per packet, bytes per frame, bytes per sample
    entry += struct.pack(">IIII", 6, UNIT[fmt], unit, 2)
    stsd = box(b"stsd", struct.pack(">II", 0, 1), struct.pack(">I", len(entry) + 4) + entry)
    stts = box(b"stts", struct.pack(">IIII", 0, 1, samples, 1))
    # the last chunk may be shorter
    runs = []
    for k, chunk in enumerate(chunks):
        count = len(chunk) // unit * 6
        if not runs or runs[-1][1] != count:
            runs.append((k + 1, count))
    stsc = box(b"stsc", struct.pack(">II", 0, len(runs)), b"".join(struct.pack(">III", first, count, 1) for first, count in runs))
    stsz = box(b"stsz", struct.pack(">III", 0, 1, samples))
    stco = box(b"stco", struct.pack(">II", 0, len(offsets)), b"".join(struct.pack(">I", o) for o in offsets))
    stbl = box(b"stbl", stsd, stts, stsc, stsz, stco)
    minf = box(b"minf", smhd, dhlr, dinf, stbl)
    mdia = box(b"mdia", mdhd, hdlr, minf)
    trak = box(b"trak", tkhd, mdia)
    moov = box(b"moov", mvhd, trak)
    return ftyp + mdat + moov


def main():
    os.makedirs(OUT, exist_ok=True)
    table = ["# name\tcodec\tchannels\tbytes per trial (a fresh decoder), 0 for one stream"]
    for fmt, label in (("MAC3", "mac3"), ("MAC6", "mac6")):
        data, trial = coverage(fmt, 11)
        write_fixture("coverage-" + label, fmt, 1, data, trial, table)
        data, trial = stress(fmt, 12, 96, 128)
        write_fixture("stress-" + label, fmt, 1, data, trial, table)
        source = music(22050, 1.0, 1, 13)
        write_fixture("encoded-" + label, fmt, 1, run(source, fmt="raw ", outfmt=fmt), 0, table)
        source = music(22050, 0.5, 2, 14)
        write_fixture("encoded-stereo-" + label, fmt, 2, run(source, fmt="raw ", ch=2, outfmt=fmt), 0, table)
        rng = random.Random(15)
        data = bytes(rng.randrange(256) for _ in range(UNIT[fmt] * 2 * 600))
        write_fixture("random-stereo-" + label, fmt, 2, data, 0, table)
        data = bytes(rng.randrange(256) for _ in range(UNIT[fmt] * 300))
        with open(os.path.join(OUT, "pcm16-" + label + ".mace"), "wb") as file:
            file.write(data)
        with open(os.path.join(OUT, "pcm16-" + label + ".pcm16"), "wb") as file:
            file.write(run(data, fmt=fmt, outfmt="twos", outbits=16))
    data, trial, unreachable = boundaries(31)
    print("entries without a boundary probe", sorted(unreachable))
    write_fixture("boundaries-mac3", "MAC3", 1, data, trial, table, digest_only=True)
    with open(os.path.join(OUT, "fixtures.tsv"), "w", newline="\n") as file:
        file.write("\n".join(table) + "\n")

    for fmt, label, channels, rate, seconds in (("MAC3", "mace3", 2, 11025, .6), ("MAC6", "mace6", 1, 11025, .6)):
        encoded = run(music(rate, seconds, channels, 21), fmt="raw ", ch=channels, outfmt=fmt)
        for path, payload in ((f"aiff/{label}.aifc", aifc(fmt, channels, rate, encoded)),
                              (f"mp4/{label}.mov", mov(fmt, channels, rate, encoded))):
            target = os.path.join(SAMPLES, path)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "wb") as file:
                file.write(payload)
            print(path, len(payload))
        with open(os.path.join(OUT, f"sample-{label}.pcm"), "wb") as file:
            file.write(run(encoded, fmt=fmt, ch=channels))


if __name__ == "__main__":
    main()
