package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.container.internal.RawVideoTags;

/**
 * Reads what the container layer needs from the frames and configurations of the intermediate and image video codecs
 * ({@link com.github.auties00.smalt.track.Codec#PNG}, {@code TIFF}, {@code GIF}, {@code QTRLE}, {@code CFHD} and
 * {@code HAP}): key frames of CineForm and GIF, the HAP sample entry type and the QuickTime Animation depth and
 * color table.
 *
 * <p>Every method treats its input as untrusted and never throws for a short or damaged frame.
 */
public final class IntermediateFrames {
    /**
     * The HAP sample entry type of DXT1 frames ({@code Hap1}).
     */
    public static final int HAP1 = 0x48617031;

    /**
     * The HAP Alpha sample entry type ({@code Hap5}).
     */
    public static final int HAP5 = 0x48617035;

    /**
     * The HAP Q sample entry type ({@code HapY}).
     */
    public static final int HAPY = 0x48617059;

    /**
     * The HAP Q Alpha sample entry type ({@code HapM}).
     */
    public static final int HAPM = 0x4861704D;

    /**
     * The HAP Alpha Only sample entry type ({@code HapA}).
     */
    public static final int HAPA = 0x48617041;

    /**
     * The HAP R sample entry type ({@code Hap7}).
     */
    public static final int HAP7 = 0x48617037;

    /**
     * The HAP HDR sample entry type ({@code HapH}).
     */
    public static final int HAPH = 0x48617048;

    /**
     * The CineForm sample type of the second frame of a group.
     */
    private static final int CFHD_SECOND_FRAME = 1;

    /**
     * The CineForm sample type of an inter frame.
     */
    private static final int CFHD_INTER_FRAME = 10;

    private IntermediateFrames() {
        throw new AssertionError();
    }

    /**
     * Returns whether a type is one of the HAP sample entry types.
     *
     * @param type the type, four ASCII characters packed big-endian
     * @return {@code true} for {@code Hap1}, {@code Hap5}, {@code HapY}, {@code HapM}, {@code HapA}, {@code Hap7} and
     * {@code HapH}
     */
    public static boolean isHapType(int type) {
        return type == HAP1 || type == HAP5 || type == HAPY || type == HAPM || type == HAPA || type == HAP7
                || type == HAPH;
    }

    /**
     * Returns the HAP sample entry type of a track: the one its configuration names or, without one, the one the
     * texture format of its first frame implies.
     *
     * @param config the track's configuration, or {@code null}
     * @param frame the first frame, or {@code null} when none is known
     * @return the type; {@link #HAP1} when neither tells
     */
    public static int hapType(byte[] config, byte[] frame) {
        if (config != null && config.length == 4) {
            var type = (config[0] & 0xFF) << 24 | (config[1] & 0xFF) << 16 | (config[2] & 0xFF) << 8 | config[3] & 0xFF;
            if (isHapType(type)) {
                return type;
            }
        }
        if (frame == null || frame.length < 4) {
            return HAP1;
        }
        var section = frame[3] & 0xFF;
        if (section == 0x0D) {
            return HAPM;
        }
        return switch (section & 0x0F) {
            case 0x0E -> HAP5;
            case 0x0F -> HAPY;
            case 0x01 -> HAPA;
            case 0x0C -> HAP7;
            case 0x02, 0x03 -> HAPH;
            default -> HAP1;
        };
    }

    /**
     * Returns the depth of a HAP sample entry: 32 for the types that carry alpha, 24 otherwise.
     *
     * @param type the sample entry type
     * @return the depth
     */
    public static int hapDepth(int type) {
        return type == HAP5 || type == HAPM || type == HAPA || type == HAP7 ? 32 : 24;
    }

    /**
     * Returns whether a CineForm sample starts a frame or group that decodes on its own: every sample but the
     * second of a group and an inter frame, as the sample type tag at its start declares.
     *
     * @param frame the sample
     * @param offset the index of its first byte
     * @param length the number of valid bytes
     * @return {@code true} for a key frame, and for a sample that does not start with its type tag
     */
    public static boolean isCineFormKey(byte[] frame, int offset, int length) {
        if (length < 4 || frame[offset] != 0 || frame[offset + 1] != 1) {
            return true;
        }
        var type = (frame[offset + 2] & 0xFF) << 8 | frame[offset + 3] & 0xFF;
        return type != CFHD_SECOND_FRAME && type != CFHD_INTER_FRAME;
    }

