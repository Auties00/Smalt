package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;

import java.util.Arrays;

/**
 * Holds one Enhanced Reference Picture Selection layer of a picture, group of blocks or slice header (ITU-T H.263
 * U.3.1.5).
 *
 * <p>The layer re-maps the relative indexes of the multi-picture buffer for the picture or video picture segment it
 * precedes, and, for pictures other than B-pictures, tells how the decoded picture is buffered and which memory
 * management control operations apply after it.
 */
final class ErpsLayer {
    /**
     * The re-mapping of a picture number by a negative difference (RMPNI "1").
     */
    static final int REMAP_NEGATIVE = 0;

    /**
     * The re-mapping of a picture number by a positive difference (RMPNI "010").
     */
    static final int REMAP_POSITIVE = 1;

    /**
     * The re-mapping of a long-term picture (RMPNI "011").
     */
    static final int REMAP_LONG_TERM = 2;

    /**
     * The operation marking a short-term picture as unused (MMCO "011").
     */
    static final int MMCO_SHORT_UNUSED = 1;

    /**
     * The operation marking a long-term picture as unused (MMCO "0100").
     */
    static final int MMCO_LONG_UNUSED = 2;

    /**
     * The operation assigning a long-term index to a short-term picture (MMCO "0101").
     */
    static final int MMCO_ASSIGN_LONG = 3;

    /**
     * The operation marking sub-picture areas of a short-term picture as unused (MMCO "00100").
     */
    static final int MMCO_SHORT_AREAS = 4;

    /**
     * The operation marking sub-picture areas of a long-term picture as unused (MMCO "00101").
     */
    static final int MMCO_LONG_AREAS = 5;

    /**
     * The operation setting the largest long-term index plus one (MMCO "00110").
     */
    static final int MMCO_MAX_LONG = 6;

    /**
     * The operation setting the buffer size and structure (MMCO "00111").
     */
    static final int MMCO_BUFFER = 7;

    /**
     * The most re-mappings or memory management operations one layer may carry.
     */
    private static final int MAX_OPERATIONS = 4096;

    /**
     * Whether more than one reference picture may be used (MRPA).
     */
    boolean multiple;

    /**
     * The number of re-mappings.
     */
    int remaps;

    /**
     * The kind of every re-mapping, one of the {@code REMAP_} constants.
     */
    int[] remapKinds = new int[8];

    /**
     * The ADPN or LPIR value of every re-mapping.
     */
    int[] remapValues = new int[8];

    /**
     * Whether a B-picture predicts backward from two pictures (BTPSM).
     */
    boolean twoBackward;

    /**
     * Whether the picture is buffered by the sliding window (RPBT "1") rather than by adaptive memory control.
     */
    boolean slidingWindow = true;

    /**
     * The number of memory management control operations.
     */
    int operations;

    /**
     * The kind of every operation, one of the {@code MMCO_} constants.
     */
    int[] operationKinds = new int[8];

    /**
     * The first value of every operation: DPN, LPIN, MLIP1 or SPWI.
     */
    int[] operationFirst = new int[8];

    /**
     * The second value of every operation: LPIN, or SPHI.
     */
    int[] operationSecond = new int[8];

    /**
     * The third value of every operation: SPTN.
     */
    int[] operationThird = new int[8];

    /**
     * The fourth value of every operation: RESET.
     */
    int[] operationFourth = new int[8];

    /**
     * The sub-picture removal bit-map of every operation that marks areas, or {@code null}.
     */
    boolean[][] operationAreas = new boolean[8][];

    /**
     * Creates an empty layer.
     */
    ErpsLayer() {
    }

