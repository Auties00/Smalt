/* Ports jxl-oxide 0.12.6 (crates/jxl-color: colour_encoding_to_icc) and libjxl (lib/jxl/cms). Copyright Wonwoo Choi
   and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.color;

import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an ICC version 4 profile describing an enumerated JPEG XL color encoding, so that images
 * without an embedded profile still carry one (ISO/IEC 18181-1 section L.3 describes the
 * encodings; the profile layout follows ICC.1:2010).
 *
 * <p>RGB encodings get a matrix/TRC profile with a chromatic adaptation tag, gray encodings a gray
 * TRC profile. Parametric curves describe the sRGB, BT.709 and power transfer functions; PQ and
 * HLG are sampled into 4096-entry curves and also signaled with a {@code cicp} tag.
 */
public final class IccSynthesizer {
    /**
     * The D50 profile connection space white.
     */
    private static final double[] D50 = {0.345669, 0.358496};

    /**
     * Prevents instantiation.
     */
    private IccSynthesizer() {
        throw new AssertionError();
    }

    /**
     * Holds one tag while the profile is assembled.
     *
     * @param signatures the tag signatures sharing the data
     * @param data the tag data
     */
    private record Tag(String[] signatures, byte[] data) {
    }

    /**
     * Builds the profile of an RGB or gray encoding.
     *
     * @param encoding the enumerated RGB or gray encoding
     * @return the profile bytes
     * @throws IllegalArgumentException if the encoding is XYB or unknown
     */
    public static byte[] synthesize(ColorEncoding encoding) {
        var space = encoding.colorSpace();
        if (space != ColorEncoding.RGB && space != ColorEncoding.GRAY) {
            throw new IllegalArgumentException("no ICC profile for color space " + space);
        }
        var header = new byte[128];
        putAscii(header, 4, "jxl ");
        header[8] = 4;
        header[9] = 0x40;
        putAscii(header, 12, "mntr");
        putAscii(header, 16, space == ColorEncoding.RGB ? "RGB " : "GRAY");
        putAscii(header, 20, "XYZ ");
        var date = new int[]{2023, 4, 22, 0, 0, 0};
        for (var i = 0; i < 6; i++) {
            header[24 + 2 * i] = (byte) (date[i] >>> 8);
            header[25 + 2 * i] = (byte) date[i];
        }
        putAscii(header, 36, "acsp");
        putAscii(header, 40, "APPL");
        header[67] = (byte) encoding.renderingIntent();
        ByteArrays.put32be(header, 68, 0xF6D6);
        ByteArrays.put32be(header, 72, 0x10000);
        ByteArrays.put32be(header, 76, 0xD32D);
        putAscii(header, 80, "jxl ");
        var tags = new ArrayList<Tag>();
        tags.add(new Tag(new String[]{"desc"}, mluc(describe(encoding))));
        tags.add(new Tag(new String[]{"cprt"}, mluc("CC0")));
        var white = encoding.whiteXy();
        double[] chad = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        if (space == ColorEncoding.RGB) {
            tags.add(new Tag(new String[]{"wtpt"}, xyz(0xF6D6, 0x10000, 0xD32D)));
            chad = ColorMath.adapt(white, D50);
            var data = new byte[8 + 36];
            putAscii(data, 0, "sf32");
            for (var i = 0; i < 9; i++) {
                ByteArrays.put32be(data, 8 + 4 * i, (int) (chad[i] * 65536.0 + 0.5));
            }
            tags.add(new Tag(new String[]{"chad"}, data));
        } else {
            var w = ColorMath.whiteXyz(white);
            tags.add(new Tag(new String[]{"wtpt"}, xyz((int) (w[0] * 65536.0 + 0.5), (int) (w[1] * 65536.0 + 0.5),
                    (int) (w[2] * 65536.0 + 0.5))));
        }
        var trc = transferCurve(encoding);
        if (encoding.transfer() == ColorEncoding.TF_PQ || encoding.transfer() == ColorEncoding.TF_HLG) {
            var cicpPrimaries = switch (encoding.primaries()) {
                case ColorEncoding.PRIMARIES_SRGB -> 1;
                case ColorEncoding.PRIMARIES_BT2100 -> 9;
                case ColorEncoding.PRIMARIES_P3 -> 11;
                default -> -1;
            };
            if (cicpPrimaries > 0) {
                var cicp = new byte[12];
                putAscii(cicp, 0, "cicp");
                cicp[8] = (byte) cicpPrimaries;
                cicp[9] = (byte) encoding.transfer();
                cicp[10] = 0;
                cicp[11] = 1;
                tags.add(new Tag(new String[]{"cicp"}, cicp));
            }
        }
        if (space == ColorEncoding.RGB) {
            tags.add(new Tag(new String[]{"rTRC", "gTRC", "bTRC"}, trc));
            var toXyz = ColorMath.multiply(chad, ColorMath.rgbToXyz(encoding.primariesXy(), white));
            for (var c = 0; c < 3; c++) {
                var sig = c == 0 ? "rXYZ" : c == 1 ? "gXYZ" : "bXYZ";
                tags.add(new Tag(new String[]{sig}, xyz((int) (toXyz[c] * 65536.0 + 0.5),
                        (int) (toXyz[3 + c] * 65536.0 + 0.5), (int) (toXyz[6 + c] * 65536.0 + 0.5))));
            }
        } else {
            tags.add(new Tag(new String[]{"kTRC"}, trc));
        }
        return assemble(header, tags);
    }

