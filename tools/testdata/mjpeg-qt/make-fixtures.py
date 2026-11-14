#!/usr/bin/env python3
"""Regenerates the QuickTime Motion-JPEG fixtures of smalt-image's MjpegTablesBoxTest; see README.md.

Usage: python -I make-fixtures.py <QTSystem directory> <scratch directory> <output directory>
Windows only. It checks every measurement it records, and fails when QuickTime no longer behaves as the README says.
"""
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import zlib

WIDTH, HEIGHT = 48, 32
QUALITIES = (0, 256, 512, 768, 1023)
FRAME_STEP = 20
TIMESCALE = 600
BLANK = 0xA5


def box(kind, *parts):
    body = b''.join(parts)
    return struct.pack('>I4s', len(body) + 8, kind.encode('latin1')) + body


def atoms(data, at=0, end=None):
    end = len(data) if end is None else end
    while at + 8 <= end:
        size, kind = struct.unpack('>I4s', data[at:at + 8])
        if size < 8 or at + size > end:
            return
        yield kind.decode('latin1'), at + 8, at + size
        at += size


def find(data, path, at=0, end=None):
    for kind, start, stop in atoms(data, at, end):
        if kind == path[0]:
            return (start, stop) if len(path) == 1 else find(data, path[1:], start, stop)
    return None


def video_trak(data):
    start, stop = find(data, ['moov'])
    for kind, a, b in atoms(data, start, stop):
        if kind == 'trak' and find(data, ['mdia', 'minf', 'vmhd'], a, b):
            return a, b
    raise ValueError('no video track')


def sample_entry_of(data):
    a, b = find(data, ['mdia', 'minf', 'stbl', 'stsd'], *video_trak(data))
    return data[a + 8:b]


def extensions_of(entry):
    return [(kind, entry[a:b]) for kind, a, b in atoms(entry, 86)]


def samples_of(data):
    a, b = find(data, ['mdia', 'minf', 'stbl'], *video_trak(data))
    stsz = find(data, ['stsz'], a, b)[0]
    stco = find(data, ['stco'], a, b)[0]
    stsc = find(data, ['stsc'], a, b)[0]
    uniform, count = struct.unpack('>II', data[stsz + 4:stsz + 12])
    sizes = [uniform] * count if uniform else struct.unpack(f'>{count}I', data[stsz + 12:stsz + 12 + 4 * count])
    chunks = struct.unpack('>I', data[stco + 4:stco + 8])[0]
    offsets = struct.unpack(f'>{chunks}I', data[stco + 8:stco + 8 + 4 * chunks])
    runs = struct.unpack('>I', data[stsc + 4:stsc + 8])[0]
    table = [struct.unpack('>III', data[stsc + 8 + 12 * i:stsc + 20 + 12 * i]) for i in range(runs)]
    out = []
    for chunk in range(chunks):
        at = offsets[chunk]
        for _ in range([run[1] for run in table if run[0] <= chunk + 1][-1]):
            if len(out) == count:
                break
            out.append(data[at:at + sizes[len(out)]])
            at += sizes[len(out) - 1]
    return out


def sample_entry(fourcc, extensions=(), name=b'Motion JPEG B'):
    fixed = struct.pack('>6xHHH4sIIHHIIIH', 1, 1, 1, b'appl', 0, 512, WIDTH, HEIGHT, 0x480000, 0x480000, 0, 1)
    fixed += (bytes([len(name)]) + name).ljust(32, b'\0') + struct.pack('>Hh', 24, -1)
    return box(fourcc, fixed, *(box(kind, body) for kind, body in extensions))


