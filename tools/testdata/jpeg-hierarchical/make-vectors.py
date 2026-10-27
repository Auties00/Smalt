"""Writes the hierarchical JPEG test vectors of smalt-image.

Usage: python -I make-vectors.py <referenceA jpeg> <output directory>

<referenceA jpeg> is the command line codec of the ISO/IEC 10918-7 reference software A (Thomas Richter's
libjpeg), run as a black box under its evaluation and conformance-testing license: the only encoder of
hierarchical JPEG at hand. For every case of CASES it codes a synthetic PNM image (the synthetic() of
../jpegls/make-vectors.py) and decodes the result into its component planes (-U). The output directory receives:

  <name>.jpg           the hierarchical stream
  <name>.raw           for the DCT-coded cases, reference A's decoded component planes, one after another, each
                       sample an unsigned 16-bit little-endian value; for the DCT cases with a final lossless frame,
                       the source with the reference of that frame clamped (t81decode.clamped_reference), which
                       reference A's own decode leaves unclamped
  hierarchical.tsv     one row per case: its options, the SHA-256 of reference A's decoded planes (the source image
                       for the 16-bit lossless cases, which reference A cannot decode), of the T.81 reconstruction of
                       the lossless hierarchies (t81decode.py; "-" for the others) and of the source image

An arithmetic-coded case named after a Huffman-coded one plus "-arith" codes the same source image: reference A
computes the same differences for both, so the two decode to the same samples and share the T.81 reconstruction.
  hierarchical.sha256  the SHA-256 of every file above

The output is deterministic.
"""
import hashlib
import importlib.util
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('jpegls_vectors', os.path.join(HERE, '..', 'jpegls', 'make-vectors.py'))
jpegls = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jpegls)
spec = importlib.util.spec_from_file_location('t81decode', os.path.join(HERE, 't81decode.py'))
t81decode = importlib.util.module_from_spec(spec)
spec.loader.exec_module(t81decode)

