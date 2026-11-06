package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Holds the settings of an {@link RtpPacketizer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class RtpPacketizerOptions {
    /**
     * The default largest packet size in bytes, RTP header included.
     */
    public static final int DEFAULT_MAX_PACKET_SIZE = 1200;

    /**
     * The default payload type, the first dynamic one.
     */
    public static final int DEFAULT_PAYLOAD_TYPE = 96;

    /**
     * The smallest accepted packet size, which leaves room for the largest payload header of every format.
     */
    public static final int MIN_PACKET_SIZE = 64;

    private static final RtpPacketizerOptions DEFAULTS = new Builder().build();

    private final int payloadType;
    private final long ssrc;
    private final int initialSequenceNumber;
    private final long initialTimestamp;
    private final int maxPacketSize;
    private final Rational timeBase;
    private final int framesPerPacket;
    private final int pictureIdBits;
    private final boolean parameterSetsBeforeKeyFrames;
    private final int interleavingLength;
    private final int dependencyDescriptorId;

    private RtpPacketizerOptions(Builder builder) {
        this.payloadType = builder.payloadType;
        this.ssrc = builder.ssrc;
        this.initialSequenceNumber = builder.initialSequenceNumber;
        this.initialTimestamp = builder.initialTimestamp;
        this.maxPacketSize = builder.maxPacketSize;
        this.timeBase = builder.timeBase;
        this.framesPerPacket = builder.framesPerPacket;
        this.pictureIdBits = builder.pictureIdBits;
        this.parameterSetsBeforeKeyFrames = builder.parameterSetsBeforeKeyFrames;
        this.interleavingLength = builder.interleavingLength;
        this.dependencyDescriptorId = builder.dependencyDescriptorId;
    }

    /**
     * Returns options with payload type {@value #DEFAULT_PAYLOAD_TYPE}, random synchronization source, sequence
     * number and timestamp, {@value #DEFAULT_MAX_PACKET_SIZE}-byte packets and input timestamps in RTP clock units.
     *
     * @return the default options
     */
    public static RtpPacketizerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.payloadType = payloadType;
        builder.ssrc = ssrc;
        builder.initialSequenceNumber = initialSequenceNumber;
        builder.initialTimestamp = initialTimestamp;
        builder.maxPacketSize = maxPacketSize;
        builder.timeBase = timeBase;
        builder.framesPerPacket = framesPerPacket;
        builder.pictureIdBits = pictureIdBits;
        builder.parameterSetsBeforeKeyFrames = parameterSetsBeforeKeyFrames;
        builder.interleavingLength = interleavingLength;
        builder.dependencyDescriptorId = dependencyDescriptorId;
        return builder;
    }

    /**
     * {@return the payload type of every packet, from 0 to 127; {@value #DEFAULT_PAYLOAD_TYPE} by default}
     */
    public int payloadType() {
        return payloadType;
    }

    /**
     * {@return the synchronization source identifier, from 0 to {@code 2^32 - 1}, or empty for a random one chosen
     * when the packetizer is created (the default)}
     */
    public OptionalLong ssrc() {
        return ssrc < 0 ? OptionalLong.empty() : OptionalLong.of(ssrc);
    }

    /**
     * {@return the sequence number of the first packet, from 0 to 65535, or empty for a random one chosen when the
     * packetizer is created (the default)}
     *
     * <p>The VP8 and VP9 picture identifiers start at this number modulo their range.
     */
    public OptionalInt initialSequenceNumber() {
        return initialSequenceNumber < 0 ? OptionalInt.empty() : OptionalInt.of(initialSequenceNumber);
    }

    /**
     * {@return the RTP timestamp of input timestamp zero, from 0 to {@code 2^32 - 1}, or empty for a random one
     * chosen when the packetizer is created (the default)}
     *
     * <p>Each packet's timestamp is this value plus its input timestamp converted to the clock rate, modulo
     * {@code 2^32}.
     */
    public OptionalLong initialTimestamp() {
        return initialTimestamp < 0 ? OptionalLong.empty() : OptionalLong.of(initialTimestamp);
    }

    /**
     * {@return the largest packet size in bytes, RTP header included, from {@value #MIN_PACKET_SIZE} to 65535;
     * {@value #DEFAULT_MAX_PACKET_SIZE} by default}
     *
     * <p>Units larger than the room it leaves are fragmented where the payload format allows it; where it does not
     * (H.264 packetization mode 0, an Opus packet, an AMR frame-block, an {@code AAC-lbr} frame), packetizing such a
     * unit fails.
     */
    public int maxPacketSize() {
        return maxPacketSize;
    }

    /**
     * {@return the time base of the input packets' timestamps, or empty when they are already in units of the
     * payload format's clock (the default)}
     */
    public Optional<Rational> timeBase() {
        return Optional.ofNullable(timeBase);
    }

    /**
     * {@return the number of audio frames each packet aggregates, from 1 to 64; 1 by default}
     *
     * <p>AMR and AMR-WB aggregate this many frame-blocks; {@code mpeg4-generic} aggregates up to this many access
     * units, fewer when the next one would not fit the packet. Other formats ignore it: an Opus or video packet
     * always carries one input packet.
     */
    public int framesPerPacket() {
        return framesPerPacket;
    }

    /**
     * {@return the length in bits of the VP8 and VP9 picture identifier: 0 (no identifier, and for VP9 no
     * scalability structure), 7 or 15; 15 by default}
     */
    public int pictureIdBits() {
        return pictureIdBits;
    }

    /**
     * {@return whether an H.264 access unit holding an IDR picture but no parameter set is preceded by the
     * {@code sprop-parameter-sets} of the format; {@code true} by default}
     *
     * <p>A receiver that missed the session description or joins late can then decode from any IDR picture.
     */
    public boolean parameterSetsBeforeKeyFrames() {
        return parameterSetsBeforeKeyFrames;
    }

    /**
     * {@return the AMR interleaving length in frame-blocks, the {@code ILL} field plus one, from 1 to 16; 1 by
     * default}
     *
     * <p>It applies only when the format signals {@code interleaving}, and the interleaving group it makes,
     * {@link #framesPerPacket()} times this length, must not exceed the signaled value.
     */
    public int interleavingLength() {
        return interleavingLength;
    }

    /**
     * {@return the RFC 8285 extension identifier under which AV1 packets carry an {@link Av1DependencyDescriptor},
     * from 1 to 255, or empty for no descriptor (the default)}
     *
     * <p>The identifier is the one the session maps to {@value Av1DependencyDescriptor#URI}. Every packet's
     * extension holds the descriptor, in the one-byte form when it fits and the two-byte form otherwise, and
     * {@link #maxPacketSize()} covers it.
     */
    public OptionalInt dependencyDescriptorId() {
        return dependencyDescriptorId == 0 ? OptionalInt.empty() : OptionalInt.of(dependencyDescriptorId);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof RtpPacketizerOptions that && payloadType == that.payloadType && ssrc == that.ssrc
                && initialSequenceNumber == that.initialSequenceNumber && initialTimestamp == that.initialTimestamp
                && maxPacketSize == that.maxPacketSize && Objects.equals(timeBase, that.timeBase)
                && framesPerPacket == that.framesPerPacket && pictureIdBits == that.pictureIdBits
                && parameterSetsBeforeKeyFrames == that.parameterSetsBeforeKeyFrames
                && interleavingLength == that.interleavingLength
                && dependencyDescriptorId == that.dependencyDescriptorId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(payloadType, ssrc, initialSequenceNumber, initialTimestamp, maxPacketSize, timeBase,
                framesPerPacket, pictureIdBits, parameterSetsBeforeKeyFrames, interleavingLength,
                dependencyDescriptorId);
    }

    @Override
    public String toString() {
        return "RtpPacketizerOptions[payloadType=" + payloadType + ", ssrc=" + (ssrc < 0 ? "random" : ssrc)
                + ", initialSequenceNumber=" + (initialSequenceNumber < 0 ? "random" : initialSequenceNumber)
                + ", initialTimestamp=" + (initialTimestamp < 0 ? "random" : initialTimestamp)
                + ", maxPacketSize=" + maxPacketSize + ", timeBase=" + (timeBase == null ? "clock" : timeBase)
                + ", framesPerPacket=" + framesPerPacket + ", pictureIdBits=" + pictureIdBits
                + ", parameterSetsBeforeKeyFrames=" + parameterSetsBeforeKeyFrames
                + ", interleavingLength=" + interleavingLength + ", dependencyDescriptorId="
                + (dependencyDescriptorId == 0 ? "none" : dependencyDescriptorId) + "]";
    }

    /**
     * Assembles {@link RtpPacketizerOptions} setting by setting.
     *
     * <p>Each setter validates its value. Instances are not thread-safe.
     */
    public static final class Builder {
        private int payloadType = DEFAULT_PAYLOAD_TYPE;
        private long ssrc = -1;
        private int initialSequenceNumber = -1;
        private long initialTimestamp = -1;
        private int maxPacketSize = DEFAULT_MAX_PACKET_SIZE;
        private Rational timeBase;
        private int framesPerPacket = 1;
        private int pictureIdBits = 15;
        private boolean parameterSetsBeforeKeyFrames = true;
        private int interleavingLength = 1;
        private int dependencyDescriptorId;

        private Builder() {
        }

        /**
         * Sets the payload type; see {@link RtpPacketizerOptions#payloadType()}.
         *
         * @param payloadType the value
         * @return this builder
         * @throws IllegalArgumentException if {@code payloadType} is not from 0 to 127
         */
        public Builder payloadType(int payloadType) {
            if (payloadType < 0 || payloadType > 127) {
                throw new IllegalArgumentException("payload type " + payloadType + " is not from 0 to 127");
            }
            this.payloadType = payloadType;
            return this;
        }

        /**
         * Sets the synchronization source identifier; see {@link RtpPacketizerOptions#ssrc()}.
         *
         * @param ssrc the value
         * @return this builder
         * @throws IllegalArgumentException if {@code ssrc} is not from 0 to {@code 2^32 - 1}
         */
        public Builder ssrc(long ssrc) {
            if (ssrc < 0 || ssrc > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("ssrc " + ssrc + " is not an unsigned 32-bit value");
            }
            this.ssrc = ssrc;
            return this;
        }

        /**
         * Chooses a random synchronization source identifier; see {@link RtpPacketizerOptions#ssrc()}.
         *
         * @return this builder
         */
        public Builder randomSsrc() {
            this.ssrc = -1;
            return this;
        }

        /**
         * Sets the first sequence number; see {@link RtpPacketizerOptions#initialSequenceNumber()}.
         *
         * @param sequenceNumber the value
         * @return this builder
         * @throws IllegalArgumentException if {@code sequenceNumber} is not from 0 to 65535
         */
        public Builder initialSequenceNumber(int sequenceNumber) {
            if (sequenceNumber < 0 || sequenceNumber > 0xFFFF) {
                throw new IllegalArgumentException("sequence number " + sequenceNumber + " is not from 0 to 65535");
            }
            this.initialSequenceNumber = sequenceNumber;
            return this;
        }

        /**
         * Chooses a random first sequence number; see {@link RtpPacketizerOptions#initialSequenceNumber()}.
         *
         * @return this builder
         */
        public Builder randomSequenceNumber() {
            this.initialSequenceNumber = -1;
            return this;
        }

        /**
         * Sets the RTP timestamp of input timestamp zero; see {@link RtpPacketizerOptions#initialTimestamp()}.
         *
         * @param timestamp the value
         * @return this builder
         * @throws IllegalArgumentException if {@code timestamp} is not from 0 to {@code 2^32 - 1}
         */
        public Builder initialTimestamp(long timestamp) {
            if (timestamp < 0 || timestamp > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("timestamp " + timestamp + " is not an unsigned 32-bit value");
            }
            this.initialTimestamp = timestamp;
            return this;
        }

        /**
         * Chooses a random RTP timestamp for input timestamp zero; see
         * {@link RtpPacketizerOptions#initialTimestamp()}.
         *
         * @return this builder
         */
        public Builder randomTimestamp() {
            this.initialTimestamp = -1;
            return this;
        }

        /**
         * Sets the largest packet size; see {@link RtpPacketizerOptions#maxPacketSize()}.
         *
         * @param bytes the value
         * @return this builder
         * @throws IllegalArgumentException if {@code bytes} is not from
         * {@value RtpPacketizerOptions#MIN_PACKET_SIZE} to 65535
         */
        public Builder maxPacketSize(int bytes) {
            if (bytes < MIN_PACKET_SIZE || bytes > 0xFFFF) {
                throw new IllegalArgumentException("packet size " + bytes + " is not from " + MIN_PACKET_SIZE
                        + " to 65535");
            }
            this.maxPacketSize = bytes;
            return this;
        }

        /**
         * Sets the time base of the input timestamps; see {@link RtpPacketizerOptions#timeBase()}.
         *
         * @param timeBase the value, with a positive numerator and denominator
         * @return this builder
         * @throws IllegalArgumentException if a term of {@code timeBase} is not positive
         */
        public Builder timeBase(Rational timeBase) {
            if (timeBase.numerator() <= 0 || timeBase.denominator() <= 0) {
                throw new IllegalArgumentException("time base " + timeBase + " is not positive");
            }
            this.timeBase = timeBase;
            return this;
        }

        /**
         * Takes the input timestamps as RTP clock units; see {@link RtpPacketizerOptions#timeBase()}.
         *
         * @return this builder
         */
        public Builder clockTimeBase() {
            this.timeBase = null;
            return this;
        }

        /**
         * Sets the number of audio frames per packet; see {@link RtpPacketizerOptions#framesPerPacket()}.
         *
         * @param frames the value
         * @return this builder
         * @throws IllegalArgumentException if {@code frames} is not from 1 to 64
         */
        public Builder framesPerPacket(int frames) {
            if (frames < 1 || frames > 64) {
                throw new IllegalArgumentException("frames per packet " + frames + " is not from 1 to 64");
            }
            this.framesPerPacket = frames;
            return this;
        }

        /**
         * Sets the picture identifier length; see {@link RtpPacketizerOptions#pictureIdBits()}.
         *
         * @param bits the value
         * @return this builder
         * @throws IllegalArgumentException if {@code bits} is not 0, 7 or 15
         */
        public Builder pictureIdBits(int bits) {
            if (bits != 0 && bits != 7 && bits != 15) {
                throw new IllegalArgumentException("picture identifier length " + bits + " is not 0, 7 or 15");
            }
            this.pictureIdBits = bits;
            return this;
        }

        /**
         * Sets whether H.264 IDR access units are preceded by the format's parameter sets; see
         * {@link RtpPacketizerOptions#parameterSetsBeforeKeyFrames()}.
         *
         * @param enabled the value
         * @return this builder
         */
        public Builder parameterSetsBeforeKeyFrames(boolean enabled) {
            this.parameterSetsBeforeKeyFrames = enabled;
            return this;
        }

        /**
         * Sets the AMR interleaving length; see {@link RtpPacketizerOptions#interleavingLength()}.
         *
         * @param frameBlocks the value
         * @return this builder
         * @throws IllegalArgumentException if {@code frameBlocks} is not from 1 to 16
         */
        public Builder interleavingLength(int frameBlocks) {
            if (frameBlocks < 1 || frameBlocks > 16) {
                throw new IllegalArgumentException("interleaving length " + frameBlocks + " is not from 1 to 16");
            }
            this.interleavingLength = frameBlocks;
            return this;
        }

        /**
         * Sets the extension identifier of the AV1 dependency descriptor; see
         * {@link RtpPacketizerOptions#dependencyDescriptorId()}.
         *
         * @param id the value
         * @return this builder
         * @throws IllegalArgumentException if {@code id} is not from 1 to 255
         */
        public Builder dependencyDescriptorId(int id) {
            if (id < 1 || id > 255) {
                throw new IllegalArgumentException("extension identifier " + id + " is not from 1 to 255");
            }
            this.dependencyDescriptorId = id;
            return this;
        }

        /**
         * Sends AV1 packets without a dependency descriptor; see
         * {@link RtpPacketizerOptions#dependencyDescriptorId()}.
         *
         * @return this builder
         */
        public Builder noDependencyDescriptor() {
            this.dependencyDescriptorId = 0;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public RtpPacketizerOptions build() {
            return new RtpPacketizerOptions(this);
        }
    }
}
