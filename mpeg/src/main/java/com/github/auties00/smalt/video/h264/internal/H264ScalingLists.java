package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Parses and resolves the H.264 quantization scaling lists of sequence and picture parameter
 * sets.
 *
 * <p>Resolved lists are stored in raster order ({@code y * size + x}), ready to multiply the
 * normalization factors. Lists 0 to 5 are the 4x4 lists (Y, Cb, Cr intra, then Y, Cb, Cr inter);
 * lists 0 to 5 of the 8x8 set are Y intra, Y inter, Cb intra, Cb inter, Cr intra, Cr inter.
 */
public final class H264ScalingLists {
    private H264ScalingLists() {
        throw new AssertionError();
    }

    /**
     * Returns the flat 4x4 lists: six lists of sixteen entries equal to 16, in raster order.
     *
     * @return a new array of six flat lists
     */
    public static int[][] flat4x4() {
        var result = new int[6][16];
        for (var list : result) {
            Arrays.fill(list, 16);
        }
        return result;
    }

    /**
     * Returns the flat 8x8 lists: six lists of 64 entries equal to 16, in raster order.
     *
     * @return a new array of six flat lists
     */
    public static int[][] flat8x8() {
        var result = new int[6][64];
        for (var list : result) {
            Arrays.fill(list, 16);
        }
        return result;
    }

    /**
     * Parses the scaling lists of a parameter set and resolves the absent ones with the
     * fall-back rules of ITU-T H.264 Table 7-2.
     *
     * @param reader the reader positioned after the {@code *_scaling_matrix_present_flag}
     * @param count the number of {@code *_scaling_list_present_flag} entries: 6, 8 or 12
     * @param fallback4 the 4x4 lists used by fall-back rule B, or {@code null} for rule A
     * @param fallback8 the 8x8 lists used by fall-back rule B, or {@code null} for rule A
     * @param out4 receives the six resolved 4x4 lists in raster order
     * @param out8 receives the six resolved 8x8 lists in raster order
     */
    public static void parse(H264BitReader reader, int count, int[][] fallback4, int[][] fallback8,
                             int[][] out4, int[][] out8) {
        var zigzag = new int[64];
        for (var i = 0; i < 12; i++) {
            var is4x4 = i < 6;
            var size = is4x4 ? 16 : 64;
            var present = i < count && reader.readFlag();
            int[] target = is4x4 ? out4[i] : out8[i - 6];
            if (present) {
                var useDefault = readList(reader, zigzag, size);
                if (useDefault) {
                    toRaster(defaultList(i), target, size);
                } else {
                    toRaster(zigzag, target, size);
                }
                continue;
            }
            if (i >= count && !is4x4 && i >= 8) {
                var reference = out8[i - 8];
                System.arraycopy(reference, 0, target, 0, 64);
                continue;
            }
            if (fallback4 == null) {
                if (i == 0 || i == 3 || i == 6 || i == 7) {
                    toRaster(defaultList(i), target, size);
                } else if (is4x4) {
                    System.arraycopy(out4[i - 1], 0, target, 0, 16);
                } else {
                    System.arraycopy(out8[i - 8], 0, target, 0, 64);
                }
            } else {
                if (i == 0 || i == 3) {
                    System.arraycopy(fallback4[i], 0, target, 0, 16);
                } else if (i == 6 || i == 7) {
                    System.arraycopy(fallback8[i - 6], 0, target, 0, 64);
                } else if (is4x4) {
                    System.arraycopy(out4[i - 1], 0, target, 0, 16);
                } else {
                    System.arraycopy(out8[i - 8], 0, target, 0, 64);
                }
            }
        }
    }

    /**
     * Reads one {@code scaling_list()} syntax structure (ITU-T H.264 7.3.2.1.1.1).
     *
     * @param reader the reader positioned at the list
     * @param list receives the list in zig-zag order
     * @param size the list size, 16 or 64
     * @return the {@code useDefaultScalingMatrixFlag}
     */
    private static boolean readList(H264BitReader reader, int[] list, int size) {
        var lastScale = 8;
        var nextScale = 8;
        var useDefault = false;
        for (var j = 0; j < size; j++) {
            if (nextScale != 0) {
                var delta = reader.readSe();
                if (delta < -128 || delta > 127) {
                    throw new com.github.auties00.smalt.MediaException.Malformed("scaling list delta " + delta + " out of range");
                }
                nextScale = (lastScale + delta + 256) & 0xFF;
                useDefault = j == 0 && nextScale == 0;
            }
            list[j] = nextScale == 0 ? lastScale : nextScale;
            lastScale = list[j];
        }
        return useDefault;
    }

    /**
     * Returns the default list of a list index, in zig-zag order.
     *
     * @param index the list index, 0 to 11
     * @return the default list
     */
    private static int[] defaultList(int index) {
        byte[] source;
        if (index < 6) {
            source = index < 3 ? H264Tables.DEFAULT_4X4_INTRA : H264Tables.DEFAULT_4X4_INTER;
        } else {
            source = (index & 1) == 0 ? H264Tables.DEFAULT_8X8_INTRA : H264Tables.DEFAULT_8X8_INTER;
        }
        var result = new int[source.length];
        for (var i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    /**
     * Converts a list from zig-zag to raster order.
     *
     * @param zigzag the list in zig-zag order
     * @param raster receives the list in raster order
     * @param size the list size, 16 or 64
     */
    private static void toRaster(int[] zigzag, int[] raster, int size) {
        var scan = size == 16 ? H264Tables.ZIGZAG_4X4 : H264Tables.ZIGZAG_8X8;
        for (var i = 0; i < size; i++) {
            raster[scan[i]] = zigzag[i];
        }
    }
}
