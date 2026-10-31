"""Writes the JPEG-LS test vectors of smalt-image.

Usage: python -I make-vectors.py <charls-driver> <referenceA jpeg> <T87_data_set.zip> <output directory>

<charls-driver> is charls-driver.cpp of this directory built against CharLS (BSD-3-Clause) at the commit of
README.md; <referenceA jpeg> is the command line codec of the ISO/IEC 10918-7 reference software A (Thomas
Richter's libjpeg, run as a black box under its evaluation and conformance-testing license); the zip is the ITU-T
T.87 conformance data set of tools/testdata/vectors.tsv. The output directory receives:

  charls/<name>.jls      CharLS's encoding of a synthetic image (see synthetic()), for every case of CHARLS
  charls.tsv             one row per case: its options and the SHA-256 of CharLS's decode (planar 16-bit LE)
  referenceA/<name>.jls  reference software A's JPEG-LS encoding of a synthetic PNM image (subsampling, restart
                         intervals, DNL), for every case of REFERENCE
  referenceA.tsv         one row per case: the SHA-256 of reference A's decode of every component
  t87.tsv                the SHA-256 of an oracle's decode of every stream of the T.87 data set: CharLS for the
                         streams it decodes, reference A (component planes) for the subsampled ones
  jpegls.sha256          the SHA-256 of every file above

The output is deterministic. JpegLsVectorsTest regenerates the synthetic images with the same arithmetic.
"""
import hashlib
import os
import struct
import subprocess
import sys
import tempfile
import zipfile


def synthetic(width, height, components, bits, seed, factors=None):
    """Returns planar samples: flat, gradient, noisy gradient and noise regions of 8x8 blocks.

    Component c has factors[c] = (h, v) and the size ceil(width * h / hmax) x ceil(height * v / vmax)."""
    mask = (1 << bits) - 1
    if factors is None:
        factors = [(1, 1)] * components
    hmax = max(f[0] for f in factors)
    vmax = max(f[1] for f in factors)
    state = seed
    planes = []
    for c in range(components):
        w = (width * factors[c][0] + hmax - 1) // hmax
        h = (height * factors[c][1] + vmax - 1) // vmax
        plane = []
        for y in range(h):
            for x in range(w):
                state = (state * 1103515245 + 12345) & 0x7FFFFFFF
                noise = (state >> 16) & 15
                region = ((x >> 3) + (y >> 3) + c) % 4
                gradient = x * 5 + y * 3 + c * 17
                if region == 0:
                    v = c * 40 + 20
                elif region == 1:
                    v = gradient
                elif region == 2:
                    v = gradient + noise
                else:
                    v = state >> 8
                if bits >= 8:
                    v = (v << (bits - 8)) | (v & ((1 << (bits - 8)) - 1))
                else:
                    v >>= 8 - bits
                plane.append(v & mask)
        planes.append((w, h, plane))
    return planes


def planar_bytes(planes):
    out = bytearray()
    for _, _, plane in planes:
        for v in plane:
            out += struct.pack('<H', v)
    return bytes(out)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


