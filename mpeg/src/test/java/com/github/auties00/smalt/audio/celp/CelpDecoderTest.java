package com.github.auties00.smalt.audio.celp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.aac.LoasMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the API of {@link CelpDecoder} and {@link CelpDecoderOptions} on the ISO/IEC 14496-26 smoke streams: the
 * codec registry, the layer limit and the missing layers of a frame, the post-processor, closed decoders, invalid
 * tracks and damaged access units, and CELP carried in LOAS. {@link CelpConformanceTest} checks the decoded signal.
 */
@DisplayName("CelpDecoder")
class CelpDecoderTest {
    private static float[] decodeBase(CelpTestSupport.Stream stream, CelpDecoderOptions options) {
        try (var decoder = new CelpDecoder(List.of(stream.tracks().getFirst()), options)) {
            var out = new CelpTestSupport.Samples();
            for (var frame = 0; frame < stream.frames(); frame++) {
                decoder.decode(stream.units().getFirst().get(frame), buffer -> out.add(buffer.plane(0),
                        buffer.frames()));
            }
            return out.toArray();
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("defaults decode every layer with the post-processor under the default limits")
        void defaults() {
            var options = CelpDecoderOptions.defaults();
            assertTrue(options.postFilter());
            assertTrue(options.maxLayer().isEmpty());
            assertSame(options, CelpDecoderOptions.defaults());
        }

        @Test
        @DisplayName("toBuilder keeps every setting and the setters validate")
        void builder() {
            var options = CelpDecoderOptions.builder().postFilter(false).maxLayer(2).build();
            var copy = options.toBuilder().build();
            assertFalse(copy.postFilter());
            assertEquals(2, copy.maxLayer().orElseThrow());
            assertTrue(options.toBuilder().allLayers().build().maxLayer().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> CelpDecoderOptions.builder().maxLayer(-1));
            assertThrows(NullPointerException.class, () -> CelpDecoderOptions.builder().limits(null));
        }
    }

    @Test
    @DisplayName("the MP4 demuxer reports CELP tracks, which the registry decodes")
    void registry() {
        var stream = CelpTestSupport.stream("ce00");
        var track = stream.tracks().getFirst();
        assertEquals(Codec.CELP, track.codec());
        assertEquals(8000, track.sampleRate());
        assertEquals(ChannelLayout.MONO, track.layout().orElseThrow());
        try (var decoder = AudioDecoders.create(track)) {
            assertInstanceOf(CelpDecoder.class, decoder);
            assertEquals(8000, decoder.sampleRate());
            assertEquals(ChannelLayout.MONO, decoder.layout());
        }
        try (var decoder = new CelpDecoder(track, AudioDecoderOptions.defaults())) {
            assertEquals(160, decoder.frameSize());
            assertTrue(decoder.options().postFilter());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ce00", "ce07", "er_ce06_ep0", "er_ce02_ep3"})
    @DisplayName("decodes the base layer of a stream remuxed to LOAS as from its MP4 file, error protected or not")
    void loasRoundTrip(String name) {
        var stream = CelpTestSupport.stream(name);
        var options = CelpDecoderOptions.builder().postFilter(false).build();
        var track = stream.tracks().getFirst();
        var expected = CelpTestSupport.decode(new CelpTestSupport.Stream(List.of(track),
                List.of(stream.units().getFirst())), options);
        var sink = MediaSink.memory();
        try (var muxer = new LoasMuxer(sink)) {
            muxer.addTrack(track);
            for (var packet : stream.units().getFirst()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
            var read = (AudioTrack) demuxer.tracks().getFirst();
            // the configuration keeps the bits a reader parses: the trailing bit the ce07 file sets after its
            // CelpSpecificConfig is dropped
            assertEquals(Codec.CELP, read.codec());
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            var remuxed = new CelpTestSupport.Stream(List.of(read), List.of(packets));
            assertArrayEquals(expected, CelpTestSupport.decode(remuxed, options));
        }
    }

    @Test
    @DisplayName("decodes a single-stream object packet by packet as the list decode does")
    void singlePacket() {
        var stream = CelpTestSupport.stream("ce07");
        var options = CelpDecoderOptions.builder().postFilter(false).build();
        var listed = CelpTestSupport.decode(stream, options);
        var single = decodeBase(stream, options);
        assertArrayEquals(listed, single);
        assertEquals(stream.frames() * 240, single.length);
    }

    @Test
    @DisplayName("the post-processor changes the speech without changing its level")
    void postFilter() {
        var stream = CelpTestSupport.stream("ce00");
        var plain = CelpTestSupport.decode(stream, CelpDecoderOptions.builder().postFilter(false).build());
        var filtered = CelpTestSupport.decode(stream, CelpDecoderOptions.defaults());
        assertEquals(plain.length, filtered.length);
        double plainEnergy = 0;
        double filteredEnergy = 0;
        double difference = 0;
        for (var i = 0; i < plain.length; i++) {
            assertTrue(Float.isFinite(filtered[i]));
            plainEnergy += plain[i] * plain[i];
            filteredEnergy += filtered[i] * filtered[i];
            difference += (plain[i] - filtered[i]) * (plain[i] - filtered[i]);
        }
        assertTrue(difference > 1e-4 * plainEnergy, "the post-processor changes the signal");
        var ratio = 10 * Math.log10(filteredEnergy / plainEnergy);
        assertTrue(Math.abs(ratio) < 3, "level change " + ratio + " dB");
    }

    @Nested
    @DisplayName("layers")
    class Layers {
        @Test
        @DisplayName("the layer limit selects the rate and the frame size")
        void limit() {
            var stream = CelpTestSupport.stream("er_ce06_ep0");
            assertEquals(4, stream.tracks().size());
            try (var decoder = new CelpDecoder(stream.tracks(), CelpDecoderOptions.builder().maxLayer(2).build())) {
                assertEquals(8000, decoder.sampleRate());
                assertEquals(240, decoder.frameSize());
            }
            try (var decoder = new CelpDecoder(stream.tracks(), CelpDecoderOptions.defaults())) {
                assertEquals(16000, decoder.sampleRate());
                assertEquals(480, decoder.frameSize());
            }
            try (var decoder = new CelpDecoder(stream.tracks(), CelpDecoderOptions.builder().maxLayer(9).build())) {
                assertEquals(16000, decoder.sampleRate());
            }
        }

        @Test
        @DisplayName("a frame without its upper layers decodes the layers present")
        void missing() {
            var stream = CelpTestSupport.stream("er_ce06_ep0");
            var options = CelpDecoderOptions.builder().postFilter(false).build();
            var base = CelpTestSupport.decode(stream, options.toBuilder().maxLayer(0).build());
            try (var decoder = new CelpDecoder(stream.tracks(), options)) {
                var out = new CelpTestSupport.Samples();
                for (var frame = 0; frame < stream.frames(); frame++) {
                    decoder.decode(List.of(stream.units().getFirst().get(frame)), buffer -> {
                        assertEquals(480, buffer.frames());
                        out.add(buffer.plane(0), buffer.frames());
                    });
                }
                var decoded = out.toArray();
                for (var i = 0; i < base.length; i++) {
                    assertEquals(base[i], decoded[2 * i], "sample " + i);
                    assertEquals(base[i], decoded[2 * i + 1], "sample " + i);
                }
            }
        }

        @Test
        @DisplayName("an object whose categories sit in separate tracks needs all of them")
        void categories() {
            var stream = CelpTestSupport.stream("er_ce00_ep1");
            assertEquals(5, stream.tracks().size());
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(stream.tracks().getFirst()));
            try (var decoder = new CelpDecoder(stream.tracks(), CelpDecoderOptions.defaults())) {
                var frames = new int[1];
                decoder.decode(stream.frame(0).subList(0, 2), buffer -> {
                    frames[0]++;
                    for (var i = 0; i < buffer.frames(); i++) {
                        assertEquals(0f, buffer.plane(0)[i]);
                    }
                });
                assertEquals(1, frames[0], "a frame lacking categories decodes to silence");
            }
        }
    }

    @Nested
    @DisplayName("invalid use")
    class Invalid {
        @Test
        @DisplayName("refuses tracks of other codecs, without a configuration or of an enhancement layer")
        void tracks() {
            var aac = AudioTrack.builder().codec(Codec.AAC).timeBase(new Rational(1, 8000)).build();
            assertThrows(IllegalArgumentException.class, () -> new CelpDecoder(aac));
            var bare = AudioTrack.builder().codec(Codec.CELP).timeBase(new Rational(1, 8000)).build();
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(bare));
            assertThrows(IllegalArgumentException.class, () -> new CelpDecoder(List.of(),
                    CelpDecoderOptions.defaults()));
            var writer = new MsbBitWriter();
            writer.write(8, 5);
            writer.write(11, 4);
            writer.write(1, 4);
            writer.write(0, 1);
            writer.write(0, 1);
            writer.write(1, 2);
            writer.alignWithZeros();
            var layer = bare.toBuilder().codecConfig(writer.toByteArray()).build();
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(layer));
            var truncated = bare.toBuilder().codecConfig(new byte[]{0x40}).build();
            assertThrows(MediaException.class, () -> new CelpDecoder(truncated));
        }

        @Test
        @DisplayName("a closed decoder refuses packets, and flush emits nothing")
        void closed() {
            var stream = CelpTestSupport.stream("ce00");
            var decoder = new CelpDecoder(stream.tracks().getFirst());
            decoder.flush(buffer -> {
                throw new AssertionError("flush emitted a block");
            });
            decoder.close();
            assertThrows(IllegalStateException.class,
                    () -> decoder.decode(stream.units().getFirst().getFirst(), buffer -> {
                    }));
            assertThrows(IllegalArgumentException.class, () -> new CelpDecoder(stream.tracks().getFirst())
                    .decode(List.of(), buffer -> {
                    }));
        }
    }

    @Test
    @DisplayName("damaged, truncated and empty access units decode to finite blocks or are rejected as malformed")
    void damaged() {
        for (var name : List.of("ce00", "ce07", "er_ce00_ep1", "er_ce02_ep3")) {
            var stream = CelpTestSupport.stream(name);
            var random = new Random(name.hashCode());
            var decodedAfterDamage = 0;
            try (var decoder = new CelpDecoder(stream.tracks(), CelpDecoderOptions.defaults())) {
                for (var frame = 0; frame < Math.min(stream.frames(), 300); frame++) {
                    var packets = new ArrayList<Packet>();
                    for (var packet : stream.frame(frame)) {
                        var data = packet.data().clone();
                        if (frame % 3 == 1 && data.length > 0) {
                            switch (random.nextInt(4)) {
                                case 0 -> data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                                case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length));
                                case 2 -> Arrays.fill(data, (byte) 0xFF);
                                default -> data = new byte[0];
                            }
                        }
                        packets.add(new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(),
                                true));
                    }
                    var damagedFrame = frame % 3 == 1;
                    try {
                        decoder.decode(packets, buffer -> {
                            for (var i = 0; i < buffer.frames(); i++) {
                                assertTrue(Float.isFinite(buffer.plane(0)[i]), name + " finite output");
                                assertTrue(Math.abs(buffer.plane(0)[i]) < 1e4, name + " bounded output");
                            }
                        });
                        if (!damagedFrame) {
                            decodedAfterDamage++;
                        }
                    } catch (MediaException.Malformed exception) {
                        assertTrue(damagedFrame, name + " rejects undamaged frame " + frame + ": " + exception);
                    }
                }
            }
            assertTrue(decodedAfterDamage > 0, name + " recovers after damaged frames");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4", "fragmented"})
    @DisplayName("a stream remuxed to MP4 keeps its configuration and decodes as the original")
    void mp4RoundTrip(String kind) {
        var stream = CelpTestSupport.stream("ce07");
        var track = stream.tracks().getFirst();
        var sink = MediaSink.memory();
        try (var muxer = kind.equals("mp4") ? new Mp4Muxer(sink) : new FragmentedMp4Muxer(sink)) {
            muxer.addTrack(track);
            for (var packet : stream.units().getFirst()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var remuxed = CelpTestSupport.stream(sink.toByteArray());
        assertArrayEquals(track.codecConfig().orElseThrow(), remuxed.tracks().getFirst().codecConfig().orElseThrow());
        var options = CelpDecoderOptions.builder().postFilter(false).build();
        assertArrayEquals(CelpTestSupport.decode(stream, options), CelpTestSupport.decode(remuxed, options));
    }

    @Test
    @DisplayName("the audio reader seeks to the samples of a decode from the start")
    void seek() {
        var data = Fixtures.read(Conformance.fetchListed("mpeg4audio-ce00.mp4"));
        var linear = new CelpTestSupport.Samples();
        try (var reader = AudioReader.open(MediaSource.of(data))) {
            assertEquals(8000, reader.sampleRate());
            for (var block = reader.read(); block != null; block = reader.read()) {
                linear.add(block.plane(0), block.frames());
            }
            var all = linear.toArray();
            for (var seconds : new int[]{3, 1}) {
                reader.seek(seconds * 1_000_000L);
                var seeked = new CelpTestSupport.Samples();
                for (var block = reader.read(); block != null; block = reader.read()) {
                    seeked.add(block.plane(0), block.frames());
                }
                assertArrayEquals(Arrays.copyOfRange(all, seconds * 8000, all.length), seeked.toArray(),
                        "from " + seconds + " s");
            }
        }
    }

    @Test
    @DisplayName("decodes CELP carried in LOAS as the same stream in MP4")
    void loas() {
        var stream = CelpTestSupport.stream("ce00");
        var options = CelpDecoderOptions.builder().postFilter(false).build();
        var expected = CelpTestSupport.decode(stream, options);
        var config = stream.tracks().getFirst().codecConfig().orElseThrow();
        var file = new ByteArrayOutputStream();
        for (var frame = 0; frame < stream.frames(); frame++) {
            file.writeBytes(loasFrame(config, stream.units().getFirst().get(frame).data(), frame % 8 == 0));
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.CELP, track.codec());
            assertEquals(8000, track.sampleRate());
            try (var decoder = new CelpDecoder(List.of(track), options)) {
                var out = new CelpTestSupport.Samples();
                var pts = 0L;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    assertEquals(pts, packet.pts());
                    assertEquals(160, packet.duration());
                    pts += packet.duration();
                    decoder.decode(packet, buffer -> out.add(buffer.plane(0), buffer.frames()));
                }
                assertArrayEquals(expected, out.toArray());
            }
        }
    }

