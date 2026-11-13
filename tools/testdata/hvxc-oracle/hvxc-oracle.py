#!/usr/bin/env python3
"""Writes the fixture of smalt-mpeg's HvxcReferenceDifferentialTest, sampled from the ISO/IEC 14496-3 reference decoder.

No encoder writes MPEG-4 HVXC and the ISO/IEC 14496-26 streams cover few of its paths with the random number generator
on, but every field of an HVXC frame has a fixed width, so random bits make a valid frame of any mode. This script
generates frames (fixed rate at 2, 3.7 and 4 kbit/s, variable rate at 2 and 4 kbit/s with background noise frames,
the error resilient bit order, the scalable 2 + 2 kbit/s mode with frames that lack the enhancement layer, the error
concealment driven by class CRC failures, the low delay mode, speed and pitch change, and the test modes), decodes
them with the HVXC core of the reference decoder, as distributed in the ISO/IEC 23003-3 reference software
(src_par), through a small frontend (harness.c, which replaces the MP4 and bit stream layers and feeds the CRC
results), and writes the RMS and three samples of each 160-sample block, in 16-bit units, to the fixture. The
reference source is never redistributed: the first argument is the usacEncDec directory of the archive that
tools/testdata/mps-oracle.py downloads, and gcc builds it (on Windows from an MSYS2 UCRT64 shell). The frame
generator must match HvxcReferenceDifferentialTest.frames.

    python -I tools/testdata/hvxc-oracle/hvxc-oracle.py <usacEncDec directory> <scratch directory>
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
                       'hvxc', 'reference-decoder.txt')
FRAMES = 30
SOURCES = ['src_par/' + name for name in ('hvxcDec', 'hvxcCommon', 'hvxcFltDec', 'hvxcItpDec', 'hvxcProtect',
                                          'hvxcQAmDec', 'hvxcQLspDec', 'hvxcSynUVDec', 'hvxcSynVDec',
                                          'hvxcUVExtGenDec', 'hvxcVExtGenDec', 'hvxcVxcDec')] \
    + ['src_lpc/' + name for name in ('pan_lspdec', 'pan_stab', 'pan_sort')]


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


def cases():
    """Yields name, varMode, rateMode, extension, scalable, epFlag, delayMode, testMode, speed, pitch, enhancement.

    The enhancement column is 0 for no enhancement layer, 1 for one in every frame and 2 for one in some frames; the
    case decodes 4 kbit/s frames where it has one."""
    for rate, label in ((0, '2k'), (1, '4k'), (2, '3k')):
        for extension in (0, 1):
            for delay in (1, 0):
                yield ('fix%s_e%d_d%d' % (label, extension, delay), 0, rate, extension, 0, 0, delay, 0, 1.0, 1.0, 0)
        yield ('fix%s_tm1' % label, 0, rate, 0, 0, 0, 1, 1, 1.0, 1.0, 0)
        yield ('fix%s_tm6' % label, 0, rate, 1, 0, 0, 0, 6, 1.0, 1.0, 0)
        yield ('fix%s_speed' % label, 0, rate, 0, 0, 0, 1, 0, 0.6, 1.0, 0)
        yield ('fix%s_fast' % label, 0, rate, 1, 0, 0, 0, 0, 1.7, 1.3, 0)
        yield ('fix%s_pitch' % label, 0, rate, 0, 0, 0, 0, 0, 1.0, 0.7, 0)
        yield ('fix%s_ep' % label, 0, rate, 1, 0, 1, 1, 0, 1.0, 1.0, 0)
        yield ('fix%s_ep_ld' % label, 0, rate, 1, 0, 1, 0, 0, 0.8, 1.0, 0)
    for extension in (0, 1):
        for delay in (1, 0):
            yield ('scal_e%d_d%d' % (extension, delay), 0, 0, extension, extension, 0, delay, 0, 1.0, 1.0, 1)
    yield 'scal_some', 0, 0, 1, 1, 0, 1, 0, 1.0, 1.0, 2
    yield 'scal_ep', 0, 0, 1, 1, 1, 1, 0, 1.0, 1.0, 1
    for extension in (0, 1):
        for delay in (1, 0):
            yield ('var2k_e%d_d%d' % (extension, delay), 1, 0, extension, 0, 0, delay, 0, 1.0, 1.0, 0)
            yield ('var4k_e%d_d%d' % (extension, delay), 1, 1, extension, 0, 0, delay, 0, 1.0, 1.0, 0)
    yield 'var2k_tm8', 1, 0, 0, 0, 0, 1, 8, 1.0, 1.0, 0
    yield 'var2k_speed', 1, 0, 0, 0, 0, 1, 0, 0.5, 1.2, 0
    yield 'var4k_speed', 1, 1, 1, 0, 0, 0, 0, 1.4, 0.9, 0
    yield 'varscal_base', 1, 0, 1, 1, 0, 1, 0, 1.0, 1.0, 0
    yield 'varscal_both', 1, 0, 1, 1, 0, 1, 0, 1.0, 1.0, 1
    yield 'varscal_some', 1, 0, 1, 1, 0, 0, 0, 0.9, 1.0, 2
    yield 'var4k_ep', 1, 1, 1, 0, 1, 1, 0, 1.0, 1.0, 0


def frames(name, variable, enhancement, error_protection):
    """Yields whether the frame has the enhancement layer, the CRC failure mask and the ten bytes of each frame.

    A variable rate frame of background noise (VUV 1) only follows unvoiced frames, as an encoder writes them: after
    a voiced frame the reference decoder reads the background noise frame's fields as voiced parameters, through a
    union, and its spectral shape indices then run past its tables. Such a frame becomes unvoiced (VUV 0)."""
    random = Random(name)
    previous = 0
    for frame in range(FRAMES):
        data = bytearray(random.next() & 0xFF for _ in range(10))
        extra = random.next()
        if variable:
            vuv = data[0] >> 6
            if vuv == 1 and previous != 0:
                data[0] &= 0x3F
                vuv = 0
            if vuv != 1:
                previous = vuv
        enhanced = enhancement == 1 or (enhancement == 2 and extra & 1)
        crc = (extra >> 8) & 0x1F if error_protection and (extra >> 4) % 4 == 0 else 0
        yield enhanced, crc, bytes(data)


def build(source, scratch):
    objects = os.path.join(scratch, 'obj')
    os.makedirs(objects, exist_ok=True)
    include = ['-I' + os.path.join(source, d) for d in ('src_par', 'src_lpc', 'src_frame', 'src_tf', 'src_mp4')]
    flags = ['-w', '-O1', '-fcommon', '-DVERSION2', '-DCORRIGENDUM1'] + include
    names = []
    for path in SOURCES:
        name = os.path.basename(path)
        output = os.path.join(objects, name + '.o')
        if not os.path.exists(output):
            subprocess.run(['gcc', '-std=gnu89'] + flags + ['-c', os.path.join(source, path + '.c'), '-o', output],
                           check=True)
        names.append(output)
    program = os.path.join(scratch, 'harness.exe')
    subprocess.run(['gcc'] + flags + [os.path.join(HERE, 'harness.c')] + names + ['-lm', '-o', program], check=True)
    return program


def main():
    source, scratch = sys.argv[1], sys.argv[2]
    os.makedirs(scratch, exist_ok=True)
    program = build(source, scratch)
    input_path = os.path.join(scratch, 'frames.bin')
    output_path = os.path.join(scratch, 'out.bin')
    lines = ['# RMS and the samples at 1/4, 1/2 and 3/4 of each 160-sample block of the ISO/IEC 14496-3 reference',
             '# decoder, in 16-bit units, before the decoder delay is dropped; written by',
             '# tools/testdata/hvxc-oracle/hvxc-oracle.py. Columns: name, varMode, rateMode, extensionFlag, scalable,',
             '# error protection, delay mode, test mode, speed, pitch, enhancement, then the values.']
    for case in cases():
        name, variable, rate, extension, scalable, ep, delay, test, speed, pitch, enhancement = case
        with open(input_path, 'wb') as out:
            for enhanced, crc, data in frames(name, variable, enhancement, ep):
                out.write(bytes((1 if enhanced else rate, crc)))
                out.write(data)
        subprocess.run([program, str(variable), str(rate), str(extension), str(scalable), str(ep), str(delay),
                        str(test), repr(speed), repr(pitch), input_path, output_path], check=True)
        raw = open(output_path, 'rb').read()
        samples = array.array('f')
        position = 0
        while position < len(raw):
            count = struct.unpack_from('<i', raw, position)[0]
            position += 4
            samples.frombytes(raw[position:position + 4 * count])
            position += 4 * count
        values = []
        for block in range(len(samples) // 160):
            part = samples[block * 160:(block + 1) * 160]
            values.append('%.2f' % math.sqrt(sum(x * x for x in part) / 160))
            values.extend('%.0f' % part[160 * k // 4] for k in (1, 2, 3))
        lines.append(' '.join(map(str, case)) + ' ' + ' '.join(values))
    os.makedirs(os.path.dirname(FIXTURE), exist_ok=True)
    with open(FIXTURE, 'w', newline='\n', encoding='ascii') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
