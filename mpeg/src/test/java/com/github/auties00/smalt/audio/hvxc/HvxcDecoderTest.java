package com.github.auties00.smalt.audio.hvxc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.hvxc.internal.HvxcCore;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.aac.LoasMuxer;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the API of {@link HvxcDecoder} and {@link HvxcDecoderOptions} on the ISO/IEC 14496-26 smoke streams: the
 * codec registry, the options and their effect on the output length and the delay, the layer limit and missing
 * enhancement layers, closed decoders, invalid tracks, damaged access units and error concealment, MP4 and LOAS
 * round trips, and seeking through the audio reader. {@link HvxcConformanceTest} checks the decoded signal.
 */
@DisplayName("HvxcDecoder")
class HvxcDecoderTest {
    private static float[] raw(HvxcTestSupport.Stream stream, HvxcDecoderOptions options) {
        var out = new HvxcTestSupport.Samples();
        try (var decoder = new HvxcDecoder(stream.tracks(), options)) {
            for (var frame = 0; frame < stream.frames(); frame++) {
                decoder.decode(stream.frame(frame), buffer -> {
                    assertEquals(8000, buffer.sampleRate());
                    out.add(buffer.plane(0), buffer.frames());
                });
            }
        }
        return out.toArray();
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("defaults decode every layer at the original speed and pitch with the postfilter")
        void defaults() {
            var options = HvxcDecoderOptions.defaults();
            assertTrue(options.postFilter());
            assertFalse(options.lowDelay());
            assertEquals(1.0f, options.speedFactor());
            assertEquals(1.0f, options.pitchFactor());
            assertTrue(options.maxLayer().isEmpty());
            assertSame(options, HvxcDecoderOptions.defaults());
        }

        @Test
        @DisplayName("toBuilder keeps every setting and the setters validate")
        void builder() {
            var options = HvxcDecoderOptions.builder().postFilter(false).lowDelay(true).speedFactor(0.5f)
                    .pitchFactor(2f).maxLayer(0).build();
            var copy = options.toBuilder().build();
            assertFalse(copy.postFilter());
            assertTrue(copy.lowDelay());
            assertEquals(0.5f, copy.speedFactor());
            assertEquals(2f, copy.pitchFactor());
            assertEquals(0, copy.maxLayer().orElseThrow());
            assertTrue(options.toBuilder().allLayers().build().maxLayer().isEmpty());
            var builder = HvxcDecoderOptions.builder();
            assertThrows(IllegalArgumentException.class, () -> builder.maxLayer(-1));
            assertThrows(IllegalArgumentException.class, () -> builder.speedFactor(0.19f));
            assertThrows(IllegalArgumentException.class, () -> builder.speedFactor(5.01f));
            assertThrows(IllegalArgumentException.class, () -> builder.speedFactor(Float.NaN));
            assertThrows(IllegalArgumentException.class, () -> builder.pitchFactor(0.2f));
            assertThrows(IllegalArgumentException.class, () -> builder.pitchFactor(Float.POSITIVE_INFINITY));
            assertThrows(NullPointerException.class, () -> builder.limits(null));
            builder.speedFactor(HvxcDecoderOptions.MIN_SPEED).speedFactor(HvxcDecoderOptions.MAX_SPEED)
                    .pitchFactor(HvxcDecoderOptions.MIN_PITCH).pitchFactor(HvxcDecoderOptions.MAX_PITCH);
        }
    }

    @Test
    @DisplayName("the MP4 demuxer reports HVXC tracks, which the registry decodes")
    void registry() {
        var stream = HvxcTestSupport.stream("hv01");
        var track = stream.tracks().getFirst();
        assertEquals(Codec.HVXC, track.codec());
        assertEquals(8000, track.sampleRate());
        assertEquals(ChannelLayout.MONO, track.layout().orElseThrow());
        assertTrue(AudioDecoders.isSupported(Codec.HVXC));
        try (var decoder = AudioDecoders.create(track)) {
            assertInstanceOf(HvxcDecoder.class, decoder);
            assertEquals(8000, decoder.sampleRate());
            assertEquals(ChannelLayout.MONO, decoder.layout());
        }
        try (var decoder = new HvxcDecoder(track, AudioDecoderOptions.defaults())) {
            assertTrue(decoder.options().postFilter());
            assertEquals(80, decoder.delay());
            assertEquals(1, decoder.layers());
        }
    }

    @Test
    @DisplayName("the public options without the postfilter decode as the conformance procedure's test mode 1")
    void publicMatchesTestMode() {
        var stream = HvxcTestSupport.stream("hv01");
        var options = HvxcDecoderOptions.builder().postFilter(false).lowDelay(true).speedFactor(0.75f)
                .pitchFactor(1.6f).build();
        assertArrayEquals(HvxcTestSupport.decode(stream, HvxcCore.TEST_NO_POSTFILTER, true, 0.75f, 1.6f, -1),
                HvxcTestSupport.decode(stream, options));
    }

