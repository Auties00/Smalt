package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.color.TransferFunction;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes ICC display profiles of the matrix/TRC kind: version 4.3 RGB with three colorants and a
 * tone curve, or gray with a tone curve, and version 4.4 RGB profiles of high-dynamic-range signals.
 *
 * <p>The profile holds a {@code desc} and a {@code cprt} ({@code multiLocalizedUnicodeType}), the
 * D50 media white ({@code wtpt}), the Bradford adaptation from the native white to D50
 * ({@code chad}, omitted when the white is D50), and for RGB the D50-adapted colorants and one
 * curve shared by the three channels. A high-dynamic-range profile adds the {@code cicp} tag of
 * ICC.1:2022 (version 4.4, section 9.2.17) with its code points, the identity matrix and full
 * range, and samples its curves into a {@code curveType} table. The header carries no creation
 * date and the MD5 profile identifier of ICC.1:2022 section 7.2.18, so the output depends only on
 * the arguments.
 *
 * @implNote This implementation builds the colorants the way Little CMS's {@code cmsCreateRGBProfile} does
 * ({@code _cmsBuildRGB2XYZtransferMatrix} with Bradford adaptation), so a profile written here matches the one Little
 * CMS builds from the same primaries to the precision of the {@code s15Fixed16Number} encoding.
 */