def movie(entry, frames):
    ftyp = box('ftyp', b'qt  ', struct.pack('>I', 0x20050300), b'qt  ')
    mdat = box('mdat', *frames)
    count = len(frames)
    duration = count * FRAME_STEP
    matrix = struct.pack('>9I', 0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)
    mvhd = box('mvhd', struct.pack('>5I', 0, 0, 0, TIMESCALE, duration), struct.pack('>IH', 0x10000, 0x100), b'\0' * 10,
               matrix, b'\0' * 24, struct.pack('>I', 2))
    tkhd = box('tkhd', struct.pack('>5I', 0xF, 0, 0, 1, 0), struct.pack('>I', duration), b'\0' * 8, b'\0' * 8, matrix,
               struct.pack('>II', WIDTH << 16, HEIGHT << 16))
    mdhd = box('mdhd', struct.pack('>5IHH', 0, 0, 0, TIMESCALE, duration, 0, 0))
    hdlr = box('hdlr', struct.pack('>I4s4s', 0, b'mhlr', b'vide'), b'appl', b'\0' * 8, b'\x0cVideoHandler')
    data_handler = box('hdlr', struct.pack('>I4s4s', 0, b'dhlr', b'alis'), b'appl', b'\0' * 8, b'\x0bDataHandler')
    dinf = box('dinf', box('dref', struct.pack('>II', 0, 1), box('alis', struct.pack('>I', 1))))
    stbl = box('stbl', box('stsd', struct.pack('>II', 0, 1), entry),
               box('stts', struct.pack('>IIII', 0, 1, count, FRAME_STEP)),
               box('stsc', struct.pack('>5I', 0, 1, 1, count, 1)),
               box('stsz', struct.pack('>III', 0, 0, count), *(struct.pack('>I', len(f)) for f in frames)),
               box('stco', struct.pack('>III', 0, 1, len(ftyp) + 8)))
    minf = box('minf', box('vmhd', struct.pack('>I', 1), b'\0' * 8), data_handler, dinf, stbl)
    return ftyp + mdat + box('moov', mvhd, box('trak', tkhd, box('mdia', mdhd, hdlr, minf)))


class QuickTime:
    def __init__(self, directory, scratch):
        self.exe = os.path.join(directory, 'q.exe')
        self.scratch = scratch

    def path(self, name):
        return os.path.join(self.scratch, name)

    def run(self, *arguments):
        result = subprocess.run([self.exe, *map(str, arguments)], capture_output=True, text=True, timeout=300)
        return result.returncode, result.stdout

    def compress(self, kind, picture, quality):
        source, frame, description = self.path('in.argb'), self.path('out.frame'), self.path('out.idesc')
        with open(source, 'wb') as f:
            f.write(picture)
        code, out = self.run('c', kind, WIDTH, HEIGHT, quality, source, frame, description)
        if code:
            raise RuntimeError(f'compress {kind}: {out}')
        return read(frame), read(description)

    def decompress(self, frame, description, extensions=()):
        """{return (error code, 32-bit ARGB pixels or None)}"""
        frame_path, description_path, out = self.path('d.frame'), self.path('d.idesc'), self.path('d.argb')
        write(frame_path, frame)
        write(description_path, description)
        if os.path.exists(out):
            os.remove(out)
        arguments = ['d', frame_path, description_path, WIDTH, HEIGHT, 32, out, WIDTH * 4]
        for i, (kind, body) in enumerate(extensions):
            extension = self.path(f'd.x{i}')
            write(extension, body)
            arguments += [kind, extension]
        code, text = self.run(*arguments)
        status = int(text.strip().split()[-1])
        return status, read(out) if status == 0 else None

    def export(self, source, target, quality):
        code, text = self.run('e', source, target, 'mjpb', quality)
        if code:
            raise RuntimeError(f'export: {text}')

    def render(self, path, index):
        """{return the file's frame as the Movie Toolbox draws it, or None when it draws nothing}"""
        out = path + f'.{index}.argb'
        code, text = self.run('r', path, WIDTH, HEIGHT, out, index, FRAME_STEP)
        if code:
            raise RuntimeError(f'render: {text}')
        pixels = read(out)
        os.remove(out)
        return None if all(b == BLANK for b in pixels) else pixels


