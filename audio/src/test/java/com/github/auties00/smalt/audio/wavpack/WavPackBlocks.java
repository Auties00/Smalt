package com.github.auties00.smalt.audio.wavpack;

/**
 * Edits WavPack files for the tests: changes a metadata sub-block and recomputes the block checksum, so that only
 * the change itself is detectable.
 */
final class WavPackBlocks {
    private WavPackBlocks() {
    }

    /** Returns a copy of a file whose MD5 signature sub-block has its first byte flipped. */
    static byte[] tamperMd5(byte[] file) {
        var data = file.clone();
        var position = 0;
        while (position + 32 <= data.length) {
            var end = position + WavPackStream.i32(data, position + 4) + 8;
            var at = position + 32;
            var found = false;
            while (at + 2 <= end) {
                var id = data[at] & 0xFF;
                var length = (data[at + 1] & 0xFF) << 1;
                var header = 2;
                if ((id & 0x80) != 0) {
                    length += (data[at + 2] & 0xFF) << 9 | (data[at + 3] & 0xFF) << 17;
                    header = 4;
                }
                if ((id & 0x3f) == 0x26) {
                    data[at + header] ^= 1;
                    found = true;
                }
                if ((id & 0x3f) == 0x2f && found) {
                    rechecksum(data, position, at, length);
                    return data;
                }
                at += header + length;
            }
            if (found) {
                return data;
            }
            position = end;
        }
        throw new IllegalArgumentException("no MD5 signature");
    }

    /**
     * Returns a copy of a single-block file whose byte at {@code at} is flipped, with the block checksum recomputed
     * when the block carries one.
     */
    static byte[] alterBitstream(byte[] block, int at) {
        var data = block.clone();
        data[at] ^= 0x5A;
        var end = WavPackStream.i32(data, 4) + 8;
        var position = 32;
        while (position + 2 <= end) {
            var id = data[position] & 0xFF;
            var length = (data[position + 1] & 0xFF) << 1;
            var header = 2;
            if ((id & 0x80) != 0) {
                length += (data[position + 2] & 0xFF) << 9 | (data[position + 3] & 0xFF) << 17;
                header = 4;
            }
            if ((id & 0x3f) == 0x2f) {
                if (at >= position && at < position + header + length) {
                    throw new IllegalArgumentException("the altered byte is the block checksum");
                }
                rechecksum(data, 0, position, length);
            }
            position += header + length;
        }
        return data;
    }

    private static void rechecksum(byte[] data, int block, int at, int length) {
        var words = (at - block) >> 1;
        var sum = 0xffffffff;
        for (var i = 0; i < words; i++) {
            sum = sum * 3 + WavPackStream.u16(data, block + 2 * i);
        }
        if (length == 4) {
            for (var k = 0; k < 4; k++) {
                data[at + 2 + k] = (byte) (sum >> (8 * k));
            }
        } else {
            sum ^= sum >>> 16;
            data[at + 2] = (byte) sum;
            data[at + 3] = (byte) (sum >> 8);
        }
    }
}
