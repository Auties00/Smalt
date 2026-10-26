#!/usr/bin/env python3
"""Writes the oracle files of the raw module's RawOracleTest, RafOracleTest, OlympusDerivationTest,
PanasonicDerivationTest and RawOracleCoverageTest.

The tests check that the decoder reproduces, exactly, the sensor values Adobe DNG Converter stores
for CC0 sample files of the formats whose coding was worked out against it (ORF, RW2,
PEF variants, Canon mRAW and sRAW, Canon CRW, Canon CR3, interlaced Coolpix NEF, Minolta MRW), check the image
area and the rendering of the Sigma X3F samples it converts (DP1, SD14, SD15), re-derive the Olympus compression and
Panasonic's raw format version 4 from witness samples, and compare the rendering of the
converter's four-color DNG files with the previews it renders into them (Canon PowerShot G1, Nikon
E5700, Sony DSC-F828), render the staggered photosites of two Fujifilm SuperCCD cameras (FinePix S2Pro and
S6500fd) against the previews the converter renders into their DNG files, and check RafOracleTest's Fujifilm RAF
decoding photosite for photosite: compressed files of X-Trans and GFX cameras, the bare sensor words of the FinePix
S-series and compacts (SuperCCD, transposed or not, the F700's rows of two images in turn, and Bayer) and the 12-bit
packings of the FinePix compacts with EXR and other CMOS sensors (F550EXR, F770EXR, X10, S1, SL1000, HS10, and the
EXR HR and DR files of the F600EXR, F900EXR and HS20EXR, an S200EXR file in EXR HR mode and an X10 file in EXR SN
mode). The F700, E550, E900, S5600, HS10, F600EXR, F900EXR and HS20EXR samples of raw.pixls.us and the S200EXR and
X10 samples of rawsamples.ch are licensed CC BY-NC-SA 4.0 (tools/testdata/licences.tsv); like every sample they are only
downloaded into the local cache for testing, never committed or redistributed. This script produces those values: it downloads Adobe DNG
Converter 18.0 for Windows and innoextract 1.9 (both pinned by SHA-256), extracts the converter
without installing it, and runs it as a black box on each sample, writing an uncompressed DNG file
(-u) per sample into <conformance cache>/raw-oracle, where the tests look by default
(-Dsmalt.test.rawOracle overrides it). For the samples of JXL_SAMPLES it also writes <stem>-jxl.dng, a DNG 1.7 file
with the sensor data in lossless JPEG XL tiles stored as four interleaved subimages (RowInterleaveFactor and
ColumnInterleaveFactor 2), JPEG XL previews and the reduced-resolution linear raw previews of fast load data
(-losslessJXL -jxl -p2 -fl), which DngSpecOracleTest decodes against the uncompressed file. For the samples of
SUMMARY_SAMPLES, whose DNG files take 100 to 200 MB each, it keeps instead a <stem>.photosites text file and deletes
the DNG file: the size, color filter array and CFALayout of the stored image, and for each sample of a pixel the
SHA-256 of its values over the whole image (little-endian 16-bit words, row by row) and the values of its first row,
which RafOracleTest uses to place the image in the decoded one. For the Fujifilm samples of two images per photosite
(LINEAR_SAMPLES) it also converts each into a demosaiced linear DNG (-u -l) and keeps a <stem>.linear crop of its
image instead, the window (1536 by 1024 pixels at most) holding the most pixels at or above the 99th percentile of
the brightest channel, or, for the EXR HR files, the centered one: the magic "SMALTLIN", then as little-endian 32-bit integers the version 1, the width and height
of the linear image and the left, top, width and height of the crop, then the crop's red, green and blue values as
little-endian 16-bit words, pixel by pixel and row by row. RafOracleTest compares the merged and interleaved photosites
with them. The sample files are fetched first when the cache lacks them.

With --coverage it instead converts every ORF sample of vectors.tsv and every RW2 or RWL sample of
raw format version 4 (about 160 files; the DNG files take about 6 GB) into
<conformance cache>/raw-oracle-coverage, which RawOracleCoverageTest checks photosite for
photosite (-Dsmalt.test.rawCoverage overrides the directory).

Windows only: the converter is a Windows executable. Run the script with a Windows build of Python;
under an MSYS2 Python the converter fails to open its input. Version 18.0 stores the same values as 14.0
for every sample both support (checked on RW2 files); 18.0 is needed for the 2022 and later
Panasonic models. The converter is free to download and use; nothing of it is redistributed.

Naming sample files converts only those (for example the MRW samples of SAMPLES), leaving the other
oracle files in place.

    python tools/testdata/raw-oracle.py [--coverage] [sample ...]
"""
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

