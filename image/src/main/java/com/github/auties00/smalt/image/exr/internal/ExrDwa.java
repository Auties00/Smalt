/* Ports OpenEXR 3.3.5 (internal_dwa_compressor.h, internal_dwa_decoder.h, internal_dwa_classifier.h,
   internal_dwa_simd.h scalar paths, dwaLookups.cpp). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decompresses DWAA and DWAB chunks: channels classified by name into lossy DCT channels (with red, green and blue
 * triples coded as Y'CbCr), run-length coded channels and zlib-coded leftovers.
 */
public final class ExrDwa {
    private static final int UNKNOWN = 0;

    private static final int LOSSY_DCT = 1;

    private static final int RLE = 2;

    private static final int HEADER = 11 * 8;

    /**
     * The zig-zag position of each coefficient of an 8 by 8 block in natural order.
     */
    private static final int[] ZIGZAG = {0, 1, 5, 6, 14, 15, 27, 28, 2, 4, 7, 13, 16, 26, 29, 42, 3, 8, 12, 17, 25,
            30, 41, 43, 9, 11, 18, 24, 31, 40, 44, 53, 10, 19, 23, 32, 39, 45, 52, 54, 20, 22, 33, 38, 46, 51, 55, 60,
            21, 34, 37, 47, 50, 56, 59, 61, 35, 36, 48, 49, 57, 58, 62, 63};

    /**
     * Describes a classification rule.
     *
     * @param suffix the channel name suffix it matches, the part after the last dot
     * @param scheme the compression scheme of the channels it matches
     * @param pixelType the pixel type it matches
     * @param csc the index in a red, green and blue triple, or -1
     * @param caseInsensitive whether the suffix matches ignoring case
     */
    private record Rule(String suffix, int scheme, int pixelType, int csc, boolean caseInsensitive) {
        boolean matches(String channelSuffix, int type) {
            if (type != pixelType) {
                return false;
            }
            return caseInsensitive ? channelSuffix.toLowerCase(Locale.ROOT).equals(suffix.toLowerCase(Locale.ROOT))
                    : channelSuffix.equals(suffix);
        }
    }

    private static final List<Rule> LEGACY_RULES = legacyRules();

    private ExrDwa() {
        throw new AssertionError();
    }

    private static List<Rule> legacyRules() {
        var rules = new ArrayList<Rule>();
        String[][] lossy = {{"r", "0"}, {"red", "0"}, {"g", "1"}, {"grn", "1"}, {"green", "1"}, {"b", "2"},
                {"blu", "2"}, {"blue", "2"}, {"y", "-1"}, {"by", "-1"}, {"ry", "-1"}};
        for (var entry : lossy) {
            rules.add(new Rule(entry[0], LOSSY_DCT, 1, Integer.parseInt(entry[1]), true));
            rules.add(new Rule(entry[0], LOSSY_DCT, 2, Integer.parseInt(entry[1]), true));
        }
        for (var type = 0; type < 3; type++) {
            rules.add(new Rule("a", RLE, type, -1, true));
        }
        return List.copyOf(rules);
    }

