#!/usr/bin/env python3
"""Writes the VVC vectors of smalt-mpeg's VvcDecoderTest, VvcRobustnessTest and the I/O behavior harness.

Each vector is a short VVC elementary stream coded by the VVenC encoder (vvencapp, The Clear BSD License, used as a
black-box encoder) from ffmpeg's testsrc2 pattern, with decoded picture hash SEI messages. The VVdeC decoder
(vvdecapp, The Clear BSD License, used as a black-box decoder) checks every hash while it decodes the stream, and the
script fails unless it reports every picture as matching; the SHA-256 of the stream and of VVdeC's output (8-bit samples, or 16-bit
little-endian ones above 8 bits, planar, luma only for 4:0:0) and the frame count go into vvc.tsv next to the
streams:

    name    frames    stream sha256    decoded sha256    decoded md5

Made with VVenC and VVdeC at the master commits of 2026-10-29 (https://github.com/fraunhoferhhi/vvenc and
https://github.com/fraunhoferhhi/vvdec, built with CMake, Ninja and MSYS2 UCRT64 gcc 15, release configuration) and
ffmpeg 7.1. The concatenated vectors join two streams coded alike from consecutive seconds of the pattern; the
second stream of 'no-output-420-8bit' comes from a VVenC build with vvenc-no-output-of-prior-pics.patch applied, which
sets sh_no_output_of_prior_pics_flag in IDR pictures when the VVENC_NO_OUTPUT_OF_PRIOR_PICS environment variable is
set. VVdeC's application outputs every picture regardless of that flag, so the expected output of that vector is
VVdeC's without the pictures of the first stream still waiting for output when the second stream's IDR picture
empties the decoded picture buffer (H.266 clause C.5.2.2): with dpb_max_num_reorder_pics 5, the first stream's
pictures 4 to 8 in output order. Pass the directories holding vvencapp and vvdecapp and the output directory:

    python -I tools/testdata/vvc-vectors/make-vectors.py <vvenc bin> <vvdec bin> <mpeg/src/test/resources/com/github/auties00/smalt/video/vvc>
"""
import hashlib
import os
import subprocess
import sys
import tempfile

# name: (width, height, frames, ffmpeg pixel format, vvencapp input format, hash type (1 MD5, 2 CRC, 3 checksum),
#        vvencapp options)
VECTORS = {
    'ra-420-10bit': (136, 104, 12, 'yuv420p10le', 'yuv420_10', 1, ['--preset', 'faster', '--qp', '32']),
    'ld-420-8bit': (128, 96, 10, 'yuv420p', 'yuv420', 2, ['--preset', 'faster', '--qp', '30',
                                                          '--internal-bitdepth', '8', '--refreshtype', 'none', '--additional',
                                                          'GOPSize=8 PicReordering=0']),
    'ld-420-10bit': (128, 96, 10, 'yuv420p10le', 'yuv420_10', 1, ['--preset', 'faster', '--qp', '30', '--refreshtype',
                                                                 'none', '--additional', 'GOPSize=8 PicReordering=0']),
    'intra-400-10bit': (104, 72, 3, 'gray10le', 'yuv400_10', 3, ['--preset', 'fast', '--qp', '27',
                                                                  '--intraperiod', '1']),
    'tiles-420-10bit': (256, 128, 6, 'yuv420p10le', 'yuv420_10', 1, ['--preset', 'fast', '--qp', '34',
                                                                      '--tiles', '2x1', '--level', '4.1']),
    'hdr-420-10bit': (128, 64, 4, 'yuv420p10le', 'yuv420_10', 1, ['--preset', 'faster', '--qp', '35',
                                                                   '--hdr', 'pq_2020', '--range', 'full']),
    'wpp-420-10bit': (256, 256, 6, 'yuv420p10le', 'yuv420_10', 1, ['--preset', 'faster', '--qp', '32', '--additional',
                                                                    'WaveFrontSynchro=1']),
}

# name: (width, height, frames per part, vvencapp options, whether the second part sets
#        sh_no_output_of_prior_pics_flag, the indexes of VVdeC's output frames the flag discards)
CONCATENATED = {
    'concat-420-8bit': (128, 96, 9, ['--preset', 'faster', '--qp', '30', '--internal-bitdepth', '8',
                                     '--refreshtype', 'idr'], False, []),
    'no-output-420-8bit': (128, 96, 9, ['--preset', 'faster', '--qp', '30', '--internal-bitdepth', '8',
                                        '--refreshtype', 'idr'], True, [4, 5, 6, 7, 8]),
}


def run(arguments, **kwargs):
    result = subprocess.run(arguments, capture_output=True, text=True, **kwargs)
    if result.returncode != 0:
        sys.exit('command failed: ' + ' '.join(arguments) + '\n' + result.stdout + result.stderr)
    return result


