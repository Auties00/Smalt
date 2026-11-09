package com.github.auties00.smalt.audio.twinvq;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.aac.AacDecoder;
import com.github.auties00.smalt.audio.aac.AacDecoderOptions;
import com.github.auties00.smalt.audio.aac.internal.ep.EpStreams;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.twinvq.internal.TwinVqConfig;
import com.github.auties00.smalt.audio.twinvq.internal.TwinVqObject;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the API of {@link TwinVqDecoder} and {@link TwinVqDecoderOptions} on the ISO/IEC 14496-26 smoke streams: the
 * codec registry, the layer limit and the missing layers of a frame, closed decoders, invalid tracks, damaged access
 * units, MP4 round trips and seeking. {@link TwinVqConformanceTest} checks the decoded signal.
 */
@DisplayName("TwinVqDecoder")
class TwinVqDecoderTest {
    private static float[][] decodeAll(TwinVqTestSupport.Stream stream, TwinVqDecoderOptions options) {
        try (var decoder = new TwinVqDecoder(stream.tracks(), options)) {
            return TwinVqTestSupport.decode(decoder, stream);
        }
    }

    /**
     * Builds a track of one elementary stream.
     */
    private static AudioTrack synthetic(int id, Codec codec, byte[] config) {
        return AudioTrack.builder().id(id).codec(codec).timeBase(new Rational(1, 12000)).codecConfig(config).build();
    }

    /**
     * Returns a mono frame of random bits with long windows and no long term prediction, optionally with one TNS filter
     * of order 4 over 20 bands.
     */
    private static byte[] monoFrame(Random random, int bytes, boolean tns) {
        var data = new byte[bytes];
        random.nextBytes(data);
        if (tns) {
            data[0] = 0x0B;
            data[1] = 0x50;
            data[2] = (byte) (0x80 | (data[2] & 0x07));
        } else {
            data[0] &= 0x27;
        }
        return data;
    }

    /**
     * Returns a stereo frame of random bits with long windows and no M/S, long term prediction or TNS.
     */
    private static byte[] stereoFrame(Random random, int bytes) {
        var data = new byte[bytes];
        random.nextBytes(data);
        data[0] &= 0x20;
        data[1] &= 0x7F;
        return data;
    }

    private static List<float[]> blocks(java.util.function.Consumer<java.util.function.Consumer<AudioBuffer>> run) {
        var collected = new ArrayList<float[]>();
        run.accept(buffer -> collected.add(Arrays.copyOf(buffer.plane(0), buffer.frames())));
        return collected;
    }

    @Nested
    @DisplayName("a frame with TNS in its base layer")
    class BaseLayerTns {
        private static final byte[] CONFIG = {0x3C, 0x08};

        @Test
        @DisplayName("keeps its filters when the scalable layers are absent, as when the layer limit removes them")
        void missingLayers() {
            var tracks = List.of(synthetic(1, Codec.TWINVQ, CONFIG), synthetic(2, Codec.TWINVQ, CONFIG));
            var random = new Random(144969);
            try (var limited = new TwinVqDecoder(tracks, TwinVqDecoderOptions.builder().maxLayer(0).build());
                 var absent = new TwinVqDecoder(tracks, TwinVqDecoderOptions.defaults());
                 var single = new TwinVqDecoder(tracks, TwinVqDecoderOptions.defaults());
                 var plain = new TwinVqDecoder(tracks.getFirst())) {
                var signal = false;
                for (var frame = 0; frame < 8; frame++) {
                    var packet = new Packet(1, monoFrame(random, 128, true), frame, frame, 1, true);
                    var expected = blocks(sink -> limited.decode(List.of(packet), sink)).getFirst();
                    assertArrayEquals(expected, blocks(sink -> absent.decode(List.of(packet), sink)).getFirst());
                    assertArrayEquals(expected, blocks(sink -> single.decode(packet, sink)).getFirst());
                    assertArrayEquals(expected, blocks(sink -> plain.decode(packet, sink)).getFirst());
                    signal |= !Arrays.equals(new float[expected.length], expected);
                }
                assertTrue(signal, "the frames decode to a signal");
            }
        }

