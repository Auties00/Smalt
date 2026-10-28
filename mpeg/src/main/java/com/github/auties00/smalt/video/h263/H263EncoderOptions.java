package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link H263Encoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>Every optional mode is off by default, which gives the baseline syntax every H.263 decoder supports; turning on
 * any mode of the second version of ITU-T H.263 (Annexes D, I, J, K or T) makes the pictures carry the extended picture
 * type, as a picture size other than the five standard formats does. The picture size is not an option: it is given
 * to the {@link H263Encoder} constructor.
 */
public final class H263EncoderOptions {
    /**
     * The largest number of threads; larger requests are lowered to it.
     */
    private static final int MAX_THREADS = 64;

    private static final H263EncoderOptions DEFAULTS = new Builder().build();

    private static final H263EncoderOptions REALTIME = new Builder()
            .noPeriodicKeyframes()
            .groupOfBlocksHeaders(true)
            .build();

    private static final H263EncoderOptions CODING_EFFICIENCY = new Builder()
            .advancedIntraCoding(true)
            .deblockingFilter(true)
            .modifiedQuantization(true)
            .build();

    private final Rational frameRate;
    private final Rational timeBase;
    private final long bitrate;
    private final OptionalInt fixedQuantizer;
    private final int minQuantizer;
    private final int maxQuantizer;
    private final OptionalInt keyframeInterval;
    private final boolean unrestrictedVectors;
    private final boolean advancedPrediction;
    private final boolean advancedIntraCoding;
    private final boolean deblockingFilter;
    private final boolean modifiedQuantization;
    private final boolean groupOfBlocksHeaders;
    private final OptionalInt sliceSize;
    private final int searchRange;
    private final int threads;
    private final MediaLimits limits;

    private H263EncoderOptions(Builder builder) {
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.bitrate = builder.bitrate;
        this.fixedQuantizer = builder.fixedQuantizer;
        this.minQuantizer = builder.minQuantizer;
        this.maxQuantizer = builder.maxQuantizer;
        this.keyframeInterval = builder.keyframeInterval;
        this.unrestrictedVectors = builder.unrestrictedVectors;
        this.advancedPrediction = builder.advancedPrediction;
        this.advancedIntraCoding = builder.advancedIntraCoding;
        this.deblockingFilter = builder.deblockingFilter;
        this.modifiedQuantization = builder.modifiedQuantization;
        this.groupOfBlocksHeaders = builder.groupOfBlocksHeaders;
        this.sliceSize = builder.sliceSize;
        this.searchRange = builder.searchRange;
        this.threads = Math.min(builder.threads, MAX_THREADS);
        this.limits = builder.limits;
    }

