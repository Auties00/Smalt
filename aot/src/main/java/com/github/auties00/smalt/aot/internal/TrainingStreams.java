package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the coded training streams this module bundles, for the decoders of codecs Smalt does not encode, or
 * whose coding tools its encoder does not use.
 */
final class TrainingStreams {
    /**
     * The first track of a training stream and its packets.
     *
     * @param track the first track of the stream
     * @param packets the packets of the track, in decoding order
     */
    record Demuxed(Track track, List<Packet> packets) {
    }

    /**
     * Prevents instantiation.
     */
    private TrainingStreams() {
        throw new AssertionError();
    }

    /**
     * Returns the bytes of a training stream.
     *
     * @param path the resource path, relative to this package
     * @return the bytes
     * @throws IllegalStateException if the resource is missing
     */
    static byte[] bytes(String path) {
        try (var input = TrainingStreams.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing training stream " + path);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("unreadable training stream " + path, exception);
        }
    }

    /**
     * Demuxes the first track of a training stream.
     *
     * @param path the resource path, relative to this package
     * @return the track and its packets
     * @throws IllegalStateException if the resource is missing
     */
    static Demuxed demux(String path) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes(path)))) {
            return read(demuxer);
        }
    }

    /**
     * Reads the packets of the first track of a demuxer.
     *
     * @param demuxer the demuxer, not closed
     * @return the track and its packets
     */
    static Demuxed read(Demuxer demuxer) {
        var track = demuxer.tracks().getFirst();
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            if (packet.trackId() == track.id()) {
                packets.add(packet);
            }
        }
        return new Demuxed(track, List.copyOf(packets));
    }
}