CONVERTER_URL = 'https://download.adobe.com/pub/adobe/dng/win/AdobeDNGConverter_x64_18_0.exe'
CONVERTER_SHA256 = 'd551ec18cd81a8cb60dd2a1a0cb28b0a5c29230ff09b251670ff13d24bb71164'
INNOEXTRACT_URL = 'https://github.com/dscharrer/innoextract/releases/download/1.9/innoextract-1.9-windows.zip'
INNOEXTRACT_SHA256 = '6989342c9b026a00a72a38f23b62a8e6a22cc5de69805cf47d68ac2fec993065'

SAMPLES = [
    'rawpixls-canon-eos-40d-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-5d-mark-iii-sraw2-sraw.cr2',
    'rawpixls-canon-eos-7d-sraw1-mraw-3-2.cr2',
    'rawpixls-canon-eos-7d-sraw2-sraw-3-2.cr2',
    'rawpixls-canon-eos-10d-raw-3-2-3164.crw',
    'rawpixls-canon-eos-10d-raw-3-2-3165.crw',
    'rawpixls-canon-eos-10d-raw-3-2.crw',
    'rawpixls-canon-eos-300d-raw-3-2-3162.crw',
    'rawpixls-canon-eos-300d-raw-3-2.crw',
    'rawpixls-canon-eos-d30-raw-3-2.crw',
    'rawpixls-canon-eos-d60-raw-3-2.crw',
    'rawpixls-canon-eos-digital-rebel-raw-3-2.crw',
    'rawpixls-canon-powershot-g1-raw-4-3.crw',
    'rawpixls-canon-powershot-g5-raw-4-3.crw',
    'rawpixls-canon-powershot-g6-raw-4-3.crw',
    'rawpixls-minolta-alpha-7-digital-3-2-3919.mrw',
    'rawpixls-minolta-alpha-7-digital-3-2-7778.mrw',
    'rawpixls-minolta-alpha-sweet-digital-3-2.mrw',
    'rawpixls-minolta-dimage-5-4-3.mrw',
    'rawpixls-minolta-dimage-a2-4-3.mrw',
    'rawpixls-minolta-dynax-5d-3-2.mrw',
    'rawpixls-minolta-dynax-7d-3-2.mrw',
    'rawpixls-minolta-maxxum-7d-3-2.mrw',
    'rawpixls-nikon-coolpix-e8800-12bit-12bit-uncompressed-4-3.nef',
    'rawpixls-nikon-e5700-12bit-12bit-uncompressed-4-3.nef',
    'rawpixls-nikon-e8400-12bit-12bit-uncompressed-4-3.nef',
    'rawpixls-olympus-c5060wz-12bit-4-3.orf',
    'rawpixls-olympus-e-1-16bit-4-3.orf',
    'rawpixls-olympus-e-10-16bit-4-3.orf',
    'rawpixls-olympus-e-400-16bit-4-3.orf',
    'rawpixls-olympus-e-410-16bit-4-3.orf',
    'rawpixls-olympus-e-m1-16bit-4-3.orf',
    'rawpixls-olympus-e-m10-16bit-4-3.orf',
    'rawpixls-olympus-e-p1-16bit-4-3.orf',
    'rawpixls-olympus-e-p3-16bit-4-3.orf',
    'rawpixls-olympus-e-p5-16bit-4-3.orf',
    'rawpixls-olympus-e-pl6-16bit-4-3.orf',
    'rawpixls-om-system-om-1-16bit-4-3-5283.orf',
    'rawpixls-om-system-tg-7-16bit-4-3.orf',
    'rawpixls-pentax-ist-d-12bit-3-2.pef',
    'rawpixls-pentax-ist-dl-12bit-3-2.pef',
    'rawpixls-pentax-k-3-mark-iii-monochrome-14bit-3-2.pef',
    'rawpixls-pentax-k-5-14bit-3-2.pef',
    'rawpixls-pentax-k-s1-12bit-3-2.pef',
    'rawpixls-panasonic-dc-g9-4-3-2348.rw2',
    'rawpixls-panasonic-dc-g9m2-4-3-6999.rw2',
    'rawpixls-panasonic-dc-gh5s-4-3-2603.rw2',
    'rawpixls-panasonic-dc-s1-3-2.rw2',
    'rawpixls-panasonic-dc-s5m2-14bit-3-2-7790.rw2',
    'rawpixls-panasonic-dmc-gf6-4-3.rw2',
    'rawpixls-panasonic-dmc-gh1-1-1.rw2',
    'rawpixls-panasonic-dmc-lx3-1-1.rw2',
    'rawpixls-sony-dsc-f828-4-3.srf',
    'rawpixls-fujifilm-finepix-s2pro-3-2.raf',
    'rawpixls-fujifilm-finepix-s6500fd-4-3.raf',
    'rawpixls-fujifilm-finepix-s3pro-3-2.raf',
    'rawpixls-fujifilm-finepix-s5pro-3-2.raf',
    'rawpixls-fujifilm-finepix-s5000-4-3.raf',
    'rawpixls-fujifilm-finepix-s5200-4-3.raf',
    'rawpixls-fujifilm-finepix-s5500-4-3.raf',
    'rawpixls-fujifilm-finepix-s6000fd-4-3.raf',
    'rawpixls-fujifilm-finepix-s7000-4-3.raf',
    'rawpixls-fujifilm-finepix-s9500-4-3.raf',
    'rawpixls-fujifilm-finepix-s9600-4-3.raf',
    'rawpixls-fujifilm-finepix-s100fs-4-3.raf',
    'rawpixls-fujifilm-finepix-s200exr-4-3.raf',
    'rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-x-t4-14bit-14bit-lossless-compressed-3-2.raf',
    'rawpixls-fujifilm-x-e5-14bit-lossless-compressed-3-2.raf',
    'rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-lossless-4-3.raf',
    'rawpixls-fujifilm-gfx50s-ii-14bit-14bit-compressed-4-3.raf',
    'rawpixls-fujifilm-x-t4-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-x-e5-14bit-lossy-compressed-3-2.raf',
    'rawpixls-fujifilm-finepix-f550exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f770exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-s1-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-sl1000-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-gfx100s-16bit-16bit-lossless-compressed-4-3.raf',
    'rawpixls-fujifilm-gfx100-ii-16bit-16bit-compressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f700-double-width-16-bit-unpacked-little-endian-4-3.raf',
    'rawpixls-fujifilm-finepix-e550-4-3.raf',
    'rawpixls-fujifilm-finepix-e900-4-3.raf',
    'rawpixls-fujifilm-finepix-s5600-4-3.raf',
    'rawpixls-fujifilm-finepix-hs10-hs11-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f600exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-f900exr-12bit-12bit-uncompressed-4-3.raf',
    'rawpixls-fujifilm-finepix-hs20exr-12bit-12bit-uncompressed-4-3.raf',
    'rawsamples-fujifilm-finepix-s200exr-hr.raf',
    'rawsamples-fujifilm-x10-sn.raf',
    'rawpixls-sigma-dp1-3-2-1116.x3f',
    'rawpixls-sigma-sigma-sd14-3-2-4462.x3f',
    'rawpixls-sigma-sigma-sd15-3-2-1863.x3f',
    'rawpixls-canon-eos-1d-x-mark-iii-3-2.cr3',
    'rawpixls-canon-eos-200d-mark-ii-3-2.cr3',
    'rawpixls-canon-eos-250d-c-raw-3-2.cr3',
    'rawpixls-canon-eos-250d-raw-3-2.cr3',
    'rawpixls-canon-eos-850d-3-2.cr3',
    'rawpixls-canon-eos-90d-3-2.cr3',
    'rawpixls-canon-eos-kiss-m-3-2.cr3',
    'rawpixls-canon-eos-m200-craw-3-2.cr3',
    'rawpixls-canon-eos-m200-raw-3-2.cr3',
    'rawpixls-canon-eos-m50-3-2.cr3',
    'rawpixls-canon-eos-m50-craw-3-2.cr3',
    'rawpixls-canon-eos-m50-mark-ii-3-2.cr3',
    'rawpixls-canon-eos-m6-mark-ii-c-raw-3-2.cr3',
    'rawpixls-canon-eos-m6-mark-ii-raw-3-2.cr3',
    'rawpixls-canon-eos-r-3-2.cr3',
    'rawpixls-canon-eos-r10-3-2.cr3',
    'rawpixls-canon-eos-r100-craw-3-2.cr3',
    'rawpixls-canon-eos-r100-raw-3-2.cr3',
    'rawpixls-canon-eos-r3-3-2.cr3',
    'rawpixls-canon-eos-r5-3-2.cr3',
    'rawpixls-canon-eos-r5-mark-ii-craw-3-2.cr3',
    'rawpixls-canon-eos-r5-mark-ii-raw-3-2.cr3',
    'rawpixls-canon-eos-r5-raw-1.6-crop-dual-pixel-3-2.cr3',
    'rawpixls-canon-eos-r50-craw-3-2.cr3',
    'rawpixls-canon-eos-r50-raw-3-2.cr3',
    'rawpixls-canon-eos-r50-v-raw-3-2.cr3',
    'rawpixls-canon-eos-r6-3-2.cr3',
    'rawpixls-canon-eos-r6-mark-ii-3-2.cr3',
    'rawpixls-canon-eos-r6-mark-iii-craw-3-2.cr3',
    'rawpixls-canon-eos-r6-mark-iii-raw-3-2.cr3',
    'rawpixls-canon-eos-r7-3-2.cr3',
    'rawpixls-canon-eos-r8-3-2.cr3',
    'rawpixls-canon-eos-rebel-sl3-3-2.cr3',
    'rawpixls-canon-eos-rp-3-2.cr3',
    'rawpixls-canon-powershot-g5-x-mark-ii-c-raw-1-1.cr3',
    'rawpixls-canon-powershot-g5-x-mark-ii-c-raw-3-2.cr3',
    'rawpixls-canon-powershot-g5-x-mark-ii-raw-3-2.cr3',
    'rawpixls-canon-powershot-g7-x-mark-iii-3-2.cr3',
    'rawpixls-canon-powershot-sx70-hs-c-raw-3-2-3-2.cr3',
    'rawpixls-canon-powershot-sx70-hs-raw-3-2-3-2.cr3',
    'rawpixls-canon-powershot-v1-craw-3-2.cr3',
]