    /**
     * Decompresses a chunk.
     *
     * @param data the bytes
     * @param offset the offset of the compressed chunk
     * @param length the compressed bytes
     * @param block the chunk's pixels
     * @param size the uncompressed bytes
     * @return the uncompressed chunk
     * @throws MediaException.Malformed if the chunk is invalid
     */
    public static byte[] decompress(byte[] data, int offset, int length, ExrBlock block, int size) {
        if (length < HEADER) {
            throw new MediaException.Malformed("the OpenEXR DWA chunk is shorter than its header");
        }
        var counters = new long[11];
        for (var index = 0; index < 11; index++) {
            counters[index] = ExrHeader.i64(data, offset + 8 * index);
            if (counters[index] < 0) {
                throw new MediaException.Malformed("invalid OpenEXR DWA counter " + index);
            }
        }
        var version = counters[0];
        var unknownUncompressed = counters[1];
        var unknownCompressed = counters[2];
        var acCompressed = counters[3];
        var dcCompressed = counters[4];
        var rleCompressed = counters[5];
        var rleUncompressed = counters[6];
        var rleRaw = counters[7];
        var acCount = counters[8];
        var dcCount = counters[9];
        var acCompression = counters[10];
        if (version > 2) {
            throw new MediaException.Unsupported("exr", "DWA version " + version);
        }
        var end = offset + length;
        var position = offset + HEADER;
        List<Rule> rules;
        if (version < 2) {
            rules = LEGACY_RULES;
        } else {
            if (end - position <= 2) {
                throw new MediaException.Malformed("the OpenEXR DWA chunk has no channel rules");
            }
            var ruleSize = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8;
            if (ruleSize < 2 || position + ruleSize > end) {
                throw new MediaException.Malformed("invalid OpenEXR DWA channel rules");
            }
            rules = readRules(data, position + 2, position + ruleSize);
            position += ruleSize;
        }
        var total = unknownCompressed + acCompressed + dcCompressed + rleCompressed;
        if (total > end - position) {
            throw new MediaException.Malformed("the OpenEXR DWA sections run past their chunk");
        }
        var channels = block.channels();
        var count = channels.size();
        var schemes = new int[count];
        var sets = classify(channels, rules, schemes);
        var unknownStart = position;
        var acStart = unknownStart + (int) unknownCompressed;
        var dcStart = acStart + (int) acCompressed;
        var rleStart = dcStart + (int) dcCompressed;
        var pixels = (long) block.width() * block.height();
        long unknownCapacity = 0;
        long rleCapacity = 0;
        var lossyChannels = 0;
        for (var channel = 0; channel < count; channel++) {
            switch (schemes[channel]) {
                case LOSSY_DCT -> lossyChannels++;
                case RLE -> rleCapacity += 2 * pixels * channels.get(channel).bytes();
                default -> unknownCapacity += pixels * channels.get(channel).bytes();
            }
        }
        var blocksArea = (long) ((block.height() + 7) / 8) * ((block.width() + 7) / 8);
        if (acCount > blocksArea * 63 * lossyChannels || dcCount > blocksArea * lossyChannels
                || unknownUncompressed > unknownCapacity + 65536 || rleRaw > rleCapacity
                || rleUncompressed > rleCapacity) {
            throw new MediaException.Malformed("the OpenEXR DWA counters exceed the chunk");
        }
        var unknown = unknownCompressed > 0
                ? ExrCodecs.inflate(data, unknownStart, (int) unknownCompressed, (int) unknownUncompressed, true)
                : new byte[0];
        var ac = new short[(int) acCount];
        if (acCompressed > 0) {
            switch ((int) acCompression) {
                case 0 -> ExrHuffman.decode(data, acStart, (int) acCompressed, ac, ac.length);
                case 1 -> {
                    var bytes = ExrCodecs.inflate(data, acStart, (int) acCompressed, ac.length * 2, true);
                    for (var index = 0; index < ac.length; index++) {
                        ac[index] = (short) ((bytes[2 * index] & 0xFF) | (bytes[2 * index + 1] & 0xFF) << 8);
                    }
                }
                default -> throw new MediaException.Malformed("invalid OpenEXR DWA AC compression " + acCompression);
            }
        }
        var dc = new short[(int) dcCount];
        if (dcCompressed > 0) {
            var bytes = ExrCodecs.unpredict(ExrCodecs.inflate(data, dcStart, (int) dcCompressed, dc.length * 2, true));
            for (var index = 0; index < dc.length; index++) {
                dc[index] = (short) ((bytes[2 * index] & 0xFF) | (bytes[2 * index + 1] & 0xFF) << 8);
            }
        } else if (dcCount != 0) {
            throw new MediaException.Malformed("the OpenEXR DWA chunk counts DC values it does not hold");
        }
        var rle = new byte[0];
        if (rleRaw > 0) {
            var packed = ExrCodecs.inflate(data, rleStart, (int) rleCompressed, (int) rleUncompressed, true);
            rle = ExrCodecs.rle(packed, 0, packed.length, (int) rleRaw);
        }
        var rows = new byte[count][][];
        for (var channel = 0; channel < count; channel++) {
            var height = block.channelHeight(channel);
            rows[channel] = new byte[height][block.channelWidth(channel) * channels.get(channel).bytes()];
        }
        var state = new int[]{0, 0};
        var processed = new boolean[count];
        for (var set : sets) {
            for (var member : set) {
                if (schemes[member] != LOSSY_DCT) {
                    throw new MediaException.Malformed("an OpenEXR DWA color triple is not lossy");
                }
            }
            var width = block.channelWidth(set[0]);
            var height = block.channelHeight(set[0]);
            lossy(new byte[][][]{rows[set[0]], rows[set[1]], rows[set[2]]},
                    new int[]{channels.get(set[0]).pixelType(), channels.get(set[1]).pixelType(),
                            channels.get(set[2]).pixelType()}, ac, dc, state, true, width, height);
            processed[set[0]] = processed[set[1]] = processed[set[2]] = true;
        }
        var unknownPosition = 0;
        var rlePosition = 0;
        for (var channel = 0; channel < count; channel++) {
            var width = block.channelWidth(channel);
            var height = block.channelHeight(channel);
            var bytes = channels.get(channel).bytes();
            if (processed[channel] || width == 0 || height == 0) {
                continue;
            }
            switch (schemes[channel]) {
                case LOSSY_DCT -> lossy(new byte[][][]{rows[channel]}, new int[]{channels.get(channel).pixelType()},
                        ac, dc, state, !channels.get(channel).linear(), width, height);
                case RLE -> {
                    var plane = width * height;
                    if (rlePosition + (long) plane * bytes > rle.length) {
                        throw new MediaException.Malformed("the OpenEXR DWA RLE data ends early");
                    }
                    for (var row = 0; row < height; row++) {
                        var target = rows[channel][row];
                        for (var x = 0; x < width; x++) {
                            for (var b = 0; b < bytes; b++) {
                                target[x * bytes + b] = rle[rlePosition + b * plane + row * width + x];
                            }
                        }
                    }
                    rlePosition += plane * bytes;
                }
                default -> {
                    var line = width * bytes;
                    if (unknownPosition + (long) line * height > unknown.length) {
                        throw new MediaException.Malformed("the OpenEXR DWA leftover data ends early");
                    }
                    for (var row = 0; row < height; row++) {
                        System.arraycopy(unknown, unknownPosition, rows[channel][row], 0, line);
                        unknownPosition += line;
                    }
                }
            }
        }
        var out = new byte[size];
        var written = 0;
        var lines = new int[count];
        for (var row = 0; row < block.height(); row++) {
            var y = block.y() + row;
            for (var channel = 0; channel < count; channel++) {
                if (!block.samples(channel, y) || block.channelHeight(channel) == 0) {
                    continue;
                }
                var line = rows[channel][lines[channel]++];
                if (written + line.length > size) {
                    throw new MediaException.Malformed("the OpenEXR DWA chunk overflows its lines");
                }
                System.arraycopy(line, 0, out, written, line.length);
                written += line.length;
            }
        }
        return out;
    }

