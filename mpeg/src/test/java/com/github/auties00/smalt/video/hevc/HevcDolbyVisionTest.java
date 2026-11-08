package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.track.DolbyVision;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HEVC decoder on Dolby Vision streams, the samples of core's {@code make-dolby-vision-samples.py} and
 * dovi_tool's mkvmerge sample: a
 * backward compatible profile 8.4 stream decodes to the same pictures as its base layer without RPUs, while a profile 5
 * stream, declared by its container or only by its in-band RPUs, is refused with
 * {@link MediaException.Unsupported} unless the options ask for its base layer.
 */
@DisplayName("HEVC decoding of Dolby Vision streams")
class HevcDolbyVisionTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    private record Stream(VideoTrack track, List<Packet> packets) {
    }

    private static Stream read(String name) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(SAMPLES.resolve(name))))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Stream(track, packets);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the access units without their NAL units of type 62. */
    private static List<Packet> withoutRpus(List<Packet> packets) {
        return packets.stream().map(packet -> packet.withData(filter(packet.data()))).toList();
    }

    private static byte[] filter(byte[] accessUnit) {
        var out = new ByteArrayOutputStream();
        NalUnits.forEachLengthPrefixed(accessUnit, 4, (data, offset, length) -> {
            if (length > 0 && NalUnits.type(data[offset], true) != 62) {
                out.write(length >>> 24);
                out.write(length >>> 16);
                out.write(length >>> 8);
                out.write(length);
                out.write(data, offset, length);
            }
        });
        return out.toByteArray();
    }

    private static List<String> decode(VideoDecoder decoder, List<Packet> packets) {
        var hashes = new ArrayList<String>();
        Consumer<VideoFrame> sink = frame -> {
            var digest = HevcTestStreams.md5();
            HevcTestStreams.hash(digest, frame.picture());
            hashes.add(HexFormat.of().formatHex(digest.digest()));
        };
        try (decoder) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return hashes;
    }

    private static List<String> baseLayer(Stream stream) {
        return decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build()), withoutRpus(stream.packets()));
    }

    @Test
    @DisplayName("decodes a profile 8.4 stream to the pictures of its base layer, its RPUs ignored")
    void compatible() {
        var stream = read("mp4/dolby-vision-p84.mp4");
        assertEquals(DolbyVision.of(8, 1, 4), stream.track().dolbyVision().orElseThrow());
        var expected = baseLayer(stream);
        assertEquals(6, expected.size());
        assertEquals(expected, decode(VideoDecoders.create(stream.track()), stream.packets()));
        // without the container's configuration, the in-band profile 8 RPUs are checked and accepted
        assertEquals(expected, decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build()),
                stream.packets()));
    }

    @Test
    @DisplayName("decodes dovi_tool's mkvmerge profile 8.1 sample to the pictures of its base layer")
    void mkvmerge() throws IOException {
        Stream stream;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(
                com.github.auties00.smalt.testkit.Conformance.fetchListed("dovi-tool-regular.mkv"))))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            stream = new Stream(track, packets);
        }
        assertEquals(DolbyVision.of(8, 1, 1), stream.track().dolbyVision().orElseThrow());
        var expected = baseLayer(stream);
        assertEquals(259, expected.size());
        assertEquals(expected, decode(VideoDecoders.create(stream.track()), stream.packets()));
        assertEquals(expected, decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build()),
                stream.packets()));
    }

    @Nested
    @DisplayName("profile 5")
    class Profile5 {
        @Test
        @DisplayName("is refused when the container declares it, by VideoDecoders and by the decoder itself")
        void declared() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            assertFalse(stream.track().dolbyVision().orElseThrow().isBackwardCompatible());
            var refused = assertThrows(MediaException.Unsupported.class, () -> VideoDecoders.create(stream.track()));
            assertEquals("dolby vision reconstruction", refused.format());
            assertThrows(MediaException.Unsupported.class, () -> new HevcDecoder(stream.track()));
        }

        @Test
        @DisplayName("decodes to its base layer when the options ask for it")
        void baseLayerOption() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var expected = baseLayer(stream);
            assertEquals(6, expected.size());
            var options = VideoDecoderOptions.builder().dolbyVisionBaseLayer(true).build();
            assertEquals(expected, decode(VideoDecoders.create(stream.track(), options), stream.packets()));
            assertEquals(expected, decode(new HevcDecoder(stream.track(), HevcDecoderOptions.builder()
                    .dolbyVisionBaseLayer(true).build()), stream.packets()));
            assertEquals(expected, decode(new HevcDecoder(stream.track().toBuilder().noDolbyVision().build(),
                    HevcDecoderOptions.builder().dolbyVisionBaseLayer(true).build()), stream.packets()));
        }

        @Test
        @DisplayName("is refused at its first RPU when only the bitstream declares it, and stays refused")
        void inBand() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var plain = stream.track().toBuilder().noDolbyVision().build();
            try (var decoder = new HevcDecoder(plain)) {
                var frames = new ArrayList<VideoFrame>();
                var first = assertThrows(MediaException.Unsupported.class,
                        () -> decoder.decode(stream.packets().getFirst(), frames::add));
                assertEquals("dolby vision reconstruction", first.format());
                assertThrows(MediaException.Unsupported.class, () -> decoder.decode(stream.packets().get(1), frames::add));
                assertTrue(frames.isEmpty());
            }
        }

        @Test
        @DisplayName("is refused in a raw Annex B stream, which declares nothing")
        void annexB() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var annexB = new ByteArrayOutputStream();
            var record = com.github.auties00.smalt.internal.nal.NalRecords.parseHevcStrict(
                    stream.track().codecConfig().orElseThrow());
            for (var set : record.parameterSets()) {
                annexB.writeBytes(new byte[]{0, 0, 0, 1});
                annexB.writeBytes(set);
            }
            for (var packet : stream.packets()) {
                NalUnits.forEachLengthPrefixed(packet.data(), 4, (data, offset, length) -> {
                    annexB.writeBytes(new byte[]{0, 0, 0, 1});
                    annexB.write(data, offset, length);
                });
            }
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(annexB.toByteArray()))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertFalse(track.dolbyVision().isPresent());
                try (var decoder = VideoDecoders.create(track)) {
                    assertThrows(MediaException.Unsupported.class, () -> {
                        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                            decoder.decode(packet, frame -> { });
                        }
                        decoder.flush(frame -> { });
                    });
                }
            }
        }

        @Test
        @DisplayName("decodes when its RPUs are damaged, since only a valid RPU declares the profile")
        void damagedRpus() {
            var stream = read("mp4/dolby-vision-p5.mp4");
            var damaged = stream.packets().stream().map(packet -> {
                var data = packet.data().clone();
                // the byte before the 0x80 terminator is the last CRC byte of the RPU, the last NAL unit
                data[data.length - 2] ^= 0x5A;
                assertTrue(DolbyVisionRpus.rpuNals(data).size() == 1);
                return packet.withData(data);
            }).toList();
            var plain = stream.track().toBuilder().noDolbyVision().build();
            assertEquals(baseLayer(stream), decode(new HevcDecoder(plain), damaged));
        }
    }
}
