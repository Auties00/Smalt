#!/usr/bin/env python3
"""Builds the MPEG-2 video reference decoder and encoder of ISO/IEC 13818-5 as black-box oracles: smalt-mpeg2dec and
smalt-mpeg2enc.

The reference software of ISO/IEC 13818-5:2005 (the MPEG Software Simulation Group's mpeg2decode and mpeg2encode,
version 1.2 of 1996, which decode and encode ISO/IEC 13818-2 and ISO/IEC 11172-2 video) was published in ISO's
Publicly Available Standards tree; that tree is gone, and the archive is downloaded from the Internet Archive's copy,
pinned by SHA-256. Its sources state that they "are available to the user without any license fee or royalty on an
'as is' basis"; Smalt only runs them in tests and never ships them.

The decoder is built with three changes, none of which touches the normative decoding process:
  - its integer inverse DCT (idct.c) is replaced by smalt_idct.c, the transform Smalt's decoder uses, so that the
    two decode bit for bit (the inverse DCT is the one step the standards leave free within the IEEE 1180
    accuracy); -r still selects the decoder's double precision reference transform (idctref.c);
  - the YUV output (-o0) appends every frame, its Y, U and V planes in turn, to the one file the output name gives,
    instead of writing three files per frame;
  - it is compiled without the X11 display, the trace and the verifier.
The encoder is built unchanged.

Usage: python -I build.py <directory>     (with cc on PATH; an MSYS2 UCRT64 shell on Windows)
The programs land in <directory>/bin; run them as
  smalt-mpeg2dec -b <bitstream> -q -f -o0 <output file> [-r]
which writes the decoded frames in display order as planar 8-bit frames of horizontal_size by vertical_size (the
chroma planes of the sequence's chroma format, their width halved by a right shift for 4:2:0 and 4:2:2), and
  smalt-mpeg2enc <parameter file> <bitstream>
whose parameter file follows the decoder's video/par examples.
"""
import hashlib
import io
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

URL = ('http://web.archive.org/web/20060630183351id_/http://standards.iso.org/ittf/PubliclyAvailableStandards/'
       'c039486_ISO_IEC_13818-5_2005_Reference_Software.zip')
SHA256 = 'ba372c4069e9c96d0675e1442dbcb5a9c7f46d8ca760866bd51f185641ba759e'
HERE = os.path.dirname(os.path.abspath(__file__))
DECODER_SOURCES = ['mpeg2dec.c', 'getpic.c', 'motion.c', 'getvlc.c', 'gethdr.c', 'getblk.c', 'getbits.c', 'store.c',
                   'recon.c', 'spatscal.c', 'idctref.c', 'systems.c', 'subspic.c', 'verify.c']
ENCODER_SOURCES = ['mpeg2enc.c', 'conform.c', 'putseq.c', 'putpic.c', 'puthdr.c', 'putmpg.c', 'putvlc.c', 'putbits.c',
                   'motion.c', 'predict.c', 'readpic.c', 'writepic.c', 'transfrm.c', 'fdctref.c', 'idct.c',
                   'quantize.c', 'ratectl.c', 'stats.c']
# The YUV output of store.c: every plane of every frame is appended to the file the output name gives.
STORE_REPLACEMENTS = [
    ('sprintf(tmpname,"%s.Y",outname);', 'sprintf(tmpname,"%s",outname);'),
    ('sprintf(tmpname,"%s.U",outname);', 'sprintf(tmpname,"%s",outname);'),
    ('sprintf(tmpname,"%s.V",outname);', 'sprintf(tmpname,"%s",outname);'),
    ('if ((outfile = open(name,O_CREAT|O_TRUNC|O_WRONLY|O_BINARY,0666))==-1)',
     'if ((outfile = open(name,O_CREAT|O_APPEND|O_WRONLY|O_BINARY,0666))==-1)'),
]
# The sources are pre-ANSI C (implicit int, implicit function declarations), which gnu89 accepts.
FLAGS = ['-std=gnu89', '-O2', '-w', '-fwrapv', '-fno-strict-aliasing', '-ffp-contract=off',
         '-Wno-error=implicit-function-declaration', '-Wno-error=implicit-int', '-Wno-error=int-conversion',
         '-Wno-error=incompatible-pointer-types']


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
    """Extracts the decoder and encoder sources of the video part of the archive (video.zip inside it)."""
    if os.path.isdir(source):
        shutil.rmtree(source)
    with zipfile.ZipFile(archive) as outer:
        inner = zipfile.ZipFile(io.BytesIO(outer.read('video.zip')))
    for part in ('decoder', 'encoder'):
        prefix = 'video/' + part + '/'
        os.makedirs(os.path.join(source, part), exist_ok=True)
        for name in inner.namelist():
            if name.startswith(prefix) and name.endswith(('.c', '.h')) and '/' not in name[len(prefix):]:
                with open(os.path.join(source, part, name[len(prefix):]), 'wb') as sink:
                    sink.write(inner.read(name).replace(b'\r\n', b'\n'))


def patch(path, replacements):
    with open(path, encoding='latin-1') as file:
        text = file.read()
    for old, new in replacements:
        if old not in text:
            sys.exit('%s does not contain %r' % (path, old))
        text = text.replace(old, new)
    with open(path, 'w', encoding='latin-1', newline='\n') as file:
        file.write(text)


def link(cc, directory, sources, binary, env):
    objects = []
    for source in sources:
        target = os.path.join(directory, os.path.splitext(os.path.basename(source))[0] + '.o')
        subprocess.run(cc + FLAGS + ['-I' + directory, '-c', source, '-o', target], check=True, env=env)
        objects.append(target)
    os.makedirs(os.path.dirname(binary), exist_ok=True)
    subprocess.run(cc + ['-o', binary] + objects + ['-lm'], check=True, env=env)
    return binary


def build(archive, work, cc, decoder, encoder, env=None):
    """Builds the decoder and the encoder from the archive in the directory work into the paths decoder and encoder,
    with the C compiler command cc (a list)."""
    source = os.path.join(work, 'src')
    extract(archive, source)
    decoding = os.path.join(source, 'decoder')
    patch(os.path.join(decoding, 'store.c'), STORE_REPLACEMENTS)
    shutil.copy2(os.path.join(HERE, 'smalt_idct.c'), os.path.join(decoding, 'smalt_idct.c'))
    sources = [os.path.join(decoding, name) for name in DECODER_SOURCES + ['smalt_idct.c']]
    link(cc, decoding, sources, decoder, env)
    encoding = os.path.join(source, 'encoder')
    link(cc, encoding, [os.path.join(encoding, name) for name in ENCODER_SOURCES], encoder, env)
    return decoder, encoder


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    work = os.path.abspath(sys.argv[1])
    os.makedirs(work, exist_ok=True)
    windows = os.name == 'nt' or sys.platform == 'msys'
    suffix = '.exe' if windows else ''
    cc = [shutil.which('cc') or shutil.which('gcc') or 'cc'] + (['-static'] if windows else [])
    print(build(fetch(work), work, cc, os.path.join(work, 'bin', 'smalt-mpeg2dec' + suffix),
                os.path.join(work, 'bin', 'smalt-mpeg2enc' + suffix)))


if __name__ == '__main__':
    main()
