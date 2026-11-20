#!/usr/bin/env python3
"""Writes the USAC streams of smalt-mpeg's UsacConcealmentTest.

Each stream is a deterministic synthetic signal (harmonic notes with decaying attacks, a bright noise floor and
short noise bursts, a different note sequence per channel) encoded by the libxaac encoder test application
(xaacenc, github.com/ittiam-systems/libxaac at commit 2fbadd57bca46693b7a077bcf1513131106d960c, Apache-2.0, built
with CMake, Ninja and gcc as tools/testdata/eld-sbr-oracle.py describes) with an audio pre-roll every second, so
that the streams hold independent access units after their first one. They are written as <name>.raw (the
AudioSpecificConfig followed by the access units) and <name>.meta (their sizes) into
mpeg/src/test/resources/com/github/auties00/smalt/audio/aac/usac/ and pinned in usac.sha256 there. The encoder
output is deterministic for a given build; a different digest is reported, not rejected, since the test only
compares Smalt with itself. The MPEG-D DRC stream needs libxaac's test/encoder/impd_drc_config_params.txt, which
the script copies from --drc-params into the encoder's working directory.

    python -I tools/testdata/usac-concealment/make-vectors.py <xaacenc> <checkout> --drc-params <file>
"""
import argparse
import hashlib
import math
import os
import shutil
import struct
import subprocess
import tempfile

STREAMS = {
    'fd-mono': (48000, 1, ['-usac:1', '-ccfl_idx:1', '-esbr:0', '-br:64000', '-nf:1']),
    'fd-stereo': (48000, 2, ['-usac:1', '-ccfl_idx:1', '-esbr:0', '-br:96000', '-nf:1', '-cmpx_pred:1']),
    'fd-768-esbr': (48000, 1, ['-usac:1', '-ccfl_idx:2', '-br:32000']),
    'lpd-mono': (16000, 1, ['-usac:2', '-ccfl_idx:1', '-br:16000']),
    'switched-esbr': (32000, 1, ['-usac:0', '-ccfl_idx:3', '-br:16000']),
    'pvc-esbr': (32000, 1, ['-usac:0', '-ccfl_idx:3', '-br:16000', '-pvc_enc:1']),
    'mps212': (48000, 2, ['-usac:0', '-mps:1', '-ccfl_idx:3', '-br:32000']),
    'drc': (48000, 1, ['-usac:1', '-ccfl_idx:3', '-br:32000', '-drc:1']),
}


def signal(rate, channels, seconds):
    notes = [[220, 330, 262, 392, 294, 440, 349, 523, 247, 370, 196, 415],
             [330, 196, 440, 262, 523, 294, 392, 220, 415, 247, 349, 370]]
    total = int(rate * seconds)
    planes = []
    seed = 12345
    for channel in range(channels):
        plane = [0.0] * total
        previous = 0.0
        for n in range(total):
            t = n / rate
            note = notes[channel][int(t * 2) % 12]
            age = t - int(t * 2) / 2
            envelope = 0.15 * math.exp(-3.0 * age) * min(1.0, age * 400)
            value = 0.0
            k = 1
            while k * note < min(15000, rate / 2 - 500) and k <= 40:
                value += math.sin(2 * math.pi * k * note * t + 0.3 * k) / k
                k += 1
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
            noise = seed / 0x3FFFFFFF - 1.0
            bright = noise - previous
            previous = noise
            burst = 0.12 if (t % 1.5) < 0.02 else 0.0
            plane[n] = envelope * value + 0.004 * bright + burst * noise
        planes.append(plane)
    return planes


def write_wav(path, planes, rate):
    channels = len(planes)
    body = bytearray()
    for n in range(len(planes[0])):
        for plane in planes:
            body += struct.pack('<h', max(-32768, min(32767, int(round(plane[n] * 32767)))))
    with open(path, 'wb') as out:
        out.write(b'RIFF' + struct.pack('<I', 36 + len(body)) + b'WAVEfmt ')
        out.write(struct.pack('<IHHIIHH', 16, 1, channels, rate, rate * channels * 2, channels * 2, 16))
        out.write(b'data' + struct.pack('<I', len(body)) + body)


def read_units(es, meta):
    data = open(es, 'rb').read()
    config_length = 0
    sizes = []
    for line in open(meta):
        key, _, value = line.strip().partition(':')
        if key == '-dec_info_init':
            config_length = int(value)
        elif key == '-ia_mp4_stsz_size':
            sizes.append(int(value))
    units = []
    position = config_length
    for size in sizes:
        units.append(data[position:position + size])
        position += size
    return data[:config_length], units


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('encoder')
    parser.add_argument('checkout')
    parser.add_argument('--drc-params', required=True)
    args = parser.parse_args()
    target = os.path.join(args.checkout, 'mpeg', 'src', 'test', 'resources', 'com', 'github', 'auties00', 'smalt',
                          'audio', 'aac', 'usac')
    os.makedirs(target, exist_ok=True)
    digests = []
    for name, (rate, channels, options) in STREAMS.items():
        work = tempfile.mkdtemp(prefix='usac-vectors-')
        try:
            shutil.copy(args.drc_params, os.path.join(work, 'impd_drc_config_params.txt'))
            write_wav(os.path.join(work, 'in.wav'), signal(rate, channels, 3), rate)
            subprocess.check_call([os.path.abspath(args.encoder), '-ifile:in.wav', '-ofile:out.es', '-aot:42',
                                   '-rap:1000'] + options, cwd=work, stdout=subprocess.DEVNULL)
            config, units = read_units(os.path.join(work, 'out.es'), os.path.join(work, 'out.txt'))
        finally:
            shutil.rmtree(work)
        independent = [i for i, unit in enumerate(units) if unit and unit[0] >> 7]
        if len(independent) < 3:
            raise SystemExit(name + ': only access units ' + str(independent) + ' are independent')
        raw = os.path.join(target, name + '.raw')
        with open(raw, 'wb') as out:
            out.write(config)
            for unit in units:
                out.write(unit)
        with open(os.path.join(target, name + '.meta'), 'w', newline='\n') as out:
            out.write('-dec_info_init:%d\n-g_track_count:1\n-ia_mp4_stsz_entries:%d\n-media_time_scale:%d\n'
                      % (len(config), len(units), rate))
            for unit in units:
                out.write('-ia_mp4_stsz_size:%d\n' % len(unit))
        for path in (raw, os.path.join(target, name + '.meta')):
            digests.append('%s  %s\n' % (hashlib.sha256(open(path, 'rb').read()).hexdigest(),
                                         os.path.basename(path)))
        print(name, len(units), 'access units, independent', independent)
    with open(os.path.join(target, 'usac.sha256'), 'w', newline='\n') as out:
        out.writelines(digests)


if __name__ == '__main__':
    main()
