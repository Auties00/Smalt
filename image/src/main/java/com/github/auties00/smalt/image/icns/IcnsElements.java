package com.github.auties00.smalt.image.icns;

import com.github.auties00.smalt.MediaException;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Describes the element types of an ICNS icon family and codes their pixel data: the 1, 4 and 8-bit indexed icons
 * with the Mac OS system palettes, the 24-bit icons of three run-length coded planes with their 8-bit masks, and the
 * ARGB icons of four.
 */
final class IcnsElements {
    /**
     * The type of the element holding the dark appearance of the family, itself a complete icon family.
     */
    static final String DARK = new String(new byte[]{(byte) 0xFD, (byte) 0xD9, 0x2F, (byte) 0xA8},
            java.nio.charset.StandardCharsets.ISO_8859_1);

    /**
     * Enumerates how an element type stores its pixels.
     */
    enum Kind {
        /**
         * A 1-bit icon without a mask.
         */
        MONO,

        /**
         * A 1-bit icon followed by its 1-bit mask.
         */
        MONO_MASKED,

        /**
         * A 4 or 8-bit icon indexing a system palette, masked by the 1-bit element of its size.
         */
        INDEXED,

        /**
         * A 24-bit icon of three planes, masked by an 8-bit mask element.
         */
        RGB,

        /**
         * An 8-bit mask.
         */
        MASK,

        /**
         * A PNG file, a JPEG 2000 file or codestream, or ARGB planes.
         */
        COMPRESSED
    }

    /**
     * Describes an element type.
     *
     * @param code the four-character type
     * @param width the width of its image in pixels
     * @param height the height of its image in pixels
     * @param kind how the element stores its pixels
     * @param depth the bits per pixel of an indexed or 1-bit icon, 0 otherwise
     * @param mask the type of the element masking it, or {@code null}
     */
    record Type(String code, int width, int height, Kind kind, int depth, String mask) {
    }

    /**
     * The element types, by code.
     */
    private static final Map<String, Type> TYPES = Map.ofEntries(
            type("ICON", 32, 32, Kind.MONO, 1, null),
            type("ICN#", 32, 32, Kind.MONO_MASKED, 1, null),
            type("icm#", 16, 12, Kind.MONO_MASKED, 1, null),
            type("icm4", 16, 12, Kind.INDEXED, 4, "icm#"),
            type("icm8", 16, 12, Kind.INDEXED, 8, "icm#"),
            type("ics#", 16, 16, Kind.MONO_MASKED, 1, null),
            type("ics4", 16, 16, Kind.INDEXED, 4, "ics#"),
            type("ics8", 16, 16, Kind.INDEXED, 8, "ics#"),
            type("is32", 16, 16, Kind.RGB, 0, "s8mk"),
            type("s8mk", 16, 16, Kind.MASK, 0, null),
            type("icl4", 32, 32, Kind.INDEXED, 4, "ICN#"),
            type("icl8", 32, 32, Kind.INDEXED, 8, "ICN#"),
            type("il32", 32, 32, Kind.RGB, 0, "l8mk"),
            type("l8mk", 32, 32, Kind.MASK, 0, null),
            type("ich#", 48, 48, Kind.MONO_MASKED, 1, null),
            type("ich4", 48, 48, Kind.INDEXED, 4, "ich#"),
            type("ich8", 48, 48, Kind.INDEXED, 8, "ich#"),
            type("ih32", 48, 48, Kind.RGB, 0, "h8mk"),
            type("h8mk", 48, 48, Kind.MASK, 0, null),
            type("it32", 128, 128, Kind.RGB, 0, "t8mk"),
            type("t8mk", 128, 128, Kind.MASK, 0, null),
            type("icp4", 16, 16, Kind.COMPRESSED, 0, null),
            type("icp5", 32, 32, Kind.COMPRESSED, 0, null),
            type("icp6", 48, 48, Kind.COMPRESSED, 0, null),
            type("ic04", 16, 16, Kind.COMPRESSED, 0, null),
            type("ic05", 32, 32, Kind.COMPRESSED, 0, null),
            type("icsb", 18, 18, Kind.COMPRESSED, 0, null),
            type("icsB", 36, 36, Kind.COMPRESSED, 0, null),
            type("sb24", 24, 24, Kind.COMPRESSED, 0, null),
            type("SB24", 48, 48, Kind.COMPRESSED, 0, null),
            type("ic11", 32, 32, Kind.COMPRESSED, 0, null),
            type("ic12", 64, 64, Kind.COMPRESSED, 0, null),
            type("ic07", 128, 128, Kind.COMPRESSED, 0, null),
            type("ic13", 256, 256, Kind.COMPRESSED, 0, null),
            type("ic08", 256, 256, Kind.COMPRESSED, 0, null),
            type("ic14", 512, 512, Kind.COMPRESSED, 0, null),
            type("ic09", 512, 512, Kind.COMPRESSED, 0, null),
            type("ic10", 1024, 1024, Kind.COMPRESSED, 0, null));

