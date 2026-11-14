#!/usr/bin/env python3
"""Writes the HEVC screen content coding vectors of smalt-mpeg's HevcSccConformanceTest.

The JCT-VC conformance streams of the screen content coding extensions are fetched, never committed (their licence is
unstated), so the test also decodes short streams the HM-SCM reference encoder (TAppEncoder, BSD-3-Clause, used as a
black-box encoder) coded from ffmpeg's testsrc pattern, whose flat colors, digits and color bars select palette mode,
intra block copy and the adaptive color transform. Each stream carries MD5 decoded picture hash SEI messages; the HM
reference decoder (TAppDecoder) checks every hash while it decodes the stream, and the script fails unless it reports
every picture as matching. scc.tsv next to the streams holds:

    name    frames    bit depth    stream sha256    decoded sha256    frame MD5s

where the decoded SHA-256 and the comma-separated frame MD5s hash HM's output in output order (planar, chroma
included; 8-bit samples, or 16-bit little-endian ones above 8 bits).

    scc-intra-444-8   all intra, 4:4:4, 8 bits: palette mode with predictor initializers in the SPS and the PPS,
                      intra block copy, the adaptive color transform, wavefronts, disabled intra boundary filtering
    scc-ld-444-8      low delay B, 4:4:4, 8 bits: the above with inter pictures, adaptive motion vector resolution
                      (motion_vector_resolution_control_idc 2) and two tile columns
    scc-ra-420-10     random access, 4:2:0, 10 bits: palette mode and intra block copy with B pictures
    scc-ld-400-8      low delay B, monochrome, 8 bits: palette mode with monochrome predictor initializers in the SPS
                      and the PPS and escape samples (a two-entry palette), intra block copy
    scc-ld-422-10     low delay B, 4:2:2, 10 bits: palette mode with the escape samples of the subsampled chroma (a
                      four-entry palette) and intra block copy with half-sample chroma vectors
    scc-ld-444-8-lossless
                      low delay B, 4:4:4, 8 bits, every coding unit lossless (cu_transquant_bypass_flag): escape samples
                      coded as fixed-length samples (a four-entry palette), the lossless adaptive color transform and
                      intra block copy
    scc-ld-444-8-mixed
                      low delay B, 4:4:4, 8 bits, lossless and lossy coding units mixed in a picture
    scc-ld-444-8-10   low delay B, 4:4:4, 8-bit luma and 10-bit chroma: the bit depth alignment of the adaptive color
                      transform; HM's output and the expected one carry the luma shifted left to 10 bits

Made with HM-16.21+SCM-8.8 (https://vcgit.hhi.fraunhofer.de/jvet/HM, branch HM-SCC-extensions at f9f83a0) built with
gcc 15 (cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS="-w -fpermissive", targets TAppEncoder and TAppDecoder)
and ffmpeg 7.1. Pass the directory holding TAppEncoder and TAppDecoder, the HM cfg directory and the output directory:

    python -I tools/testdata/hevc-scc/make-vectors.py <HM bin> <HM cfg> \\
        <mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/scc>
"""
import hashlib
import os
import subprocess
import sys
import tempfile

WIDTH = 192
HEIGHT = 128

# palettes small enough for the pattern's colors to need escape samples, by palette_max_size
SMALL_PALETTE = {size: ['--PaletteMaxSize=%d' % size, '--PaletteMaxPredSize=%d' % (2 * size)] for size in (2, 4)}

# name: (configuration, frames, chroma format, luma bit depth, chroma bit depth, options)
VECTORS = {
    'scc-intra-444-8': ('encoder_intra_main_scc.cfg', 2, '444', 8, 8,
                        ['--WaveFrontSynchro=1', '--PalettePredInSPSEnabled=1', '--PalettePredInPPSEnabled=1']),
    'scc-ld-444-8': ('encoder_lowdelay_main_scc.cfg', 5, '444', 8, 8,
                     ['--NumTileColumnsMinus1=1', '--UniformSpacingIdc=1']),
    'scc-ra-420-10': ('misc/encoder_randomaccess_main_scc_GOP8.cfg', 9, '420', 10, 10, ['--IntraPeriod=8']),
    'scc-ld-400-8': ('encoder_lowdelay_main_scc.cfg', 3, '400', 8, 8,
                     ['--PalettePredInSPSEnabled=1', '--PalettePredInPPSEnabled=1'] + SMALL_PALETTE[2]),
    'scc-ld-422-10': ('encoder_lowdelay_main_scc.cfg', 3, '422', 10, 10, SMALL_PALETTE[4]),
    'scc-ld-444-8-lossless': ('encoder_lowdelay_main_scc.cfg', 3, '444', 8, 8,
                              ['--TransquantBypassEnable=1', '--CUTransquantBypassFlagForce=1', '--CostMode=lossless']
                              + SMALL_PALETTE[4]),
    'scc-ld-444-8-mixed': ('encoder_lowdelay_main_scc.cfg', 3, '444', 8, 8,
                           ['--TransquantBypassEnable=1', '--CostMode=mixed_lossless_lossy']),
    'scc-ld-444-8-10': ('encoder_lowdelay_main_scc.cfg', 3, '444', 8, 10, []),
}

