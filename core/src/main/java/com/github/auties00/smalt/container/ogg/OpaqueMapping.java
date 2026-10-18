package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Map;

/**
 * Describes a logical stream whose mapping this demuxer does not interpret, such as an Ogg
 * Skeleton index or an unknown codec.
 *
 * <p>The stream has no header packets: every packet, the first included, is returned as-is on a
 * {@link DataTrack} whose handler names what was recognized ({@code "skeleton"} or
 * {@code "unknown"}), without timestamps.
 */
final class OpaqueMapping extends OggMapping {
    /**
     * The handler name reported on the track.
     */
    private final String handler;

    /**
     * Creates the mapping.
     *
     * @param handler the handler name reported on the track
     */
    OpaqueMapping(String handler) {
        this.handler = handler;
    }

    @Override
    int headerCount() {
        return 0;
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        throw new IllegalStateException("opaque streams have no headers");
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        return DataTrack.builder().id(id).timeBase(timeBase()).handler(handler).build();
    }

    @Override
    Rational timeBase() {
        return Rational.MILLISECONDS;
    }
}