    /**
     * Returns the baseline settings (profile 0 of ITU-T H.263 Annex X): 256 kbit/s at 30000/1001 frames per second,
     * an I-picture every 250 pictures, no optional mode and no group of blocks headers.
     *
     * @return the options
     */
    public static H263EncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the settings for interactive transmission: those of {@link #defaults()} without periodic I-pictures (a
     * receiver asks for one with {@link H263Encoder#requestKeyframe()}) and with a header on every group of blocks,
     * which a decoder resynchronizes at after a loss.
     *
     * @return the options
     */
    public static H263EncoderOptions forRealtime() {
        return REALTIME;
    }

    /**
     * Returns the settings of the coding efficiency profile (profile 1 of ITU-T H.263 Annex X): those of
     * {@link #defaults()} with Advanced INTRA Coding, the Deblocking Filter and Modified Quantization.
     *
     * @return the options
     */
    public static H263EncoderOptions forCodingEfficiency() {
        return CODING_EFFICIENCY;
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
     * {@return the nominal frame rate in frames per second, from 0.1 to 1000; 30000/1001 by default}
     *
     * <p>It gives the duration of a frame without one. With the extended picture type the stream signals a custom
     * picture clock frequency of which the frame rate is a whole fraction, when one exists; otherwise the temporal
     * references count periods of the standard clock of 30000/1001 Hz.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the unit of the timestamps of accepted frames and produced packets; 1/90000 second by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the target bitrate in bits per second, positive; 256000 by default}
     *
     * <p>The rate control holds it over the stream, within a buffer of about one second; it is ignored with a
     * {@linkplain #fixedQuantizer() fixed quantizer}.
     */
    public long bitrate() {
        return bitrate;
    }

    /**
     * {@return the quantizer every picture is coded with, from 1 to 31, or empty for the rate control of
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
     * {@return the most pictures from one I-picture to the next, positive, or empty when only the first picture and
     * the pictures after {@link H263Encoder#requestKeyframe()} are I-pictures; 250 by default}
     */
    public OptionalInt keyframeInterval() {
        return keyframeInterval;
    }

    /**
     * {@return whether the Unrestricted Motion Vector mode (Annex D) is on: vectors may point outside the picture
     * and span the larger range of Table D.1; {@code false} by default}
     */
    public boolean unrestrictedVectors() {
        return unrestrictedVectors;
    }

    /**
     * {@return whether the Advanced Prediction mode (Annex F) is on: four vectors per macroblock where they pay,
     * overlapped block motion compensation, and vectors that may point outside the picture; {@code false} by
     * default}
     */
    public boolean advancedPrediction() {
        return advancedPrediction;
    }

    /**
     * {@return whether the Advanced INTRA Coding mode (Annex I) is on: intra blocks predicted from their neighbors
     * in the frequency domain; {@code false} by default}
     */
    public boolean advancedIntraCoding() {
        return advancedIntraCoding;
    }

    /**
     * {@return whether the Deblocking Filter mode (Annex J) is on: an in-loop filter across the block edges, and
     * four vectors per macroblock where they pay; {@code false} by default}
     */
    public boolean deblockingFilter() {
        return deblockingFilter;
    }

    /**
     * {@return whether the Modified Quantization mode (Annex T) is on: a finer chrominance quantizer and coefficient
     * levels beyond 127 at small quantizers; {@code false} by default}
     */
    public boolean modifiedQuantization() {
        return modifiedQuantization;
    }

    /**
     * {@return whether every group of blocks after the first starts with a header, at which a decoder resumes after
     * a loss and prediction restarts; {@code false} by default}
     */
    public boolean groupOfBlocksHeaders() {
        return groupOfBlocksHeaders;
    }

    /**
     * {@return the macroblocks of each slice of the Slice Structured mode (Annex K), positive, or empty for groups
     * of blocks; empty by default}
     *
     * <p>Slices follow one another in raster order, each but the first starting with a header at which a decoder
     * resumes after a loss and prediction restarts; the last slice of a picture may be shorter.
     */
    public OptionalInt sliceSize() {
        return sliceSize;
    }

    /**
     * {@return the largest motion vector component searched, in whole samples, from 1 to 256; 16 by default}
     *
     * <p>The vectors of the baseline syntax and of the Advanced Prediction mode span at most 16 samples whatever the
     * range, those of the Unrestricted Motion Vector mode the larger range of Table D.1.
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
     * {@return the limits the picture size is checked against; {@link MediaLimits#DEFAULT} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof H263EncoderOptions that && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase) && bitrate == that.bitrate
                && fixedQuantizer.equals(that.fixedQuantizer) && minQuantizer == that.minQuantizer
                && maxQuantizer == that.maxQuantizer && keyframeInterval.equals(that.keyframeInterval)
                && unrestrictedVectors == that.unrestrictedVectors && advancedPrediction == that.advancedPrediction
                && advancedIntraCoding == that.advancedIntraCoding && deblockingFilter == that.deblockingFilter
                && modifiedQuantization == that.modifiedQuantization
                && groupOfBlocksHeaders == that.groupOfBlocksHeaders && sliceSize.equals(that.sliceSize)
                && searchRange == that.searchRange
                && threads == that.threads && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameRate, timeBase, bitrate, fixedQuantizer, minQuantizer, maxQuantizer,
                keyframeInterval, unrestrictedVectors, advancedPrediction, advancedIntraCoding, deblockingFilter,
                modifiedQuantization, groupOfBlocksHeaders, sliceSize, searchRange, threads, limits);
    }

    @Override
    public String toString() {
        return "H263EncoderOptions[frameRate=" + frameRate + ", timeBase=" + timeBase + ", bitrate=" + bitrate
                + ", fixedQuantizer=" + fixedQuantizer + ", quantizers=" + minQuantizer + ".." + maxQuantizer
                + ", keyframeInterval=" + keyframeInterval + ", unrestrictedVectors=" + unrestrictedVectors
                + ", advancedPrediction=" + advancedPrediction + ", advancedIntraCoding=" + advancedIntraCoding
                + ", deblockingFilter=" + deblockingFilter + ", modifiedQuantization=" + modifiedQuantization
                + ", groupOfBlocksHeaders=" + groupOfBlocksHeaders + ", sliceSize=" + sliceSize
                + ", searchRange=" + searchRange
                + ", threads=" + threads + ", limits=" + limits + "]";
    }

    /**
     * Builds {@link H263EncoderOptions}. The setters check only their own argument against null; the ranges are
     * checked by {@link #build()}.
     */
    public static final class Builder {
        private Rational frameRate = new Rational(30000, 1001);
        private Rational timeBase = new Rational(1, 90_000);
        private long bitrate = 256_000;
        private OptionalInt fixedQuantizer = OptionalInt.empty();
        private int minQuantizer = 2;
        private int maxQuantizer = 31;
        private OptionalInt keyframeInterval = OptionalInt.of(250);
        private boolean unrestrictedVectors;
        private boolean advancedPrediction;
        private boolean advancedIntraCoding;
        private boolean deblockingFilter;
        private boolean modifiedQuantization;
        private boolean groupOfBlocksHeaders;
        private OptionalInt sliceSize = OptionalInt.empty();
        private int searchRange = 16;
        private int threads;
        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(H263EncoderOptions from) {
            frameRate = from.frameRate;
            timeBase = from.timeBase;
            bitrate = from.bitrate;
            fixedQuantizer = from.fixedQuantizer;
            minQuantizer = from.minQuantizer;
            maxQuantizer = from.maxQuantizer;
            keyframeInterval = from.keyframeInterval;
            unrestrictedVectors = from.unrestrictedVectors;
            advancedPrediction = from.advancedPrediction;
            advancedIntraCoding = from.advancedIntraCoding;
            deblockingFilter = from.deblockingFilter;
            modifiedQuantization = from.modifiedQuantization;
            groupOfBlocksHeaders = from.groupOfBlocksHeaders;
            sliceSize = from.sliceSize;
            searchRange = from.searchRange;
            threads = from.threads;
            limits = from.limits;
        }

        /**
         * Sets the nominal frame rate; see {@link H263EncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link H263EncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the target bitrate; see {@link H263EncoderOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(long bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        /**
         * Sets a fixed quantizer in place of the rate control; see {@link H263EncoderOptions#fixedQuantizer()}.
         *
         * @param quantizer the value
         * @return this builder
         */
        public Builder fixedQuantizer(int quantizer) {
            this.fixedQuantizer = OptionalInt.of(quantizer);
            return this;
        }

        /**
         * Lets the rate control choose the quantizers; see {@link H263EncoderOptions#fixedQuantizer()}.
         *
         * @return this builder
         */
        public Builder automaticQuantizer() {
            this.fixedQuantizer = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the quantizer range of the rate control; see {@link H263EncoderOptions#minQuantizer()} and
         * {@link H263EncoderOptions#maxQuantizer()}.
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
         * Sets the key frame interval; see {@link H263EncoderOptions#keyframeInterval()}.
         *
         * @param frames the value
         * @return this builder
         */
        public Builder keyframeInterval(int frames) {
            this.keyframeInterval = OptionalInt.of(frames);
            return this;
        }

        /**
         * Codes no periodic I-pictures; see {@link H263EncoderOptions#keyframeInterval()}.
         *
         * @return this builder
         */
        public Builder noPeriodicKeyframes() {
            this.keyframeInterval = OptionalInt.empty();
            return this;
        }

        /**
         * Sets whether the Unrestricted Motion Vector mode is on; see
         * {@link H263EncoderOptions#unrestrictedVectors()}.
         *
         * @param unrestrictedVectors the value
         * @return this builder
         */
        public Builder unrestrictedVectors(boolean unrestrictedVectors) {
            this.unrestrictedVectors = unrestrictedVectors;
            return this;
        }

        /**
         * Sets whether the Advanced Prediction mode is on; see {@link H263EncoderOptions#advancedPrediction()}.
         *
         * @param advancedPrediction the value
         * @return this builder
         */
        public Builder advancedPrediction(boolean advancedPrediction) {
            this.advancedPrediction = advancedPrediction;
            return this;
        }

        /**
         * Sets whether the Advanced INTRA Coding mode is on; see {@link H263EncoderOptions#advancedIntraCoding()}.
         *
         * @param advancedIntraCoding the value
         * @return this builder
         */
        public Builder advancedIntraCoding(boolean advancedIntraCoding) {
            this.advancedIntraCoding = advancedIntraCoding;
            return this;
        }

        /**
         * Sets whether the Deblocking Filter mode is on; see {@link H263EncoderOptions#deblockingFilter()}.
         *
         * @param deblockingFilter the value
         * @return this builder
         */
        public Builder deblockingFilter(boolean deblockingFilter) {
            this.deblockingFilter = deblockingFilter;
            return this;
        }

        /**
         * Sets whether the Modified Quantization mode is on; see {@link H263EncoderOptions#modifiedQuantization()}.
         *
         * @param modifiedQuantization the value
         * @return this builder
         */
        public Builder modifiedQuantization(boolean modifiedQuantization) {
            this.modifiedQuantization = modifiedQuantization;
            return this;
        }

        /**
         * Sets whether every group of blocks after the first has a header; see
         * {@link H263EncoderOptions#groupOfBlocksHeaders()}.
         *
         * @param groupOfBlocksHeaders the value
         * @return this builder
         */
        public Builder groupOfBlocksHeaders(boolean groupOfBlocksHeaders) {
            this.groupOfBlocksHeaders = groupOfBlocksHeaders;
            return this;
        }

        /**
         * Codes the pictures in slices of the Slice Structured mode; see {@link H263EncoderOptions#sliceSize()}.
         *
         * @param macroblocks the value
         * @return this builder
         */
        public Builder sliceSize(int macroblocks) {
            this.sliceSize = OptionalInt.of(macroblocks);
            return this;
        }

        /**
         * Codes the pictures in groups of blocks; see {@link H263EncoderOptions#sliceSize()}.
         *
         * @return this builder
         */
        public Builder noSlices() {
            this.sliceSize = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the motion search range; see {@link H263EncoderOptions#searchRange()}.
         *
         * @param samples the value
         * @return this builder
         */
        public Builder searchRange(int samples) {
            this.searchRange = samples;
            return this;
        }

        /**
         * Sets the thread count; see {@link H263EncoderOptions#threads()}.
         *
         * @param threads the value
         * @return this builder
         */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /**
         * Sets the limits; see {@link H263EncoderOptions#limits()}.
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
         * not in [1, 31] or the largest not in [smallest, 31], the key frame interval is not positive, the slice size
         * is not positive or is combined with group of blocks headers, the search range is not in [1, 256] or the
         * thread count is negative
         */
        public H263EncoderOptions build() {
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
            if (sliceSize.isPresent()) {
                Checks.positive("slice size", sliceSize.getAsInt());
                if (groupOfBlocksHeaders) {
                    throw new IllegalArgumentException("slices replace the groups of blocks; they take no headers");
                }
            }
            Checks.inRange("search range", searchRange, 1, 256);
            Checks.nonNegative("thread count", threads);
            return new H263EncoderOptions(this);
        }
    }
}