PIXEL_FORMATS = {('444', 8): 'yuv444p', ('420', 10): 'yuv420p10le', ('400', 8): 'gray', ('422', 10): 'yuv422p10le',
                 ('444', 10): 'yuv444p10le'}

# chroma samples per four luma samples
CHROMA_SAMPLES = {'400': 0, '420': 2, '422': 4, '444': 8}


def tool(directory, name):
    path = os.path.abspath(os.path.join(directory, name))
    return path + '.exe' if os.path.exists(path + '.exe') else path


def run(args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit('%s failed:\n%s%s' % (args[0], result.stdout, result.stderr))
    return result.stdout


def make(name, hm_bin, cfg, out_dir, work):
    config, frames, chroma, luma_depth, chroma_depth, options = VECTORS[name]
    depth = max(luma_depth, chroma_depth)
    src = os.path.join(work, name + '.yuv')
    run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
         'testsrc=size=%dx%d:rate=25' % (WIDTH, HEIGHT), '-frames:v', str(frames), '-pix_fmt',
         PIXEL_FORMATS[(chroma, depth)], src])
    bit = os.path.join(out_dir, name + '.hevc')
    run([tool(hm_bin, 'TAppEncoder'), '-c', os.path.join(cfg, config), '-i', src, '-b', bit, '-o', os.devnull,
         '-wdt', str(WIDTH), '-hgt', str(HEIGHT), '-fr', '25', '-f', str(frames),
         '--InputChromaFormat=' + chroma, '--ChromaFormatIDC=' + chroma, '--InputBitDepth=%d' % depth,
         '--InternalBitDepth=%d' % luma_depth, '--InternalBitDepthC=%d' % chroma_depth,
         '--OutputBitDepth=%d' % depth, '--OutputBitDepthC=%d' % depth, '--SEIDecodedPictureHash=1',
         '--ConformanceWindowMode=0'] + options)
    rec = os.path.join(work, name + '-rec.yuv')
    # -d shifts a shallower plane left to the deepest depth, as the decoder under test outputs it
    log = run([tool(hm_bin, 'TAppDecoder'), '-b', bit, '-o', rec, '-d', str(depth)])
    if log.count('(OK)') != frames or 'ERROR' in log:
        raise SystemExit('HM did not verify every picture hash of %s:\n%s' % (name, log))
    decoded = open(rec, 'rb').read()
    sample = 2 if depth > 8 else 1
    frame_size = WIDTH * HEIGHT * sample * (4 + CHROMA_SAMPLES[chroma]) // 4
    assert len(decoded) == frames * frame_size, 'HM wrote %d bytes' % len(decoded)
    md5s = [hashlib.md5(decoded[i * frame_size:(i + 1) * frame_size]).hexdigest() for i in range(frames)]
    stream = open(bit, 'rb').read()
    return '\t'.join([name, str(frames), str(depth), hashlib.sha256(stream).hexdigest(),
                      hashlib.sha256(decoded).hexdigest(), ','.join(md5s)])


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    hm_bin, cfg, out_dir = sys.argv[1:]
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name in VECTORS:
            rows.append(make(name, hm_bin, cfg, out_dir, work))
    with open(os.path.join(out_dir, 'scc.tsv'), 'w', newline='\n') as f:
        f.write('# name\tframes\tbit depth\tstream sha256\tdecoded sha256\tframe MD5s\n')
        for row in rows:
            f.write(row + '\n')


if __name__ == '__main__':
    main()
