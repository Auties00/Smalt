#!/usr/bin/env python3
"""Writes the oracle files of smalt-mpeg's MpegSurroundTest into <conformance cache>/mps-oracle.

The MPEG Surround (ISO/IEC 23003-1) conformance streams are not public, so the oracle is the MPEG Surround decoder
of the ISO/IEC 23003-3 reference software (mp4spatialdec, the full 23003-1 decoder: every tree, residual coding,
arbitrary downmix, temporal shaping), driven by a small frontend (mps-oracle/mpsref.c) and fed two kinds of
streams:

1. Synthetic streams written by mps-oracle/mpsgen.py: every tree configuration (5151 to 7572), quantization and
   TTT mode, temporal shaping, arbitrary downmix gains, residual and arbitrary downmix residual coding, at 24, 48
   and 96 kHz, each with a synthetic downmix: <case>.bs, <case>_dmx.wav and <case>_ref.wav.
2. Streams of the reference encoder (mp4spatialenc, trees 5151, 5152 and 525 at 16 and 32 slots per frame) of a
   synthetic 5.1 signal: enc<tree>_<slots>.bs, its downmix enc<tree>_<slots>_dmx.wav and the decode
   enc<tree>_<slots>_ref.wav.

The .bs files hold length-prefixed blocks (32-bit little endian): the header (sacTimeAlignFlag, sacHeaderLen and the
SpatialSpecificConfig), then one byte-aligned SpatialFrame() per frame. The WAV files are float in 16-bit scale
divided by 32768.

The reference software, which this script downloads from standards.iso.org (pinned by SHA-256) into
<work directory>/download and never redistributes, trims the configuration syntax to the 2-1-2 tree of USAC: the
script patches copies of sac_bitdec.c and bitstream.c to read and write the full SpatialSpecificConfig of
ISO/IEC 23003-1 (sampling frequency index, frame length and tree, differential parameter slots, the high rate
mode outside 2-1-2) and builds the decoder and encoder libraries with gcc (on Windows from an MSYS2 UCRT64 shell).

    python -I tools/testdata/mps-oracle.py <work directory>
"""
import hashlib
import math
import os
import random
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

URL = 'https://standards.iso.org/iso-iec/23003/-3/ed-2/en/ISO_IEC_23003-3_2019(E)_Reference_Software.zip'
SHA256 = 'e775283aa86c9e525fc6941889ebbc083d9fc1ae19fbbc52971ef5eeccd1245e'
ROOT = 'MPEG-D_USAC_refSoft_src_v02_03_00_20190711/mpegD_usac/'
SOURCES = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mps-oracle')
ENCODES = [(tree, slots) for tree in (5151, 5152, 525) for slots in (16, 32)]

BITDEC_PATCHES = [(
    """  config->bsTreeConfig = 7;  /* only MPS212 in USAC  */

  if(config->bsTreeConfig != 7){
    config->bsFrameLength = s_GetBits(bitstream, 7);
  } else {
    config->bsFrameLength = bsFrameLength;
  }
  config->bsFreqRes = s_GetBits(bitstream, 3);
""",
    """  {
    int samplingFrequencyIndex = s_GetBits(bitstream, 4);
    if (samplingFrequencyIndex == 15) {
      s_GetBits(bitstream, 24);
    }
  }
  config->bsFrameLength = s_GetBits(bitstream, 7);
  config->bsFreqRes = s_GetBits(bitstream, 3);
  config->bsTreeConfig = s_GetBits(bitstream, 4);
  config->bsHighRateMode = 1;
"""), (
    """  for(i = 0; i < self->numParameterSets; i++) {
    if(bsFramingType) {
      int bitsParamSlot = 0;
      while((1<<bitsParamSlot) < self->timeSlots) bitsParamSlot++;
      self->paramSlot[i] = s_GetBits(bitstream, bitsParamSlot);
    } else {""",
    """  for(i = 0; i < self->numParameterSets; i++) {
    if(bsFramingType && self->treeConfig != 7) {
      int bitsParamSlot = 0;
      int previousSlot = i > 0 ? self->paramSlot[i - 1] : -1;
      while((1<<bitsParamSlot) < (self->timeSlots - self->numParameterSets + i - previousSlot)) bitsParamSlot++;
      self->paramSlot[i] = previousSlot + 1 + (bitsParamSlot ? s_GetBits(bitstream, bitsParamSlot) : 0);
    } else if(bsFramingType) {
      int bitsParamSlot = 0;
      while((1<<bitsParamSlot) < self->timeSlots) bitsParamSlot++;
      self->paramSlot[i] = s_GetBits(bitstream, bitsParamSlot);
    } else {"""), (
    """  if(self->treeConfig == 7){
    self->bsHighRateMode = config->bsHighRateMode;
  }""",
    """  if(self->treeConfig == 7){
    self->bsHighRateMode = config->bsHighRateMode;
  } else {
    self->bsHighRateMode = 1;
  }""")]