    /**
     * Reads the channel rules of a version 2 chunk.
     */
    private static List<Rule> readRules(byte[] data, int position, int end) {
        var rules = new ArrayList<Rule>();
        while (position < end) {
            if (end - position <= 3) {
                throw new MediaException.Malformed("an OpenEXR DWA channel rule ends early");
            }
            var stop = position;
            while (stop < end && data[stop] != 0) {
                stop++;
            }
            if (stop - position > 128 || stop + 3 > end) {
                throw new MediaException.Malformed("invalid OpenEXR DWA channel rule");
            }
            var suffix = new String(data, position, stop - position, StandardCharsets.ISO_8859_1);
            var value = data[stop + 1] & 0xFF;
            var type = data[stop + 2] & 0xFF;
            var csc = (value >> 4) - 1;
            var scheme = (value >> 2) & 3;
            if (csc < -1 || csc >= 3 || scheme >= 3 || type > 2) {
                throw new MediaException.Malformed("invalid OpenEXR DWA channel rule " + suffix);
            }
            rules.add(new Rule(suffix, scheme, type, csc, (value & 1) != 0));
            position = stop + 3;
        }
        return rules;
    }

    /**
     * Classifies the channels and returns the red, green and blue triples coded together.
     */
    private static List<int[]> classify(List<ExrHeader.Channel> channels, List<Rule> rules, int[] schemes) {
        var prefixes = new ArrayList<String>();
        var triples = new ArrayList<int[]>();
        for (var channel = 0; channel < channels.size(); channel++) {
            var name = channels.get(channel).name();
            var dot = name.lastIndexOf('.');
            var suffix = dot >= 0 ? name.substring(dot + 1) : name;
            var prefix = dot >= 0 ? name.substring(0, dot + 1) : "";
            var slot = prefixes.indexOf(prefix);
            if (slot < 0) {
                prefixes.add(prefix);
                triples.add(new int[]{-1, -1, -1});
                slot = prefixes.size() - 1;
            }
            schemes[channel] = UNKNOWN;
            for (var rule : rules) {
                if (rule.matches(suffix, channels.get(channel).pixelType())) {
                    schemes[channel] = rule.scheme();
                    if (rule.csc() >= 0) {
                        triples.get(slot)[rule.csc()] = channel;
                    }
                }
            }
        }
        var sets = new ArrayList<int[]>();
        for (var triple : triples) {
            if (triple[0] < 0 || triple[1] < 0 || triple[2] < 0) {
                continue;
            }
            var red = channels.get(triple[0]);
            var green = channels.get(triple[1]);
            var blue = channels.get(triple[2]);
            if (red.xSampling() != green.xSampling() || red.xSampling() != blue.xSampling()
                    || red.ySampling() != green.ySampling() || red.ySampling() != blue.ySampling()) {
                continue;
            }
            sets.add(triple.clone());
        }
        return sets;
    }

