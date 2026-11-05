package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the multichannel extension decoding of {@link Mp3Decoder} on synthetic streams ({@link Mp2MultichannelStreams}):
 * the output layout of every channel configuration, extension frames, the downmix identity of the base channels,
 * the concealment of a frame whose extension fails, the multichannel option and the channel limit, and robustness
 * against mutated streams.
 */
class Mp2MultichannelTest {
    private static final AudioTrack TRACK = AudioTrack.builder().codec(Codec.MP2).timeBase(Rational.timeBaseOf(48000))
            .sampleRate(48000).layout(ChannelLayout.STEREO).build();

    /** A decoded stream: the buffers' layouts, one plane per output channel and the layout the decoder reports. */
    private record Decoded(List<ChannelLayout> layouts, float[][] planes, long concealed, ChannelLayout reported) {
    }

    private static Decoded decode(List<byte[]> packets, Mp3DecoderOptions options, int corruptPacket) {
        var decoder = new Mp3Decoder(TRACK, options);
        var layouts = new ArrayList<ChannelLayout>();
        var planes = new ArrayList<float[]>();
        var length = new int[1];
        for (var i = 0; i < packets.size(); i++) {
            var packet = new Packet(0, packets.get(i), i * 1152L, i * 1152L, 1152, true);
            if (i == corruptPacket) {
                packet = packet.withFlags(EnumSet.of(PacketFlag.KEY, PacketFlag.CORRUPT));
            }
            decoder.decode(packet, buffer -> append(buffer, layouts, planes, length));
        }
        return new Decoded(layouts, planes.toArray(float[][]::new), decoder.concealedFrames(), decoder.layout());
    }

    private static void append(AudioBuffer buffer, List<ChannelLayout> layouts, List<float[]> planes, int[] length) {
        layouts.add(buffer.layout());
        for (var c = 0; c < buffer.channels(); c++) {
            if (planes.size() <= c) {
                planes.add(new float[0]);
            }
            var plane = planes.get(c);
            var grown = Arrays.copyOf(plane, length[0] + buffer.frames());
            System.arraycopy(buffer.plane(c), 0, grown, length[0], buffer.frames());
            planes.set(c, grown);
        }
        length[0] += buffer.frames();
    }

