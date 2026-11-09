#!/usr/bin/env python3
"""Writes the oracle files of the raw module's LensCorrectionOracleTest and PixelShiftOracleTest.

It downloads Adobe DNG Converter 18.0 for Windows and innoextract 1.9 (both pinned by SHA-256, as raw-oracle.py
does), extracts the converter without installing it and runs it as a black box, writing into
<conformance cache>/raw-lens-oracle (-Dsmalt.test.rawLensOracle overrides it in the tests):

- for each sample of LENS_SAMPLES, a <stem>.lens text file: after a comment line, "active" with the active area
  of the converter's DNG file (top, left, bottom and right), "crop" with its default crop (x, y, width and height
  relative to the active area) and "list2"/"list3" with the hexadecimal bytes of its opcode lists 2 and 3 when it
  writes them (the converter turns the lens corrections of the vendor's maker note into FixVignetteRadial and
  WarpRectilinear opcodes there); for the samples of RENDER_SAMPLES it also keeps the DNG file, <stem>.dng;
- for each sample of SHIFT_SAMPLES (pixel-shift Pentax PEF and DNG files and a Sony ARQ file), a <stem>.merged
  text file summarizing the uncompressed linear DNG file (-u) the converter merges the frames into: "size"
  (width, height and samples per pixel) and, for each sample, "digest <sample> <SHA-256>" of its values over the
  whole image as little-endian 16-bit words, row by row, and "row <sample> <hex>" of its first row.

Windows only, with a Windows build of Python. The samples are fetched into the conformance cache when it lacks them.

    python tools/testdata/raw-lens-oracle.py [sample ...]
"""
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from array import array

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

CONVERTER_URL = 'https://download.adobe.com/pub/adobe/dng/win/AdobeDNGConverter_x64_18_0.exe'
CONVERTER_SHA256 = 'd551ec18cd81a8cb60dd2a1a0cb28b0a5c29230ff09b251670ff13d24bb71164'
INNOEXTRACT_URL = 'https://github.com/dscharrer/innoextract/releases/download/1.9/innoextract-1.9-windows.zip'
INNOEXTRACT_SHA256 = '6989342c9b026a00a72a38f23b62a8e6a22cc5de69805cf47d68ac2fec993065'

LENS_SAMPLES = [
    'rawpixls-sony-dsc-rx100m3-12bit-12bit-compressed-3-2.arw',
    'rawpixls-sony-dsc-rx10-12bit-12bit-compressed-3-2.arw',
    'rawpixls-sony-dsc-rx0-12bit-12bit-compressed-3-2.arw',
    'rawpixls-sony-zv-1-12bit-12bit-compressed-3-2.arw',
    'rawpixls-sony-ilce-7m3-12bit-12bit-compressed-3-2.arw',
    'rawpixls-panasonic-dc-g9-4-3-2348.rw2',
    'rawpixls-panasonic-dmc-gx80-4-3.rw2',
    'rawpixls-panasonic-dc-tz200-4-3.rw2',
    'rawpixls-panasonic-dmc-lx100-4-3.rw2',
    'rawpixls-panasonic-dmc-fz1000-1-1.rw2',
    'rawpixls-olympus-e-m1-16bit-4-3.orf',
    'rawpixls-olympus-e-m10-mark-iii-16bit-4-3.orf',
    'rawpixls-olympus-e-pl5-16bit-4-3.orf',
    'rawpixls-olympus-stylus1-16bit-4-3.orf',
    'rawpixls-fujifilm-x-t4-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-x-a1-12bit-12bit-uncompressed-3-2.raf',
    'rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf',
]

RENDER_SAMPLES = [
    'rawpixls-sony-dsc-rx100m3-12bit-12bit-compressed-3-2.arw',
    'rawpixls-olympus-e-m1-16bit-4-3.orf',
]

SHIFT_SAMPLES = [
    'rawpixls-pentax-k-1-14bit-3-2-pixel-shift.pef',
    'rawpixls-pentax-k-3-mark-iii-14bit-3-2-pixel-shift.pef',
    'rawpixls-pentax-k-70-14bit-3-2-pixel-shift.dng',
    'rawpixls-sony-ilce-7rm3-14bit-14bit-uncompressed-3-2.arq',
]

SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4, 16: 8}


def windows(path):
    return os.path.abspath(path).replace('/', '\\')


def raw_directory(data):
    """Returns the tags (raw values and the position of each value) of the main image of a DNG file."""
    order = '<' if data[:2] == b'II' else '>'
    queue = [struct.unpack(order + 'I', data[4:8])[0]]
    seen = set()
    while queue:
        offset = queue.pop(0)
        if offset in seen or offset <= 0 or offset >= len(data):
            continue
        seen.add(offset)
        count = struct.unpack(order + 'H', data[offset:offset + 2])[0]
        tags = {}
        for index in range(count):
            entry = offset + 2 + 12 * index
            tag, kind, number = struct.unpack(order + 'HHI', data[entry:entry + 8])
            size = SIZES.get(kind, 1) * number
            at = entry + 8 if size <= 4 else struct.unpack(order + 'I', data[entry + 8:entry + 12])[0]
            tags[tag] = (kind, number, at)
        following = struct.unpack(order + 'I', data[offset + 2 + 12 * count:offset + 6 + 12 * count])[0]
        if 330 in tags:
            kind, number, at = tags[330]
            queue.extend(struct.unpack(order + 'I' * number, data[at:at + 4 * number]))
        if following:
            queue.append(following)
        if 254 in tags and values(data, order, tags[254])[0] == 0 and values(data, order, tags[262])[0] in (32803, 34892):
            return order, tags
    sys.exit('no main image')