    /**
     * The 16-color Mac OS system palette, as 0xRRGGBB.
     */
    private static final int[] PALETTE_4 = {0xFFFFFF, 0xFCF305, 0xFF6402, 0xDD0806, 0xF20884, 0x4600A5, 0x0000D4,
            0x02ABEA, 0x1FB714, 0x006411, 0x562C05, 0x90713A, 0xC0C0C0, 0x808080, 0x404040, 0x000000};

    /**
     * The 256-color Mac OS system palette, as 0xRRGGBB.
     */
    private static final int[] PALETTE_8 = palette8();

    private IcnsElements() {
        throw new AssertionError();
    }

    /**
     * {@return the type with a code, or {@code null} for an element that holds no pixels or is unknown}
     *
     * @param code the four-character type
     */
    static Type of(String code) {
        return TYPES.get(code);
    }

    /**
     * Builds a map entry of the type table.
     */
    private static Map.Entry<String, Type> type(String code, int width, int height, Kind kind, int depth,
                                                String mask) {
        return Map.entry(code, new Type(code, width, height, kind, depth, mask));
    }

    /**
     * Builds the 256-color system palette: the 6 by 6 by 6 color cube from white down without black, ten-step ramps
     * of red, green, blue and gray that skip the cube's levels, and black.
     */
    private static int[] palette8() {
        var palette = new int[256];
        var levels = new int[]{0xFF, 0xCC, 0x99, 0x66, 0x33, 0x00};
        var index = 0;
        for (var r : levels) {
            for (var g : levels) {
                for (var b : levels) {
                    if ((r | g | b) != 0) {
                        palette[index++] = r << 16 | g << 8 | b;
                    }
                }
            }
        }
        var ramp = new int[]{0xEE, 0xDD, 0xBB, 0xAA, 0x88, 0x77, 0x55, 0x44, 0x22, 0x11};
        for (var shift = 16; shift >= 0; shift -= 8) {
            for (var value : ramp) {
                palette[index++] = value << shift;
            }
        }
        for (var value : ramp) {
            palette[index++] = value * 0x010101;
        }
        return palette;
    }

    /**
     * {@return the number of bytes of one row of a packed icon}
     *
     * @param width the width in pixels
     * @param depth the bits per pixel
     */
    static int rowBytes(int width, int depth) {
        return (width * depth + 7) / 8;
    }

    /**
     * Expands an indexed or 1-bit icon to RGB.
     *
     * @param data the element data
     * @param offset the offset of the icon in {@code data}
     * @param length the bytes available from {@code offset}
     * @param type the element type
     * @param pixels the packed RGBA target, whose alpha is left untouched
     * @throws MediaException.Malformed if the data is shorter than the icon
     */
    static void expand(byte[] data, int offset, int length, Type type, byte[] pixels) {
        var depth = type.depth();
        var stride = rowBytes(type.width(), depth);
        if (length < stride * type.height()) {
            throw new MediaException.Malformed("the ICNS element " + type.code() + " holds " + length + " of "
                    + stride * type.height() + " bytes");
        }
        var mask = (1 << depth) - 1;
        for (var y = 0; y < type.height(); y++) {
            var row = offset + y * stride;
            for (var x = 0; x < type.width(); x++) {
                var bit = x * depth;
                var value = (data[row + (bit >>> 3)] & 0xFF) >>> (8 - depth - (bit & 7)) & mask;
                var rgb = switch (depth) {
                    case 1 -> value == 0 ? 0xFFFFFF : 0;
                    case 4 -> PALETTE_4[value];
                    default -> PALETTE_8[value];
                };
                var at = (y * type.width() + x) * 4;
                pixels[at] = (byte) (rgb >>> 16);
                pixels[at + 1] = (byte) (rgb >>> 8);
                pixels[at + 2] = (byte) rgb;
            }
        }
    }