    private static double energy(float[] plane, int from, int to) {
        var sum = 0.0;
        for (var i = from; i < to; i++) {
            sum += plane[i] * plane[i];
        }
        return sum;
    }

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of(new Mp2MultichannelStreams.Configuration(1, 2, true, 0), ChannelLayout.SURROUND_5_1),
                Arguments.of(new Mp2MultichannelStreams.Configuration(1, 2, false, 1),
                        ChannelLayout.defaultFor(5)),
                Arguments.of(new Mp2MultichannelStreams.Configuration(1, 2, false, 2),
                        ChannelLayout.defaultFor(5)),
                Arguments.of(new Mp2MultichannelStreams.Configuration(1, 1, false, 0),
                        new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                                Speaker.BACK_CENTER))),
                Arguments.of(new Mp2MultichannelStreams.Configuration(0, 2, true, 0),
                        new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY,
                                Speaker.BACK_LEFT, Speaker.BACK_RIGHT))),
                Arguments.of(new Mp2MultichannelStreams.Configuration(0, 1, false, 0),
                        new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER))),
                Arguments.of(new Mp2MultichannelStreams.Configuration(1, 0, false, 3),
                        new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER))),
                Arguments.of(new Mp2MultichannelStreams.Configuration(0, 0, true, 0),
                        new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY))));
    }

    @Nested
    @DisplayName("channel configurations")
    class Configurations {
        @ParameterizedTest(name = "{1}")
        @MethodSource("com.github.auties00.smalt.audio.mp3.Mp2MultichannelTest#configurations")
        @DisplayName("every configuration decodes to its layout, in the frame and through extension frames alike")
        void layouts(Mp2MultichannelStreams.Configuration configuration, ChannelLayout expected) {
            for (var extension : new boolean[]{false, true}) {
                var decoded = decode(Mp2MultichannelStreams.build(configuration, 6, 11, extension),
                        Mp3DecoderOptions.defaults(), -1);
                assertEquals(6, decoded.layouts().size());
                for (var layout : decoded.layouts()) {
                    assertEquals(expected, layout);
                }
                assertEquals(expected, decoded.reported(), "the layout the decoder reports");
                assertEquals(0, decoded.concealed());
                for (var plane : decoded.planes()) {
                    assertEquals(6 * 1152, plane.length);
                    assertTrue(energy(plane, 0, plane.length) > 0, "a silent channel in " + expected);
                }
            }
        }

        @Test
        @DisplayName("the base channels are the procedure 0 downmix of the decoded left, center and surround")
        void downmixIdentity() {
            var packets = Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 8, 3, false);
            var full = decode(packets, Mp3DecoderOptions.defaults(), -1);
            var base = decode(packets, Mp3DecoderOptions.builder().multichannel(false).build(), -1);
            for (var layout : base.layouts()) {
                assertEquals(ChannelLayout.STEREO, layout);
            }
            var a = 1 / (1 + Math.sqrt(2));
            var b = a / Math.sqrt(2);
            for (var side = 0; side < 2; side++) {
                var lo = base.planes()[side];
                var left = full.planes()[side];
                var center = full.planes()[2];
                var surround = full.planes()[4 + side];
                var error = 0.0;
                var signal = 0.0;
                for (var i = 0; i < lo.length; i++) {
                    var mix = a * left[i] + b * center[i] + b * surround[i];
                    error += (lo[i] - mix) * (lo[i] - mix);
                    signal += lo[i] * lo[i];
                }
                assertTrue(signal > 0 && error < signal * 1e-8, "side " + side + ": " + error / signal);
            }
        }

        @Test
        @DisplayName("two decoders of a stream with extension frames produce the same samples")
        void deterministic() {
            var packets = Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 4, 5, true);
            var first = decode(packets, Mp3DecoderOptions.defaults(), -1);
            var second = decode(packets, Mp3DecoderOptions.defaults(), -1);
            for (var c = 0; c < first.planes().length; c++) {
                assertArrayEquals(first.planes()[c], second.planes()[c]);
            }
        }
    }

    @Nested
    @DisplayName("damaged extensions")
    class Damaged {
        /** Flips a bit of a frame's extension header, after the base audio data, so that its CRC fails. */
        private static List<byte[]> damage(List<byte[]> packets, int frame) {
            var copy = new ArrayList<>(packets);
            var data = copy.get(frame).clone();
            var decoder = new Layer12();
            var bits = new BitReader();
            bits.init(data, 4, data.length - 4);
            decoder.readScaleInfo(com.github.auties00.smalt.internal.header.MpegAudioHeader.read(data, 0), bits);
            var granule = new float[2 * 576];
            for (var part = 0; part < 3; part++) {
                decoder.dequantizeGranule(granule, 0, bits, 3);
            }
            // the audio_mix bit of mc_header, the seventh bit of the extension
            var position = 4 * 8 + bits.position + 6;
            data[position >> 3] ^= (byte) (0x80 >>> (position & 7));
            copy.set(frame, data);
            return copy;
        }

        @Test
        @DisplayName("a failing extension CRC in a multichannel stream conceals the frame in the multichannel layout")
        void concealed() {
            var packets = Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 10, 7, false);
            var decoded = decode(damage(packets, 5), Mp3DecoderOptions.defaults(), -1);
            assertEquals(1, decoded.concealed());
            for (var layout : decoded.layouts()) {
                assertEquals(ChannelLayout.SURROUND_5_1, layout);
            }
            var clean = decode(packets, Mp3DecoderOptions.defaults(), -1);
            var center = decoded.planes()[2];
            var cleanCenter = clean.planes()[2];
            // the center decays through the frame and recovers after it
            assertTrue(energy(center, 5 * 1152 + 600, 6 * 1152) < 0.01 * energy(cleanCenter, 5 * 1152 + 600, 6 * 1152));
            assertArrayEquals(Arrays.copyOfRange(cleanCenter, 0, 5 * 1152), Arrays.copyOfRange(center, 0, 5 * 1152));
            assertTrue(energy(center, 7 * 1152, 8 * 1152) > 0.5 * energy(cleanCenter, 7 * 1152, 8 * 1152));
            var unchecked = decode(damage(packets, 5), Mp3DecoderOptions.builder().crcCheck(false).build(), -1);
            assertEquals(0, unchecked.concealed());
        }

        @Test
        @DisplayName("a first frame whose extension fails decodes as stereo, and the stream turns multichannel after it")
        void notYetMultichannel() {
            var packets = Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 4, 9, false);
            var decoded = decode(damage(packets, 0), Mp3DecoderOptions.defaults(), -1);
            assertEquals(ChannelLayout.STEREO, decoded.layouts().get(0));
            assertEquals(ChannelLayout.SURROUND_5_1, decoded.layouts().get(1));
            assertEquals(ChannelLayout.STEREO, decoded.reported(), "the layout of the first decoded frame");
            assertEquals(0, decoded.concealed());
        }

        @Test
        @DisplayName("a missing extension frame and a packet flagged corrupt are concealed in the multichannel layout")
        void missingExtensionFrame() {
            var packets = new ArrayList<>(Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 6, 13, true));
            var frameBytes = 144 * 112 * 1000 / 48000;
            packets.set(3, Arrays.copyOf(packets.get(3), frameBytes));
            var decoded = decode(packets, Mp3DecoderOptions.defaults(), 4);
            assertEquals(2, decoded.concealed());
            for (var layout : decoded.layouts()) {
                assertEquals(ChannelLayout.SURROUND_5_1, layout);
            }
        }

        @Test
        @DisplayName("an extension frame whose own CRC fails is concealed")
        void extensionFrameCrc() {
            var packets = new ArrayList<>(Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 6, 15, true));
            var data = packets.get(2).clone();
            var frameBytes = 144 * 112 * 1000 / 48000;
            data[frameBytes + 6] ^= 0x10;
            packets.set(2, data);
            assertEquals(1, decode(packets, Mp3DecoderOptions.defaults(), -1).concealed());
        }

        @Test
        @DisplayName("a frame of more channels than the limits allow fails with LimitExceeded")
        void limits() {
            var packets = Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 2, 17, false);
            var options = Mp3DecoderOptions.builder().limits(MediaLimits.builder().maxChannels(2).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> decode(packets, options, -1));
        }

        @Test
        @DisplayName("mutated multichannel streams decode or fail with a MediaException, never anything else")
        void mutations() {
            var inputs = new ArrayList<byte[]>();
            for (var extension : new boolean[]{false, true}) {
                inputs.add(Mp2MultichannelStreams.concatenate(Mp2MultichannelStreams.build(
                        Mp2MultichannelStreams.surround51(), 3, extension ? 19 : 21, extension)));
            }
            AudioMutations.assertRobust(inputs, 200, 23, Duration.ofSeconds(5), data -> {
                var decoder = new Mp3Decoder(TRACK, Mp3DecoderOptions.defaults());
                decoder.decode(new Packet(0, data, 0, 0, 1152, true), buffer -> {
                    for (var c = 0; c < buffer.channels(); c++) {
                        for (var i = 0; i < buffer.frames(); i++) {
                            assertFalse(Float.isNaN(buffer.plane(c)[i]), "NaN output");
                        }
                    }
                });
            });
        }
    }
}