# name, width, height, components, bits, interleave, near, maxval, t1, t2, t3, reset, transform, spiff color space
CHARLS = [
    ('gray8', 61, 47, 1, 8, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray8-near1', 61, 47, 1, 8, 0, 1, 0, 0, 0, 0, 0, 0, None),
    ('gray8-near3', 61, 47, 1, 8, 0, 3, 0, 0, 0, 0, 0, 0, None),
    ('gray2', 33, 29, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray5', 33, 29, 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray5-near1', 33, 29, 1, 5, 0, 1, 0, 0, 0, 0, 0, 0, None),
    ('gray10', 45, 31, 1, 10, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray12', 45, 31, 1, 12, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray12-near2', 45, 31, 1, 12, 0, 2, 0, 0, 0, 0, 0, 0, None),
    ('gray16', 45, 31, 1, 16, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray16-near7', 45, 31, 1, 16, 0, 7, 0, 0, 0, 0, 0, 0, None),
    ('gray8-1x1', 1, 1, 1, 8, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray8-1x37', 1, 37, 1, 8, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray8-37x1', 37, 1, 1, 8, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('gray8-presets', 61, 47, 1, 8, 0, 0, 0, 9, 9, 9, 31, 0, None),
    ('gray8-presets-near2', 61, 47, 1, 8, 0, 2, 0, 5, 11, 40, 100, 0, None),
    ('gray12-reset3', 45, 31, 1, 12, 0, 0, 0, 0, 0, 0, 3, 0, None),
    ('rgb8-ilv0', 53, 41, 3, 8, 0, 0, 0, 0, 0, 0, 0, 0, None),
    ('rgb8-ilv1', 53, 41, 3, 8, 1, 0, 0, 0, 0, 0, 0, 0, None),
    ('rgb8-ilv2', 53, 41, 3, 8, 2, 0, 0, 0, 0, 0, 0, 0, None),
    ('rgb8-ilv1-near2', 53, 41, 3, 8, 1, 2, 0, 0, 0, 0, 0, 0, None),
    ('rgb8-ilv2-near5', 53, 41, 3, 8, 2, 5, 0, 0, 0, 0, 0, 0, None),
    ('rgb10-ilv1', 53, 41, 3, 10, 1, 0, 0, 0, 0, 0, 0, 0, None),
    ('rgb16-ilv2', 37, 29, 3, 16, 2, 0, 0, 0, 0, 0, 0, 0, None),
    ('rgb8-ilv1-presets', 53, 41, 3, 8, 1, 0, 0, 9, 9, 9, 31, 0, None),
    ('rgb8-hp1-ilv1', 53, 41, 3, 8, 1, 0, 0, 0, 0, 0, 0, 1, None),
    ('rgb8-hp2-ilv2', 53, 41, 3, 8, 2, 0, 0, 0, 0, 0, 0, 2, None),
    ('rgb8-hp3-ilv1', 53, 41, 3, 8, 1, 0, 0, 0, 0, 0, 0, 3, None),
    ('rgb16-hp3-ilv2', 37, 29, 3, 16, 2, 0, 0, 0, 0, 0, 0, 3, None),
    ('rgb8-spiff', 53, 41, 3, 8, 2, 0, 0, 0, 0, 0, 0, 0, 10),
    ('ycbcr8-spiff', 53, 41, 3, 8, 1, 0, 0, 0, 0, 0, 0, 0, 3),
    ('two8-ilv2', 41, 33, 2, 8, 2, 0, 0, 0, 0, 0, 0, 0, None),
    ('four8-ilv1', 41, 33, 4, 8, 1, 0, 0, 0, 0, 0, 0, 0, None),
    ('four8-ilv2-near1', 41, 33, 4, 8, 2, 1, 0, 0, 0, 0, 0, 0, None),
    ('five8-ilv0', 23, 19, 5, 8, 0, 0, 0, 0, 0, 0, 0, 0, None),
]

# name, PNM components, bits, height, options. Reference A does not complete the last minimum coded unit of a
# line-interleaved scan of subsampled components with extra lines, as T.87 B.4 requires, so those images have a
# height the units divide.
REFERENCE = [
    ('ilv0-sub', 3, 8, 45, ['-ls', '0', '-c', '-s', '1x1,1x2,2x1']),
    ('ilv1-sub420', 3, 8, 44, ['-ls', '1', '-c', '-s', '1x1,2x2,2x2']),
    ('ilv1-sub420-near1-ri2', 3, 8, 44, ['-ls', '1', '-c', '-s', '1x1,2x2,2x2', '-z', '2', '-m', '1']),
    ('ilv1-ri3', 3, 8, 45, ['-ls', '1', '-c', '-z', '3']),
    ('ilv2-near2', 3, 8, 45, ['-ls', '2', '-c', '-m', '2']),
    ('ilv2-ri1', 3, 8, 45, ['-ls', '2', '-c', '-z', '1']),
    ('ilv1-dnl', 3, 8, 45, ['-ls', '1', '-c', '-n']),
    ('ilv0-ri5', 3, 8, 45, ['-ls', '0', '-c', '-z', '5']),
    ('gray16-ri4', 1, 16, 45, ['-ls', '0', '-z', '4']),
    ('gray12-near3', 1, 12, 45, ['-ls', '0', '-m', '3']),
]

T87 = [
    'T87_test-1-2-3-4-5-6/T8C0E0.JLS', 'T87_test-1-2-3-4-5-6/T8C0E3.JLS', 'T87_test-1-2-3-4-5-6/T8C1E0.JLS',
    'T87_test-1-2-3-4-5-6/T8C1E3.JLS', 'T87_test-1-2-3-4-5-6/T8C2E0.JLS', 'T87_test-1-2-3-4-5-6/T8C2E3.JLS',
    'T87_test-7-8/T8SSE0.JLS', 'T87_test-7-8/T8SSE3.JLS', 'T87_test-9-10/T8NDE0.JLS', 'T87_test-9-10/T8NDE3.JLS',
    'T87_test-11-12/T16E0.JLS', 'T87_test-11-12/T16E3.JLS',
]


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit('failed: %s\n%s%s' % (' '.join(command), result.stdout, result.stderr))
    return result.stdout


def charls_decode(driver, jls, work):
    raw = os.path.join(work, 'decoded.raw')
    run([driver, 'decode', jls, raw])
    with open(raw, 'rb') as f:
        return f.read()


def write_pnm(path, planes, bits):
    width, height = planes[0][0], planes[0][1]
    maxval = (1 << bits) - 1
    out = bytearray(('P%d\n%d %d\n%d\n' % (5 if len(planes) == 1 else 6, width, height, maxval)).encode())
    for i in range(width * height):
        for _, _, plane in planes:
            if bits > 8:
                out += struct.pack('>H', plane[i])
            else:
                out.append(plane[i])
    with open(path, 'wb') as f:
        f.write(out)


def reference_planes(reference, jls, work):
    """Decodes with reference A into its component planes, returned as planar 16-bit LE samples."""
    target = os.path.join(work, 'planes.pnm')
    for name in os.listdir(work):
        if name.startswith('planes.pnm'):
            os.remove(os.path.join(work, name))
    run([reference, '-U', jls, target])
    out = bytearray()
    index = 0
    while os.path.exists('%s_%d.h' % (target, index)):
        with open('%s_%d.h' % (target, index)) as f:
            header = f.read().split()
        bits = int(header[2].lstrip('+'))
        with open('%s_%d.raw' % (target, index), 'rb') as f:
            data = f.read()
        if bits > 8:
            for k in range(0, len(data), 2):
                out += struct.pack('<H', (data[k] << 8) | data[k + 1])
        else:
            for v in data:
                out += struct.pack('<H', v)
        index += 1
    if index == 0:
        raise SystemExit('reference A wrote no component planes for ' + jls)
    return bytes(out)


def main():
    driver, reference, zip_path, out = sys.argv[1:5]
    os.makedirs(os.path.join(out, 'charls'), exist_ok=True)
    os.makedirs(os.path.join(out, 'referenceA'), exist_ok=True)
    pins = []
    with tempfile.TemporaryDirectory() as work:
        rows = ['# name\twidth\theight\tcomponents\tbits\tinterleave\tnear\tmaxval\tt1\tt2\tt3\treset\ttransform'
                '\tspiff\tseed\tdecoded sha256']
        for index, case in enumerate(CHARLS):
            name, width, height, components, bits, ilv, near, maxval, t1, t2, t3, reset, transform, spiff = case
            seed = 1000 + index
            planes = synthetic(width, height, components, bits, seed)
            raw = os.path.join(work, 'in.raw')
            with open(raw, 'wb') as f:
                f.write(planar_bytes(planes))
            jls = os.path.join(out, 'charls', name + '.jls')
            command = [driver, 'encode', raw, jls, str(width), str(height), str(components), str(bits), str(ilv),
                       str(near), str(maxval), str(t1), str(t2), str(t3), str(reset), str(transform)]
            if spiff is not None:
                command.append(str(spiff))
            run(command)
            decoded = charls_decode(driver, jls, work)
            if near == 0 and decoded != planar_bytes(planes):
                raise SystemExit('CharLS does not decode ' + name + ' losslessly')
            rows.append('\t'.join(str(v) for v in [name, width, height, components, bits, ilv, near, maxval, t1, t2,
                                                   t3, reset, transform, '-' if spiff is None else spiff, seed,
                                                   sha256(decoded)]))
            pins.append('charls/' + name + '.jls')
        for name, bits, table_entry in [('palette8', 8, 3), ('mapped8to16', 8, 2), ('mapped4to8', 4, 1)]:
            planes = synthetic(39, 27, 1, bits, 7000 + len(name))
            raw = os.path.join(work, 'in.raw')
            with open(raw, 'wb') as f:
                f.write(planar_bytes(planes))
            table = bytearray()
            for value in range(1 << bits):
                for byte in range(table_entry):
                    table.append((value * (37 + 50 * byte) + 11 * byte) & 0xFF)
            table_path = os.path.join(work, 'table.bin')
            with open(table_path, 'wb') as f:
                f.write(table)
            jls = os.path.join(out, 'charls', name + '.jls')
            run([driver, 'encode-mapped', raw, jls, '39', '27', str(bits), table_path, str(table_entry)])
            decoded = charls_decode(driver, jls, work)
            if decoded != planar_bytes(planes):
                raise SystemExit('CharLS does not decode the indexes of ' + name)
            rows.append('\t'.join(str(v) for v in [name, 39, 27, 1, bits, 0, 0, 0, 0, 0, 0, 0, 0, 'table%d'
                                                   % table_entry, 7000 + len(name), sha256(decoded)]))
            pins.append('charls/' + name + '.jls')
        with open(os.path.join(out, 'charls.tsv'), 'w', newline='\n') as f:
            f.write('\n'.join(rows) + '\n')
        pins.append('charls.tsv')
        rows = ['# name\tcomponents\tbits\toptions\tseed\tdecoded sha256 (component planes, planar 16-bit LE)']
        for index, (name, components, bits, height, options) in enumerate(REFERENCE):
            seed = 3000 + index
            planes = synthetic(67, height, components, bits, seed)
            pnm = os.path.join(work, 'in.pnm')
            write_pnm(pnm, planes, bits)
            jls = os.path.join(out, 'referenceA', name + '.jls')
            run([reference] + options + [pnm, jls])
            decoded = reference_planes(reference, jls, work)
            rows.append('\t'.join([name, str(components), str(bits), ' '.join(options), str(seed), sha256(decoded)]))
            pins.append('referenceA/' + name + '.jls')
        with open(os.path.join(out, 'referenceA.tsv'), 'w', newline='\n') as f:
            f.write('\n'.join(rows) + '\n')
        pins.append('referenceA.tsv')
        rows = ['# T.87 data set entry\toracle\tdecoded sha256 (planar 16-bit LE)']
        with zipfile.ZipFile(zip_path) as archive:
            for entry in T87:
                jls = os.path.join(work, 'entry.jls')
                with open(jls, 'wb') as f:
                    f.write(archive.read(entry))
                if 'SSE' in entry:
                    rows.append('\t'.join([entry, 'referenceA', sha256(reference_planes(reference, jls, work))]))
                else:
                    rows.append('\t'.join([entry, 'charls', sha256(charls_decode(driver, jls, work))]))
        with open(os.path.join(out, 't87.tsv'), 'w', newline='\n') as f:
            f.write('\n'.join(rows) + '\n')
        pins.append('t87.tsv')
    lines = []
    for name in pins:
        with open(os.path.join(out, name), 'rb') as f:
            lines.append('%s  %s' % (sha256(f.read()), name))
    with open(os.path.join(out, 'jpegls.sha256'), 'w', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