        @Test
        @DisplayName("stands for the max_sfb of the highest layer present")
        void maxBand() {
            var random = new Random(144970);
            // 8 kbit/s at 16 kHz leaves the upper lines of the base layer out, which the second 8 kbit/s brings in
            var first = monoFrame(random, 32, false);
            var second = new byte[32];
            random.nextBytes(second);
            var both = new TwinVqObject(List.of(CONFIG, CONFIG), -1, MediaLimits.defaults());
            var base = new TwinVqObject(List.of(CONFIG), -1, MediaLimits.defaults());
            base.decodeCore(new byte[][]{first}, new int[]{32}, 1);
            assertTrue(base.maxBand() > 0, "the base layer codes bands");
            both.decodeCore(new byte[][]{first}, new int[]{32}, 1);
            assertEquals(base.maxBand(), both.maxBand(), "without the scalable layer");
            both.decodeCore(new byte[][]{first, second}, new int[]{32, 32}, 2);
            assertTrue(both.maxBand() > base.maxBand(), "the scalable layer widens the band");
            both.decodeCore(new byte[][]{first}, new int[]{32}, 0);
            assertEquals(0, both.maxBand(), "a silent frame codes no band");
        }

        @Test
        @DisplayName("runs its filter over the spectrum")
        void filters() {
            var random = new Random(144971);
            var unit = monoFrame(random, 128, true);
            var object = new TwinVqObject(List.of(CONFIG), -1, MediaLimits.defaults());
            object.decodeCore(new byte[][]{unit}, new int[]{128}, 1);
            assertTrue(object.tnsPresent(0), "the frame carries TNS");
            var spectrum = object.spectrum(0).clone();
            object.applyTns(0, spectrum, object.maxBand());
            assertTrue(!Arrays.equals(object.spectrum(0), spectrum), "the filter changes the spectrum");
            var untouched = object.spectrum(0).clone();
            object.applyTns(0, untouched, 0);
            assertArrayEquals(object.spectrum(0), untouched, "no band leaves the spectrum alone");
        }
    }

    @Nested
    @DisplayName("a TwinVQ core under scalable AAC layers")
    class AacLayers {
        private static final byte[] CORE = {0x3C, (byte) 0x88};

        private static final byte[] MONO = {0x34, (byte) 0x8A, 0, 0, 0};

        private static final byte[] STEREO = {0x34, (byte) 0x90, 0x20};

        private static List<AudioTrack> tracks() {
            return List.of(synthetic(1, Codec.TWINVQ, CORE), synthetic(2, Codec.AAC, MONO),
                    synthetic(3, Codec.AAC, STEREO));
        }

        private static List<Packet> layers(int frame) {
            return List.of(new Packet(2, new byte[16], frame, frame, 1, true),
                    new Packet(3, new byte[16], frame, frame, 1, true));
        }

        private static List<Packet> withCore(byte[] core, int frame) {
            var packets = new ArrayList<Packet>();
            packets.add(new Packet(1, core, frame, frame, 1, true));
            packets.addAll(layers(frame));
            return packets;
        }

        private static byte[][] coreFrames() {
            var random = new Random(144972);
            var frames = new byte[6][];
            for (var i = 0; i < frames.length; i++) {
                frames[i] = monoFrame(random, 85, false);
            }
            return frames;
        }

        private static float[] join(List<float[]> blocks) {
            return TwinVqDecoderTest.join(blocks);
        }

