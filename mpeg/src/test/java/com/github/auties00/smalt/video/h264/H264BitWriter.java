package com.github.auties00.smalt.video.h264;

import java.io.ByteArrayOutputStream;

/**
 * Writes H.264 syntax elements (fixed-length fields and Exp-Golomb codes) for tests that build
 * parameter sets and slices by hand, and escapes the result into a NAL unit.
 */
public final class H264BitWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current;
    private int filled;

    public H264BitWriter bits(long value, int count) {
        for (var i = count - 1; i >= 0; i--) {
            bit((int) (value >>> i) & 1);
        }
        return this;
    }

    public H264BitWriter bit(int value) {
        current = (current << 1) | (value & 1);
        if (++filled == 8) {
            out.write(current);
            current = 0;
            filled = 0;
        }
        return this;
    }

    public H264BitWriter flag(boolean value) {
        return bit(value ? 1 : 0);
    }

    public H264BitWriter ue(long value) {
        var code = value + 1;
        var length = 63 - Long.numberOfLeadingZeros(code);
        bits(0, length);
        return bits(code, length + 1);
    }

    public H264BitWriter se(long value) {
        return ue(value > 0 ? 2 * value - 1 : -2 * value);
    }

    /** Returns whether the next bit starts a byte. */
    public boolean aligned() {
        return filled == 0;
    }

    /** Appends the rbsp_trailing_bits and returns the payload bytes. */
    public byte[] trailing() {
        bit(1);
        while (filled != 0) {
            bit(0);
        }
        return out.toByteArray();
    }

    /** Returns the bytes written so far, padding a partial byte with zeros. */
    public byte[] bytes() {
        var copy = new ByteArrayOutputStream();
        copy.writeBytes(out.toByteArray());
        if (filled != 0) {
            copy.write(current << (8 - filled));
        }
        return copy.toByteArray();
    }

    /** Builds a NAL unit: the header byte followed by the escaped payload. */
    public static byte[] nal(int refIdc, int type, byte[] rbsp) {
        var result = new ByteArrayOutputStream();
        result.write((refIdc << 5) | type);
        var zeros = 0;
        for (var b : rbsp) {
            var value = b & 0xFF;
            if (zeros >= 2 && value <= 3) {
                result.write(3);
                zeros = 0;
            }
            result.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return result.toByteArray();
    }

    /**
     * Builds a High profile SPS with the given size in macroblocks, POC type 2 and no VUI.
     */
    public static byte[] sps(int profile, int widthMbs, int heightMbs, int maxRefFrames) {
        var writer = new H264BitWriter();
        writer.bits(profile, 8).bits(0, 8).bits(40, 8).ue(0);
        if (profile == 100) {
            writer.ue(1).ue(0).ue(0).bit(0).bit(0);
        }
        writer.ue(0).ue(2).ue(maxRefFrames).bit(0).ue(widthMbs - 1).ue(heightMbs - 1).bit(1).bit(1).bit(0).bit(0);
        return nal(3, 7, writer.trailing());
    }

    /** Builds a CAVLC PPS referring to SPS 0. */
    public static byte[] pps() {
        var writer = new H264BitWriter();
        writer.ue(0).ue(0).bit(0).bit(0).ue(0).ue(0).ue(0).bit(0).bits(0, 2).se(0).se(0).se(0).bit(1).bit(0).bit(0);
        return nal(3, 8, writer.trailing());
    }

    /**
     * Builds the header of an IDR I slice for {@link #sps} and {@link #pps}, followed by the
     * given macroblock layer bits.
     */
    public static byte[] idrSlice(byte[] macroblocks) {
        var writer = new H264BitWriter();
        writer.ue(0).ue(7).ue(0).bits(0, 4).ue(0).bit(0).bit(0).se(0).ue(1);
        for (var b : macroblocks) {
            writer.bits(b & 0xFF, 8);
        }
        return nal(3, 5, writer.trailing());
    }
}