    /**
     * Parses an ERPS layer.
     *
     * @param reader the stream, positioned at the layer
     * @param type the picture type, one of the {@code PictureHeader} type constants
     * @param width the picture width
     * @param height the picture height
     * @param spwi the sub-picture width indication in effect, or -1 before any buffer structure
     * @param sphi the sub-picture height indication in effect
     * @throws MediaException.Malformed if the layer is corrupt
     */
    void parse(BitReader reader, int type, int width, int height, int spwi, int sphi) {
        multiple = false;
        remaps = 0;
        twoBackward = false;
        slidingWindow = true;
        operations = 0;
        var intra = type == PictureHeader.I || type == PictureHeader.EI;
        if (!intra) {
            multiple = reader.readFlag();
            while (true) {
                int kind;
                if (reader.readBit() == 1) {
                    kind = REMAP_NEGATIVE;
                } else {
                    var code = reader.read(2);
                    if (code == 0b10) {
                        kind = REMAP_POSITIVE;
                    } else if (code == 0b11) {
                        kind = REMAP_LONG_TERM;
                    } else if (code == 0b01) {
                        break;
                    } else {
                        throw new MediaException.Malformed("invalid H.263 RMPNI");
                    }
                }
                var value = PlusTables.readIndex(reader);
                if (value == PlusTables.INVALID || reader.overrun() || remaps >= MAX_OPERATIONS) {
                    throw new MediaException.Malformed("invalid H.263 picture re-mapping");
                }
                if (remaps == remapKinds.length) {
                    remapKinds = Arrays.copyOf(remapKinds, remaps * 2);
                    remapValues = Arrays.copyOf(remapValues, remaps * 2);
                }
                remapKinds[remaps] = kind;
                remapValues[remaps] = kind == REMAP_LONG_TERM ? value : value + 1;
                remaps++;
            }
        }
        if (type == PictureHeader.B) {
            twoBackward = multiple && reader.readFlag();
            return;
        }
        slidingWindow = reader.readFlag();
        if (slidingWindow) {
            return;
        }
        while (true) {
            int kind;
            if (reader.readBit() == 1) {
                break;
            }
            if (reader.readBit() == 1) {
                if (reader.readBit() == 1) {
                    kind = MMCO_SHORT_UNUSED;
                } else {
                    kind = reader.readBit() == 0 ? MMCO_LONG_UNUSED : MMCO_ASSIGN_LONG;
                }
            } else if (reader.readBit() == 1) {
                kind = MMCO_SHORT_AREAS + reader.read(2);
            } else {
                throw new MediaException.Malformed("invalid H.263 MMCO");
            }
            if (operations >= MAX_OPERATIONS) {
                throw new MediaException.Malformed("too many H.263 MMCO commands");
            }
            if (operations == operationKinds.length) {
                var size = operations * 2;
                operationKinds = Arrays.copyOf(operationKinds, size);
                operationFirst = Arrays.copyOf(operationFirst, size);
                operationSecond = Arrays.copyOf(operationSecond, size);
                operationThird = Arrays.copyOf(operationThird, size);
                operationFourth = Arrays.copyOf(operationFourth, size);
                operationAreas = Arrays.copyOf(operationAreas, size);
            }
            var at = operations++;
            operationKinds[at] = kind;
            operationAreas[at] = null;
            switch (kind) {
                case MMCO_SHORT_UNUSED, MMCO_LONG_UNUSED, MMCO_MAX_LONG -> operationFirst[at] = index(reader);
                case MMCO_ASSIGN_LONG -> {
                    operationFirst[at] = index(reader);
                    operationSecond[at] = index(reader);
                }
                case MMCO_SHORT_AREAS, MMCO_LONG_AREAS -> {
                    operationFirst[at] = index(reader);
                    operationAreas[at] = areas(reader, spwi < 0 ? 0 : subPictures(width, height, spwi, sphi));
                }
                default -> {
                    operationFirst[at] = reader.read(7);
                    operationSecond[at] = reader.read(7);
                    operationThird[at] = index(reader) + 1;
                    operationFourth[at] = reader.readBit();
                    spwi = operationFirst[at];
                    sphi = operationSecond[at];
                }
            }
            if (reader.overrun()) {
                throw new MediaException.Malformed("truncated H.263 ERPS layer");
            }
        }
    }

    /**
     * Returns the number of sub-pictures of a picture under a buffer structure (U.3.1.5.13 and U.3.1.5.14).
     *
     * @param width the picture width
     * @param height the picture height
     * @param spwi the sub-picture width indication
     * @param sphi the sub-picture height indication
     * @return the count, columns times rows
     */
    static int subPictures(int width, int height, int spwi, int sphi) {
        return columns(width, spwi) * rows(height, sphi);
    }

    /**
     * Returns the sub-picture columns of a picture.
     *
     * @param width the picture width
     * @param spwi the sub-picture width indication
     * @return the columns
     */
    static int columns(int width, int spwi) {
        var mbs = (width + 15) >> 4;
        return (mbs + spwi) / (spwi + 1);
    }

    /**
     * Returns the sub-picture rows of a picture.
     *
     * @param height the picture height
     * @param sphi the sub-picture height indication, at least 1
     * @return the rows
     */
    static int rows(int height, int sphi) {
        var mbs = (height + 15) >> 4;
        return (mbs + sphi - 1) / Math.max(1, sphi);
    }

    /**
     * Reads a value of Table U.1.
     *
     * @param reader the stream
     * @return the value
     */
    private static int index(BitReader reader) {
        var value = PlusTables.readIndex(reader);
        if (value == PlusTables.INVALID) {
            throw new MediaException.Malformed("invalid H.263 ERPS value");
        }
        return value;
    }

    /**
     * Reads a sub-picture removal bit-map with its emulation prevention bits (U.3.1.5.10 and U.3.1.5.11).
     *
     * @param reader the stream
     * @param count the bits of the map
     * @return the map
     */
    private static boolean[] areas(BitReader reader, int count) {
        if (count <= 0 || count > 1 << 16) {
            throw new MediaException.Malformed("H.263 sub-picture removal without a buffer structure");
        }
        var result = new boolean[count];
        var zeros = 0;
        for (var i = 0; i < count; i++) {
            var bit = reader.readBit();
            result[i] = bit == 1;
            zeros = bit == 0 ? zeros + 1 : 0;
            if (zeros == 8) {
                reader.marker("SPRB");
                zeros = 0;
            }
        }
        return result;
    }
}