final class IccWriter {
    /**
     * Describes the tone curve of a written profile.
     *
     * @param function the ICC {@code parametricCurveType} function, {@code 0..4}, {@code -1} for a {@code curveType}
     * holding one 8.8 fixed-point gamma, or {@code -2} for a {@code curveType} table
     * @param params the parameters of the function, the single gamma, or the table's samples in {@code 0..1}
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
         * Returns the decoding direction of a transfer function sampled into a {@code curveType} table of
         * {@link Hdr#ICC_CURVE_ENTRIES} entries, clipped to {@code 0..1}.
         *
         * @param transfer the transfer characteristics
         * @return the curve
         */
        static Curve sampled(TransferCharacteristics transfer) {
            var function = TransferFunction.of(transfer);
            var samples = new double[Hdr.ICC_CURVE_ENTRIES];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = Math.clamp(function.decode((double) i / (samples.length - 1)), 0, 1);
            }
            return new Curve(-2, samples);
        }

        /**
         * Returns the curve as a {@link ToneCurve} with the encoded precision.
         *
         * @return the tone curve
         */
        ToneCurve toToneCurve() {
            if (function == -2) {
                var table = new int[params.length];
                for (var i = 0; i < table.length; i++) {
                    table[i] = (int) Math.round(params[i] * 65535.0);
                }
                return ToneCurve.tabulated(table);
            }
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
        return rgb(description, red, green, blue, white, curve, curve, curve);
    }

    /**
     * Writes an RGB matrix/TRC profile with a tone curve per channel; a curve shared by the three channels is
     * stored once.
     *
     * @param description the profile description
     * @param red the red chromaticity, {@code {x, y}}
     * @param green the green chromaticity, {@code {x, y}}
     * @param blue the blue chromaticity, {@code {x, y}}
     * @param white the white chromaticity, {@code {x, y}}
     * @param redCurve the tone curve of the red channel
     * @param greenCurve the tone curve of the green channel
     * @param blueCurve the tone curve of the blue channel
     * @return the profile bytes
     * @throws IllegalArgumentException if the primaries are degenerate
     */
    static byte[] rgb(String description, double[] red, double[] green, double[] blue, double[] white, Curve redCurve,
                      Curve greenCurve, Curve blueCurve) {
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
        var redTrc = curve(redCurve);
        var greenTrc = curve(greenCurve);
        var blueTrc = curve(blueCurve);
        greenTrc = Arrays.equals(greenTrc, redTrc) ? redTrc : greenTrc;
        blueTrc = Arrays.equals(blueTrc, redTrc) ? redTrc : Arrays.equals(blueTrc, greenTrc) ? greenTrc : blueTrc;
        tags.add(new Tag("rTRC", redTrc));
        tags.add(new Tag("gTRC", greenTrc));
        tags.add(new Tag("bTRC", blueTrc));
        return assemble("RGB ", 0x04300000, tags);
    }

    /**
     * Writes the version 4.4 RGB profile of a high-dynamic-range signal.
     *
     * <p>The tone curves hold {@link Hdr#ICC_CURVE_ENTRIES} samples of {@link Hdr#iccCurve}, the
     * rendering of a reader that ignores the {@code cicp} tag.
     *
     * @param description the profile description
     * @param red the red chromaticity, {@code {x, y}}
     * @param green the green chromaticity, {@code {x, y}}
     * @param blue the blue chromaticity, {@code {x, y}}
     * @param white the white chromaticity, {@code {x, y}}
     * @param primaries the ITU-T H.273 code of the primaries
     * @param transfer {@link TransferCharacteristics#PQ} or {@link TransferCharacteristics#HLG}
     * @return the profile bytes
     * @throws IllegalArgumentException if the primaries are degenerate
     */
    static byte[] hdr(String description, double[] red, double[] green, double[] blue, double[] white, int primaries,
                      TransferCharacteristics transfer) {
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
        var trc = transfer == TransferCharacteristics.PQ ? HdrCurves.PQ : HdrCurves.HLG;
        tags.add(new Tag("rTRC", trc));
        tags.add(new Tag("gTRC", trc));
        tags.add(new Tag("bTRC", trc));
        var cicp = new ByteArrayOutputStream();
        writeSignature(cicp, "cicp");
        writeInt(cicp, 0);
        cicp.write(primaries);
        cicp.write(transfer.code());
        cicp.write(0);
        cicp.write(1);
        tags.add(new Tag("cicp", cicp.toByteArray()));
        return assemble("RGB ", 0x04400000, tags);
    }

    /**
     * Holds the tone curve tags of high-dynamic-range profiles, each built on first use and never modified.
     */
    private static final class HdrCurves {
        /**
         * The {@code curveType} of a PQ profile.
         */
        static final byte[] PQ = table(TransferCharacteristics.PQ);

        /**
         * The {@code curveType} of an HLG profile.
         */
        static final byte[] HLG = table(TransferCharacteristics.HLG);

        /**
         * Prevents instantiation.
         */
        private HdrCurves() {
            throw new AssertionError();
        }
    }

    /**
     * Encodes the sampled tone curve of a high-dynamic-range signal as a {@code curveType}.
     *
     * @param transfer the transfer characteristics, PQ or HLG
     * @return the tag data
     */
    private static byte[] table(TransferCharacteristics transfer) {
        var count = Hdr.ICC_CURVE_ENTRIES;
        var data = new ByteArrayOutputStream(12 + 2 * count);
        writeSignature(data, "curv");
        writeInt(data, 0);
        writeInt(data, count);
        for (var i = 0; i < count; i++) {
            var value = (int) Math.round(Hdr.iccCurve(transfer, (double) i / (count - 1)) * 65535.0);
            data.write(value >>> 8);
            data.write(value);
        }
        return data.toByteArray();
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
        return assemble("GRAY", 0x04300000, tags);
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
     * Writes the version 4.3 abstract identity profile of a connection space: an {@code A2B0} of three identity
     * curves.
     *
     * @param description the profile description
     * @param space {@code "Lab "} or {@code "XYZ "}
     * @return the profile bytes
     */
    static byte[] identity(String description, String space) {
        var identity = identityCurves(3);
        var tags = new ArrayList<Tag>();
        tags.add(new Tag("desc", text(description)));
        tags.add(new Tag("cprt", text("No copyright, use freely")));
        tags.add(new Tag("wtpt", xyz(ColorMath.d50())));
        tags.add(new Tag("A2B0", lutAtoB(3, 3, identity, null, null, null)));
        return assemble("abst", space, space, 0x04300000, 0, tags);
    }

    /**
     * Writes a version 4.3 device link of one curve per channel, from a color space to itself.
     *
     * @param description the profile description
     * @param space the color space signature
     * @param curves the curves, one per channel
     * @return the profile bytes
     */
    static byte[] linearization(String description, String space, Curve[] curves) {
        var tags = new ArrayList<Tag>();
        tags.add(new Tag("desc", text(description)));
        tags.add(new Tag("cprt", text("No copyright, use freely")));
        tags.add(new Tag("A2B0", lutAtoB(curves.length, curves.length, curves, null, null, null)));
        return assemble("link", space, space, 0x04300000, 0, tags);
    }

    /**
     * Writes a version 4.3 profile whose {@code A2B0} is a 16-bit lookup table between identity curves.
     *
     * @param description the profile description
     * @param deviceClass the profile class signature, {@code "link"} or {@code "abst"}
     * @param colorSpace the input color space signature
     * @param pcs the output color space signature
     * @param grid the grid points per input
     * @param outputs the number of outputs
     * @param table the 16-bit samples, the first input varying slowest
     * @param intent the rendering intent of the header, {@code 0..3}
     * @param white the media white point, or {@code null} for none
     * @return the profile bytes
     */
    static byte[] table(String description, String deviceClass, String colorSpace, String pcs, int[] grid,
                        int outputs, int[] table, int intent, double[] white) {
        var tags = new ArrayList<Tag>();
        tags.add(new Tag("desc", text(description)));
        tags.add(new Tag("cprt", text("No copyright, use freely")));
        if (white != null) {
            tags.add(new Tag("wtpt", xyz(white)));
        }
        tags.add(new Tag("A2B0", lutAtoB(grid.length, outputs, identityCurves(outputs), grid, table,
                identityCurves(grid.length))));
        return assemble(deviceClass, colorSpace, pcs, 0x04300000, intent, tags);
    }

    /**
     * Writes a version 4.3 named color profile ({@code ncl2}).
     *
     * @param description the profile description
     * @param colorSpace the device color space signature
     * @param pcs the connection space signature of the colors' values
     * @param prefix the prefix of every name, at most 31 ASCII characters
     * @param suffix the suffix of every name, at most 31 ASCII characters
     * @param roots the root name of each color, at most 31 ASCII characters each
     * @param values the 16-bit connection space values, three per color
     * @param devices the number of device coordinates per color
     * @param device the 16-bit device coordinates, {@code devices} per color
     * @param intent the rendering intent of the header, {@code 0..3}
     * @return the profile bytes
     */
    static byte[] namedColors(String description, String colorSpace, String pcs, String prefix, String suffix,
                              String[] roots, int[] values, int devices, int[] device, int intent) {
        var data = new ByteArrayOutputStream();
        writeSignature(data, "ncl2");
        writeInt(data, 0);
        writeInt(data, 0);
        writeInt(data, roots.length);
        writeInt(data, devices);
        writeName(data, prefix);
        writeName(data, suffix);
        for (var i = 0; i < roots.length; i++) {
            writeName(data, roots[i]);
            for (var c = 0; c < 3; c++) {
                writeShort(data, values[i * 3 + c]);
            }
            for (var c = 0; c < devices; c++) {
                writeShort(data, device[i * devices + c]);
            }
        }
        var tags = new ArrayList<Tag>();
        tags.add(new Tag("desc", text(description)));
        tags.add(new Tag("cprt", text("No copyright, use freely")));
        tags.add(new Tag("wtpt", xyz(ColorMath.d50())));
        tags.add(new Tag("ncl2", data.toByteArray()));
        return assemble("nmcl", colorSpace, pcs, 0x04300000, intent, tags);
    }

    /**
     * Writes a name field of a {@code namedColor2Type}: 32 bytes, NUL-padded.
     *
     * @param out the stream
     * @param name the name, at most 32 Latin-1 characters
     */
    private static void writeName(ByteArrayOutputStream out, String name) {
        var bytes = name.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(bytes);
        for (var i = bytes.length; i < 32; i++) {
            out.write(0);
        }
    }

    /**
     * Returns identity curves.
     *
     * @param channels the number of curves
     * @return the curves
     */
    private static Curve[] identityCurves(int channels) {
        var curves = new Curve[channels];
        Arrays.fill(curves, Curve.gamma(1.0));
        return curves;
    }

    /**
     * Encodes a {@code lutAtoBType}: curves, an optional 16-bit lookup table, and curves applied first.
     *
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     * @param b the curves applied last, one per output
     * @param grid the grid points per input of the table, or {@code null} for none
     * @param table the 16-bit table samples, or {@code null}
     * @param a the curves applied first, one per input, or {@code null} without a table
     * @return the tag data
     */
    private static byte[] lutAtoB(int inputs, int outputs, Curve[] b, int[] grid, int[] table, Curve[] a) {
        var data = new ByteArrayOutputStream();
        writeSignature(data, "mAB ");
        writeInt(data, 0);
        data.write(inputs);
        data.write(outputs);
        data.write(0);
        data.write(0);
        var bOffset = 32;
        var bCurves = curveSet(b);
        var clutOffset = 0;
        byte[] clut = null;
        var aOffset = 0;
        byte[] aCurves = null;
        if (grid != null) {
            clutOffset = bOffset + bCurves.length;
            clut = clut(grid, table);
            aOffset = clutOffset + clut.length;
            aCurves = curveSet(a);
        }
        writeInt(data, bOffset);
        writeInt(data, 0);
        writeInt(data, 0);
        writeInt(data, clutOffset);
        writeInt(data, aOffset);
        data.writeBytes(bCurves);
        if (clut != null) {
            data.writeBytes(clut);
            data.writeBytes(aCurves);
        }
        return data.toByteArray();
    }

    /**
     * Encodes curves one after the other, each padded to a multiple of four bytes.
     *
     * @param curves the curves to encode, in channel order
     * @return the bytes
     */
    private static byte[] curveSet(Curve[] curves) {
        var data = new ByteArrayOutputStream();
        for (var curve : curves) {
            var encoded = curve(curve);
            data.writeBytes(encoded);
            for (var p = encoded.length; p < align(encoded.length); p++) {
                data.write(0);
            }
        }
        return data.toByteArray();
    }

    /**
     * Encodes the lookup table element of a {@code lutAtoBType} with 16-bit samples, padded to a multiple of four
     * bytes.
     *
     * @param grid the grid points per input
     * @param table the samples
     * @return the bytes
     */
    private static byte[] clut(int[] grid, int[] table) {
        var data = new ByteArrayOutputStream();
        for (var i = 0; i < 16; i++) {
            data.write(i < grid.length ? grid[i] : 0);
        }
        data.write(2);
        data.write(0);
        data.write(0);
        data.write(0);
        for (var sample : table) {
            writeShort(data, sample);
        }
        if ((table.length & 1) != 0) {
            writeShort(data, 0);
        }
        return data.toByteArray();
    }

    /**
     * Returns a profile with one of its text tags replaced, or added when it is absent: a
     * {@code multiLocalizedUnicodeType} in a version 4 profile, a {@code textDescriptionType} ({@code desc}) or
     * {@code textType} ({@code cprt}) in a version 2 one.
     *
     * <p>Every other tag keeps its data and its sharing; the header keeps its fields except the size and the profile
     * identifier, which is computed again when the profile had one.
     *
     * @param profile the profile bytes, whose tag table has been validated
     * @param signature {@code "desc"} or {@code "cprt"}
     * @param value the text, in ASCII for a version 2 profile
     * @return the profile bytes
     */
    static byte[] retag(byte[] profile, String signature, String value) {
        var size = (int) Math.min(readInt(profile, 0) & 0xFFFFFFFFL, profile.length);
        var version4 = (profile[8] & 0xFF) >= 4;
        byte[] replacement;
        if (version4) {
            replacement = text(value);
        } else if (signature.equals("desc")) {
            replacement = textDescription(value);
        } else {
            var data = new ByteArrayOutputStream();
            writeSignature(data, "text");
            writeInt(data, 0);
            data.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
            data.write(0);
            replacement = data.toByteArray();
        }
        var count = readInt(profile, 128);
        var target = IccReader.signature(signature);
        var signatures = new ArrayList<Integer>();
        var datas = new ArrayList<byte[]>();
        var byOffset = new java.util.HashMap<Long, byte[]>();
        var replaced = false;
        for (var i = 0; i < count; i++) {
            var entry = 132 + i * 12;
            var tag = readInt(profile, entry);
            var offset = readInt(profile, entry + 4) & 0xFFFFFFFFL;
            var length = readInt(profile, entry + 8) & 0xFFFFFFFFL;
            if (offset + length > size) {
                continue;
            }
            if (tag == target) {
                if (!replaced) {
                    signatures.add(tag);
                    datas.add(replacement);
                    replaced = true;
                }
                continue;
            }
            var key = (offset << 32) | length;
            var shared = byOffset.get(key);
            if (shared == null) {
                shared = Arrays.copyOfRange(profile, (int) offset, (int) (offset + length));
                byOffset.put(key, shared);
            }
            signatures.add(tag);
            datas.add(shared);
        }
        if (!replaced) {
            signatures.add(target);
            datas.add(replacement);
        }
        var tableEnd = 128 + 4 + 12 * signatures.size();
        var offsets = new int[signatures.size()];
        var body = new ByteArrayOutputStream();
        var cursor = align(tableEnd);
        for (var i = 0; i < datas.size(); i++) {
            var shared = -1;
            for (var j = 0; j < i; j++) {
                if (datas.get(j) == datas.get(i)) {
                    shared = j;
                    break;
                }
            }
            if (shared >= 0) {
                offsets[i] = offsets[shared];
                continue;
            }
            offsets[i] = cursor;
            body.writeBytes(datas.get(i));
            for (var p = datas.get(i).length; p < align(datas.get(i).length); p++) {
                body.write(0);
            }
            cursor += align(datas.get(i).length);
        }
        var out = new ByteArrayOutputStream(cursor);
        writeInt(out, cursor);
        out.write(profile, 4, 124);
        writeInt(out, signatures.size());
        for (var i = 0; i < signatures.size(); i++) {
            writeInt(out, signatures.get(i));
            writeInt(out, offsets[i]);
            writeInt(out, datas.get(i).length);
        }
        for (var p = tableEnd; p < align(tableEnd); p++) {
            out.write(0);
        }
        out.writeBytes(body.toByteArray());
        var bytes = out.toByteArray();
        var identified = false;
        for (var i = 84; i < 100; i++) {
            identified |= profile[i] != 0;
        }
        if (identified) {
            identify(bytes);
        }
        return bytes;
    }

    /**
     * Encodes a version 2 {@code textDescriptionType} with an ASCII description and empty Unicode and Macintosh
     * descriptions.
     *
     * @param text the description, in ASCII
     * @return the tag data
     */
    private static byte[] textDescription(String text) {
        var ascii = text.getBytes(StandardCharsets.US_ASCII);
        var data = new ByteArrayOutputStream();
        writeSignature(data, "desc");
        writeInt(data, 0);
        writeInt(data, ascii.length + 1);
        data.writeBytes(ascii);
        data.write(0);
        writeInt(data, 0);
        writeInt(data, 0);
        data.write(0);
        data.write(0);
        data.write(0);
        data.writeBytes(new byte[67]);
        return data.toByteArray();
    }

    /**
     * Reads a big-endian 32-bit value.
     *
     * @param data the bytes
     * @param at the offset
     * @return the value
     */
    private static int readInt(byte[] data, int at) {
        return (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8 | data[at + 3] & 0xFF;
    }

    /**
     * Fills in the MD5 profile identifier of ICC.1:2022 section 7.2.18: the digest of the profile with the flags,
     * the rendering intent and the identifier itself zeroed.
     *
     * @param bytes the profile, modified in place
     */
    private static void identify(byte[] bytes) {
        var copy = bytes.clone();
        Arrays.fill(copy, 44, 48, (byte) 0);
        Arrays.fill(copy, 64, 68, (byte) 0);
        Arrays.fill(copy, 84, 100, (byte) 0);
        try {
            var md5 = MessageDigest.getInstance("MD5").digest(copy);
            System.arraycopy(md5, 0, bytes, 84, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("MD5 is a required JDK algorithm", exception);
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
     * Assembles a display profile with an XYZ connection space and the perceptual header intent.
     *
     * @param colorSpace the data color space signature
     * @param version the profile version field, for example {@code 0x04300000} for 4.3
     * @param tags the tags of the profile, in file order
     * @return the profile bytes
     */
    private static byte[] assemble(String colorSpace, int version, List<Tag> tags) {
        return assemble("mntr", colorSpace, "XYZ ", version, 0, tags);
    }

    /**
     * Assembles the header, the tag table and the tag data, and fills in the profile identifier.
     *
     * @param deviceClass the profile class signature
     * @param colorSpace the data color space signature
     * @param pcs the connection space signature, or the output color space of a device link
     * @param version the profile version field, for example {@code 0x04300000} for 4.3
     * @param intent the rendering intent of the header, {@code 0..3}
     * @param tags the tags of the profile, in file order
     * @return the profile bytes
     */
    private static byte[] assemble(String deviceClass, String colorSpace, String pcs, int version, int intent,
                                   List<Tag> tags) {
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
        writeInt(out, version);
        writeSignature(out, deviceClass);
        writeSignature(out, colorSpace);
        writeSignature(out, pcs);
        out.writeBytes(new byte[12]);
        writeSignature(out, "acsp");
        out.writeBytes(new byte[24]);
        writeInt(out, intent);
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
        identify(bytes);
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
        if (curve.function() == -2) {
            writeSignature(data, "curv");
            writeInt(data, 0);
            writeInt(data, curve.params().length);
            for (var sample : curve.params()) {
                var value = (int) Math.round(sample * 65535.0);
                data.write(value >>> 8);
                data.write(value);
            }
            return data.toByteArray();
        }
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

    /**
     * Writes a big-endian 16-bit value.
     *
     * @param out the stream
     * @param value the value, {@code 0..65535}
     */
    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }
}
