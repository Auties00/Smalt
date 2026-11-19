package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

/**
 * Receives the packets of one input track: a {@link Stage}, or a decoder that several stages share.
 *
 * <p>An instance is not thread-safe.
 */
interface TrackReceiver {
    /**
     * Returns the identifier of the input track the receiver reads.
     *
     * @return the track identifier, or {@code -1} for an input without tracks
     */
    int sourceTrackId();

    /**
     * Processes the next packet of the input track.
     *
     * @param packet the packet, in decoding order
     */
    void accept(Packet packet);

    /**
     * Prepares for the packets of a later file of a concatenation, called before its first packet of this track.
     *
     * @implSpec The default implementation does nothing: the packets of every file are handled alike.
     * @param track the file's track, with the identifier and time base of the first file's
     * @param startMicros the start of the file on the concatenated timeline, in microseconds
     */
    default void startSegment(Track track, long startMicros) {
    }

    /**
     * Returns whether the receiver reached the end of the kept range, so later packets are ignored.
     *
     * @return {@code true} when no later packet can contribute to the output
     */
    boolean done();
}
