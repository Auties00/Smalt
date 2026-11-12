#!/usr/bin/env python3
"""Writes the fixture of smalt-mpeg's CelpReferenceDifferentialTest, sampled from the ISO/IEC 14496-3 reference decoder.

No encoder writes MPEG-4 CELP and the ISO/IEC 14496-26 streams use a part of its configurations, but every field of a
CELP frame has a fixed width, so random bits make a valid stream of any configuration. This script generates those
frames (for every MPE_Configuration and RPE_Configuration, with fine rate control and silence compression, and for
bitrate and bandwidth scalable layers, alone and combined with those tools; streams that start in a non-active period
included), decodes them with the CELP core of the reference decoder, as distributed in
the ISO/IEC 23003-3 reference software (src_lpc), through a small frontend (harness.c, which replaces the MP4 and
bit stream layers), and writes the frame RMS and three samples of each frame, in 16-bit units, to the fixture. The
reference source is never redistributed: the first argument is the usacEncDec directory of the archive that
tools/testdata/mps-oracle.py downloads, and gcc builds it (on Windows from an MSYS2 UCRT64 shell).

The frames avoid what no conforming stream holds, since the reference and Smalt treat it differently: the RESERVED
combination of interpolation_flag and LPC_Present, LPC_Present 0 where no earlier frame carried the LSPs, the reserved
RPE fixed codebook gain index 31; and they keep the regular pulse gains small (Tables 3.88 to 3.90), since gains well
above 1 make the signal grow until it holds nothing but rounding errors. Fine rate control with a bandwidth scalable
layer is left out: the syntax allows it, but the reference decoder skips the layer's fields then and crashes or hangs.
The frame generator must match CelpReferenceDifferentialTest.frames.

    python -I tools/testdata/celp-oracle/celp-oracle.py <usacEncDec directory> <scratch directory>
"""
import array
import math
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPOSITORY = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
FIXTURE = os.path.join(REPOSITORY, 'mpeg', 'src', 'test', 'resources', 'com', 'github', 'auties00', 'smalt', 'audio',
                       'celp', 'reference-decoder.txt')
FRAMES = 16
UNIT_BYTES = 140
TX_PATTERN = [1, 1, 1, 1, 1, 2, 0, 0, 3, 0, 2, 3, 1, 1, 3, 0]
# Selected by the last letter of a case name: a stream that opens with a SID frame, one that opens without SID
# information, and periods of one frame.
TX_PATTERNS = {'b': [2, 0, 3, 3, 2, 3, 2, 0, 0, 3, 2, 2, 3, 0, 1, 1],
               'c': [0, 0, 3, 0, 2, 0, 0, 3, 1, 1, 1, 3, 2, 2, 2, 2],
               'd': [1, 2, 1, 3, 1, 0, 1, 2, 2, 1, 1, 0, 2, 1, 3, 3]}
OBJECTS = ('celp_decoder celp_bitstream_demux phi_post phi_lpc phi_xits phi_gxit phi_fbit phi_lsfr att_pc2lsf '
           'pan_lspqtz2_dd phi_priv att_abs_postp att_bwx att_firfilt att_iirfilt att_lsf2pc att_testbound nb_celp_dec '
           'pan_lsp_intp pan_lspdec pan_mv_data pan_sort pan_stab nec_abs_exc_generation nec_qrms_dec nec_exc_acb_dec '
           'nec_exc_mp_dec nec_exc_gain_dec nec_exc_mp_config nec_exc_module nec_enh_mp_dec nec_enh_gain_dec '
           'nec_bws_acb_dec nec_bws_exc_generation nec_bws_gain_dec nec_bws_module nec_bws_mp_dec nec_bws_qlsp_dec '
           'nec_bws_qrms_dec nec_cng_dec nec_cng_module nec_cng_lspvq nec_vad').split()


class Random:
    """A 32-bit xorshift generator seeded by the FNV-1a hash of a case name."""

    def __init__(self, name):
        state = 0x811C9DC5
        for character in name.encode('ascii'):
            state = ((state ^ character) * 0x01000193) & 0xFFFFFFFF
        self.state = state or 1

    def next(self):
        x = self.state
        x ^= (x << 13) & 0xFFFFFFFF
        x ^= x >> 17
        x ^= (x << 5) & 0xFFFFFFFF
        self.state = x
        return x


def set_bits(data, position, width, value):
    for i in range(width):
        bit = (value >> (width - 1 - i)) & 1
        at = position + i
        data[at >> 3] = (data[at >> 3] & ~(0x80 >> (at & 7))) | (bit * (0x80 >> (at & 7)))


