#!/usr/bin/env python3
"""Writes the reference decodes of the layered HEVC image items of Nokia's HEIF conformance files.

smalt-mpeg's HeicMultilayerTest decodes the 'lhv1' items of multilayer001, 004 and 005.heic, whose
reconstructed pictures no conformance package gives. This script decodes them with the SHM-12.4 reference decoder of
the scalable and multiview extensions (TAppDecoder, BSD-3-Clause, used as a black box) and prints, for each item, the
MD5 of the decoded picture of the item's output layer (cropped by the conformance window, planar 4:2:0, 8-bit):

    file    item    output layer    width    height    MD5

Each 'lhv1' item is turned into an Annex B stream: the parameter sets of its 'lhvC' property (and of the base item's
'hvcC' when the base layer is an 'hvc1' item) followed by the NAL units of the item's data, then decoded with
-olsidx set to the item's 'tols' target output layer set. The layer it outputs is the 'lsel' layer when the item has
one, the highest output layer otherwise. An item whose base layer is an AVC-coded item ('exbl' reference,
multilayer004) gets that item decoded by ffmpeg (black box) and handed to SHM as the external base layer picture
(-ibl). For the multiview file (multilayer005) ffmpeg 7.1's MV-HEVC decoder must decode the two views to SHM's two
layers.

Made with SHM-12.4 (https://vcgit.hhi.fraunhofer.de/jvet/SHM, tag SHM-12.4) compiled with gcc 15 from the sources of
TLibCommon, TLibDecoder, TLibVideoIO, TAppCommon, libmd5 and TAppDecoder (g++ -O2 -w -fpermissive -std=gnu++11),
and ffmpeg 7.1. Pass the SHM decoder and the directory holding the Nokia files:

    python -I tools/testdata/hevc-multilayer/make-references.py <TAppDecoder> <directory of multilayer00N.heic>
"""
import hashlib
import os
import struct
import subprocess
import sys
import tempfile


def boxes(data, start, end):
    """Yields (type, payload start, end) of the boxes between two offsets."""
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack('>I4s', data[position:position + 8])
        header = 8
        if size == 1:
            size = struct.unpack('>Q', data[position + 8:position + 16])[0]
            header = 16
        elif size == 0:
            size = end - position
        yield kind.decode('latin1'), position + header, position + size
        position += size


def child(data, start, end, kind):
    for name, payload, stop in boxes(data, start, end):
        if name == kind:
            return payload, stop
    raise KeyError(kind)