    /**
     * Writes an {@code AudioSyncStream} frame carrying one CELP access unit with {@code frameLengthType} 0.
     *
     * @param config the {@code AudioSpecificConfig}, copied bit by bit
     * @param payload the access unit
     * @param withConfig whether the frame carries the {@code StreamMuxConfig}
     * @return the frame
     */
    private static byte[] loasFrame(byte[] config, byte[] payload, boolean withConfig) {
        var writer = new MsbBitWriter();
        writer.write(withConfig ? 0 : 1, 1);
        if (withConfig) {
            writer.write(0, 1);
            writer.write(1, 1);
            writer.write(0, 6);
            writer.write(0, 4);
            writer.write(0, 3);
            // the AudioSpecificConfig of a CELP base layer with the multi-pulse coder: 13 bits of object type, rate
            // and channels, and 12 of CelpHeader
            for (var bit = 0; bit < 25; bit++) {
                writer.write((config[bit >>> 3] >> (7 - (bit & 7))) & 1, 1);
            }
            writer.write(0, 3);
            writer.write(0xFF, 8);
            writer.write(0, 1);
            writer.write(0, 1);
        }
        var length = payload.length;
        while (length >= 255) {
            writer.write(255, 8);
            length -= 255;
        }
        writer.write(length, 8);
        for (var b : payload) {
            writer.write(b & 0xFF, 8);
        }
        writer.alignWithZeros();
        var element = writer.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(0x56);
        out.write(0xE0 | (element.length >> 8));
        out.write(element.length & 0xFF);
        out.writeBytes(element);
        return out.toByteArray();
    }
}