def frames(name, object_type, rpe, fine_rate, silence, configuration, streams):
    """The access units of a case, frame by frame."""
    random = Random(name)
    pending = False
    result = []
    for frame in range(FRAMES):
        units = []
        for stream in range(streams):
            data = bytearray(random.next() & 0xFF for _ in range(UNIT_BYTES))
            if stream == 0:
                position = 0
                tx = 1
                if silence:
                    tx = TX_PATTERNS.get(name[-1], TX_PATTERN)[frame]
                    set_bits(data, 0, 2, tx)
                    position = 2
                lsp = True
                if fine_rate and tx == 1:
                    if pending:
                        flags = (0, 0)
                        pending = False
                    else:
                        flags = (1, 1) if random.next() & 1 else (0, 1)
                        pending = flags == (1, 1)
                    set_bits(data, position, 1, flags[0])
                    set_bits(data, position + 1, 1, flags[1])
                    position += 2
                    lsp = flags[1] == 1
                elif fine_rate:
                    pending = False
                if rpe and tx == 1:
                    subframes = (6, 4, 8, 10)[configuration]
                    if object_type == 8:
                        position += 46 if lsp else 0
                        shape = 11 if configuration < 2 else 12
                        for subframe in range(subframes):
                            set_bits(data, position + 8 + shape, 3, 0)
                            set_bits(data, position + 8 + shape + 6, 1, 0)
                            position += 8 + shape + 6 + (5 if subframe == 0 else 3)
                    else:
                        position += 15 if lsp else 0
                        for subframe in range(subframes):
                            set_bits(data, position, 3, 0)
                            set_bits(data, position + 3, 1, 0)
                            position += 3 + (2 if subframe == 0 else 1)
            units.append(bytes(data))
        result.append(units)
    return result


def cases():
    """Each case: name, object type, rpe, wideband, frc, silence, configuration, enhancement layers, bandwidth layer."""
    result = []
    for mode, count in (('nb', 28), ('wb', 32), ('rpe', 4)):
        rpe = int(mode == 'rpe')
        wide = int(mode != 'nb')
        for c in range(count):
            if mode == 'wb' and c in (7, 23):
                continue
            result.append((f'{mode}{c}', 8, rpe, wide, 0, 0, c, 0, -1))
            result.append((f'{mode}{c}f', 8, rpe, wide, 1, 0, c, 0, -1))
            result.append((f'{mode}{c}s', 24, rpe, wide, 0, 1, c, 0, -1))
            result.append((f'{mode}{c}fs', 24, rpe, wide, 1, 1, c, 0, -1))
    for c in (0, 3, 6, 12, 13, 21, 22, 26):
        result.append((f'nb{c}e3', 8, 0, 0, 0, 0, c, 3, -1))
    for c in (0, 8, 16, 24):
        result.append((f'wb{c}e3', 8, 0, 1, 0, 0, c, 3, -1))
    for c in (0, 3, 6, 13, 22, 26):
        for w in range(4):
            result.append((f'nb{c}b{w}', 8, 0, 0, 0, 0, c, 0, w))
            result.append((f'nb{c}b{w}s', 24, 0, 0, 0, 1, c, 0, w))
    result.append(('nb13e2b3', 8, 0, 0, 0, 0, 13, 2, 3))
    for c in (0, 13, 22):
        result.append((f'nb{c}e2f', 8, 0, 0, 1, 0, c, 2, -1))
        result.append((f'nb{c}e2s', 24, 0, 0, 0, 1, c, 2, -1))
        result.append((f'nb{c}e2fs', 24, 0, 0, 1, 1, c, 2, -1))
        result.append((f'nb{c}e3b1', 8, 0, 0, 0, 0, c, 3, 1))
        result.append((f'nb{c}e3b1p', 24, 0, 0, 0, 0, c, 3, 1))
    for c in (0, 8, 16, 24):
        result.append((f'wb{c}e2f', 8, 0, 1, 1, 0, c, 2, -1))
        result.append((f'wb{c}e2s', 24, 0, 1, 0, 1, c, 2, -1))
        result.append((f'wb{c}e2fs', 24, 0, 1, 1, 1, c, 2, -1))
        result.append((f'wb{c}p', 24, 0, 1, 0, 0, c, 0, -1))
        result.append((f'wb{c}fp', 24, 0, 1, 1, 0, c, 0, -1))
    for c in (0, 3, 6, 13, 22, 26):
        result.append((f'nb{c}e1b1s', 24, 0, 0, 0, 1, c, 1, 1))
    for c in (0, 13, 27):
        result.append((f'nb{c}p', 24, 0, 0, 0, 0, c, 0, -1))
        result.append((f'nb{c}fp', 24, 0, 0, 1, 0, c, 0, -1))
    for c in range(4):
        result.append((f'rpe{c}p', 24, 1, 1, 0, 0, c, 0, -1))
        result.append((f'rpe{c}fp', 24, 1, 1, 1, 0, c, 0, -1))
    for pattern in 'bcd':
        for c in (0, 13, 27):
            result.append((f'nb{c}s{pattern}', 24, 0, 0, 0, 1, c, 0, -1))
            result.append((f'nb{c}fs{pattern}', 24, 0, 0, 1, 1, c, 0, -1))
        for c in (0, 8, 16, 24):
            result.append((f'wb{c}s{pattern}', 24, 0, 1, 0, 1, c, 0, -1))
            result.append((f'wb{c}fs{pattern}', 24, 0, 1, 1, 1, c, 0, -1))
        for c in range(4):
            result.append((f'rpe{c}s{pattern}', 24, 1, 1, 0, 1, c, 0, -1))
            result.append((f'rpe{c}fs{pattern}', 24, 1, 1, 1, 1, c, 0, -1))
        for c in (0, 3, 13, 22):
            result.append((f'nb{c}e1b1s{pattern}', 24, 0, 0, 0, 1, c, 1, 1))
    return result


