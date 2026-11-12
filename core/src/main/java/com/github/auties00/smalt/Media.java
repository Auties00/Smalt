package com.github.auties00.smalt;

import com.github.auties00.smalt.internal.probe.ProbeAccess;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.MediaInfo;
import com.github.auties00.smalt.probe.ProbeOptions;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Describes media files from their headers: the facade of the {@code probe} package.
 *
 * <p>{@link #probe(MediaSource)} recognizes the content (never the file name), then describes an
 * image (format, size, bit depth, transparency, orientation, animation frame count and loops) or
 * a container (format, duration, overall bit rate, metadata tags, chapters, cover art, and per
 * track the codec with its profile and level, the picture size, orientation, frame rate, sample
 * aspect ratio, scan, color description with HDR metadata and chroma siting, bit depth, the sampling
 * rate, channels and bits per sample, the language and the track's tags). No pixel or sample is
 * decoded; the demuxer reads the container headers, as opening it for playback does, and the
 * codec configuration records are parsed in core, so a probe needs no codec artifact.
 *
 * <p>The class is stateless and thread-safe.
 *
 * @apiNote This is the in-process replacement for running {@code ffprobe}: the field names and
 * the spelling of profiles and normalized tag names follow ffprobe's where both describe the same
 * thing.
 */
public final class Media {
    /**
     * Prevents instantiation.
     */
    private Media() {
        throw new AssertionError();
    }

    /**
     * Describes a media file with {@link ProbeOptions#defaults()}.
     *
     * @param source the input, read from offset zero; not closed
     * @return the description
     * @throws NullPointerException         if {@code source} is {@code null}
     * @throws MediaException.Unsupported   if the input is neither a recognized image nor a
     *                                      recognized container
     * @throws MediaException.Malformed     if the headers are corrupt
     * @throws MediaException.LimitExceeded if a declared size exceeds the default limits
     * @throws MediaException.Io            if reading the source fails
     */
    public static MediaInfo probe(MediaSource source) {
        return probe(source, ProbeOptions.defaults());
    }

    /**
     * Describes a media file.
     *
     * @param source  the input, read from offset zero; not closed
     * @param options the limits and what to read beyond the headers
     * @return the description
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Unsupported   if the input is neither a recognized image nor a
     *                                      recognized container
     * @throws MediaException.Malformed     if the headers are corrupt
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits of
     *                                      {@code options}
     * @throws MediaException.Io            if reading the source fails
     */
    public static MediaInfo probe(MediaSource source, ProbeOptions options) {
        return ProbeAccess.probe(source, options);
    }

    /**
     * Describes a media file on disk with {@link ProbeOptions#defaults()}; the file is opened and
     * closed by this method.
     *
     * @param file the file, opened and closed by this method
     * @return the description
     * @throws MediaException.Unsupported if the file is neither a recognized image nor a recognized
     *     container
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.LimitExceeded if a declared size exceeds the default limits
     * @throws MediaException.Io if the file cannot be read
     */
    public static MediaInfo probe(Path file) {
        return probe(file, ProbeOptions.defaults());
    }

    /**
     * Describes a media file on disk; the file is opened and closed by this method.
     *
     * @param file the file, opened and closed by this method
     * @param options the limits and what to read beyond the headers
     * @return the description
     * @throws MediaException.Unsupported if the file is neither a recognized image nor a recognized
     *     container
     * @throws MediaException.Malformed if the headers are corrupt
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits of {@code options}
     * @throws MediaException.Io if the file cannot be read
     */
    public static MediaInfo probe(Path file, ProbeOptions options) {
        Objects.requireNonNull(options, "options");
        try (var source = MediaSource.of(file)) {
            return probe(source, options);
        }
    }
}