    /**
     * Decodes lossy DCT channels, one or a Y'CbCr triple, into their rows.
     *
     * @param rows the rows of each channel
     * @param types the pixel type of each channel
     * @param ac the AC values
     * @param dc the DC values
     * @param state the next AC and DC indices
     * @param toLinear whether the decoded values pass through the table to linear values
     * @param width the width of the channels
     * @param height the height of the channels
     */
    private static void lossy(byte[][][] rows, int[] types, short[] ac, short[] dc, int[] state, boolean toLinear,
                              int width, int height) {
        var components = rows.length;
        var blocksX = (width + 7) / 8;
        var blocksY = (height + 7) / 8;
        if (dc.length - state[1] < (long) components * blocksX * blocksY) {
            throw new MediaException.Malformed("the OpenEXR DWA chunk holds too few DC values");
        }
        var dcStart = new int[components];
        for (var component = 0; component < components; component++) {
            dcStart[component] = state[1] + component * blocksX * blocksY;
        }
        var zig = new short[64];
        var spatial = new float[components][64];
        var halves = new short[components][blocksX * 64];
        var lut = toLinear ? Tables.TO_LINEAR : null;
        for (var by = 0; by < blocksY; by++) {
            for (var bx = 0; bx < blocksX; bx++) {
                for (var component = 0; component < components; component++) {
                    java.util.Arrays.fill(zig, (short) 0);
                    zig[0] = dc[dcStart[component]++];
                    var last = unRleAc(ac, state, zig);
                    var block = spatial[component];
                    if (last == 0) {
                        var value = Float.float16ToFloat(zig[0]) * 3.535536e-01f * 3.535536e-01f;
                        java.util.Arrays.fill(block, value);
                    } else {
                        for (var index = 0; index < 64; index++) {
                            block[index] = Float.float16ToFloat(zig[ZIGZAG[index]]);
                        }
                        inverse(block);
                    }
                }
                if (components == 3) {
                    for (var index = 0; index < 64; index++) {
                        var y = spatial[0][index];
                        var cb = spatial[1][index];
                        var cr = spatial[2][index];
                        spatial[0][index] = y + 1.5747f * cr;
                        spatial[1][index] = y - 0.1873f * cb - 0.4682f * cr;
                        spatial[2][index] = y + 1.8556f * cb;
                    }
                }
                for (var component = 0; component < components; component++) {
                    var target = halves[component];
                    for (var index = 0; index < 64; index++) {
                        target[bx * 64 + index] = Float.floatToFloat16(spatial[component][index]);
                    }
                }
            }
            var maxY = by == blocksY - 1 ? height - by * 8 : 8;
            for (var component = 0; component < components; component++) {
                var wide = types[component] == 2;
                for (var y = by * 8; y < by * 8 + maxY; y++) {
                    var target = rows[component][y];
                    for (var x = 0; x < width; x++) {
                        var value = halves[component][(x / 8) * 64 + (y & 7) * 8 + (x & 7)];
                        if (lut != null) {
                            value = lut[value & 0xFFFF];
                        }
                        if (wide) {
                            var bits = Float.floatToRawIntBits(Float.float16ToFloat(value));
                            target[4 * x] = (byte) bits;
                            target[4 * x + 1] = (byte) (bits >>> 8);
                            target[4 * x + 2] = (byte) (bits >>> 16);
                            target[4 * x + 3] = (byte) (bits >>> 24);
                        } else {
                            target[2 * x] = (byte) value;
                            target[2 * x + 1] = (byte) (value >> 8);
                        }
                    }
                }
            }
        }
        state[1] += components * blocksX * blocksY;
    }

