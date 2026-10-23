"""Makes the small scalable (SVC) H.264 streams of H264SvcStreamsTest with the JSVM reference software.

usage: python -I make-vectors.py <JSVM bin directory> <JSVM data directory> <output directory>

The bin directory holds the JSVM 9.19.15 executables H264AVCEncoderLibTestStatic, BitStreamExtractorStatic and
H264AVCDecoderLibTestStatic (built from the JSVM sources, H264Extension); the data directory is the JSVM
H264Extension/data directory, whose sample configurations encoder.cfg and layer0.cfg every configuration starts
from, with the overrides of VECTORS. The inputs are synthetic: the same moving scene drawn at the size of each
layer. For every stream the script encodes the layers, lists the layers the extractor reports, and for the highest
temporal layer of every DQId (and the lowest temporal layer of the highest DQId) extracts the sub-bitstream with
BitStreamExtractorStatic -sl and decodes it with the JSVM decoder. It writes each stream and the rows of
streams.tsv: per stream its SHA-256 and size, and per decoded layer its DQId, the highest temporal_id it holds, its
frame count and the MD5 of the decoder's planar 4:2:0 output. The inputs are synthetic and the JSVM encoder is
deterministic, so the streams' checksums are reproduced.
"""
import hashlib
import os
import re
import subprocess
import sys

COMMON_LAYER = {'FrameRateIn': '30', 'FrameRateOut': '30', 'IDRPeriod': '0', 'MinLevelIdc': '10',
                'BaseLayerId': '0'}

ADAPTIVE = {'InterLayerPred': '2', 'ILModePred': '2', 'ILMotionPred': '2', 'ILResidualPred': '2'}

# name -> overrides of encoder.cfg and of layer0.cfg for each layer
VECTORS = {
    # coarse-grain quality scalability over three temporal layers of hierarchical P pictures
    'svc-cgs-temporal': {
        'main': {'FramesToBeEncoded': '9', 'GOPSize': '4', 'IntraPeriod': '8', 'CgsSnrRefinement': '0',
                 'EncodeKeyPictures': '0', 'NumberReferenceFrames': '2', 'FrameRate': '30.0'},
        'layers': [
            {'SourceWidth': '48', 'SourceHeight': '32', 'SymbolMode': '0', 'ProfileIdc': '66',
             'DisableBSlices': '1', 'QP': '36.0', 'IntraPeriod': '8'},
            dict(ADAPTIVE, SourceWidth='48', SourceHeight='32', SymbolMode='0', ProfileIdc='83',
                 DisableBSlices='1', QP='28.0', IntraPeriod='8'),
        ],
    },
    # medium-grain quality scalability: hierarchical B pictures, key pictures that store and predict from
    # reference base pictures, CABAC and 8x8 transforms in the quality layer
    'svc-mgs-keys': {
        'main': {'FramesToBeEncoded': '9', 'GOPSize': '4', 'IntraPeriod': '8', 'CgsSnrRefinement': '1',
                 'EncodeKeyPictures': '1', 'MGSControl': '2', 'NumberReferenceFrames': '2', 'FrameRate': '30.0'},
        'layers': [
            {'SourceWidth': '64', 'SourceHeight': '48', 'SymbolMode': '0', 'ProfileIdc': '100',
             'DisableBSlices': '0', 'QP': '34.0', 'Enable8x8Transform': '0', 'IntraPeriod': '8'},
            dict(ADAPTIVE, SourceWidth='64', SourceHeight='48', SymbolMode='1', ProfileIdc='86',
                 DisableBSlices='0', QP='28.0', Enable8x8Transform='1', IntraPeriod='8'),
        ],
    },
    # spatial scalability: a dyadic CAVLC layer over the base, then a CABAC layer at 1.5 times its size
    'svc-spatial': {
        'main': {'FramesToBeEncoded': '5', 'GOPSize': '2', 'IntraPeriod': '4', 'CgsSnrRefinement': '0',
                 'EncodeKeyPictures': '0', 'NumberReferenceFrames': '1', 'FrameRate': '30.0'},
        'layers': [
            {'SourceWidth': '32', 'SourceHeight': '32', 'SymbolMode': '0', 'ProfileIdc': '66',
             'DisableBSlices': '1', 'QP': '32.0', 'IntraPeriod': '4'},
            dict(ADAPTIVE, SourceWidth='64', SourceHeight='64', SymbolMode='0', ProfileIdc='83',
                 DisableBSlices='1', QP='30.0', IntraPeriod='4'),
            dict(ADAPTIVE, SourceWidth='96', SourceHeight='96', SymbolMode='1', ProfileIdc='86',
                 DisableBSlices='0', QP='30.0', IntraPeriod='4', Enable8x8Transform='1', UseESS='1',
                 ESSCropWidth='96', ESSCropHeight='96', ESSOriginX='0', ESSOriginY='0'),
        ],
    },
}


