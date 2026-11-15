#!/usr/bin/env python3
"""Decodes the streams of smalt-mpeg's HevcEncoderTest with the HM reference decoder and checks their hashes.

HevcEncoderTest writes every stream it hands to ffmpeg as an Annex B file under mpeg/target/hevcenc-work (one
directory per test fork when the suite runs in several). Every stream carries a decoded picture hash SEI message
per picture, so the HM reference decoder (TAppDecoder, BSD-3-Clause, used as a black-box decoder) checks the
reconstruction of the Smalt encoder picture by picture while it decodes. The script fails unless the decoder
reports every picture of every stream as matching and no error.

HM is not one of the CI oracle tools; run this after the suite on a machine with an HM build (HM-16.21 or later,
cmake -DCMAKE_BUILD_TYPE=Release with the high bit depth build, TAppDecoderHBD, for the 10-bit streams):

    mvn -f mpeg/pom.xml test -Dtest=HevcEncoderTest
    python -I tools/testdata/hevc-encoder/hm-check.py <directory of TAppDecoder> mpeg/target/hevcenc-work
"""
import os
import subprocess
import sys


def decoder(directory):
    for name in ('TAppDecoderHBD', 'TAppDecoderHighBitDepthStatic', 'TAppDecoder', 'TAppDecoderStatic'):
        for suffix in ('', '.exe'):
            path = os.path.join(directory, name + suffix)
            if os.path.isfile(path):
                return path
    raise SystemExit('no TAppDecoder in ' + directory)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    executable = decoder(sys.argv[1])
    streams = []
    for root, _, files in os.walk(sys.argv[2]):
        streams.extend(os.path.join(root, name) for name in files if name.endswith('.hevc'))
    if not streams:
        raise SystemExit('no .hevc stream under ' + sys.argv[2])
    failures = 0
    pictures = 0
    for stream in sorted(streams):
        result = subprocess.run([executable, '-b', stream], capture_output=True, text=True, timeout=600)
        lines = [line for line in result.stdout.splitlines() if line.startswith('POC')]
        matched = sum(1 for line in lines if '(OK)' in line)
        bad = result.returncode != 0 or not lines or matched != len(lines) or 'ERROR' in result.stdout.upper()
        pictures += matched
        print(('FAIL ' if bad else 'ok   ') + os.path.relpath(stream, sys.argv[2]) + ': ' + str(matched) + '/'
              + str(len(lines)) + ' pictures match')
        failures += bad
    print(str(len(streams)) + ' streams, ' + str(pictures) + ' pictures verified, ' + str(failures) + ' failed')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
