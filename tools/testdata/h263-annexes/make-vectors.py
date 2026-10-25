"""Makes the H.263 streams of H263AnnexStreamsTest, which exercise the optional modes of ITU-T H.263 that the
macroblock engine shared with MPEG-4 Part 2 lacks.

usage: python -I make-vectors.py <smalt checkout> <directory holding the tmn and tmndec binaries>

Every stream codes a synthetic QCIF sequence (moving sinusoidal texture, a checkerboard, hashed noise and a moving
disc) and comes with the MD5 of the decoder reconstruction it implies when blocks are transformed with the double
precision reference inverse DCT of the test models, which a decoder using that transform must reproduce sample for
sample:

- the tmn-* streams are coded by the TMN 3.2 encoder of the University of British Columbia (tmn-3.2.0.tgz from
  https://www.itu.int/wftp3/av-arch/video-site/h263plus/TMN_software_UBC/, built with its Makefile and run as a
  black box). Their reference is the encoder's reconstruction (-o), or for the enhancement layer of spatial
  scalability the output of the matching decoder, tmndec 3.2 (tmndec-3.2.0.tgz) with -r, whose reconstruction file
  the encoder writes with a wrong size. The TMN encoder is always run with the Unrestricted Motion Vector mode,
  since without it the encoder codes vectors past the right edge of the picture that the Recommendation forbids.
- the smalt-* streams are coded by h263enc.py in this directory, an encoder written from the text of the
  Recommendation for the modes the TMN encoder lacks (Annexes C, K with rectangular slices, N, P, Q, R and U) and
  for B-pictures and Improved PB-frames combined with them; it computes the reconstruction itself.

The script writes the streams and golden.tsv into mpeg/src/test/resources/com/github/auties00/smalt/video/h263/annex/.
golden.tsv holds per stream: the file, its origin, the SHA-256 of the file, the number of pictures each
sub-bitstream outputs, the size of every output picture (one size when they share it), and the MD5 of the planar
4:2:0 output of each sub-bitstream in display order. The inputs are synthetic and both encoders are deterministic, so
the checksums are reproduced.
"""
import hashlib
import math
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import h263enc  # noqa: E402

FRAMES = 30

# name, TMN options, last frame passed to -b, reference: 'encoder' (the encoder's reconstruction), 'enhanced' (its
# enhancement layer reconstruction) or 'tmndec' (enhanced_1.raw of tmndec -r), output size
TMN = [
    ('tmn-sac', ['-E', '-D', '1', '-F'], 7, 'encoder', '176x144'),
    ('tmn-pb', ['-G', '-D', '1', '-F'], 7, 'encoder', '176x144'),
    ('tmn-pb-sac', ['-G', '-E', '-D', '1'], 7, 'encoder', '176x144'),
    ('tmn-ipb', ['-M', '-D', '2', '-F', '-J'], 7, 'encoder', '176x144'),
    ('tmn-ipb-aic', ['-M', '-D', '2', '-I', '-T', '-S'], 7, 'encoder', '176x144'),
    ('tmn-b', ['-c', '2', '-D', '2', '-J', '-F', '-I'], 7, 'encoder', '176x144'),
    ('tmn-snr', ['-u', '1', '-v', '6', '-D', '2'], 9, 'enhanced', '176x144'),
    ('tmn-spatial-horizontal', ['-u', '3', '-v', '6', '-D', '2'], 9, 'tmndec', '352x144'),
    ('tmn-spatial-vertical', ['-u', '5', '-v', '6', '-D', '2'], 9, 'tmndec', '176x288'),
    ('tmn-spatial', ['-u', '7', '-v', '6', '-D', '2'], 9, 'tmndec', '352x288'),
]

# name, configuration of h263enc.py
OWN = [
    ('smalt-rpr', 'rpr'), ('smalt-rru', 'rru'), ('smalt-rru-ap', 'rruap'), ('smalt-rru-df', 'rrudf'),
    ('smalt-isd', 'isd'), ('smalt-rect-aso', 'rect'), ('smalt-isd-rect', 'isdrect'), ('smalt-rps', 'rps'),
    ('smalt-erps', 'erps'), ('smalt-erps-b', 'erpsb'), ('smalt-b', 'b'), ('smalt-b-rpr', 'brpr'),
    ('smalt-b-rru', 'brru'), ('smalt-ipb', 'ipb'), ('smalt-ipb-df', 'ipbdf'), ('smalt-ipb-erps', 'ipberps'),
    ('smalt-ipb-rru', 'ipbrru'), ('smalt-cpm', 'cpm'),
]


