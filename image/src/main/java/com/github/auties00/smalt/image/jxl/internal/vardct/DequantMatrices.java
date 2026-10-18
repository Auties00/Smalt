/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: DequantMatrixSet). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.modular.MaTree;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularImage;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Holds the dequantization matrices of a VarDCT frame (ISO/IEC 18181-1 section I.2.4): for each of
 * the 17 parameter sets and each channel, the multiplier of every coefficient.
 *
 * <p>Each set is either the default, or encoded as one of the parametric forms (Hornuss, DCT2,
 * DCT4, DCT4x8, AFV, DCT bands) or as raw modular-coded values. Matrices are stored row by row in
 * the coefficient layout of their transform type, and also transposed for the types whose
 * coefficients are transposed.
 */
public final class DequantMatrices {

    /**
     * The common tail of the X bands of the large DCTs.
     */
    private static final float[] SEQ_A = {-1.025f, -0.78f, -0.65012f, -0.19041574f, -0.20819396f, -0.421064f, -0.32733846f};

    /**
     * The common tail of the Y bands of the large DCTs.
     */
    private static final float[] SEQ_B = {-0.30419582f, -0.36330363f, -0.3566038f, -0.34430745f, -0.33699593f, -0.30180866f,
            -0.27321684f};

    /**
     * The common tail of the B bands of the large DCTs.
     */
    private static final float[] SEQ_C = {-1.2f, -1.2f, -0.8f, -0.7f, -0.7f, -0.4f, -0.5f};

    /**
     * The default DCT4x8 band parameters.
     */
    private static final float[][] DCT4X8_PARAMS = {
            {2198.0505f, -0.96269625f, -0.7619425f, -0.65511405f},
            {764.36554f, -0.926302f, -0.967523f, -0.2784529f},
            {527.10754f, -1.4594386f, -1.4500821f, -1.5843723f},
    };

    /**
     * The default DCT4 band parameters.
     */
    private static final float[][] DCT4_PARAMS = {
            {2200.0f, 0.0f, 0.0f, 0.0f},
            {392.0f, 0.0f, 0.0f, 0.0f},
            {112.0f, -0.25f, -0.25f, -0.5f},
    };

    /**
     * The frequencies of the AFV 4x4 corner coefficients.
     */
    private static final float[] AFV_FREQS = {
            0.0f, 0.0f, 0.8517779f, 5.3777843f, 0.0f, 0.0f, 4.734748f, 5.4492455f, 1.659827f, 4.0f,
            7.275749f, 10.423227f, 2.6629324f, 7.6306577f, 8.962389f, 12.971662f,
    };

    /**
     * The matrices of each set and channel, row by row.
     */
    private final float[][][] matrices;

    /**
     * The transposed matrices of each set and channel.
     */
    private final float[][][] transposed;

    /**
     * The raw quantization tables of a recompressed JPEG, or {@code null}.
     */
    private final int[][] jpegTables;

    /**
     * Creates a matrix set.
     *
     * @param matrices the matrices of every parameter set and channel
     * @param transposed the transposed matrices
     * @param jpegTables the JPEG quantization tables, or {@code null}
     */
    private DequantMatrices(float[][][] matrices, float[][][] transposed, int[][] jpegTables) {
        this.matrices = matrices;
        this.transposed = transposed;
        this.jpegTables = jpegTables;
    }

    /**
     * Holds the parameters of one set while it is read.
     */
    private static final class Params {
        /**
         * Creates an empty instance.
         */
        Params() {
        }

        /**
         * The encoding: 0 for DCT bands, 1 Hornuss, 2 DCT2, 3 DCT4, 4 DCT4x8, 5 AFV, 7 raw.
         */
        int mode;

        /**
         * The fixed parameters of each channel.
         */
        float[][] fixed;

        /**
         * The DCT band parameters of each channel.
         */
        float[][] dct;

        /**
         * The DCT4 band parameters of each channel, for AFV.
         */
        float[][] dct4;

        /**
         * The raw denominator.
         */
        float denominator;

        /**
         * The raw values of each channel.
         */
        int[][] raw;
    }

