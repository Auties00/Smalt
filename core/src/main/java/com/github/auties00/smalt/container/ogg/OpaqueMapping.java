package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Map;

/**
 * Describes a logical stream whose codec this demuxer does not recognize.
 *
 * <p>The stream has no header packets: every packet, the first included, is returned as-is on a
 * {@link DataTrack} whose handler is {@code "unknown"}. Its packets carry no timestamps unless an
 * Ogg Skeleton {@code fisbone} packet gives the stream's granule rate ({@link #describe}): from
 * then on the time base is the inverted granule rate, a packet is stamped with the time of the
 * granule position of the page it ends on, and with a granule shift that time is the sum of the
 * granule position's high bits and its shift low bits.
 */
final class OpaqueMapping extends OggMapping {
    /**
     * The time base given by a {@code fisbone}, or {@code null}.
     */
    private Rational timeBase;

    /**
     * The granule shift given by a {@code fisbone}.
     */
    private int granuleShift;

    /**
     * Applies the granule mapping of a {@code fisbone} packet that describes this stream.
     *
     * @param fisbone the description
     */
    void describe(SkeletonMapping.Fisbone fisbone) {
        if (fisbone.granuleRate() != null && timeBase == null) {
            timeBase = fisbone.granuleRate().invert();
            granuleShift = fisbone.granuleShift();
        }
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
        return DataTrack.builder().id(id).timeBase(timeBase()).duration(duration).handler("unknown").build();
    }

    @Override
    Rational timeBase() {
        return timeBase == null ? Rational.MILLISECONDS : timeBase;
    }

    @Override
    boolean timestamped() {
        return timeBase != null;
    }

    @Override
    long granuleToTime(long granule) {
        if (granuleShift == 0) {
            return granule;
        }
        return (granule >>> granuleShift) + (granule & ((1L << granuleShift) - 1));
    }
}