    @ParameterizedTest(name = "speed {0}")
    @ValueSource(floats = {0.2f, 0.75f, 1f, 1.5f, 5f})
    @DisplayName("the speed change scales the number of samples a frame decodes to, in blocks of 160")
    void speed(float speed) {
        var stream = HvxcTestSupport.stream("hv03");
        var options = HvxcDecoderOptions.builder().speedFactor(speed).build();
        var samples = raw(stream, options).length;
        assertEquals(0, samples % 160);
        // the decoder steps its position by the factor in single precision, which drifts by a few frames over the
        // thousand of a factor of 0.2
        var expected = stream.frames() / (double) speed;
        assertTrue(Math.abs(samples / 160 - expected) <= 1 + 0.005 * expected, samples / 160 + " blocks for "
                + expected);
    }

    @Test
    @DisplayName("the low delay mode shortens the delay by 20 samples and changes the synthesis")
    void lowDelay() {
        var stream = HvxcTestSupport.stream("hv01");
        try (var normal = new HvxcDecoder(stream.tracks(), HvxcDecoderOptions.defaults());
             var low = new HvxcDecoder(stream.tracks(), HvxcDecoderOptions.builder().lowDelay(true).build())) {
            assertEquals(80, normal.delay());
            assertEquals(60, low.delay());
        }
        var normal = raw(stream, HvxcDecoderOptions.defaults());
        var low = raw(stream, HvxcDecoderOptions.builder().lowDelay(true).build());
        assertEquals(normal.length, low.length);
        assertFalse(Arrays.equals(normal, low));
    }

    @Test
    @DisplayName("the postfilter changes the speech and keeps it within full scale")
    void postFilter() {
        var stream = HvxcTestSupport.stream("hv07");
        var filtered = raw(stream, HvxcDecoderOptions.defaults());
        var plain = raw(stream, HvxcDecoderOptions.builder().postFilter(false).build());
        assertEquals(plain.length, filtered.length);
        assertFalse(Arrays.equals(plain, filtered));
        for (var sample : filtered) {
            assertTrue(Math.abs(sample) <= 1f, "sample " + sample);
        }
    }

    @Test
    @DisplayName("decoders of the same stream and options produce the same output")
    void deterministic() {
        var stream = HvxcTestSupport.stream("hv02");
        assertArrayEquals(raw(stream, HvxcDecoderOptions.defaults()), raw(stream, HvxcDecoderOptions.defaults()));
    }

    @Nested
    @DisplayName("layers")
    class Layers {
        @Test
        @DisplayName("the layer limit decodes the base layer alone, as frames without the enhancement layer do")
        void limit() {
            var stream = HvxcTestSupport.stream("er_hv10_ep0");
            assertEquals(2, stream.tracks().size());
            var options = HvxcDecoderOptions.builder().postFilter(false).build();
            try (var both = new HvxcDecoder(stream.tracks(), options);
                 var base = new HvxcDecoder(stream.tracks(), options.toBuilder().maxLayer(0).build())) {
                assertEquals(2, both.layers());
                assertEquals(1, base.layers());
            }
            var limited = HvxcTestSupport.decode(stream, options.toBuilder().maxLayer(0).build());
            var baseOnly = new HvxcTestSupport.Stream(List.of(stream.tracks().getFirst()),
                    List.of(stream.units().getFirst()));
            assertArrayEquals(limited, HvxcTestSupport.decode(baseOnly, options));
            assertFalse(Arrays.equals(limited, HvxcTestSupport.decode(stream, options)));
        }

        @Test
        @DisplayName("a frame without its enhancement layer decodes the base layer and the decoder carries on")
        void missing() {
            var stream = HvxcTestSupport.stream("er_hv10_ep0");
            var options = HvxcDecoderOptions.builder().postFilter(false).build();
            try (var decoder = new HvxcDecoder(stream.tracks(), options)) {
                var blocks = new int[1];
                for (var frame = 0; frame < stream.frames(); frame++) {
                    var packets = stream.frame(frame);
                    decoder.decode(frame % 3 == 0 ? packets.subList(0, 1) : packets, buffer -> {
                        assertEquals(160, buffer.frames());
                        blocks[0]++;
                    });
                }
                assertEquals(stream.frames(), blocks[0]);
            }
        }

