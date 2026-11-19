package com.github.auties00.smalt.audio.adpcm;

import com.github.auties00.smalt.MediaException;

/**
 * Microsoft ADPCM ({@code WAVE_FORMAT_ADPCM}) blocks.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class MsAdpcm {
    /**
     * The seven standard predictor coefficient pairs, first and second coefficient of each, in 1/256.
     */
    static final int[] STANDARD_COEFFICIENTS = {256, 0, 512, -256, 0, 0, 192, 64, 240, 0, 460, -208, 392, -232};

    /**
     * The factor, in 1/256, by which each code scales the quantization delta.
     */
    private static final int[] ADAPTATION = {230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230,
            230};

    /**
     * The smallest quantization delta.
     */
    private static final int MIN_DELTA = 16;

    /**
     * The coefficient pairs, first and second coefficient of each, in 1/256.
     */
    private final int[] coefficients;

    /**
     * Constructs the decoder or encoder of a stream.
     *
     * @param coefficients the coefficient pairs, first and second coefficient of each, in 1/256; at least one pair
     */
    MsAdpcm(int[] coefficients) {
        this.coefficients = coefficients;
    }

    /**
     * Returns the coefficient pairs of a configuration.
     *
     * @param config the {@code ADPCMWAVEFORMAT} fields after {@code cbSize}, or {@code null} for the standard pairs
     * @return the coefficient pairs, first and second coefficient of each
     * @throws MediaException.Malformed if the configuration is shorter than the table it declares or declares no
     * pairs
     */
    static int[] coefficients(byte[] config) {
        if (config == null || config.length == 0) {
            return STANDARD_COEFFICIENTS;
        }
        if (config.length < 4) {
            throw new MediaException.Malformed("Microsoft ADPCM configuration of " + config.length + " bytes");
        }
        var count = (config[2] & 0xFF) | (config[3] & 0xFF) << 8;
        if (count == 0 || config.length < 4 + 4L * count) {
            throw new MediaException.Malformed("Microsoft ADPCM configuration of " + config.length + " bytes for "
                    + count + " coefficient pairs");
        }
        var pairs = new int[2 * count];
        for (var i = 0; i < pairs.length; i++) {
            pairs[i] = (short) ((config[4 + 2 * i] & 0xFF) | (config[5 + 2 * i] & 0xFF) << 8);
        }
        return pairs;
    }

    /**
     * Returns the samples per channel of a block.
     *
     * @param bytes the size of the block
     * @param channels the number of channels
     * @return the samples per channel, or 0 when the block is shorter than its headers
     */
    static int samples(int bytes, int channels) {
        if (bytes < 7 * channels) {
            return 0;
        }
        return 2 + 2 * (bytes - 7 * channels) / channels;
    }

    /**
     * Returns the prediction of a sample from the two before it.
     *
     * @param first the first coefficient, in 1/256
     * @param second the second coefficient, in 1/256
     * @param previous the sample before
     * @param beforePrevious the sample before {@code previous}
     * @return the prediction, rounded toward negative infinity
     */
    private static int predict(int first, int second, int previous, int beforePrevious) {
        return (previous * first + beforePrevious * second) >> 8;
    }

    /**
     * Returns the quantization delta after a code.
     *
     * @param delta the delta the code was coded with
     * @param code the 4-bit code
     * @return the next delta, at least 16
     */
    private static int adapt(int delta, int code) {
        // the product wraps in 32 bits, as Microsoft's codec computes it, so a stream whose delta keeps growing
        // decodes as that codec decodes it
        return Math.max(MIN_DELTA, ADAPTATION[code] * delta >> 8);
    }

    /**
     * Decodes one block.
     *
     * @param data the packet
     * @param planes receives the samples, one plane per channel
     * @return the samples per channel decoded
     * @throws MediaException.Malformed if the block is shorter than its headers or names a predictor the
     * configuration lacks
     */
    int decode(byte[] data, int[][] planes) {
        var channels = planes.length;
        var samples = samples(data.length, channels);
        if (samples == 0) {
            throw new MediaException.Malformed("Microsoft ADPCM block of " + data.length + " bytes for " + channels
                    + " channels");
        }
        var pairs = coefficients.length / 2;
        var position = 7 * channels;
        var codes = (samples - 2) * channels;
        for (var c = 0; c < channels; c++) {
            var predictor = data[c] & 0xFF;
            if (predictor >= pairs) {
                throw new MediaException.Malformed("Microsoft ADPCM predictor " + predictor + " of " + pairs);
            }
            int delta = (short) ((data[channels + 2 * c] & 0xFF) | (data[channels + 2 * c + 1] & 0xFF) << 8);
            int previous = (short) ((data[3 * channels + 2 * c] & 0xFF) | (data[3 * channels + 2 * c + 1] & 0xFF) << 8);
            int beforePrevious = (short) ((data[5 * channels + 2 * c] & 0xFF)
                    | (data[5 * channels + 2 * c + 1] & 0xFF) << 8);
            var plane = planes[c];
            plane[0] = beforePrevious;
            plane[1] = previous;
            var first = coefficients[2 * predictor];
            var second = coefficients[2 * predictor + 1];
            for (var n = c; n < codes; n += channels) {
                var value = data[position + (n >> 1)] & 0xFF;
                var code = (n & 1) == 0 ? value >>> 4 : value & 0x0F;
                var signed = code << 28 >> 28;
                var sample = Math.max(-32768, Math.min(32767, predict(first, second, previous, beforePrevious)
                        + signed * delta));
                plane[2 + n / channels] = sample;
                beforePrevious = previous;
                previous = sample;
                delta = adapt(delta, code);
            }
        }
        return samples;
    }

    /**
     * Codes one block, choosing for each channel the predictor and initial delta that code it with the smallest
     * squared error.
     *
     * @param planes the samples, one plane per channel
     * @param count the samples per channel, at least 2, and with {@code (count - 2) * channels} even
     * @param target receives the block, {@code 7 * channels + (count - 2) * channels / 2} bytes
     */
    void encode(int[][] planes, int count, byte[] target) {
        var channels = planes.length;
        var position = 7 * channels;
        var pairs = coefficients.length / 2;
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var bestError = Long.MAX_VALUE;
            var bestPredictor = 0;
            var bestDelta = MIN_DELTA;
            for (var predictor = 0; predictor < pairs; predictor++) {
                var delta = initialDelta(plane, count, predictor);
                var error = trial(plane, count, predictor, delta);
                if (error < bestError) {
                    bestError = error;
                    bestPredictor = predictor;
                    bestDelta = delta;
                }
            }
            target[c] = (byte) bestPredictor;
            target[channels + 2 * c] = (byte) bestDelta;
            target[channels + 2 * c + 1] = (byte) (bestDelta >> 8);
            var previous = plane[Math.min(1, count - 1)];
            target[3 * channels + 2 * c] = (byte) previous;
            target[3 * channels + 2 * c + 1] = (byte) (previous >> 8);
            target[5 * channels + 2 * c] = (byte) plane[0];
            target[5 * channels + 2 * c + 1] = (byte) (plane[0] >> 8);
            codeChannel(plane, count, bestPredictor, bestDelta, target, position, c, channels);
        }
    }

    /**
     * Estimates the initial quantization delta of a channel from the prediction errors of its first samples.
     *
     * @param plane the channel's samples
     * @param count the samples in the block
     * @param predictor the coefficient pair
     * @return the delta, from 16 to 32767
     */
    private int initialDelta(int[] plane, int count, int predictor) {
        var first = coefficients[2 * predictor];
        var second = coefficients[2 * predictor + 1];
        var sum = 0L;
        var terms = 0;
        for (var i = 2; i < Math.min(count, 6); i++) {
            sum += Math.abs(plane[i] - predict(first, second, plane[i - 1], plane[i - 2]));
            terms++;
        }
        if (terms == 0) {
            return MIN_DELTA;
        }
        // an average error of about two deltas codes in the middle of the 4-bit range
        return (int) Math.max(MIN_DELTA, Math.min(32767, sum / terms / 2));
    }

    /**
     * Codes a channel without storing the codes and returns the squared error of the reconstruction.
     *
     * @param plane the channel's samples
     * @param count the samples in the block
     * @param predictor the coefficient pair
     * @param delta the initial quantization delta
     * @return the sum of the squared errors
     */
    private long trial(int[] plane, int count, int predictor, int delta) {
        var first = coefficients[2 * predictor];
        var second = coefficients[2 * predictor + 1];
        var previous = plane[1];
        var beforePrevious = plane[0];
        var error = 0L;
        for (var i = 2; i < count; i++) {
            var prediction = predict(first, second, previous, beforePrevious);
            var code = quantize(plane[i] - prediction, delta);
            var sample = Math.max(-32768, Math.min(32767, prediction + code * delta));
            var difference = (long) plane[i] - sample;
            error += difference * difference;
            beforePrevious = previous;
            previous = sample;
            delta = adapt(delta, code & 0x0F);
        }
        return error;
    }

    /**
     * Codes a channel into the block.
     *
     * @param plane the channel's samples
     * @param count the samples in the block
     * @param predictor the coefficient pair
     * @param delta the initial quantization delta
     * @param target the block
     * @param position the offset of the codes in the block
     * @param channel the channel's index
     * @param channels the number of channels
     */
    private void codeChannel(int[] plane, int count, int predictor, int delta, byte[] target, int position,
                             int channel, int channels) {
        var first = coefficients[2 * predictor];
        var second = coefficients[2 * predictor + 1];
        var previous = plane[1];
        var beforePrevious = plane[0];
        for (var i = 2; i < count; i++) {
            var prediction = predict(first, second, previous, beforePrevious);
            var code = quantize(plane[i] - prediction, delta);
            var sample = Math.max(-32768, Math.min(32767, prediction + code * delta));
            var n = (i - 2) * channels + channel;
            var nibble = code & 0x0F;
            if ((n & 1) == 0) {
                target[position + (n >> 1)] = (byte) (target[position + (n >> 1)] & 0x0F | nibble << 4);
            } else {
                target[position + (n >> 1)] = (byte) (target[position + (n >> 1)] & 0xF0 | nibble);
            }
            beforePrevious = previous;
            previous = sample;
            delta = adapt(delta, nibble);
        }
    }

    /**
     * Quantizes a prediction error to a signed code.
     *
     * @param error the prediction error
     * @param delta the quantization delta
     * @return the code, from -8 to 7, nearest to {@code error / delta}
     */
    private static int quantize(int error, int delta) {
        var code = Math.floorDiv(2L * error + delta, 2L * delta);
        return (int) Math.max(-8, Math.min(7, code));
    }
}
