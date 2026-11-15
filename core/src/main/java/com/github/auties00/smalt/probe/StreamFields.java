package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.track.TrackDisposition;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Holds the fields every {@link StreamInfo} shares.
 *
 * @param index the position of the stream
 * @param trackId the container track identifier, or {@code -1} for an image
 * @param language the language, or {@code null}
 * @param durationMicros the duration in microseconds, or {@code -1}
 * @param bitRate the bit rate in bits per second, or {@code -1}
 * @param frameCount the frame or packet count, or {@code -1}
 * @param tags the normalized tags, unmodifiable
 * @param disposition the dispositions, unmodifiable
 */
record StreamFields(int index, int trackId, String language, long durationMicros, long bitRate, long frameCount,
                    Map<String, String> tags, Set<TrackDisposition> disposition) {
    /**
     * Returns the track identifier.
     *
     * @return the identifier, or empty
     */
    OptionalInt optionalTrackId() {
        return trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * Returns the language.
     *
     * @return the language, or empty
     */
    Optional<String> optionalLanguage() {
        return Optional.ofNullable(language);
    }

    /**
     * Returns the duration.
     *
     * @return the duration, or empty
     */
    OptionalLong optionalDuration() {
        return durationMicros < 0 ? OptionalLong.empty() : OptionalLong.of(durationMicros);
    }

    /**
     * Returns the bit rate.
     *
     * @return the bit rate, or empty
     */
    OptionalLong optionalBitRate() {
        return bitRate < 0 ? OptionalLong.empty() : OptionalLong.of(bitRate);
    }

    /**
     * Returns the frame count.
     *
     * @return the count, or empty
     */
    OptionalLong optionalFrameCount() {
        return frameCount < 0 ? OptionalLong.empty() : OptionalLong.of(frameCount);
    }

    /**
     * Formats the shared fields for a {@code toString}.
     *
     * @return the fields as {@code name=value} pairs
     */
    String describe() {
        return "index=" + index + (trackId >= 0 ? ", trackId=" + trackId : "") + (language != null ? ", language=" + language : "")
                + (durationMicros >= 0 ? ", durationMicros=" + durationMicros : "") + (bitRate >= 0 ? ", bitRate=" + bitRate : "")
                + (frameCount >= 0 ? ", frameCount=" + frameCount : "") + (tags.isEmpty() ? "" : ", tags=" + tags)
                + (disposition.isEmpty() ? "" : ", disposition=" + disposition);
    }
}
