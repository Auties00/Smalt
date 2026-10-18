package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of an {@link OggMuxer}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class OggMuxerOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final OggMuxerOptions DEFAULTS = new OggMuxerOptions("Smalt", Map.of(), 255, 1_000_000, null);

    private final String vendor;
    private final Map<String, String> comments;
    private final int maxPacketsPerPage;
    private final long maxPageDurationMicros;

    /**
     * The serial number of the first logical stream, or {@code null} to choose one at random.
     */
    private final Integer serial;

    private OggMuxerOptions(String vendor, Map<String, String> comments, int maxPacketsPerPage,
                            long maxPageDurationMicros, Integer serial) {
        this.vendor = vendor;
        this.comments = comments;
        this.maxPacketsPerPage = maxPacketsPerPage;
        this.maxPageDurationMicros = maxPageDurationMicros;
        this.serial = serial;
    }

    /**
     * Returns the default options: vendor {@code "Smalt"}, no comments, up to 255 packets or one
     * second of audio per page (the limits libopusenc uses by default), and a random serial
     * number.
     *
     * @return the default options
     */
    public static OggMuxerOptions defaults() {
        return DEFAULTS;
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
     * {@return the vendor string of the {@code OpusTags} header, identifying the encoder;
     * {@code "Smalt"} by default}
     */
    public String vendor() {
        return vendor;
    }

    /**
     * {@return the user comments of the {@code OpusTags} header, unmodifiable, in write order; empty by
     * default}
     *
     * <p>Comment names are non-empty and use only printable ASCII other than {@code '='}; values are
     * written as UTF-8.
     */
    public Map<String, String> comments() {
        return comments;
    }

    /**
     * {@return the largest number of packets completed on one page, from 1 to 255; 255 by default}
     *
     * <p>A page is completed when it holds this many packets or {@link #maxPageDurationMicros()} of
     * audio.
     */
    public int maxPacketsPerPage() {
        return maxPacketsPerPage;
    }

    /**
     * {@return the audio duration after which a page is flushed, in microseconds; positive; one second
     * by default}
     */
    public long maxPageDurationMicros() {
        return maxPageDurationMicros;
    }

    /**
     * {@return the serial number of the first logical stream, or empty to choose one at random; empty by
     * default}
     *
     * <p>Further logical streams use consecutive numbers.
     */
    public OptionalInt serial() {
        return serial == null ? OptionalInt.empty() : OptionalInt.of(serial);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OggMuxerOptions that
                && vendor.equals(that.vendor)
                && comments.equals(that.comments)
                && maxPacketsPerPage == that.maxPacketsPerPage
                && maxPageDurationMicros == that.maxPageDurationMicros
                && Objects.equals(serial, that.serial);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vendor, comments, maxPacketsPerPage, maxPageDurationMicros, serial);
    }

    @Override
    public String toString() {
        return "OggMuxerOptions[vendor=" + vendor + ", comments=" + comments + ", maxPacketsPerPage="
                + maxPacketsPerPage + ", maxPageDurationMicros=" + maxPageDurationMicros + ", serial=" + serial + "]";
    }

    /**
     * Assembles {@link OggMuxerOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store their value unchecked; {@link #build()} checks the
     * comments and the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private String vendor;
        private Map<String, String> comments;
        private int maxPacketsPerPage;
        private long maxPageDurationMicros;
        private Integer serial;

        private Builder(OggMuxerOptions options) {
            this.vendor = options.vendor;
            this.comments = options.comments;
            this.maxPacketsPerPage = options.maxPacketsPerPage;
            this.maxPageDurationMicros = options.maxPageDurationMicros;
            this.serial = options.serial;
        }

        /**
         * Sets the vendor string; see {@link OggMuxerOptions#vendor()}.
         *
         * @param vendor the value
         * @return this builder
         */
        public Builder vendor(String vendor) {
            this.vendor = Objects.requireNonNull(vendor, "vendor");
            return this;
        }

        /**
         * Sets the user comments; see {@link OggMuxerOptions#comments()}.
         *
         * <p>The comments are written in the map's iteration order, so a caller that needs a stable
         * order passes a {@link LinkedHashMap} or a sorted map. {@link #build()} copies them.
         *
         * @param comments the value
         * @return this builder
         */
        public Builder comments(Map<String, String> comments) {
            this.comments = Objects.requireNonNull(comments, "comments");
            return this;
        }

        /**
         * Sets the packet limit of a page; see {@link OggMuxerOptions#maxPacketsPerPage()}.
         *
         * @param maxPacketsPerPage the value
         * @return this builder
         */
        public Builder maxPacketsPerPage(int maxPacketsPerPage) {
            this.maxPacketsPerPage = maxPacketsPerPage;
            return this;
        }

        /**
         * Sets the duration limit of a page; see {@link OggMuxerOptions#maxPageDurationMicros()}.
         *
         * @param maxPageDurationMicros the value
         * @return this builder
         */
        public Builder maxPageDurationMicros(long maxPageDurationMicros) {
            this.maxPageDurationMicros = maxPageDurationMicros;
            return this;
        }

        /**
         * Sets a fixed serial number; see {@link OggMuxerOptions#serial()}.
         *
         * @param serial the value
         * @return this builder
         */
        public Builder serial(int serial) {
            this.serial = serial;
            return this;
        }

        /**
         * Makes the serial number random; see {@link OggMuxerOptions#serial()}.
         *
         * @return this builder
         */
        public Builder randomSerial() {
            this.serial = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws NullPointerException if a comment name or value is {@code null}
         * @throws IllegalArgumentException if a comment name is empty or holds a character other than
         * printable ASCII without {@code '='}, the packet limit is outside 1 to 255, or the duration
         * limit is not positive
         */
        public OggMuxerOptions build() {
            return new OggMuxerOptions(vendor, freeze(comments),
                    Checks.inRange("packets per page", maxPacketsPerPage, 1, 255),
                    Checks.positive("page duration", maxPageDurationMicros), serial);
        }

        /**
         * Validates comments and freezes them in their iteration order.
         *
         * @param comments the comments set on the builder
         * @return an unmodifiable copy
         * @throws NullPointerException     if an entry is {@code null}
         * @throws IllegalArgumentException if a comment name is invalid
         */
        private static Map<String, String> freeze(Map<String, String> comments) {
            var copy = new LinkedHashMap<String, String>();
            for (var entry : comments.entrySet()) {
                var name = Objects.requireNonNull(entry.getKey(), "comment name");
                var value = Objects.requireNonNull(entry.getValue(), "comment value");
                Checks.notEmpty("comment name", name);
                for (var i = 0; i < name.length(); i++) {
                    var c = name.charAt(i);
                    if (c < 0x20 || c > 0x7D || c == '=') {
                        throw new IllegalArgumentException("comment name " + name + " holds an invalid character");
                    }
                }
                copy.put(name, value);
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
