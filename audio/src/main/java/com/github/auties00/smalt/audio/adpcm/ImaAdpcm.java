package com.github.auties00.smalt.audio.adpcm;

import com.github.auties00.smalt.MediaException;

/**
 * IMA ADPCM (the DVI ADPCM of the IMA Digital Audio Focus recommendation) in Microsoft's WAVE block layout and in
 * Apple's {@code ima4} frames.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class ImaAdpcm {
    /**
     * The quantizer step sizes, indexed by the step index.
     */
    static final int[] STEPS = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55,
            60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494,
            544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
            3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289,
            16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};

    /**
     * The change of the step index for each code magnitude.
     */
    private static final int[] INDEX_STEPS = {-1, -1, -1, -1, 2, 4, 6, 8};

    /**
     * The largest step index.
     */
    static final int MAX_INDEX = 88;

    /**
     * The bytes of one channel of an {@code ima4} frame.
     */
    static final int QT_CHANNEL_BYTES = 34;

    /**
     * The samples per channel of an {@code ima4} frame.
     */
    static final int QT_SAMPLES = 64;

    /**
     * The predictor of each channel, the last decoded or coded sample.
     */
    private final int[] predictors;

    /**
     * The step index of each channel.
     */
    private final int[] indices;

    /**
     * Constructs the state of a stream.
     *
     * @param channels the number of channels
     */
    ImaAdpcm(int channels) {
        this.predictors = new int[channels];
        this.indices = new int[channels];
    }

    /**
     * Expands one code, updating a channel's predictor and step index.
     *
     * @param channel the index of the channel
     * @param code the 4-bit code
     * @return the decoded sample
     */
    private int expand(int channel, int code) {
        var index = indices[channel];
        var step = STEPS[index];
        // the recommendation's shift-and-add quantizer, which a multiplication would round differently
        var difference = step >> 3;
        if ((code & 4) != 0) {
            difference += step;
        }
        if ((code & 2) != 0) {
            difference += step >> 1;
        }
        if ((code & 1) != 0) {
            difference += step >> 2;
        }
        var predictor = predictors[channel] + ((code & 8) != 0 ? -difference : difference);
        predictor = Math.max(-32768, Math.min(32767, predictor));
        predictors[channel] = predictor;
        indices[channel] = Math.max(0, Math.min(MAX_INDEX, index + INDEX_STEPS[code & 7]));
        return predictor;
    }

    /**
     * Quantizes one sample, updating a channel's predictor and step index as the decoder will.
     *
     * @param channel the index of the channel
     * @param sample the 16-bit sample
     * @return the 4-bit code
     */
    private int compress(int channel, int sample) {
        var index = indices[channel];
        var step = STEPS[index];
        var difference = sample - predictors[channel];
        var code = 0;
        if (difference < 0) {
            code = 8;
            difference = -difference;
        }
        var reconstructed = step >> 3;
        if (difference >= step) {
            code |= 4;
            difference -= step;
            reconstructed += step;
        }
        step >>= 1;
        if (difference >= step) {
            code |= 2;
            difference -= step;
            reconstructed += step;
        }
        step >>= 1;
        if (difference >= step) {
            code |= 1;
            reconstructed += step;
        }
        var predictor = predictors[channel] + ((code & 8) != 0 ? -reconstructed : reconstructed);
        predictors[channel] = Math.max(-32768, Math.min(32767, predictor));
        indices[channel] = Math.max(0, Math.min(MAX_INDEX, index + INDEX_STEPS[code & 7]));
        return code;
    }

    /**
     * Returns the samples per channel of a WAVE block.
     *
     * @param bytes the size of the block
     * @param channels the number of channels
     * @return the samples per channel, or 0 when the block is shorter than its headers
     */
    static int wavSamples(int bytes, int channels) {
        if (bytes < 4 * channels) {
            return 0;
        }
        return 1 + (bytes - 4 * channels) / (4 * channels) * 8;
    }

    /**
     * Decodes one WAVE block.
     *
     * @param data the packet
     * @param planes receives the samples, one plane per channel
     * @return the samples per channel decoded
     * @throws MediaException.Malformed if the block is shorter than its headers or a step index exceeds 88
     */
    int decodeWav(byte[] data, int[][] planes) {
        var channels = planes.length;
        var samples = wavSamples(data.length, channels);
        if (samples == 0) {
            throw new MediaException.Malformed("IMA ADPCM block of " + data.length + " bytes for " + channels
                    + " channels");
        }
        for (var c = 0; c < channels; c++) {
            var position = 4 * c;
            var first = (short) ((data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8);
            var index = data[position + 2] & 0xFF;
            if (index > MAX_INDEX) {
                throw new MediaException.Malformed("IMA ADPCM step index " + index);
            }
            predictors[c] = first;
            indices[c] = index;
            planes[c][0] = first;
        }
        var position = 4 * channels;
        for (var sample = 1; sample < samples; sample += 8) {
            for (var c = 0; c < channels; c++) {
                var plane = planes[c];
                for (var k = 0; k < 4; k++) {
                    var value = data[position++] & 0xFF;
                    plane[sample + 2 * k] = expand(c, value & 0x0F);
                    plane[sample + 2 * k + 1] = expand(c, value >>> 4);
                }
            }
        }
        return samples;
    }

    /**
     * Codes one WAVE block; the block header stores each channel's first sample and the step index carried over
     * from the previous block.
     *
     * @param planes the samples, one plane per channel
     * @param count the samples per channel, {@code 1 + 8k}
     * @param target receives the block, {@code 4 * channels * (1 + (count - 1) / 8)} bytes
     */
    void encodeWav(int[][] planes, int count, byte[] target) {
        var channels = planes.length;
        for (var c = 0; c < channels; c++) {
            var first = planes[c][0];
            predictors[c] = first;
            var position = 4 * c;
            target[position] = (byte) first;
            target[position + 1] = (byte) (first >> 8);
            target[position + 2] = (byte) indices[c];
            target[position + 3] = 0;
        }
        var position = 4 * channels;
        for (var sample = 1; sample < count; sample += 8) {
            for (var c = 0; c < channels; c++) {
                var plane = planes[c];
                for (var k = 0; k < 4; k++) {
                    var low = compress(c, plane[sample + 2 * k]);
                    var high = compress(c, plane[sample + 2 * k + 1]);
                    target[position++] = (byte) (low | high << 4);
                }
            }
        }
    }

    /**
     * Decodes the {@code ima4} frames of a packet.
     *
     * @param data the packet, whole frames of {@code 34 * channels} bytes
     * @param planes receives the samples, one plane per channel, each at least 64 per frame long
     * @return the samples per channel decoded
     * @throws MediaException.Malformed if the packet does not hold whole frames
     */
    int decodeQt(byte[] data, int[][] planes) {
        var channels = planes.length;
        var frameBytes = QT_CHANNEL_BYTES * channels;
        if (data.length % frameBytes != 0) {
            throw new MediaException.Malformed("IMA4 packet of " + data.length + " bytes for " + channels
                    + " channels");
        }
        var frames = data.length / frameBytes;
        for (var f = 0; f < frames; f++) {
            for (var c = 0; c < channels; c++) {
                var position = f * frameBytes + c * QT_CHANNEL_BYTES;
                var header = (data[position] & 0xFF) << 8 | (data[position + 1] & 0xFF);
                var predictor = (short) (header & 0xFF80);
                var index = Math.min(MAX_INDEX, header & 0x7F);
                if (indices[c] != index || (predictors[c] & ~0x7F) != predictor) {
                    predictors[c] = predictor;
                    indices[c] = index;
                }
                var plane = planes[c];
                var out = f * QT_SAMPLES;
                for (var k = 2; k < QT_CHANNEL_BYTES; k++) {
                    var value = data[position + k] & 0xFF;
                    plane[out++] = expand(c, value & 0x0F);
                    plane[out++] = expand(c, value >>> 4);
                }
            }
        }
        return frames * QT_SAMPLES;
    }

    /**
     * Codes one {@code ima4} frame; the header stores each channel's predictor, truncated to its top nine bits, and
     * step index, which the decoder's own state matches, so the full predictor carries over.
     *
     * @param planes the 64 samples of each channel
     * @param target receives the frame, {@code 34 * channels} bytes
     */
    void encodeQt(int[][] planes, byte[] target) {
        for (var c = 0; c < planes.length; c++) {
            var position = c * QT_CHANNEL_BYTES;
            var header = predictors[c] & 0xFF80 | indices[c];
            target[position] = (byte) (header >> 8);
            target[position + 1] = (byte) header;
            var plane = planes[c];
            for (var k = 0; k < QT_SAMPLES / 2; k++) {
                var low = compress(c, plane[2 * k]);
                var high = compress(c, plane[2 * k + 1]);
                target[position + 2 + k] = (byte) (low | high << 4);
            }
        }
    }
}