def build(source, scratch):
    objects = os.path.join(scratch, 'obj')
    os.makedirs(objects, exist_ok=True)
    include = ['-I' + os.path.join(source, d) for d in ('src_lpc', 'src_frame', 'src_tf', 'src_mp4', 'src_usac',
                                                        'src_par', 'src_sls')]
    flags = ['-w', '-O1', '-fcommon', '-DVERSION2', '-DCORRIGENDUM1'] + include
    for name in OBJECTS:
        output = os.path.join(objects, name + '.o')
        if not os.path.exists(output):
            subprocess.run(['gcc', '-std=gnu89'] + flags + ['-c', os.path.join(source, 'src_lpc', name + '.c'), '-o',
                                                            output], check=True)
    program = os.path.join(scratch, 'harness.exe')
    subprocess.run(['gcc'] + flags + [os.path.join(HERE, 'harness.c')]
                   + [os.path.join(objects, name + '.o') for name in OBJECTS] + ['-lm', '-o', program], check=True)
    return program


def main():
    source, scratch = sys.argv[1], sys.argv[2]
    os.makedirs(scratch, exist_ok=True)
    program = build(source, scratch)
    input_path = os.path.join(scratch, 'frames.bin')
    output_path = os.path.join(scratch, 'out.bin')
    lines = ['# Frame RMS and the samples at 1/4, 1/2 and 3/4 of each frame of the ISO/IEC 14496-3 reference decoder, in',
             '# 16-bit units; written by tools/testdata/celp-oracle/celp-oracle.py. Columns: name, object type, RPE,',
             '# 16 kHz, FineRateControl, SilenceCompression, configuration, enhancement layers, BWS_configuration or -1,',
             '# then the values.']
    for name, ot, rpe, wide, frc, silence, c, layers, bws in cases():
        streams = 1 + layers + (1 if bws >= 0 else 0)
        with open(input_path, 'wb') as out:
            for units in frames(name, ot, rpe, frc, silence, c, streams):
                for unit in units:
                    out.write(struct.pack('>H', 8 * len(unit)))
                    out.write(unit)
        subprocess.run([program, str(ot), str(rpe), str(wide), str(frc), str(silence), str(c), str(layers),
                        str(int(bws >= 0)), str(max(bws, 0)), '0', str(streams), input_path, output_path], check=True)
        samples = array.array('f')
        samples.frombytes(open(output_path, 'rb').read())
        size = len(samples) // FRAMES
        values = []
        for frame in range(FRAMES):
            block = samples[frame * size:(frame + 1) * size]
            values.append('%.0f' % math.sqrt(sum(x * x for x in block) / size))
            values.extend('%.0f' % block[size * k // 4] for k in (1, 2, 3))
        lines.append(' '.join(map(str, (name, ot, rpe, wide, frc, silence, c, layers, bws))) + ' ' + ' '.join(values))
    os.makedirs(os.path.dirname(FIXTURE), exist_ok=True)
    with open(FIXTURE, 'w', newline='\n', encoding='ascii') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
