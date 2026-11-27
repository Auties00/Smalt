package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Rewrites the frames of a Media 100 ({@code dtPA}) track as Motion JPEG format B frames, as ffmpeg's
 * {@code media100_to_mjpegb} bitstream filter does.
 *
 * <p>A Media 100 frame holds two fields, each a 4-byte header, the 64 luma and the 64 chroma quantizer values of
 * baseline JPEG as 32-bit big-endian words, 8 more bytes and the entropy-coded scan, the second field after the
 * marker word {@code 00 00 00 01} that follows the first. Each field becomes a format B field: the 40-byte
 * {@code mjpg} header with the offsets of its segments, a frame header of 8-bit 4:2:2 YCbCr at half the frame's
 * height, a scan header of the three components, the two quantization tables, and the scan with 8 zero bytes after
 * it; the frame carries no Huffman table, so the default tables of the JPEG standard apply. This class is
 * thread-safe.
 */
final class Media100Frames {
    /**
     * The size of a format B field header.
     */
    private static final int HEADER = 40;

    /**
     * The bytes of a Media 100 field before its scan: the header and the 128 quantizer words.
     */
    private static final int TABLES = 4 + 128 * 4;

    private Media100Frames() {
        throw new AssertionError();
    }

    /**
     * Rewrites one Media 100 frame as a Motion JPEG format B frame.
     *
     * @param frame the stored frame
     * @param width the frame width
     * @param height the frame height
     * @return the format B frame
     * @throws MediaException.Malformed if the frame is shorter than its quantization tables
     */
    static byte[] toMotionJpegB(byte[] frame, int width, int height) {
        if (frame.length < TABLES + 8) {
            throw new MediaException.Malformed("Media 100 frame of " + frame.length + " bytes");
        }
        var out = new byte[frame.length + 1024];
        var put = 0;
        var get = 0;
        var nextField = 0;
        var secondField = 0;
        var offsets = new int[2][5];
        for (var field = 0; field < 2; field++) {
            var start = put;
            put += HEADER;
            out[start + 4] = 'm';
            out[start + 5] = 'j';
            out[start + 6] = 'p';
            out[start + 7] = 'g';
            offsets[field][2] = put;
            put = u16(out, put, 17);
            out[put++] = 8;
            put = u16(out, put, height / 2);
            put = u16(out, put, width);
            put = bytes(out, put, 3, 1, 0x21, 0, 2, 0x11, 1, 3, 0x11, 1);
            offsets[field][3] = put;
            put = u16(out, put, 12);
            put = bytes(out, put, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 0, 0);
            offsets[field][0] = put;
            put = u16(out, put, 132);
            out[put++] = 0;
            get = Math.min(frame.length, get + 4);
            for (var table = 0; table < 2; table++) {
                if (table == 1) {
                    out[put++] = 1;
                }
                for (var value = 0; value < 64; value++) {
                    // the low byte of each big-endian word, as bytestream2_get_be32 truncated to a byte
                    out[put++] = get + 4 <= frame.length ? frame[get + 3] : 0;
                    get = Math.min(frame.length, get + 4);
                }
            }
            offsets[field][4] = put;
            if (field == 0) {
                for (var at = get + 8; at < frame.length - 4; at++) {
                    if (frame[at] == 0 && frame[at + 1] == 0 && frame[at + 2] == 0 && frame[at + 3] == 1) {
                        nextField = at;
                        break;
                    }
                }
            } else {
                nextField = frame.length;
            }
            get = Math.min(frame.length, get + 8);
            var scan = Math.max(0, nextField - get);
            if (put + scan + 8 > out.length) {
                out = Arrays.copyOf(out, put + scan + 8 + 1024);
            }
            System.arraycopy(frame, get, out, put, scan);
            get += scan;
            put += scan + 8;
            if (field == 0) {
                secondField = put;
            }
        }
        u32(out, 8, secondField);
        u32(out, 12, secondField);
        u32(out, 16, secondField);
        u32(out, 20, offsets[0][0]);
        u32(out, 24, 0);
        u32(out, 28, offsets[0][2]);
        u32(out, 32, offsets[0][3]);
        u32(out, 36, offsets[0][4]);
        u32(out, secondField + 8, put - secondField);
        u32(out, secondField + 12, put - secondField);
        u32(out, secondField + 16, 0);
        u32(out, secondField + 20, offsets[1][0] - secondField);
        u32(out, secondField + 24, 0);
        u32(out, secondField + 28, offsets[1][2] - secondField);
        u32(out, secondField + 32, offsets[1][3] - secondField);
        u32(out, secondField + 36, offsets[1][4] - secondField);
        return Arrays.copyOf(out, put);
    }

    private static int u16(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 8);
        out[at + 1] = (byte) value;
        return at + 2;
    }

    private static void u32(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    private static int bytes(byte[] out, int at, int... values) {
        for (var value : values) {
            out[at++] = (byte) value;
        }
        return at;
    }
}