def source(width, height, frames):
    """Returns the synthetic 4:2:0 sequence."""
    def noise(x, y, t):
        return ((x * 73856093) ^ (y * 19349663) ^ (t * 83492791)) & 15

    def clip(v):
        return max(0, min(255, int(round(v * 255))))

    data = bytearray()
    for t in range(frames):
        for y in range(height):
            for x in range(width):
                sx = x * 176.0 / width
                sy = y * 144.0 / height
                v = (0.5 + 0.23 * math.sin((sx + 2 * t) / 7.0) * math.cos((sy - t) / 9.0)
                     + 0.12 * ((int(sx + 1.5 * t) // 8 + int(sy) // 8) % 2) + (noise(x, y, t) - 8) / 400.0)
                cx = 60 + 3.0 * t
                cy = 72 + 6.0 * math.sin(t / 3.0)
                if (sx - cx) ** 2 + (sy - cy) ** 2 < 400:
                    v = 0.85 - 0.3 * ((sx - cx) ** 2 + (sy - cy) ** 2) / 400
                data.append(clip(v))
        for plane in range(2):
            for y in range(height // 2):
                for x in range(width // 2):
                    sx = x * 88.0 / (width // 2)
                    sy = y * 72.0 / (height // 2)
                    v = 0.5 + 0.3 * (math.sin((sx - t) / 11.0) if plane == 0 else math.cos((sy + t) / 13.0))
                    data.append(clip(v))
    return bytes(data)


def tmn_vector(binaries, work, src, name, options, last, reference):
    """Codes a stream with the TMN encoder and returns it with its reference reconstruction."""
    d = os.path.join(work, name)
    os.makedirs(d)
    shutil.copy(src, os.path.join(d, 'src.yuv'))
    subprocess.run([os.path.join(binaries, 'tmn'), '-i', 'src.yuv', '-B', 'out.263', '-o', 'rec.yuv', '-a', '0', '-b',
                    str(last), '-k', '0', '-q', '10'] + options, cwd=d, check=True, stdout=subprocess.DEVNULL)
    if reference == 'encoder':
        ref = os.path.join(d, 'rec.yuv')
    elif reference == 'enhanced':
        ref = os.path.join(d, 'enhanced.raw')
    else:
        dec = os.path.join(d, 'dec')
        os.makedirs(dec)
        subprocess.run([os.path.join(binaries, 'tmndec'), '-o5', '-r', '-q', '../out.263', 'base.yuv'], cwd=dec,
                       check=True, stdout=subprocess.DEVNULL)
        ref = os.path.join(dec, 'enhanced_1.raw')
    return open(os.path.join(d, 'out.263'), 'rb').read(), [open(ref, 'rb').read()]


def write(out, rows, name, origin, stream, frames, size, refs):
    """Writes a stream and its golden.tsv row."""
    open(os.path.join(out, name + '.263'), 'wb').write(stream)
    rows.append('\t'.join([name + '.263', origin, hashlib.sha256(stream).hexdigest(), ','.join(map(str, frames)),
                           size, ','.join(hashlib.md5(r).hexdigest() for r in refs)]))
    print(name, len(stream), 'bytes', frames)


def main():
    checkout, binaries = sys.argv[1], sys.argv[2]
    out = os.path.join(checkout, 'mpeg/src/test/resources/com/github/auties00/smalt/video/h263/annex')
    os.makedirs(out, exist_ok=True)
    h263enc.load_tcoef(os.path.join(checkout, 'mpeg/src/main/java/com/github/auties00/smalt/video/mpeg4/internal',
                                    'Tables.java'))
    qcif = source(176, 144, FRAMES)
    rows = ['# file\torigin\tsha256\tpictures per sub-bitstream\tpicture sizes\tMD5 of each sub-bitstream\'s '
            'reference reconstruction',
            '# made by tools/testdata/h263-annexes/make-vectors.py']
    with tempfile.TemporaryDirectory() as work:
        src = os.path.join(work, 'src.yuv')
        open(src, 'wb').write(qcif)
        for name, options, last, reference, size in TMN:
            stream, refs = tmn_vector(binaries, work, src, name, options, last, reference)
            w, h = map(int, size.split('x'))
            frames = [len(r) // (w * h * 3 // 2) for r in refs]
            write(out, rows, name, 'tmn' if reference != 'tmndec' else 'tmn+tmndec', stream, frames, size, refs)
    for name, config in OWN:
        cfg = h263enc.CONFIGS[config]
        enc = h263enc.Encoder(cfg, qcif, 176, 144)
        bits, recon = cfg['recipe'](enc)
        groups = recon if isinstance(recon[0], list) else [recon]
        sizes = sorted(set('%dx%d' % (p.width, p.height) for g in groups for p in g))
        size = sizes[0] if len(sizes) == 1 else ','.join('%dx%d' % (p.width, p.height) for p in groups[0])
        refs = [b''.join(p.dump() for p in g) for g in groups]
        write(out, rows, name, 'smalt-h263enc', bits.data(), [len(g) for g in groups], size, refs)
    open(os.path.join(out, 'golden.tsv'), 'w', newline='\n').write('\n'.join(rows) + '\n')


if __name__ == '__main__':
    main()
