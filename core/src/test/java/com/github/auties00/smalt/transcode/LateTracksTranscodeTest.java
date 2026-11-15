package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.ogg.OggMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers transcodes of an input whose demuxer finds tracks while reading: a chained Ogg stream read once, whose
 * later links are described only as they are reached. A copy of every audio track into Matroska, through the muxer's
 * late tracks, and into MP4, through {@code Mp4Remuxer}, keeps every link as the copy of the same bytes from memory
 * does, the default selection keeps the first link only in both cases, and
 * an output that cannot take a track after its first packet fails cleanly.
 */
@DisplayName("transcodes of tracks found while reading")
class LateTracksTranscodeTest {
    static final Rational OPUS = Rational.timeBaseOf(48_000);

    /** One Ogg link of synthetic Opus packets of 20 ms with a TOC byte and a random payload. */
    static byte[] link(int serial, int channels, int count, long seed) {
        var track = AudioTrack.builder().id(0).codec(Codec.OPUS).timeBase(OPUS).sampleRate(48_000)
                .layout(ChannelLayout.defaultFor(channels)).skipSamples(312).build();
        var random = new SplittableRandom(seed);
        var output = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(output), OggMuxerOptions.builder().serial(serial).build())) {
            muxer.addTrack(track);
            for (var index = 0; index < count; index++) {
                var data = new byte[30 + random.nextInt(50)];
                random.nextBytes(data);
                data[0] = (byte) 0xF8;
                muxer.writePacket(new Packet(0, data, index * 960L, index * 960L, 960, true));
            }
            muxer.finish();
        }
        return output.toByteArray();
    }

    /** A chain of three Opus links. */
    static byte[] chain() {
        var output = new ByteArrayOutputStream();
        output.writeBytes(link(11, 2, 150, 1));
        output.writeBytes(link(22, 1, 100, 2));
        output.writeBytes(link(33, 2, 120, 3));
        return output.toByteArray();
    }

    static MediaSource unsized(byte[] file) {
        return MediaSource.of(new ByteArrayInputStream(file), -1);
    }

    static TranscodeOptions copyAll(OutputContainer container) {
        return TranscodeOptions.remux(container).toBuilder()
                .audio(AudioTranscodeOptions.copy().toBuilder().allTracks().build()).build();
    }

    /** The packets of every link, in link order, as the memory read of the chain gives them. */
    static List<List<Packet>> linkPackets(byte[] file) {
        try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
            var result = new ArrayList<List<Packet>>();
            for (var ignored : demuxer.tracks()) {
                result.add(new ArrayList<>());
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                result.get(packet.trackId()).add(packet);
            }
            return result;
        }
    }

    static ContainerProbe.Demuxed transcode(MediaSource input, TranscodeOptions options, String name) {
        var sink = MediaSink.memory();
        var result = Transcoder.transcode(input, sink, options);
        assertTrue(result.streams().stream().allMatch(TranscodedStream::copied));
        return MuxSupport.demux(MuxSupport.validated(name, sink.toByteArray()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = OutputContainer.class, names = {"MATROSKA", "MP4"})
    @DisplayName("copies every link of a chain read once, as from memory")
    void copiesEveryLink(OutputContainer container) {
        var file = chain();
        var expected = linkPackets(file);
        var fromMemory = transcode(MediaSource.of(file), copyAll(container), "chain-memory");
        var fromStream = transcode(unsized(file), copyAll(container), "chain-stream");
        for (var demuxed : List.of(fromMemory, fromStream)) {
            assertEquals(3, demuxed.tracks().size());
            for (var index = 0; index < 3; index++) {
                var track = demuxed.tracks().get(index);
                assertEquals(Codec.OPUS, track.codec());
                var packets = demuxed.packetsOf(track);
                assertEquals(expected.get(index).size(), packets.size(), "packets of link " + index);
                for (var at = 0; at < packets.size(); at++) {
                    assertArrayEquals(expected.get(index).get(at).data(), packets.get(at).data());
                }
            }
        }
        for (var index = 0; index < 3; index++) {
            assertEquals(MuxSupport.ptsMicros(fromMemory, fromMemory.tracks().get(index)),
                    MuxSupport.ptsMicros(fromStream, fromStream.tracks().get(index)), "times of link " + index);
            assertEquals(((AudioTrack) fromMemory.tracks().get(index)).layout(),
                    ((AudioTrack) fromStream.tracks().get(index)).layout());
        }
    }

    @Test
    @DisplayName("keeps the first link only when the options take the first audio track, as from memory")
    void firstTrackOnly() {
        var file = chain();
        var options = TranscodeOptions.remux(OutputContainer.MATROSKA);
        var fromMemory = transcode(MediaSource.of(file), options, "first-memory");
        var fromStream = transcode(unsized(file), options, "first-stream");
        assertEquals(1, fromMemory.tracks().size());
        assertEquals(1, fromStream.tracks().size());
        assertEquals(fromMemory.packetsOf(fromMemory.tracks().getFirst()).size(),
                fromStream.packetsOf(fromStream.tracks().getFirst()).size());
    }

    @Test
    @DisplayName("appends the tracks an input of a merge finds while reading, with the next merged identifiers")
    void merged() {
        var file = chain();
        var other = link(44, 1, 60, 4);
        var expected = linkPackets(file);
        try (var merged = new MergedDemuxer(List.of(new OggDemuxer(unsized(file)), new OggDemuxer(MediaSource.of(other))))) {
            assertEquals(2, merged.tracks().size());
            var counts = new int[5];
            var listed = merged.tracks();
            for (var packet = merged.read(); packet != null; packet = merged.read()) {
                var current = merged.tracks();
                assertEquals(listed, current.subList(0, listed.size()), "a listed track changed");
                listed = current;
                var id = packet.trackId();
                assertTrue(id >= 1 && id <= current.size(), "packet of unlisted track " + id);
                assertEquals(id, current.get(id - 1).id());
                counts[id - 1]++;
            }
            assertEquals(4, listed.size());
            // merged 1 and 2 are the first link and the other input; the later links of the chain follow
            assertEquals(List.of(0, 1, 0, 0), List.of(merged.inputOf(1), merged.inputOf(2), merged.inputOf(3),
                    merged.inputOf(4)));
            assertEquals(List.of(1, 2), List.of(merged.originalId(3), merged.originalId(4)));
            assertEquals(3, merged.mergedId(0, 1));
            assertEquals(List.of(expected.get(0).size(), 60, expected.get(1).size(), expected.get(2).size()),
                    List.of(counts[0], counts[1], counts[2], counts[3]));
        }
    }

    @Test
    @DisplayName("fails cleanly when the output cannot take a track after its first packet")
    void streamingOutput() {
        var file = chain();
        var stream = new ByteArrayOutputStream();
        try (var sink = MediaSink.of(stream)) {
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> Transcoder.transcode(unsized(file), sink, copyAll(OutputContainer.MATROSKA)));
            assertTrue(failure.getMessage().contains("found while reading"), failure.getMessage());
        }
    }
}