    /**
     * Lays out the header, the tag table and the tag data.
     *
     * @param header the 128-byte header
     * @param tags the tags, in table order
     * @return the profile
     */
    private static byte[] assemble(byte[] header, List<Tag> tags) {
        var entries = 0;
        for (var tag : tags) {
            entries += tag.signatures().length;
        }
        var data = new ByteArrayOutputStream();
        var table = new ByteArrayOutputStream();
        var dataStart = 128 + 4 + entries * 12;
        for (var tag : tags) {
            var offset = dataStart + data.size();
            for (var signature : tag.signatures()) {
                table.writeBytes(signature.getBytes(StandardCharsets.ISO_8859_1));
                table.writeBytes(intBytes(offset));
                table.writeBytes(intBytes(tag.data().length));
            }
            data.writeBytes(tag.data());
            while (data.size() % 4 != 0) {
                data.write(0);
            }
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(intBytes(entries));
        out.writeBytes(table.toByteArray());
        out.writeBytes(data.toByteArray());
        var profile = out.toByteArray();
        ByteArrays.put32be(profile, 0, profile.length);
        return profile;
    }

    /**
     * Builds the tone curve tag of an encoding.
     *
     * @param encoding the enumerated encoding whose transfer function the curve holds
     * @return the {@code para} or {@code curv} tag data
     */
    private static byte[] transferCurve(ColorEncoding encoding) {
        return switch (encoding.transfer()) {
            case ColorEncoding.TF_GAMMA -> {
                var g = encoding.gamma() & 0xFFFF_FFFFL;
                yield para(0, new int[]{(int) ((65536L * 10_000_000L + g / 2) / g)});
            }
            case ColorEncoding.TF_BT709 -> para(3, new int[]{(65536 * 20 + 4) / 9, (65536 * 1000 + 549) / 1099,
                    (65536 * 99 + 549) / 1099, (65536 * 10 + 22) / 45, (65536 * 81 + 500) / 1000});
            case ColorEncoding.TF_LINEAR -> curv(new int[0]);
            case ColorEncoding.TF_PQ -> curv(pqTable(4096));
            case ColorEncoding.TF_DCI -> para(0, new int[]{(65536 * 26 + 5) / 10});
            case ColorEncoding.TF_HLG -> curv(hlgTable(4096));
            default -> para(3, new int[]{(65536 * 24 + 5) / 10, (65536 * 1000 + 527) / 1055, (65536 * 55 + 527) / 1055,
                    (65536 * 100 + 646) / 1292, (65536 * 4045 + 50000) / 100000});
        };
    }

    /**
     * Samples the PQ decoding curve.
     *
     * @param n the number of samples
     * @return the linear values, scaled to 65535 for 10000 cd/m2
     */
    private static int[] pqTable(int n) {
        var out = new int[n];
        for (var i = 0; i < n; i++) {
            var e = i / (double) (n - 1);
            var p = StrictMath.pow(e, 4096.0 / (2523.0 * 128.0));
            var d = StrictMath.pow(Math.max(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p), 16384.0 / 2610.0);
            out[i] = (int) Math.min(65535, Math.max(0, d * 65535.0));
        }
        return out;
    }

    /**
     * Samples the HLG decoding curve.
     *
     * @param n the number of samples
     * @return the scene-linear values, scaled to 65535
     */
    private static int[] hlgTable(int n) {
        var out = new int[n];
        for (var i = 0; i < n; i++) {
            var e = i / (double) (n - 1);
            var d = e <= 0.5 ? e * e / 3.0 : (StrictMath.exp((e - 0.5599107) / 0.17883277) + 0.28466892) / 12.0;
            out[i] = (int) Math.min(65535, Math.max(0, d * 65535.0));
        }
        return out;
    }

    /**
     * Builds a {@code para} tag.
     *
     * @param type the function type
     * @param params the parameters in s15.16
     * @return the tag data
     */
    private static byte[] para(int type, int[] params) {
        var data = new byte[12 + params.length * 4];
        putAscii(data, 0, "para");
        data[8] = (byte) (type >>> 8);
        data[9] = (byte) type;
        for (var i = 0; i < params.length; i++) {
            ByteArrays.put32be(data, 12 + 4 * i, params[i]);
        }
        return data;
    }

    /**
     * Builds a {@code curv} tag.
     *
     * @param table the 16-bit entries, none for the identity
     * @return the tag data
     */
    private static byte[] curv(int[] table) {
        var data = new byte[12 + table.length * 2];
        putAscii(data, 0, "curv");
        ByteArrays.put32be(data, 8, table.length);
        for (var i = 0; i < table.length; i++) {
            data[12 + 2 * i] = (byte) (table[i] >>> 8);
            data[13 + 2 * i] = (byte) table[i];
        }
        return data;
    }

    /**
     * Builds an {@code XYZ } tag.
     *
     * @param x the X value in s15.16
     * @param y the Y value in s15.16
     * @param z the Z value in s15.16
     * @return the tag data
     */
    private static byte[] xyz(int x, int y, int z) {
        var data = new byte[20];
        putAscii(data, 0, "XYZ ");
        ByteArrays.put32be(data, 8, x);
        ByteArrays.put32be(data, 12, y);
        ByteArrays.put32be(data, 16, z);
        return data;
    }

    /**
     * Builds an {@code mluc} tag with one English string.
     *
     * @param text the string
     * @return the tag data
     */
    private static byte[] mluc(String text) {
        var utf16 = text.getBytes(StandardCharsets.UTF_16BE);
        var data = new byte[28 + utf16.length];
        putAscii(data, 0, "mluc");
        ByteArrays.put32be(data, 8, 1);
        ByteArrays.put32be(data, 12, 12);
        putAscii(data, 16, "enUS");
        ByteArrays.put32be(data, 20, utf16.length);
        ByteArrays.put32be(data, 24, 28);
        System.arraycopy(utf16, 0, data, 28, utf16.length);
        return data;
    }

    /**
     * Describes an encoding in the style of libjxl's profile descriptions.
     *
     * @param encoding the enumerated encoding to describe
     * @return the description
     */
    private static String describe(ColorEncoding encoding) {
        var space = encoding.colorSpace() == ColorEncoding.GRAY ? "Gra" : "RGB";
        var white = switch (encoding.whitePoint()) {
            case ColorEncoding.WHITE_E -> "EER";
            case ColorEncoding.WHITE_DCI -> "DCI";
            case ColorEncoding.WHITE_CUSTOM -> "Cst";
            default -> "D65";
        };
        var primaries = switch (encoding.primaries()) {
            case ColorEncoding.PRIMARIES_BT2100 -> "202";
            case ColorEncoding.PRIMARIES_P3 -> "DCI";
            case ColorEncoding.PRIMARIES_CUSTOM -> "Cst";
            default -> "SRG";
        };
        var intent = switch (encoding.renderingIntent()) {
            case 0 -> "Per";
            case 2 -> "Sat";
            case 3 -> "Abs";
            default -> "Rel";
        };
        var tf = switch (encoding.transfer()) {
            case ColorEncoding.TF_BT709 -> "709";
            case ColorEncoding.TF_LINEAR -> "Lin";
            case ColorEncoding.TF_PQ -> "PeQ";
            case ColorEncoding.TF_HLG -> "HLG";
            case ColorEncoding.TF_DCI -> "DCI";
            case ColorEncoding.TF_GAMMA -> "g" + (encoding.gamma() / 1e7);
            default -> "SRG";
        };
        return encoding.colorSpace() == ColorEncoding.GRAY ? space + "_" + white + "_" + intent + "_" + tf
                : space + "_" + white + "_" + primaries + "_" + intent + "_" + tf;
    }

    /**
     * Writes ASCII characters.
     *
     * @param data the array
     * @param offset the first index
     * @param text the characters
     */
    private static void putAscii(byte[] data, int offset, String text) {
        for (var i = 0; i < text.length(); i++) {
            data[offset + i] = (byte) text.charAt(i);
        }
    }

    /**
     * Returns the big-endian bytes of a 32-bit value.
     *
     * @param value the 32-bit value
     * @return the four bytes
     */
    private static byte[] intBytes(int value) {
        var data = new byte[4];
        ByteArrays.put32be(data, 0, value);
        return data;
    }
}
