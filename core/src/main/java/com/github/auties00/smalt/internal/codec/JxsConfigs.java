package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;

/**
 * Reads and writes the JPEG XS metadata the containers carry: the picture header of a codestream (ISO/IEC 21122-1),
 * the video information ({@code jpvi}) and profile and level ({@code jxpl}) boxes of ISO/IEC 21122-3, which form the
 * configuration of a JPEG XS track, and the elementary stream header ({@code jxes}) of the MPEG-2 transport stream
 * carriage (Rec. ITU-T H.222.0 Annex W).
 *
 * <p>The video information fields: {@code brat} is the largest bit rate in Mbit/s; {@code frat} is the interlace mode
 * (2 bits: 0 progressive, 1 top field first, 2 bottom field first), the frame rate denominator code (6 bits: 1 for 1,
 * 2 for 1.001), 8 reserved bits and the frame rate numerator (16 bits); {@code schar} is a valid flag (1 bit), 7
 * reserved bits, the bit depth minus one (4 bits) and the sampling structure (4 bits: 0 4:2:2, 1 4:4:4 Y'CbCr, 2 4:4:4
 * R'G'B', 3 4:2:0, 4 4:2:2:4, 5 4:4:4:4 Y'CbCr, 6 4:4:4:4 R'G'B'); {@code tcod} is a time code (hours, minutes,
 * seconds and frames, one byte each). Instances are immutable.
 */
public final class JxsConfigs {
    /**
     * The size of the {@code jpvi} box followed by the {@code jxpl} box.
     */
    public static final int CONFIG_SIZE = 22 + 12;

    /**
     * The size of the {@code jxes} box.
     */
    public static final int JXES_SIZE = 30;

    private JxsConfigs() {
        throw new AssertionError();
    }

    /**
     * What the main header of a codestream says about the picture.
     *
     * @param width the width of the sampling grid
     * @param height the height of the sampling grid
     * @param components the number of components
     * @param bitDepth the largest component precision
     * @param profile the profile code {@code Ppih}
     * @param level the level and sublevel {@code Plev}
     * @param colorTransform the color transformation {@code Cpih}
     * @param sampling the sampling structure code of {@code schar}, or -1 when no code describes the components
     */
    public record Picture(int width, int height, int components, int bitDepth, int profile, int level,
                          int colorTransform, int sampling) {
        /**
         * {@return the width of the decoded picture: twice the sampling grid for Bayer data}
         */
        public int pictureWidth() {
            return colorTransform == 3 ? width * 2 : width;
        }

        /**
         * {@return the height of the decoded picture: twice the sampling grid for Bayer data}
         */
        public int pictureHeight() {
            return colorTransform == 3 ? height * 2 : height;
        }
    }

    /**
     * The video information and profile of a JPEG XS track.
     *
     * @param brat the largest bit rate in Mbit/s
     * @param frat the field coding and frame rate word
     * @param schar the sample characteristics word
     * @param tcod the time code word
     * @param profile the profile code {@code Ppih}
     * @param level the level and sublevel {@code Plev}
     */
    public record Config(long brat, int frat, int schar, long tcod, int profile, int level) {
        /**
         * {@return the frame rate, or {@code null} when the word declares none}
         */
        public Rational frameRate() {
            var numerator = frat & 0xFFFF;
            var denominator = (frat >>> 24) & 0x3F;
            if (numerator == 0 || denominator < 1 || denominator > 2) {
                return null;
            }
            return denominator == 1 ? new Rational(numerator, 1) : new Rational(numerator * 1000L, 1001);
        }

        /**
         * {@return the interlacing of the frames, or {@code null} for progressive frames}
         */
        public Interlacing interlacing() {
            return switch (frat >>> 30) {
                case 1 -> Interlacing.TOP_FIELD_FIRST;
                case 2 -> Interlacing.BOTTOM_FIELD_FIRST;
                default -> null;
            };
        }
    }

