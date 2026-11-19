package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the OBU type codes ({@code obu_type}, AV1 specification section 6.2.2) and the trailing
 * bit check that ends an OBU payload.
 *
 * @implNote This implementation ports dav1d's {@code check_trailing_bits} ({@code src/obu.c}).
 */
public final class Obu {
    /**
     * The sequence header OBU type.
     */
    public static final int OBU_SEQ_HDR = 1;

    /**
     * The temporal delimiter OBU type.
     */
    public static final int OBU_TD = 2;

    /**
     * The frame header OBU type.
     */
    public static final int OBU_FRAME_HDR = 3;

    /**
     * The tile group OBU type.
     */
    public static final int OBU_TILE_GRP = 4;

    /**
     * The metadata OBU type.
     */
    public static final int OBU_METADATA = 5;

    /**
     * The frame OBU type (a frame header followed by a tile group).
     */
    public static final int OBU_FRAME = 6;

    /**
     * The redundant frame header OBU type.
     */
    public static final int OBU_REDUNDANT_FRAME_HDR = 7;

    /**
     * The tile list OBU type, the input of large scale tile decoding.
     */
    public static final int OBU_TILE_LIST = 8;

    /**
     * The padding OBU type.
     */
    public static final int OBU_PADDING = 15;

    /**
     * Prevents instantiation.
     */
    private Obu() {
        throw new AssertionError();
    }

    /**
     * Checks the trailing bits that end an OBU payload ({@code trailing_bits()}).
     *
     * <p>A read past the end always fails; the trailing one bit and zero padding are checked only in
     * strict mode.
     *
     * @param gb the reader, positioned after the payload's syntax
     * @param strict whether to check the padding
     * @return {@code true} when the payload ends properly
     */
    public static boolean checkTrailingBits(BitReader gb, boolean strict) {
        var trailingOneBit = gb.bit();
        if (gb.error()) {
            return false;
        }
        if (!strict) {
            return true;
        }
        if (trailingOneBit == 0 || gb.state() != 0) {
            return false;
        }
        var data = gb.data();
        var size = gb.end() - gb.position();
        while (size > 0 && data[gb.position() + size - 1] == 0) {
            size--;
        }
        return size == 0;
    }
}