def sha256(path):
    with open(path, 'rb') as file:
        return hashlib.sha256(file.read()).hexdigest()


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    encoder = os.path.join(sys.argv[1], 'vvencapp')
    decoder = os.path.join(sys.argv[2], 'vvdecapp')
    output = sys.argv[3]
    os.makedirs(output, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as scratch:
        for name, (width, height, frames, pixel_format, input_format, hash_type, options) in VECTORS.items():
            source = os.path.join(scratch, name + '.yuv')
            run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=%dx%d:rate=25' % (width, height),
                 '-frames:v', str(frames), '-pix_fmt', pixel_format, '-f', 'rawvideo', source])
            stream = os.path.join(output, name + '.vvc')
            run([encoder, '-v', '1', '--stats', '0', '-i', source, '-s', '%dx%d' % (width, height), '-c',
                 input_format, '-r', '25', '-f', str(frames), '--qpa', '0', '-t', '1', '-dph', str(hash_type),
                 '-o', stream] + options)
            decoded = os.path.join(scratch, name + '.out.yuv')
            result = run([decoder, '-v', '4', '-t', '1', '-dph', '-b', stream, '-o', decoded])
            log = result.stdout + result.stderr
            if log.count(',(OK)]') != frames or 'ERROR' in log.upper():
                sys.exit('VVdeC does not verify every picture hash of ' + name + '\n' + log)
            if pixel_format.startswith('gray'):
                decoded = strip_chroma(decoded, width, height, frames, pixel_format.endswith('10le'))
            with open(decoded, 'rb') as file:
                samples = file.read()
            sample_size = 1 if pixel_format in ('yuv420p', 'gray') else 2
            frame_size = width * height * sample_size * (1 if pixel_format.startswith('gray') else 3) // (
                1 if pixel_format.startswith('gray') else 2)
            if len(samples) != frame_size * frames:
                sys.exit('VVdeC wrote %d bytes for %s, not %d frames of %d' % (len(samples), name, frames,
                                                                               frame_size))
            rows.append('\t'.join([name, str(frames), sha256(stream), hashlib.sha256(samples).hexdigest(),
                                   hashlib.md5(samples).hexdigest()]))
        for name, (width, height, frames, options, no_output, discarded) in CONCATENATED.items():
            stream = os.path.join(output, name + '.vvc')
            with open(stream, 'wb') as joined:
                for part in range(2):
                    source = os.path.join(scratch, '%s.%d.yuv' % (name, part))
                    run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                         'testsrc2=size=%dx%d:rate=25' % (width, height), '-ss', str(part), '-frames:v', str(frames),
                         '-pix_fmt', 'yuv420p', '-f', 'rawvideo', source])
                    coded = os.path.join(scratch, '%s.%d.vvc' % (name, part))
                    environment = dict(os.environ)
                    environment.pop('VVENC_NO_OUTPUT_OF_PRIOR_PICS', None)
                    if no_output and part == 1:
                        environment['VVENC_NO_OUTPUT_OF_PRIOR_PICS'] = '1'
                    run([encoder, '-v', '1', '--stats', '0', '-i', source, '-s', '%dx%d' % (width, height), '-c',
                         'yuv420', '-r', '25', '-f', str(frames), '--qpa', '0', '-t', '1', '-dph', '1', '-o', coded]
                        + options, env=environment)
                    with open(coded, 'rb') as file:
                        joined.write(file.read())
            decoded = os.path.join(scratch, name + '.out.yuv')
            result = run([decoder, '-v', '4', '-t', '1', '-dph', '-b', stream, '-o', decoded])
            log = result.stdout + result.stderr
            if log.count(',(OK)]') != 2 * frames or 'ERROR' in log.upper():
                sys.exit('VVdeC does not verify every picture hash of ' + name + '\n' + log)
            frame_size = width * height * 3 // 2
            with open(decoded, 'rb') as file:
                samples = file.read()
            if len(samples) != frame_size * 2 * frames:
                sys.exit('VVdeC wrote %d bytes for %s, not %d frames' % (len(samples), name, 2 * frames))
            kept = b''.join(samples[index * frame_size:(index + 1) * frame_size] for index in range(2 * frames)
                            if index not in discarded)
            rows.append('\t'.join([name, str(2 * frames - len(discarded)), sha256(stream),
                                   hashlib.sha256(kept).hexdigest(), hashlib.md5(kept).hexdigest()]))
    with open(os.path.join(output, 'vvc.tsv'), 'w', newline='\n') as file:
        file.write('# name\tframes\tstream sha256\tdecoded sha256\tdecoded md5\n')
        file.write('\n'.join(rows) + '\n')


def strip_chroma(path, width, height, frames, deep):
    """Keeps the luma plane of each frame VVdeC writes for a 4:0:0 stream, when it writes chroma too."""
    size = 2 if deep else 1
    luma = width * height * size
    frame = luma * 3 // 2
    with open(path, 'rb') as file:
        data = file.read()
    if len(data) == luma * frames:
        return path
    stripped = path + '.luma'
    with open(stripped, 'wb') as file:
        for index in range(frames):
            file.write(data[index * frame:index * frame + luma])
    return stripped


if __name__ == '__main__':
    main()
