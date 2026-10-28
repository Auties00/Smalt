#!/usr/bin/env python3
"""Writes the HEVC recovery point and field vectors of smalt-mpeg's HevcRandomAccessTest and HevcFieldTest.

Each vector is a short HEVC elementary stream coded from ffmpeg's testsrc2 pattern, with MD5 decoded picture hash SEI
messages on every picture:

    rp-ra            HM random access, intra pictures every 16 coded as TRAIL I pictures carrying recovery point SEI
                     messages (DecodingRefreshType 3) instead of CRA pictures
    rp-ld            HM low delay P, the same with intra pictures every 8
    field-ra-tff     HM random access field coding, top field first, picture timing SEI (pic_struct 1 and 2), fields
                     of 60 rows coded as 64 with a conformance window
    field-ld-bff-10  HM low delay field coding at 10 bits, bottom field first
    field-x265-bff   x265 field coding (ffmpeg -x265-params interlace=bff), bottom field first, with B pictures and
                     a CRA picture
    field-x265-ld-bff-10
                     x265 field coding at 10 bits without B pictures, bottom field first
    frame-timing     HM low delay progressive frames with picture timing SEI messages (pic_struct 0)

The field sources are interlaced: testsrc2 at 50 frames per second woven into 25 frames per second with ffmpeg's
tinterlace filter, top or bottom field first. The HM reference decoder (TAppDecoder) checks every hash while it
decodes each stream, and the script fails unless it reports every picture as matching. HM field coding writes
field_seq_flag 0 and marks the fields by their picture timing pic_struct, as the HM decoder reads them; x265 writes
field_seq_flag 1. HM's decoder weaves the fields of a stream that reorders them in another order than their output
order, so the frame order of a field vector is that of ffmpeg's decode (one field per picture, woven here two by two
in output order), which must hold the same frames as HM's output. For each vector, vectors.tsv next to the streams
holds:

    name    width    height    frames    bit depth    stream sha256    decoded sha256    decoding order POCs
    frame MD5s

where width, height and frames describe HM's output (woven frames for field coding), the decoded SHA-256 and the
comma-separated frame MD5s hash HM's output (8-bit samples, or 16-bit little-endian ones above 8 bits, planar, chroma
included) and the POCs are those HM reports, in decoding order.

Made with HM-18.0 (https://vcgit.hhi.fraunhofer.de/jvet/HM, tag HM-18.0, BSD-3-Clause, used as a black box) built with
gcc 15 (cmake -DCMAKE_BUILD_TYPE=Release, targets TAppEncoder and TAppDecoder), ffmpeg 7.1 and its libx265 4.0 (used
as a black-box encoder). Pass the HM source tree, whose bin directory holds the build:

    python -I tools/testdata/hevc-random-access/make-vectors.py <HM source tree> \\
        <mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/random>
"""
import hashlib
import os
import re
import subprocess
import sys
import tempfile

RA = 'encoder_randomaccess_main.cfg'
LD_P = 'encoder_lowdelay_P_main.cfg'
LD = 'encoder_lowdelay_main.cfg'
RA_FIELD = os.path.join('misc', 'encoder_randomaccess_main_field_coding.cfg')
LD_FIELD = os.path.join('misc', 'encoder_lowdelay_main_field_coding.cfg')

TIMING = ['--SEIPictureTiming=1', '--FrameFieldInfoPresentFlag=1', '--VuiParametersPresent=1',
          '--TargetBitrate=300000', '--RCCpbSize=300000']