# converted a second time into DNG 1.7 files with JPEG XL data, previews and fast load data
JXL_SAMPLES = [
    'rawpixls-canon-eos-10d-raw-3-2.crw',
    'rawpixls-canon-powershot-g1-raw-4-3.crw',
    'rawpixls-fujifilm-finepix-s6500fd-4-3.raf',
]

JXL_FLAGS = ['-losslessJXL', '-jxl', '-p2', '-fl']

# kept as <stem>.photosites summaries instead of DNG files, for their size
SUMMARY_SAMPLES = [
    'rawpixls-fujifilm-gfx-50s-14bit-14bit-compressed-3-2.raf',
    'rawpixls-fujifilm-gfx100s-16bit-16bit-lossless-compressed-4-3.raf',
    'rawpixls-fujifilm-gfx100-ii-16bit-16bit-compressed-4-3.raf',
]

# converted a second time into demosaiced linear DNG files, kept as <stem>.linear crops: of the brightest window, or
# of the center for the EXR HR files, where the lens correction the converter applies to the demosaiced image of some
# of them (its WarpRectilinear) moves the pixels least
LINEAR_SAMPLES = {
    'rawpixls-fujifilm-finepix-s3pro-3-2.raf': 'brightest',
    'rawpixls-fujifilm-finepix-s5pro-3-2.raf': 'brightest',
    'rawpixls-fujifilm-finepix-f700-double-width-16-bit-unpacked-little-endian-4-3.raf': 'brightest',
    'rawpixls-fujifilm-finepix-s200exr-4-3.raf': 'brightest',
    'rawpixls-fujifilm-finepix-f600exr-12bit-12bit-uncompressed-4-3.raf': 'brightest',
    'rawpixls-fujifilm-finepix-f550exr-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawpixls-fujifilm-finepix-x10-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawpixls-fujifilm-finepix-f900exr-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawpixls-fujifilm-finepix-hs20exr-12bit-12bit-uncompressed-4-3.raf': 'center',
    'rawsamples-fujifilm-finepix-s200exr-hr.raf': 'center',
}

