#!/usr/bin/env python3
"""Writes the reference outputs of smalt-mpeg's TimeWarpedSynthesisTest (tw-1024.f32 and tw-768.f32).

No conformance stream of ISO/IEC 23003-3 uses the time-warped MDCT (tw_mdct), so the oracle is the time-warped
filterbank of the ISO/IEC 23003-3 reference software: usac_tw_tools.c (warp contour, sample positions, warped
windowing, resampling) and calc_tw_window of usac_calcWindow.c, compiled with the frontend
usac-tw-oracle/twref.c, which holds the time-warped branch of fd2buffer (usac_imdct.c) and drives them like
decodeChannelElement over pseudo-random window sequences, shapes, warp ratios and transform outputs (the generator
is described in twref.c; the test rebuilds the same inputs). The stand-in headers of usac-tw-oracle/shim replace
the reference software's headers, which pull in the whole decoder.

The reference software, which this script downloads from standards.iso.org (pinned by SHA-256) into
<work directory>/download and never redistributes, is built with gcc (on Windows from an MSYS2 UCRT64 shell). The
committed outputs were written with mingw-w64 gcc 15.2 on Windows x64.

    python -I tools/testdata/usac-tw-oracle.py <work directory> <output directory>
"""
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

URL = 'https://standards.iso.org/iso-iec/23003/-3/ed-2/en/ISO_IEC_23003-3_2019(E)_Reference_Software.zip'
SHA256 = 'e775283aa86c9e525fc6941889ebbc083d9fc1ae19fbbc52971ef5eeccd1245e'
ROOT = 'MPEG-D_USAC_refSoft_src_v02_03_00_20190711/mpegD_usac/usacEncDec/'
SOURCES = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'usac-tw-oracle')
FILES = ['src_usac/usac_tw_tools.c', 'src_usac/usac_tw_tools.h', 'src_usac/usac_tw_defines.h',
         'src_usac/usac_calcWindow.c', 'src_usac/usac_calcWindow.h', 'src_frame/vector_ops.c',
         'src_frame/vector_ops.h']
CASES = [(1024, 16, 12345), (768, 16, 777)]


def fetch(target):
    if not os.path.exists(target):
        urllib.request.urlretrieve(URL, target)
    digest = hashlib.sha256(open(target, 'rb').read()).hexdigest()
    if digest != SHA256:
        raise SystemExit(target + ' has SHA-256 ' + digest + ', expected ' + SHA256)


def build(work):
    download = os.path.join(work, 'download')
    os.makedirs(download, exist_ok=True)
    archive = os.path.join(download, 'ISO_IEC_23003-3_2019_Reference_Software.zip')
    fetch(archive)
    # the reference sources sit apart from the rest of the reference software, so that their quoted includes
    # reach the stand-in headers
    sources = os.path.join(work, 'tw-src')
    shutil.rmtree(sources, ignore_errors=True)
    os.makedirs(sources)
    with zipfile.ZipFile(archive) as source:
        for name in FILES:
            with source.open(ROOT + name) as data, open(os.path.join(sources, os.path.basename(name)), 'wb') as out:
                shutil.copyfileobj(data, out)
    includes = ['-I' + os.path.join(SOURCES, 'shim'), '-I' + sources]
    objects = []
    for name in ['usac_tw_tools.c', 'usac_calcWindow.c', 'vector_ops.c', None]:
        path = os.path.join(SOURCES, 'twref.c') if name is None else os.path.join(sources, name)
        target = os.path.join(sources, os.path.basename(path)[:-2] + '.o')
        subprocess.check_call(['gcc', '-O2', '-fcommon', '-w', '-c'] + includes + ['-o', target, path])
        objects.append(target)
    executable = os.path.join(sources, 'twref.exe' if os.name == 'nt' else 'twref')
    subprocess.check_call(['gcc', '-o', executable] + objects + ['-lm'])
    return executable


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    work = os.path.abspath(sys.argv[1])
    output = os.path.abspath(sys.argv[2])
    os.makedirs(work, exist_ok=True)
    os.makedirs(output, exist_ok=True)
    twref = build(work)
    for length, frames, seed in CASES:
        target = os.path.join(output, 'tw-%d.f32' % length)
        subprocess.check_call([twref, str(length), str(frames), str(seed), target])
        print(os.path.basename(target), hashlib.sha256(open(target, 'rb').read()).hexdigest())


if __name__ == '__main__':
    main()
