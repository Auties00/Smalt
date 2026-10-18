package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.audio.process.Resampler;

/**
 * Converts a stream of interleaved float frames to another sampling rate, for the backends whose
 * operating system API does not.
 *
 * <p>The rate is converted with a {@link Resampler} of {@link Resampler.Quality#FAST} quality, whose
 * look-ahead delays the output by {@link #delayFrames()} frames; {@link #outputNanos(long)} moves a
 * capture time back by that delay. Instances are not thread-safe.
 */
public final class RateConverter {
    private final int channels;
    private final int outputRate;

    /**
     * The rate converter, or {@code null} when the rates are equal.
     */
    private final Resampler resampler;

    private float[][] inputPlanes;
    private float[][] outputPlanes;
    private float[] output = new float[0];

    /**
     * Constructs a converter.
     *
     * @param inputRate the input sampling rate, in hertz
     * @param outputRate the output sampling rate, in hertz
     * @param channels the channel count of both sides
     */
    public RateConverter(int inputRate, int outputRate, int channels) {
        this.channels = channels;
        this.outputRate = outputRate;
        this.resampler = inputRate == outputRate ? null : new Resampler(inputRate, outputRate, channels, Resampler.Quality.FAST);
        this.inputPlanes = new float[channels][0];
        this.outputPlanes = new float[channels][0];
    }

    /**
     * {@return whether the rates are equal, so the converter changes nothing}
     */
    public boolean identity() {
        return resampler == null;
    }

    /**
     * {@return the latency the conversion adds, in output frames}
     */
    public int delayFrames() {
        return resampler == null ? 0 : resampler.delay();
    }

    /**
     * Returns the capture time of the first frame a conversion produces.
     *
     * @param inputNanos the capture time of the first frame of the converted input, in nanoseconds
     * @return {@code inputNanos} moved back by {@link #delayFrames()}, in nanoseconds
     */
    public long outputNanos(long inputNanos) {
        return inputNanos - delayFrames() * 1_000_000_000L / outputRate;
    }

    /**
     * Converts a block of frames.
     *
     * @param input the interleaved input frames
     * @param frames the number of input frames
     * @return the number of output frames now in {@link #output()}
     */
    public int convert(float[] input, int frames) {
        if (resampler == null) {
            var count = frames * channels;
            if (output.length < count) {
                output = new float[count];
            }
            System.arraycopy(input, 0, output, 0, count);
            return frames;
        }
        inputPlanes = grow(inputPlanes, frames);
        for (var c = 0; c < channels; c++) {
            var plane = inputPlanes[c];
            for (var i = 0; i < frames; i++) {
                plane[i] = input[i * channels + c];
            }
        }
        outputPlanes = grow(outputPlanes, resampler.maxOutputFrames(frames));
        var produced = resampler.process(inputPlanes, frames, outputPlanes, 0);
        var needed = produced * channels;
        if (output.length < needed) {
            output = new float[needed];
        }
        for (var c = 0; c < channels; c++) {
            var plane = outputPlanes[c];
            for (var i = 0; i < produced; i++) {
                output[i * channels + c] = plane[i];
            }
        }
        return produced;
    }

    /**
     * {@return the interleaved output of the last {@link #convert(float[], int)}, valid until the next
     * conversion}
     */
    public float[] output() {
        return output;
    }

    /**
     * Returns planes at least {@code frames} long, reusing the given ones when they are.
     */
    private static float[][] grow(float[][] planes, int frames) {
        if (planes.length > 0 && planes[0].length >= frames) {
            return planes;
        }
        var grown = new float[planes.length][];
        for (var c = 0; c < planes.length; c++) {
            grown[c] = new float[Math.max(frames, 64)];
        }
        return grown;
    }
}
