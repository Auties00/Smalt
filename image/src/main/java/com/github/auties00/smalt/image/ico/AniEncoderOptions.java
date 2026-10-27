package com.github.auties00.smalt.image.ico;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of an {@link AniEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class AniEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final AniEncoderOptions DEFAULTS = new Builder().build();

    private final IcoEncoderOptions frameOptions;

    private final String title;

    private final String artist;

    private AniEncoderOptions(Builder builder) {
        this.frameOptions = builder.frameOptions;
        this.title = builder.title;
        this.artist = builder.artist;
    }

    /**
     * Returns options writing each frame as a one-entry icon of the frame's own size, without a title
     * or an author.
     *
     * @return the default options
     */
    public static AniEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
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
     * {@return the settings each frame's icon or cursor file is encoded with;
     * {@link IcoEncoderOptions#defaults()} by default}
     *
     * <p>Their entry sizes, entry encoding and hotspot apply to every frame; with a hotspot, every frame
     * is a cursor.
     */
    public IcoEncoderOptions frameOptions() {
        return frameOptions;
    }

    /**
     * {@return the title stored in the {@code INAM} entry of the {@code INFO} list, or empty when none is;
     * empty by default}
     *
     * <p>It is stored in the Windows-1252 character set.
     */
    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    /**
     * {@return the author stored in the {@code IART} entry of the {@code INFO} list, or empty when none
     * is; empty by default}
     *
     * <p>It is stored in the Windows-1252 character set.
     */
    public Optional<String> artist() {
        return Optional.ofNullable(artist);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AniEncoderOptions that && frameOptions.equals(that.frameOptions)
                && Objects.equals(title, that.title) && Objects.equals(artist, that.artist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameOptions, title, artist);
    }

    @Override
    public String toString() {
        return "AniEncoderOptions[frameOptions=" + frameOptions + (title != null ? ", title=" + title : "")
                + (artist != null ? ", artist=" + artist : "") + "]";
    }

    /**
     * A builder of {@link AniEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the text. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private IcoEncoderOptions frameOptions = IcoEncoderOptions.defaults();

        private String title;

        private String artist;

        private Builder() {
        }

        private Builder(AniEncoderOptions options) {
            this.frameOptions = options.frameOptions;
            this.title = options.title;
            this.artist = options.artist;
        }

        /**
         * Sets the settings of each frame's icon or cursor file; see {@link AniEncoderOptions#frameOptions()}.
         *
         * @param frameOptions the value
         * @return this builder
         */
        public Builder frameOptions(IcoEncoderOptions frameOptions) {
            this.frameOptions = Objects.requireNonNull(frameOptions, "frameOptions");
            return this;
        }

        /**
         * Sets the title; see {@link AniEncoderOptions#title()}.
         *
         * @param title the value
         * @return this builder
         */
        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        /**
         * Removes the title; see {@link AniEncoderOptions#title()}.
         *
         * @return this builder
         */
        public Builder noTitle() {
            this.title = null;
            return this;
        }

        /**
         * Sets the author; see {@link AniEncoderOptions#artist()}.
         *
         * @param artist the value
         * @return this builder
         */
        public Builder artist(String artist) {
            this.artist = Objects.requireNonNull(artist, "artist");
            return this;
        }

        /**
         * Removes the author; see {@link AniEncoderOptions#artist()}.
         *
         * @return this builder
         */
        public Builder noArtist() {
            this.artist = null;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the title or the author holds a character Windows-1252 cannot
         * encode or a NUL character
         */
        public AniEncoderOptions build() {
            check("title", title);
            check("author", artist);
            return new AniEncoderOptions(this);
        }

        /**
         * Checks that a text is storable.
         *
         * @param what the name of the text in the failure message
         * @param text the text, or {@code null}
         * @throws IllegalArgumentException if the text is not storable
         */
        private static void check(String what, String text) {
            if (text != null && (text.indexOf('\0') >= 0 || !AniFile.TEXT.newEncoder().canEncode(text))) {
                throw new IllegalArgumentException("the " + what + " is not storable in Windows-1252: " + text);
            }
        }
    }
}