def read(path):
    with open(path, 'rb') as f:
        return f.read()


def write(path, data):
    with open(path, 'wb') as f:
        f.write(data)


def picture(index):
    """A noisy diagonal gradient; each frame shifts it, so that a wrong table or frame order shows."""
    out = bytearray(WIDTH * HEIGHT * 4)
    state = 12345 + index
    for y in range(HEIGHT):
        for x in range(WIDTH):
            state = (state * 1103515245 + 12345) & 0x7fffffff
            noise = (state >> 16) & 31
            at = (y * WIDTH + x) * 4
            out[at] = 255
            out[at + 1] = min(255, (x + 5 * index) * 255 // WIDTH // 2 + noise + 40)
            out[at + 2] = min(255, y * 255 // HEIGHT // 2 + noise + 40)
            out[at + 3] = min(255, ((x ^ y) * 3 + 60 + 20 * index) & 255)
    return bytes(out)


def field_starts(frame):
    starts = [0]
    following = struct.unpack('>I', frame[16:20])[0]
    if following:
        starts.append(following)
    return starts


def without_offset(frame, slot):
    out = bytearray(frame)
    for start in field_starts(frame):
        out[start + 20 + 4 * slot:start + 24 + 4 * slot] = bytes(4)
    return bytes(out)


def segment_of(frame, slot, field=0):
    start = field_starts(frame)[field]
    offset = struct.unpack('>I', frame[start + 20 + 4 * slot:start + 24 + 4 * slot])[0]
    return frame[start + offset:start + offset + struct.unpack('>H', frame[start + offset:start + offset + 2])[0]]


def segments_of_jpeg(jpeg):
    at = 2
    out = {}
    while True:
        marker = jpeg[at + 1]
        size = struct.unpack('>H', jpeg[at + 2:at + 4])[0]
        out.setdefault(marker, []).append(jpeg[at + 2:at + 2 + size])
        at += 2 + size
        if marker == 0xDA:
            return out, jpeg[at:jpeg.rindex(b'\xff\xd9')]


def merged(segments):
    body = b''.join(segment[2:] for segment in segments)
    return struct.pack('>H', len(body) + 2) + body


def optimized_field(argb, scratch, row):
    """A format B field from ffmpeg's JPEG of every other row, under Huffman tables far from Annex K."""
    rows = b''.join(argb[y * WIDTH * 4:(y + 1) * WIDTH * 4] for y in range(row, HEIGHT, 2))
    source, target = os.path.join(scratch, 'ff.argb'), os.path.join(scratch, 'ff.jpg')
    write(source, rows)
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'argb',
                    '-s', f'{WIDTH}x{HEIGHT // 2}', '-i', source, '-frames:v', '1', '-c:v', 'mjpeg',
                    '-pix_fmt', 'yuvj422p', '-huffman', 'optimal', '-q:v', '4', '-update', '1', target], check=True)
    return segments_of_jpeg(read(target))


def format_b(field, dht, last):
    segments, data = field
    dqt = merged(segments[0xDB])
    sof, sos = segments[0xC0][0], segments[0xDA][0]
    data = data.replace(b'\xff\x00', b'\xff')
    q, h = 48, 48 + len(dqt)
    f = h + len(dht)
    s = f + len(sof)
    d = s + len(sos)
    size = d + len(data)
    padded = (size + 15) // 16 * 16
    header = struct.pack('>10I', 0, 0x6D6A7067, size, padded, 0 if last else padded, q, h, f, s, d) + bytes(8)
    return (header + dqt + dht + sof + sos + data).ljust(padded, b'\0')


