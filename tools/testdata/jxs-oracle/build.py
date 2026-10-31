#!/usr/bin/env python3
"""Builds the JPEG XS reference decoder and encoder of ISO/IEC 21122-5 as black-box oracles: smalt-jxsdec and
smalt-jxsenc.

The reference software of ISO/IEC 21122-5:2024 (third edition, the "XS test model" libjxs 3.0.2-ED3 of intoPIX,
Fraunhofer IIS and Canon) is published on ISO's maintenance portal and downloaded from it, pinned by SHA-256. Its
licence grants its use to evaluate it and to determine whether an implementation conforms to the standard; Smalt only
runs it in tests and never ships or ports it.

The programs are built unchanged, except that the command line helper's own strcat_s and strcpy_s, which MinGW's
headers also declare, are renamed.

Usage: python -I build.py <directory>     (with cc on PATH; an MSYS2 UCRT64 shell on Windows)
The programs land in <directory>/bin; run them as
  smalt-jxsdec <codestream> <output.pgx>
which writes each component as big-endian raw samples (<output>_<c>.raw, with its PGX header in <output>_<c>.h), and
  smalt-jxsenc -c "<configuration>" <input.pgx> <codestream>
"""
import hashlib
import io
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

URL = 'https://standards.iso.org/iso-iec/21122/-5/ed-3/en/ISO_IEC_21122-5_ED-3.zip'
SHA256 = '36efc26b92a09b7447bc6e580c902d5c19ee9e919b324609768e390d38621835'
LIBRARY = ['bio.c', 'buf_mgmt.c', 'dwt.c', 'ids.c', 'image.c', 'mct.c', 'nlt.c', 'packing.c', 'precinct.c',
           'precinct_budget_table.c', 'predbuffer.c', 'quant.c', 'rate_control.c', 'sb_weighting.c', 'version.c',
           'xs_config.c', 'xs_config_parser.c', 'xs_dec.c', 'xs_enc.c', 'xs_markers.c']
UTILS = ['cmdline_options.c', 'file_io.c', 'file_sequence.c', 'image_open.c', 'converters/argb.c',
         'converters/helpers.c', 'converters/mono.c', 'converters/pgx.c', 'converters/planar.c', 'converters/ppm.c',
         'converters/rgb16.c', 'converters/uyvy8.c', 'converters/v210.c', 'converters/yuv16.c']
FLAGS = ['-std=gnu11', '-O2', '-w', '-fwrapv', '-fno-strict-aliasing', '-ffp-contract=off', '-DNDEBUG']


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def fetch(work):
    archive = os.path.join(work, 'reference.zip')
    if not os.path.isfile(archive) or sha256(archive) != SHA256:
        request = urllib.request.Request(URL, headers={'User-Agent': 'smalt-testdata/1'})
        with urllib.request.urlopen(request, timeout=600) as response, open(archive + '.part', 'wb') as sink:
            shutil.copyfileobj(response, sink, 1 << 20)
        if sha256(archive + '.part') != SHA256:
            sys.exit('the reference software archive does not have the pinned SHA-256')
        os.replace(archive + '.part', archive)
    return archive


def extract(archive, source):
    if os.path.isdir(source):
        shutil.rmtree(source)
    with zipfile.ZipFile(archive) as files:
        for name in files.namelist():
            if name.endswith(('.c', '.h')):
                target = os.path.join(source, *name.split('/'))
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, 'wb') as sink:
                    sink.write(files.read(name).replace(b'\r\n', b'\n'))


def patch(path):
    with open(path, encoding='latin-1') as file:
        text = file.read()
    for name in ('strcat_s', 'strcpy_s'):
        text = text.replace(name + '(', 'smalt_' + name + '(')
    with open(path, 'w', encoding='latin-1', newline='\n') as file:
        file.write(text)


def build(archive, work, cc, decoder, encoder, env=None):
    """Builds the decoder and the encoder from the archive in the directory work into the paths decoder and encoder,
    with the C compiler command cc (a list)."""
    source = os.path.join(work, 'src')
    extract(archive, source)
    root = source
    if not os.path.isdir(os.path.join(root, 'libjxs')):
        root = os.path.join(source, os.listdir(source)[0])
    programs = os.path.join(root, 'programs')
    patch(os.path.join(programs, 'cmdline_options.c'))
    includes = ['-I' + os.path.join(root, 'libjxs', 'public'), '-I' + os.path.join(root, 'libjxs', 'src'),
                '-I' + programs, '-I' + os.path.join(root, 'std')]
    objects = []
    objects_dir = os.path.join(work, 'obj')
    os.makedirs(objects_dir, exist_ok=True)
    sources = [os.path.join(root, 'libjxs', 'src', name) for name in LIBRARY]
    sources += [os.path.join(programs, *name.split('/')) for name in UTILS]
    for index, path in enumerate(sources):
        target = os.path.join(objects_dir, '%02d.o' % index)
        subprocess.run(cc + FLAGS + includes + ['-c', path, '-o', target], check=True, env=env)
        objects.append(target)
    for main, binary in (('xs_dec_main.c', decoder), ('xs_enc_main.c', encoder)):
        os.makedirs(os.path.dirname(binary), exist_ok=True)
        subprocess.run(cc + FLAGS + includes + ['-o', binary, os.path.join(programs, main)] + objects + ['-lm'],
                       check=True, env=env)
    return decoder, encoder


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    work = os.path.abspath(sys.argv[1])
    os.makedirs(work, exist_ok=True)
    windows = os.name == 'nt' or sys.platform == 'msys'
    suffix = '.exe' if windows else ''
    cc = [shutil.which('cc') or shutil.which('gcc') or 'cc'] + (['-static'] if windows else [])
    print(build(fetch(work), work, cc, os.path.join(work, 'bin', 'smalt-jxsdec' + suffix),
                os.path.join(work, 'bin', 'smalt-jxsenc' + suffix)))


if __name__ == '__main__':
    main()
