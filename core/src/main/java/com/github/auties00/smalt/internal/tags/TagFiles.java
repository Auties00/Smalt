package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Opens the {@link TagFile} that matches the container of a source.
 */
public final class TagFiles {
    /**
     * Opens the tag file of every format whose tags can be edited.
     */
    private static final Map<ContainerFormat, BiFunction<MediaSource, MediaLimits, TagFile>> OPENERS = openers();
    private TagFiles() {
        throw new AssertionError();
    }

    /**
     * Returns whether tags of a container format can be read and written.
     *
     * @param format the container format
     * @return {@code true} for MP3, ADTS, FLAC, Ogg, MP4, Matroska, WAV and AIFF
     */
    public static boolean isSupported(ContainerFormat format) {
        return OPENERS.containsKey(format);
    }

    /**
     * Detects the container of a source and parses its metadata structure.
     *
     * @param source the file to parse; not closed
     * @param limits the resource bounds
     * @return the tag file
     * @throws MediaException.Unsupported if the container is not recognized or holds no tags Smalt can edit
     * @throws MediaException.Malformed if the container structure is corrupt
     */
    public static TagFile open(MediaSource source, MediaLimits limits) {
        var format = Containers.detect(source).orElseThrow(() -> new MediaException.Unsupported("unknown",
                "the input is not a recognized audio or video container"));
        var opener = OPENERS.get(format);
        if (opener == null) {
            throw new MediaException.Unsupported(format.name().toLowerCase(Locale.ROOT),
                    "tags of " + format.name().toLowerCase(Locale.ROOT) + " files cannot be edited");
        }
        try {
            return opener.apply(source, limits);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt " + format.name().toLowerCase(Locale.ROOT) + " metadata", exception);
        }
    }

    /**
     * Builds the table of tag file constructors by container format.
     *
     * @return the table
     */
    private static Map<ContainerFormat, BiFunction<MediaSource, MediaLimits, TagFile>> openers() {
        var openers = new EnumMap<ContainerFormat, BiFunction<MediaSource, MediaLimits, TagFile>>(ContainerFormat.class);
        openers.put(ContainerFormat.MPEG_AUDIO, Id3StreamTagFile::new);
        openers.put(ContainerFormat.ADTS, Id3StreamTagFile::new);
        openers.put(ContainerFormat.FLAC, FlacTagFile::new);
        openers.put(ContainerFormat.OGG, OggTagFile::new);
        openers.put(ContainerFormat.MP4, Mp4TagFile::new);
        openers.put(ContainerFormat.MATROSKA, MatroskaTagFile::new);
        openers.put(ContainerFormat.WAV, RiffTagFile::new);
        openers.put(ContainerFormat.AIFF, RiffTagFile::new);
        return Collections.unmodifiableMap(openers);
    }
}