    /**
     * Expands the run-length coded AC values of one block: a value whose high byte is 0xFF skips its low byte of
     * zero coefficients, or ends the block when the low byte is zero.
     *
     * @return the zig-zag index of the last coefficient set, 0 when only the DC is
     */
    private static int unRleAc(short[] ac, int[] state, short[] zig) {
        var component = 1;
        var last = 0;
        var position = state[0];
        while (component < 64) {
            if (position >= ac.length) {
                throw new MediaException.Malformed("the OpenEXR DWA AC values end early");
            }
            var value = ac[position++];
            if ((value & 0xFF00) == 0xFF00) {
                var run = value & 0xFF;
                component += run == 0 ? 64 : run;
            } else {
                last = component;
                zig[component] = value;
                component++;
            }
        }
        state[0] = position;
        return last;
    }

    /**
     * {@return the table from DWA's perceptual values to linear halves}
     */
    static short[] toLinearTable() {
        return Tables.TO_LINEAR;
    }

    /**
     * Inverts the 8 by 8 DCT in place, rows then columns.
     */
    private static void inverse(float[] data) {
        var a = Tables.A;
        var b = Tables.B;
        var c = Tables.C;
        var d = Tables.D;
        var e = Tables.E;
        var f = Tables.F;
        var g = Tables.G;
        for (var row = 0; row < 8; row++) {
            var at = row * 8;
            var alpha0 = c * data[at + 2];
            var alpha1 = f * data[at + 2];
            var alpha2 = c * data[at + 6];
            var alpha3 = f * data[at + 6];
            var beta0 = b * data[at + 1] + d * data[at + 3] + e * data[at + 5] + g * data[at + 7];
            var beta1 = d * data[at + 1] - g * data[at + 3] - b * data[at + 5] - e * data[at + 7];
            var beta2 = e * data[at + 1] - b * data[at + 3] + g * data[at + 5] + d * data[at + 7];
            var beta3 = g * data[at + 1] - e * data[at + 3] + d * data[at + 5] - b * data[at + 7];
            var theta0 = a * (data[at] + data[at + 4]);
            var theta3 = a * (data[at] - data[at + 4]);
            var theta1 = alpha0 + alpha3;
            var theta2 = alpha1 - alpha2;
            var gamma0 = theta0 + theta1;
            var gamma1 = theta3 + theta2;
            var gamma2 = theta3 - theta2;
            var gamma3 = theta0 - theta1;
            data[at] = gamma0 + beta0;
            data[at + 1] = gamma1 + beta1;
            data[at + 2] = gamma2 + beta2;
            data[at + 3] = gamma3 + beta3;
            data[at + 4] = gamma3 - beta3;
            data[at + 5] = gamma2 - beta2;
            data[at + 6] = gamma1 - beta1;
            data[at + 7] = gamma0 - beta0;
        }
        for (var column = 0; column < 8; column++) {
            var alpha0 = c * data[16 + column];
            var alpha1 = f * data[16 + column];
            var alpha2 = c * data[48 + column];
            var alpha3 = f * data[48 + column];
            var beta0 = b * data[8 + column] + d * data[24 + column] + e * data[40 + column] + g * data[56 + column];
            var beta1 = d * data[8 + column] - g * data[24 + column] - b * data[40 + column] - e * data[56 + column];
            var beta2 = e * data[8 + column] - b * data[24 + column] + g * data[40 + column] + d * data[56 + column];
            var beta3 = g * data[8 + column] - e * data[24 + column] + d * data[40 + column] - b * data[56 + column];
            var theta0 = a * (data[column] + data[32 + column]);
            var theta3 = a * (data[column] - data[32 + column]);
            var theta1 = alpha0 + alpha3;
            var theta2 = alpha1 - alpha2;
            var gamma0 = theta0 + theta1;
            var gamma1 = theta3 + theta2;
            var gamma2 = theta3 - theta2;
            var gamma3 = theta0 - theta1;
            data[column] = gamma0 + beta0;
            data[8 + column] = gamma1 + beta1;
            data[16 + column] = gamma2 + beta2;
            data[24 + column] = gamma3 + beta3;
            data[32 + column] = gamma3 - beta3;
            data[40 + column] = gamma2 - beta2;
            data[48 + column] = gamma1 - beta1;
            data[56 + column] = gamma0 - beta0;
        }
    }

