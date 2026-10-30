package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of an {@link MxfMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class MxfMuxerOptions {
    /**
     * The largest KLV alignment grid accepted, in bytes.
     */
    public static final int MAX_KAG_SIZE = 1 << 20;

    /**
     * The default body partition duration, in microseconds.
     */
    public static final long DEFAULT_PARTITION_MICROS = 10_000_000;

    /**
     * The options of {@link #defaults()}.
     */
    private static final MxfMuxerOptions DEFAULTS = new MxfMuxerOptions(Pattern.OP1A, null,
            DEFAULT_PARTITION_MICROS, 512, 0, true, null, new Rational(25, 1));

    /**
     * The options of {@link #forOpAtom()}.
     */
    private static final MxfMuxerOptions OP_ATOM = new MxfMuxerOptions(Pattern.OP_ATOM, null,
            DEFAULT_PARTITION_MICROS, 512, 0, true, null, new Rational(25, 1));

    private final Pattern pattern;
    private final Wrapping wrapping;
    private final long partitionMicros;
    private final int kagSize;
    private final int avcIntraClass;
    private final boolean avcIntraParameterSets;
    private final byte[] materialPackageUmid;
    private final Rational audioEditRate;

    private MxfMuxerOptions(Pattern pattern, Wrapping wrapping, long partitionMicros, int kagSize, int avcIntraClass,
                            boolean avcIntraParameterSets, byte[] materialPackageUmid, Rational audioEditRate) {
        this.pattern = pattern;
        this.wrapping = wrapping;
        this.partitionMicros = partitionMicros;
        this.kagSize = kagSize;
        this.avcIntraClass = avcIntraClass;
        this.avcIntraParameterSets = avcIntraParameterSets;
        this.materialPackageUmid = materialPackageUmid;
        this.audioEditRate = audioEditRate;
    }

    /**
     * Returns the default options: OP1a, frame wrapping when a picture track is declared, body partitions of
     * {@value #DEFAULT_PARTITION_MICROS} microseconds, a KLV alignment grid of 512 bytes, AVC written as a plain AVC
     * byte stream, a random material package UMID, and an edit rate of 25 Hz for frame-wrapped sound without
     * pictures.
     *
     * @return the default options
     */
    public static MxfMuxerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the options of an OP-Atom file (SMPTE ST 390): {@link #defaults()} with the OP-Atom pattern, which
     * holds one track, clip wrapped unless the options say otherwise.
     *
     * @return the OP-Atom options
     */
    public static MxfMuxerOptions forOpAtom() {
        return OP_ATOM;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
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
     * {@return the operational pattern; {@link Pattern#OP1A} by default}
     */
    public Pattern pattern() {
        return pattern;
    }

    /**
     * {@return how the essence is wrapped, or empty for the automatic choice (the default): frame wrapping for an
     * OP1a file with a picture track or several sound tracks, clip wrapping for an OP-Atom file and an OP1a file of
     * one sound track}
     *
     * <p>Clip wrapping holds exactly one track, and ProRes is always frame wrapped (SMPTE RDD 44).
     */
    public Optional<Wrapping> wrapping() {
        return Optional.ofNullable(wrapping);
    }

    /**
     * {@return the duration of the essence of each body partition of a frame-wrapped file, in microseconds, at least
     * one edit unit long; 0 writes all the essence in one body partition; {@value #DEFAULT_PARTITION_MICROS} by
     * default}
     *
     * <p>Partitions let a reader that starts within the file, or recovers from damage, find the header metadata and
     * the position in the essence. Each body partition starts at the first key frame of every picture track after
     * the duration elapses.
     */
    public long partitionMicros() {
        return partitionMicros;
    }

    /**
     * {@return the KLV alignment grid, in bytes, from 1 to {@link #MAX_KAG_SIZE}; 512 by default}
     *
     * <p>Partition packs, header metadata, index tables and the essence of each partition start on the grid, which
     * KLV fill items pad to.
     */
    public int kagSize() {
        return kagSize;
    }

    /**
     * {@return the SMPTE RP 2027 AVC-Intra class (50, 100 or 200) H.264 tracks are written in, or 0 (the default)
     * for a plain AVC byte stream}
     *
     * <p>With a class, every access unit is laid out as RP 2027 requires (an access unit delimiter, the parameter sets
     * padded to 512 bytes, SEI messages padded to 18 blocks of 512 bytes for 1080 lines or 10 for 720, the coded
     * picture padded to the class's coded frame size), and the picture coding label names the class and raster. The
     * track must be one of the class's rasters (1440 or 1920 by 1080 interlaced at 25 or 29.97 Hz or progressive at
     * 25 or 29.97 Hz, 960 or 1280 by 720 at 50 or 59.94 Hz), coded with the class's profile, and every access unit
     * must fit the coded frame size.
     */
    public int avcIntraClass() {
        return avcIntraClass;
    }

    /**
     * {@return whether AVC-Intra access units carry their sequence and picture parameter sets; {@code true} by
     * default}
     *
     * <p>Without them the stream is headerless: a decoder takes the parameter sets of the class and raster the
     * picture coding label names. Applies only with an {@link #avcIntraClass()}.
     */
    public boolean avcIntraParameterSets() {
        return avcIntraParameterSets;
    }

    /**
     * {@return the basic UMID (32 bytes) of the material package, or empty (the default) for a random one}
     *
     * <p>A fixed UMID makes the output deterministic: every other identifier of the file is derived from it. The
     * OP-Atom files of one clip share their material package UMID.
     */
    public Optional<byte[]> materialPackageUmid() {
        return Optional.ofNullable(materialPackageUmid).map(byte[]::clone);
    }

    /**
     * {@return the edit rate of frame-wrapped sound in a file without picture tracks; 25 Hz by default}
     */
    public Rational audioEditRate() {
        return audioEditRate;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MxfMuxerOptions that && pattern == that.pattern && wrapping == that.wrapping
                && partitionMicros == that.partitionMicros && kagSize == that.kagSize
                && avcIntraClass == that.avcIntraClass && avcIntraParameterSets == that.avcIntraParameterSets
                && Arrays.equals(materialPackageUmid, that.materialPackageUmid)
                && audioEditRate.equals(that.audioEditRate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern, wrapping, partitionMicros, kagSize, avcIntraClass, avcIntraParameterSets,
                Arrays.hashCode(materialPackageUmid), audioEditRate);
    }

    @Override
    public String toString() {
        return "MxfMuxerOptions[pattern=" + pattern + ", wrapping=" + (wrapping == null ? "automatic" : wrapping)
                + ", partitionMicros=" + partitionMicros + ", kagSize=" + kagSize + ", avcIntraClass="
                + avcIntraClass + ", avcIntraParameterSets=" + avcIntraParameterSets + ", materialPackageUmid="
                + (materialPackageUmid == null ? "random" : HexFormat.of().formatHex(materialPackageUmid))
                + ", audioEditRate=" + audioEditRate + "]";
    }

    /**
     * Enumerates the operational patterns written (SMPTE ST 378 and ST 390).
     */
    public enum Pattern {
        /**
         * OP1a: one material package playing one file package of any number of tracks, interleaved, label
         * {@code 06 0E 2B 34 04 01 01 01 0D 01 02 01 01 01 09 00}.
         */
        OP1A,

        /**
         * OP-Atom (SMPTE ST 390): one file package of exactly one track, closed and complete header metadata, and a
         * complete index table in the footer partition, label {@code 06 0E 2B 34 04 01 01 02 0D 01 02 01 10 00 00 00}.
         */
        OP_ATOM
    }

    /**
     * Enumerates the ways essence is wrapped in the generic container (SMPTE ST 379-2).
     */
    public enum Wrapping {
        /**
         * One essence element per edit unit and track, the elements of every track for one edit unit forming a
         * content package.
         */
        FRAME,

        /**
         * One essence element holding every edit unit of the track.
         */
        CLIP
    }

    /**
     * Assembles {@link MxfMuxerOptions} setting by setting.
     *
     * <p>Setters store their value unchecked; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private Pattern pattern;
        private Wrapping wrapping;
        private long partitionMicros;
        private int kagSize;
        private int avcIntraClass;
        private boolean avcIntraParameterSets;
        private byte[] materialPackageUmid;
        private Rational audioEditRate;

        private Builder(MxfMuxerOptions options) {
            this.pattern = options.pattern;
            this.wrapping = options.wrapping;
            this.partitionMicros = options.partitionMicros;
            this.kagSize = options.kagSize;
            this.avcIntraClass = options.avcIntraClass;
            this.avcIntraParameterSets = options.avcIntraParameterSets;
            this.materialPackageUmid = options.materialPackageUmid;
            this.audioEditRate = options.audioEditRate;
        }

        /**
         * Sets the operational pattern; see {@link MxfMuxerOptions#pattern()}.
         *
         * @param pattern the value
         * @return this builder
         */
        public Builder pattern(Pattern pattern) {
            this.pattern = Objects.requireNonNull(pattern, "pattern");
            return this;
        }

        /**
         * Sets the wrapping; see {@link MxfMuxerOptions#wrapping()}.
         *
         * @param wrapping the value
         * @return this builder
         */
        public Builder wrapping(Wrapping wrapping) {
            this.wrapping = Objects.requireNonNull(wrapping, "wrapping");
            return this;
        }

        /**
         * Restores the automatic choice of wrapping; see {@link MxfMuxerOptions#wrapping()}.
         *
         * @return this builder
         */
        public Builder automaticWrapping() {
            this.wrapping = null;
            return this;
        }

        /**
         * Sets the duration of each body partition; see {@link MxfMuxerOptions#partitionMicros()}.
         *
         * @param micros the value
         * @return this builder
         */
        public Builder partitionMicros(long micros) {
            this.partitionMicros = micros;
            return this;
        }

        /**
         * Sets the KLV alignment grid; see {@link MxfMuxerOptions#kagSize()}.
         *
         * @param kagSize the value
         * @return this builder
         */
        public Builder kagSize(int kagSize) {
            this.kagSize = kagSize;
            return this;
        }

        /**
         * Sets the AVC-Intra class; see {@link MxfMuxerOptions#avcIntraClass()}.
         *
         * @param avcIntraClass the value
         * @return this builder
         */
        public Builder avcIntraClass(int avcIntraClass) {
            this.avcIntraClass = avcIntraClass;
            return this;
        }

        /**
         * Writes H.264 as a plain AVC byte stream rather than an AVC-Intra class; see
         * {@link MxfMuxerOptions#avcIntraClass()}.
         *
         * @return this builder
         */
        public Builder noAvcIntraClass() {
            this.avcIntraClass = 0;
            return this;
        }

        /**
         * Sets whether AVC-Intra access units carry their parameter sets; see
         * {@link MxfMuxerOptions#avcIntraParameterSets()}.
         *
         * @param avcIntraParameterSets the value
         * @return this builder
         */
        public Builder avcIntraParameterSets(boolean avcIntraParameterSets) {
            this.avcIntraParameterSets = avcIntraParameterSets;
            return this;
        }

        /**
         * Sets the material package UMID; see {@link MxfMuxerOptions#materialPackageUmid()}.
         *
         * @param umid the value, 32 bytes, copied
         * @return this builder
         */
        public Builder materialPackageUmid(byte[] umid) {
            this.materialPackageUmid = umid.clone();
            return this;
        }

        /**
         * Makes every file take a random material package UMID; see {@link MxfMuxerOptions#materialPackageUmid()}.
         *
         * @return this builder
         */
        public Builder randomMaterialPackageUmid() {
            this.materialPackageUmid = null;
            return this;
        }

        /**
         * Sets the edit rate of frame-wrapped sound without pictures; see {@link MxfMuxerOptions#audioEditRate()}.
         *
         * @param rate the value
         * @return this builder
         */
        public Builder audioEditRate(Rational rate) {
            this.audioEditRate = Objects.requireNonNull(rate, "rate");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the partition duration is negative, the KLV alignment grid is outside 1
         * to {@link #MAX_KAG_SIZE}, the AVC-Intra class is not 0, 50, 100 or 200, the UMID is not 32 bytes, or a term
         * of the sound edit rate does not fit the 32-bit rational MXF stores
         */
        public MxfMuxerOptions build() {
            if (partitionMicros < 0) {
                throw new IllegalArgumentException("negative partition duration " + partitionMicros);
            }
            if (kagSize < 1 || kagSize > MAX_KAG_SIZE) {
                throw new IllegalArgumentException("KLV alignment grid of " + kagSize + " bytes");
            }
            if (avcIntraClass != 0 && avcIntraClass != 50 && avcIntraClass != 100 && avcIntraClass != 200) {
                throw new IllegalArgumentException("AVC-Intra class " + avcIntraClass);
            }
            if (materialPackageUmid != null && materialPackageUmid.length != 32) {
                throw new IllegalArgumentException("a basic UMID is 32 bytes, not " + materialPackageUmid.length);
            }
            if (audioEditRate.numerator() > Integer.MAX_VALUE || audioEditRate.denominator() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("sound edit rate " + audioEditRate);
            }
            return new MxfMuxerOptions(pattern, wrapping, partitionMicros, kagSize, avcIntraClass,
                    avcIntraParameterSets, materialPackageUmid, audioEditRate);
        }
    }
}
