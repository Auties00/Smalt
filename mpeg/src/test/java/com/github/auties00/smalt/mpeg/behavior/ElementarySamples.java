package com.github.auties00.smalt.mpeg.behavior;

import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream;

/**
 * Packages a raw MPEG-4 Part 2 or H.263 elementary stream into an AVI file of one track at 30 frames per second, one
 * stored frame per packet the elementary stream reader splits (AVI timestamps the stored frames in decoding order),
 * so that the stream runs through {@code VideoReader} under the I/O behavior harness.
 */
final class ElementarySamples {
    private ElementarySamples() {
    }

    /**
     * Returns an AVI file holding an elementary stream.
     *
     * @param stream the elementary stream
     * @param width the display width the track declares
     * @param height the display height the track declares
     */
    static byte[] avi(byte[] stream, int width, int height) {
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(stream); var elementary = Mpeg4ElementaryStream.open(source);
             var muxer = new AviMuxer(sink)) {
            var track = elementary.track().toBuilder().width(width).height(height).timeBase(new Rational(1, 30))
                    .build();
            muxer.addTrack(track);
            var index = 0L;
            for (var packet = elementary.read(); packet != null; packet = elementary.read()) {
                muxer.writePacket(new Packet(track.id(), packet.data(), index, index, 1, packet.keyframe()));
                index++;
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }
}
