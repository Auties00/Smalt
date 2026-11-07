#!/usr/bin/env python3
"""Writes the committed 3D-HEVC vectors of smalt-mpeg's Hevc3dVectorTest.

The JCT-3V conformance streams of 3D-HEVC are fetched, never committed (their licence is unstated), so the tests also
decode short streams the 3D-HEVC reference encoder HTM (TAppEncoder, BSD-3-Clause, used as a black-box encoder) coded
from a synthetic scene: two views of 128x128 pictures (HTM's encoder needs whole coding tree blocks), each with its
depth view (layers 0 texture view 0, 1 depth view 0, 2 texture view 1, 3 depth view 1), random access with a GOP of 8. The texture of each view is a moving pattern
of gradients and squares shifted by the disparity its depth map gives, and the depth map is a ramp with a foreground
square. Every stream carries MD5 decoded picture hash SEI messages of each layer; the HTM reference decoder
(TAppDecoder) checks every hash while it decodes the stream, and the script fails unless it reports every picture of
every layer as matching. vectors.tsv next to the streams holds one row per layer:

    name    layer    width    height    frames    stream sha256    frame MD5s

where the comma-separated frame MD5s hash HTM's output of the layer in output order (planar 4:2:0, 8-bit samples, a
depth layer with mid-gray chroma).

    tex-ivmc-arp     inter-view motion prediction with 8x8 sub-block partitions, the view scaling of inter-view
                     motion vectors and advanced residual prediction in the dependent texture view; inter-view
                     motion prediction in the dependent depth view
    tex-ic           illumination compensation (with the merge index 0 exception off) and inter-view motion prediction
    tex-all          inter-view motion prediction with 16x16 sub-block partitions in both dependent views, motion
                     vector scaling, residual prediction and illumination compensation

The streams use no tool that reads the depth view of the texture being decoded (view synthesis prediction,
depth-based block partitioning, depth-refined disparity vectors): HTM's decoder refines disparity vectors with depth
only when depth-based block partitioning is on, so HTM's own streams with depth refinement alone fail its hash check.

Made with HTM-16.3 (https://vcgit.hhi.fraunhofer.de/jvet/HTM, tag HTM-16.3) compiled with gcc 15 and HEVC_EXT=2
from the sources of TLibCommon, TLibEncoder, TLibDecoder, TLibRenderer, TLibVideoIO, TAppCommon, libmd5,
TAppEncoder and TAppDecoder (g++ -O2 -w -fpermissive -std=gnu++11). Pass the encoder, the decoder, the HTM cfg
directory and the output directory:

    python -I tools/testdata/hevc-3d/make-vectors.py <TAppEncoder> <TAppDecoder> <HTM cfg> \\
        <mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/threeD>
"""
import hashlib
import os
import re
import subprocess
import sys
import tempfile

WIDTH, HEIGHT, FRAMES = 128, 128, 9

COMMON = ['--SEIDecodedPictureHash=1', '--VSO=0', '--IntraWedgeFlag=0', '--IntraContourFlag=0', '--IntraSdcFlag=0',
          '--DLT=0', '--QTL=0', '--QtPredFlag=0', '--InterSdcFlag=0', '--MpiFlag=0', '--DepthIntraSkip=0',
          '--ViewSynthesisPredFlag=0', '--DepthBasedBlkPartFlag=0', '--IntraPeriod=24', '--ProfileTierLevelIdx_3=1 2 2 2']

VECTORS = {
    'tex-ivmc-arp': ['--IvMvPredFlag=1 1', '--IvResPredFlag=1', '--IlluCompEnable=0', '--IvMvScalingFlag=1',
                     '--Log2SubPbSizeMinus3=0', '--DepthRefinementFlag=0'],
    'tex-ic': ['--IvMvPredFlag=1 0', '--IvResPredFlag=0', '--IlluCompEnable=1', '--IlluCompLowLatencyEnc=0',
               '--IvMvScalingFlag=0', '--Log2SubPbSizeMinus3=3', '--DepthRefinementFlag=0'],
    'tex-all': ['--IvMvPredFlag=1 1', '--IvResPredFlag=1', '--IlluCompEnable=1', '--IlluCompLowLatencyEnc=0',
                '--IvMvScalingFlag=1', '--Log2SubPbSizeMinus3=1', '--DepthRefinementFlag=0'],
}


def depth(x, y, t):
    """The depth sample of the scene: a ramp, nearer at the bottom, with a nearer square moving right."""
    value = 40 + (y * 120) // HEIGHT
    if 30 + 3 * t <= x < 70 + 3 * t and 28 <= y < 64:
        value = 200
    return value


def disparity(value, view):
    return (value * 6 * view) // 255


