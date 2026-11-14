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
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the program selection of {@link Mp3DecoderOptions#program()} on synthetic streams
 * ({@link Mp2MultichannelStreams}): mirrored streams, whose second stereo program and multilingual channels repeat the
 * bits of the base channels and so must decode to the same samples, the format of half-rate multilingual channels,
 * the fallback to the main program, concealment in the selected format, the options themselves and robustness
 * against mutated streams.
 */
class Mp2ProgramTest {
    private static final AudioTrack TRACK = AudioTrack.builder().codec(Codec.MP2).timeBase(Rational.timeBaseOf(48000))
            .sampleRate(48000).layout(ChannelLayout.STEREO).build();

    /**
     * The difference allowed between a mirrored channel and its base channel, a third of a 16-bit unit: the extension
     * scales its samples with its own gain table, so the same bits differ from the base channels by float rounding.
     */
    private static final float FILTERBANK_ROUNDING = 1e-5f;

    /** A 2/0 + 2/0 stream whose second program repeats the base channels. */
    private static final Mp2MultichannelStreams.Configuration SECOND_MIRROR =
            new Mp2MultichannelStreams.Configuration(0, 3, false, 3, 0, false, true);

    /** A 2/0 stream with two full-rate multilingual channels repeating the base channels. */
    private static final Mp2MultichannelStreams.Configuration MULTILINGUAL_MIRROR =
            new Mp2MultichannelStreams.Configuration(0, 0, false, 3, 2, false, true);

    /** A 3/2 stream with three multilingual channels at half the sampling rate. */
    private static final Mp2MultichannelStreams.Configuration HALF_RATE =
            new Mp2MultichannelStreams.Configuration(1, 2, false, 0, 3, true, false);

    /** A 3/0 + 2/0 stream with five full-rate multilingual channels. */
    private static final Mp2MultichannelStreams.Configuration BOTH =
            new Mp2MultichannelStreams.Configuration(1, 3, false, 0, 5, false, false);

    /** The buffers of a decoded stream, one plane per output channel. */
    private record Decoded(List<ChannelLayout> layouts, List<Integer> rates, List<Integer> frames, float[][] planes,
                           long concealed, int reportedRate, ChannelLayout reportedLayout) {
    }

    private static Mp3DecoderOptions options(Mp2Program program) {
        return Mp3DecoderOptions.builder().program(program).build();
    }

    private static Decoded decode(List<byte[]> packets, Mp3DecoderOptions options, int corruptPacket) {
        var decoder = new Mp3Decoder(TRACK, options);
        var buffers = new ArrayList<AudioBuffer>();
        for (var i = 0; i < packets.size(); i++) {
            var packet = new Packet(0, packets.get(i), i * 1152L, i * 1152L, 1152, true);
            if (i == corruptPacket) {
                packet = packet.withFlags(EnumSet.of(PacketFlag.KEY, PacketFlag.CORRUPT));
            }
            decoder.decode(packet, buffer -> buffers.add(buffer.copy()));
        }
        return collect(buffers, decoder);
    }

    private static Decoded collect(List<AudioBuffer> buffers, Mp3Decoder decoder) {
        var layouts = new ArrayList<ChannelLayout>();
        var rates = new ArrayList<Integer>();
        var frames = new ArrayList<Integer>();
        var planes = new ArrayList<float[]>();
        var length = 0;
        for (var buffer : buffers) {
            layouts.add(buffer.layout());
            rates.add(buffer.sampleRate());
            frames.add(buffer.frames());
            for (var c = 0; c < buffer.channels(); c++) {
                if (planes.size() <= c) {
                    planes.add(new float[0]);
                }
                var grown = Arrays.copyOf(planes.get(c), length + buffer.frames());
                System.arraycopy(buffer.plane(c), 0, grown, length, buffer.frames());
                planes.set(c, grown);
            }
            length += buffer.frames();
        }
        return new Decoded(layouts, rates, frames, planes.toArray(float[][]::new), decoder.concealedFrames(),
                decoder.sampleRate(), decoder.layout());
    }

    private static double energy(float[] plane, int from, int to) {
        var sum = 0.0;
        for (var i = from; i < to; i++) {
            sum += plane[i] * plane[i];
        }
        return sum;
    }

    @Nested
    @DisplayName("mirrored streams")
    class Mirrored {
        @ParameterizedTest(name = "extension frames {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("the second stereo program coded with the base channels' bits decodes to their samples")
        void secondStereo(boolean extensionFrames) {
            var packets = Mp2MultichannelStreams.build(SECOND_MIRROR, 6, 31, extensionFrames);
            var main = decode(packets, Mp3DecoderOptions.defaults(), -1);
            var second = decode(packets, options(Mp2Program.SECOND_STEREO), -1);
            assertEquals(Collections.nCopies(6, ChannelLayout.STEREO), main.layouts());
            assertEquals(Collections.nCopies(6, ChannelLayout.STEREO), second.layouts());
            assertEquals(Collections.nCopies(6, 1152), second.frames());
            for (var c = 0; c < 2; c++) {
                assertTrue(energy(main.planes()[c], 0, 6 * 1152) > 0, "silent base channel " + c);
                assertArrayEquals(main.planes()[c], second.planes()[c], FILTERBANK_ROUNDING, "channel " + c);
            }
        }

        @ParameterizedTest(name = "extension frames {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("multilingual channels coded with the base channels' bits decode to their samples")
        void multilingual(boolean extensionFrames) {
            var packets = Mp2MultichannelStreams.build(MULTILINGUAL_MIRROR, 6, 33, extensionFrames);
            var main = decode(packets, Mp3DecoderOptions.defaults(), -1);
            var all = decode(packets, options(Mp2Program.MULTILINGUAL), -1);
            var unknown = new ChannelLayout(List.of(Speaker.UNKNOWN, Speaker.UNKNOWN));
            assertEquals(Collections.nCopies(6, unknown), all.layouts());
            assertEquals(Collections.nCopies(6, 48000), all.rates());
            assertEquals(unknown, all.reportedLayout());
            for (var c = 0; c < 2; c++) {
                assertTrue(energy(main.planes()[c], 0, 6 * 1152) > 0, "silent base channel " + c);
                assertArrayEquals(main.planes()[c], all.planes()[c], FILTERBANK_ROUNDING, "channel " + c);
                var one = decode(packets, Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL)
                        .multilingualChannel(c + 1).build(), -1);
                assertEquals(Collections.nCopies(6, ChannelLayout.MONO), one.layouts());
                assertArrayEquals(main.planes()[c], one.planes()[0], FILTERBANK_ROUNDING,
                        "multilingual channel " + (c + 1) + " alone");
            }
        }

        @Test
        @DisplayName("the main program of a stream with multilingual channels is the same as without them")
        void mainUnchanged() {
            var with = Mp2MultichannelStreams.build(MULTILINGUAL_MIRROR, 4, 35, true);
            var main = decode(with, Mp3DecoderOptions.defaults(), -1);
            var base = decode(with, Mp3DecoderOptions.builder().multichannel(false).build(), -1);
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(base.planes()[c], main.planes()[c]);
            }
        }
    }

    @Nested
    @DisplayName("a mono base frame")
    class MonoBase {
        @ParameterizedTest(name = "shared {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("1/0 + 2/0 gives the second stereo program of the same bits in 2/0 + 2/0")
        void secondStereo(boolean shared) {
            var mono = Mp2MultichannelStreams.buildOverSilentBase(1, true, shared, 0, 4, 71);
            var stereo = Mp2MultichannelStreams.buildOverSilentBase(2, true, shared, 0, 4, 71);
            var fromMono = decode(mono, options(Mp2Program.SECOND_STEREO), -1);
            var fromStereo = decode(stereo, options(Mp2Program.SECOND_STEREO), -1);
            assertEquals(Collections.nCopies(4, ChannelLayout.STEREO), fromMono.layouts());
            assertEquals(Collections.nCopies(4, 1152), fromMono.frames());
            for (var c = 0; c < 2; c++) {
                assertTrue(energy(fromMono.planes()[c], 0, 4 * 1152) > 0, "silent channel " + c);
                assertArrayEquals(fromStereo.planes()[c], fromMono.planes()[c], "channel " + c);
            }
            if (shared) {
                assertArrayEquals(fromMono.planes()[0], fromMono.planes()[1], FILTERBANK_ROUNDING);
            } else {
                assertNotEquals(Arrays.hashCode(fromMono.planes()[0]), Arrays.hashCode(fromMono.planes()[1]));
            }
            var main = decode(mono, Mp3DecoderOptions.defaults(), -1);
            assertEquals(Collections.nCopies(4, ChannelLayout.MONO), main.layouts());
            assertEquals(0, energy(main.planes()[0], 0, 4 * 1152));
        }

        @Test
        @DisplayName("1/0 with multilingual channels gives the channels of the same bits in 2/0")
        void multilingual() {
            var mono = Mp2MultichannelStreams.buildOverSilentBase(1, false, false, 3, 4, 73);
            var stereo = Mp2MultichannelStreams.buildOverSilentBase(2, false, false, 3, 4, 73);
            var fromMono = decode(mono, options(Mp2Program.MULTILINGUAL), -1);
            var fromStereo = decode(stereo, options(Mp2Program.MULTILINGUAL), -1);
            assertEquals(Collections.nCopies(4, new ChannelLayout(Collections.nCopies(3, Speaker.UNKNOWN))),
                    fromMono.layouts());
            for (var c = 0; c < 3; c++) {
                assertTrue(energy(fromMono.planes()[c], 0, 4 * 1152) > 0, "silent channel " + c);
                assertArrayEquals(fromStereo.planes()[c], fromMono.planes()[c], "channel " + c);
            }
            var main = decode(mono, Mp3DecoderOptions.defaults(), -1);
            assertEquals(Collections.nCopies(4, ChannelLayout.MONO), main.layouts());
            assertEquals(0, energy(main.planes()[0], 0, 4 * 1152));
        }
    }

    @Nested
    @DisplayName("a stream changing its programs")
    class Switching {
        private static List<AudioBuffer> buffers(Mp3Decoder decoder, List<byte[]> packets) {
            var buffers = new ArrayList<AudioBuffer>();
            for (var i = 0; i < packets.size(); i++) {
                decoder.decode(new Packet(0, packets.get(i), i * 1152L, i * 1152L, 1152, true),
                        buffer -> buffers.add(buffer.copy()));
            }
            return buffers;
        }

        @Test
        @DisplayName("every run of frames decodes as it does alone, leaving no filterbank history behind")
        void switching() {
            var runs = List.of(
                    Mp2MultichannelStreams.build(BOTH, 3, 83, true),
                    Mp2MultichannelStreams.build(new Mp2MultichannelStreams.Configuration(1, 2, false, 0), 3, 85, true),
                    Mp2MultichannelStreams.build(HALF_RATE, 3, 87, true),
                    Mp2MultichannelStreams.build(new Mp2MultichannelStreams.Configuration(1, 2, false, 0), 3, 89, true),
                    Mp2MultichannelStreams.build(BOTH, 3, 91, true));
            var decoder = new Mp3Decoder(TRACK, options(Mp2Program.MULTILINGUAL));
            var layouts = new ArrayList<ChannelLayout>();
            for (var run : runs) {
                var alone = buffers(new Mp3Decoder(TRACK, options(Mp2Program.MULTILINGUAL)), run);
                var together = buffers(decoder, run);
                assertEquals(3, together.size());
                for (var f = 0; f < 3; f++) {
                    var expected = alone.get(f);
                    var actual = together.get(f);
                    assertEquals(expected.layout(), actual.layout());
                    assertEquals(expected.sampleRate(), actual.sampleRate());
                    assertEquals(expected.frames(), actual.frames());
                    for (var c = 0; c < expected.channels(); c++) {
                        assertArrayEquals(Arrays.copyOf(expected.plane(c), expected.frames()),
                                Arrays.copyOf(actual.plane(c), actual.frames()), "frame " + f + " channel " + c);
                    }
                }
                layouts.add(together.getFirst().layout());
            }
            var surround = ChannelLayout.defaultFor(5);
            assertEquals(List.of(new ChannelLayout(Collections.nCopies(5, Speaker.UNKNOWN)), surround,
                    new ChannelLayout(Collections.nCopies(3, Speaker.UNKNOWN)), surround,
                    new ChannelLayout(Collections.nCopies(5, Speaker.UNKNOWN))), layouts);
        }
    }

    @Nested
    @DisplayName("formats")
    class Formats {
        @Test
        @DisplayName("half-rate multilingual channels decode to 576 samples per frame at half the rate")
        void halfRate() {
            var packets = Mp2MultichannelStreams.build(HALF_RATE, 5, 37, true);
            var decoded = decode(packets, options(Mp2Program.MULTILINGUAL), -1);
            var layout = new ChannelLayout(Collections.nCopies(3, Speaker.UNKNOWN));
            assertEquals(Collections.nCopies(5, layout), decoded.layouts());
            assertEquals(Collections.nCopies(5, 24000), decoded.rates());
            assertEquals(Collections.nCopies(5, 576), decoded.frames());
            assertEquals(24000, decoded.reportedRate());
            for (var plane : decoded.planes()) {
                assertEquals(5 * 576, plane.length);
                assertTrue(energy(plane, 0, plane.length) > 0, "a silent multilingual channel");
            }
            assertNotEquals(Arrays.hashCode(decoded.planes()[0]), Arrays.hashCode(decoded.planes()[1]));
            var main = decode(packets, Mp3DecoderOptions.defaults(), -1);
            assertEquals(Collections.nCopies(5, ChannelLayout.defaultFor(5)), main.layouts());
        }

        @Test
        @DisplayName("a stream with both a second stereo program and multilingual channels gives each on request")
        void both() {
            var packets = Mp2MultichannelStreams.build(BOTH, 4, 39, true);
            var main = decode(packets, Mp3DecoderOptions.defaults(), -1);
            assertEquals(new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER)),
                    main.layouts().getFirst());
            var second = decode(packets, options(Mp2Program.SECOND_STEREO), -1);
            assertEquals(Collections.nCopies(4, ChannelLayout.STEREO), second.layouts());
            var multilingual = decode(packets, options(Mp2Program.MULTILINGUAL), -1);
            assertEquals(Collections.nCopies(4, new ChannelLayout(Collections.nCopies(5, Speaker.UNKNOWN))),
                    multilingual.layouts());
            var fifth = decode(packets, Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL)
                    .multilingualChannel(5).build(), -1);
            assertArrayEquals(multilingual.planes()[4], fifth.planes()[0]);
            for (var plane : second.planes()) {
                assertTrue(energy(plane, 0, plane.length) > 0);
            }
        }

        @Test
        @DisplayName("a program the stream does not carry decodes as the main program")
        void fallback() {
            var surround = Mp2MultichannelStreams.build(Mp2MultichannelStreams.surround51(), 4, 41, false);
            var main = decode(surround, Mp3DecoderOptions.defaults(), -1);
            var halfRate = Mp2MultichannelStreams.build(HALF_RATE, 4, 43, false);
            var halfRateMain = decode(halfRate, Mp3DecoderOptions.defaults(), -1);
            var cases = List.of(
                    List.of(surround, options(Mp2Program.SECOND_STEREO), main),
                    List.of(surround, options(Mp2Program.MULTILINGUAL), main),
                    List.of(halfRate, options(Mp2Program.SECOND_STEREO), halfRateMain),
                    List.of(halfRate, Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL)
                            .multilingualChannel(4).build(), halfRateMain));
            for (var row : cases) {
                @SuppressWarnings("unchecked")
                var packets = (List<byte[]>) row.get(0);
                var expected = (Decoded) row.get(2);
                var decoded = decode(packets, (Mp3DecoderOptions) row.get(1), -1);
                assertEquals(expected.layouts(), decoded.layouts());
                for (var c = 0; c < expected.planes().length; c++) {
                    assertArrayEquals(expected.planes()[c], decoded.planes()[c]);
                }
            }
        }
    }

    @Nested
    @DisplayName("concealment")
    class Concealment {
        @Test
        @DisplayName("a corrupt packet in the selected program keeps its format and decays, and the program recovers")
        void corrupt() {
            var packets = Mp2MultichannelStreams.build(HALF_RATE, 8, 45, true);
            var clean = decode(packets, options(Mp2Program.MULTILINGUAL), -1);
            var damaged = decode(packets, options(Mp2Program.MULTILINGUAL), 4);
            assertEquals(1, damaged.concealed());
            assertEquals(clean.layouts(), damaged.layouts());
            assertEquals(clean.rates(), damaged.rates());
            assertEquals(clean.frames(), damaged.frames());
            for (var c = 0; c < 3; c++) {
                var plane = damaged.planes()[c];
                var reference = clean.planes()[c];
                assertArrayEquals(Arrays.copyOfRange(reference, 0, 4 * 576), Arrays.copyOfRange(plane, 0, 4 * 576));
                assertTrue(energy(plane, 4 * 576 + 300, 5 * 576) < 0.01 * energy(reference, 4 * 576 + 300, 5 * 576),
                        "channel " + c + " does not decay");
                assertTrue(energy(plane, 6 * 576, 7 * 576) > 0.5 * energy(reference, 6 * 576, 7 * 576),
                        "channel " + c + " does not recover");
            }
        }

        @Test
        @DisplayName("lost frames are concealed in the format of the selected program")
        void lost() {
            var packets = Mp2MultichannelStreams.build(HALF_RATE, 3, 47, true);
            var decoder = new Mp3Decoder(TRACK, options(Mp2Program.MULTILINGUAL));
            for (var i = 0; i < packets.size(); i++) {
                decoder.decode(new Packet(0, packets.get(i), i * 1152L, i * 1152L, 1152, true), buffer -> {
                });
            }
            var buffers = new ArrayList<AudioBuffer>();
            var concealed = decoder.conceal(576, buffer -> buffers.add(buffer.copy()));
            assertEquals(576, concealed);
            assertEquals(1, buffers.size());
            assertEquals(24000, buffers.getFirst().sampleRate());
            assertEquals(576, buffers.getFirst().frames());
            assertEquals(3, buffers.getFirst().channels());
            buffers.clear();
            assertEquals(1152, decoder.conceal(1152, buffer -> buffers.add(buffer.copy())));
            assertEquals(2, buffers.size());
        }

        @Test
        @DisplayName("multilingual channels cut short keep their format and decay, and the main program is unharmed")
        void multilingualCutShort() {
            var whole = Mp2MultichannelStreams.build(HALF_RATE, 6, 81, true);
            var packets = new ArrayList<>(whole);
            packets.set(3, Mp2MultichannelStreams.shortenExtensionFrame(whole.get(3), 144 * 112 * 1000 / 48000, 4));
            var clean = decode(whole, options(Mp2Program.MULTILINGUAL), -1);
            var cut = decode(packets, options(Mp2Program.MULTILINGUAL), -1);
            assertEquals(1, cut.concealed());
            assertEquals(clean.layouts(), cut.layouts());
            assertEquals(clean.rates(), cut.rates());
            assertEquals(clean.frames(), cut.frames());
            for (var c = 0; c < 3; c++) {
                assertTrue(energy(cut.planes()[c], 3 * 576 + 300, 4 * 576)
                        < 0.01 * energy(clean.planes()[c], 3 * 576 + 300, 4 * 576), "channel " + c + " does not decay");
            }
            var cleanMain = decode(whole, Mp3DecoderOptions.defaults(), -1);
            var cutMain = decode(packets, Mp3DecoderOptions.defaults(), -1);
            assertEquals(0, cutMain.concealed());
            for (var c = 0; c < cleanMain.planes().length; c++) {
                assertArrayEquals(cleanMain.planes()[c], cutMain.planes()[c], "main channel " + c);
            }
        }

        @Test
        @DisplayName("a failing extension of a second stereo stream keeps the stereo program and decays")
        void secondStereoExtension() {
            var packets = new ArrayList<>(Mp2MultichannelStreams.build(SECOND_MIRROR, 6, 49, true));
            var frameBytes = 144 * 112 * 1000 / 48000;
            packets.set(3, Arrays.copyOf(packets.get(3), frameBytes));
            var clean = decode(Mp2MultichannelStreams.build(SECOND_MIRROR, 6, 49, true),
                    options(Mp2Program.SECOND_STEREO), -1);
            var decoded = decode(packets, options(Mp2Program.SECOND_STEREO), -1);
            assertEquals(1, decoded.concealed());
            assertEquals(Collections.nCopies(6, ChannelLayout.STEREO), decoded.layouts());
            for (var c = 0; c < 2; c++) {
                assertTrue(energy(decoded.planes()[c], 3 * 1152 + 600, 4 * 1152)
                        < 0.01 * energy(clean.planes()[c], 3 * 1152 + 600, 4 * 1152));
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the defaults select the main program and every multilingual channel")
        void defaults() {
            var defaults = Mp3DecoderOptions.defaults();
            assertEquals(Mp2Program.MAIN, defaults.program());
            assertEquals(OptionalInt.empty(), defaults.multilingualChannel());
            var options = Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL).multilingualChannel(7).build();
            assertEquals(OptionalInt.of(7), options.multilingualChannel());
            assertEquals(options, options.toBuilder().build());
            assertNotEquals(options, options.toBuilder().allMultilingualChannels().build());
            assertNotEquals(defaults, Mp3DecoderOptions.builder().program(Mp2Program.SECOND_STEREO).build());
            assertTrue(options.toString().contains("MULTILINGUAL"));
        }

        @Test
        @DisplayName("inconsistent settings are rejected")
        void rejected() {
            assertThrows(IllegalArgumentException.class, () -> Mp3DecoderOptions.builder().multilingualChannel(0));
            assertThrows(IllegalArgumentException.class, () -> Mp3DecoderOptions.builder().multilingualChannel(8));
            assertThrows(NullPointerException.class, () -> Mp3DecoderOptions.builder().program(null));
            assertThrows(IllegalArgumentException.class, () -> Mp3DecoderOptions.builder()
                    .program(Mp2Program.SECOND_STEREO).multichannel(false).build());
            assertThrows(IllegalArgumentException.class, () -> Mp3DecoderOptions.builder().multilingualChannel(2)
                    .build());
        }

        @Test
        @DisplayName("multilingual channels beyond the channel limit fail with LimitExceeded")
        void limits() {
            var packets = Mp2MultichannelStreams.build(BOTH, 2, 51, true);
            var options = Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL)
                    .limits(MediaLimits.builder().maxChannels(4).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> decode(packets, options, -1));
        }
    }

    @Test
    @DisplayName("mutated streams decode to every program or fail with a MediaException, never anything else")
    void mutations() {
        var inputs = new ArrayList<byte[]>();
        inputs.add(Mp2MultichannelStreams.concatenate(Mp2MultichannelStreams.build(HALF_RATE, 3, 53, true)));
        inputs.add(Mp2MultichannelStreams.concatenate(Mp2MultichannelStreams.build(BOTH, 3, 55, true)));
        inputs.add(Mp2MultichannelStreams.concatenate(Mp2MultichannelStreams.build(SECOND_MIRROR, 3, 57, false)));
        inputs.add(Mp2MultichannelStreams.concatenate(
                Mp2MultichannelStreams.buildOverSilentBase(1, true, true, 2, 3, 58)));
        for (var program : Mp2Program.values()) {
            AudioMutations.assertRobust(inputs, 100, 59 + program.ordinal(), Duration.ofSeconds(5), data -> {
                var decoder = new Mp3Decoder(TRACK, options(program));
                decoder.decode(new Packet(0, data, 0, 0, 1152, true), buffer -> {
                    assertEquals(buffer.layout().channels(), buffer.channels());
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
