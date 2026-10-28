package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link Mpeg4Encoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The {@linkplain #profile() profile} decides which tools may be used; {@link Builder#build()} rejects the tools
 * the Simple profile lacks. The picture size is not an option: it is given to the {@link Mpeg4Encoder} constructor.
 */
public final class Mpeg4EncoderOptions {
    /**
     * The largest number of threads; larger requests are lowered to it.
     */
    private static final int MAX_THREADS = 64;

    /**
     * The most B-VOPs between two references.
     */
    private static final int MAX_B_FRAMES = 4;

    /**
     * The largest search range in whole samples.
     */
    private static final int MAX_SEARCH_RANGE = 256;

    private static final Mpeg4EncoderOptions DEFAULTS = new Builder().build();

    private static final Mpeg4EncoderOptions REALTIME = new Builder()
            .noPeriodicKeyframes()
            .videoPacketSize(1200)
            .build();

    private static final Mpeg4EncoderOptions FILE = new Builder()
            .profile(Mpeg4Profile.ADVANCED_SIMPLE)
            .bFrames(2)
            .build();

    private final Mpeg4Profile profile;
    private final Rational frameRate;
    private final Rational timeBase;
    private final long bitrate;
    private final OptionalInt fixedQuantizer;
    private final int minQuantizer;
    private final int maxQuantizer;
    private final OptionalInt keyframeInterval;
    private final int bFrames;
    private final boolean quarterSample;
    private final boolean mpegQuantization;
    private final int[] intraMatrix;
    private final int[] interMatrix;
    private final boolean fourMotionVectors;
    private final boolean acPrediction;
    private final OptionalInt videoPacketSize;
    private final boolean dataPartitioning;
    private final boolean reversibleVlc;
    private final int searchRange;
    private final int threads;
    private final Optional<ColorInfo> color;
    private final MediaLimits limits;

    private Mpeg4EncoderOptions(Builder builder) {
        this.profile = builder.profile;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.bitrate = builder.bitrate;
        this.fixedQuantizer = builder.fixedQuantizer;
        this.minQuantizer = builder.minQuantizer;
        this.maxQuantizer = builder.maxQuantizer;
        this.keyframeInterval = builder.keyframeInterval;
        this.bFrames = builder.bFrames;
        this.quarterSample = builder.quarterSample;
        this.mpegQuantization = builder.mpegQuantization;
        this.intraMatrix = builder.intraMatrix;
        this.interMatrix = builder.interMatrix;
        this.fourMotionVectors = builder.fourMotionVectors;
        this.acPrediction = builder.acPrediction;
        this.videoPacketSize = builder.videoPacketSize;
        this.dataPartitioning = builder.dataPartitioning;
        this.reversibleVlc = builder.reversibleVlc;
        this.searchRange = builder.searchRange;
        this.threads = Math.min(builder.threads, MAX_THREADS);
        this.color = builder.color;
        this.limits = builder.limits;
    }

    /**
     * Returns the general-purpose settings: the Simple profile at 1 Mbit/s and 25 frames per second, an I-VOP every
     * 250 pictures, four vectors per macroblock where they pay, AC prediction, and no video packets.
     *
     * @return the options
     */
    public static Mpeg4EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings for interactive transmission: those of {@link #defaults()} without periodic I-VOPs (a
     * receiver asks for one with {@link Mpeg4Encoder#requestKeyframe()}) and with video packets of about 1200 bytes,
     * which a decoder resynchronizes at after a loss.
     *
     * @return the options
     */
    public static Mpeg4EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the settings for files: those of {@link #defaults()} in the Advanced Simple profile with two B-VOPs
     * between references, which code most scenes in fewer bits at the cost of a delay of three pictures.
     *
     * @return the options
     */
    public static Mpeg4EncoderOptions forFile() {
        return FILE;
    }

    /**
     * Returns a builder of options initialized with the settings of {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the profile the stream conforms to; {@link Mpeg4Profile#SIMPLE} by default}
     *
     * <p>The stream signals the lowest level of the profile whose picture size, macroblock rate and bitrate hold
     * the stream's, or the highest level of the profile when none does.
     */
    public Mpeg4Profile profile() {
        return profile;
    }

    /**
     * {@return the nominal frame rate in frames per second, from 0.1 to 1000; 25 by default}
     *
     * <p>It gives the duration of a frame without one and of the first VOP times, and the time resolution of the
     * stream when the {@linkplain #timeBase() time base} is finer than 1/65535 second.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the unit of the timestamps of accepted frames and produced packets; 1/90000 second by default}
     *
     * <p>A time base of 1/{@code n} second with {@code n} up to 65535 becomes the stream's
     * {@code vop_time_increment_resolution}, so the VOP times carry the timestamps exactly; with another time base
     * the resolution is the numerator of the frame rate, or 65535 when that is larger, and the VOP times are the
     * timestamps rounded to it.
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the target bitrate in bits per second, positive; 1000000 by default}
     *
     * <p>The rate control holds it over the stream, within a buffer of about one second; it is ignored with a
     * {@linkplain #fixedQuantizer() fixed quantizer}.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the quantizer every VOP is coded with, from 1 to 31, or empty for the rate control of
     * {@link #bitrate()}; empty by default}
     */
    public OptionalInt fixedQuantizer() {
        return fixedQuantizer;
    }

    /**
     * {@return the smallest quantizer the rate control chooses, from 1 to 31; 2 by default}
     */
    public int minQuantizer() {
        return minQuantizer;
    }

    /**
     * {@return the largest quantizer the rate control chooses, from {@link #minQuantizer()} to 31; 31 by default}
     */
    public int maxQuantizer() {
        return maxQuantizer;
    }

    /**
     * {@return the most pictures from one I-VOP to the next, positive, or empty when only the first picture and the
     * pictures after {@link Mpeg4Encoder#requestKeyframe()} are I-VOPs; 250 by default}
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return the most B-VOPs between two references, from 0 to 4, at most 0 in the Simple profile; 0 by default}
     *
     * <p>A B-VOP is predicted from the references before and after it in presentation order, so with B-VOPs the
     * encoder holds that many pictures back and packets come out in decoding order.
     */
    public int bFrames() {
        return bFrames;
    }

    /**
     * {@return whether motion vectors are in quarter samples rather than half samples, Advanced Simple profile only;
     * {@code false} by default}
     */
    public boolean quarterSample() {
        return quarterSample;
    }

    /**
     * {@return whether blocks are quantized with the MPEG method and its matrices rather than the H.263 method,
     * Advanced Simple profile only; {@code false} by default}
     */
    public boolean mpegQuantization() {
        return mpegQuantization;
    }

    /**
     * {@return a copy of the intra quantization matrix the stream downloads, 64 values from 1 to 255 in raster
     * order, or empty for the default matrix of ISO/IEC 14496-2 6.3.3; empty by default}
     *
     * <p>It applies with {@link #mpegQuantization()} only; the first value, that of the DC coefficient, is not
     * used.
     */
    public Optional<int[]> intraMatrix() {
        return Optional.ofNullable(intraMatrix).map(int[]::clone);
    }

    /**
     * {@return a copy of the non-intra quantization matrix the stream downloads, 64 values from 1 to 255 in raster
     * order, or empty for the default matrix; empty by default}
     *
     * <p>It applies with {@link #mpegQuantization()} only.
     */
    public Optional<int[]> interMatrix() {
        return Optional.ofNullable(interMatrix).map(int[]::clone);
    }

    /**
     * {@return whether a macroblock may have one vector per 8x8 luminance block where that predicts it better;
     * {@code true} by default}
     */
    public boolean fourMotionVectors() {
        return fourMotionVectors;
    }

    /**
     * {@return whether intra macroblocks may predict the first row or column of their AC coefficients from a
     * neighbor where that saves bits; {@code true} by default}
     */
    public boolean acPrediction() {
        return acPrediction;
    }

    /**
     * {@return the size in bytes after which a video packet ends and the next starts with a resynchronization
     * marker, from 32 to 65536, or empty for one packet per VOP; empty by default}
     *
     * <p>A decoder that loses part of a VOP resumes at the next video packet. Prediction does not cross packets, so
     * smaller packets cost more bits.
     */
    public OptionalInt videoPacketSize() {
        return videoPacketSize;
    }

    /**
     * {@return whether the macroblock data of each video packet of the I- and P-VOPs is partitioned, the macroblock
     * headers and motion vectors ahead of the texture, so that a decoder that loses the texture of a packet keeps
     * its motion; {@code false} by default}
     *
     * <p>B-VOPs are coded in the combined syntax, as ISO/IEC 14496-2 defines.
     */
    public boolean dataPartitioning() {
        return dataPartitioning;
    }

    /**
     * {@return whether the texture of data partitioned VOPs is coded with the reversible codes of Table B-23 of
     * ISO/IEC 14496-2, which a decoder can read backward from the end of a damaged packet, needing
     * {@link #dataPartitioning()}; {@code false} by default}
     */
    public boolean reversibleVlc() {
        return reversibleVlc;
    }

    /**
     * {@return the largest motion vector component searched, in whole samples, from 1 to 256; 16 by default}
     *
     * <p>A larger range follows faster motion and costs more search time per macroblock.
     */
    public int searchRange() {
        return searchRange;
    }

    /**
     * {@return the most threads of the motion analysis, at least 0; 0, every thread of the library's pool, by
     * default}
     *
     * <p>With 1 the calling thread does all the work; the stream does not depend on the thread count.
     */
    public int threads() {
        return threads;
    }

    /**
     * {@return the color description the stream signals and RGB input is converted with, or empty for limited-range
     * BT.601 up to 1024x576 and limited-range BT.709 above; empty by default}
     */
    public Optional<ColorInfo> color() {
        return color;
    }

    /**
     * {@return the limits the picture size is checked against; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Mpeg4EncoderOptions that && profile == that.profile
                && frameRate.equals(that.frameRate) && timeBase.equals(that.timeBase) && bitrate == that.bitrate
                && fixedQuantizer.equals(that.fixedQuantizer) && minQuantizer == that.minQuantizer
                && maxQuantizer == that.maxQuantizer && keyframeInterval.equals(that.keyframeInterval)
                && bFrames == that.bFrames && quarterSample == that.quarterSample
                && mpegQuantization == that.mpegQuantization && Arrays.equals(intraMatrix, that.intraMatrix)
                && Arrays.equals(interMatrix, that.interMatrix) && fourMotionVectors == that.fourMotionVectors
                && acPrediction == that.acPrediction && videoPacketSize.equals(that.videoPacketSize)
                && dataPartitioning == that.dataPartitioning && reversibleVlc == that.reversibleVlc
                && searchRange == that.searchRange && threads == that.threads && color.equals(that.color)
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, frameRate, timeBase, bitrate, fixedQuantizer, minQuantizer, maxQuantizer,
                keyframeInterval, bFrames, quarterSample, mpegQuantization, Arrays.hashCode(intraMatrix),
                Arrays.hashCode(interMatrix), fourMotionVectors, acPrediction, videoPacketSize, dataPartitioning,
                reversibleVlc, searchRange, threads,
                color, limits);
    }

    @Override
    public String toString() {
        return "Mpeg4EncoderOptions[profile=" + profile + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", bitrate=" + bitrate + ", fixedQuantizer=" + fixedQuantizer + ", quantizers=" + minQuantizer
                + ".." + maxQuantizer + ", keyframeInterval=" + keyframeInterval + ", bFrames=" + bFrames
                + ", quarterSample=" + quarterSample + ", mpegQuantization=" + mpegQuantization
                + ", intraMatrix=" + (intraMatrix != null) + ", interMatrix=" + (interMatrix != null)
                + ", fourMotionVectors=" + fourMotionVectors + ", acPrediction=" + acPrediction
                + ", videoPacketSize=" + videoPacketSize + ", dataPartitioning=" + dataPartitioning
                + ", reversibleVlc=" + reversibleVlc + ", searchRange=" + searchRange + ", threads=" + threads
                + ", color=" + color + ", limits=" + limits + "]";
    }

    /**
     * Builds {@link Mpeg4EncoderOptions}. The setters check only their own argument against null; the ranges and
     * the combinations are checked by {@link #build()}.
     */
    public static final class Builder {
        private Mpeg4Profile profile = Mpeg4Profile.SIMPLE;
        private Rational frameRate = new Rational(25, 1);
        private Rational timeBase = new Rational(1, 90_000);
        private long bitrate = 1_000_000;
        private OptionalInt fixedQuantizer = OptionalInt.empty();
        private int minQuantizer = 2;
        private int maxQuantizer = 31;
        private OptionalInt keyframeInterval = OptionalInt.of(250);
        private int bFrames;
        private boolean quarterSample;
        private boolean mpegQuantization;
        private int[] intraMatrix;
        private int[] interMatrix;
        private boolean fourMotionVectors = true;
        private boolean acPrediction = true;
        private OptionalInt videoPacketSize = OptionalInt.empty();
        private boolean dataPartitioning;
        private boolean reversibleVlc;
        private int searchRange = 16;
        private int threads;
        private Optional<ColorInfo> color = Optional.empty();
        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(Mpeg4EncoderOptions from) {
            profile = from.profile;
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            bitrate = from.bitrate;
            fixedQuantizer = from.fixedQuantizer;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            keyframeInterval = from.keyframeInterval;
            bFrames = from.bFrames;
            quarterSample = from.quarterSample;
            mpegQuantization = from.mpegQuantization;
            intraMatrix = from.intraMatrix;
            interMatrix = from.interMatrix;
            fourMotionVectors = from.fourMotionVectors;
            acPrediction = from.acPrediction;
            videoPacketSize = from.videoPacketSize;
            dataPartitioning = from.dataPartitioning;
            reversibleVlc = from.reversibleVlc;
            searchRange = from.searchRange;
            threads = from.threads;
            color = from.color;
            limits = from.limits;
        }

        /**
         * Sets the profile; see {@link Mpeg4EncoderOptions#profile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder profile(Mpeg4Profile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link Mpeg4EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link Mpeg4EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link Mpeg4EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets a fixed quantizer in place of the rate control; see {@link Mpeg4EncoderOptions#fixedQuantizer()}.
         *
         * @param quantizer the value
         * @return this builder
         */
        public Builder fixedQuantizer(int quantizer) {
            this.fixedQuantizer = OptionalInt.of(quantizer);
            return this;
        }

        /**
         * Lets the rate control choose the quantizers; see {@link Mpeg4EncoderOptions#fixedQuantizer()}.
         *
         * @return this builder
         */
        public Builder automaticQuantizer() {
            this.fixedQuantizer = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the quantizer range of the rate control; see {@link Mpeg4EncoderOptions#minQuantizer()} and
         * {@link Mpeg4EncoderOptions#maxQuantizer()}.
         *
         * @param min the smallest quantizer
         * @param max the largest quantizer
         * @return this builder
         */
        public Builder quantizers(int min, int max) {
            this.minQuantizer = min;
            this.maxQuantizer = max;
            return this;
        }

        /**
         * Sets the key frame interval; see {@link Mpeg4EncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Codes no periodic I-VOPs; see {@link Mpeg4EncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the most B-VOPs between references; see {@link Mpeg4EncoderOptions#bFrames()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder bFrames(int frames) {
            this.bFrames = frames;
            return this;
        }

        /**
         * Sets whether vectors are in quarter samples; see {@link Mpeg4EncoderOptions#quarterSample()}.
         *
         * @param quarterSample the value
         * @return this builder
         */
        public Builder quarterSample(boolean quarterSample) {
            this.quarterSample = quarterSample;
            return this;
        }

        /**
         * Sets whether the MPEG quantization method is used; see {@link Mpeg4EncoderOptions#mpegQuantization()}.
         *
         * @param mpegQuantization the value
         * @return this builder
         */
        public Builder mpegQuantization(boolean mpegQuantization) {
            this.mpegQuantization = mpegQuantization;
            return this;
        }

        /**
         * Sets the intra quantization matrix the stream downloads; see {@link Mpeg4EncoderOptions#intraMatrix()}.
         *
         * @param matrix the value, copied
         * @return this builder
         */
        public Builder intraMatrix(int[] matrix) {
            this.intraMatrix = Objects.requireNonNull(matrix, "matrix").clone();
            return this;
        }

        /**
         * Sets the non-intra quantization matrix the stream downloads; see
         * {@link Mpeg4EncoderOptions#interMatrix()}.
         *
         * @param matrix the value, copied
         * @return this builder
         */
        public Builder interMatrix(int[] matrix) {
            this.interMatrix = Objects.requireNonNull(matrix, "matrix").clone();
            return this;
        }

        /**
         * Uses the default matrices of ISO/IEC 14496-2 6.3.3; see {@link Mpeg4EncoderOptions#intraMatrix()}.
         *
         * @return this builder
         */
        public Builder defaultMatrices() {
            this.intraMatrix = null;
            this.interMatrix = null;
            return this;
        }

        /**
         * Sets whether macroblocks may have four vectors; see {@link Mpeg4EncoderOptions#fourMotionVectors()}.
         *
         * @param fourMotionVectors the value
         * @return this builder
         */
        public Builder fourMotionVectors(boolean fourMotionVectors) {
            this.fourMotionVectors = fourMotionVectors;
            return this;
        }

        /**
         * Sets whether intra macroblocks may use AC prediction; see {@link Mpeg4EncoderOptions#acPrediction()}.
         *
         * @param acPrediction the value
         * @return this builder
         */
        public Builder acPrediction(boolean acPrediction) {
            this.acPrediction = acPrediction;
            return this;
        }

        /**
         * Sets the video packet size; see {@link Mpeg4EncoderOptions#videoPacketSize()}.
         *
         * @param bytes the value
         * @return this builder
         */
        public Builder videoPacketSize(int bytes) {
            this.videoPacketSize = OptionalInt.of(bytes);
            return this;
        }

        /**
         * Codes each VOP as one video packet; see {@link Mpeg4EncoderOptions#videoPacketSize()}.
         *
         * @return this builder
         */
        public Builder noVideoPackets() {
            this.videoPacketSize = OptionalInt.empty();
            return this;
        }

        /**
         * Sets whether the macroblock data is partitioned; see {@link Mpeg4EncoderOptions#dataPartitioning()}.
         *
         * @param dataPartitioning the value
         * @return this builder
         */
        public Builder dataPartitioning(boolean dataPartitioning) {
            this.dataPartitioning = dataPartitioning;
            return this;
        }

        /**
         * Sets whether partitioned texture uses the reversible codes; see {@link Mpeg4EncoderOptions#reversibleVlc()}.
         *
         * @param reversibleVlc the value
         * @return this builder
         */
        public Builder reversibleVlc(boolean reversibleVlc) {
            this.reversibleVlc = reversibleVlc;
            return this;
        }

        /**
         * Sets the motion search range; see {@link Mpeg4EncoderOptions#searchRange()}.
         *
         * @param samples the value
         * @return this builder
         */
        public Builder searchRange(int samples) {
            this.searchRange = samples;
            return this;
        }

        /**
         * Sets the thread count; see {@link Mpeg4EncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the color description; see {@link Mpeg4EncoderOptions#color()}.
         *
         * @param color the value
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = Optional.of(color);
            return this;
        }

        /**
         * Chooses the color description from the picture size; see {@link Mpeg4EncoderOptions#color()}.
         *
         * @return this builder
         */
        public Builder automaticColor() {
            this.color = Optional.empty();
            return this;
        }

        /**
         * Sets the limits; see {@link Mpeg4EncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the frame rate is not in [0.1, 1000], a time base term is not
         * positive, the bitrate is not positive, the fixed quantizer is not in [1, 31], the smallest quantizer is
         * not in [1, 31] or the largest not in [smallest, 31], the key frame interval is not positive, the B-VOPs
         * are not in [0, 4], a matrix does not hold 64 values from 1 to 255, the video packet size is not in [32,
         * 65536], the search range is not in [1, 256], the thread count is negative, reversible codes are asked
         * for without data partitioning, or the Simple profile is combined with B-VOPs, quarter samples or MPEG
         * quantization
         */
        public Mpeg4EncoderOptions build() {
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            Checks.positive("bitrate", bitrate);
            if (fixedQuantizer.isPresent()) {
                Checks.inRange("fixed quantizer", fixedQuantizer.getAsInt(), 1, 31);
            }
            Checks.inRange("smallest quantizer", minQuantizer, 1, 31);
            Checks.inRange("largest quantizer", maxQuantizer, minQuantizer, 31);
            if (keyframeInterval.isPresent()) {
                Checks.positive("key frame interval", keyframeInterval.getAsInt());
            }
            Checks.inRange("B-VOPs", bFrames, 0, MAX_B_FRAMES);
            checkMatrix("intra matrix", intraMatrix);
            checkMatrix("non-intra matrix", interMatrix);
            if (videoPacketSize.isPresent()) {
                Checks.inRange("video packet size", videoPacketSize.getAsInt(), 32, 65536);
            }
            Checks.inRange("search range", searchRange, 1, MAX_SEARCH_RANGE);
            Checks.nonNegative("thread count", threads);
            if (reversibleVlc && !dataPartitioning) {
                throw new IllegalArgumentException("reversible codes need data partitioning");
            }
            if (profile == Mpeg4Profile.SIMPLE && (bFrames > 0 || quarterSample || mpegQuantization)) {
                throw new IllegalArgumentException("B-VOPs, quarter samples and MPEG quantization need the Advanced "
                        + "Simple profile");
            }
            return new Mpeg4EncoderOptions(this);
        }

        /**
         * Checks a quantization matrix.
         *
         * @param name the matrix name, for the message
         * @param matrix the matrix, or {@code null}
         * @throws IllegalArgumentException if the matrix does not hold 64 values from 1 to 255
         */
        private static void checkMatrix(String name, int[] matrix) {
            if (matrix == null) {
                return;
            }
            if (matrix.length != 64) {
                throw new IllegalArgumentException(name + " has " + matrix.length + " values, not 64");
            }
            for (var value : matrix) {
                Checks.inRange(name + " value", value, 1, 255);
            }
        }
    }
}
