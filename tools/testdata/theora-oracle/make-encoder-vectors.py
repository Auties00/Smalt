"""Records libtheora's decode of the streams Smalt's Theora encoder writes, for video's TheoraEncoderReferenceTest.

The smalt-*.packets resources (video/src/test/resources/com/github/auties00/smalt/video/theora) are written by
TheoraEncoderReferenceTest itself when it runs with -Dsmalt.test.updateDumps=true: Smalt's encoder output for the
synthetic clips of TheoraClips (a panning half-sample texture, a moving square, a noise patch and a scene cut), in
the packet listing smalt-theoradec reads. This script decodes each with smalt-theoradec (this directory: libtheora
1.2.0's decoder, BSD 3-Clause, post-processing off; see make-vectors.py for the build), writes the MD5 of every
decoded picture region to encoder-expected.tsv, and adds the SHA-256 of every listing to ../theora.sha256.

Usage: python -I make-encoder-vectors.py <smalt-theoradec> <resource directory> <scratch directory>
"""
import hashlib
import os
import struct
import subprocess
import sys


def frame_size(listing):
    """Returns the size of one decoded picture region, Y, Cb and Cr, from the identification header."""
    length = struct.unpack_from('<I', listing, 0)[0]
    header = listing[4:4 + length]
    width = int.from_bytes(header[14:17], 'big')
    height = int.from_bytes(header[17:20], 'big')
    pixel_format = header[41] >> 3 & 3
    chroma_width = width if pixel_format == 3 else (width + 1) // 2
    chroma_height = (height + 1) // 2 if pixel_format == 0 else height
    return width * height + 2 * chroma_width * chroma_height


def data_packets(listing):
    count, position = 0, 0
    while position < len(listing):
        length = struct.unpack_from('<I', listing, position)[0]
        position += 4 + length
        count += 1
    return count - 3


def main():
    oracle, resources, scratch = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(scratch, exist_ok=True)
    rows, digests = [], {}
    for name in sorted(os.listdir(resources)):
        if not (name.startswith('smalt-') and name.endswith('.packets')):
            continue
        path = os.path.join(resources, name)
        with open(path, 'rb') as f:
            listing = f.read()
        digests[name] = hashlib.sha256(listing).hexdigest()
        raw = os.path.join(scratch, name + '.yuv')
        subprocess.run([oracle, path, raw], check=True)
        with open(raw, 'rb') as f:
            decoded = f.read()
        size, frames = frame_size(listing), data_packets(listing)
        assert len(decoded) == size * frames, (name, len(decoded), size, frames)
        md5s = [hashlib.md5(decoded[i * size:(i + 1) * size]).hexdigest() for i in range(frames)]
        rows.append(name[len('smalt-'):-len('.packets')] + '\t' + ' '.join(md5s))
        os.remove(raw)
    with open(os.path.join(resources, 'encoder-expected.tsv'), 'w', newline='\n') as f:
        f.write('# libtheora 1.2.0 decode of the Smalt encoder streams; see make-encoder-vectors.py\n')
        for row in rows:
            f.write(row + '\n')
    digests['encoder-expected.tsv'] = hashlib.sha256(
        open(os.path.join(resources, 'encoder-expected.tsv'), 'rb').read()).hexdigest()
    pins = os.path.join(resources, '..', 'theora.sha256')
    with open(pins, 'r', newline='') as f:
        lines = [line for line in f.read().split('\n') if line]
    names = [line.split('  ', 1)[1] for line in lines]
    for name, digest in sorted(digests.items()):
        if name in names:
            lines[names.index(name)] = digest + '  ' + name
        else:
            lines.append(digest + '  ' + name)
    with open(pins, 'w', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