class Heif:
    """The items, properties and references of a HEIF file's meta box."""

    def __init__(self, path):
        self.data = data = open(path, 'rb').read()
        meta, meta_end = child(data, 0, len(data), 'meta')
        meta += 4
        self.items = {}
        self.locations = {}
        self.properties = []
        self.associations = {}
        self.references = []
        for kind, payload, stop in boxes(data, meta, meta_end):
            if kind == 'iinf':
                version = data[payload]
                first = payload + 4 + (2 if version == 0 else 4)
                for name, entry, _ in boxes(data, first, stop):
                    if name == 'infe':
                        item_version = data[entry]
                        if item_version == 2:
                            identifier = struct.unpack('>H', data[entry + 4:entry + 6])[0]
                            item_type = data[entry + 8:entry + 12].decode('latin1')
                        else:
                            identifier = struct.unpack('>I', data[entry + 4:entry + 8])[0]
                            item_type = data[entry + 10:entry + 14].decode('latin1')
                        self.items[identifier] = item_type
            elif kind == 'iloc':
                self.parse_iloc(payload, stop)
            elif kind == 'iprp':
                ipco, ipco_end = child(data, payload, stop, 'ipco')
                for name, box, end in boxes(data, ipco, ipco_end):
                    self.properties.append((name, data[box:end]))
                ipma, ipma_end = child(data, payload, stop, 'ipma')
                self.parse_ipma(ipma, ipma_end)
            elif kind == 'iref':
                version = data[payload]
                for name, box, end in boxes(data, payload + 4, stop):
                    width = 2 if version == 0 else 4
                    source = int.from_bytes(data[box:box + width], 'big')
                    count = struct.unpack('>H', data[box + width:box + width + 2])[0]
                    targets = [int.from_bytes(data[box + width + 2 + k * width:box + width + 2 + (k + 1) * width], 'big')
                               for k in range(count)]
                    self.references.append((name, source, targets))

    def parse_iloc(self, start, end):
        data = self.data
        version = data[start]
        position = start + 4
        offset_size, length_size = data[position] >> 4, data[position] & 15
        base_size = data[position + 1] >> 4
        index_size = data[position + 1] & 15 if version in (1, 2) else 0
        position += 2

        def read(width):
            nonlocal position
            value = int.from_bytes(data[position:position + width], 'big') if width else 0
            position += width
            return value

        count = read(2 if version < 2 else 4)
        for _ in range(count):
            identifier = read(2 if version < 2 else 4)
            if version in (1, 2):
                read(2)
            read(2)
            base = read(base_size)
            extents = []
            for _ in range(read(2)):
                read(index_size)
                extents.append((base + read(offset_size), read(length_size)))
            self.locations[identifier] = extents

    def parse_ipma(self, start, end):
        data = self.data
        version, flags = data[start], int.from_bytes(data[start + 1:start + 4], 'big')
        position = start + 4
        count = struct.unpack('>I', data[position:position + 4])[0]
        position += 4
        for _ in range(count):
            width = 2 if version < 1 else 4
            identifier = int.from_bytes(data[position:position + width], 'big')
            position += width
            associations = data[position]
            position += 1
            indices = []
            for _ in range(associations):
                if flags & 1:
                    value = struct.unpack('>H', data[position:position + 2])[0] & 0x7FFF
                    position += 2
                else:
                    value = data[position] & 0x7F
                    position += 1
                indices.append(value)
            self.associations[identifier] = indices

    def item_properties(self, identifier):
        return [self.properties[index - 1] for index in self.associations.get(identifier, []) if index > 0]

    def item_data(self, identifier):
        return b''.join(self.data[offset:offset + length] for offset, length in self.locations[identifier])


def record_units(record, header):
    """Returns the NAL units of the arrays of an hvcC (header 23) or lhvC (header 6) record."""
    units = []
    arrays = record[header - 1]
    position = header
    for _ in range(arrays):
        count = struct.unpack('>H', record[position + 1:position + 3])[0]
        position += 3
        for _ in range(count):
            length = struct.unpack('>H', record[position:position + 2])[0]
            units.append(record[position + 2:position + 2 + length])
            position += 2 + length
    return units


def length_prefixed(data, size=4):
    units = []
    position = 0
    while position + size <= len(data):
        length = int.from_bytes(data[position:position + size], 'big')
        units.append(data[position + size:position + size + length])
        position += size + length
    return units


def annex_b(units):
    return b''.join(b'\x00\x00\x00\x01' + unit for unit in units)


def run(command):
    result = subprocess.run(command, capture_output=True)
    if result.returncode != 0:
        raise SystemExit('failed: %s\n%s' % (' '.join(command), result.stderr.decode(errors='replace')[-2000:]))
    return result.stdout.decode(errors='replace')