BITSTREAM_PATCHES = [(
    """int FreqResBinTable[] = {0, 28, 20, 14, 10, 7, 5, 4};""",
    """int FreqResBinTable[] = {0, 28, 20, 14, 10, 7, 5, 4};

int mpsrefSamplingFrequencyIndex = 3;"""), (
    """  if(config->bsTreeConfig == 7) {
    treeDescription->numOttBoxes""",
    """  if(config->bsTreeConfig == 7 || config->bsTreeConfig <= 2) {
    treeDescription->numOttBoxes"""), (
    """    if(config->bsTreeConfig != 7) {
      writeBits(bitstream, config->bsFrameLength, 7);
    }
    writeBits(bitstream, config->bsFreqRes, 3);
""",
    """    if(config->bsTreeConfig != 7) {
      writeBits(bitstream, mpsrefSamplingFrequencyIndex, 4);
      writeBits(bitstream, config->bsFrameLength, 7);
    }
    writeBits(bitstream, config->bsFreqRes, 3);
""")]


def fetch(target):
    if not os.path.exists(target):
        urllib.request.urlretrieve(URL, target)
    digest = hashlib.sha256(open(target, 'rb').read()).hexdigest()
    if digest != SHA256:
        raise SystemExit(target + ' has SHA-256 ' + digest + ', expected ' + SHA256)


def extract(archive, target):
    with zipfile.ZipFile(archive) as source:
        for name in source.namelist():
            if not name.startswith(ROOT) or name.endswith('/') or '..' in name.split('/'):
                continue
            path = os.path.join(target, *name[len(ROOT):].split('/'))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with source.open(name) as data, open(path, 'wb') as out:
                shutil.copyfileobj(data, out)


def patch(source, target, pairs):
    text = open(source, encoding='latin-1', newline='').read()
    crlf = '\r\n' in text
    for old, new in pairs:
        if crlf:
            old = old.replace('\n', '\r\n')
            new = new.replace('\n', '\r\n')
        if text.count(old) != 1:
            raise SystemExit(source + ': the patch anchor occurs ' + str(text.count(old)) + ' times:\n' + old)
        text = text.replace(old, new)
    open(target, 'w', encoding='latin-1', newline='').write(text)


def compile_all(sources, includes, flags, objects, prefix, replaced):
    built = []
    for name in sorted(os.listdir(sources)):
        if not name.endswith('.c'):
            continue
        source = replaced.get(name, os.path.join(sources, name))
        target = os.path.join(objects, prefix + name[:-2] + '.o')
        subprocess.check_call(['gcc', '-O2', '-fcommon', '-w', '-c'] + flags + includes + ['-o', target, source])
        built.append(target)
    return built


def build(work):
    download = os.path.join(work, 'download')
    os.makedirs(download, exist_ok=True)
    archive = os.path.join(download, 'ISO_IEC_23003-3_2019_Reference_Software.zip')
    fetch(archive)
    refsoft = os.path.join(work, 'refsoft')
    if not os.path.isdir(refsoft):
        extract(archive, refsoft)
    decoder = os.path.join(refsoft, 'mp4spatialdec')
    encoder = os.path.join(refsoft, 'mp4spatialenc')
    common = os.path.join(refsoft, 'usacEncDec')
    patched = os.path.join(work, 'patched')
    stub = os.path.join(work, 'stub')
    objects = os.path.join(work, 'obj')
    for directory in (patched, stub, objects):
        os.makedirs(directory, exist_ok=True)
    patch(os.path.join(decoder, 'src', 'sac_bitdec.c'), os.path.join(patched, 'sac_bitdec.c'), BITDEC_PATCHES)
    patch(os.path.join(encoder, 'src', 'bitstream.c'), os.path.join(patched, 'bitstream.c'), BITSTREAM_PATCHES)
    shutil.copy(os.path.join(SOURCES, 'libtsp.h'), stub)
    shared = ['-I' + os.path.join(common, 'src_frame'), '-I' + os.path.join(common, 'include'),
              '-I' + os.path.join(common, 'src_tf')]
    decoder_includes = ['-I' + stub, '-I' + os.path.join(decoder, 'include'), '-I' + os.path.join(decoder, 'src'),
                        '-I' + os.path.join(common, 'src_ic')] + shared
    encoder_includes = ['-I' + stub, '-I' + os.path.join(encoder, 'include'),
                        '-I' + os.path.join(encoder, 'src')] + shared
    built = compile_all(os.path.join(decoder, 'src'), decoder_includes, ['-DTP_BUG_FIX', '-DHRTF_DYNAMIC_UPDATE'],
                        objects, 'dec_', {'sac_bitdec.c': os.path.join(patched, 'sac_bitdec.c')})
    built += compile_all(os.path.join(encoder, 'src'), encoder_includes, ['-std=gnu89'], objects, 'enc_',
                         {'bitstream.c': os.path.join(patched, 'bitstream.c')})
    for name, includes in (('mpsref.c', decoder_includes + ['-I' + os.path.join(encoder, 'include'),
                                                           '-I' + os.path.join(encoder, 'src')]),
                           ('dump.c', decoder_includes), ('common.c', [])):
        target = os.path.join(objects, 'frontend_' + name[:-2] + '.o')
        subprocess.check_call(['gcc', '-O2', '-fcommon', '-w', '-c'] + includes
                              + ['-o', target, os.path.join(SOURCES, name)])
        built.append(target)
    executable = os.path.join(work, 'mpsref' + ('.exe' if os.name == 'nt' else ''))
    subprocess.check_call(['gcc', '-fcommon', '-o', executable] + built + ['-lm'])
    return executable, os.path.join(decoder, 'src')