def format_a_without(frame, drop):
    """A QuickTime format A frame with the segments in drop removed from each field and its APP1 offsets rewritten."""
    out = b''
    start = 0
    while True:
        at = start + 2
        kept = []
        while True:
            marker = frame[at + 1]
            size = struct.unpack('>H', frame[at + 2:at + 4])[0]
            kept.append((marker, frame[at + 2:at + 2 + size]))
            at += 2 + size
            if marker == 0xDA:
                break
        app1 = dict(kept)[0xE1]
        fields = struct.unpack('>10I', app1[2:42])
        next_field = fields[4]
        data = frame[at:start + fields[2]]
        position = 2
        offsets = {}
        layout = [(m, s) for m, s in kept if m not in drop]
        for marker, segment in layout:
            offsets[marker] = position + 2
            position += 2 + len(segment)
        size = position + len(data)
        padded = (size + 15) // 16 * 16
        rebuilt = b'\xff\xd8'
        for marker, segment in layout:
            if marker == 0xE1:
                segment = segment[:2] + struct.pack('>10I', 0, 0x6D6A7067, size, padded, padded if next_field else 0,
                                                    offsets.get(0xDB, 0), offsets.get(0xC4, 0), offsets[0xC0],
                                                    offsets[0xDA], position) + segment[42:]
            rebuilt += b'\xff' + bytes([marker]) + segment
        out += (rebuilt + data).ljust(padded, b'\0')
        if not next_field:
            return out
        start += next_field


def without_offset_of_field(frame, field, slot):
    out = bytearray(frame)
    start = field_starts(frame)[field]
    out[start + 20 + 4 * slot:start + 24 + 4 * slot] = bytes(4)
    return bytes(out)


def without_gamma(description):
    """The description with its gama extension removed, so that QuickTime draws the coded samples unconverted."""
    extensions = description[86:]
    kept = b''
    at = 0
    while at + 8 <= len(extensions):
        size = struct.unpack('>I', extensions[at:at + 4])[0]
        if size < 8:
            break
        if extensions[at + 4:at + 8] != b'gama':
            kept += extensions[at:at + size]
        at += size
    return struct.pack('<I', 86 + len(kept)) + description[4:86] + kept