    /**
     * Reads the main header of the codestream at {@code data[offset, offset + length)}.
     *
     * @param data the bytes
     * @param offset the position of the {@code SOC} marker
     * @param length the number of bytes available
     * @return the picture, or {@code null} when the bytes do not start a JPEG XS codestream with a picture header
     */
    public static Picture picture(byte[] data, int offset, int length) {
        var end = offset + length;
        if (length < 4 || u16(data, offset) != 0xFF10 || u16(data, offset + 2) != 0xFF50 || offset + 6 > end) {
            return null;
        }
        var pih = offset + 4 + u16(data, offset + 4);
        if (pih + 28 > end || u16(data, pih) != 0xFF12) {
            return null;
        }
        var width = u16(data, pih + 12);
        var height = u16(data, pih + 14);
        var components = data[pih + 20] & 0xFF;
        var transform = data[pih + 25] & 0x0F;
        var picture = new Picture(width, height, components, 0, u16(data, pih + 8), u16(data, pih + 10), transform,
                -1);
        var marker = pih + 28;
        while (marker + 4 <= end && (data[marker] & 0xFF) == 0xFF && (data[marker + 1] & 0xFF) != 0x13
                && (data[marker + 1] & 0xFF) != 0x20 && (data[marker + 1] & 0xFF) != 0x21) {
            marker += 2 + u16(data, marker + 2);
        }
        if (width == 0 || height == 0 || marker + 4 + 2 * components > end || u16(data, marker) != 0xFF13) {
            return width == 0 || height == 0 ? null : picture;
        }
        var depth = 0;
        var sx = new int[components];
        var sy = new int[components];
        for (var c = 0; c < components; c++) {
            depth = Math.max(depth, data[marker + 4 + 2 * c] & 0xFF);
            sx[c] = (data[marker + 5 + 2 * c] & 0xFF) >>> 4;
            sy[c] = data[marker + 5 + 2 * c] & 0x0F;
        }
        return new Picture(width, height, components, depth, picture.profile(), picture.level(), transform,
                sampling(components, sx, sy, transform));
    }

    private static int sampling(int components, int[] sx, int[] sy, int transform) {
        if (components != 3 && components != 4 || transform == 3) {
            return -1;
        }
        var full = sx[1] == 1 && sy[1] == 1 && sx[2] == 1 && sy[2] == 1;
        var h422 = sx[1] == 2 && sy[1] == 1 && sx[2] == 2 && sy[2] == 1;
        var h420 = sx[1] == 2 && sy[1] == 2 && sx[2] == 2 && sy[2] == 2;
        if (components == 3) {
            if (full) {
                return transform == 1 ? 2 : 1;
            }
            return h422 ? 0 : h420 ? 3 : -1;
        }
        if (sx[3] != 1 || sy[3] != 1) {
            return -1;
        }
        if (full) {
            return transform == 1 ? 6 : 5;
        }
        return h422 ? 4 : -1;
    }

    /**
     * Returns the field coding and frame rate word of a frame rate.
     *
     * @param frameRate the frame rate, or {@code null}
     * @param interlacing the field order, or {@code null} for progressive frames
     * @return the word, its numerator and denominator zero when the rate is unknown or not an integer rate over 1
     * or 1.001
     */
    public static int frat(Rational frameRate, Interlacing interlacing) {
        var mode = interlacing == Interlacing.TOP_FIELD_FIRST ? 1 : interlacing == Interlacing.BOTTOM_FIELD_FIRST ? 2
                : 0;
        var word = mode << 30;
        if (frameRate == null || frameRate.numerator() <= 0 || frameRate.denominator() <= 0) {
            return word;
        }
        var numerator = frameRate.numerator();
        var denominator = frameRate.denominator();
        if (numerator % denominator == 0 && numerator / denominator <= 0xFFFF) {
            return word | 1 << 24 | (int) (numerator / denominator);
        }
        if (denominator == 1001 && numerator % 1000 == 0 && numerator / 1000 <= 0xFFFF) {
            return word | 2 << 24 | (int) (numerator / 1000);
        }
        return word;
    }

    /**
     * Returns the sample characteristics word of a picture.
     *
     * @param picture the components, depth and sampling of a codestream
     * @return the word, with the valid flag clear when no sampling structure code describes the components
     */
    public static int schar(Picture picture) {
        if (picture.sampling() < 0 || picture.bitDepth() < 1) {
            return 0;
        }
        return 0x8000 | (picture.bitDepth() - 1) << 4 | picture.sampling();
    }

    /**
     * Returns the configuration of a JPEG XS track: the {@code jpvi} box followed by the {@code jxpl} box.
     *
     * @param config the fields
     * @return the {@value #CONFIG_SIZE} bytes
     */
    public static byte[] config(Config config) {
        var out = new byte[CONFIG_SIZE];
        u32(out, 0, 22);
        type(out, 4, "jpvi");
        u32(out, 8, config.brat());
        u32(out, 12, config.frat() & 0xFFFFFFFFL);
        u16(out, 16, config.schar());
        u32(out, 18, config.tcod());
        u32(out, 22, 12);
        type(out, 26, "jxpl");
        u16(out, 30, config.profile());
        u16(out, 32, config.level());
        return out;
    }