def surround_signal(path, rate, seconds, seed):
    """Writes a 5.1 signal (L R C LFE Ls Rs) of tones, sweeps and noise bursts whose levels and panning change."""
    generator = random.Random(seed)
    frames = int(rate * seconds)
    smoothed = [0.0] * 6
    data = bytearray()
    for n in range(frames):
        t = n / rate
        common = 0.3 * math.sin(2 * math.pi * 220 * t)
        for c in range(6):
            tone = 0.25 * math.sin(2 * math.pi * (330 + 110 * c) * t + c)
            sweep = 0.1 * math.sin(2 * math.pi * (200 + 2000 * t / seconds * (c + 1) / 3) * t)
            weight = 1.0 / (8 + 4 * c)
            smoothed[c] += weight * (generator.uniform(-1.7, 1.7) - smoothed[c])
            bursts = 1.0 if (t * (2 + c)) % 1.0 < 0.08 else 0.0
            envelope = 0.5 + 0.5 * math.sin(2 * math.pi * (0.3 + 0.2 * c) * t + c)
            value = ((common * (0.8 if c != 3 else 0.2) + tone + sweep) * envelope + 0.4 * smoothed[c] * bursts) * 0.4
            if c == 3:
                value = 0.5 * common + 0.2 * math.sin(2 * math.pi * 60 * t)
            data += struct.pack('<h', max(-32768, min(32767, int(round(value * 32767)))))
    with open(path, 'wb') as out:
        out.write(b'RIFF' + struct.pack('<I', 36 + len(data)) + b'WAVEfmt '
                  + struct.pack('<IHHIIHH', 16, 1, 6, rate, rate * 12, 12, 16))
        out.write(b'data' + struct.pack('<I', len(data)) + data)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    work = os.path.abspath(sys.argv[1])
    os.makedirs(work, exist_ok=True)
    mpsref, tables = build(work)
    directory = os.path.join(testdata.conformance_dir(), 'mps-oracle')
    os.makedirs(directory, exist_ok=True)
    subprocess.check_call([sys.executable, '-I', os.path.join(SOURCES, 'mpsgen.py'), tables, directory])
    cases = sorted(name[:-3] for name in os.listdir(directory) if name.endswith('.bs') and not name.startswith('enc'))
    source = os.path.join(work, 'surround.wav')
    surround_signal(source, 48000, 2.0, 1)
    for tree, slots in ENCODES:
        name = 'enc%d_%d' % (tree, slots)
        subprocess.check_call([mpsref, 'enc', str(tree), source, os.path.join(directory, name + '_dmx.wav'),
                               os.path.join(directory, name + '.bs'), str(slots)])
        cases.append(name)
    for name in cases:
        subprocess.check_call([mpsref, 'dec', os.path.join(directory, name + '_dmx.wav'),
                               os.path.join(directory, name + '.bs'), os.path.join(directory, name + '_ref.wav')])
    for name in cases:
        digest = hashlib.sha256(open(os.path.join(directory, name + '.bs'), 'rb').read()).hexdigest()
        print(name, digest)
    print(len(cases), 'cases in', directory)


if __name__ == '__main__':
    main()
