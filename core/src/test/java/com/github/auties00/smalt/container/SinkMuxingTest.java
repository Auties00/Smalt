package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.ogg.OggMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers muxing into every kind of {@link MediaSink}: the streaming muxers (fast-start MP4,
 * remuxing, Ogg) write the same bytes to a channel, memory and a non-seekable stream, and the
 * progressive MP4 muxer rejects a stream.
 */
@DisplayName("Muxing into sinks")
class SinkMuxingTest {
    @Test
    @DisplayName("remuxes to a channel, memory and a stream with identical bytes")
    void remux() throws Exception {
        var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 1, List.of("-c:v", "libx264", "-c:a", "aac")));
        var channel = new MemoryChannel();
        var viaChannel = Mp4Remuxer.remux(MediaSource.of(input), MediaSink.of(channel), RemuxOptions.video());
        var memory = MediaSink.memory();
        var viaMemory = Mp4Remuxer.remux(MediaSource.of(input), memory);
        var stream = new ByteArrayOutputStream();
        var viaStream = Mp4Remuxer.remux(MediaSource.of(input), MediaSink.of(stream), RemuxOptions.video());
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("sink-remux.mp4", channel.toByteArray());
        assertArrayEquals(channel.toByteArray(), memory.toByteArray());
        assertArrayEquals(channel.toByteArray(), stream.toByteArray());
        assertEquals(viaChannel.outputSize(), viaMemory.outputSize());
        assertEquals(viaChannel.outputSize(), viaStream.outputSize());
        assertEquals(channel.toByteArray().length, viaStream.outputSize());
    }

    @Test
    @DisplayName("writes a fast-start MP4 to a stream and rejects a stream without fast start")
    void mp4() throws Exception {
        var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 1, List.of("-c:v", "libx264", "-an")));
        Function<MediaSink, Void> mux = sink -> {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(input));
                 var muxer = new Mp4Muxer(sink)) {
                demuxer.tracks().forEach(muxer::addTrack);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            return null;
        };
        var memory = MediaSink.memory();
        mux.apply(memory);
        var stream = new ByteArrayOutputStream();
        mux.apply(MediaSink.of(stream));
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("sink-fast-start.mp4", stream.toByteArray());
        assertArrayEquals(memory.toByteArray(), stream.toByteArray());
        var progressive = Mp4MuxerOptions.mp4().toBuilder().fastStart(false).build();
        var failure = assertThrows(MediaException.Unsupported.class,
                () -> new Mp4Muxer(MediaSink.of(new ByteArrayOutputStream()), progressive));
        assertEquals("non-seekable output", failure.format());
    }

    @Test
    @DisplayName("writes the same Ogg file to a channel and a stream")
    void ogg() throws Exception {
        var input = Fixtures.read(Fixtures.tone("ogg", 440, 48_000, 1, 0.5, List.of("-c:a", "libopus")));
        var packets = new ArrayList<Packet>();
        Track track;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(input))) {
            track = demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        var options = OggMuxerOptions.builder().serial(0x1234).build();
        var channel = new MemoryChannel();
        try (var muxer = new OggMuxer(MediaSink.of(channel), options)) {
            muxer.addTrack(track);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        var stream = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(stream), options)) {
            muxer.addTrack(track);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        assertArrayEquals(channel.toByteArray(), stream.toByteArray());
    }
}
