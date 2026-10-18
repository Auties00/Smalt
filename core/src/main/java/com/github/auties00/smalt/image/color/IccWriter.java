package com.github.auties00.smalt.image.color;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes ICC version 4.3 display profiles of the matrix/TRC kind: RGB with three colorants and a
 * tone curve, or gray with a tone curve.
 *
 * <p>The profile holds a {@code desc} and a {@code cprt} ({@code multiLocalizedUnicodeType}), the
 * D50 media white ({@code wtpt}), the Bradford adaptation from the native white to D50
 * ({@code chad}, omitted when the white is D50), and for RGB the D50-adapted colorants and one
 * curve shared by the three channels. The header carries no creation date and the MD5 profile
 * identifier of ICC.1:2022 section 7.2.18, so the output depends only on the arguments.
 *
 * @implNote This implementation builds the colorants the way Little CMS's {@code cmsCreateRGBProfile} does
 * ({@code _cmsBuildRGB2XYZtransferMatrix} with Bradford adaptation), so a profile written here matches the one Little
 * CMS builds from the same primaries to the precision of the {@code s15Fixed16Number} encoding.
 */
final class IccWriter {
    /**
     * Describes the tone curve of a written profile.
     *
     * @param function the ICC {@code parametricCurveType} function, {@code 0..4}, or {@code -1} for a {@code curveType}
     * holding one 8.8 fixed-point gamma
     * @param params the parameters of the function, or the single gamma
     */
    record Curve(int function, double... params) {
        /**
         * The sRGB curve: IEC 61966-2-1, function 3.
         */
        static final Curve SRGB = new Curve(3, 2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045);

        /**
         * The inverse of the ITU-R BT.709 opto-electronic transfer function, function 3.
         */
        static final Curve BT709 = new Curve(3, 1 / 0.45, 1 / 1.099, 0.099 / 1.099, 1 / 4.5, 0.081);

        /**
         * Returns a pure power curve, function 0.
         *
         * @param gamma the exponent
         * @return the curve
         */
        static Curve gamma(double gamma) {
            return new Curve(0, gamma);
        }

        /**
         * Returns the curve as a {@link ToneCurve} with the encoded precision.
         *
         * @return the tone curve
         */
        ToneCurve toToneCurve() {
            if (function < 0) {
                return ToneCurve.gamma(Math.round(params[0] * 256.0) / 256.0);
            }
            var quantized = new double[params.length];
            for (var i = 0; i < params.length; i++) {
                quantized[i] = s15(params[i]) / 65536.0;
            }
            return ToneCurve.parametric(function + 1, quantized);
        }
    }

    /**
     * Prevents instantiation.
     */
    private IccWriter() {
        throw new AssertionError();
    }

    /**
     * Writes an RGB matrix/TRC profile.
     *
     * @param description the profile description
     * @param red the red chromaticity, {@code {x, y}}
     * @param green the green chromaticity, {@code {x, y}}
     * @param blue the blue chromaticity, {@code {x, y}}
     * @param white the white chromaticity, {@code {x, y}}
     * @param curve the tone curve of the three channels
     * @return the profile bytes
     * @throws IllegalArgumentException if the primaries are degenerate
     */
    static byte[] rgb(String description, double[] red, double[] green, double[] blue, double[] white, Curve curve) {
        var matrix = ColorMath.rgbToD50(red, green, blue, white);
        if (matrix == null) {
            throw new IllegalArgumentException("degenerate primaries");
        }
        var tags = new ArrayList<Tag>();
        tags.add(new Tag("desc", text(description)));
        tags.add(new Tag("cprt", text("No copyright, use freely")));
        tags.add(new Tag("wtpt", xyz(ColorMath.d50())));
        addAdaptation(tags, white);
        tags.add(new Tag("rXYZ", xyz(new double[]{matrix[0], matrix[3], matrix[6]})));
        tags.add(new Tag("gXYZ", xyz(new double[]{matrix[1], matrix[4], matrix[7]})));
        tags.add(new Tag("bXYZ", xyz(new double[]{matrix[2], matrix[5], matrix[8]})));
        var trc = curve(curve);
        tags.add(new Tag("rTRC", trc));
        tags.add(new Tag("gTRC", trc));
        tags.add(new Tag("bTRC", trc));
        return assemble("RGB ", tags);
    }

    /**
     * Writes a gray profile.
     *
     * @param description the profile description
     * @param white the white chromaticity, {@code {x, y}}
     * @param curve the tone curve
     * @return the profile bytes
     */
    static byte[] gray(String description, double[] white, Curve curve) {
        var tags = new ArrayList<Tag>();
        tags.add(new Tag("desc", text(description)));
        tags.add(new Tag("cprt", text("No copyright, use freely")));
        tags.add(new Tag("wtpt", xyz(ColorMath.d50())));
        addAdaptation(tags, white);
        tags.add(new Tag("kTRC", curve(curve)));
        return assemble("GRAY", tags);
    }

    /**
     * Adds the {@code chad} tag when the white is not D50.
     *
     * @param tags the tag list to append to
     * @param white the white chromaticity
     */
    private static void addAdaptation(List<Tag> tags, double[] white) {
        var adaptation = ColorMath.bradford(ColorMath.xyToXyz(white[0], white[1]), ColorMath.d50());
        if (adaptation != null && !ColorMath.isIdentity(adaptation)) {
            var data = new ByteArrayOutputStream();
            writeSignature(data, "sf32");
            writeInt(data, 0);
            for (var value : adaptation) {
                writeInt(data, s15(value));
            }
            tags.add(new Tag("chad", data.toByteArray()));
        }
    }