def main():
    qt_dir, scratch, out_dir = sys.argv[1:4]
    os.makedirs(scratch, exist_ok=True)
    os.makedirs(out_dir, exist_ok=True)
    qt = QuickTime(qt_dir, scratch)
    pictures = [picture(i) for i in range(2)]
    expected = []

    # The source movie: QuickTime's own format A frames at maximum quality.
    frames = [qt.compress('mjpa', p, 1023)[0] for p in pictures]
    source = os.path.join(scratch, 'source.mov')
    write(source, movie(sample_entry('mjpa', [('fiel', b'\x02\x01')], b'Motion JPEG A'), frames))

    # QuickTime's exports, the drawn samples of each frame, and the box form of each.
    _, description = qt.compress('mjpb', pictures[0], 512)
    plain = without_gamma(description)
    exports = {}
    for quality in QUALITIES:
        path = os.path.join(out_dir, f'export-{quality}.mov')
        if os.path.exists(path):
            os.remove(path)
        qt.export(source, path, quality)
        data = read(path)
        entry = sample_entry_of(data)
        kinds = [kind for kind, _ in extensions_of(entry)]
        if entry[4:8] != b'mjpb' or 'mjqt' in kinds or 'mjht' in kinds:
            raise RuntimeError(f'QuickTime now writes {entry[4:8]} {kinds}')
        samples = samples_of(data)
        exports[quality] = samples, extensions_of(entry)
        drawn = b''
        for frame in samples:
            status, pixels = qt.decompress(frame, plain)
            if status:
                raise RuntimeError(f'decompress {status}')
            drawn += pixels
        write(os.path.join(out_dir, f'export-{quality}.argb.z'), zlib.compress(drawn, 9))

        moved = [without_offset(frame, 0) for frame in samples]
        boxed = os.path.join(out_dir, f'tables-{quality}.mov')
        write(boxed, movie(sample_entry('mjpb', extensions_of(entry) + [('mjqt', segment_of(samples[0], 0))]), moved))
        for i, frame in enumerate(samples):
            if qt.render(boxed, i) != qt.render(path, i):
                raise RuntimeError('QuickTime draws the box differently from the inline tables')
        expected.append((f'tables-{quality}.mov', f'export-{quality}.mov', 'equal'))

    # Custom Huffman tables, only decodable through the mjht box. Both fields share their tables, as a box must.
    top = optimized_field(pictures[0], scratch, 0)
    dht = merged(top[0][0xC4])
    custom = format_b(top, dht, False) + format_b(top, dht, True)
    write(os.path.join(out_dir, 'custom.mov'), movie(sample_entry('mjpb', exports[512][1]), [custom]))
    status, pixels = qt.decompress(custom, plain)
    if status:
        raise RuntimeError(f'custom {status}')
    write(os.path.join(out_dir, 'custom.argb.z'), zlib.compress(pixels, 9))
    dqt = merged(top[0][0xDB])
    stripped = without_offset(custom, 1)
    fully = without_offset(stripped, 0)
    annex_k = segment_of(exports[512][0][0], 1)
    other_q = segment_of(exports[0][0][0], 0)

    # The boxes in every shape measured: (name, frame, boxes). The verdict is read from QuickTime's own drawing.
    t = segment_of(exports[512][0][0], 0)
    sample = exports[512][0][0]
    no_q = without_offset(sample, 0)
    t0, t1 = t[2:67], t[67:132]
    sized = lambda *tables: struct.pack('>H', 2 + sum(map(len, tables))) + b''.join(tables)
    wide = lambda table: bytes([table[0] | 0x10]) + b''.join(bytes([0, v]) for v in table[1:])
    q_shapes = [
        ('q-no-box', no_q, []),
        ('q-padded', no_q, [('mjqt', t + bytes(4))]),
        ('q-reversed', no_q, [('mjqt', sized(t1, t0))]),
        ('q-wide', no_q, [('mjqt', sized(wide(t0), wide(t1)))]),
        ('q-payload', no_q, [('mjqt', t[2:])]),
        ('q-marker', no_q, [('mjqt', b'\xff\xdb' + t)]),
        ('q-bare-tables', no_q, [('mjqt', t0[1:] + t1[1:])]),
        ('q-length-zero', no_q, [('mjqt', bytes(2) + t[2:])]),
        ('q-length-past', no_q, [('mjqt', struct.pack('>H', 400) + t[2:])]),
        ('q-length-inside', no_q, [('mjqt', struct.pack('>H', 100) + t[2:])]),
        ('q-empty', no_q, [('mjqt', b'')]),
        ('q-other-quality', no_q, [('mjqt', other_q)]),
        ('q-first-box-wins', no_q, [('mjqt', t), ('mjqt', other_q)]),
        ('q-first-box-loses', no_q, [('mjqt', other_q), ('mjqt', t)]),
        ('q-inline-wins', sample, [('mjqt', other_q)]),
        ('q-first-field-inline', without_offset_of_field(sample, 1, 0), [('mjqt', t)]),
        ('q-second-field-inline', without_offset_of_field(sample, 0, 0), [('mjqt', t)]),
        ('q-inline-with-bad-box', sample, [('mjqt', bytes(2))]),
    ]
    reference = qt.render(os.path.join(out_dir, 'export-512.mov'), 0)
    base = exports[512][1]
    for name, frame, boxes in q_shapes:
        path = os.path.join(out_dir, name + '.mov')
        write(path, movie(sample_entry('mjpb', base + boxes), [frame]))
        drawn = qt.render(path, 0)
        verdict = 'blank' if drawn is None else 'equal' if drawn == reference else 'different'
        expected.append((name + '.mov', 'export-512.mov', verdict))

    custom_path = os.path.join(out_dir, 'custom.mov')
    custom_reference = qt.render(custom_path, 0)
    parts = []
    at = 2
    while at < len(dht):
        size = 17 + sum(dht[at + 1:at + 17])
        parts.append(dht[at:at + size])
        at += size
    h_shapes = [
        ('h-no-box', stripped, []),
        ('h-box', stripped, [('mjht', dht)]),
        ('h-padded', stripped, [('mjht', dht + bytes(7))]),
        ('h-reversed', stripped, [('mjht', sized(*reversed(parts)))]),
        ('h-payload', stripped, [('mjht', dht[2:])]),
        ('h-marker', stripped, [('mjht', b'\xff\xc4' + dht)]),
        ('h-one-per-table', stripped, [('mjht', b''.join(sized(p) for p in parts))]),
        ('h-length-past', stripped, [('mjht', struct.pack('>H', 4000) + dht[2:])]),
        ('h-annex-k', stripped, [('mjht', annex_k)]),
        ('h-first-box-wins', stripped, [('mjht', dht), ('mjht', annex_k)]),
        ('h-inline-wins', custom, [('mjht', annex_k)]),
        ('both-boxes', fully, [('mjqt', dqt), ('mjht', dht)]),
    ]
    for name, frame, boxes in h_shapes:
        path = os.path.join(out_dir, name + '.mov')
        write(path, movie(sample_entry('mjpb', base + boxes), [frame]))
        drawn = qt.render(path, 0)
        verdict = 'blank' if drawn is None else 'equal' if drawn == custom_reference else 'different'
        expected.append((name + '.mov', 'custom.mov', verdict))

    # Format A frames without their tables, which QuickTime then reads from the boxes too.
    inline_a = qt.compress('mjpa', pictures[0], 512)[0]
    a_base = [('fiel', b'\x02\x01')]
    a_name = b'Motion JPEG A'
    rebuilt = format_a_without(inline_a, ())
    write(os.path.join(out_dir, 'format-a.mov'), movie(sample_entry('mjpa', a_base, a_name), [rebuilt]))
    a_reference = qt.render(os.path.join(out_dir, 'format-a.mov'), 0)
    if qt.decompress(rebuilt, qt.compress('mjpa', pictures[0], 512)[1]) [0]:
        raise RuntimeError('format A rebuild')
    a_q = find_segment(inline_a, 0xDB)
    a_h = find_segment(inline_a, 0xC4)
    a_shapes = [
        ('a-q-box', format_a_without(inline_a, (0xDB,)), [('mjqt', a_q)]),
        ('a-h-box', format_a_without(inline_a, (0xC4,)), [('mjht', a_h)]),
        ('a-both-boxes', format_a_without(inline_a, (0xDB, 0xC4)), [('mjqt', a_q), ('mjht', a_h)]),
        ('a-inline-wins', rebuilt, [('mjqt', other_q)]),
    ]
    for name, frame, boxes in a_shapes:
        path = os.path.join(out_dir, name + '.mov')
        write(path, movie(sample_entry('mjpa', a_base + boxes, a_name), [frame]))
        drawn = qt.render(path, 0)
        verdict = 'blank' if drawn is None else 'equal' if drawn == a_reference else 'different'
        expected.append((name + '.mov', 'format-a.mov', verdict))

    with open(os.path.join(out_dir, 'expected.tsv'), 'w', newline='\n') as f:
        f.write('# file\treference\twhat QuickTime 7.7.9 draws compared with the reference\n')
        for row in expected:
            f.write('\t'.join(row) + '\n')
    with open(os.path.join(out_dir, 'SHA256SUMS'), 'w', newline='\n') as f:
        for name in sorted(os.listdir(out_dir)):
            if name != 'SHA256SUMS':
                f.write(f'{hashlib.sha256(read(os.path.join(out_dir, name))).hexdigest()}  {name}\n')
    shutil.rmtree(scratch)
    for row in expected:
        print(*row)


def find_segment(jpeg, marker):
    at = 2
    while True:
        current = jpeg[at + 1]
        size = struct.unpack('>H', jpeg[at + 2:at + 4])[0]
        if current == marker:
            return jpeg[at + 2:at + 2 + size]
        at += 2 + size


if __name__ == '__main__':
    main()
