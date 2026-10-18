package com.github.auties00.smalt.image.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Builds Multi-Picture Format JPEG files (CIPA DC-007): a primary JPEG whose {@code APP2 MPF}
 * index points at a secondary JPEG appended after it, and checks that the index still leads to
 * the secondary image.
 */
final class MultiPictureFixture {
    private MultiPictureFixture() {
    }

    /** Builds a primary JPEG with an MPF index after its SOI marker, followed by the secondary JPEG. */
    static byte[] build(byte[] primary, byte[] secondary) {
        var index = ByteBuffer.allocate(8 + 2 + 3 * 12 + 4 + 32);
        index.put(new byte[]{'M', 'M', 0, 42}).putInt(8);
        index.putShort((short) 3);
        index.putShort((short) 0xB000).putShort((short) 7).putInt(4).put(new byte[]{'0', '1', '0', '0'});
        index.putShort((short) 0xB001).putShort((short) 4).putInt(1).putInt(2);
        index.putShort((short) 0xB002).putShort((short) 7).putInt(32).putInt(8 + 2 + 3 * 12 + 4);
        index.putInt(0);
        var segmentLength = 2 + 4 + index.capacity();
        var primaryLength = primary.length + 2 + segmentLength;
        var headerOffset = 2 + 4 + 4;
        index.putInt(0x20030000).putInt(primaryLength).putInt(0).putShort((short) 0).putShort((short) 0);
        index.putInt(0x00020000).putInt(secondary.length).putInt(primaryLength - headerOffset).putShort((short) 0).putShort((short) 0);
        var out = new ByteArrayOutputStream();
        out.write(primary, 0, 2);
        out.write(0xFF);
        out.write(0xE2);
        out.write(segmentLength >>> 8);
        out.write(segmentLength);
        out.writeBytes(new byte[]{'M', 'P', 'F', 0});
        out.writeBytes(index.array());
        out.write(primary, 2, primary.length - 2);
        out.writeBytes(secondary);
        return out.toByteArray();
    }

    /** Returns the offset and length of the MPF segment, or {@code null}. */
    static int[] segment(byte[] jpeg) {
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xDA) {
                return null;
            }
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (marker == 0xE2 && jpeg[position + 4] == 'M' && jpeg[position + 5] == 'P' && jpeg[position + 6] == 'F') {
                return new int[]{position, 2 + length};
            }
            position += 2 + length;
        }
        return null;
    }

    /** Returns whether the second MP entry points at a JPEG start-of-image marker. */
    static boolean secondaryIsReachable(byte[] jpeg) {
        var segment = segment(jpeg);
        if (segment == null) {
            return false;
        }
        var header = segment[0] + 8;
        var buffer = ByteBuffer.wrap(jpeg);
        var ifd = header + buffer.getInt(header + 4);
        var count = buffer.getShort(ifd) & 0xFFFF;
        for (var i = 0; i < count; i++) {
            var entry = ifd + 2 + 12 * i;
            if ((buffer.getShort(entry) & 0xFFFF) == 0xB002) {
                var data = header + buffer.getInt(entry + 8);
                var offset = header + buffer.getInt(data + 16 + 8);
                return offset + 3 <= jpeg.length && (jpeg[offset] & 0xFF) == 0xFF && (jpeg[offset + 1] & 0xFF) == 0xD8
                        && (jpeg[offset + 2] & 0xFF) == 0xFF;
            }
        }
        return false;
    }

    /** Returns a copy of {@code target} whose MPF segment is replaced by the one of {@code source}. */
    static byte[] withSegmentOf(byte[] target, byte[] source) {
        var into = segment(target);
        var from = segment(source);
        var result = Arrays.copyOf(target, target.length);
        System.arraycopy(source, from[0], result, into[0], Math.min(from[1], into[1]));
        return result;
    }
}
