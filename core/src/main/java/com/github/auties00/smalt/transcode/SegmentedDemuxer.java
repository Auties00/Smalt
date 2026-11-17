package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.track.Track;

/**
 * A demuxer whose tracks continue with the tracks of later files, as a concatenation's do.
 */
interface SegmentedDemuxer extends Demuxer {
    /**
     * Receives the start of a later file, per track.
     */
    @FunctionalInterface
    interface Listener {
        /**
         * Receives the start of a later file in a track, before the file's first packet of the track is read.
         *
         * @param trackId the identifier of the track, as {@link #tracks()} numbers it
         * @param track the file's track, with that identifier and the time base of the track
         * @param startMicros the start of the file on the demuxer's timeline, in microseconds
         */
        void segmentStarted(int trackId, Track track, long startMicros);
    }

    /**
     * Sets the receiver of the starts of later files.
     *
     * @param listener the receiver, or {@code null} for none
     */
    void segmentListener(Listener listener);
}
