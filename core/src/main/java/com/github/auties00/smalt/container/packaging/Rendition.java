package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Describes one rendition of a presentation: an encode of the content, usually at one size and
 * bit rate among several, read from a media file.
 *
 * <p>The {@link Packager} takes the first video track and the first audio track of the file. The
 * name prefixes the files written for the rendition; without one the packager names renditions
 * {@code r0}, {@code r1} and so on, in the order they are given.
 *
 * <p>Instances are immutable and thread-safe; the source itself is read by the packager.
 */
public final class Rendition {
    /**
     * The characters a rendition name may hold, so that it is a safe file name prefix.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * The input file.
     */
    private final MediaSource source;

    /**
     * The name, or {@code null}.
     */
    private final String name;

    /**
     * Constructs a rendition.
     *
     * @param source the input file
     * @param name the name, or {@code null}
     * @throws IllegalArgumentException if the name holds characters other than letters, digits,
     * underscores and hyphens
     */
    private Rendition(MediaSource source, String name) {
        this.source = Objects.requireNonNull(source, "source");
        if (name != null && !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("rendition name \"" + name
                    + "\" is not 1 to 64 letters, digits, underscores or hyphens");
        }
        this.name = name;
    }

    /**
     * Returns a rendition read from a media file, named by its position.
     *
     * @param source the input, in any container
     * {@link com.github.auties00.smalt.container.Containers} opens; not closed by the packager
     * @return the rendition
     */
    public static Rendition of(MediaSource source) {
        return new Rendition(source, null);
    }

    /**
     * Returns a named rendition read from a media file.
     *
     * @param name the name, 1 to 64 letters, digits, underscores or hyphens
     * @param source the input; not closed by the packager
     * @return the rendition
     * @throws IllegalArgumentException if the name holds other characters
     */
    public static Rendition of(String name, MediaSource source) {
        return new Rendition(source, Objects.requireNonNull(name, "name"));
    }

    /**
     * Returns the input file.
     *
     * @return the source
     */
    public MediaSource source() {
        return source;
    }

    /**
     * Returns the name.
     *
     * @return the name, or empty when the packager names the rendition by its position
     */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Rendition that && source.equals(that.source) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, name);
    }

    @Override
    public String toString() {
        return "Rendition[name=" + name + ", source=" + source + "]";
    }
}