        @Test
        @DisplayName("an AAC frame without a core unit decodes over silence and does not repeat the last core frame")
        void missingCoreUnit() {
            var core = coreFrames();
            try (var missing = new AacDecoder(tracks()); var silent = new AacDecoder(tracks());
                 var repeated = new AacDecoder(tracks())) {
                var first = new ArrayList<float[]>();
                var second = new ArrayList<float[]>();
                var third = new ArrayList<float[]>();
                for (var frame = 0; frame < 4; frame++) {
                    var unit = core[frame];
                    var index = frame;
                    first.addAll(blocks(sink -> missing.decode(withCore(unit, index), sink)));
                    second.addAll(blocks(sink -> silent.decode(withCore(unit, index), sink)));
                    third.addAll(blocks(sink -> repeated.decode(withCore(unit, index), sink)));
                }
                for (var frame = 4; frame < 6; frame++) {
                    var index = frame;
                    first.addAll(blocks(sink -> missing.decode(layers(index), sink)));
                    second.addAll(blocks(sink -> silent.decode(withCore(new byte[0], index), sink)));
                    third.addAll(blocks(sink -> repeated.decode(withCore(core[3], index), sink)));
                }
                assertArrayEquals(join(second), join(first));
                assertTrue(!Arrays.equals(join(third), join(first)), "the core frame is not repeated");
                for (var sample : first.getLast()) {
                    assertEquals(0f, sample, 0f, "two frames on, nothing is left of the core");
                }
            }
        }

        @Test
        @DisplayName("takes a core unit and its AAC frame in one call or in separate calls")
        void separateCalls() {
            var core = coreFrames();
            try (var together = new AacDecoder(tracks()); var apart = new AacDecoder(tracks())) {
                for (var frame = 0; frame < core.length; frame++) {
                    var unit = core[frame];
                    var index = frame;
                    var expected = blocks(sink -> together.decode(withCore(unit, index), sink));
                    assertEquals(0, blocks(sink -> apart.decode(new Packet(1, unit, index, index, 1, true), sink)).size(),
                            "the core alone waits for its AAC frame");
                    var decoded = blocks(sink -> apart.decode(layers(index), sink));
                    assertArrayEquals(join(expected), join(decoded), "frame " + frame);
                }
            }
        }

        @Test
        @DisplayName("outputs the core at a layer of the core, whose unit is not held back")
        void coreLayer() {
            var core = coreFrames();
            var options = AacDecoderOptions.builder().maxLayer(0).build();
            try (var limited = new AacDecoder(tracks(), options); var plain = new TwinVqDecoder(tracks().getFirst())) {
                assertEquals(ChannelLayout.MONO, limited.layout());
                assertEquals(1024, limited.frameLength());
                for (var frame = 0; frame < core.length; frame++) {
                    var packet = new Packet(1, core[frame], frame, frame, 1, true);
                    var expected = blocks(sink -> plain.decode(packet, sink));
                    assertArrayEquals(join(expected), join(blocks(sink -> limited.decode(packet, sink))));
                }
            }
        }
    }

    @Test
    @DisplayName("refuses a stereo object under limits of one channel")
    void channelLimit() {
        var stereo = synthetic(1, Codec.TWINVQ, new byte[]{0x3C, 0x10});
        var limits = MediaLimits.builder().maxChannels(1).build();
        assertThrows(MediaException.LimitExceeded.class, () -> new TwinVqDecoder(List.of(stereo),
                TwinVqDecoderOptions.builder().limits(limits).build()));
        assertThrows(MediaException.LimitExceeded.class, () -> new TwinVqDecoder(stereo,
                AudioDecoderOptions.builder().limits(limits).build()));
        try (var decoder = new TwinVqDecoder(stereo)) {
            assertEquals(ChannelLayout.STEREO, decoder.layout());
        }
    }

