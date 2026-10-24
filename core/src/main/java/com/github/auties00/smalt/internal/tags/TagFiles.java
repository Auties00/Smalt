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
     * @return {@code true} for MP3, ADTS, raw AC-3 and E-AC-3, AMR, FLAC, Ogg, MP4, Matroska, WAV, AIFF and AVI
     */
    public static boolean isSupported(ContainerFormat format) {
        return OPENERS.containsKey(format);
    }

    /**
     * Returns the key a RIFF {@code INFO} identifier is read under, as
     * {@link com.github.auties00.smalt.metadata.TagEditor TagEditor} reads it.
     *
     * @param id the four-character identifier
     * @return the identifier, or {@code "INFO:"} followed by it for an {@code INFO} identifier that names a standard
     * tag {@code INFO} has no identifier for ({@code ISRC}, the RIFF source)
     */
    public static String riffInfoKey(String id) {
        return RiffTagFile.infoKey(id);
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
        openers.put(ContainerFormat.AC3, Id3StreamTagFile::new);
        openers.put(ContainerFormat.AMR, Id3StreamTagFile::new);
        openers.put(ContainerFormat.FLAC, FlacTagFile::new);
        openers.put(ContainerFormat.OGG, OggTagFile::new);
        openers.put(ContainerFormat.MP4, Mp4TagFile::new);
        openers.put(ContainerFormat.MATROSKA, MatroskaTagFile::new);
        openers.put(ContainerFormat.WAV, RiffTagFile::new);
        openers.put(ContainerFormat.AIFF, RiffTagFile::new);
        openers.put(ContainerFormat.AVI, AviTagFile::new);
        // TODO: edit the track names and audio types of MPEG-TS files in the program map sections they repeat, which
        // TsMuxer writes and TsDemuxer reads.
        return Collections.unmodifiableMap(openers);
    }
}
