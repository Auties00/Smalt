package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Optional;

/**
 * Applies a gain map to a base image and encodes the result as PQ or HLG at 16 bits.
 */
final class GainMapRenderer {
    /**
     * The intervals of the base-2 exponential table.
     */
    private static final int EXP_STEPS = 4096;

    private GainMapRenderer() {
        throw new AssertionError();
    }

    /**
     * Renders a base image with its gain map.
     *
     * @param base the base image
     * @param gainMap the gain map
     * @param options the rendering options
     * @return the rendered image
     */
    static DecodedImage render(DecodedImage base, GainMap gainMap, GainMapOptions options) {
        var metadata = gainMap.metadata();
        var referenceWhite = options.referenceWhite();
        var source = LinearSource.of(base, referenceWhite);
        var alternate = gainMap.alternateProfile().map(Primaries::of).orElse(source.primaries);
        var math = metadata.useBaseColorSpace() ? source.primaries : alternate;
        var output = options.primaries().map(Primaries::of).orElseGet(() -> alternate.cicp() != null ? alternate
                : Primaries.of(ColorPrimaries.BT2020));
        var toMath = source.primaries.same(math) ? null : toFloat(source.primaries.conversion(math));
        var toOutput = math.same(output) ? null : toFloat(math.conversion(output));
        var headroom = options.headroom().orElse(metadata.alternateHeadroom());
        var weight = metadata.weight(headroom) * (metadata.isBaseBrighter() ? -1 : 1);
        var width = source.width;
        var height = source.height;
        var sampler = weight == 0 ? null : new GainSampler(gainMap.image(), metadata, width, height);
        var exponent = sampler == null ? null : new Exp2(weight * sampler.minimum, weight * sampler.maximum);
        var offsets = new float[6];
        for (var channel = 0; channel < 3; channel++) {
            offsets[channel] = (float) metadata.channel(channel).baseOffset();
            offsets[3 + channel] = (float) metadata.channel(channel).alternateOffset();
        }
        var hlg = options.transfer() == TransferCharacteristics.HLG;
        var scale = (float) (referenceWhite / (hlg ? Transfers.HLG_PEAK : Transfers.PQ_PEAK));
        var luminance = output.luminance();
        var picture = Rgba16Picture.allocate(width, height, source.hasAlpha);
        var bands = ParallelBands.bands(height, (long) width * height * 4);
        var peaks = new float[bands];
        // summed per row and then in row order, so that the average does not depend on the band count
        var sums = new double[height];
        var fixedWeight = (float) weight;
        ParallelBands.run(bands, band -> {
            var red = new float[width];
            var green = new float[width];
            var blue = new float[width];
            var alpha = new short[width];
            var gains = sampler == null ? null : new float[sampler.planes][width];
            var pixels = picture.pixels();
            var stride = picture.stride();
            var peak = 0f;
            for (var y = ParallelBands.start(band, bands, height); y < ParallelBands.start(band + 1, bands, height);
                 y++) {
                var sum = 0.0;
                source.row(y, red, green, blue);
                source.alpha(y, alpha);
                if (sampler != null) {
                    for (var plane = 0; plane < sampler.planes; plane++) {
                        sampler.row(y, plane, gains[plane]);
                    }
                }
                var at = y * stride;
                for (var x = 0; x < width; x++, at += 4) {
                    var r = red[x];
                    var g = green[x];
                    var b = blue[x];
                    if (toMath != null) {
                        var mr = toMath[0] * r + toMath[1] * g + toMath[2] * b;
                        var mg = toMath[3] * r + toMath[4] * g + toMath[5] * b;
                        var mb = toMath[6] * r + toMath[7] * g + toMath[8] * b;
                        r = mr;
                        g = mg;
                        b = mb;
                    }
                    if (sampler != null) {
                        var gr = gains[0][x];
                        var gg = sampler.planes == 1 ? gr : gains[1][x];
                        var gb = sampler.planes == 1 ? gr : gains[2][x];
                        r = (r + offsets[0]) * exponent.of(gr * fixedWeight) - offsets[3];
                        g = (g + offsets[1]) * exponent.of(gg * fixedWeight) - offsets[4];
                        b = (b + offsets[2]) * exponent.of(gb * fixedWeight) - offsets[5];
                    }
                    if (toOutput != null) {
                        var or = toOutput[0] * r + toOutput[1] * g + toOutput[2] * b;
                        var og = toOutput[3] * r + toOutput[4] * g + toOutput[5] * b;
                        var ob = toOutput[6] * r + toOutput[7] * g + toOutput[8] * b;
                        r = or;
                        g = og;
                        b = ob;
                    }
                    r = clamp(r * scale);
                    g = clamp(g * scale);
                    b = clamp(b * scale);
                    var brightest = Math.max(r, Math.max(g, b));
                    peak = Math.max(peak, brightest);
                    sum += brightest;
                    if (hlg) {
                        var displayLuminance = (float) (luminance[0] * r + luminance[1] * g + luminance[2] * b);
                        var factor = displayLuminance > 0
                                ? Transfers.hlgInverseGamma(Math.min(displayLuminance, 1)) / displayLuminance : 0;
                        pixels[at] = quantize(Transfers.hlg(clamp(r * factor)));
                        pixels[at + 1] = quantize(Transfers.hlg(clamp(g * factor)));
                        pixels[at + 2] = quantize(Transfers.hlg(clamp(b * factor)));
                    } else {
                        pixels[at] = quantize(Transfers.pq(r));
                        pixels[at + 1] = quantize(Transfers.pq(g));
                        pixels[at + 2] = quantize(Transfers.pq(b));
                    }
                    pixels[at + 3] = alpha[x];
                }
                sums[y] = sum;
            }
            peaks[band] = peak;
        });
        var peak = 0f;
        for (var band = 0; band < bands; band++) {
            peak = Math.max(peak, peaks[band]);
        }
        var sum = 0.0;
        for (var y = 0; y < height; y++) {
            sum += sums[y];
        }
        var nits = hlg ? Transfers.HLG_PEAK : Transfers.PQ_PEAK;
        var level = new ContentLightLevel((int) Math.min(65535, Math.round(peak * nits)),
                (int) Math.min(65535, Math.round(sum / ((double) width * height) * nits)));
        var color = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, output.cicp(), options.transfer())
                .withContentLightLevel(level);
        return new DecodedImage(picture, base.format(), base.orientation(), base.sourceWidth(), base.sourceHeight(),
                Optional.empty(), base.exif(), base.xmp(), Optional.of(ColorProfile.of(color)), base.density());
    }

    /**
     * {@return a value clamped to {@code 0..1}, NaN becoming 0}
     */
    private static float clamp(float value) {
        return value >= 1 ? 1 : value > 0 ? value : 0;
    }

    /**
     * {@return a signal from 0 to 1 rounded to a 16-bit sample}
     */
    private static short quantize(float signal) {
        return (short) (int) (signal * 65535 + 0.5f);
    }

    /**
     * {@return a row-major matrix as floats}
     */
    private static float[] toFloat(double[] matrix) {
        var result = new float[9];
        for (var i = 0; i < 9; i++) {
            result[i] = (float) matrix[i];
        }
        return result;
    }

    /**
     * Interpolates {@code 2^x} over a range of exponents.
     */
    private static final class Exp2 {
        /**
         * The smallest exponent.
         */
        private final float low;

        /**
         * The table steps per unit of exponent.
         */
        private final float scale;

        /**
         * The samples of {@code 2^x}.
         */
        private final float[] table;

        /**
         * Samples {@code 2^x} between two exponents, in either order.
         *
         * @param first one end of the range
         * @param second the other end
         */
        Exp2(double first, double second) {
            var low = Math.min(first, second);
            var high = Math.max(first, second);
            this.low = (float) low;
            this.table = new float[EXP_STEPS + 1];
            var span = high - low;
            this.scale = span > 0 ? (float) (EXP_STEPS / span) : 0;
            for (var i = 0; i <= EXP_STEPS; i++) {
                table[i] = (float) StrictMath.pow(2, low + span * i / EXP_STEPS);
            }
        }

        /**
         * {@return {@code 2^x}, for {@code x} within the range}
         */
        float of(float x) {
            var position = (x - low) * scale;
            if (!(position > 0)) {
                return table[0];
            }
            if (position >= EXP_STEPS) {
                return table[EXP_STEPS];
            }
            var index = (int) position;
            var fraction = position - index;
            return table[index] + (table[index + 1] - table[index]) * fraction;
        }
    }
}