    @Test
    @DisplayName("the MP4 muxer refuses a configuration of another object type and times frames of 960 samples")
    void mp4Configuration() {
        var bad = synthetic(1, Codec.TWINVQ, new byte[]{0x12, 0x10});
        assertThrows(IllegalArgumentException.class, () -> new Mp4Muxer(MediaSink.memory()).addTrack(bad));
        var track = AudioTrack.builder().id(1).codec(Codec.TWINVQ).timeBase(new Rational(1, 16000))
                .codecConfig(new byte[]{0x3C, 0x0C}).build();
        var sink = MediaSink.memory();
        var random = new Random(144973);
        try (var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(track);
            muxer.writePacket(new Packet(1, monoFrame(random, 64, false), 0, 0, Packet.UNKNOWN_DURATION, true));
            muxer.finish();
        }
        var stream = TwinVqTestSupport.stream(sink.toByteArray());
        assertEquals(960, stream.units().getFirst().getFirst().duration());
        assertEquals(16000, stream.tracks().getFirst().sampleRate());
        try (var decoder = new TwinVqDecoder(stream.tracks().getFirst())) {
            assertEquals(960, decoder.frameSize());
            decoder.decode(stream.units().getFirst().getFirst(), buffer -> assertEquals(960, buffer.frames()));
        }
    }

    @Nested
    @DisplayName("damaged frames of the error protection tool")
    class DamagedProtection {
        private static final byte[] CONFIG = {(byte) 0xAA, (byte) 0x90, 0x00};

        private static final byte[] SOURCE = {(byte) 0xAA, (byte) 0x90, 0x40};

