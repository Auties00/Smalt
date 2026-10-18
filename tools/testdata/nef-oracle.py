#!/usr/bin/env python3
"""Writes the oracle files of the raw module's NikonTablesTest.

The test re-derives the Huffman tables of Nikon's NEF compression from CC0 sample files and the
sensor values Adobe DNG Converter stores for them. This script produces those values: it downloads
Adobe DNG Converter 14.0 for Windows and innoextract 1.9 (both pinned by SHA-256), extracts the
converter without installing it, and runs it as a black box on each sample NEF file, writing an
uncompressed DNG file (-u) per sample into <conformance cache>/nef-oracle, where the test looks
by default (-Dsmalt.test.nefOracle overrides it). The sample NEF files are fetched first when the
cache lacks them.

Windows only: the converter is a Windows executable. Adobe DNG Converter 7.4 stores the same values
for every sample it supports (checked on 22 files), so the version only matters for camera
support. The converter is free to download and use; nothing of it is redistributed.

    python tools/testdata/nef-oracle.py
"""
import os
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

CONVERTER_URL = 'https://download.adobe.com/pub/adobe/dng/win/AdobeDNGConverter_x64_14_0.exe'
CONVERTER_SHA256 = 'a2b14beac45b1b0d51527c6a7738256ee486b40ccc6a0ba10764bd8ab34a2579'
INNOEXTRACT_URL = 'https://github.com/dscharrer/innoextract/releases/download/1.9/innoextract-1.9-windows.zip'
INNOEXTRACT_SHA256 = '6989342c9b026a00a72a38f23b62a8e6a22cc5de69805cf47d68ac2fec993065'

SAMPLES = [
    'rawpixls-nikon-d300s-12bit-12bit-compressed-lossless-3-2.nef',
    'rawpixls-nikon-d300s-14bit-14bit-compressed-lossless-3-2.nef',
    'rawpixls-nikon-d5600-12bit-12bit-compressed-lossy-type-2-3-2-2261.nef',
    'rawpixls-nikon-d7000-12bit-12bit-compressed-lossy-type-2-3-2.nef',
    'rawpixls-nikon-d7000-14bit-14bit-compressed-lossless-3-2.nef',
    'rawpixls-nikon-d7000-14bit-14bit-compressed-lossy-type-2-3-2-2578.nef',
    'rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef',
    'rawpixls-nikon-z-6-2-12bit-12bit-compressed-lossy-type-2-3-2.nef',
]


def windows(path):
    return os.path.abspath(path).replace('/', '\\')


def main():
    if os.name != 'nt':
        sys.exit('Adobe DNG Converter for Windows is needed; run this script on Windows')
    directory = testdata.conformance_dir()
    vectors = {row['name']: row for row in testdata.read_tsv(testdata.VECTORS, testdata.VECTOR_COLUMNS)}
    for name in SAMPLES:
        row = vectors[name]
        path = os.path.join(directory, name)
        if not testdata.valid(path, row['sha256']):
            print(f'fetching {name}')
            testdata.download(row['url'], path, row['sha256'])
    output = os.path.join(directory, 'nef-oracle')
    os.makedirs(output, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='nef-oracle-') as work:
        installer = os.path.join(work, 'AdobeDNGConverter_x64_14_0.exe')
        archive = os.path.join(work, 'innoextract.zip')
        print('fetching Adobe DNG Converter 14.0')
        testdata.download(CONVERTER_URL, installer, CONVERTER_SHA256)
        print('fetching innoextract 1.9')
        testdata.download(INNOEXTRACT_URL, archive, INNOEXTRACT_SHA256)
        with zipfile.ZipFile(archive) as source:
            source.extract('innoextract.exe', work)
        subprocess.run([os.path.join(work, 'innoextract.exe'), '-s', '-e', '-I', 'app', '-d', work, installer], check=True)
        converter = os.path.join(work, 'app', 'Adobe DNG Converter.exe')
        for name in SAMPLES:
            print(f'converting {name}')
            # the converter only accepts backslash-separated paths
            subprocess.run([converter, '-u', '-d', windows(output), windows(os.path.join(directory, name))], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            target = os.path.join(output, name[:-4] + '.dng')
            if not os.path.isfile(target):
                sys.exit(f'Adobe DNG Converter wrote no {target}')
    print(f'wrote {len(SAMPLES)} oracle files into {output}')


if __name__ == '__main__':
    main()