    /**
     * Reads a configuration: any sequence of boxes among which the {@code jpvi} and {@code jxpl} boxes are taken.
     *
     * @param data the boxes
     * @return the fields, or {@code null} when neither box is present
     */
    public static Config parseConfig(byte[] data) {
        if (data == null) {
            return null;
        }
        var found = false;
        long brat = 0;
        var frat = 0;
        var schar = 0;
        long tcod = 0;
        var profile = 0;
        var level = 0;
        var position = 0;
        while (position + 8 <= data.length) {
            var size = u32(data, position);
            if (size < 8 || size > data.length - position) {
                break;
            }
            var type = new String(data, position + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (type.equals("jpvi") && size >= 22) {
                brat = u32(data, position + 8);
                frat = (int) u32(data, position + 12);
                schar = u16(data, position + 16);
                tcod = u32(data, position + 18);
                found = true;
            } else if (type.equals("jxpl") && size >= 12) {
                profile = u16(data, position + 8);
                level = u16(data, position + 10);
                found = true;
            }
            position += (int) size;
        }
        return found ? new Config(brat, frat, schar, tcod, profile, level) : null;
    }

    /**
     * Returns the {@code jxes} elementary stream header of an access unit.
     *
     * @param config the video information and profile
     * @param color the color description, or {@code null} for unspecified
     * @return the {@value #JXES_SIZE} bytes
     */
    public static byte[] jxes(Config config, ColorInfo color) {
        var out = new byte[JXES_SIZE];
        u32(out, 0, JXES_SIZE);
        type(out, 4, "jxes");
        u32(out, 8, config.brat());
        u32(out, 12, config.frat() & 0xFFFFFFFFL);
        u16(out, 16, config.schar());
        u16(out, 18, config.profile());
        u16(out, 20, config.level());
        out[22] = (byte) (color == null ? 2 : color.primaries().code());
        out[23] = (byte) (color == null ? 2 : color.transfer().code());
        out[24] = (byte) (color == null ? 2 : color.matrix().code());
        out[25] = (byte) (color != null && color.range() == ColorRange.FULL ? 0x80 : 0);
        u32(out, 26, config.tcod());
        return out;
    }

    /**
     * Returns whether {@code data} holds a {@code jxes} header at {@code offset}.
     *
     * @param data the bytes
     * @param offset the position
     * @param end the end of the data (exclusive)
     * @return whether a complete {@code jxes} box starts there
     */
    public static boolean isJxes(byte[] data, int offset, int end) {
        return end - offset >= JXES_SIZE && u32(data, offset) >= JXES_SIZE && data[offset + 4] == 'j'
                && data[offset + 5] == 'x' && data[offset + 6] == 'e' && data[offset + 7] == 's'
                && u32(data, offset) <= end - offset;
    }

    /**
     * Reads the configuration fields of a {@code jxes} header.
     *
     * @param data the bytes
     * @param offset the position of the header, checked by {@link #isJxes}
     * @return the fields
     */
    public static Config jxesConfig(byte[] data, int offset) {
        return new Config(u32(data, offset + 8), (int) u32(data, offset + 12), u16(data, offset + 16),
                u32(data, offset + 26), u16(data, offset + 18), u16(data, offset + 20));
    }

    /**
     * Reads the color description of a {@code jxes} header.
     *
     * @param data the bytes
     * @param offset the position of the header, checked by {@link #isJxes}
     * @return the color description
     */
    public static ColorInfo jxesColor(byte[] data, int offset) {
        return new ColorInfo(MatrixCoefficients.of(data[offset + 24] & 0xFF),
                (data[offset + 25] & 0x80) != 0 ? ColorRange.FULL : ColorRange.LIMITED,
                ColorPrimaries.of(data[offset + 22] & 0xFF), TransferCharacteristics.of(data[offset + 23] & 0xFF));
    }

    /**
     * {@return the length of the header box at {@code offset}, read by {@link #isJxes}}
     *
     * @param data the bytes
     * @param offset the position
     */
    public static int jxesLength(byte[] data, int offset) {
        return (int) u32(data, offset);
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }

    private static void u16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 8);
        out[offset + 1] = (byte) value;
    }

    private static void u32(byte[] out, int offset, long value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }

    private static void type(byte[] out, int offset, String type) {
        for (var i = 0; i < 4; i++) {
            out[offset + i] = (byte) type.charAt(i);
        }
    }
}