def texture(x, y, t, view):
    """The texture of the scene seen from a view: the pattern at the position the disparity moves it from."""
    d = depth(x, y, t)
    xs = x + disparity(d, view)
    if d == 200:
        u = xs - 3 * t
        return 60 + ((u * 7 + y * 5) % 128) if (u // 8 + y // 8) % 2 else 220 - (y % 16) * 4
    return (xs * 2 + y + t * 2) % 200 + 20 + (16 if (xs // 16 + y // 16) % 2 else 0)


def write_yuv(path, sample, chroma):
    with open(path, 'wb') as f:
        for t in range(FRAMES):
            f.write(bytes(sample(x, y, t) for y in range(HEIGHT) for x in range(WIDTH)))
            for c in range(2):
                f.write(bytes(chroma(x * 2, y * 2, t, c) for y in range(HEIGHT // 2) for x in range(WIDTH // 2)))


def main():
    encoder, decoder, cfg, out = sys.argv[1:5]
    rows = []
    with tempfile.TemporaryDirectory() as work:
        inputs = []
        for view in range(2):
            tex = os.path.join(work, 'tex%d.yuv' % view)
            dep = os.path.join(work, 'dep%d.yuv' % view)
            write_yuv(tex, lambda x, y, t, v=view: texture(x, y, t, v),
                      lambda x, y, t, c, v=view: (texture(x, y, t, v) // 2 + 64 + 40 * c) % 256)
            write_yuv(dep, lambda x, y, t, v=view: depth(x + disparity(depth(x, y, t), v), y, t),
                      lambda x, y, t, c: 128)
            inputs += [tex, dep]
        cam = os.path.join(work, 'cam_pars.txt')
        with open(cam, 'w', newline='\n') as f:
            f.write('0 0 %d 1000.0 0.0 0.0 -2000.0 -9000.0\n' % (FRAMES - 1))
            f.write('1 0 %d 1000.0 -5.0 0.0 -2000.0 -9000.0\n' % (FRAMES - 1))
        with open(os.path.join(cfg, '3D-HEVC', 'baseCfg_2view+depth.cfg')) as f:
            base = f.read().splitlines()
        for name, options in VECTORS.items():
            stream = os.path.join(out, name + '.hevc')
            # No inter-component tool is enabled, so no slice may enable inter-component prediction.
            lines = []
            for line in base:
                match = re.match(r'(Frame\w*_l[123]:.*\s)\d\s*$', line)
                if match:
                    line = match.group(1) + '0'
                lines.append(line)
            config = os.path.join(work, name + '.cfg')
            with open(config, 'w', newline='\n') as f:
                f.write('\n'.join(lines) + '\n')
            args = [encoder, '-c', config,
                    '-c', os.path.join(cfg, '3D-HEVC', 'qpCfg_Nview+depth_QP30.cfg'),
                    '--SourceWidth=%d' % WIDTH, '--SourceHeight=%d' % HEIGHT, '--FrameRate=25',
                    '--FramesToBeEncoded=%d' % FRAMES, '--CameraParameterFile=' + cam, '--BaseViewCameraNumbers=0 1',
                    '--BitstreamFile=' + stream]
            for i, path in enumerate(inputs):
                args += ['--InputFile_%d=%s' % (i, path), '--ReconFile_%d=%s' % (i, os.path.join(work, 'enc_%d.yuv' % i))]
            args += COMMON + options
            r = subprocess.run(args, cwd=work, capture_output=True, text=True)
            if r.returncode:
                sys.exit('%s: the encoder failed (%d):\n%s%s' % (name, r.returncode, r.stdout[-3000:], r.stderr[-2000:]))
            log = subprocess.run([decoder, '-b', stream, '-o', os.path.join(work, 'rec.yuv')], check=True,
                                 capture_output=True, text=True, cwd=work).stdout
            pictures = re.findall(r'Layer\s+\d+\s+POC.*', log)
            if len(pictures) != 4 * FRAMES or any('(OK)' not in line for line in pictures):
                sys.exit('%s: the decoder did not verify every picture hash:\n%s' % (name, log))
            with open(stream, 'rb') as f:
                digest = hashlib.sha256(f.read()).hexdigest()
            size = WIDTH * HEIGHT * 3 // 2
            for layer in range(4):
                with open(os.path.join(work, 'rec_%d.yuv' % layer), 'rb') as f:
                    data = f.read()
                md5s = [hashlib.md5(data[i * size:(i + 1) * size]).hexdigest() for i in range(len(data) // size)]
                rows.append('\t'.join([name, str(layer), str(WIDTH), str(HEIGHT), str(len(md5s)), digest,
                                       ','.join(md5s)]))
    with open(os.path.join(out, 'vectors.tsv'), 'w', newline='\n') as f:
        f.write('\n'.join(rows) + '\n')
    print('wrote %d layers' % len(rows))


if __name__ == '__main__':
    main()