        @Test
        @DisplayName("an object whose categories sit in separate tracks needs all of them")
        void categories() {
            var stream = HvxcTestSupport.stream("er_hv04_ep1");
            assertEquals(5, stream.tracks().size());
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(stream.tracks().getFirst()));
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(stream.tracks().subList(0, 3),
                    HvxcDecoderOptions.defaults()));
            try (var decoder = new HvxcDecoder(stream.tracks(), HvxcDecoderOptions.defaults())) {
                var emitted = new int[1];
                decoder.decode(stream.units().getFirst().getFirst(), buffer -> emitted[0]++);
                assertEquals(0, emitted[0], "the first category alone decodes nothing");
            }
        }
    }

    @Nested
    @DisplayName("invalid use")
    class Invalid {
        @Test
        @DisplayName("refuses tracks of other codecs, without a configuration, of an enhancement layer or not HVXC")
        void tracks() {
            var celp = AudioTrack.builder().codec(Codec.CELP).timeBase(new Rational(1, 8000)).build();
            assertThrows(IllegalArgumentException.class, () -> new HvxcDecoder(celp));
            var track = AudioTrack.builder().codec(Codec.HVXC).timeBase(new Rational(1, 8000)).build();
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(track));
            assertThrows(IllegalArgumentException.class, () -> new HvxcDecoder(List.of(),
                    HvxcDecoderOptions.defaults()));
            var enhancement = HvxcTestSupport.stream("er_hv10_ep0").tracks().get(1);
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(enhancement));
            // a CELP base layer configuration, multi-pulse at 8 kHz
            assertThrows(MediaException.Unsupported.class, () -> new HvxcDecoder(track.toBuilder()
                    .codecConfig(new byte[]{0x45, (byte) 0x8C, 0x00, 0x00}).build()));
            // HVXC at 16 kHz, and in stereo
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(track.toBuilder()
                    .codecConfig(new byte[]{0x4C, 0x0C, 0x00}).build()));
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(track.toBuilder()
                    .codecConfig(new byte[]{0x4D, (byte) 0x94, 0x00}).build()));
            // the reserved HVXCrateMode 3
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(track.toBuilder()
                    .codecConfig(new byte[]{0x4D, (byte) 0x8D, (byte) 0x80}).build()));
            // a base layer configuration cut before its extensionFlag, and an empty one
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(track.toBuilder()
                    .codecConfig(new byte[]{0x4D, (byte) 0x8C}).build()));
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(track.toBuilder()
                    .codecConfig(new byte[0]).build()));
            // a 2 kbit/s HVXC base layer with an ER HVXC enhancement layer, and with a second base layer
            var base = track.toBuilder().codecConfig(new byte[]{0x4D, (byte) 0x8C, 0x00}).build();
            try (var alone = new HvxcDecoder(base)) {
                assertEquals(1, alone.layers());
            }
            var erEnhancement = track.toBuilder().codecConfig(new byte[]{(byte) 0xCD, (byte) 0x88}).build();
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(List.of(base, erEnhancement),
                    HvxcDecoderOptions.defaults()));
            assertThrows(MediaException.Malformed.class, () -> new HvxcDecoder(List.of(base, base),
                    HvxcDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("a closed decoder refuses packets, and flush emits nothing")
        void closed() {
            var stream = HvxcTestSupport.stream("hv01");
            var decoder = new HvxcDecoder(stream.tracks().getFirst());
            decoder.flush(buffer -> {
                throw new AssertionError("flush emitted a block");
            });
            decoder.close();
            assertThrows(IllegalStateException.class, () -> decoder.decode(stream.units().getFirst().getFirst(),
                    buffer -> {
                    }));
            assertThrows(IllegalStateException.class, () -> decoder.decode(stream.frame(0), buffer -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> new HvxcDecoder(stream.tracks().getFirst())
                    .decode(List.of(), buffer -> {
                    }));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"hv01", "hv02", "hv07", "er_hv04_ep1", "er_hv09_ep1", "er_hv03_ep2_crc"})
    @DisplayName("damaged, truncated and empty access units decode to finite blocks within full scale")
    void damaged(String name) {
        var stream = HvxcTestSupport.stream(name);
        var random = new Random(name.hashCode());
        try (var decoder = new HvxcDecoder(stream.tracks(), HvxcDecoderOptions.defaults())) {
            for (var frame = 0; frame < stream.frames(); frame++) {
                var packets = new ArrayList<Packet>();
                for (var packet : stream.frame(frame)) {
                    var data = packet.data().clone();
                    switch (frame % 5) {
                        case 1 -> {
                            for (var i = 0; i < data.length; i++) {
                                data[i] ^= (byte) random.nextInt(256);
                            }
                        }
                        case 2 -> data = Arrays.copyOf(data, data.length / 2);
                        case 3 -> data = new byte[0];
                        default -> {
                        }
                    }
                    packets.add(new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(),
                            true));
                }
                decoder.decode(packets, buffer -> {
                    for (var i = 0; i < buffer.frames(); i++) {
                        var sample = buffer.plane(0)[i];
                        assertTrue(Float.isFinite(sample) && Math.abs(sample) <= 1f, name + " sample " + sample);
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("the error concealment mutes frames whose classes fail their CRC and recovers after them")
    void concealment() {
        var stream = HvxcTestSupport.stream("er_hv03_ep2_crc");
        var options = HvxcDecoderOptions.builder().postFilter(false).build();
        var clean = HvxcTestSupport.decode(stream, options);
        var damaged = new ArrayList<Packet>();
        var units = stream.units().getFirst();
        for (var i = 0; i < units.size(); i++) {
            var packet = units.get(i);
            var data = packet.data().clone();
            if (i >= 40 && i < 46) {
                for (var b = 0; b < data.length; b++) {
                    data[b] = (byte) ~data[b];
                }
            }
            damaged.add(new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true));
        }
        var concealed = HvxcTestSupport.decode(new HvxcTestSupport.Stream(stream.tracks(), List.of(damaged)), options);
        assertEquals(clean.length, concealed.length);
        // an EP frame carries two source frames of 160 samples; the six damaged EP frames end at sample 46 * 320
        var start = 40 * 320;
        var end = 46 * 320;
        assertArrayEquals(Arrays.copyOf(clean, start - 400), Arrays.copyOf(concealed, start - 400));
        var cleanEnergy = 0.0;
        var concealedEnergy = 0.0;
        for (var i = start + 640; i < end; i++) {
            cleanEnergy += clean[i] * clean[i];
            concealedEnergy += concealed[i] * concealed[i];
        }
        assertTrue(concealedEnergy < cleanEnergy * 0.5, "the concealed frames fade out: " + concealedEnergy + " of "
                + cleanEnergy);
        var tail = 0.0;
        for (var i = end + 3200; i < clean.length; i++) {
            tail = Math.max(tail, Math.abs(clean[i] - concealed[i]));
        }
        assertTrue(tail < 0.02, "the decoder recovers: " + tail);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4", "fragmented"})
    @DisplayName("a stream remuxed to MP4 keeps its configuration and decodes as the original")
    void mp4RoundTrip(String kind) {
        var stream = HvxcTestSupport.stream("hv07");
        var track = stream.tracks().getFirst();
        var sink = MediaSink.memory();
        try (var muxer = kind.equals("mp4") ? new Mp4Muxer(sink) : new FragmentedMp4Muxer(sink)) {
            muxer.addTrack(track);
            for (var packet : stream.units().getFirst()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var remuxed = HvxcTestSupport.stream(sink.toByteArray());
        assertEquals(Codec.HVXC, remuxed.tracks().getFirst().codec());
        assertArrayEquals(track.codecConfig().orElseThrow(), remuxed.tracks().getFirst().codecConfig().orElseThrow());
        assertArrayEquals(raw(stream, HvxcDecoderOptions.defaults()), raw(remuxed, HvxcDecoderOptions.defaults()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"hv01", "hv07", "er_hv10_ep0", "er_hv03_ep2_crc"})
    @DisplayName("decodes the base layer of a stream remuxed to LOAS as from its MP4 file, error protected or not")
    void loasRoundTrip(String name) {
        var stream = HvxcTestSupport.stream(name);
        var track = stream.tracks().getFirst();
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
            assertEquals(Codec.HVXC, read.codec());
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                assertEquals(160L * packets.size(), packet.pts());
                packets.add(packet);
            }
            var remuxed = new HvxcTestSupport.Stream(List.of(read), List.of(packets));
            var base = new HvxcTestSupport.Stream(List.of(track), List.of(stream.units().getFirst()));
            assertArrayEquals(raw(base, HvxcDecoderOptions.defaults()), raw(remuxed, HvxcDecoderOptions.defaults()));
        }
    }

    @Test
    @DisplayName("the audio reader seeks to the samples of a decode from the start")
    void seek() {
        var data = Fixtures.read(Conformance.fetchListed("mpeg4audio-hv03.mp4"));
        var linear = new HvxcTestSupport.Samples();
        try (var reader = AudioReader.open(MediaSource.of(data))) {
            assertEquals(8000, reader.sampleRate());
            for (var block = reader.read(); block != null; block = reader.read()) {
                linear.add(block.plane(0), block.frames());
            }
            var all = linear.toArray();
            assertEquals(160 * HvxcTestSupport.stream(data).frames(), all.length);
            for (var seconds : new int[]{3, 1}) {
                reader.seek(seconds * 1_000_000L);
                var seeked = new HvxcTestSupport.Samples();
                for (var block = reader.read(); block != null; block = reader.read()) {
                    seeked.add(block.plane(0), block.frames());
                }
                assertArrayEquals(Arrays.copyOfRange(all, seconds * 8000, all.length), seeked.toArray(),
                        "from " + seconds + " s");
            }
        }
    }
}
