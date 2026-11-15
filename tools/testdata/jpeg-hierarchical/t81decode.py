"""Reconstructs a Huffman-coded lossless hierarchical JPEG image exactly as ITU-T T.81 Annex J specifies.

Used by make-vectors.py: the reference software A encoder upsamples its reference components with a rounding of its
own, so the T.81 reconstruction of its lossless hierarchical streams differs from its source image by one on some
interpolated samples, and only a decoder that follows J.1.1.2 can serve as the expected value.

Supports what make-vectors.py writes: a non-differential lossless frame (SOF3) followed by differential lossless frames
(SOF7), every component sampled 1x1, one scan per frame, no restart intervals. reconstruct() returns the component
planes of the last frame, each a list of samples in raster order. Below 16 bits a reconstructed sample outside the
range of the precision (T.81 adds the differences modulo 2^16 and leaves such samples undefined) is read as a signed
16-bit value and clamped to the range, as smalt-image does.

clamped_reference() serves the DCT hierarchies with a final lossless frame: reference software A takes the reference of
that frame from its DCT reconstruction before clamping it to the range of the precision, which T.81 A.3.1 requires, so
it returns the samples a decoder with reference A's inverse DCT but the clamp of A.3.1 reconstructs.
"""


def _segments(data):
    i = 2
    while i < len(data):
        if data[i] != 0xFF:
            raise ValueError('expected a marker at %d' % i)
        marker = data[i + 1]
        if marker == 0xD9:
            return
        length = data[i + 2] << 8 | data[i + 3]
        body = data[i + 4:i + 2 + length]
        end = i + 2 + length
        if marker == 0xDA:
            j = end
            while not (data[j] == 0xFF and data[j + 1] != 0x00):
                j += 1
            yield marker, body, data[end:j]
            i = j
        else:
            yield marker, body, None
            i = end


class _Bits:
    def __init__(self, coded):
        self.bytes = bytearray()
        k = 0
        while k < len(coded):
            self.bytes.append(coded[k])
            k += 2 if coded[k] == 0xFF else 1
        self.position = 0

    def bit(self):
        index = self.position >> 3
        value = (self.bytes[index] >> (7 - (self.position & 7))) & 1 if index < len(self.bytes) else 1
        self.position += 1
        return value

    def bits(self, count):
        value = 0
        for _ in range(count):
            value = value << 1 | self.bit()
        return value


def _table(body):
    counts = body[1:17]
    values = body[17:17 + sum(counts)]
    codes = {}
    code = 0
    k = 0
    for length in range(1, 17):
        for _ in range(counts[length - 1]):
            codes[(length, code)] = values[k]
            k += 1
            code += 1
        code <<= 1
    return body[0] & 15, codes, 17 + sum(counts)


def _difference(bits, codes):
    code = 0
    for length in range(1, 17):
        code = code << 1 | bits.bit()
        if (length, code) in codes:
            ssss = codes[(length, code)]
            break
    else:
        raise ValueError('invalid Huffman code')
    if ssss == 0:
        return 0
    if ssss == 16:
        return 32768
    value = bits.bits(ssss)
    if value < 1 << (ssss - 1):
        value -= (1 << ssss) - 1
    return value


def _decode(frame, scan):
    width, height, precision, differential = frame['width'], frame['height'], frame['precision'], frame['differential']
    components, predictor, transform, coded, tables = scan
    bits = _Bits(coded)
    planes = [[0] * (width * height) for _ in components]
    for y in range(height):
        for x in range(width):
            for c, table in enumerate(components):
                difference = _difference(bits, tables[table])
                plane = planes[c]
                if differential:
                    prediction = 0
                elif x == 0 and y == 0:
                    prediction = 1 << (precision - transform - 1)
                elif y == 0:
                    prediction = plane[x - 1]
                elif x == 0:
                    prediction = plane[(y - 1) * width]
                else:
                    ra = plane[y * width + x - 1]
                    rb = plane[(y - 1) * width + x]
                    rc = plane[(y - 1) * width + x - 1]
                    prediction = [0, ra, rb, rc, ra + rb - rc, ra + ((rb - rc) >> 1), rb + ((ra - rc) >> 1),
                                  (ra + rb) >> 1][predictor]
                plane[y * width + x] = (prediction + difference) & 0xFFFF
    if not differential and transform:
        planes = [[v << transform & 0xFFFF for v in plane] for plane in planes]
    return planes


