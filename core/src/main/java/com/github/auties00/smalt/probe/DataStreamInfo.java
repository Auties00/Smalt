package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.track.Codec;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Describes a stream of a probed file that is neither audio nor video: subtitles, chapters text,
 * timecode, timed metadata or any track this module does not interpret.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class DataStreamInfo implements StreamInfo {
    /**
     * The shared fields.
     */
    private final StreamFields fields;

    /**
     * The codec.
     */
    private final Codec codec;

    /**
     * The handler, or {@code null}.
     */
    private final String handler;

    /**
     * Constructs a description.
     *
     * @param fields  the shared fields
     * @param codec   the codec of the stream
     * @param handler the container handler, or {@code null}
     */
    DataStreamInfo(StreamFields fields, Codec codec, String handler) {
        this.fields = fields;
        this.codec = codec;
        this.handler = handler;
    }

    @Override
    public int index() {
        return fields.index();
    }

    @Override
    public OptionalInt trackId() {
        return fields.optionalTrackId();
    }

    @Override
    public Optional<String> language() {
        return fields.optionalLanguage();
    }

    @Override
    public OptionalLong durationMicros() {
        return fields.optionalDuration();
    }

    @Override
    public OptionalLong bitrate() {
        return fields.optionalBitRate();
    }

    @Override
    public OptionalLong frameCount() {
        return fields.optionalFrameCount();
    }

    @Override
    public Map<String, String> tags() {
        return fields.tags();
    }

    /**
     * Returns the codec of the stream.
     *
     * @return the codec, {@link Codec#DATA} when the container does not name one
     */
    public Codec codec() {
        return codec;
    }

    /**
     * Returns the container's name for the kind of data, such as the MP4 handler type
     * ({@code text}, {@code sbtl}, {@code tmcd}, {@code meta}).
     *
     * @return the handler, or empty when the container does not name one
     */
    public Optional<String> handler() {
        return Optional.ofNullable(handler);
    }

    @Override
    public String toString() {
        return "DataStreamInfo[" + fields.describe() + ", codec=" + codec + (handler != null ? ", handler=" + handler : "")
                + "]";
    }
}