def main():
    decoder, directory = sys.argv[1], sys.argv[2]
    rows = []
    with tempfile.TemporaryDirectory() as work:
        # multilayer002's grid tiles after the first are predicted pictures with no 'pred' reference to the tile
        # before them, so they cannot be reconstructed on their own
        for name in ('multilayer001.heic', 'multilayer004.heic', 'multilayer005.heic'):
            path = os.path.join(directory, name)
            if not os.path.exists(path):
                # the test kit's conformance cache names the files after their source
                path = os.path.join(directory, 'heic-nokia-' + name)
            heif = Heif(path)
            for identifier, item_type in sorted(heif.items.items()):
                if item_type != 'lhv1':
                    continue
                properties = dict(heif.item_properties(identifier))
                lhvc = properties['lhvC']
                tols = struct.unpack('>H', properties['tols'][4:6])[0]
                selected = struct.unpack('>H', properties['lsel'][0:2])[0] if 'lsel' in properties else None
                width, height = struct.unpack('>II', properties['ispe'][4:12])
                # the parameter sets in the order decoders take them: video, sequence, picture
                units = sorted(record_units(lhvc, 6), key=lambda unit: (unit[0] >> 1) & 0x3F)
                external = [targets[0] for kind, source, targets in heif.references
                            if kind == 'exbl' and source == identifier]
                units += length_prefixed(heif.item_data(identifier))
                stream = os.path.join(work, '%s-%d.265' % (name, identifier))
                open(stream, 'wb').write(annex_b(units))
                outputs = [os.path.join(work, '%s-%d-L%d.yuv' % (name, identifier, layer)) for layer in range(4)]
                command = [decoder, '-b', stream, '-olsidx', str(tols)]
                for layer, path in enumerate(outputs):
                    command += ['-o%d' % layer, path]
                if external:
                    base = external[0]
                    base_properties = dict(heif.item_properties(base))
                    avcc = base_properties['avcC']
                    sps_count = avcc[5] & 0x1F
                    position = 6
                    avc_units = []
                    for _ in range(sps_count):
                        length = struct.unpack('>H', avcc[position:position + 2])[0]
                        avc_units.append(avcc[position + 2:position + 2 + length])
                        position += 2 + length
                    pps_count = avcc[position]
                    position += 1
                    for _ in range(pps_count):
                        length = struct.unpack('>H', avcc[position:position + 2])[0]
                        avc_units.append(avcc[position + 2:position + 2 + length])
                        position += 2 + length
                    avc_units += length_prefixed(heif.item_data(base), (avcc[4] & 3) + 1)
                    avc = os.path.join(work, '%s-%d.264' % (name, base))
                    open(avc, 'wb').write(annex_b(avc_units))
                    base_yuv = os.path.join(work, '%s-%d-base.yuv' % (name, base))
                    run(['ffmpeg', '-v', 'error', '-y', '-f', 'h264', '-i', avc, '-f', 'rawvideo', '-pix_fmt', 'yuv420p',
                         base_yuv])
                    command += ['-ibl', base_yuv]
                log = run(command)
                produced = [(layer, path) for layer, path in enumerate(outputs)
                            if os.path.exists(path) and os.path.getsize(path) > 0]
                if not produced:
                    raise SystemExit('%s item %d: SHM output no picture\n%s' % (name, identifier, log[-2000:]))
                layer, path = next((entry for entry in produced if entry[0] == selected), produced[-1])
                picture = open(path, 'rb').read()
                frame = width * height * 3 // 2
                if len(picture) < frame:
                    raise SystemExit('%s item %d: SHM output of %d bytes is not a %dx%d picture'
                                     % (name, identifier, len(picture), width, height))
                digest = hashlib.md5(picture[:frame]).hexdigest()
                if name == 'multilayer005.heic':
                    # ffmpeg names the views by ViewId, which need not follow the layer order: the two decoders
                    # must agree on the pictures of the two views
                    views = set()
                    for view_id in (0, 1):
                        view = os.path.join(work, 'view%d.yuv' % view_id)
                        run(['ffmpeg', '-v', 'error', '-y', '-f', 'hevc', '-i', stream, '-map', '0:v:0:view:%d' % view_id,
                             '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'yuv420p', view])
                        views.add(hashlib.md5(open(view, 'rb').read()[:frame]).hexdigest())
                    layers = {hashlib.md5(open(other, 'rb').read()[:frame]).hexdigest() for _, other in produced}
                    if views != layers:
                        raise SystemExit('multilayer005: ffmpeg decodes the views to %s, SHM the layers to %s'
                                         % (sorted(views), sorted(layers)))
                rows.append((name, identifier, layer, width, height, digest))
    for row in rows:
        print('\t'.join(str(value) for value in row))


if __name__ == '__main__':
    main()
