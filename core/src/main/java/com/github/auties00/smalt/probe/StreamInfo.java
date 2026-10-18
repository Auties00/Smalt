package com.github.auties00.smalt.probe;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Describes one stream of a probed file: a track of a container, or the picture of an image file.
 *
 * <p>The permitted types are final: {@link VideoStreamInfo}, {@link AudioStreamInfo},
 * {@link DataStreamInfo} (subtitles, timed metadata and every other non-media track) and
 * {@link ImageStreamInfo}. Types may be added in minor releases, so a {@code switch} over this
 * type needs a {@code default} branch. Instances are immutable and thread-safe.
 */
public sealed interface StreamInfo permits VideoStreamInfo, AudioStreamInfo, DataStreamInfo, ImageStreamInfo {
    /**
     * Returns the position of the stream among the streams of the file.
     *
     * @return the zero-based index
     */
    int index();

    /**
     * Returns the identifier of the container track this stream describes, the
     * {@link com.github.auties00.smalt.track.Track#id()} its packets carry.
     *
     * @return the track identifier, or empty for the picture of an image file
     */
    OptionalInt trackId();

    /**
     * Returns the language of the stream.
     *
     * @return the ISO 639-2 or BCP 47 language code, or empty when undeclared
     */
    Optional<String> language();

    /**
     * Returns the duration of the stream.
     *
     * @return the duration in microseconds, or empty when unknown
     */
    OptionalLong durationMicros();

    /**
     * Returns the average bitrate of the stream.
     *
     * @return the bitrate in bits per second, or empty when the container does not give it
     *         without reading the whole stream
     */
    OptionalLong bitrate();

    /**
     * Returns the number of frames (video and images) or packets (audio and data) of the stream.
     *
     * @return the count, or empty when the container does not give it without reading the whole
     *         stream
     */
    OptionalLong frameCount();

    /**
     * Returns the metadata tags of the stream, such as its title or the encoder that produced it.
     *
     * @return the tags, with common names normalized as {@link MediaInfo#tags()} describes;
     *         unmodifiable
     */
    Map<String, String> tags();
}