    /**
     * Holds one tag to write.
     *
     * @param signature the tag signature
     * @param data the tag data; equal arrays by identity share one copy
     */
    private record Tag(String signature, byte[] data) {
    }

    /**
     * Assembles the header, the tag table and the tag data, and fills in the profile identifier.
     *
     * @param colorSpace the data color space signature
     * @param tags the tags of the profile, in file order
     * @return the profile bytes
     */
    private static byte[] assemble(String colorSpace, List<Tag> tags) {
        var tableEnd = 128 + 4 + 12 * tags.size();
        var offsets = new int[tags.size()];
        var body = new ByteArrayOutputStream();
        var cursor = align(tableEnd);
        for (var i = 0; i < tags.size(); i++) {
            var shared = -1;
            for (var j = 0; j < i; j++) {
                if (tags.get(j).data() == tags.get(i).data()) {
                    shared = j;
                    break;
                }
            }
            if (shared >= 0) {
                offsets[i] = offsets[shared];
                continue;
            }
            offsets[i] = cursor;
            body.writeBytes(tags.get(i).data());
            var padded = align(tags.get(i).data().length);
            for (var p = tags.get(i).data().length; p < padded; p++) {
                body.write(0);
            }
            cursor += padded;
        }
        var size = cursor;
        var out = new ByteArrayOutputStream(size);
        writeInt(out, size);
        writeInt(out, 0);
        writeInt(out, 0x04300000);
        writeSignature(out, "mntr");
        writeSignature(out, colorSpace);
        writeSignature(out, "XYZ ");
        out.writeBytes(new byte[12]);
        writeSignature(out, "acsp");
        out.writeBytes(new byte[24]);
        writeInt(out, 0);
        writeInt(out, s15(ColorMath.D50_X));
        writeInt(out, s15(ColorMath.D50_Y));
        writeInt(out, s15(ColorMath.D50_Z));
        out.writeBytes(new byte[48]);
        writeInt(out, tags.size());
        for (var i = 0; i < tags.size(); i++) {
            writeSignature(out, tags.get(i).signature());
            writeInt(out, offsets[i]);
            writeInt(out, tags.get(i).data().length);
        }
        for (var p = tableEnd; p < align(tableEnd); p++) {
            out.write(0);
        }
        out.writeBytes(body.toByteArray());
        var bytes = out.toByteArray();
        try {
            var md5 = MessageDigest.getInstance("MD5").digest(bytes);
            System.arraycopy(md5, 0, bytes, 84, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("MD5 is a required JDK algorithm", exception);
        }
        return bytes;
    }

    /**
     * Rounds a length up to a multiple of four.
     *
     * @param length the length in bytes, not negative
     * @return the aligned length
     */
    private static int align(int length) {
        return (length + 3) & ~3;
    }

    /**
     * Encodes a {@code multiLocalizedUnicodeType} with one English record.
     *
     * @param text the English text of the record
     * @return the tag data
     */
    private static byte[] text(String text) {
        var utf16 = text.getBytes(StandardCharsets.UTF_16BE);
        var data = new ByteArrayOutputStream();
        writeSignature(data, "mluc");
        writeInt(data, 0);
        writeInt(data, 1);
        writeInt(data, 12);
        data.write('e');
        data.write('n');
        data.write('U');
        data.write('S');
        writeInt(data, utf16.length);
        writeInt(data, 28);
        data.writeBytes(utf16);
        return data.toByteArray();
    }

    /**
     * Encodes an {@code XYZType}.
     *
     * @param xyz the value
     * @return the tag data
     */
    private static byte[] xyz(double[] xyz) {
        var data = new ByteArrayOutputStream();
        writeSignature(data, "XYZ ");
        writeInt(data, 0);
        for (var value : xyz) {
            writeInt(data, s15(value));
        }
        return data.toByteArray();
    }

    /**
     * Encodes a curve as a {@code parametricCurveType} or a {@code curveType}.
     *
     * @param curve the tone curve to encode
     * @return the tag data
     */
    private static byte[] curve(Curve curve) {
        var data = new ByteArrayOutputStream();
        if (curve.function() < 0) {
            writeSignature(data, "curv");
            writeInt(data, 0);
            writeInt(data, 1);
            var gamma = (int) Math.round(curve.params()[0] * 256.0);
            data.write(gamma >>> 8);
            data.write(gamma);
            return data.toByteArray();
        }
        writeSignature(data, "para");
        writeInt(data, 0);
        data.write(0);
        data.write(curve.function());
        data.write(0);
        data.write(0);
        for (var value : curve.params()) {
            writeInt(data, s15(value));
        }
        return data.toByteArray();
    }

    /**
     * Converts a value to {@code s15Fixed16Number}.
     *
     * @param value the real value, within the s15.16 range
     * @return the encoded value
     */
    static int s15(double value) {
        return (int) Math.round(value * 65536.0);
    }

    /**
     * Writes a four-character signature.
     *
     * @param out the stream
     * @param signature the four ASCII characters
     */
    private static void writeSignature(ByteArrayOutputStream out, String signature) {
        out.writeBytes(signature.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Writes a big-endian 32-bit value.
     *
     * @param out the stream
     * @param value the 32-bit value
     */
    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