    /**
     * Writes the alpha of a 1-bit mask: 255 where a bit is set, 0 elsewhere.
     *
     * @param data the element data
     * @param offset the offset of the mask in {@code data}
     * @param length the bytes available from {@code offset}
     * @param width the width in pixels
     * @param height the height in pixels
     * @param pixels the packed RGBA target
     * @return whether the mask was complete and applied
     */
    static boolean applyBitMask(byte[] data, int offset, int length, int width, int height, byte[] pixels) {
        var stride = rowBytes(width, 1);
        if (offset < 0 || length < stride * height) {
            return false;
        }
        for (var y = 0; y < height; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < width; x++) {
                var set = (data[row + (x >>> 3)] & (0x80 >>> (x & 7))) != 0;
                pixels[(y * width + x) * 4 + 3] = set ? (byte) 0xFF : 0;
            }
        }
        return true;
    }

    /**
     * Unpacks run-length coded planes: a control byte below 128 copies that many plus one literal bytes, one of 128
     * or more repeats the next byte the control minus 125 times. Each plane is coded on its own; a run that passes
     * the end of a plane is cut there.
     *
     * @param data the coded bytes
     * @param position the offset of the first control byte
     * @param end the offset past the last coded byte
     * @param planes the number of planes
     * @param count the samples of one plane
     * @return the planes, one after the other
     * @throws MediaException.Malformed if the data ends before every plane is complete
     */
    static byte[] unpack(byte[] data, int position, int end, int planes, int count) {
        var target = new byte[planes * count];
        for (var plane = 0; plane < planes; plane++) {
            var out = plane * count;
            var limit = out + count;
            while (out < limit) {
                if (position >= end) {
                    throw new MediaException.Malformed("the ICNS run-length data ends in plane " + plane);
                }
                var control = data[position++] & 0xFF;
                if (control < 0x80) {
                    var length = control + 1;
                    if (position + length > end) {
                        throw new MediaException.Malformed("an ICNS literal run passes the end of its element");
                    }
                    var copied = Math.min(length, limit - out);
                    System.arraycopy(data, position, target, out, copied);
                    position += length;
                    out += copied;
                } else {
                    if (position >= end) {
                        throw new MediaException.Malformed("an ICNS repeat run passes the end of its element");
                    }
                    var value = data[position++];
                    var copied = Math.min(control - 125, limit - out);
                    java.util.Arrays.fill(target, out, out + copied, value);
                    out += copied;
                }
            }
        }
        return target;
    }

    /**
     * Packs one plane of samples with the run-length coding {@link #unpack(byte[], int, int, int, int)} reads:
     * runs of three to 130 equal bytes as repeats, everything else as literal runs of up to 128 bytes.
     *
     * @param pixels the packed RGBA samples
     * @param channel the channel to pack, 0 to 3
     * @param count the number of pixels
     * @param out the output
     */
    static void pack(byte[] pixels, int channel, int count, ByteArrayOutputStream out) {
        var literal = new byte[128];
        var literals = 0;
        var index = 0;
        while (index < count) {
            var value = pixels[index * 4 + channel];
            var run = 1;
            while (run < 130 && index + run < count && pixels[(index + run) * 4 + channel] == value) {
                run++;
            }
            if (run >= 3) {
                if (literals > 0) {
                    out.write(literals - 1);
                    out.write(literal, 0, literals);
                    literals = 0;
                }
                out.write(run + 125);
                out.write(value);
                index += run;
            } else {
                literal[literals++] = value;
                index++;
                if (literals == 128) {
                    out.write(127);
                    out.write(literal, 0, 128);
                    literals = 0;
                }
            }
        }
        if (literals > 0) {
            out.write(literals - 1);
            out.write(literal, 0, literals);
        }
    }
}