LINEAR_CROP = (1536, 1024)

TIFF_SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4, 16: 8}
TIFF_FORMATS = {1: 'B', 2: 'B', 3: 'H', 4: 'I', 6: 'b', 7: 'B', 8: 'h', 9: 'i', 13: 'I', 16: 'Q'}


def windows(path):
    return os.path.abspath(path).replace('/', '\\')


def rw2_version(path):
    """Returns the raw format version (tag 0x002D of the first directory) of an RW2 or RWL file."""
    with open(path, 'rb') as source:
        data = source.read(1 << 16)
    start = struct.unpack('<I', data[4:8])[0]
    count = struct.unpack('<H', data[start:start + 2])[0]
    for index in range(count):
        entry = start + 2 + 12 * index
        tag, kind, number, value = struct.unpack('<HHII', data[entry:entry + 12])
        if tag == 0x2D:
            return value & 0xFFFF
    return 0


def cfa_image(path):
    """Returns the tags of the CFA image of an uncompressed 16-bit DNG file, its byte order and the file's bytes."""
    with open(path, 'rb') as source:
        data = source.read()
    order = '<' if data[:2] == b'II' else '>'

    def directory(offset):
        count = struct.unpack(order + 'H', data[offset:offset + 2])[0]
        tags = {}
        for index in range(count):
            entry = offset + 2 + 12 * index
            tag, kind, number = struct.unpack(order + 'HHI', data[entry:entry + 8])
            size = TIFF_SIZES.get(kind, 1) * number
            if kind not in TIFF_FORMATS or size > len(data):
                continue
            at = entry + 8 if size <= 4 else struct.unpack(order + 'I', data[entry + 8:entry + 12])[0]
            tags[tag] = list(struct.unpack(order + TIFF_FORMATS[kind] * number, data[at:at + size]))
        following = struct.unpack(order + 'I', data[offset + 2 + 12 * count:offset + 6 + 12 * count])[0]
        return tags, following

    queue = [struct.unpack(order + 'I', data[4:8])[0]]
    while queue:
        offset = queue.pop(0)
        if offset <= 0 or offset >= len(data):
            continue
        tags, following = directory(offset)
        queue.extend(tags.get(330, []))
        if following:
            queue.append(following)
        if tags.get(254, [1])[0] == 0 and tags.get(262, [0])[0] in (32803, 34892):
            if tags.get(259, [1])[0] != 1 or tags.get(258, [0])[0] != 16:
                sys.exit(f'not an uncompressed 16-bit DNG: {path}')
            return tags, order, data
    sys.exit(f'no CFA image in {path}')


