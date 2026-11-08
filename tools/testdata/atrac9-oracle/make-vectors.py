"""Computes the expected decodes of the ATRAC9 test vectors of audio/src/test/resources/com/github/auties00/smalt/audio/atrac9.

The vectors (<name>.a9: the 4-byte configuration word followed by the superframes) are written by the opt-in test
Atrac9VectorWriter (mvn -pl audio test -Dtest=Atrac9VectorWriter -Dsmalt.atrac9.writeVectors=true), from the
syntax-random writer and the signal encoder of Atrac9TestStreams: no permissively licensed ATRAC9 encoder or sample
exists. This script decodes each with smalt-at9dec (this directory: LibAtrac9 at commit efca2e3, MIT, built without
fused multiply-adds by tools/testdata/oracles.py) and writes expected.tsv: the name, the SHA-256 of the vector, the
number of superframes decoded and the MD5 of the interleaved signed 16-bit little-endian samples.

Usage: python -I make-vectors.py <smalt-at9dec> <vector directory>
"""
import hashlib
import os
import subprocess
import sys
import tempfile


def main():
    oracle, directory = sys.argv[1], sys.argv[2]
    rows = []
    for name in sorted(os.listdir(directory)):
        if not name.endswith('.a9'):
            continue
        path = os.path.join(directory, name)
        with tempfile.TemporaryDirectory() as work:
            output = os.path.join(work, 'out.pcm')
            status = subprocess.run([oracle, path, output], check=True, capture_output=True, text=True).stdout.split()
            if status[0] != 'ok':
                raise SystemExit(name + ': the reference rejects the vector: ' + ' '.join(status))
            pcm = open(output, 'rb').read()
        data = open(path, 'rb').read()
        rows.append('\t'.join([name[:-3], hashlib.sha256(data).hexdigest(), status[1], hashlib.md5(pcm).hexdigest()]))
    with open(os.path.join(directory, 'expected.tsv'), 'w', newline='\n') as out:
        out.write('# name\tsha256 of <name>.a9\tsuperframes\tmd5 of the LibAtrac9 decode (s16le, interleaved)\n')
        out.write('\n'.join(rows) + '\n')


if __name__ == '__main__':
    main()
