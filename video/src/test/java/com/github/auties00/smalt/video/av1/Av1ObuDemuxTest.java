package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.raw.ObuDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads AV1 elementary streams through {@link ObuDemuxer} and decodes its packets with the default (low-overhead)
 * decoder: aomenc's raw Annex B stream must decode to aomdec's output, and libaom test vectors written as
 * low-overhead and as Annex B streams (with and without {@code obu_size}, size fields padded) to their reference
 * MD5s, with the key frames where the IVF demuxer flags them.
 */
@DisplayName("AV1 OBU elementary streams")
class Av1ObuDemuxTest {
    private static List<Packet> packets(byte[] stream, ContainerFormat format) {
        assertEquals(format, Containers.detect(MediaSource.of(stream)).orElseThrow());
        var packets = new ArrayList<Packet>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            assertEquals(format, demuxer.format());
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        return packets;
    }

    private static VideoTrack track(byte[] stream) {
        try (var demuxer = new ObuDemuxer(MediaSource.of(stream))) {
            return (VideoTrack) demuxer.tracks().getFirst();
        }
    }

    @Test
    @DisplayName("aomenc's raw Annex B stream decodes to aomdec's output")
    void aomenc() {
        var vector = Av1AnnexBTest.encoded().filter(v -> v.name().equals("annexb-raw.obu")).findFirst().orElseThrow();
        var stream = vector.stream();
        var packets = packets(stream, ContainerFormat.AV1_ANNEX_B);
        assertEquals(vector.frames(), packets.size());
        assertTrue(packets.getFirst().keyframe());
        var out = new ByteArrayOutputStream();
        var frames = new int[1];
        Consumer<VideoFrame> sink = frame -> {
            frames[0]++;
            out.writeBytes(Av1Streams.raw(frame.picture(), false));
        };
        try (var decoder = new Av1Decoder(track(stream), Av1DecoderOptions.builder().highBitDepth(true).build())) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        assertEquals(vector.frames(), frames[0]);
        assertEquals(vector.decodedSha256(), Av1AnnexBTest.sha256(out.toByteArray()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"av1-1-b8-22-svc-L2T2-2.ivf", "av1-1-b8-23-film_grain-50.ivf",
            "av1-1-b10-23-film_grain-50.ivf", "av1-1-b8-06-mfmv.ivf", "av1-1-b10-24-monochrome.ivf",
            "av1-1-b8-04-cdfupdate.ivf", "av1-1-b8-03-sizeup.mkv"})
    @DisplayName("libaom vectors as low-overhead and Annex B streams decode to their reference MD5")
    void vectors(String name) {
        var file = Av1Streams.vector("av1-" + name);
        var units = Av1Streams.units(file);
        var keys = new ArrayList<Boolean>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                keys.add(packet.keyframe());
            }
        }
        // an IVF file carries the sequence header in its units only, a Matroska file in its av1C record
        var sequenceHeader = Av1Streams.track(file).codecConfig()
                .map(config -> Arrays.copyOfRange(config, 4, config.length)).orElse(new byte[0]);
        var lowOverhead = new ByteArrayOutputStream();
        var annexB = new ByteArrayOutputStream();
        var random = new SplittableRandom(name.hashCode());
        for (var index = 0; index < units.size(); index++) {
            var data = units.get(index).data();
            // a Matroska block holds no temporal delimiter, which a low-overhead stream needs between its units, and
            // may leave the sequence header of a key frame to the track's av1C record
            if (((data[0] >> 3) & 0x0F) != 2) {
                var unit = new ByteArrayOutputStream();
                unit.writeBytes(new byte[]{0x12, 0x00});
                if (keys.get(index) && ((data[0] >> 3) & 0x0F) != 1) {
                    unit.writeBytes(sequenceHeader);
                }
                unit.writeBytes(data);
                data = unit.toByteArray();
            }
            lowOverhead.writeBytes(data);
            annexB.writeBytes(Av1Streams.toAnnexB(data, random));
        }
        var expected = Av1Streams.referenceMd5("av1-" + name);
        for (var format : List.of(ContainerFormat.AV1, ContainerFormat.AV1_ANNEX_B)) {
            var stream = format == ContainerFormat.AV1 ? lowOverhead.toByteArray() : annexB.toByteArray();
            var packets = packets(stream, format);
            assertEquals(units.size(), packets.size(), "temporal units of " + format);
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(keys.get(i), packets.get(i).keyframe(), format + " key flag of " + i);
                assertEquals(i, packets.get(i).pts());
            }
            var md5 = new ArrayList<String>();
            Consumer<VideoFrame> sink = frame -> md5.add(Av1Streams.md5(frame.picture()));
            try (var decoder = new Av1Decoder(track(stream), Av1DecoderOptions.builder().highBitDepth(true).build())) {
                for (var packet : packets) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
            assertEquals(expected, md5, format + " decoded MD5s");
        }
    }
}
