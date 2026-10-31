package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.provider.Providers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ContainerProvider;

import java.util.Collections;
import java.util.EnumMap;
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
     * @return {@code true} for MP3, ADTS, raw AC-3 and E-AC-3, AMR, FLAC, Ogg, MP4, Matroska, WAV, AIFF, AVI, ASF
     * and MPEG-TS, and for a format a provider edits the tags of; {@code false} for {@link ContainerFormat#OTHER}
     */
    public static boolean isSupported(ContainerFormat format) {
        return OPENERS.containsKey(format)
                || format != ContainerFormat.OTHER && editor(format, format.shortName()) != null;
    }

    /**
     * Returns whether tags of a container, named by its short name, can be read and written.
     *
     * @param name the short name of a {@link ContainerFormat} or of a container a provider adds
     * @return {@code true} when core or a provider edits the tags of that container
     */
    public static boolean isSupported(String name) {
        var format = ContainerFormat.ofShortName(name).orElse(ContainerFormat.OTHER);
        return format == ContainerFormat.OTHER ? editor(format, name) != null : isSupported(format);
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
        var name = Containers.detectName(source).orElseThrow(() -> new MediaException.Unsupported("unknown",
                "the input is not a recognized audio or video container"));
        var format = ContainerFormat.ofShortName(name).orElse(ContainerFormat.OTHER);
        var opener = OPENERS.get(format);
        if (opener == null) {
            var provider = editor(format, name);
            if (provider == null) {
                throw Artifacts.unsupportedContainer(name, "tag-editable", provided(format, name) ? null
                        : Artifacts.containerOf(format));
            }
            opener = (input, bounds) -> new ProvidedTagFile(provider, input, bounds);
        }
        try {
            return opener.apply(source, limits);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt " + name + " metadata", exception);
        }
    }

    /**
     * Finds the provider that edits the tags of a container.
     *
     * @param format the format of the container, {@link ContainerFormat#OTHER} for one the enum does not name
     * @param name the short name of the container
     * @return the first provider of the container that edits its tags, or {@code null}
     */
    private static ContainerProvider editor(ContainerFormat format, String name) {
        for (var provider : Providers.containers()) {
            if (provider.format() == format && provider.name().equals(name) && provider.editsTags()) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Returns whether any provider handles a container.
     *
     * @param format the format of the container
     * @param name the short name of the container
     * @return {@code true} when a provider of the container is present, whatever it implements
     */
    private static boolean provided(ContainerFormat format, String name) {
        for (var provider : Providers.containers()) {
            if (provider.format() == format && provider.name().equals(name)) {
                return true;
            }
        }
        return false;
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
        openers.put(ContainerFormat.ASF, AsfTagFile::new);
        openers.put(ContainerFormat.MPEG_TS, TsTagFile::new);
        return Collections.unmodifiableMap(openers);
    }
}