class Noise:
    """A linear congruential generator, so that the inputs do not depend on the Python version."""

    def __init__(self):
        self.seed = 12345

    def next(self):
        self.seed = (self.seed * 1103515245 + 12345) & 0x7FFFFFFF
        return self.seed >> 16


def scene(width, height, frames):
    """Returns the 4:2:0 samples of a textured square moving over a gradient, with noise."""
    noise = Noise()
    out = bytearray()
    for t in range(frames):
        luma = bytearray(width * height)
        sx, sy = 0.15 + 0.06 * t, 0.2 + 0.03 * t
        for j in range(height):
            v = j / height
            for i in range(width):
                u = i / width
                value = 40 + int(150 * (u + v) / 2)
                if sx <= u < sx + 0.35 and sy <= v < sy + 0.4:
                    value = 200 - int(60 * (((i * 8 // width) + (j * 8 // height)) & 1)) + int(20 * u)
                value += (noise.next() % 9) - 4
                luma[j * width + i] = max(16, min(235, value))
        cw, ch = width // 2, height // 2
        cb = bytearray(cw * ch)
        cr = bytearray(cw * ch)
        for j in range(ch):
            for i in range(cw):
                u, v = i / cw, j / ch
                inside = sx <= u < sx + 0.35 and sy <= v < sy + 0.4
                cb[j * cw + i] = 90 + int(60 * u) + (30 if inside else 0)
                cr[j * cw + i] = 160 - int(50 * v) - (40 if inside else 0) + t
        out += luma + cb + cr
    return bytes(out)


def configure(template, overrides):
    """Returns a JSVM configuration file with the values of some keys replaced."""
    lines = []
    for line in open(template, encoding='latin-1').read().splitlines():
        m = re.match(r'^(#?)(\w+)(\s+)(\S+)(.*)$', line)
        if m and m.group(2) in overrides:
            lines.append(m.group(2) + m.group(3) + overrides[m.group(2)] + m.group(5))
        else:
            lines.append(line)
    return '\n'.join(lines) + '\n'


def run(command, cwd):
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        sys.exit(' '.join(command) + ' failed:\n' + result.stdout.decode('latin-1')[-3000:])
    return result.stdout.decode('latin-1')


def max_temporal_id(stream):
    """Returns the highest temporal_id of the prefix and scalable extension NAL units of a stream."""
    best = 0
    for match in re.finditer(b'\x00\x00\x01', stream):
        i = match.start() + 3
        if i + 3 < len(stream) and stream[i] & 31 in (14, 20) and stream[i + 1] & 0x80:
            best = max(best, stream[i + 3] >> 5)
    return best


def main():
    bin_dir, data, out = (os.path.abspath(arg) for arg in sys.argv[1:4])
    encoder = os.path.join(bin_dir, 'H264AVCEncoderLibTestStatic')
    extractor = os.path.join(bin_dir, 'BitStreamExtractorStatic')
    decoder = os.path.join(bin_dir, 'H264AVCDecoderLibTestStatic')
    os.makedirs(out, exist_ok=True)
    rows = []
    for name, spec in VECTORS.items():
        work = os.path.join(out, 'work', name)
        os.makedirs(work, exist_ok=True)
        frames = int(spec['main']['FramesToBeEncoded'])
        main_cfg = dict(spec['main'], OutputFile=name + '.264', NumLayers=str(len(spec['layers'])))
        text = configure(os.path.join(data, 'encoder.cfg'), main_cfg)
        text = re.sub(r'(?m)^LayerCfg.*\n', '', text)
        text = text.replace('#============================== WEIGHTED',
                            ''.join('LayerCfg layer%d.cfg\n' % k for k in range(len(spec['layers'])))
                            + '\n#============================== WEIGHTED')
        open(os.path.join(work, 'main.cfg'), 'w', newline='\n').write(text)
        for k, layer in enumerate(spec['layers']):
            width, height = int(layer['SourceWidth']), int(layer['SourceHeight'])
            source = 'in_%dx%d.yuv' % (width, height)
            open(os.path.join(work, source), 'wb').write(scene(width, height, frames))
            overrides = dict(COMMON_LAYER, **layer)
            overrides['InputFile'] = source
            overrides['ReconFile'] = 'rec_L%d.yuv' % k
            if k == 0:
                overrides.pop('BaseLayerId')
            open(os.path.join(work, 'layer%d.cfg' % k), 'w', newline='\n').write(
                configure(os.path.join(data, 'layer0.cfg'), overrides))
        run([encoder, '-pf', 'main.cfg'], work)
        stream = open(os.path.join(work, name + '.264'), 'rb').read()
        listing = run([extractor, name + '.264'], work)
        layers = [(int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
                  for m in re.finditer(r'(?m)^\s+(\d+)\s+\d+x\s*\d+.*\((\d+),(\d+),(\d+)\)', listing)]
        top = {}
        for layer_id, d, t, q in layers:
            if (d, q) not in top or t > top[(d, q)][2]:
                top[(d, q)] = (layer_id, d, t, q)
        chosen = sorted(top.values(), key=lambda e: (e[1], e[3]))
        last = chosen[-1]
        lowest = min((e for e in layers if (e[1], e[3]) == (last[1], last[3])), key=lambda e: e[2])
        if lowest[2] != last[2]:
            chosen.append(lowest)
        decoded = []
        for k, (layer_id, d, t, q) in enumerate(chosen):
            sub = 'L%d.264' % k
            run([extractor, name + '.264', sub, '-sl', str(layer_id)], work)
            run([decoder, sub, 'L%d.yuv' % k], work)
            samples = open(os.path.join(work, 'L%d.yuv' % k), 'rb').read()
            sub_stream = open(os.path.join(work, sub), 'rb').read()
            size = int(spec['layers'][d]['SourceWidth']) * int(spec['layers'][d]['SourceHeight']) * 3 // 2
            decoded.append('%d:%d:%d:%s' % (d * 16 + q, max_temporal_id(sub_stream), len(samples) // size,
                                            hashlib.md5(samples).hexdigest()))
        open(os.path.join(out, name + '.264'), 'wb').write(stream)
        rows.append('%s\t%s\t%d\t%s' % (name, hashlib.sha256(stream).hexdigest(), len(stream), ' '.join(decoded)))
    with open(os.path.join(out, 'streams.tsv'), 'w', newline='\n') as tsv:
        tsv.write('# Scalable H.264 streams made by tools/testdata/h264-svc/make-vectors.py with JSVM 9.19.15. '
                  'Columns:\n# name\tsha256\tbytes\tlayers, each DQId:temporal_id:frames:MD5 of the JSVM decoding '
                  'of its sub-bitstream\n')
        for row in rows:
            tsv.write(row + '\n')


if __name__ == '__main__':
    main()