def values(data, order, tag):
    kind, number, at = tag
    if kind in (5, 10):
        pairs = struct.unpack(order + ('II' if kind == 5 else 'ii') * number, data[at:at + 8 * number])
        return [pairs[2 * index] / pairs[2 * index + 1] for index in range(number)]
    form = {1: 'B', 3: 'H', 4: 'I', 8: 'h', 9: 'i', 11: 'f', 12: 'd'}[kind]
    return list(struct.unpack(order + form * number, data[at:at + SIZES[kind] * number]))


def write_lens(path, target):
    with open(path, 'rb') as source:
        data = source.read()
    order, tags = raw_directory(data)
    width = values(data, order, tags[256])[0]
    height = values(data, order, tags[257])[0]
    active = values(data, order, tags[50829]) if 50829 in tags else [0, 0, height, width]
    origin = values(data, order, tags[50719]) if 50719 in tags else [0, 0]
    size = values(data, order, tags[50720]) if 50720 in tags else [active[3] - active[1], active[2] - active[0]]
    lines = [f'# the lens opcodes of {os.path.basename(path)}, written by tools/testdata/raw-lens-oracle.py',
             'active ' + ' '.join(str(int(value)) for value in active),
             'crop ' + ' '.join(repr(float(value)) for value in [*origin, *size])]
    for tag, name in ((51009, 'list2'), (51022, 'list3')):
        if tag in tags:
            kind, number, at = tags[tag]
            lines.append(f'{name} {data[at:at + number].hex()}')
    with open(target, 'w', newline='\n') as output:
        output.write('\n'.join(lines) + '\n')


def write_merged(path, target):
    with open(path, 'rb') as source:
        data = source.read()
    order, tags = raw_directory(data)
    width = values(data, order, tags[256])[0]
    height = values(data, order, tags[257])[0]
    spp = values(data, order, tags[277])[0] if 277 in tags else 1
    if values(data, order, tags[259])[0] != 1 or values(data, order, tags[258])[0] != 16:
        sys.exit(f'not an uncompressed 16-bit DNG: {path}')
    rows = bytearray()
    for offset, count in zip(values(data, order, tags[273]), values(data, order, tags[279])):
        rows += data[offset:offset + count]
    del data
    words = array('H')
    words.frombytes(bytes(rows[:width * height * spp * 2]))
    del rows
    if (order == '<') != (sys.byteorder == 'little'):
        words.byteswap()
    if sys.byteorder != 'little':
        words.byteswap()
    lines = [f'# the merged image of {os.path.basename(path)}, written by tools/testdata/raw-lens-oracle.py',
             f'size {width} {height} {spp}']
    for sample in range(spp):
        plane = words[sample::spp]
        lines.append(f'digest {sample} {hashlib.sha256(plane.tobytes()).hexdigest()}')
        lines.append(f'row {sample} {plane[:width].tobytes().hex()}')
    with open(target, 'w', newline='\n') as output:
        output.write('\n'.join(lines) + '\n')


def main():
    if os.name != 'nt':
        sys.exit('Adobe DNG Converter for Windows is needed; run this script on Windows')
    directory = testdata.conformance_dir()
    vectors = {row['name']: row for row in testdata.read_tsv(testdata.VECTORS, testdata.VECTOR_COLUMNS)}
    named = sys.argv[1:]
    samples = named or LENS_SAMPLES + SHIFT_SAMPLES
    for name in samples:
        row = vectors[name]
        path = os.path.join(directory, name)
        if not testdata.valid(path, row['sha256']):
            print(f'fetching {name}')
            testdata.download(row['url'], path, row['sha256'])
    output = os.path.join(directory, 'raw-lens-oracle')
    os.makedirs(output, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='work-', dir=output) as work:
        installer = os.path.join(work, 'AdobeDNGConverter_x64_18_0.exe')
        archive = os.path.join(work, 'innoextract.zip')
        testdata.download(CONVERTER_URL, installer, CONVERTER_SHA256)
        testdata.download(INNOEXTRACT_URL, archive, INNOEXTRACT_SHA256)
        with zipfile.ZipFile(archive) as source:
            source.extract('innoextract.exe', work)
        subprocess.run([os.path.join(work, 'innoextract.exe'), '-s', '-e', '-I', 'app', '-d', work, installer], check=True)
        os.remove(installer)
        converter = os.path.join(work, 'app', 'Adobe DNG Converter.exe')
        staging = os.path.join(work, 'out')
        os.makedirs(staging)
        for name in samples:
            stem = os.path.splitext(name)[0]
            shift = name in SHIFT_SAMPLES
            print(f'converting {name}')
            flags = ['-u'] if shift else []
            # the converter only accepts backslash-separated paths
            subprocess.run([converter, *flags, '-d', windows(staging), windows(os.path.join(directory, name))],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            dng = os.path.join(staging, stem + '.dng')
            if not os.path.exists(dng):
                sys.exit(f'Adobe DNG Converter wrote no {dng}')
            if shift:
                write_merged(dng, os.path.join(output, stem + '.merged'))
            else:
                write_lens(dng, os.path.join(output, stem + '.lens'))
            if name in RENDER_SAMPLES:
                shutil.move(dng, os.path.join(output, stem + '.dng'))
            else:
                os.remove(dng)


if __name__ == '__main__':
    main()
