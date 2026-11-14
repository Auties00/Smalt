package com.github.auties00.smalt.video.vp9.internal;

/**
 * Packs several VP9 frames into one packet with a superframe index, the container of a hidden
 * frame and the shown frame after it (VP9 bitstream specification, annex B).
 *
 * <p>The index follows the frames: a marker byte {@code 0b110mmfff}, where {@code mm} is the
 * byte count of each size minus one and {@code fff} the frame count minus one, the sizes in
 * little-endian order, and the marker again.
 */
final class Vp9Superframe {
    /**
     * Prevents instantiation.
     */
    private Vp9Superframe() {
        throw new AssertionError();
    }

    /**
     * Packs frames into one packet, returning a single frame as it is.
     *
     * @param frames the frames, at most 8, in decoding order
     * @param sizes the size of each frame, a prefix of its array
     * @param count the number of frames
     * @return the packet
     * @throws IllegalArgumentException if {@code count} is not between 1 and 8
     */
    static byte[] pack(byte[][] frames, int[] sizes, int count) {
        if (count < 1 || count > 8) {
            throw new IllegalArgumentException("a superframe holds 1 to 8 frames, not " + count);
        }
        if (count == 1) {
            return java.util.Arrays.copyOf(frames[0], sizes[0]);
        }
        var largest = 0;
        var total = 0;
        for (var i = 0; i < count; i++) {
            largest = Math.max(largest, sizes[i]);
            total += sizes[i];
        }
        var mag = largest < 1 << 8 ? 1 : largest < 1 << 16 ? 2 : largest < 1 << 24 ? 3 : 4;
        var out = new byte[total + 2 + mag * count];
        var pos = 0;
        for (var i = 0; i < count; i++) {
            System.arraycopy(frames[i], 0, out, pos, sizes[i]);
            pos += sizes[i];
        }
        var marker = (byte) (0xC0 | ((mag - 1) << 3) | (count - 1));
        out[pos++] = marker;
        for (var i = 0; i < count; i++) {
            for (var b = 0; b < mag; b++) {
                out[pos++] = (byte) (sizes[i] >>> (8 * b));
            }
        }
        out[pos] = marker;
        return out;
    }
}
