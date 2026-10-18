package com.github.auties00.smalt.track;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Holds the out-of-band information a container or encoder attaches to one {@link Packet}.
 *
 * <p>The well-known entries have typed accessors:
 * <ul>
 *   <li>{@link #discardStart()} and {@link #discardEnd()}: the amount of decoded output, in the
 *       owning track's time base, to drop from the start and from the end of this packet's
 *       output. They carry the per-packet trimming a container signals, such as the Matroska
 *       {@code DiscardPadding} or the end trimming of the last Ogg page, so that a reader can
 *       remove encoder padding exactly. Track-level priming stays in
 *       {@link AudioTrack#skipSamples()}, and codecs that signal priming in band (the Opus
 *       pre-skip) handle it themselves.</li>
 *   <li>{@link #newCodecConfig()}: a decoder configuration that replaces
 *       {@link Track#codecConfig()} from this packet on, in the same canonical form.</li>
 *   <li>{@link #hdr10Plus()}: dynamic HDR10+ metadata for this frame, as the ITU-T T.35 message
 *       ({@code itu_t_t35_country_code} 0xB5 and the SMPTE ST 2094-40 payload) a container
 *       carries next to the frame.</li>
 * </ul>
 * Any other payload goes into {@link #extra()} under a key of the form
 * {@code "<origin>:<name>"}, for example {@code "matroska:BlockAdditional:1"}.
 *
 * <p>Byte arrays are shared, not copied, so neither producers nor consumers may modify them
 * after the side data is built. Instances are immutable and thread-safe.
 */
public final class PacketSideData {
    /**
     * The side data of a packet that carries none.
     */
    public static final PacketSideData NONE = new PacketSideData(0, 0, null, null, Map.of());

    /**
     * The decoded output to drop from the start of the packet, in track time-base units.
     */
    private final long discardStart;

    /**
     * The decoded output to drop from the end of the packet, in track time-base units.
     */
    private final long discardEnd;

    /**
     * The replacement decoder configuration, or {@code null}.
     */
    private final byte[] newCodecConfig;

    /**
     * The HDR10+ ITU-T T.35 message, or {@code null}.
     */
    private final byte[] hdr10Plus;

    /**
     * The other payloads by key, unmodifiable.
     */
    private final Map<String, byte[]> extra;

    /**
     * Constructs side data from validated components.
     *
     * @param discardStart   the output to drop from the start
     * @param discardEnd     the output to drop from the end
     * @param newCodecConfig the replacement configuration, or {@code null}
     * @param hdr10Plus      the HDR10+ message, or {@code null}
     * @param extra          the other payloads, unmodifiable
     */
    private PacketSideData(long discardStart, long discardEnd, byte[] newCodecConfig, byte[] hdr10Plus,
                           Map<String, byte[]> extra) {
        this.discardStart = discardStart;
        this.discardEnd = discardEnd;
        this.newCodecConfig = newCodecConfig;
        this.hdr10Plus = hdr10Plus;
        this.extra = extra;
    }

    /**
     * Returns a builder of side data, initially empty.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with the entries of this side data.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.discardStart = discardStart;
        builder.discardEnd = discardEnd;
        builder.newCodecConfig = newCodecConfig;
        builder.hdr10Plus = hdr10Plus;
        builder.extra.putAll(extra);
        return builder;
    }

    /**
     * Returns the decoded output to drop from the start of the packet.
     *
     * @return the amount in the owning track's time base, non-negative
     */
    public long discardStart() {
        return discardStart;
    }

    /**
     * Returns the decoded output to drop from the end of the packet.
     *
     * @return the amount in the owning track's time base, non-negative
     */
    public long discardEnd() {
        return discardEnd;
    }

    /**
     * Returns the decoder configuration that replaces the track's from this packet on.
     *
     * @return the configuration, not a copy, or empty when it does not change
     */
    public Optional<byte[]> newCodecConfig() {
        return Optional.ofNullable(newCodecConfig);
    }

    /**
     * Returns the HDR10+ dynamic metadata of this packet's frame.
     *
     * @return the ITU-T T.35 message, not a copy, or empty when absent
     */
    public Optional<byte[]> hdr10Plus() {
        return Optional.ofNullable(hdr10Plus);
    }

    /**
     * Returns the payload stored under a key of {@link #extra()}.
     *
     * @param key the name the payload is stored under
     * @return the payload, not a copy, or empty when absent
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public Optional<byte[]> extra(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(extra.get(key));
    }

    /**
     * Returns every payload without a typed accessor, by key.
     *
     * @return the unmodifiable map, sorted by key
     */
    public Map<String, byte[]> extra() {
        return extra;
    }

    /**
     * Returns whether this side data carries nothing.
     *
     * @return whether every entry is absent or zero
     */
    public boolean isEmpty() {
        return discardStart == 0 && discardEnd == 0 && newCodecConfig == null && hdr10Plus == null && extra.isEmpty();
    }

    /**
     * Compares the entries of two side data, byte arrays by content.
     *
     * @param other the object to compare with
     * @return whether {@code other} is side data with equal entries
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PacketSideData that)) {
            return false;
        }
        if (discardStart != that.discardStart || discardEnd != that.discardEnd
                || !Arrays.equals(newCodecConfig, that.newCodecConfig) || !Arrays.equals(hdr10Plus, that.hdr10Plus)
                || !extra.keySet().equals(that.extra.keySet())) {
            return false;
        }
        for (var entry : extra.entrySet()) {
            if (!Arrays.equals(entry.getValue(), that.extra.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        var hash = Long.hashCode(discardStart) * 31 + Long.hashCode(discardEnd);
        hash = hash * 31 + Arrays.hashCode(newCodecConfig);
        hash = hash * 31 + Arrays.hashCode(hdr10Plus);
        for (var entry : extra.entrySet()) {
            hash += entry.getKey().hashCode() ^ Arrays.hashCode(entry.getValue());
        }
        return hash;
    }

    /**
     * Returns a description of the entries, with payload sizes instead of contents.
     *
     * @return the description
     */
    @Override
    public String toString() {
        var text = new StringBuilder("PacketSideData[discardStart=").append(discardStart)
                .append(", discardEnd=").append(discardEnd);
        if (newCodecConfig != null) {
            text.append(", newCodecConfig=").append(newCodecConfig.length).append(" bytes");
        }
        if (hdr10Plus != null) {
            text.append(", hdr10Plus=").append(hdr10Plus.length).append(" bytes");
        }
        for (var entry : extra.entrySet()) {
            text.append(", ").append(entry.getKey()).append('=').append(entry.getValue().length).append(" bytes");
        }
        return text.append(']').toString();
    }

    /**
     * Builds {@link PacketSideData} instances.
     *
     * <p>Instances are not thread-safe; confine a builder to one thread.
     */
    public static final class Builder {
        /**
         * The output to drop from the start.
         */
        private long discardStart;

        /**
         * The output to drop from the end.
         */
        private long discardEnd;

        /**
         * The replacement configuration, or {@code null}.
         */
        private byte[] newCodecConfig;

        /**
         * The HDR10+ message, or {@code null}.
         */
        private byte[] hdr10Plus;

        /**
         * The other payloads by key.
         */
        private final TreeMap<String, byte[]> extra = new TreeMap<>();

        /**
         * Constructs an empty builder.
         */
        private Builder() {
        }

        /**
         * Sets the decoded output to drop from the start and from the end of the packet.
         *
         * @param discardStart the amount to drop from the start, in track time-base units;
         *                     non-negative
         * @param discardEnd   the amount to drop from the end, in track time-base units;
         *                     non-negative
         * @return this builder
         * @throws IllegalArgumentException if an amount is negative
         */
        public Builder discard(long discardStart, long discardEnd) {
            if (discardStart < 0 || discardEnd < 0) {
                throw new IllegalArgumentException("negative discard " + discardStart + "/" + discardEnd);
            }
            this.discardStart = discardStart;
            this.discardEnd = discardEnd;
            return this;
        }

        /**
         * Sets the decoder configuration that replaces the track's from this packet on.
         *
         * @param newCodecConfig the configuration, or {@code null} for none
         * @return this builder
         */
        public Builder newCodecConfig(byte[] newCodecConfig) {
            this.newCodecConfig = newCodecConfig;
            return this;
        }

        /**
         * Sets the HDR10+ dynamic metadata of the packet's frame.
         *
         * @param hdr10Plus the ITU-T T.35 message, or {@code null} for none
         * @return this builder
         */
        public Builder hdr10Plus(byte[] hdr10Plus) {
            this.hdr10Plus = hdr10Plus;
            return this;
        }

        /**
         * Stores a payload without a typed accessor under a key.
         *
         * @param key   the key, of the form {@code "<origin>:<name>"}
         * @param value the payload, or {@code null} to remove the key
         * @return this builder
         * @throws NullPointerException if {@code key} is {@code null}
         */
        public Builder extra(String key, byte[] value) {
            Objects.requireNonNull(key, "key");
            if (value == null) {
                extra.remove(key);
            } else {
                extra.put(key, value);
            }
            return this;
        }

        /**
         * Builds the side data.
         *
         * @return {@link #NONE} when nothing was set, otherwise new side data
         */
        public PacketSideData build() {
            if (discardStart == 0 && discardEnd == 0 && newCodecConfig == null && hdr10Plus == null && extra.isEmpty()) {
                return NONE;
            }
            var entries = extra.isEmpty() ? Map.<String, byte[]>of() : Collections.unmodifiableMap(new TreeMap<>(extra));
            return new PacketSideData(discardStart, discardEnd, newCodecConfig, hdr10Plus, entries);
        }
    }
}
