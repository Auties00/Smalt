package com.github.auties00.smalt.audio.loudness;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Synthesizes the EBU Tech 3341 and 3342 test signals and writes float WAV files for the ffmpeg
 * comparisons.
 */
final class Signals {
    private Signals() {
    }

    /**
     * Builds a planar signal of consecutive 1 kHz tone segments, the same on every channel, each
     * segment given as {@code seconds, dBFS} (peak level) pairs; a level of {@code NaN} is silence.
     */
    static float[][] tones(int rate, int channels, double... segments) {
        var total = 0L;
        for (var index = 0; index < segments.length; index += 2) {
            total += Math.round(segments[index] * rate);
        }
        var planes = new float[channels][(int) total];
        var position = 0;
        for (var index = 0; index < segments.length; index += 2) {
            var frames = (int) Math.round(segments[index] * rate);
            var level = segments[index + 1];
            if (!Double.isNaN(level)) {
                var amplitude = Math.pow(10, level / 20);
                for (var frame = 0; frame < frames; frame++) {
                    // the phase runs on across segments, as one continuous oscillator
                    var value = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * (position + frame) / rate));
                    for (var plane : planes) {
                        plane[position + frame] = value;
                    }
                }
            }
            position += frames;
        }
        return planes;
    }

    /**
     * Builds a 1 kHz tone with a peak level per channel.
     */
    static float[][] channels(int rate, double seconds, double... levels) {
        var frames = (int) Math.round(seconds * rate);
        var planes = new float[levels.length][frames];
        for (var channel = 0; channel < levels.length; channel++) {
            var amplitude = Math.pow(10, levels[channel] / 20);
            for (var frame = 0; frame < frames; frame++) {
                planes[channel][frame] = (float) (amplitude * Math.sin(2 * Math.PI * 1000 * frame / rate));
            }
        }
        return planes;
    }

    /**
     * Builds the stereo sine of true-peak test cases 15 to 19: a frequency, an amplitude in
     * fractions of full scale and a phase in degrees, tapered with 10 ms fades.
     */
    static float[][] truePeakSine(int rate, double frequency, double amplitude, double phaseDegrees) {
        var frames = rate;
        var plane = new float[frames];
        var phase = Math.toRadians(phaseDegrees);
        var fade = rate / 100;
        for (var frame = 0; frame < frames; frame++) {
            var gain = Math.min(1.0, Math.min(frame, frames - 1 - frame) / (double) fade);
            plane[frame] = (float) (gain * amplitude * Math.sin(2 * Math.PI * frequency * frame / rate + phase));
        }
        return new float[][]{plane, plane.clone()};
    }

    /**
     * Builds the stereo signal of true-peak test cases 20 to 23: a 0.5 FFS sine at fs/6 holding a
     * single period of a full-scale sine at fs/4, synthesized at 4 fs, low-pass filtered and
     * decimated by four starting at an offset.
     */
    static float[][] truePeakBurst(int rate, int offset) {
        var oversampled = new ArrayList<Double>();
        var fade = 4 * rate / 100;
        // fs/6 at 4 fs has a 24-sample period, fs/4 a 16-sample period
        for (var n = 0; n < 24 * 2000; n++) {
            oversampled.add(0.5 * Math.sin(2 * Math.PI * n / 24));
        }
        for (var n = 0; n < 16; n++) {
            oversampled.add(Math.sin(2 * Math.PI * n / 16));
        }
        for (var n = 0; n < 24 * 2000; n++) {
            oversampled.add(0.5 * Math.sin(2 * Math.PI * n / 24));
        }
        var length = oversampled.size();
        var signal = new double[length];
        for (var n = 0; n < length; n++) {
            var gain = Math.min(1.0, Math.min(n, length - 1 - n) / (double) fade);
            signal[n] = gain * oversampled.get(n);
        }
        // a 511-tap Blackman-windowed sinc cutting at 0.45 fs, the anti-aliasing filter of the decimation
        var taps = 511;
        var kernel = new double[taps];
        var cutoff = 0.45 / 4;
        for (var k = 0; k < taps; k++) {
            var m = k - (taps - 1) / 2.0;
            var sinc = m == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * m) / (Math.PI * m);
            var window = 0.42 - 0.5 * Math.cos(2 * Math.PI * k / (taps - 1)) + 0.08 * Math.cos(4 * Math.PI * k / (taps - 1));
            kernel[k] = sinc * window;
        }
        var frames = (length - offset) / 4;
        var plane = new float[frames];
        for (var frame = 0; frame < frames; frame++) {
            var center = frame * 4 + offset;
            var sum = 0.0;
            for (var k = 0; k < taps; k++) {
                var index = center + k - (taps - 1) / 2;
                if (index >= 0 && index < length) {
                    sum += kernel[k] * signal[index];
                }
            }
            plane[frame] = (float) sum;
        }
        return new float[][]{plane, plane.clone()};
    }

    /**
     * Concatenates planar signals with the same channel count.
     */
    static float[][] concat(List<float[][]> parts) {
        var channels = parts.getFirst().length;
        var total = 0;
        for (var part : parts) {
            total += part[0].length;
        }
        var result = new float[channels][total];
        var position = 0;
        for (var part : parts) {
            for (var channel = 0; channel < channels; channel++) {
                System.arraycopy(part[channel], 0, result[channel], position, part[channel].length);
            }
            position += part[0].length;
        }
        return result;
    }

    /**
     * Encodes planar samples as a 32-bit float WAV file.
     */
    static byte[] wav(float[][] planes, int rate) {
        var channels = planes.length;
        var frames = planes[0].length;
        var data = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var frame = 0; frame < frames; frame++) {
            for (var plane : planes) {
                data.putFloat(plane[frame]);
            }
        }
        var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + data.capacity()).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16).putShort((short) 3).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * 4).putShort((short) (channels * 4)).putShort((short) 32);
        header.put("data".getBytes()).putInt(data.capacity());
        var out = new ByteArrayOutputStream(44 + data.capacity());
        out.writeBytes(header.array());
        out.writeBytes(data.array());
        return out.toByteArray();
    }

    /**
     * Measures planar samples in one call.
     */
    static LoudnessMeter measure(float[][] planes, int rate, com.github.auties00.smalt.audio.ChannelLayout layout) {
        var meter = new LoudnessMeter(layout, rate);
        meter.accept(planes, 0, planes[0].length);
        return meter;
    }
}