def _expand(plane, width, height, horizontal, vertical):
    """Upsamples as J.1.1.2: Px = (Ra + Rb) / 2 truncated, edges replicated, horizontal before vertical."""
    if horizontal:
        out = [0] * (2 * width * height)
        for y in range(height):
            for x in range(width):
                a = plane[y * width + x]
                b = plane[y * width + min(x + 1, width - 1)]
                out[y * 2 * width + 2 * x] = a
                out[y * 2 * width + 2 * x + 1] = (a + b) >> 1
        plane, width = out, 2 * width
    if vertical:
        out = [0] * (2 * width * height)
        for y in range(height):
            for x in range(width):
                a = plane[y * width + x]
                b = plane[min(y + 1, height - 1) * width + x]
                out[2 * y * width + x] = a
                out[(2 * y + 1) * width + x] = (a + b) >> 1
        plane, height = out, 2 * height
    return plane, width, height


def reconstruct(data):
    tables = {}
    frames = []
    expansion = None
    for marker, body, coded in _segments(data):
        if marker == 0xC4:
            k = 0
            while k < len(body):
                table, codes, size = _table(body[k:])
                tables[table] = codes
                k += size
        elif marker == 0xDD:
            raise ValueError('restart intervals are not supported')
        elif marker == 0xDF:
            expansion = (body[0] >> 4, body[0] & 15)
        elif marker in (0xC3, 0xC7):
            count = body[5]
            for c in range(count):
                if body[7 + 3 * c] != 0x11:
                    raise ValueError('subsampled components are not supported')
            frames.append({'differential': marker == 0xC7, 'precision': body[0], 'height': body[1] << 8 | body[2],
                           'width': body[3] << 8 | body[4], 'expansion': expansion, 'scans': []})
            expansion = None
        elif marker in (0xC0, 0xC1, 0xC2, 0xC5, 0xC6, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            raise ValueError('only Huffman lossless frames are supported')
        elif marker == 0xDA:
            count = body[0]
            components = [body[2 + 2 * c] >> 4 for c in range(count)]
            frames[-1]['scans'].append((components, body[1 + 2 * count], body[3 + 2 * count] & 15, coded,
                                        dict(tables)))
    planes = None
    width = height = 0
    for frame in frames:
        if len(frame['scans']) != 1:
            raise ValueError('frames of several scans are not supported')
        decoded = _decode(frame, frame['scans'][0])
        if not frame['differential']:
            planes, width, height = decoded, frame['width'], frame['height']
            continue
        horizontal, vertical = frame['expansion'] or (0, 0)
        references = []
        for plane in planes:
            expanded, w, h = _expand(plane, width, height, horizontal, vertical)
            references.append([expanded[y * w + x] for y in range(frame['height']) for x in range(frame['width'])])
        planes = [[(r + d) & 0xFFFF for r, d in zip(reference, difference)]
                  for reference, difference in zip(references, decoded)]
        width, height = frame['width'], frame['height']
    precision = frames[0]['precision']
    if precision < 16:
        top = (1 << precision) - 1
        planes = [[min(max(v - 65536 if v >= 32768 else v, 0), top) for v in plane] for plane in planes]
    return planes


def clamped_reference(data, source):
    """Returns the source planes with the reference of the last frame, implied by its differences, clamped.

    The last frame must be a Huffman-coded differential lossless frame (SOF7) of 1x1-sampled components in one scan;
    the frames before it are not decoded.
    """
    tables = {}
    frame = None
    scan = None
    for marker, body, coded in _segments(data):
        if marker == 0xC4:
            k = 0
            while k < len(body):
                table, codes, size = _table(body[k:])
                tables[table] = codes
                k += size
        elif marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            frame = None
            scan = None
            if marker == 0xC7:
                frame = {'differential': True, 'precision': body[0], 'height': body[1] << 8 | body[2],
                         'width': body[3] << 8 | body[4]}
        elif marker == 0xDA and frame is not None:
            count = body[0]
            scan = ([body[2 + 2 * c] >> 4 for c in range(count)], body[1 + 2 * count], body[3 + 2 * count] & 15,
                    coded, dict(tables))
    if scan is None:
        raise ValueError('the last frame is not a Huffman differential lossless frame')
    top = (1 << frame['precision']) - 1
    differences = _decode(frame, scan)
    planes = []
    for plane, difference in zip(source, differences):
        out = []
        for value, d in zip(plane, difference):
            signed = d - 65536 if d >= 32768 else d
            out.append(min(max(min(max(value - signed, 0), top) + signed, 0), top))
        planes.append(out)
    return planes
