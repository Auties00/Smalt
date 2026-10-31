package com.github.auties00.smalt.internal.dsd;

import java.util.Arrays;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts 1-bit DSD streams to PCM: a linear-phase low-pass FIR filter applied at the DSD rate and evaluated once
 * every {@code ratio} DSD samples.
 *
 * <p>The filter is a Kaiser-windowed sinc of {@code 8 * groups} taps whose pass band ends at 40% of the output rate
 * and whose stop band, attenuated by 120 dB, starts at half the output rate; its coefficients sum to 1, so a DSD bit
 * of 1 counts as +1 and a bit of 0 as -1. Output sample {@code k} is centered on DSD sample {@code k * ratio}: the
 * filter's delay is compensated, and the DSD samples the filter reaches before the start and past the end of the
 * stream are the silence pattern {@code 0x69}. A stream of {@code n} bytes per channel yields
 * {@code floor(8 * n / ratio)} samples per channel.
 *
 * <p>Bytes are fed most significant bit first (the oldest DSD sample in bit 7); {@link #REVERSED} converts the
 * least significant bit first order of DSF files. Every channel is fed the same number of bytes.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation sums a table of 256 partial sums per group of eight taps, so each output sample
 * costs one table lookup per eight taps; the tables of each ratio are built once per JVM.
 */
public final class DsdDecimator {
    /**
     * The DSD silence pattern, which pads the stream at both ends.
     */
    public static final int SILENCE = 0x69;

    /**
     * The bit-reversal table that converts a least significant bit first byte to most significant bit first.
     */
    public static final byte[] REVERSED = reversed();

    /**
     * The smallest decimation ratio.
     */
    public static final int MIN_RATIO = 8;

    /**
     * The default decimation ratio: DSD64 decodes at 176.4 kHz, the rate of its DSD over PCM transport.
     */
    public static final int DEFAULT_RATIO = 16;

    /**
     * The largest decimation ratio.
     */
    public static final int MAX_RATIO = 512;

    private static final double ATTENUATION = 120;

    private static final ConcurrentHashMap<Integer, Filter> FILTERS = new ConcurrentHashMap<>();

    private final int channels;

    private final int ratio;

    private final int step;

    private final int groups;

    private final int half;

    private final double[] table;

    private byte[][] history;

    /**
     * The number of bytes per channel held in {@link #history}, from index 0.
     */
    private int length;

    /**
     * The index in {@link #history} of the center byte of the next output sample.
     */
    private int center;

    /**
     * The number of bytes per channel being fed since {@link #prepare(int)}.
     */
    private int pending;

    private long fed;

    private long produced;

    private boolean flushed;

    /**
     * The coefficients and lookup table of one ratio.
     *
     * @param taps the coefficients, earliest DSD sample first
     * @param table the partial sums, {@code 256} per group of eight taps
     */
    private record Filter(double[] taps, double[] table) {
    }

    /**
     * Constructs a decimator.
     *
     * @param channels the channel count, positive
     * @param ratio the decimation ratio, a power of two from {@value #MIN_RATIO} to {@value #MAX_RATIO}
     * @throws IllegalArgumentException if {@code channels} is not positive or {@code ratio} is out of range
     */
    public DsdDecimator(int channels, int ratio) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels: " + channels);
        }
        checkRatio(ratio);
        this.channels = channels;
        this.ratio = ratio;
        this.step = ratio / 8;
        var filter = filter(ratio);
        this.table = filter.table();
        this.groups = filter.taps().length / 8;
        this.half = groups / 2;
        this.history = new byte[channels][Math.max(4 * groups, 4096)];
        reset();
    }

    private static void checkRatio(int ratio) {
        if (ratio < MIN_RATIO || ratio > MAX_RATIO || Integer.bitCount(ratio) != 1) {
            throw new IllegalArgumentException("decimation ratio: " + ratio);
        }
    }

    /**
     * Returns the decimation ratio that brings a DSD rate closest to, and not below, a preferred output rate.
     *
     * <p>Without a preferred rate the ratio is {@value #DEFAULT_RATIO}; a preferred rate above the DSD rate divided
     * by {@value #MIN_RATIO} gives {@value #MIN_RATIO}.
     *
     * @param dsdRate the DSD rate in hertz
     * @param preferred the preferred output rate in hertz, if any
     * @return a power of two from {@value #MIN_RATIO} to {@value #MAX_RATIO}
     */
    public static int ratioFor(int dsdRate, OptionalInt preferred) {
        if (preferred.isEmpty()) {
            return DEFAULT_RATIO;
        }
        var target = Math.max(preferred.getAsInt(), 1);
        var ratio = MIN_RATIO;
        while (ratio < MAX_RATIO && (long) dsdRate / (ratio * 2L) >= target) {
            ratio *= 2;
        }
        return ratio;
    }

    /**
     * Returns the filter coefficients of a ratio, earliest DSD sample first.
     *
     * @param ratio the decimation ratio
     * @return a new array of {@code 8 * groups} coefficients summing to 1
     * @throws IllegalArgumentException if {@code ratio} is out of range
     */
    public static double[] coefficients(int ratio) {
        checkRatio(ratio);
        return filter(ratio).taps().clone();
    }

    private static Filter filter(int ratio) {
        return FILTERS.computeIfAbsent(ratio, DsdDecimator::design);
    }

    private static Filter design(int ratio) {
        // Kaiser's estimate for a transition from 0.4 to 0.5 of the output rate, rounded up to whole even groups.
        var width = 2 * StrictMath.PI * 0.1 / ratio;
        var order = (ATTENUATION - 7.95) / (2.285 * width);
        var groups = 2 * (int) StrictMath.ceil((order + 1) / 16);
        var count = groups * 8;
        var beta = 0.1102 * (ATTENUATION - 8.7);
        var cutoff = 0.45 / ratio;
        var taps = new double[count];
        var sum = 0.0;
        var middle = (count - 1) / 2.0;
        var normalizer = besselI0(beta);
        for (var n = 0; n < count; n++) {
            var t = n - middle;
            var x = 2 * cutoff * t;
            var sinc = StrictMath.sin(StrictMath.PI * x) / (StrictMath.PI * x);
            var r = t / middle;
            var window = besselI0(beta * StrictMath.sqrt(Math.max(0, 1 - r * r))) / normalizer;
            taps[n] = 2 * cutoff * sinc * window;
            sum += taps[n];
        }
        for (var n = 0; n < count; n++) {
            taps[n] /= sum;
        }
        var table = new double[groups * 256];
        for (var g = 0; g < groups; g++) {
            for (var b = 0; b < 256; b++) {
                var acc = 0.0;
                for (var i = 0; i < 8; i++) {
                    var tap = taps[g * 8 + i];
                    acc += (b & (0x80 >> i)) != 0 ? tap : -tap;
                }
                table[g * 256 + b] = acc;
            }
        }
        return new Filter(taps, table);
    }

    private static double besselI0(double x) {
        var sum = 1.0;
        var term = 1.0;
        var half = x / 2;
        for (var k = 1; k < 200; k++) {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return sum;
    }

    private static byte[] reversed() {
        var table = new byte[256];
        for (var i = 0; i < 256; i++) {
            table[i] = (byte) (Integer.reverse(i) >>> 24);
        }
        return table;
    }

    /**
     * {@return the channel count}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the decimation ratio}
     */
    public int ratio() {
        return ratio;
    }

    /**
     * {@return the filter length in DSD samples}
     */
    public int taps() {
        return groups * 8;
    }

    /**
     * Restarts the stream: the next byte fed is the first of a new stream.
     */
    public void reset() {
        for (var line : history) {
            Arrays.fill(line, 0, half, (byte) SILENCE);
        }
        length = half;
        center = half;
        fed = 0;
        produced = 0;
        flushed = false;
    }

    /**
     * Makes room for {@code bytes} more bytes per channel, discarding the bytes no further output needs.
     *
     * @param bytes the number of bytes about to be fed per channel
     */
    private void reserve(int bytes) {
        var keep = center - half;
        if (keep > 0) {
            for (var line : history) {
                System.arraycopy(line, keep, line, 0, length - keep);
            }
            length -= keep;
            center -= keep;
        }
        if (length + bytes > history[0].length) {
            var capacity = Math.max(length + bytes, history[0].length * 2);
            for (var c = 0; c < channels; c++) {
                history[c] = Arrays.copyOf(history[c], capacity);
            }
        }
    }

    /**
     * Feeds the bytes of one channel at a stride, most significant bit first unless {@code reverse} is set.
     *
     * <p>Callers feed every channel the same number of bytes before the next {@link #drain} and must call
     * {@link #prepare(int)} first.
     *
     * @param channel the index of the channel, from 0
     * @param source the bytes
     * @param offset the index of the first byte
     * @param stride the distance between consecutive bytes of the channel, 1 for planar data
     * @param bytes the number of bytes
     * @param reverse whether the bytes are least significant bit first
     */
    public void feed(int channel, byte[] source, int offset, int stride, int bytes, boolean reverse) {
        var line = history[channel];
        var at = length;
        if (reverse) {
            for (var i = 0; i < bytes; i++) {
                line[at + i] = REVERSED[source[offset + i * stride] & 0xFF];
            }
        } else if (stride == 1) {
            System.arraycopy(source, offset, line, at, bytes);
        } else {
            for (var i = 0; i < bytes; i++) {
                line[at + i] = source[offset + i * stride];
            }
        }
    }

    /**
     * Prepares the feeding of {@code bytes} bytes per channel through {@link #feed}.
     *
     * @param bytes the number of bytes per channel the following {@code feed} calls add, each channel
     * @throws IllegalStateException if the stream was flushed
     */
    public void prepare(int bytes) {
        if (flushed) {
            throw new IllegalStateException("flushed");
        }
        reserve(bytes);
        pending = bytes;
    }

    /**
     * Commits the bytes fed since {@link #prepare(int)}.
     */
    public void commit() {
        length += pending;
        fed += pending;
        pending = 0;
    }

    /**
     * Feeds one byte per channel, most significant bit first, from {@code values[channel]}; used by sources that
     * produce bytes one frame at a time.
     *
     * @param values one byte per channel
     */
    public void feedFrame(int[] values) {
        if (length == history[0].length) {
            reserve(4096);
        }
        for (var c = 0; c < channels; c++) {
            history[c][length] = (byte) values[c];
        }
        length++;
        fed++;
    }

    /**
     * {@return the number of samples per channel the next {@link #drain} produces}
     */
    public int available() {
        var limit = flushed ? (fed * 8 / ratio) - produced : Long.MAX_VALUE;
        var ready = length - half - center;
        if (ready < 0) {
            return 0;
        }
        long count = ready / step + 1;
        return (int) Math.max(0, Math.min(count, limit));
    }

    /**
     * Writes every sample the fed bytes complete into {@code output}, from index {@code offset} of each plane.
     *
     * @param output one plane per channel, with room for {@link #available()} samples
     * @param offset the index of the first sample written
     * @return the number of samples written per channel
     */
    public int drain(float[][] output, int offset) {
        var count = available();
        for (var c = 0; c < channels; c++) {
            var line = history[c];
            var plane = output[c];
            var start = center - half;
            for (var k = 0; k < count; k++) {
                plane[offset + k] = (float) sum(line, start + k * step);
            }
        }
        advance(count);
        return count;
    }

    /**
     * Writes every sample the fed bytes complete into {@code output} at double precision.
     *
     * @param output one plane per channel, with room for {@link #available()} samples
     * @param offset the index of the first sample written
     * @return the number of samples written per channel
     */
    public int drain(double[][] output, int offset) {
        var count = available();
        for (var c = 0; c < channels; c++) {
            var line = history[c];
            var plane = output[c];
            var start = center - half;
            for (var k = 0; k < count; k++) {
                plane[offset + k] = sum(line, start + k * step);
            }
        }
        advance(count);
        return count;
    }

    private double sum(byte[] line, int start) {
        var acc = 0.0;
        var t = table;
        for (var g = 0; g < groups; g++) {
            acc += t[(g << 8) + (line[start + g] & 0xFF)];
        }
        return acc;
    }

    private void advance(int count) {
        center += count * step;
        produced += count;
    }

    /**
     * Ends the stream: pads it with silence so that {@link #drain} produces the samples of its last bytes.
     */
    public void flush() {
        if (flushed) {
            return;
        }
        reserve(half);
        for (var line : history) {
            Arrays.fill(line, length, length + half, (byte) SILENCE);
        }
        length += half;
        flushed = true;
    }
}
