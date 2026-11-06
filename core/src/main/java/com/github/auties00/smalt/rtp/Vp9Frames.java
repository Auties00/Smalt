package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

/**
 * Reads the parts of the VP9 bitstream the payload format needs: the superframe index (VP9 bitstream
 * specification annex B) and the start of the uncompressed frame header (section 6.2).
 */
final class Vp9Frames {
    /** The facts of a frame header; {@code width} and {@code height} are 0 when the header gives no size. */
    record Header(boolean key, boolean shown, boolean intraOnly, int width, int height) {
    }

    private Vp9Frames() {
        throw new AssertionError();
    }

    /**
     * Returns the frame sizes of a superframe, or {@code null} when the data has no valid superframe index and is
     * one frame.
     */
    static int[] superframeSizes(byte[] data) {
        if (data.length == 0) {
            return null;
        }
        var marker = data[data.length - 1] & 0xFF;
        if ((marker & 0xE0) != 0xC0) {
            return null;
        }
        var frames = (marker & 0x07) + 1;
        var magnitude = ((marker >>> 3) & 0x03) + 1;
        var indexSize = 2 + magnitude * frames;
        if (data.length < indexSize || (data[data.length - indexSize] & 0xFF) != marker) {
            return null;
        }
        var sizes = new int[frames];
        var position = data.length - indexSize + 1;
        long total = 0;
        for (var frame = 0; frame < frames; frame++) {
            long size = 0;
            for (var index = 0; index < magnitude; index++) {
                size |= (long) (data[position++] & 0xFF) << (8 * index);
            }
            sizes[frame] = (int) size;
            total += size;
        }
        if (total > data.length - indexSize) {
            return null;
        }
        return sizes;
    }

    /**
     * Parses the start of a frame header, or returns {@code null} when it is not a valid VP9 frame.
     */
    static Header header(byte[] data, int offset, int length) {
        if (length < 1) {
            return null;
        }
        var reader = new MsbBitReader(data, offset, length);
        if (reader.read(2) != 2) {
            return null;
        }
        var low = reader.read(1);
        var profile = (reader.read(1) << 1) | low;
        if (profile == 3) {
            reader.read(1);
        }
        if (reader.read(1) == 1) {
            return reader.overrun() ? null : new Header(false, true, false, 0, 0);
        }
        var key = reader.read(1) == 0;
        var shown = reader.read(1) == 1;
        reader.read(1);
        var intraOnly = false;
        if (!key) {
            intraOnly = !shown && reader.read(1) == 1;
            return reader.overrun() ? null : new Header(false, shown, intraOnly, 0, 0);
        }
        if (reader.read(24) != 0x498342) {
            return null;
        }
        if (profile >= 2) {
            reader.read(1);
        }
        var colorSpace = reader.read(3);
        if (colorSpace != 7) {
            reader.read(1);
            if (profile == 1 || profile == 3) {
                reader.read(3);
            }
        } else if (profile == 1 || profile == 3) {
            reader.read(1);
        }
        var width = reader.read(16) + 1;
        var height = reader.read(16) + 1;
        if (reader.overrun()) {
            return new Header(true, shown, false, 0, 0);
        }
        return new Header(true, shown, false, width, height);
    }
}