# name, components, bits, width, height, lossless, options
CASES = [
    ('dct8-gray-2', 1, 8, 41, 29, False, ['-q', '75', '-y', '2', '-h']),
    ('dct8-rgb-2', 3, 8, 41, 29, False, ['-q', '75', '-y', '2', '-h', '-c']),
    ('dct8-ycc420-2', 3, 8, 41, 29, False, ['-q', '80', '-y', '2', '-h', '-s', '1x1,2x2,2x2']),
    ('dct8-gray-3-arith', 1, 8, 41, 29, False, ['-q', '75', '-y', '3', '-a']),
    ('dct8-gray-2-prog', 1, 8, 41, 29, False, ['-q', '75', '-y', '2', '-v', '-h']),
    ('dct8-gray-2-prog-arith', 1, 8, 41, 29, False, ['-q', '75', '-y', '2', '-v', '-a']),
    ('dct8-gray-2-ri3', 1, 8, 41, 29, False, ['-q', '75', '-y', '2', '-h', '-z', '3']),
    ('dct8-gray-2-dnl', 1, 8, 41, 29, False, ['-q', '75', '-y', '2', '-h', '-n']),
    ('dct12-gray-2', 1, 12, 41, 29, False, ['-q', '75', '-y', '2', '-h']),
    ('dct12-gray-3-arith', 1, 12, 41, 29, False, ['-q', '75', '-y', '3', '-a']),
    ('dct8-gray-final-lossless', 1, 8, 67, 45, True, ['-q', '75', '-y', '1', '-h']),
    ('dct8-rgb-same-size-lossless', 3, 8, 67, 45, True, ['-q', '75', '-y', '0', '-c', '-h']),
    ('dct8-rgb-same-size-lossless-arith', 3, 8, 67, 45, True, ['-q', '75', '-y', '0', '-c', '-a']),
    ('dct12-gray-final-lossless', 1, 12, 67, 45, True, ['-q', '75', '-y', '1', '-h']),
    ('lossless8-gray-2', 1, 8, 67, 45, True, ['-p', '-y', '2', '-c', '-h']),
    ('lossless8-rgb-2', 3, 8, 67, 45, True, ['-p', '-y', '2', '-c', '-h']),
    ('lossless8-rgb-2-arith', 3, 8, 67, 45, True, ['-p', '-y', '2', '-c', '-a']),
    ('lossless12-gray-3-arith', 1, 12, 67, 45, True, ['-p', '-y', '3', '-c', '-a']),
    ('lossless12-gray-2', 1, 12, 67, 45, True, ['-p', '-y', '2', '-c', '-h']),
    ('lossless16-gray-2', 1, 16, 67, 45, True, ['-p', '-y', '2', '-c', '-h']),
    ('lossless16-gray-2-arith', 1, 16, 67, 45, True, ['-p', '-y', '2', '-c', '-a']),
]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    reference, out = sys.argv[1:3]
    os.makedirs(out, exist_ok=True)
    pins = []
    rows = ['# name\tcomponents\tbits\twidth\theight\tlossless\toptions\tseed\tdecoded sha256 (component planes,'
            ' planar 16-bit LE)\tT.81 reconstruction sha256\tsource sha256']
    seeds = {}
    reconstructions = {}
    clamped = {}
    with tempfile.TemporaryDirectory() as work:
        for index, (name, components, bits, width, height, lossless, options) in enumerate(CASES):
            twin = name[:-len('-arith')] if name.endswith('-arith') else None
            seed = seeds[twin] if twin in seeds else 5000 + index
            seeds[name] = seed
            planes = jpegls.synthetic(width, height, components, bits, seed)
            pnm = os.path.join(work, 'in.pnm')
            jpegls.write_pnm(pnm, planes, bits)
            jpg = os.path.join(out, name + '.jpg')
            jpegls.run([reference] + options + [pnm, jpg])
            if os.path.getsize(jpg) == 0:
                raise SystemExit('reference A wrote nothing for ' + name)
            source = jpegls.planar_bytes(planes)
            try:
                decoded = jpegls.reference_planes(reference, jpg, work)
            except SystemExit:
                # reference A's decoder rejects the 16-bit differential lossless frames its encoder writes
                if not lossless or bits != 16:
                    raise
                decoded = source
            reconstruction = '-'
            if twin in reconstructions:
                reconstruction = reconstructions[twin]
            elif lossless and '-p' in options and twin is None:
                with open(jpg, 'rb') as f:
                    planes_t81 = t81decode.reconstruct(f.read())
                reconstruction = sha256(b''.join(v.to_bytes(2, 'little') for plane in planes_t81 for v in plane))
            reconstructions[name] = reconstruction
            pins.append(name + '.jpg')
            raw = None
            if not lossless:
                raw = decoded
            elif '-q' in options:
                if twin in clamped:
                    raw = clamped[twin]
                else:
                    with open(jpg, 'rb') as f:
                        planes_clamped = t81decode.clamped_reference(f.read(), [plane for _, _, plane in planes])
                    raw = b''.join(v.to_bytes(2, 'little') for plane in planes_clamped for v in plane)
                clamped[name] = raw
            if raw is not None:
                with open(os.path.join(out, name + '.raw'), 'wb') as f:
                    f.write(raw)
                pins.append(name + '.raw')
            rows.append('\t'.join([name, str(components), str(bits), str(width), str(height),
                                   'true' if lossless else 'false', ' '.join(options), str(seed), sha256(decoded),
                                   reconstruction, sha256(source)]))
    with open(os.path.join(out, 'hierarchical.tsv'), 'w', newline='\n') as f:
        f.write('\n'.join(rows) + '\n')
    pins.append('hierarchical.tsv')
    lines = []
    for name in pins:
        with open(os.path.join(out, name), 'rb') as f:
            lines.append('%s  %s' % (sha256(f.read()), name))
    with open(os.path.join(out, 'hierarchical.sha256'), 'w', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
