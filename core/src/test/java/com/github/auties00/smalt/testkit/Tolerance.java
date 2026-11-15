package com.github.auties00.smalt.testkit;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.audio.OpusCompare;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rule by which a decode may differ from its reference, which every differential test declares once per codec
 * instead of writing its own threshold arithmetic.
 *
 * <p>The rules follow the comparison modes of FFmpeg's FATE ({@code CMP=diff}, {@code oneoff} and {@code stddev})
 * and the criteria the codec standards define:
 * <ul>
 *   <li>{@link #exact()}: every sample equal, for normative integer decoders (H.264, HEVC, VP8, VP9, AV1, FLAC,
 *       PNG, lossless WebP, JPEG decoded with the same inverse DCT);</li>
 *   <li>{@link #maxAbs(double)} and {@link #offByOne()}: every sample within a bound, for integer decoders whose
 *       rounding is allowed to differ;</li>
 *   <li>{@link #meanAbs(double)}, {@link #rms(double)} (FATE's {@code stddev}, the root of the mean squared
 *       difference), {@link #psnr(double)}, {@link #snr(double)} and {@link #differing(double)}: aggregate bounds,
 *       for float decoders and for decoders whose inverse transform is not normative;</li>
 *   <li>{@link #iso11172(Iso11172Accuracy)}: the ISO/IEC 11172-4 (MPEG audio) compliance limits on the difference
 *       of full-scale samples;</li>
 *   <li>{@link #opusCompare(int, int)}: the RFC 6716 and RFC 8251 conformance criterion of libopus'
 *       {@code opus_compare};</li>
 *   <li>{@link Ieee1180} carries the IEEE 1180-1990 inverse DCT accuracy test, which runs a transform rather than
 *       comparing two decodes.</li>
 * </ul>
 * Rules combine with {@link #and(Tolerance)}. Sample units are those of the compared arrays: 8-bit or deeper
 * integer samples, signed 16-bit PCM, or full-scale float audio (full scale 1.0).
 *
 * <p>A violation fails the test with every measured statistic and the first differing sample, and, through the
 * {@code assertFrames} methods, the first frame that breaks the rule. Instances are immutable and thread-safe.
 */
public sealed interface Tolerance {
    /**
     * The ISO/IEC 11172-4 (and ISO/IEC 13818-4) accuracy classes of an MPEG audio decoder.
     */
    enum Iso11172Accuracy {
        /**
         * Full accuracy: the RMS of the difference below {@code 2^-15 / sqrt(12)} of full scale and no difference
         * larger than {@code 2^-14} of full scale.
         */
        FULL(Math.pow(2, -15) / Math.sqrt(12), Math.pow(2, -14)),

        /**
         * Limited accuracy: the RMS of the difference below {@code 2^-11 / sqrt(12)} of full scale, with no bound on
         * a single difference.
         */
        LIMITED(Math.pow(2, -11) / Math.sqrt(12), Double.POSITIVE_INFINITY);

        private final double rms;
        private final double maxAbs;

        Iso11172Accuracy(double rms, double maxAbs) {
            this.rms = rms;
            this.maxAbs = maxAbs;
        }

        /** {@return the largest RMS of the difference, as a fraction of full scale, exclusive} */
        public double rms() {
            return rms;
        }

        /** {@return the largest absolute difference, as a fraction of full scale, inclusive} */
        public double maxAbs() {
            return maxAbs;
        }
    }

    /** {@return the rule that every sample equals its reference} */
    static Tolerance exact() {
        return Exact.INSTANCE;
    }

    /** {@return the rule that every sample is within one unit of its reference, FATE's {@code oneoff}} */
    static Tolerance offByOne() {
        return new MaxAbs(1);
    }

    /**
     * Returns the rule that every sample is within a bound of its reference.
     *
     * @param limit the largest absolute difference, inclusive, in sample units
     * @return the rule
     */
    static Tolerance maxAbs(double limit) {
        return new MaxAbs(limit);
    }

    /**
     * Returns the rule that the mean absolute difference stays within a bound.
     *
     * @param limit the largest mean absolute difference, inclusive, in sample units
     * @return the rule
     */
    static Tolerance meanAbs(double limit) {
        return new MeanAbs(limit);
    }

    /**
     * Returns the rule that the root of the mean squared difference stays within a bound, FATE's {@code stddev}.
     *
     * @param limit the largest RMS difference, inclusive, in sample units
     * @return the rule
     */
    static Tolerance rms(double limit) {
        return new Rms(limit);
    }

    /**
     * Returns the rule that the peak signal-to-noise ratio reaches a floor; the peak is the largest sample value of
     * the compared arrays' type (255, {@code 2^bitDepth - 1}, 32767 or 1.0).
     *
     * @param minimum the smallest PSNR, inclusive, in decibels
     * @return the rule
     */
    static Tolerance psnr(double minimum) {
        return new Psnr(minimum);
    }

    /**
     * Returns the rule that the signal-to-noise ratio of the reference over the difference reaches a floor.
     *
     * @param minimum the smallest SNR, inclusive, in decibels
     * @return the rule
     */
    static Tolerance snr(double minimum) {
        return new Snr(minimum);
    }

    /**
     * Returns the rule that at most a fraction of the samples differ at all.
     *
     * @param fraction the largest fraction of differing samples, inclusive, from 0 to 1
     * @return the rule
     */
    static Tolerance differing(double fraction) {
        return new Differing(fraction);
    }

    /**
     * Returns the ISO/IEC 11172-4 compliance rule of an accuracy class, over samples of full scale 1.0 (float
     * audio) or 32768 (16-bit PCM).
     *
     * @param accuracy the accuracy class
     * @return the rule
     */
    static Tolerance iso11172(Iso11172Accuracy accuracy) {
        return new Iso11172(accuracy);
    }

    /**
     * Returns the {@code opus_compare} conformance criterion: the decode, at {@code rate}, passes when the quality
     * metric of {@link OpusCompare} is not negative against the 48 kHz reference. Mono and stereo signals are
     * compared as {@code opus_compare} compares them; a signal of more channels is compared channel by channel,
     * each as a mono signal. It applies to PCM only ({@code assertPcm}).
     *
     * @param channels the channel count of both signals
     * @param rate the sampling rate of the decode under test, one of 8000, 12000, 16000, 24000 and 48000
     * @return the rule
     */
    static Tolerance opusCompare(int channels, int rate) {
        return new OpusPerceptual(channels, rate);
    }

    /**
     * Returns the rule that both this rule and another hold.
     *
     * @param other the other rule
     * @return the combined rule
     */
    default Tolerance and(Tolerance other) {
        var rules = new ArrayList<Tolerance>();
        for (var rule : List.of(this, other)) {
            if (rule instanceof All all) {
                rules.addAll(all.rules());
            } else {
                rules.add(rule);
            }
        }
        return new All(List.copyOf(rules));
    }

    /**
     * Returns whether a measured difference satisfies this rule.
     *
     * @param difference the measured difference
     * @return the violated bounds, empty when the rule holds
     * @throws IllegalArgumentException if this rule does not apply to measured differences
     *                                  ({@link #opusCompare(int, int)})
     */
    List<String> violations(Difference difference);

    /** {@return a short description of the rule, as failure messages print it} */
    String describe();

    /**
     * Asserts that two arrays of 8-bit unsigned samples (peak 255) satisfy this rule.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference samples
     * @param actual the samples under test
     */
    default void assertWithin(String what, byte[] expected, byte[] actual) {
        assertLength(what, expected.length, actual.length);
        check(what, Difference.of(expected, actual));
    }

    /**
     * Asserts that two arrays of unsigned samples of a bit depth of 9 to 16 bits satisfy this rule.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference samples
     * @param actual the samples under test
     * @param bitDepth the bit depth, which sets the peak to {@code 2^bitDepth - 1}
     */
    default void assertWithin(String what, short[] expected, short[] actual, int bitDepth) {
        assertLength(what, expected.length, actual.length);
        check(what, Difference.of(expected, actual, bitDepth));
    }

    /**
     * Asserts that two arrays of signed 32-bit samples (full scale {@code 2^31}), such as left-justified PCM, satisfy
     * this rule.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference samples
     * @param actual the samples under test
     */
    default void assertWithin(String what, int[] expected, int[] actual) {
        assertLength(what, expected.length, actual.length);
        check(what, Difference.of(expected, actual));
    }

    /**
     * Asserts that two pictures of the same type, size and layout satisfy this rule over every sample of every plane.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference picture
     * @param actual the picture under test
     */
    default void assertWithin(String what, Picture expected, Picture actual) {
        Assertions.assertEquals(expected.getClass(), actual.getClass(), what + ": picture type");
        Assertions.assertEquals(expected.width() + "x" + expected.height(), actual.width() + "x" + actual.height(),
                what + ": picture size");
        check(what, Difference.of(expected, actual));
    }

    /**
     * Asserts that two arrays of interleaved signed 16-bit PCM samples (full scale 32768) satisfy this rule.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference samples
     * @param actual the samples under test
     */
    default void assertPcm(String what, short[] expected, short[] actual) {
        assertLength(what, expected.length, actual.length);
        check(what, Difference.ofPcm(expected, actual));
    }

    /**
     * Asserts that two arrays of interleaved float PCM samples (full scale 1.0) satisfy this rule.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference samples
     * @param actual the samples under test
     */
    default void assertPcm(String what, float[] expected, float[] actual) {
        assertLength(what, expected.length, actual.length);
        check(what, Difference.ofPcm(expected, actual));
    }

    /**
     * Asserts that two arrays holding the same number of equally sized frames of 8-bit samples satisfy this rule
     * frame by frame, naming the first frame that breaks it.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference frames, one after the other
     * @param actual the frames under test
     * @param frameSize the number of samples of a frame, at least 1
     */
    default void assertFrames(String what, byte[] expected, byte[] actual, int frameSize) {
        if (frameSize < 1) {
            throw new IllegalArgumentException("frame size " + frameSize);
        }
        Assertions.assertEquals(expected.length / frameSize, actual.length / frameSize, what + ": frame count");
        assertLength(what, expected.length, actual.length);
        for (var frame = 0; frame * frameSize < expected.length; frame++) {
            var from = frame * frameSize;
            var to = Math.min(expected.length, from + frameSize);
            check(what + " frame " + frame, Difference.of(expected, actual, from, to));
        }
    }

    /**
     * Asserts that two arrays holding the same number of equally sized frames of unsigned samples of a bit depth of
     * 9 to 16 bits satisfy this rule frame by frame, naming the first frame that breaks it.
     *
     * @param what what is compared, for the failure message
     * @param expected the reference frames, one after the other
     * @param actual the frames under test
     * @param frameSize the number of samples of a frame, at least 1
     * @param bitDepth the bit depth, which sets the peak to {@code 2^bitDepth - 1}
     */
    default void assertFrames(String what, short[] expected, short[] actual, int frameSize, int bitDepth) {
        if (frameSize < 1) {
            throw new IllegalArgumentException("frame size " + frameSize);
        }
        Assertions.assertEquals(expected.length / frameSize, actual.length / frameSize, what + ": frame count");
        assertLength(what, expected.length, actual.length);
        for (var frame = 0; frame * frameSize < expected.length; frame++) {
            var from = frame * frameSize;
            var to = Math.min(expected.length, from + frameSize);
            check(what + " frame " + frame, Difference.of(java.util.Arrays.copyOfRange(expected, from, to),
                    java.util.Arrays.copyOfRange(actual, from, to), bitDepth));
        }
    }

    /**
     * Asserts that a measured difference satisfies this rule.
     *
     * @param what what is compared, for the failure message
     * @param difference the measured difference
     */
    default void check(String what, Difference difference) {
        var violations = violations(difference);
        if (!violations.isEmpty()) {
            Assertions.fail(what + ": " + describe() + " violated (" + String.join(", ", violations) + "); "
                    + difference);
        }
    }

    private static void assertLength(String what, int expected, int actual) {
        Assertions.assertEquals(expected, actual, what + ": sample count");
    }

    /**
     * The statistics of the difference between a reference and the samples under test.
     *
     * @param count the number of compared samples
     * @param differing the number of samples that differ
     * @param maxAbs the largest absolute difference, in sample units
     * @param sumAbs the sum of the absolute differences
     * @param sumSquares the sum of the squared differences
     * @param signalSquares the sum of the squared reference samples, for the SNR
     * @param peak the largest sample value of the sample type, for the PSNR
     * @param fullScale the sample value of full scale, for the ISO/IEC 11172-4 limits
     * @param first the index of the first differing sample, or -1 when none differs
     * @param firstExpected the reference value at {@code first}
     * @param firstActual the value under test at {@code first}
     */
    record Difference(long count, long differing, double maxAbs, double sumAbs, double sumSquares,
                      double signalSquares, double peak, double fullScale, long first, double firstExpected,
                      double firstActual) {
        /** {@return the mean absolute difference, in sample units} */
        public double meanAbs() {
            return count == 0 ? 0 : sumAbs / count;
        }

        /** {@return the root of the mean squared difference, in sample units} */
        public double rms() {
            return count == 0 ? 0 : Math.sqrt(sumSquares / count);
        }

        /** {@return the peak signal-to-noise ratio in decibels, infinite for identical samples} */
        public double psnr() {
            return sumSquares == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(peak * peak * count / sumSquares);
        }

        /** {@return the signal-to-noise ratio in decibels, infinite for identical samples} */
        public double snr() {
            return sumSquares == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signalSquares / sumSquares);
        }

        /**
         * Measures two arrays of 8-bit unsigned samples.
         *
         * @param expected the reference samples
         * @param actual the samples under test, at least as long as {@code expected}
         * @return the statistics
         */
        public static Difference of(byte[] expected, byte[] actual) {
            return of(expected, actual, 0, expected.length);
        }

        /**
         * Measures a range of two arrays of 8-bit unsigned samples.
         *
         * @param expected the reference samples
         * @param actual the samples under test
         * @param from the first index, inclusive
         * @param to the last index, exclusive
         * @return the statistics
         */
        public static Difference of(byte[] expected, byte[] actual, int from, int to) {
            var accumulator = new Accumulator(255, 255);
            for (var i = from; i < to; i++) {
                accumulator.add(i - from, expected[i] & 0xFF, actual[i] & 0xFF);
            }
            return accumulator.result();
        }

        /**
         * Measures two arrays of unsigned samples of a bit depth.
         *
         * @param expected the reference samples
         * @param actual the samples under test, at least as long as {@code expected}
         * @param bitDepth the bit depth, from 1 to 16
         * @return the statistics
         */
        public static Difference of(short[] expected, short[] actual, int bitDepth) {
            if (bitDepth < 1 || bitDepth > 16) {
                throw new IllegalArgumentException("bit depth " + bitDepth);
            }
            var peak = (1 << bitDepth) - 1;
            var accumulator = new Accumulator(peak, peak);
            for (var i = 0; i < expected.length; i++) {
                accumulator.add(i, expected[i] & 0xFFFF, actual[i] & 0xFFFF);
            }
            return accumulator.result();
        }

        /**
         * Measures two arrays of signed 32-bit samples; the peak is {@code 2^31 - 1} and full scale {@code 2^31}.
         *
         * @param expected the reference samples
         * @param actual the samples under test, at least as long as {@code expected}
         * @return the statistics
         */
        public static Difference of(int[] expected, int[] actual) {
            var accumulator = new Accumulator(Integer.MAX_VALUE, 2147483648.0);
            for (var i = 0; i < expected.length; i++) {
                accumulator.add(i, expected[i], actual[i]);
            }
            return accumulator.result();
        }

        /**
         * Measures two arrays of signed 16-bit PCM samples; the peak is 32767 and full scale 32768.
         *
         * @param expected the reference samples
         * @param actual the samples under test, at least as long as {@code expected}
         * @return the statistics
         */
        public static Difference ofPcm(short[] expected, short[] actual) {
            var accumulator = new Accumulator(32767, 32768);
            for (var i = 0; i < expected.length; i++) {
                accumulator.add(i, expected[i], actual[i]);
            }
            return accumulator.result();
        }

        /**
         * Measures two arrays of float PCM samples; the peak and full scale are 1.0.
         *
         * @param expected the reference samples
         * @param actual the samples under test, at least as long as {@code expected}
         * @return the statistics
         */
        public static Difference ofPcm(float[] expected, float[] actual) {
            var accumulator = new Accumulator(1, 1);
            for (var i = 0; i < expected.length; i++) {
                accumulator.add(i, expected[i], actual[i]);
            }
            return accumulator.result();
        }

        /**
         * Measures two pictures of the same type, size and layout over every sample of every plane (luma, chroma
         * and alpha, or the packed components); the peak is 255 for 8-bit pictures, {@code 2^bitDepth - 1} for
         * planar deep pictures, 65535 for packed 16-bit ones and 1.0 for float ones.
         *
         * @param expected the reference picture
         * @param actual the picture under test
         * @return the statistics
         * @throws IllegalArgumentException if the pictures differ in type or size
         */
        public static Difference of(Picture expected, Picture actual) {
            if (expected.width() != actual.width() || expected.height() != actual.height()
                    || expected.getClass() != actual.getClass()) {
                throw new IllegalArgumentException("pictures differ in type or size");
            }
            var width = expected.width();
            var height = expected.height();
            return switch (expected) {
                case YuvPicture e -> {
                    var a = (YuvPicture) actual;
                    var accumulator = new Accumulator(255, 255);
                    accumulator.plane(e.y(), e.yStride(), a.y(), a.yStride(), width, height);
                    if (e.chromaFormat().hasChroma()) {
                        accumulator.plane(e.u(), e.uvStride(), a.u(), a.uvStride(), e.chromaWidth(), e.chromaHeight());
                        accumulator.plane(e.v(), e.uvStride(), a.v(), a.uvStride(), e.chromaWidth(), e.chromaHeight());
                    }
                    if (e.hasAlpha()) {
                        accumulator.plane(e.alpha(), e.alphaStride(), a.alpha(), a.alphaStride(), width, height);
                    }
                    yield accumulator.result();
                }
                case RgbaPicture e -> {
                    var a = (RgbaPicture) actual;
                    var accumulator = new Accumulator(255, 255);
                    accumulator.plane(e.pixels(), e.stride(), a.pixels(), a.stride(), width * 4, height);
                    yield accumulator.result();
                }
                case Yuv16Picture e -> {
                    var a = (Yuv16Picture) actual;
                    var peak = (1 << e.bitDepth()) - 1;
                    var accumulator = new Accumulator(peak, peak);
                    accumulator.plane(e.y(), e.yStride(), a.y(), a.yStride(), width, height);
                    if (e.chromaFormat().hasChroma()) {
                        accumulator.plane(e.u(), e.uvStride(), a.u(), a.uvStride(), e.chromaWidth(), e.chromaHeight());
                        accumulator.plane(e.v(), e.uvStride(), a.v(), a.uvStride(), e.chromaWidth(), e.chromaHeight());
                    }
                    if (e.hasAlpha()) {
                        accumulator.plane(e.alpha(), e.alphaStride(), a.alpha(), a.alphaStride(), width, height);
                    }
                    yield accumulator.result();
                }
                case Rgba16Picture e -> {
                    var a = (Rgba16Picture) actual;
                    var accumulator = new Accumulator(65535, 65535);
                    accumulator.plane(e.pixels(), e.stride(), a.pixels(), a.stride(), width * 4, height);
                    yield accumulator.result();
                }
                case Channel8Picture e -> {
                    var a = (Channel8Picture) actual;
                    var accumulator = new Accumulator(255, 255);
                    accumulator.plane(e.pixels(), e.stride(), a.pixels(), a.stride(), width * e.channelCount(), height);
                    yield accumulator.result();
                }
                case Channel16Picture e -> {
                    var a = (Channel16Picture) actual;
                    var accumulator = new Accumulator(65535, 65535);
                    accumulator.plane(e.pixels(), e.stride(), a.pixels(), a.stride(), width * e.channelCount(), height);
                    yield accumulator.result();
                }
                case ChannelFloatPicture e -> {
                    var a = (ChannelFloatPicture) actual;
                    var accumulator = new Accumulator(1, 1);
                    var row = width * e.channelCount();
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < row; x++) {
                            accumulator.add((long) y * row + x, e.pixels()[y * e.stride() + x],
                                    a.pixels()[y * a.stride() + x]);
                        }
                    }
                    yield accumulator.result();
                }
            };
        }

        @Override
        public String toString() {
            var text = new StringBuilder(String.format(Locale.ROOT,
                    "%d of %d samples differ, max %s, mean %.6g, rms %.6g, psnr %.3f dB, snr %.3f dB",
                    differing, count, number(maxAbs), meanAbs(), rms(), psnr(), snr()));
            if (first >= 0) {
                text.append(", first at ").append(first).append(" (expected ").append(number(firstExpected))
                        .append(", actual ").append(number(firstActual)).append(')');
            }
            return text.toString();
        }

        private static String number(double value) {
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return Long.toString((long) value);
            }
            if (!Double.isFinite(value)) {
                return Double.toString(value);
            }
            return new java.math.BigDecimal(String.format(Locale.ROOT, "%.6g", value)).stripTrailingZeros()
                    .toPlainString();
        }
    }

    /** Collects the statistics of a {@link Difference} sample by sample. */
    final class Accumulator {
        private final double peak;
        private final double fullScale;
        private long count;
        private long differing;
        private double maxAbs;
        private double sumAbs;
        private double sumSquares;
        private double signalSquares;
        private long first = -1;
        private double firstExpected;
        private double firstActual;

        /**
         * Constructs an empty accumulator.
         *
         * @param peak the largest sample value of the sample type
         * @param fullScale the sample value of full scale
         */
        public Accumulator(double peak, double fullScale) {
            this.peak = peak;
            this.fullScale = fullScale;
        }

        /**
         * Adds one sample pair.
         *
         * @param index the sample index, reported for the first difference
         * @param expected the reference value
         * @param actual the value under test
         */
        public void add(long index, double expected, double actual) {
            count++;
            signalSquares += expected * expected;
            if (expected != actual) {
                var difference = Math.abs(expected - actual);
                if (Double.isNaN(difference)) {
                    difference = Double.POSITIVE_INFINITY;
                }
                if (first < 0) {
                    first = index;
                    firstExpected = expected;
                    firstActual = actual;
                }
                differing++;
                maxAbs = Math.max(maxAbs, difference);
                sumAbs += difference;
                sumSquares += difference * difference;
            }
        }

        private void plane(byte[] expected, int expectedStride, byte[] actual, int actualStride, int width,
                           int height) {
            var base = count;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    add(base + (long) y * width + x, expected[y * expectedStride + x] & 0xFF,
                            actual[y * actualStride + x] & 0xFF);
                }
            }
        }

        private void plane(short[] expected, int expectedStride, short[] actual, int actualStride, int width,
                           int height) {
            var base = count;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    add(base + (long) y * width + x, expected[y * expectedStride + x] & 0xFFFF,
                            actual[y * actualStride + x] & 0xFFFF);
                }
            }
        }

        /** {@return the statistics of the samples added so far} */
        public Difference result() {
            return new Difference(count, differing, maxAbs, sumAbs, sumSquares, signalSquares, peak, fullScale, first,
                    firstExpected, firstActual);
        }
    }

    /** Every sample equal. */
    record Exact() implements Tolerance {
        private static final Exact INSTANCE = new Exact();

        @Override
        public List<String> violations(Difference difference) {
            return difference.differing() == 0 ? List.of() : List.of(difference.differing() + " samples differ");
        }

        @Override
        public String describe() {
            return "exact";
        }
    }

    /**
     * Every sample within a bound.
     *
     * @param limit the largest absolute difference, inclusive
     */
    record MaxAbs(double limit) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            return difference.maxAbs() <= limit ? List.of() : List.of("max " + Difference.number(difference.maxAbs())
                    + " > " + Difference.number(limit));
        }

        @Override
        public String describe() {
            return limit == 1 ? "off by one" : "max abs " + Difference.number(limit);
        }
    }

    /**
     * The mean absolute difference within a bound.
     *
     * @param limit the largest mean absolute difference, inclusive
     */
    record MeanAbs(double limit) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            return difference.meanAbs() <= limit ? List.of()
                    : List.of(String.format(Locale.ROOT, "mean %.6g > %.6g", difference.meanAbs(), limit));
        }

        @Override
        public String describe() {
            return "mean abs " + Difference.number(limit);
        }
    }

    /**
     * The RMS difference within a bound.
     *
     * @param limit the largest RMS difference, inclusive
     */
    record Rms(double limit) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            return difference.rms() <= limit ? List.of()
                    : List.of(String.format(Locale.ROOT, "rms %.6g > %.6g", difference.rms(), limit));
        }

        @Override
        public String describe() {
            return "rms " + Difference.number(limit);
        }
    }

    /**
     * A PSNR floor.
     *
     * @param minimum the smallest PSNR in decibels, inclusive
     */
    record Psnr(double minimum) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            return difference.psnr() >= minimum ? List.of()
                    : List.of(String.format(Locale.ROOT, "psnr %.3f dB < %s dB", difference.psnr(),
                    Difference.number(minimum)));
        }

        @Override
        public String describe() {
            return "psnr " + Difference.number(minimum) + " dB";
        }
    }

    /**
     * An SNR floor.
     *
     * @param minimum the smallest SNR in decibels, inclusive
     */
    record Snr(double minimum) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            return difference.snr() >= minimum ? List.of()
                    : List.of(String.format(Locale.ROOT, "snr %.3f dB < %s dB", difference.snr(),
                    Difference.number(minimum)));
        }

        @Override
        public String describe() {
            return "snr " + Difference.number(minimum) + " dB";
        }
    }

    /**
     * A bound on the fraction of differing samples.
     *
     * @param fraction the largest fraction, inclusive
     */
    record Differing(double fraction) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            var actual = difference.count() == 0 ? 0 : (double) difference.differing() / difference.count();
            return actual <= fraction ? List.of()
                    : List.of(String.format(Locale.ROOT, "%.6g of the samples differ > %.6g", actual, fraction));
        }

        @Override
        public String describe() {
            return "differing " + Difference.number(fraction);
        }
    }

    /**
     * The ISO/IEC 11172-4 compliance limits.
     *
     * @param accuracy the accuracy class
     */
    record Iso11172(Iso11172Accuracy accuracy) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            var violations = new ArrayList<String>();
            var rms = difference.rms() / difference.fullScale();
            if (rms >= accuracy.rms()) {
                violations.add(String.format(Locale.ROOT, "rms %.6g of full scale >= %.6g", rms, accuracy.rms()));
            }
            var max = difference.maxAbs() / difference.fullScale();
            if (max > accuracy.maxAbs()) {
                violations.add(String.format(Locale.ROOT, "max %.6g of full scale > %.6g", max, accuracy.maxAbs()));
            }
            return violations;
        }

        @Override
        public String describe() {
            return "ISO/IEC 11172-4 " + accuracy.name().toLowerCase(Locale.ROOT) + " accuracy";
        }
    }

    /**
     * The {@code opus_compare} criterion.
     *
     * @param channels the channel count
     * @param rate the sampling rate of the decode under test
     */
    record OpusPerceptual(int channels, int rate) implements Tolerance {
        /** Validates the arguments. */
        public OpusPerceptual {
            if (channels < 1) {
                throw new IllegalArgumentException("channels " + channels);
            }
            if (rate != 8000 && rate != 12000 && rate != 16000 && rate != 24000 && rate != 48000) {
                throw new IllegalArgumentException("rate " + rate);
            }
        }

        @Override
        public List<String> violations(Difference difference) {
            throw new IllegalArgumentException("opus_compare compares signals, not measured differences");
        }

        @Override
        public String describe() {
            return "opus_compare at " + rate + " Hz";
        }

        @Override
        public void assertPcm(String what, short[] expected, short[] actual) {
            var results = OpusCompare.compareChannels(expected, actual, channels, rate);
            for (var index = 0; index < results.size(); index++) {
                var result = results.get(index);
                if (!result.passes()) {
                    Assertions.fail(String.format(Locale.ROOT, "%s%s: %s violated (quality %.1f %%, weighted error %f)%s",
                            what, channels > 2 ? " channel " + index : "", describe(), result.quality(),
                            result.error(), rate == 48000 ? "; " + Difference.ofPcm(expected, actual) : ""));
                }
            }
        }

        @Override
        public void assertPcm(String what, float[] expected, float[] actual) {
            assertPcm(what, OpusCompare.toPcm16(expected), OpusCompare.toPcm16(actual));
        }

        @Override
        public void assertWithin(String what, byte[] expected, byte[] actual) {
            throw new IllegalArgumentException("opus_compare applies to PCM only");
        }

        @Override
        public void assertWithin(String what, short[] expected, short[] actual, int bitDepth) {
            throw new IllegalArgumentException("opus_compare applies to PCM only");
        }

        @Override
        public void assertWithin(String what, int[] expected, int[] actual) {
            throw new IllegalArgumentException("opus_compare applies to 16-bit or float PCM only");
        }

        @Override
        public void assertWithin(String what, Picture expected, Picture actual) {
            throw new IllegalArgumentException("opus_compare applies to PCM only");
        }

        @Override
        public void assertFrames(String what, byte[] expected, byte[] actual, int frameSize) {
            throw new IllegalArgumentException("opus_compare applies to PCM only");
        }

        @Override
        public void assertFrames(String what, short[] expected, short[] actual, int frameSize, int bitDepth) {
            throw new IllegalArgumentException("opus_compare applies to PCM only");
        }
    }

    /**
     * Every one of several rules.
     *
     * @param rules the rules, at least two
     */
    record All(List<Tolerance> rules) implements Tolerance {
        @Override
        public List<String> violations(Difference difference) {
            var violations = new ArrayList<String>();
            for (var rule : rules) {
                violations.addAll(rule.violations(difference));
            }
            return violations;
        }

        @Override
        public String describe() {
            var parts = new ArrayList<String>();
            for (var rule : rules) {
                parts.add(rule.describe());
            }
            return String.join(" and ", parts);
        }

        @Override
        public void assertPcm(String what, short[] expected, short[] actual) {
            for (var rule : rules) {
                rule.assertPcm(what, expected, actual);
            }
        }

        @Override
        public void assertPcm(String what, float[] expected, float[] actual) {
            for (var rule : rules) {
                rule.assertPcm(what, expected, actual);
            }
        }
    }
}