# name: (encoder, configuration, width, height, frames, bit depth, field order (None, 'tff' or 'bff'), options)
VECTORS = {
    'rp-ra': ('hm', RA, 128, 96, 40, 8, None,
              ['--IntraPeriod=16', '--DecodingRefreshType=3', '--SEIRecoveryPoint=1']),
    'rp-ld': ('hm', LD_P, 128, 96, 24, 8, None,
              ['--IntraPeriod=8', '--DecodingRefreshType=3', '--SEIRecoveryPoint=1']),
    'field-ra-tff': ('hm', RA_FIELD, 128, 120, 10, 8, 'tff',
                     ['--TopFieldFirst=1', '--TemporalFilter=0', '--ConformanceWindowMode=1'] + TIMING),
    'field-ld-bff-10': ('hm', LD_FIELD, 128, 128, 8, 10, 'bff',
                        ['--TopFieldFirst=0', '--TemporalFilter=0', '--InputBitDepth=10', '--InternalBitDepth=10',
                         '--Profile=main10'] + TIMING),
    'field-x265-bff': ('x265', None, 128, 128, 8, 8, 'bff', ['keyint=8']),
    'field-x265-ld-bff-10': ('x265', None, 128, 128, 8, 10, 'bff', ['bframes=0']),
    'frame-timing': ('hm', LD, 128, 96, 6, 8, None, ['--TemporalFilter=0'] + TIMING),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def frame_md5s(data, width, height, depth):
    size = width * height * 3 // 2 * (2 if depth > 8 else 1)
    if len(data) % size:
        raise SystemExit('decoded size %d is not a whole number of %dx%d frames' % (len(data), width, height))
    return [hashlib.md5(data[k:k + size]).hexdigest() for k in range(0, len(data), size)]


def ffmpeg_frame_md5s(stream, width, height, depth, order, pixel_format):
    """Decodes a field stream with ffmpeg, which outputs one picture per field, and weaves the fields two by two."""
    fields = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-i', stream, '-f', 'rawvideo', '-pix_fmt',
                             pixel_format, '-'], check=True, capture_output=True).stdout
    sample = 2 if depth > 8 else 1
    field_height = height // 2
    planes = [(width * sample, field_height), (width // 2 * sample, field_height // 2),
              (width // 2 * sample, field_height // 2)]
    size = sum(row * rows for row, rows in planes)
    pictures = [fields[k:k + size] for k in range(0, len(fields), size)]
    md5s = []
    for k in range(0, len(pictures) - 1, 2):
        first, second = pictures[k], pictures[k + 1]
        top, bottom = (first, second) if order == 'tff' else (second, first)
        frame = bytearray()
        offset = 0
        for row, rows in planes:
            for line in range(rows):
                frame += top[offset + line * row:offset + (line + 1) * row]
                frame += bottom[offset + line * row:offset + (line + 1) * row]
            offset += row * rows
        md5s.append(hashlib.md5(frame).hexdigest())
    return md5s


def configuration(source, work, name):
    """Copies an HM configuration without the options HM-18.0 no longer knows, which some misc files carry."""
    target = os.path.join(work, name + '.cfg')
    with open(source) as reader:
        lines = reader.readlines()
    with open(target, 'w') as writer:
        writer.writelines(line for line in lines if not re.match(r'(ConformanceMode|PaddingLuma|PaddingChroma)\b',
                                                                 line))
    return target


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    source_tree = os.path.abspath(sys.argv[1])
    target = os.path.abspath(sys.argv[2])
    configurations = os.path.join(source_tree, 'cfg')
    suffix = '.exe' if os.name == 'nt' else ''
    executables = {}
    for root, _, files in os.walk(os.path.join(source_tree, 'bin')):
        for executable in ('TAppEncoder', 'TAppDecoder'):
            if executable + suffix in files:
                executables[executable] = os.path.join(root, executable + suffix)
    if len(executables) != 2 or not os.path.isdir(configurations):
        raise SystemExit('no HM build (bin/**/TAppEncoder, TAppDecoder and cfg) in ' + source_tree)
    os.makedirs(target, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name, (encoder, config, width, height, frames, depth, order, options) in VECTORS.items():
            pixel_format = 'yuv420p10le' if depth > 8 else 'yuv420p'
            pattern = 'testsrc2=size=%dx%d:rate=25' % (width, height)
            if order is not None:
                pattern = 'testsrc2=size=%dx%d:rate=50,tinterlace=%s' % (
                    width, height, 'interleave_top' if order == 'tff' else 'interleave_bottom')
            source = os.path.join(work, name + '.yuv')
            subprocess.check_call(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-f', 'lavfi', '-i', pattern,
                                   '-frames:v', str(frames), '-pix_fmt', pixel_format, '-f', 'rawvideo', source])
            stream = os.path.join(target, name + '.hevc')
            if encoder == 'hm':
                subprocess.check_call([executables['TAppEncoder'],
                                       '-c', configuration(os.path.join(configurations, config), work, name),
                                       '-i', source, '-wdt', str(width), '-hgt', str(height),
                                       '-fr', '50' if order else '25', '-f', str(frames), '--SEIDecodedPictureHash=1',
                                       '-q', '32', '-b', stream, '-o', os.path.join(work, name + '.recon')] + options,
                                      stdout=subprocess.DEVNULL)
            else:
                # libx265 takes field coded input one field per picture, in display order
                subprocess.check_call(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt',
                                       pixel_format, '-s', '%dx%d' % (width, height), '-r', '25', '-i', source,
                                       '-vf', 'setfield=%s,separatefields' % order,
                                       '-c:v', 'libx265', '-x265-params',
                                       ':'.join(['interlace=' + order, 'hash=1', 'log-level=error'] + options),
                                       '-f', 'hevc', stream])
            decoded = os.path.join(work, name + '.decoded')
            log = subprocess.run([executables['TAppDecoder'], '-b', stream, '-o', decoded], check=True,
                                 capture_output=True, text=True).stdout
            pocs = [int(match) for match in re.findall(r'^POC\s+(-?\d+)', log, re.MULTILINE)]
            pictures = frames * 2 if order else frames
            if log.count('(OK)') != pictures or len(pocs) != pictures or 'MD5:' not in log or '***ERROR***' in log:
                raise SystemExit(name + ': HM did not verify every hash:\n' + log)
            with open(decoded, 'rb') as reader:
                output = reader.read()
            md5s = frame_md5s(output, width, height, depth)
            if order is not None:
                woven = ffmpeg_frame_md5s(stream, width, height, depth, order, pixel_format)
                if sorted(woven) != sorted(md5s):
                    raise SystemExit(name + ': ffmpeg and HM decode different frames')
                md5s = woven
            if len(md5s) != frames:
                raise SystemExit('%s: HM output %d frames, expected %d' % (name, len(md5s), frames))
            with open(stream, 'rb') as reader:
                stream_hash = sha256(reader.read())
            rows.append('\t'.join([name, str(width), str(height), str(frames), str(depth), stream_hash,
                                   sha256(output), ','.join(map(str, pocs)), ','.join(md5s)]))
    with open(os.path.join(target, 'vectors.tsv'), 'w', newline='\n') as sink:
        sink.write('# Written by tools/testdata/hevc-random-access/make-vectors.py: name, width, height, frames, bit '
                   'depth, SHA-256 of the\n# stream, SHA-256 of the HM-18.0 reference decoder output, POCs in '
                   'decoding order, MD5 of each output frame.\n')
        for row in rows:
            sink.write(row + '\n')
    print('wrote %d vectors to %s' % (len(rows), target))


if __name__ == '__main__':
    main()