def image_words(path):
    """Returns the width, height, samples per pixel and samples (pixel-interleaved, native order) of the main image
    of an uncompressed 16-bit DNG file."""
    from array import array
    tags, order, data = cfa_image(path)
    width, height, spp = tags[256][0], tags[257][0], tags.get(277, [1])[0]
    line = width * spp * 2
    rows = bytearray()
    if 273 in tags:
        for offset, count in zip(tags[273], tags[279]):
            rows += data[offset:offset + count]
    else:
        tile_width, tile_height = tags[322][0], tags[323][0]
        across = (width + tile_width - 1) // tile_width
        offsets = tags[324]
        for top in range(0, height, tile_height):
            for y in range(min(tile_height, height - top)):
                for column in range(across):
                    start = offsets[top // tile_height * across + column] + y * tile_width * spp * 2
                    rows += data[start:start + min(tile_width, width - column * tile_width) * spp * 2]
    del data
    if len(rows) < line * height:
        sys.exit(f'truncated image data in {path}')
    words = array('H')
    words.frombytes(bytes(rows[:line * height]))
    del rows
    if (order == '<') != (sys.byteorder == 'little'):
        words.byteswap()
    return tags, width, height, spp, words


def write_linear_crop(path, target, window):
    """Writes the <stem>.linear crop of a demosaiced linear DNG file (see the module documentation): the brightest
    window, or the centered one."""
    from array import array
    _, width, height, spp, words = image_words(path)
    if spp != 3:
        sys.exit(f'not a three-channel linear DNG: {path}')
    crop_width, crop_height = min(LINEAR_CROP[0], width), min(LINEAR_CROP[1], height)
    if window == 'center':
        write_crop(target, words, width, height, (width - crop_width) // 2, (height - crop_height) // 2, crop_width,
                   crop_height)
        return

    def brightest(y):
        row = words[y * width * 3:(y + 1) * width * 3]
        return list(map(max, row[0::3], row[1::3], row[2::3]))

    sample = sorted(value for y in range(0, height, 16) for value in brightest(y)[::7])
    threshold = sample[int(len(sample) * 0.99)]
    # bright pixels per cell of 64 by 64, then the window of whole cells with the most
    cell = 64
    across, down = (width + cell - 1) // cell, (height + cell - 1) // cell
    counts = [0] * (across * down)
    for y in range(height):
        base = (y // cell) * across
        for x, value in enumerate(brightest(y)):
            if value >= threshold:
                counts[base + x // cell] += 1
    best, left, top = -1, 0, 0
    span_x, span_y = crop_width // cell, crop_height // cell
    for cy in range(0, max(1, down - span_y + 1)):
        for cx in range(0, max(1, across - span_x + 1)):
            total = sum(sum(counts[(cy + j) * across + cx:(cy + j) * across + cx + span_x]) for j in range(span_y))
            if total > best:
                best, left, top = total, cx * cell, cy * cell
    left, top = min(left, width - crop_width), min(top, height - crop_height)
    write_crop(target, words, width, height, left, top, crop_width, crop_height)


def write_crop(target, words, width, height, left, top, crop_width, crop_height):
    """Writes a crop of a three-channel image as a <stem>.linear file."""
    from array import array
    out = array('H')
    for y in range(top, top + crop_height):
        out.extend(words[(y * width + left) * 3:(y * width + left + crop_width) * 3])
    if sys.byteorder != 'little':
        out.byteswap()
    with open(target, 'wb') as output:
        output.write(b'SMALTLIN')
        output.write(struct.pack('<7I', 1, width, height, left, top, crop_width, crop_height))
        output.write(out.tobytes())


def write_summary(path, target):
    """Writes the photosites summary of an uncompressed DNG file (see the module documentation)."""
    import hashlib
    tags, width, height, spp, words = image_words(path)
    if sys.byteorder != 'little':
        words.byteswap()
    dimensions = tags[33421]
    lines = [f'# the stored image of {os.path.basename(path)}, written by tools/testdata/raw-oracle.py',
             f'width {width}', f'height {height}', f'samples {spp}',
             'cfa ' + ' '.join(str(value) for value in [dimensions[1], dimensions[0], *tags[33422]]),
             f'layout {tags.get(50711, [1])[0]}']
    for sample in range(spp):
        values = words[sample::spp] if spp > 1 else words
        lines.append(f'digest {sample} {hashlib.sha256(values.tobytes()).hexdigest()}')
        lines.append(f'row {sample} {values[:width].tobytes().hex()}')
    with open(target, 'w', newline='\n') as output:
        output.write('\n'.join(lines) + '\n')


def main():
    if os.name != 'nt':
        sys.exit('Adobe DNG Converter for Windows is needed; run this script on Windows')
    directory = testdata.conformance_dir()
    vectors = {row['name']: row for row in testdata.read_tsv(testdata.VECTORS, testdata.VECTOR_COLUMNS)}
    coverage = '--coverage' in sys.argv[1:]
    named = [argument for argument in sys.argv[1:] if not argument.startswith('--')]
    if coverage:
        samples = sorted(name for name in vectors if name.startswith('rawpixls-') and name.endswith(('.orf', '.rw2', '.rwl')))
    else:
        samples = SAMPLES
    if named:
        unknown = [name for name in named if name not in samples]
        if unknown:
            sys.exit(f'not an oracle sample: {", ".join(unknown)}')
        samples = named
    for name in samples:
        row = vectors[name]
        path = os.path.join(directory, name)
        if not testdata.valid(path, row['sha256']):
            print(f'fetching {name}')
            testdata.download(row['url'], path, row['sha256'])
    if coverage:
        samples = [name for name in samples
                   if name.endswith('.orf') or rw2_version(os.path.join(directory, name)) == 4]
    output = os.path.join(directory, 'raw-oracle-coverage' if coverage else 'raw-oracle')
    os.makedirs(output, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='raw-oracle-') as work:
        installer = os.path.join(work, 'AdobeDNGConverter_x64_18_0.exe')
        archive = os.path.join(work, 'innoextract.zip')
        print('fetching Adobe DNG Converter 18.0')
        testdata.download(CONVERTER_URL, installer, CONVERTER_SHA256)
        print('fetching innoextract 1.9')
        testdata.download(INNOEXTRACT_URL, archive, INNOEXTRACT_SHA256)
        with zipfile.ZipFile(archive) as source:
            source.extract('innoextract.exe', work)
        subprocess.run([os.path.join(work, 'innoextract.exe'), '-s', '-e', '-I', 'app', '-d', work, installer], check=True)
        os.remove(installer)
        converter = os.path.join(work, 'app', 'Adobe DNG Converter.exe')
        for name in samples:
            print(f'converting {name}')
            # the converter only accepts backslash-separated paths
            subprocess.run([converter, '-u', '-d', windows(output), windows(os.path.join(directory, name))], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            target = os.path.join(output, os.path.splitext(name)[0] + '.dng')
            if not os.path.isfile(target):
                sys.exit(f'Adobe DNG Converter wrote no {target}')
            if not coverage and name in SUMMARY_SAMPLES:
                write_summary(target, os.path.splitext(target)[0] + '.photosites')
                os.remove(target)
            if not coverage and name in LINEAR_SAMPLES:
                staging = os.path.join(work, 'linear')
                os.makedirs(staging, exist_ok=True)
                subprocess.run([converter, '-u', '-l', '-d', windows(staging), windows(os.path.join(directory, name))],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                staged = os.path.join(staging, os.path.splitext(name)[0] + '.dng')
                if not os.path.isfile(staged):
                    sys.exit(f'Adobe DNG Converter wrote no {staged}')
                write_linear_crop(staged, os.path.join(output, os.path.splitext(name)[0] + '.linear'),
                                  LINEAR_SAMPLES[name])
                os.remove(staged)
            if not coverage and name in JXL_SAMPLES:
                staging = os.path.join(work, 'jxl')
                os.makedirs(staging, exist_ok=True)
                subprocess.run([converter, *JXL_FLAGS, '-d', windows(staging), windows(os.path.join(directory, name))],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                staged = os.path.join(staging, os.path.splitext(name)[0] + '.dng')
                if not os.path.isfile(staged):
                    sys.exit(f'Adobe DNG Converter wrote no {staged}')
                os.replace(staged, os.path.join(output, os.path.splitext(name)[0] + '-jxl.dng'))
    print(f'wrote {len(samples)} oracle files into {output}')


if __name__ == '__main__':
    main()