        private static void damage(byte[] config, List<byte[]> units, int blocks) {
            var track = synthetic(1, Codec.TWINVQ, config);
            var random = new Random(144974);
            try (var decoder = new TwinVqDecoder(track)) {
                for (var round = 0; round < 3; round++) {
                    for (var unit : units) {
                        var data = unit.clone();
                        if (round > 0) {
                            switch (random.nextInt(4)) {
                                case 0 -> {
                                    for (var i = 0; i < 16; i++) {
                                        data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                                    }
                                }
                                case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length));
                                case 2 -> Arrays.fill(data, (byte) 0xFF);
                                default -> data = new byte[0];
                            }
                        }
                        var count = new int[1];
                        try {
                            decoder.decode(new Packet(1, data, 0, 0, 1, true), buffer -> {
                                count[0]++;
                                for (var channel = 0; channel < buffer.channels(); channel++) {
                                    for (var i = 0; i < buffer.frames(); i++) {
                                        assertTrue(Float.isFinite(buffer.plane(channel)[i]), "finite output");
                                    }
                                }
                            });
                        } catch (MediaException.Malformed expected) {
                            assertTrue(round > 0, "an undamaged unit decodes: " + expected);
                            continue;
                        }
                        assertEquals(blocks, count[0], "the blocks of an EP frame");
                    }
                }
            }
        }

        @Test
        @DisplayName("epConfig 2 with concatenated frames decodes every block or rejects the unit as malformed")
        void unmapped() {
            var random = new Random(144975);
            var frames = new ArrayList<byte[]>();
            for (var i = 0; i < 9; i++) {
                frames.add(stereoFrame(random, 128));
            }
            var built = EpStreams.unmapped(CONFIG, frames, 3, random, 0);
            damage(built.config(), built.units(), 3);
        }

        @Test
        @DisplayName("epConfig 3 decodes every block or rejects the unit as malformed")
        void mapped() {
            var random = new Random(144976);
            var instances = new ArrayList<List<byte[]>>();
            for (var i = 0; i < 9; i++) {
                var side = stereoFrame(random, 20);
                side[1] &= (byte) 0x0F;
                var shape = new byte[108];
                random.nextBytes(shape);
                instances.add(List.of(side, shape));
            }
            var built = EpStreams.mapped(SOURCE, instances, random, 0);
            damage(built.config(), built.units(), 1);
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("defaults decode every layer under the default limits")
        void defaults() {
            var options = TwinVqDecoderOptions.defaults();
            assertTrue(options.maxLayer().isEmpty());
            assertSame(MediaLimits.defaults(), options.limits());
            assertSame(options, TwinVqDecoderOptions.defaults());
        }

        @Test
        @DisplayName("toBuilder keeps every setting and the setters validate")
        void builder() {
            var limits = MediaLimits.builder().maxChannels(4).build();
            var options = TwinVqDecoderOptions.builder().limits(limits).maxLayer(2).build();
            var copy = options.toBuilder().build();
            assertSame(limits, copy.limits());
            assertEquals(2, copy.maxLayer().orElseThrow());
            assertTrue(options.toBuilder().allLayers().build().maxLayer().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> TwinVqDecoderOptions.builder().maxLayer(-1));
            assertThrows(NullPointerException.class, () -> TwinVqDecoderOptions.builder().limits(null));
        }
    }

    @Test
    @DisplayName("the MP4 demuxer reports TwinVQ tracks, which the registry decodes")
    void registry() {
        var stream = TwinVqTestSupport.stream("tv00");
        var track = stream.tracks().getFirst();
        assertEquals(Codec.TWINVQ, track.codec());
        assertEquals(8000, track.sampleRate());
        assertEquals(ChannelLayout.MONO, track.layout().orElseThrow());
        assertTrue(AudioDecoders.isSupported(Codec.TWINVQ));
        try (var decoder = AudioDecoders.create(track)) {
            assertInstanceOf(TwinVqDecoder.class, decoder);
            assertEquals(8000, decoder.sampleRate());
            assertEquals(ChannelLayout.MONO, decoder.layout());
        }
        try (var decoder = new TwinVqDecoder(track, AudioDecoderOptions.defaults())) {
            assertEquals(1024, decoder.frameSize());
            assertTrue(decoder.options().maxLayer().isEmpty());
        }
    }

    @Test
    @DisplayName("the configuration parser reads ER TwinVQ fields and refuses other object types and channels")
    void config() {
        var config = TwinVqConfig.parse(new byte[]{(byte) 0xAB, 0x0D, 0x20});
        assertEquals(TwinVqConfig.AOT_ER_TWINVQ, config.objectType());
        assertEquals(24000, config.sampleRate());
        assertEquals(1, config.channels());
        assertEquals(960, config.frameLength());
        assertEquals(1, config.epConfig());
        assertTrue(config.errorResilient());
        var plain = TwinVqConfig.parse(new byte[]{0x3D, (byte) 0x88});
        assertEquals(8000, plain.sampleRate());
        assertEquals(1024, plain.frameLength());
        assertEquals(0, plain.epConfig());
        assertEquals("twinvq object type", assertThrows(MediaException.Unsupported.class,
                () -> TwinVqConfig.parse(new byte[]{0x12, 0x10})).format());
        assertEquals("twinvq channels", assertThrows(MediaException.Unsupported.class,
                () -> TwinVqConfig.parse(new byte[]{0x3D, (byte) 0x98})).format());
        assertThrows(MediaException.Malformed.class, () -> TwinVqConfig.parse(new byte[0]));
        assertThrows(MediaException.Malformed.class, () -> TwinVqConfig.parse(new byte[]{0x3E, (byte) 0x88}));
    }

    @Test
    @DisplayName("decodes a single-stream object packet by packet as the list decode does")
    void singlePackets() {
        var stream = TwinVqTestSupport.stream("tv00");
        var expected = decodeAll(stream, TwinVqDecoderOptions.defaults());
        try (var decoder = new TwinVqDecoder(stream.tracks().getFirst())) {
            var out = new float[expected[0].length];
            var position = new int[1];
            for (var packet : stream.units().getFirst()) {
                decoder.decode(packet, buffer -> {
                    assertEquals(1024, buffer.frames());
                    assertEquals(8000, buffer.sampleRate());
                    System.arraycopy(buffer.plane(0), 0, out, position[0], buffer.frames());
                    position[0] += buffer.frames();
                });
            }
            assertArrayEquals(expected[0], out);
        }
    }

    @Nested
    @DisplayName("layers")
    class Layers {
        @Test
        @DisplayName("each layer limit changes the signal, and a limit above the top layer decodes every layer")
        void limit() {
            var stream = TwinVqTestSupport.stream("tv24");
            var base = decodeAll(stream, TwinVqDecoderOptions.builder().maxLayer(0).build());
            var top = decodeAll(stream, TwinVqDecoderOptions.builder().maxLayer(1).build());
            var above = decodeAll(stream, TwinVqDecoderOptions.builder().maxLayer(5).build());
            var all = decodeAll(stream, TwinVqDecoderOptions.defaults());
            assertEquals(base[0].length, top[0].length);
            assertTrue(!Arrays.equals(base[0], top[0]), "the scalable layer adds to the signal");
            assertArrayEquals(top[0], above[0]);
            assertArrayEquals(top[0], all[0]);
        }

        @Test
        @DisplayName("a frame without its upper layers decodes the layers present")
        void missing() {
            var stream = TwinVqTestSupport.stream("tv24");
            var base = decodeAll(stream, TwinVqDecoderOptions.builder().maxLayer(0).build());
            try (var decoder = new TwinVqDecoder(stream.tracks(), TwinVqDecoderOptions.defaults())) {
                var position = new int[1];
                for (var frame = 0; frame < stream.frames(); frame++) {
                    decoder.decode(List.of(stream.units().getFirst().get(frame)), buffer -> {
                        for (var i = 0; i < buffer.frames(); i++) {
                            assertEquals(base[0][position[0] + i], buffer.plane(0)[i], "sample " + i);
                        }
                        position[0] += buffer.frames();
                    });
                }
                assertEquals(base[0].length, position[0]);
            }
        }

        @Test
        @DisplayName("an object whose categories sit in separate tracks needs both of them")
        void categories() {
            var stream = TwinVqTestSupport.stream("er_tv01_ep1");
            assertEquals(2, stream.tracks().size());
            assertThrows(MediaException.Malformed.class, () -> new TwinVqDecoder(stream.tracks().getFirst()));
            try (var decoder = new TwinVqDecoder(stream.tracks(), TwinVqDecoderOptions.defaults())) {
                assertEquals(ChannelLayout.STEREO, decoder.layout());
                var frames = new int[1];
                decoder.decode(stream.frame(0).subList(0, 1), buffer -> {
                    frames[0]++;
                    for (var channel = 0; channel < 2; channel++) {
                        for (var i = 0; i < buffer.frames(); i++) {
                            assertEquals(0f, buffer.plane(channel)[i]);
                        }
                    }
                });
                assertEquals(1, frames[0], "a frame lacking a category decodes to silence");
            }
        }
    }

    @Nested
    @DisplayName("invalid use")
    class InvalidUse {
        @Test
        @DisplayName("refuses tracks of other codecs, without a configuration, or of mismatched layers")
        void tracks() {
            var aac = AudioTrack.builder().codec(Codec.AAC).timeBase(new Rational(1, 8000)).build();
            assertThrows(IllegalArgumentException.class, () -> new TwinVqDecoder(aac));
            var bare = AudioTrack.builder().codec(Codec.TWINVQ).timeBase(new Rational(1, 8000)).build();
            assertThrows(MediaException.Malformed.class, () -> new TwinVqDecoder(bare));
            assertThrows(IllegalArgumentException.class, () -> new TwinVqDecoder(List.of(),
                    TwinVqDecoderOptions.defaults()));
            var first = bare.toBuilder().codecConfig(new byte[]{0x3D, (byte) 0x88}).build();
            var second = bare.toBuilder().codecConfig(new byte[]{0x3C, 0x08}).build();
            assertThrows(MediaException.Malformed.class, () -> new TwinVqDecoder(List.of(first, second),
                    TwinVqDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("a closed decoder refuses packets, and flush emits nothing")
        void closed() {
            var stream = TwinVqTestSupport.stream("tv00");
            var decoder = new TwinVqDecoder(stream.tracks().getFirst());
            decoder.flush(buffer -> {
                throw new AssertionError("flush emitted a block");
            });
            decoder.close();
            assertThrows(IllegalStateException.class,
                    () -> decoder.decode(stream.units().getFirst().getFirst(), buffer -> {
                    }));
            assertThrows(IllegalArgumentException.class, () -> new TwinVqDecoder(stream.tracks().getFirst())
                    .decode(List.of(), buffer -> {
                    }));
        }
    }

    @Test
    @DisplayName("damaged, truncated and empty access units decode to finite blocks or are rejected as malformed")
    void damaged() {
        for (var name : List.of("tv00", "tv24", "er_tv01_ep1")) {
            var stream = TwinVqTestSupport.stream(name);
            var random = new Random(name.hashCode());
            var decodedAfterDamage = 0;
            try (var decoder = new TwinVqDecoder(stream.tracks(), TwinVqDecoderOptions.defaults())) {
                for (var frame = 0; frame < Math.min(stream.frames(), 200); frame++) {
                    var packets = new ArrayList<Packet>();
                    var damagedFrame = frame % 3 == 1;
                    for (var packet : stream.frame(frame)) {
                        var data = packet.data().clone();
                        if (damagedFrame && data.length > 0) {
                            switch (random.nextInt(4)) {
                                case 0 -> data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                                case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length));
                                case 2 -> Arrays.fill(data, (byte) 0xFF);
                                default -> data = new byte[0];
                            }
                        }
                        packets.add(packet.withData(data));
                    }
                    try {
                        decoder.decode(packets, buffer -> {
                            for (var channel = 0; channel < buffer.channels(); channel++) {
                                for (var i = 0; i < buffer.frames(); i++) {
                                    assertTrue(Float.isFinite(buffer.plane(channel)[i]), name + " finite output");
                                }
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

    @Test
    @DisplayName("an empty access unit plays out the overlap, then silence, and the next frame decodes")
    void emptyUnit() {
        var stream = TwinVqTestSupport.stream("tv00");
        try (var decoder = new TwinVqDecoder(stream.tracks().getFirst())) {
            var energy = new double[3];
            var frame = new int[1];
            for (var i = 0; i < 12; i++) {
                decoder.decode(stream.units().getFirst().get(i), buffer -> {
                });
            }
            var empty = stream.units().getFirst().get(12).withData(new byte[0]);
            for (var i = 0; i < 2; i++) {
                decoder.decode(empty, buffer -> {
                    assertEquals(1024, buffer.frames());
                    for (var k = 0; k < buffer.frames(); k++) {
                        energy[frame[0]] += buffer.plane(0)[k] * buffer.plane(0)[k];
                    }
                    frame[0]++;
                });
            }
            assertEquals(0.0, energy[1], "the second empty unit is silent");
            decoder.decode(stream.units().getFirst().get(13), buffer -> assertEquals(1024, buffer.frames()));
        }
    }

    @Nested
    @DisplayName("the error protection tool on streams the suite lacks")
    class ErrorProtection {
        private static final TwinVqDecoderOptions OPTIONS = TwinVqDecoderOptions.defaults();

        private static TwinVqTestSupport.Stream built(AudioTrack track, EpStreams.Built built) {
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < built.units().size(); i++) {
                packets.add(new Packet(track.id(), built.units().get(i), i, i, 1, true));
            }
            return new TwinVqTestSupport.Stream(List.of(track.toBuilder().codecConfig(built.config()).build()),
                    List.of(packets));
        }

        @Test
        @DisplayName("decodes epConfig 3 frames of the epConfig 1 instances, with correctable errors, like epConfig 1")
        void mapped() {
            var instances = TwinVqTestSupport.stream("er_tv01_ep1");
            var expected = decodeAll(instances, OPTIONS);
            var perFrame = new ArrayList<List<byte[]>>();
            for (var frame = 0; frame < instances.frames(); frame++) {
                perFrame.add(List.of(instances.units().get(0).get(frame).data(),
                        instances.units().get(1).get(frame).data()));
            }
            var source = instances.tracks().getFirst().codecConfig().orElseThrow();
            for (var errors : new int[]{0, 3}) {
                var protection = EpStreams.mapped(source, perFrame, new Random(errors), errors);
                var decoded = decodeAll(built(instances.tracks().getFirst(), protection), OPTIONS);
                assertArrayEquals(expected[0], decoded[0], errors + " errors, left");
                assertArrayEquals(expected[1], decoded[1], errors + " errors, right");
            }
        }

        @Test
        @DisplayName("decodes frames concatenated three to an EP frame like epConfig 0")
        void concatenated() {
            var source = TwinVqTestSupport.stream("er_tv01_ep0");
            var expected = decodeAll(source, OPTIONS);
            var frames = new ArrayList<byte[]>();
            for (var packet : source.units().getFirst()) {
                frames.add(packet.data());
            }
            var config = source.tracks().getFirst().codecConfig().orElseThrow();
            var protection = EpStreams.unmapped(config, frames, 3, new Random(7), 0);
            var stream = built(source.tracks().getFirst(), protection);
            try (var decoder = new TwinVqDecoder(stream.tracks(), OPTIONS)) {
                var position = new int[1];
                for (var frame = 0; frame < stream.frames(); frame++) {
                    decoder.decode(stream.frame(frame), buffer -> {
                        assertEquals(1024, buffer.frames());
                        for (var channel = 0; channel < 2; channel++) {
                            for (var i = 0; i < 1024 && position[0] + i < expected[channel].length; i++) {
                                assertEquals(expected[channel][position[0] + i], buffer.plane(channel)[i]);
                            }
                        }
                        position[0] += 1024;
                    });
                }
                // the last EP frame repeats the last source frame to fill its three slots
                assertEquals((frames.size() + 2) / 3 * 3 * 1024, position[0]);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4", "fragmented"})
    @DisplayName("a stream remuxed to MP4 keeps its configuration and decodes as the original")
    void mp4RoundTrip(String kind) {
        var stream = TwinVqTestSupport.stream("tv00");
        var track = stream.tracks().getFirst();
        var sink = MediaSink.memory();
        try (var muxer = kind.equals("mp4") ? new Mp4Muxer(sink) : new FragmentedMp4Muxer(sink)) {
            muxer.addTrack(track);
            for (var packet : stream.units().getFirst()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var remuxed = TwinVqTestSupport.stream(sink.toByteArray());
        assertArrayEquals(track.codecConfig().orElseThrow(), remuxed.tracks().getFirst().codecConfig().orElseThrow());
        assertEquals(track.sampleRate(), remuxed.tracks().getFirst().sampleRate());
        var options = TwinVqDecoderOptions.defaults();
        assertArrayEquals(decodeAll(stream, options)[0], decodeAll(remuxed, options)[0]);
    }

    @Test
    @DisplayName("the audio reader seeks to the samples of a decode from the start")
    void seek() {
        var data = Fixtures.read(Conformance.fetchListed("mpeg4audio-tv00.mp4"));
        var collected = new ArrayList<float[]>();
        try (var reader = AudioReader.open(MediaSource.of(data))) {
            assertEquals(8000, reader.sampleRate());
            for (var block = reader.read(); block != null; block = reader.read()) {
                collected.add(Arrays.copyOf(block.plane(0), block.frames()));
            }
            var all = join(collected);
            assertNotEquals(0, all.length);
            for (var seconds : new int[]{3, 1}) {
                reader.seek(seconds * 1_000_000L);
                var seeked = new ArrayList<float[]>();
                for (var block = reader.read(); block != null; block = reader.read()) {
                    seeked.add(Arrays.copyOf(block.plane(0), block.frames()));
                }
                assertArrayEquals(Arrays.copyOfRange(all, seconds * 8000, all.length), join(seeked),
                        "from " + seconds + " s");
            }
        }
    }

    private static float[] join(List<float[]> blocks) {
        var length = 0;
        for (var block : blocks) {
            length += block.length;
        }
        var out = new float[length];
        var position = 0;
        for (var block : blocks) {
            System.arraycopy(block, 0, out, position, block.length);
            position += block.length;
        }
        return out;
    }
}
