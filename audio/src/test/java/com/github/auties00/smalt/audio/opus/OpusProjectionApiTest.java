package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the public surface of mapping family 3 (RFC 8486 projection): the options that select it, the
 * identification header, the separation of every ambisonic channel through the mixing and demixing matrices
 * (each input channel carries its own tone), the Ogg, Matroska and MP4 bindings of the header, and the
 * decoder's handling of corrupt projection headers and packets.
 */
class OpusProjectionApiTest {
    private static ChannelLayout unknown(int channels) {
        return new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
    }

    private static OpusEncoder encoder(int channels, int bitrate) {
        return new OpusEncoder(OpusEncoderOptions.builder().format(48000, unknown(channels))
                .channelMapping(OpusChannelMapping.AMBISONIC_PROJECTION).bitrate(bitrate).build());
    }

    @Nested
    @DisplayName("OpusEncoderOptions")
    class Options {
        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {4, 6, 9, 11, 16, 18, 25, 27, 36, 38})
        @DisplayName("accepts orders 1 to 5 with or without a non-diegetic pair")
        void accepts(int channels) {
            var options = OpusEncoderOptions.builder().format(48000, unknown(channels))
                    .channelMapping(OpusChannelMapping.AMBISONIC_PROJECTION).build();
            assertEquals(OpusChannelMapping.AMBISONIC_PROJECTION, options.channelMapping());
            assertEquals(OpusChannelMapping.AMBISONIC_PROJECTION, options.toBuilder().build().channelMapping());
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 3, 5, 8, 10, 12, 49, 51, 227})
        @DisplayName("rejects counts that are no projection order from 1 to 5")
        void rejects(int channels) {
            var builder = OpusEncoderOptions.builder().format(48000, unknown(channels))
                    .channelMapping(OpusChannelMapping.AMBISONIC_PROJECTION);
            assertThrows(IllegalArgumentException.class, builder::build);
        }
    }

    @ParameterizedTest(name = "{0} channels")
    @ValueSource(ints = {4, 6, 9, 11, 16, 18, 25, 27, 36, 38})
    @DisplayName("declares the streams, the demixing matrix and its gain, and separates every channel")
    void separation(int channels) {
        var encoder = encoder(channels, 96000 * channels);
        assertEquals((channels + 1) / 2, encoder.streamCount());
        var bytes = encoder.codecConfig().orElseThrow();
        var head = OpusHead.parse(bytes, MediaLimits.DEFAULT);
        assertEquals(3, head.mappingFamily());
        assertEquals(channels, head.channels());
        assertEquals(channels / 2, head.coupledCount());
        assertEquals(21 + 2 * channels * channels, bytes.length);
        var order = (int) Math.sqrt(channels) - 1;
        assertEquals(order == 2 ? 3050 : 0, head.outputGain());
        var layout = unknown(channels);
        var input = OpusMultistreamApiTest.tones(layout, 48000, 48000);
        var packets = OpusMultistreamApiTest.encode(encoder, input);
        var decoder = new OpusDecoder(OpusMultistreamApiTest.track(bytes, 48000));
        var components = (order + 1) * (order + 1);
        assertEquals(channels == components ? ChannelLayout.ofAmbisonics(order) : ChannelLayout.ofAmbisonics(
                Ambisonics.of(order), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT)), decoder.layout());
        var decoded = OpusMultistreamApiTest.decode(decoder, packets);
        assertEquals(48000, decoded[0].length, 960);
        OpusMultistreamApiTest.assertRouted(layout, decoder.layout(), decoded, 48000);
    }

    @Test
    @DisplayName("the 16-bit and float per-frame paths agree, and reset restarts every stream")
    void perFrame() {
        var channels = 9;
        var input = OpusMultistreamApiTest.tones(unknown(channels), 48000, 960 * 20);
        var floats = new float[960 * channels];
        var shorts = new short[960 * channels];
        var a = encoder(channels, 256000);
        var b = encoder(channels, 256000);
        a.setLsbDepth(16);
        var packetA = new byte[channels * 1500];
        var packetB = new byte[channels * 1500];
        var first = new byte[0];
        for (var frame = 0; frame < 20; frame++) {
            for (var i = 0; i < 960; i++) {
                for (var c = 0; c < channels; c++) {
                    var value = (short) Math.round(input[c][frame * 960 + i] * 32767);
                    shorts[i * channels + c] = value;
                    floats[i * channels + c] = value / 32768f;
                }
            }
            var lengthA = a.encode(floats, 0, 960, packetA, 0, packetA.length);
            var lengthB = b.encode(shorts, 0, 960, packetB, 0, packetB.length);
            assertArrayEquals(Arrays.copyOf(packetA, lengthA), Arrays.copyOf(packetB, lengthB), "frame " + frame);
            if (frame == 0) {
                first = Arrays.copyOf(packetA, lengthA);
            }
            if (frame == 10) {
                a.reset();
                b.reset();
            }
        }
        var fresh = encoder(channels, 256000);
        for (var i = 0; i < 960; i++) {
            for (var c = 0; c < channels; c++) {
                floats[i * channels + c] = (short) Math.round(input[c][i] * 32767) / 32768f;
            }
        }
        var length = fresh.encode(floats, 0, 960, packetA, 0, packetA.length);
        assertArrayEquals(Arrays.copyOf(packetA, length), first);
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        private static AudioTrack track(OpusEncoder encoder) {
            return AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(encoder.layout()).codecConfig(encoder.codecConfig().orElseThrow()).build();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ogg", "matroska", "mp4"})
        @DisplayName("keep the demixing matrix of the OpusHead and the decoded samples")
        void roundTrip(String container) {
            var channels = 11;
            var encoder = encoder(channels, 320000);
            var packets = OpusMultistreamApiTest.encode(encoder, OpusMultistreamApiTest.tones(unknown(channels), 48000,
                    48000));
            var sink = MediaSink.memory();
            try (Muxer muxer = switch (container) {
                case "ogg" -> new OggMuxer(sink);
                case "matroska" -> new MatroskaMuxer(sink);
                default -> new Mp4Muxer(sink);
            }) {
                muxer.addTrack(track(encoder));
                for (var packet : packets) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var file = sink.toByteArray();
            com.github.auties00.smalt.testkit.validate.Validators.assertValid("projection-" + container + ".bin", file);
            try (Demuxer demuxer = switch (container) {
                case "ogg" -> new OggDemuxer(MediaSource.of(file));
                case "matroska" -> new MatroskaDemuxer(MediaSource.of(file));
                default -> new Mp4Demuxer(MediaSource.of(file));
            }) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var head = OpusHead.parse(track.codecConfig().orElseThrow(), MediaLimits.DEFAULT);
                var expected = OpusHead.parse(encoder.codecConfig().orElseThrow(), MediaLimits.DEFAULT);
                assertArrayEquals(expected.demixing(), head.demixing());
                assertEquals(expected.outputGain(), head.outputGain());
                assertEquals(expected.streamCount(), head.streamCount());
                assertEquals(expected.coupledCount(), head.coupledCount());
                var read = new ArrayList<Packet>();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    read.add(packet);
                }
                var direct = OpusMultistreamApiTest.decode(new OpusDecoder(track(encoder)), packets);
                var demuxed = OpusMultistreamApiTest.decode(new OpusDecoder(track), read);
                var frames = Math.min(direct[0].length, demuxed[0].length);
                assertTrue(frames >= 48000 - 960, "decoded " + frames + " frames");
                for (var c = 0; c < channels; c++) {
                    assertArrayEquals(Arrays.copyOf(direct[c], frames), Arrays.copyOf(demuxed[c], frames),
                            "channel " + c);
                }
            }
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("decodes any demixing matrix, more output than decoded channels included")
        void anyMatrix() {
            var random = new Random(5);
            var encoder = encoder(4, 128000);
            var packets = OpusMultistreamApiTest.encode(encoder, OpusMultistreamApiTest.tones(unknown(4), 48000, 9600));
            for (var trial = 0; trial < 20; trial++) {
                var outputs = 1 + random.nextInt(8);
                var matrix = new short[outputs * 4];
                for (var i = 0; i < matrix.length; i++) {
                    matrix[i] = (short) random.nextInt();
                }
                var head = OpusHead.projection(outputs, 312, 48000, 2, 2, matrix, 0).toBytes();
                var decoder = new OpusDecoder(OpusMultistreamApiTest.track(head, 48000));
                assertEquals(outputs, decoder.layout().channels());
                var pcm = new short[960 * outputs];
                for (var packet : packets) {
                    assertEquals(960, decoder.decode(packet.data(), 0, packet.data().length, pcm, 0, 960));
                }
                var stereo = new OpusDecoder(OpusMultistreamApiTest.track(head, 48000),
                        OpusDecoderOptions.builder().channels(2).build());
                var block = OpusMultistreamApiTest.decode(stereo, packets);
                for (var plane : block) {
                    for (var sample : plane) {
                        assertTrue(Float.isFinite(sample));
                    }
                }
            }
        }

        @Test
        @DisplayName("reports corrupt and truncated projection packets as malformed")
        void corruptPackets() {
            var encoder = encoder(9, 192000);
            var packets = OpusMultistreamApiTest.encode(encoder, OpusMultistreamApiTest.tones(unknown(9), 48000, 9600));
            var head = encoder.codecConfig().orElseThrow();
            var random = new Random(11);
            var pcm = new float[5760 * 9];
            for (var trial = 0; trial < 300; trial++) {
                var decoder = new OpusDecoder(OpusMultistreamApiTest.track(head, 48000));
                var data = packets.get(random.nextInt(packets.size())).data().clone();
                if (trial % 3 == 0) {
                    data = Arrays.copyOf(data, random.nextInt(data.length));
                } else {
                    for (var i = 0; i < 1 + random.nextInt(8); i++) {
                        data[random.nextInt(data.length)] = (byte) random.nextInt();
                    }
                }
                if (data.length == 0) {
                    continue;
                }
                try {
                    var samples = decoder.decode(data, 0, data.length, pcm, 0, 5760);
                    assertTrue(samples > 0 && samples <= 5760);
                } catch (MediaException.Malformed | IllegalArgumentException expected) {
                    // a corrupt packet may be rejected
                }
                var packet = new Packet(0, data, 0, 0, 960, true);
                try {
                    decoder.decode(packet, buffer -> assertTrue(buffer.frames() > 0));
                } catch (MediaException.Malformed expected) {
                    // a corrupt packet may be rejected
                }
            }
        }

        @Test
        @DisplayName("rejects corrupt projection headers")
        void corruptHeaders() {
            var head = encoder(4, 128000).codecConfig().orElseThrow();
            assertThrows(MediaException.Malformed.class,
                    () -> new OpusDecoder(OpusMultistreamApiTest.track(Arrays.copyOf(head, head.length - 2), 48000)));
            var zeroStreams = head.clone();
            zeroStreams[19] = 0;
            assertThrows(MediaException.Malformed.class,
                    () -> new OpusDecoder(OpusMultistreamApiTest.track(zeroStreams, 48000)));
            var tooManyCoupled = head.clone();
            tooManyCoupled[20] = 3;
            assertThrows(MediaException.Malformed.class,
                    () -> new OpusDecoder(OpusMultistreamApiTest.track(tooManyCoupled, 48000)));
            var limited = OpusDecoderOptions.builder().limits(MediaLimits.builder().maxChannels(3).build()).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> new OpusDecoder(OpusMultistreamApiTest.track(head, 48000), limited));
        }
    }
}