    /**
     * Holds the DCT constants and the table from the perceptual values DWA codes to linear halves.
     */
    private static final class Tables {
        static final float A = .5f * cos(3.14159f / 4.0f);

        static final float B = .5f * cos(3.14159f / 16.0f);

        static final float C = .5f * cos(3.14159f / 8.0f);

        static final float D = .5f * cos(3.f * 3.14159f / 16.0f);

        static final float E = .5f * cos(5.f * 3.14159f / 16.0f);

        static final float F = .5f * cos(3.f * 3.14159f / 8.0f);

        static final float G = .5f * cos(7.f * 3.14159f / 16.0f);

        static final short[] TO_LINEAR = toLinear();

        private static float cos(float angle) {
            return (float) StrictMath.cos(angle);
        }

        /**
         * Builds the table: values up to 1 in magnitude raised to 2.2, larger ones mapped to e^(2.2 (|v| - 1)),
         * with their sign; infinities and NaNs to zero.
         */
        private static short[] toLinear() {
            var table = new short[1 << 16];
            var logBase = (float) StrictMath.pow(2.7182818, 2.2);
            for (var bits = 1; bits < table.length; bits++) {
                if ((bits & 0x7C00) == 0x7C00) {
                    continue;
                }
                var value = Float.float16ToFloat((short) bits);
                var sign = value < 0 ? -1f : 1f;
                var magnitude = Math.abs(value);
                float result;
                if (magnitude <= 1.0f) {
                    result = sign * (float) StrictMath.pow(magnitude, 2.2f);
                } else {
                    result = sign * (float) StrictMath.pow(logBase, (float) (magnitude - 1.0));
                }
                table[bits] = Float.floatToFloat16(result);
            }
            return table;
        }
    }
}
