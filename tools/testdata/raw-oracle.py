#!/usr/bin/env python3
"""Writes the oracle files of the raw module's RawOracleTest, OlympusDerivationTest,
PanasonicDerivationTest and RawOracleCoverageTest.

The tests check that the decoder reproduces, exactly, the sensor values Adobe DNG Converter stores
for CC0 sample files of the formats whose coding was worked out against it (ORF, RW2,
PEF variants, Canon mRAW and sRAW, Canon CRW, Canon CR3, interlaced Coolpix NEF, Minolta MRW), check the image
area and the rendering of the Sigma X3F samples it converts (DP1, SD14, SD15), re-derive the Olympus compression and
Panasonic's raw format version 4 from witness samples, and compare the rendering of the
converter's four-color DNG files with the previews it renders into them (Canon PowerShot G1, Nikon
E5700, Sony DSC-F828), and render the staggered photosites of two Fujifilm SuperCCD cameras (FinePix S2Pro and
S6500fd) against the previews the converter renders into their DNG files. This script produces those values: it downloads Adobe DNG
Converter 18.0 for Windows and innoextract 1.9 (both pinned by SHA-256), extracts the converter
without installing it, and runs it as a black box on each sample, writing an uncompressed DNG file
(-u) per sample into <conformance cache>/raw-oracle, where the tests look by default
(-Dsmalt.test.rawOracle overrides it). For the samples of JXL_SAMPLES it also writes <stem>-jxl.dng, a DNG 1.7 file
with the sensor data in lossless JPEG XL tiles stored as four interleaved subimages (RowInterleaveFactor and
ColumnInterleaveFactor 2), JPEG XL previews and the reduced-resolution linear raw previews of fast load data
(-losslessJXL -jxl -p2 -fl), which DngSpecOracleTest decodes against the uncompressed file. The sample files are
fetched first when the cache lacks them.

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