    /**
     * Reads the dequantization matrices.
     *
     * @param br the bitstream
     * @param bitDepth the bit depth of modular samples
     * @param numLfGroups the number of LF groups, which positions the raw sets' stream indices
     * @param globalTree the global MA tree, or {@code null}
     * @param limits the resource bounds
     * @return the matrices
     * @throws MediaException.Malformed if a set is invalid
     */
    public static DequantMatrices read(JxlBitReader br, int bitDepth, int numLfGroups, MaTree globalTree,
                                       MediaLimits limits) {
        var params = new Params[17];
        var allDefault = br.readBool();
        for (var i = 0; i < 17; i++) {
            if (allDefault) {
                params[i] = defaultParams(i);
                continue;
            }
            params[i] = readParams(br, i, bitDepth, 1 + numLfGroups * 3 + i, globalTree, limits);
        }
        int[][] jpeg = null;
        if (params[0].mode == 7 && Math.round(1.0f / params[0].denominator) == 2040) {
            jpeg = params[0].raw;
        }
        var matrices = new float[17][][];
        var transposed = new float[17][3][];
        for (var i = 0; i < 17; i++) {
            matrices[i] = build(params[i], i);
            var width = TransformType.matrixWidth(i);
            var height = TransformType.matrixHeight(i);
            for (var c = 0; c < 3; c++) {
                var matrix = matrices[i][c];
                var out = new float[matrix.length];
                for (var idx = 0; idx < out.length; idx++) {
                    var mx = idx % height;
                    var my = idx / height;
                    out[idx] = matrix[mx * width + my];
                }
                transposed[i][c] = out;
            }
        }
        return new DequantMatrices(matrices, transposed, jpeg);
    }

    /**
     * Reads the parameters of one set.
     *
     * @param br the bitstream
     * @param set the set index
     * @param bitDepth the modular bit depth
     * @param streamIndex the stream index of a raw set
     * @param globalTree the global MA tree
     * @param limits the resource bounds
     * @return the parameters
     * @throws MediaException.Malformed if the encoding does not suit the set
     */
    private static Params readParams(JxlBitReader br, int set, int bitDepth, int streamIndex, MaTree globalTree,
                                     MediaLimits limits) {
        var mode = br.readBits(3);
        if (mode >= 1 && mode <= 5 && !(set == 0 || set == 1 || set == 2 || set == 3 || set == 9 || set == 10)) {
            throw new MediaException.Malformed("invalid dequantization encoding for its transform");
        }
        var p = new Params();
        switch (mode) {
            case 0 -> {
                return defaultParams(set);
            }
            case 1 -> {
                p.mode = 1;
                p.fixed = readFixed(br, 3);
            }
            case 2 -> {
                p.mode = 2;
                p.fixed = readFixed(br, 6);
            }
            case 3 -> {
                p.mode = 3;
                p.fixed = readFixed(br, 2);
                p.dct = readDctParams(br);
            }
            case 4 -> {
                p.mode = 4;
                p.fixed = readFixed(br, 1);
                p.dct = readDctParams(br);
            }
            case 5 -> {
                p.mode = 5;
                p.fixed = readFixed(br, 9);
                for (var c = 0; c < 3; c++) {
                    for (var k = 0; k < 6; k++) {
                        p.fixed[c][k] *= 64.0f;
                    }
                }
                p.dct = readDctParams(br);
                p.dct4 = readDctParams(br);
            }
            case 6 -> {
                p.mode = 0;
                p.dct = readDctParams(br);
            }
            case 7 -> {
                p.mode = 7;
                var width = TransformType.matrixWidth(set);
                var height = TransformType.matrixHeight(set);
                p.denominator = br.readF16();
                var channels = new ArrayList<ModularChannel>(3);
                for (var c = 0; c < 3; c++) {
                    channels.add(new ModularChannel(width, height, 0, 0));
                }
                var image = ModularImage.read(br, channels, globalTree, bitDepth, limits, true);
                image.decode(br, streamIndex, image.channels().size());
                var decoded = image.finish();
                p.raw = new int[3][];
                for (var c = 0; c < 3; c++) {
                    p.raw[c] = decoded.get(c).data;
                }
            }
            default -> throw new MediaException.Malformed("invalid dequantization encoding " + mode);
        }
        return p;
    }

    /**
     * Reads fixed {@code F16} parameters for the three channels.
     *
     * @param br the bitstream
     * @param count the number of parameters per channel
     * @return the parameters
     */
    private static float[][] readFixed(JxlBitReader br, int count) {
        var out = new float[3][count];
        for (var c = 0; c < 3; c++) {
            for (var k = 0; k < count; k++) {
                out[c][k] = br.readF16();
            }
        }
        return out;
    }

    /**
     * Reads DCT band parameters for the three channels.
     *
     * @param br the bitstream
     * @return the parameters, the first of each channel scaled by 64
     */
    private static float[][] readDctParams(JxlBitReader br) {
        var count = br.readBits(4) + 1;
        var out = new float[3][count];
        for (var c = 0; c < 3; c++) {
            for (var k = 0; k < count; k++) {
                out[c][k] = br.readF16();
            }
        }
        for (var c = 0; c < 3; c++) {
            out[c][0] *= 64.0f;
        }
        return out;
    }

