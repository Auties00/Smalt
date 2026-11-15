#!/usr/bin/env python3
"""Writes the HEVC decoded picture hash vectors of smalt-mpeg's HevcPictureHashTest.

Each vector is a short HEVC elementary stream coded by the HM reference encoder (TAppEncoder, BSD-3-Clause, used as
a black-box encoder) from ffmpeg's testsrc2 pattern with decoded picture hash SEI messages of one type (MD5, CRC or
checksum) on pictures several coding tree block rows tall, in the chroma formats and bit depths HM codes. The HM
reference decoder (TAppDecoder) checks every hash while it decodes the stream, and the script fails unless it
reports every picture as matching; the SHA-256 of its output (8-bit samples, or 16-bit little-endian ones above
8 bits, planar, chroma included) goes into hash.tsv next to the streams:

    name    pictures    hash type    stream sha256    decoded sha256

Made with HM-18.0 (https://vcgit.hhi.fraunhofer.de/jvet/HM, tag HM-18.0) built with gcc 15 (cmake -DCMAKE_BUILD_TYPE=Release, targets TAppEncoder
and TAppDecoder) and ffmpeg 7.1. Pass the HM source tree, whose bin directory holds the build:

    python -I tools/testdata/hevc-hash/make-vectors.py <HM source tree> <mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/hash>
"""
import hashlib
import os
import subprocess
import sys
import tempfile

LOW_DELAY = 'encoder_lowdelay_P_main.cfg'
RANDOM_ACCESS = 'encoder_randomaccess_main.cfg'

# name: (width, height, frames, ffmpeg pixel format, hash type (1 MD5, 2 CRC, 3 checksum), configuration, options)
VECTORS = {
    'crc-420-8bit': (136, 104, 4, 'yuv420p', 2, LOW_DELAY, []),
    'crc-420-8bit-ctu16': (72, 56, 3, 'yuv420p', 2, LOW_DELAY, ['--MaxCUSize=16', '--MaxPartitionDepth=2',
                                                                '--QuadtreeTULog2MaxSize=4']),
    'crc-420-10bit': (136, 104, 4, 'yuv420p10le', 2, RANDOM_ACCESS, ['--InputBitDepth=10', '--InternalBitDepth=10',
                                                                     '--Profile=main10']),
    'crc-420-12bit': (96, 80, 3, 'yuv420p12le', 2, LOW_DELAY, ['--InputBitDepth=12', '--InternalBitDepth=12',
                                                               '--Profile=main12']),
    'crc-422-10bit': (96, 80, 3, 'yuv422p10le', 2, LOW_DELAY, ['--InputBitDepth=10', '--InternalBitDepth=10',
                                                               '--InputChromaFormat=422', '--ChromaFormatIDC=422',
                                                               '--Profile=main_422_10']),
    'crc-444-8bit': (104, 72, 3, 'yuv444p', 2, LOW_DELAY, ['--InputChromaFormat=444', '--ChromaFormatIDC=444',
                                                           '--Profile=main_444']),
    'crc-400-8bit': (104, 72, 3, 'gray', 2, LOW_DELAY, ['--InputChromaFormat=400', '--ChromaFormatIDC=400',
                                                        '--Profile=main-RExt']),
    'checksum-420-8bit': (136, 104, 4, 'yuv420p', 3, LOW_DELAY, []),
    'checksum-444-10bit': (104, 72, 3, 'yuv444p10le', 3, LOW_DELAY, ['--InputBitDepth=10', '--InternalBitDepth=10',
                                                                     '--InputChromaFormat=444',
                                                                     '--ChromaFormatIDC=444',
                                                                     '--Profile=main_444_10']),
    'md5-420-8bit': (136, 104, 4, 'yuv420p', 1, LOW_DELAY, []),
}

HASH_NAMES = {1: 'MD5', 2: 'CRC', 3: 'Checksum'}


def sha256(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    source_tree = os.path.abspath(sys.argv[1])
    target = os.path.abspath(sys.argv[2])
    configurations = os.path.join(source_tree, 'cfg')
    suffix = '.exe' if os.name == 'nt' else ''
    executables = {}
    for root, _, files in os.walk(os.path.join(source_tree, 'bin')):
        for executable in ('TAppEncoder', 'TAppDecoder'):
            if executable + suffix in files:
                executables[executable] = os.path.join(root, executable + suffix)
    if len(executables) != 2 or not os.path.isdir(configurations):
        raise SystemExit('no HM build (bin/**/TAppEncoder, TAppDecoder and cfg) in ' + source_tree)
    encoder = executables['TAppEncoder']
    decoder = executables['TAppDecoder']
    os.makedirs(target, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name, (width, height, frames, pixel_format, hash_type, configuration, options) in VECTORS.items():
            source = os.path.join(work, name + '.yuv')
            subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                                   'testsrc2=size=%dx%d:rate=25' % (width, height), '-frames:v', str(frames),
                                   '-pix_fmt', pixel_format, '-f', 'rawvideo', source])
            stream = os.path.join(target, name + '.hevc')
            subprocess.check_call([encoder, '-c', os.path.join(configurations, configuration), '-i', source,
                                   '-wdt', str(width), '-hgt', str(height), '-fr', '25', '-f', str(frames),
                                   '--SEIDecodedPictureHash=%d' % hash_type, '-q', '32', '-b', stream,
                                   '-o', os.path.join(work, name + '.recon')] + options, stdout=subprocess.DEVNULL)
            decoded = os.path.join(work, name + '.decoded')
            log = subprocess.run([decoder, '-b', stream, '-o', decoded], check=True, capture_output=True,
                                 text=True).stdout
            matched = log.count('(OK)')
            if matched != frames or HASH_NAMES[hash_type] + ':' not in log or '***ERROR***' in log:
                raise SystemExit(name + ': HM did not verify every hash:\n' + log)
            rows.append('\t'.join([name, str(frames), str(hash_type), sha256(stream), sha256(decoded)]))
    with open(os.path.join(target, 'hash.tsv'), 'w', newline='\n') as sink:
        sink.write('# Written by tools/testdata/hevc-hash/make-vectors.py: name, pictures, hash type, SHA-256 of the '
                   'stream,\n# SHA-256 of the HM-18.0 reference decoder output.\n')
        for row in rows:
            sink.write(row + '\n')
    print('wrote %d vectors to %s' % (len(rows), target))


if __name__ == '__main__':
    main()