    /**
     * Returns whether a GIF video frame starts with the GIF header, and so decodes without the frames before it.
     *
     * @param frame the array holding the GIF frame
     * @param offset the index of its first byte
     * @param length the number of valid bytes
     * @return {@code true} when the frame starts with {@code GIF87a} or {@code GIF89a}
     */
    public static boolean isGifKey(byte[] frame, int offset, int length) {
        return length >= 6 && frame[offset] == 'G' && frame[offset + 1] == 'I' && frame[offset + 2] == 'F'
                && frame[offset + 3] == '8' && (frame[offset + 4] == '7' || frame[offset + 4] == '9')
                && frame[offset + 5] == 'a';
    }

    /**
     * Builds the configuration of a QuickTime Animation track from its sample description.
     *
     * @param depth the description's depth
     * @param colorTable the description's color table (seed, flags, size and entries), or {@code null} when it has
     * none
     * @return the configuration
     */
    public static byte[] qtrleConfig(int depth, byte[] colorTable) {
        var length = colorTable == null ? 0 : colorTable.length;
        var config = new byte[2 + length];
        config[0] = (byte) (depth >>> 8);
        config[1] = (byte) depth;
        if (length > 0) {
            System.arraycopy(colorTable, 0, config, 2, length);
        }
        return config;
    }

    /**
     * Returns the depth a QuickTime Animation configuration declares.
     *
     * @param config the configuration, or {@code null}
     * @return the depth, or 24 when the configuration is missing or too short
     */
    public static int qtrleDepth(byte[] config) {
        return config == null || config.length < 2 ? 24 : (config[0] & 0xFF) << 8 | config[1] & 0xFF;
    }

    /**
     * Returns the color table a QuickTime Animation configuration carries.
     *
     * @param config the configuration, or {@code null}
     * @return the table as QuickTime stores it, or {@code null} when the configuration has none
     */
    public static byte[] qtrleColorTable(byte[] config) {
        if (config == null || config.length <= 2) {
            return null;
        }
        var table = new byte[config.length - 2];
        System.arraycopy(config, 2, table, 0, table.length);
        return table;
    }

    /**
     * Returns the palette a QuickTime Animation configuration selects: for indices (depth 1, 2, 4 or 8) the color
     * table it carries or, without one, the default Macintosh table of the depth, and for gray indices (depth 33,
     * 34, 36 or 40) a ramp from white at index 0 to black at the last index.
     *
     * @param config the configuration, or {@code null}
     * @return the palette as opaque {@code 0xAARRGGBB} entries, or {@code null} for a depth without one
     */
    public static int[] qtrlePalette(byte[] config) {
        var depth = qtrleDepth(config);
        return switch (depth) {
            case 1, 2, 4, 8 -> {
                var table = palette(qtrleColorTable(config));
                yield table != null ? table : RawVideoTags.macintoshPalette(depth);
            }
            case 33, 34, 36, 40 -> RawVideoTags.invertedGray(1 << (depth - 32));
            default -> null;
        };
    }

    /**
     * Returns the palette of a QuickTime color table.
     *
     * @param table the table (seed, flags, size and 8-byte entries of index, red, green and blue), or {@code null}
     * @return the palette as {@code 0xAARRGGBB} entries, opaque, in table order, or {@code null} when the table is
     * missing or truncated
     */
    public static int[] palette(byte[] table) {
        if (table == null || table.length < 8) {
            return null;
        }
        var count = ((table[6] & 0xFF) << 8 | table[7] & 0xFF) + 1;
        if (count > 256 || table.length < 8 + 8 * count) {
            return null;
        }
        var palette = new int[count];
        for (var i = 0; i < count; i++) {
            var at = 8 + 8 * i;
            palette[i] = 0xFF000000 | (table[at + 2] & 0xFF) << 16 | (table[at + 4] & 0xFF) << 8 | table[at + 6] & 0xFF;
        }
        return palette;
    }
}