    /**
     * Returns the band parameters of a large DCT, with the common tails.
     *
     * @param x the first X band
     * @param y the first Y band
     * @param b the first B band
     * @return the parameters
     */
    private static float[][] commonSequence(float x, float y, float b) {
        return new float[][]{prepend(x, SEQ_A), prepend(y, SEQ_B), prepend(b, SEQ_C)};
    }

    /**
     * Prepends a value to an array.
     *
     * @param first the value
     * @param rest the array
     * @return the concatenation
     */
    private static float[] prepend(float first, float[] rest) {
        var out = new float[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    /**
     * Returns the default parameters of a set.
     *
     * @param set the set index
     * @return the parameters
     */
    private static Params defaultParams(int set) {
        var p = new Params();
        switch (set) {
            case 0 -> p.dct = new float[][]{
                    {3150.0f, 0.0f, -0.4f, -0.4f, -0.4f, -2.0f},
                    {560.0f, 0.0f, -0.3f, -0.3f, -0.3f, -0.3f},
                    {512.0f, -2.0f, -1.0f, 0.0f, -1.0f, -2.0f}};
            case 1 -> {
                p.mode = 1;
                p.fixed = new float[][]{{280.0f, 3160.0f, 3160.0f}, {60.0f, 864.0f, 864.0f}, {18.0f, 200.0f, 200.0f}};
            }
            case 2 -> {
                p.mode = 2;
                p.fixed = new float[][]{
                        {3840.0f, 2560.0f, 1280.0f, 640.0f, 480.0f, 300.0f},
                        {960.0f, 640.0f, 320.0f, 180.0f, 140.0f, 120.0f},
                        {640.0f, 320.0f, 128.0f, 64.0f, 32.0f, 16.0f}};
            }
            case 3 -> {
                p.mode = 3;
                p.fixed = new float[][]{{1.0f, 1.0f}, {1.0f, 1.0f}, {1.0f, 1.0f}};
                p.dct = copy(DCT4_PARAMS);
            }
            case 4 -> p.dct = new float[][]{
                    {8996.873f, -1.3000778f, -0.4942453f, -0.43909377f, -0.6350102f, -0.9017726f, -1.6162099f},
                    {3191.4836f, -0.67424583f, -0.80745816f, -0.4492584f, -0.3586544f, -0.3132239f, -0.37615025f},
                    {1157.504f, -2.0531423f, -1.4f, -0.5068713f, -0.4270873f, -1.4856834f, -4.920914f}};
            case 5 -> p.dct = new float[][]{
                    {15718.408f, -1.025f, -0.98f, -0.9012f, -0.4f, -0.48819396f, -0.421064f, -0.27f},
                    {7305.7637f, -0.8041958f, -0.76330364f, -0.5566038f, -0.49785304f, -0.43699592f, -0.40180868f,
                            -0.27321684f},
                    {3803.5317f, -3.0607336f, -2.041327f, -2.023565f, -0.54953897f, -0.4f, -0.4f, -0.3f}};
            case 6 -> p.dct = new float[][]{
                    {7240.7734f, -0.7f, -0.7f, -0.2f, -0.2f, -0.2f, -0.5f},
                    {1448.1547f, -0.5f, -0.5f, -0.5f, -0.2f, -0.2f, -0.2f},
                    {506.85413f, -1.4f, -0.2f, -0.5f, -0.5f, -1.5f, -3.6f}};
            case 7 -> p.dct = new float[][]{
                    {16283.249f, -1.7812846f, -1.6309059f, -1.0382179f, -0.85f, -0.7f, -0.9f, -1.2360638f},
                    {5089.1577f, -0.3200494f, -0.3536285f, -0.3034f, -0.61f, -0.5f, -0.5f, -0.6f},
                    {3397.7761f, -0.32132736f, -0.3450762f, -0.7034f, -0.9f, -1.0f, -1.0f, -1.1754606f}};
            case 8 -> p.dct = new float[][]{
                    {13844.971f, -0.971138f, -0.658f, -0.42026f, -0.22712f, -0.2206f, -0.226f, -0.6f},
                    {4798.964f, -0.6112531f, -0.8377079f, -0.7901486f, -0.26927274f, -0.38272768f, -0.22924222f,
                            -0.20719099f},
                    {1807.2369f, -1.2f, -1.2f, -0.7f, -0.7f, -0.7f, -0.4f, -0.5f}};
            case 9 -> {
                p.mode = 4;
                p.fixed = new float[][]{{1.0f}, {1.0f}, {1.0f}};
                p.dct = copy(DCT4X8_PARAMS);
            }
            case 10 -> {
                p.mode = 5;
                p.fixed = new float[][]{
                        {3072.0f, 3072.0f, 256.0f, 256.0f, 256.0f, 414.0f, 0.0f, 0.0f, 0.0f},
                        {1024.0f, 1024.0f, 50.0f, 50.0f, 50.0f, 58.0f, 0.0f, 0.0f, 0.0f},
                        {384.0f, 384.0f, 12.0f, 12.0f, 12.0f, 22.0f, -0.25f, -0.25f, -0.25f}};
                p.dct = copy(DCT4X8_PARAMS);
                p.dct4 = copy(DCT4_PARAMS);
            }
            case 11 -> p.dct = commonSequence(23966.166f, 8380.191f, 4493.024f);
            case 12 -> p.dct = commonSequence(15358.898f, 5597.3604f, 2919.9617f);
            case 13 -> p.dct = commonSequence(47932.332f, 16760.383f, 8986.048f);
            case 14 -> p.dct = commonSequence(30717.797f, 11194.721f, 5839.9233f);
            case 15 -> p.dct = commonSequence(95864.664f, 33520.766f, 17972.096f);
            default -> p.dct = commonSequence(61435.594f, 24209.441f, 12979.847f);
        }
        return p;
    }

    /**
     * Copies a parameter table.
     *
     * @param source the table
     * @return a deep copy
     */
    private static float[][] copy(float[][] source) {
        var out = new float[source.length][];
        for (var i = 0; i < source.length; i++) {
            out[i] = source[i].clone();
        }
        return out;
    }

    /**
     * Interpolates a band value geometrically.
     *
     * @param pos the position
     * @param max the largest position
     * @param bands the band values
     * @return the value
     */
    private static float interpolate(float pos, float max, float[] bands) {
        if (bands.length == 1) {
            return bands[0];
        }
        var scaled = pos * (bands.length - 1) / max;
        var index = (int) scaled;
        var frac = scaled - index;
        var a = bands[index];
        var b = bands[index + 1];
        return a * (float) Math.pow(b / a, frac);
    }

    /**
     * Maps a band parameter to a multiplier.
     *
     * @param x the parameter
     * @return {@code 1 + x} for positive values, else {@code 1 / (1 - x)}
     */
    private static float mult(float x) {
        return x > 0 ? 1.0f + x : 1.0f / (1.0f - x);
    }

    /**
     * Computes the weights of a matrix from DCT band parameters.
     *
     * @param params the band parameters
     * @param width the matrix width
     * @param height the matrix height
     * @return the weights, row by row
     * @throws MediaException.Malformed if a band is not positive
     */
    private static float[] dctWeights(float[] params, int width, int height) {
        var bands = new float[params.length];
        bands[0] = params[0];
        for (var i = 1; i < params.length; i++) {
            bands[i] = bands[i - 1] * mult(params[i]);
            if (!(bands[i] > 0)) {
                throw new MediaException.Malformed("dequantization band not positive");
            }
        }
        if (!(bands[0] > 0)) {
            throw new MediaException.Malformed("dequantization band not positive");
        }
        var out = new float[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var dx = x / (float) (width - 1);
                var dy = y / (float) (height - 1);
                var distance = (float) Math.sqrt(dx * dx + dy * dy);
                out[y * width + x] = interpolate(distance, (float) Math.sqrt(2.0) + 1e-6f, bands);
            }
        }
        return out;
    }

    /**
     * Builds the matrices of one set.
     *
     * @param p the parameters
     * @param set the set index
     * @return the matrices of the three channels
     * @throws MediaException.Malformed if a weight is not positive or too large
     */
    private static float[][] build(Params p, int set) {
        var width = TransformType.matrixWidth(set);
        var height = TransformType.matrixHeight(set);
        var out = new float[3][];
        for (var c = 0; c < 3; c++) {
            float[] w;
            switch (p.mode) {
                case 0 -> w = dctWeights(p.dct[c], width, height);
                case 1 -> {
                    w = new float[64];
                    Arrays.fill(w, p.fixed[c][0]);
                    w[0] = 1.0f;
                    w[1] = p.fixed[c][1];
                    w[8] = p.fixed[c][1];
                    w[9] = p.fixed[c][2];
                }
                case 2 -> {
                    w = new float[64];
                    w[0] = 1.0f;
                    for (var idx = 0; idx < 6; idx++) {
                        var value = p.fixed[c][idx];
                        var dim = 1 << (idx / 2);
                        if (idx % 2 == 0) {
                            for (var y = 0; y < dim; y++) {
                                for (var x = dim; x < dim * 2; x++) {
                                    w[y * 8 + x] = value;
                                    w[x * 8 + y] = value;
                                }
                            }
                        } else {
                            for (var y = dim; y < dim * 2; y++) {
                                for (var x = dim; x < dim * 2; x++) {
                                    w[y * 8 + x] = value;
                                }
                            }
                        }
                    }
                }
                case 3 -> {
                    var mat = dctWeights(p.dct[c], 4, 4);
                    w = new float[64];
                    for (var y = 0; y < 4; y++) {
                        for (var x = 0; x < 4; x++) {
                            var v = mat[y * 4 + x];
                            w[y * 16 + x * 2] = v;
                            w[y * 16 + x * 2 + 1] = v;
                            w[(y * 2 + 1) * 8 + x * 2] = v;
                            w[(y * 2 + 1) * 8 + x * 2 + 1] = v;
                        }
                    }
                    w[1] /= p.fixed[c][0];
                    w[8] /= p.fixed[c][0];
                    w[9] /= p.fixed[c][1];
                }
                case 4 -> {
                    var mat = dctWeights(p.dct[c], 8, 4);
                    w = new float[64];
                    for (var y = 0; y < 4; y++) {
                        System.arraycopy(mat, y * 8, w, y * 16, 8);
                        System.arraycopy(mat, y * 8, w, y * 16 + 8, 8);
                    }
                    w[8] /= p.fixed[c][0];
                }
                case 5 -> w = afvWeights(p.fixed[c], p.dct[c], p.dct4[c]);
                default -> {
                    w = new float[width * height];
                    for (var i = 0; i < w.length; i++) {
                        w[i] = p.raw[c][i] * p.denominator;
                    }
                }
            }
            if (p.mode != 7) {
                for (var i = 0; i < w.length; i++) {
                    w[i] = 1.0f / w[i];
                }
            }
            for (var v : w) {
                if (!(v < 1e8f && v > 0)) {
                    throw new MediaException.Malformed("dequantization weight not positive or too large");
                }
            }
            out[c] = w;
        }
        return out;
    }

    /**
     * Computes the weights of an AFV matrix.
     *
     * @param params the nine fixed parameters
     * @param dct the DCT4x8 band parameters
     * @param dct4 the DCT4 band parameters
     * @return the 64 weights
     */
    private static float[] afvWeights(float[] params, float[] dct, float[] dct4) {
        var w4x8 = dctWeights(dct, 8, 4);
        var w4x4 = dctWeights(dct4, 4, 4);
        var bands = new float[4];
        bands[0] = params[5];
        for (var i = 1; i < 4; i++) {
            bands[i] = bands[i - 1] * mult(params[5 + i]);
        }
        var freqLo = AFV_FREQS[2];
        var freqHi = AFV_FREQS[15];
        var w = new float[64];
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 4; x++) {
                float v;
                if (x == 0 && y == 0) {
                    v = 1.0f;
                } else if (x == 0 && y == 1) {
                    v = params[2];
                } else if (x == 1 && y == 0) {
                    v = params[3];
                } else if (x == 1 && y == 1) {
                    v = params[4];
                } else {
                    v = interpolate(AFV_FREQS[y * 4 + x] - freqLo, freqHi - freqLo + 1e-6f, bands);
                }
                w[16 * y + 2 * x] = v;
            }
        }
        for (var y = 0; y < 4; y++) {
            for (var x = 0; x < 8; x++) {
                w[16 * y + 8 + x] = y == 0 && x == 0 ? params[0] : w4x8[y * 8 + x];
            }
            for (var x = 0; x < 4; x++) {
                w[16 * y + 2 * x + 1] = y == 0 && x == 0 ? params[1] : w4x4[y * 4 + x];
            }
        }
        return w;
    }

    /**
     * Returns the matrix of a transform type and channel.
     *
     * @param type the transform type
     * @param channel the channel, 0 for X, 1 for Y, 2 for B
     * @return the multipliers, row by row in the coefficient layout
     */
    public float[] matrix(int type, int channel) {
        return TransformType.needsTranspose(type) ? transposed[TransformType.matrix(type)][channel]
                : matrices[TransformType.matrix(type)][channel];
    }

    /**
     * Returns the JPEG quantization tables of a recompressed JPEG.
     *
     * @return the 64 values of each channel, or {@code null} when the frame does not carry them
     */
    public int[][] jpegTables() {
        return jpegTables;
    }
}
