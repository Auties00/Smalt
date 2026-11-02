package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ChannelMapping;
import com.github.auties00.smalt.audio.aac.internal.ProgramConfig;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.header.AacRates;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the encoder's channel layouts and 960-sample frames. Every layout is encoded with one tone per
 * loudspeaker, so the decoded channel of each tone names the loudspeaker a decoder assigned to the coded
 * channel: this module's decoder and ffmpeg (black box, from MP4) must both put every tone on the
 * loudspeaker it was encoded for, and agree sample for sample. 960-sample streams go through both decoders
 * as well, with their short windows.
 */
class AacEncoderLayoutsTest {
    private static final int RATE = 48000;

    private static final Path WORK = Scratch.directory("aac-encoder-layouts");

    record Case(String name, ChannelLayout layout, int configuration) {
        @Override
        public String toString() {
            return name;
        }
    }

    static ChannelLayout layout(Speaker... speakers) {
        return new ChannelLayout(List.of(speakers));
    }

    static ChannelLayout reversed(ChannelLayout layout) {
        var speakers = new ArrayList<>(layout.speakers());
        java.util.Collections.reverse(speakers);
        return new ChannelLayout(speakers);
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("mono", ChannelLayout.MONO, 1),
                new Case("stereo", ChannelLayout.STEREO, 2),
                new Case("3.0 in coding order", layout(Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT), 3),
                new Case("4.0", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_CENTER), 4),
                new Case("5.0", ChannelLayout.defaultFor(5), 5),
                new Case("5.1", ChannelLayout.SURROUND_5_1, 6),
                new Case("5.1 reversed", reversed(ChannelLayout.SURROUND_5_1), 6),
                new Case("7.1 wide", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.FRONT_LEFT_OF_CENTER,
                        Speaker.FRONT_RIGHT_OF_CENTER), 7),
                new Case("6.1 back", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.BACK_CENTER), 11),
                new Case("7.1", ChannelLayout.SURROUND_7_1, 12),
                new Case("22.2 reversed", reversed(AudioConfigs.aacLayout(13)), 13),
                new Case("7.1 top front", AudioConfigs.aacLayout(14), 14),
                new Case("2.1 (PCE)", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY), 0),
                new Case("1.1 (PCE)", layout(Speaker.LOW_FREQUENCY, Speaker.FRONT_CENTER), 0),
                new Case("quad (PCE)", ChannelLayout.defaultFor(4), 0),
                new Case("rear pair (PCE)", layout(Speaker.BACK_LEFT, Speaker.BACK_RIGHT), 0),
                new Case("3.0 back (PCE)", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER), 0),
                new Case("5.0 front (PCE)", layout(Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_CENTER,
                        Speaker.FRONT_RIGHT, Speaker.FRONT_RIGHT_OF_CENTER), 0),
                new Case("5.1 side (PCE)", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT), 0),
                new Case("hexagonal (PCE)", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.BACK_CENTER), 0),
                new Case("6.1 side (PCE)", ChannelLayout.defaultFor(7), 0),
                new Case("7.0 (PCE)", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                        Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT), 0),
                new Case("10.1 (PCE)", layout(Speaker.SIDE_RIGHT, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                        Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                        Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER, Speaker.BACK_CENTER,
                        Speaker.SIDE_LEFT), 0));
    }

    /** Returns the loudspeakers in WAVE order, the order a decoder outputs. */
    static List<Speaker> sorted(ChannelLayout layout) {
        var speakers = layout.speakers().toArray(Speaker[]::new);
        Arrays.sort(speakers);
        return List.of(speakers);
    }

    /** Returns the tone of a loudspeaker on a 1024-point DFT bin: below 240 Hz for the LFE channels. */
    static double tone(ChannelLayout layout, Speaker speaker) {
        if (speaker == Speaker.LOW_FREQUENCY) {
            return 2 * RATE / 1024.0;
        }
        if (speaker == Speaker.LOW_FREQUENCY_2) {
            return 3 * RATE / 1024.0;
        }
        var index = 0;
        for (var other : sorted(layout)) {
            if (other == speaker) {
                break;
            }
            if (other != Speaker.LOW_FREQUENCY && other != Speaker.LOW_FREQUENCY_2) {
                index++;
            }
        }
        return (8 + 3 * index) * RATE / 1024.0;
    }

    static float[][] tones(ChannelLayout layout, int rate, int samples) {
        var planes = new float[layout.channels()][samples];
        for (var c = 0; c < planes.length; c++) {
            var frequency = tone(layout, layout.speakers().get(c));
            for (var i = 0; i < samples; i++) {
                planes[c][i] = (float) (0.2 * Math.sin(2 * Math.PI * frequency * i / rate));
            }
        }
        return planes;
    }

    /** Returns a track of a configuration that declares the stream's own sampling rate. */
    static AudioTrack track(byte[] config, int rate) {
        return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(rate)).codecConfig(config)
                .sampleRate(rate).build();
    }

    /** Writes the packets of an encode into an M4A file. */
    static Path mp4(String name, AacEncoderTest.Encoded encoded, int rate, ChannelLayout layout) {
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.m4a())) {
            muxer.addTrack(AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(rate)).duration(-1)
                    .sampleRate(rate).layout(layout).codecConfig(encoded.config()).build());
            for (var packet : encoded.packets()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
            var file = WORK.resolve(name.replaceAll("[^A-Za-z0-9.]+", "-") + ".m4a");
            Files.write(file, sink.toByteArray());
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** ffmpeg's decode of a file: its planes, in ffmpeg's channel order, and ffprobe's layout description. */
    record Reference(float[][] planes, String description) {
        /** Returns the loudspeaker ffprobe names for each channel, or null for a description not listed here. */
        List<Speaker> speakers() {
            if (description.equals("22.2")) {
                return AacLayoutsTest.FFMPEG_ORDER_13;
            }
            var listed = Pattern.compile("\\d+ channels \\((.*)\\)").matcher(description);
            var names = listed.matches() ? listed.group(1) : STANDARD_LAYOUTS.get(description);
            if (names == null) {
                return null;
            }
            var speakers = new ArrayList<Speaker>();
            for (var name : names.split("\\+")) {
                var speaker = CHANNEL_NAMES.get(name.replaceAll("@.*", ""));
                if (speaker == null) {
                    return null;
                }
                speakers.add(speaker);
            }
            return speakers;
        }
    }

    /** ffmpeg's names of the loudspeakers. */
    private static final Map<String, Speaker> CHANNEL_NAMES = Map.ofEntries(Map.entry("FL", Speaker.FRONT_LEFT),
            Map.entry("FR", Speaker.FRONT_RIGHT), Map.entry("FC", Speaker.FRONT_CENTER),
            Map.entry("LFE", Speaker.LOW_FREQUENCY), Map.entry("BL", Speaker.BACK_LEFT),
            Map.entry("BR", Speaker.BACK_RIGHT), Map.entry("FLC", Speaker.FRONT_LEFT_OF_CENTER),
            Map.entry("FRC", Speaker.FRONT_RIGHT_OF_CENTER), Map.entry("BC", Speaker.BACK_CENTER),
            Map.entry("SL", Speaker.SIDE_LEFT), Map.entry("SR", Speaker.SIDE_RIGHT), Map.entry("TC", Speaker.TOP_CENTER),
            Map.entry("TFL", Speaker.TOP_FRONT_LEFT), Map.entry("TFC", Speaker.TOP_FRONT_CENTER),
            Map.entry("TFR", Speaker.TOP_FRONT_RIGHT), Map.entry("TBL", Speaker.TOP_BACK_LEFT),
            Map.entry("TBC", Speaker.TOP_BACK_CENTER), Map.entry("TBR", Speaker.TOP_BACK_RIGHT),
            Map.entry("WL", Speaker.WIDE_LEFT), Map.entry("WR", Speaker.WIDE_RIGHT),
            Map.entry("LFE2", Speaker.LOW_FREQUENCY_2), Map.entry("TSL", Speaker.TOP_SIDE_LEFT),
            Map.entry("TSR", Speaker.TOP_SIDE_RIGHT), Map.entry("BFC", Speaker.BOTTOM_FRONT_CENTER),
            Map.entry("BFL", Speaker.BOTTOM_FRONT_LEFT), Map.entry("BFR", Speaker.BOTTOM_FRONT_RIGHT));

    /** The channels of ffmpeg's named layouts (libavutil channel_layout.c), in their native order. */
    private static final Map<String, String> STANDARD_LAYOUTS = Map.ofEntries(Map.entry("mono", "FC"),
            Map.entry("stereo", "FL+FR"), Map.entry("2.1", "FL+FR+LFE"), Map.entry("3.0", "FL+FR+FC"),
            Map.entry("3.0(back)", "FL+FR+BC"), Map.entry("4.0", "FL+FR+FC+BC"), Map.entry("quad", "FL+FR+BL+BR"),
            Map.entry("quad(side)", "FL+FR+SL+SR"), Map.entry("3.1", "FL+FR+FC+LFE"),
            Map.entry("5.0", "FL+FR+FC+BL+BR"), Map.entry("5.0(side)", "FL+FR+FC+SL+SR"),
            Map.entry("4.1", "FL+FR+FC+LFE+BC"), Map.entry("5.1", "FL+FR+FC+LFE+BL+BR"),
            Map.entry("5.1(side)", "FL+FR+FC+LFE+SL+SR"), Map.entry("6.0", "FL+FR+FC+BC+SL+SR"),
            Map.entry("6.0(front)", "FL+FR+FLC+FRC+SL+SR"), Map.entry("hexagonal", "FL+FR+FC+BL+BR+BC"),
            Map.entry("6.1", "FL+FR+FC+LFE+BC+SL+SR"), Map.entry("6.1(back)", "FL+FR+FC+LFE+BL+BR+BC"),
            Map.entry("6.1(front)", "FL+FR+LFE+FLC+FRC+SL+SR"), Map.entry("7.0", "FL+FR+FC+BL+BR+SL+SR"),
            Map.entry("7.0(front)", "FL+FR+FC+FLC+FRC+SL+SR"), Map.entry("7.1", "FL+FR+FC+LFE+BL+BR+SL+SR"),
            Map.entry("7.1(wide)", "FL+FR+FC+LFE+BL+BR+FLC+FRC"),
            Map.entry("7.1(wide-side)", "FL+FR+FC+LFE+FLC+FRC+SL+SR"),
            Map.entry("5.1.2", "FL+FR+FC+LFE+BL+BR+TFL+TFR"), Map.entry("5.1.2(back)", "FL+FR+FC+LFE+BL+BR+TFL+TFR"),
            Map.entry("7.1(top)", "FL+FR+FC+LFE+BL+BR+TFL+TFR"),
            Map.entry("octagonal", "FL+FR+FC+BL+BR+BC+SL+SR"));

    static Reference ffmpegDecode(Path file) {
        var probe = Oracle.probe(file);
        var layout = Pattern.compile("\"channel_layout\": \"([^\"]*)\"").matcher(probe);
        var count = Pattern.compile("\"channels\": (\\d+)").matcher(probe);
        assertTrue(count.find(), probe);
        var channels = Integer.parseInt(count.group(1));
        var planes = AacTestSupport.deinterleave(Oracle.decodeAudio(file), channels);
        return new Reference(planes, layout.find() ? layout.group(1) : "");
    }

    /**
     * The loudspeaker the standard assigns to each channel ffmpeg 7.1 labels in its decode of channel configuration 7:
     * ffmpeg reads the configuration as 7.1, its front pair of center (the first channel pair) as front left and
     * right and its outside front pair as the side pair.
     */
    private static final Map<Speaker, Speaker> FFMPEG_CONFIGURATION_7 = Map.of(Speaker.FRONT_LEFT,
            Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT, Speaker.FRONT_RIGHT_OF_CENTER, Speaker.SIDE_LEFT,
            Speaker.FRONT_LEFT, Speaker.SIDE_RIGHT, Speaker.FRONT_RIGHT);

    /** Returns the loudspeaker of each coded channel of a configuration, in coding order. */
    static List<Speaker> codingOrder(byte[] config) {
        var parsed = AudioSpecificConfig.parse(config);
        var mapping = parsed.programConfig() != null
                ? ChannelMapping.ofProgram(parsed.programConfig(), MediaLimits.DEFAULT)
                : ChannelMapping.ofConfiguration(parsed.channelConfiguration(), MediaLimits.DEFAULT);
        var speakers = new ArrayList<Speaker>();
        for (var channel = 0; channel < mapping.channels(); channel++) {
            speakers.add(mapping.speaker(channel));
        }
        return speakers;
    }

    /**
     * Returns the loudspeaker, as the standard assigns it, of each channel of ffmpeg's decode of a stream.
     *
     * <p>ffmpeg 7.1 names no loudspeaker for the program config elements that hold a side pair and outputs their
     * channels in coding order; it reads channel configuration 7 as 7.1 (see {@link #FFMPEG_CONFIGURATION_7}).
     */
    static List<Speaker> ffmpegSpeakers(Reference reference, byte[] config) {
        var configuration = AudioSpecificConfig.parse(config).channelConfiguration();
        if (reference.description().isEmpty()) {
            assertEquals(0, configuration, "ffmpeg names no loudspeaker");
            return codingOrder(config);
        }
        var speakers = reference.speakers();
        assertTrue(speakers != null, "ffmpeg's layout " + reference.description());
        if (configuration == 7) {
            assertEquals("7.1", reference.description());
            return speakers.stream().map(speaker -> FFMPEG_CONFIGURATION_7.getOrDefault(speaker, speaker)).toList();
        }
        return speakers;
    }

    /** Returns the loudspeaker whose tone dominates a decoded channel. */
    static Speaker dominant(ChannelLayout layout, float[] plane) {
        Speaker best = null;
        var strongest = -1.0;
        for (var speaker : layout.speakers()) {
            var amplitude = AacLayoutsTest.amplitude(plane, tone(layout, speaker));
            if (amplitude > strongest) {
                strongest = amplitude;
                best = speaker;
            }
        }
        return best;
    }

    static void assertTones(String what, ChannelLayout layout, Speaker speaker, float[] plane) {
        var own = AacLayoutsTest.amplitude(plane, tone(layout, speaker));
        assertTrue(own > 0.15, what + " " + speaker + ": tone amplitude " + own);
        for (var other : layout.speakers()) {
            if (other != speaker) {
                var leak = AacLayoutsTest.amplitude(plane, tone(layout, other));
                assertTrue(leak < 0.01, what + " " + speaker + " carries " + other + " at " + leak);
            }
        }
    }

    @Nested
    @DisplayName("channel layouts")
    class Layouts {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacEncoderLayoutsTest#cases")
        @DisplayName("signals the channel configuration, or a program config element the decoder maps back to the layout")
        void signaling(Case test) {
            var options = AacEncoderOptions.of(RATE, test.layout(), 64000 * test.layout().channels());
            assertEquals(test.configuration(), options.channelConfiguration());
            var config = AudioSpecificConfig.parse(new AacEncoder(options).codecConfig().orElseThrow());
            assertEquals(test.configuration(), config.channelConfiguration());
            assertEquals(1024, config.frameLength());
            assertEquals(test.configuration() == 0, config.programConfig() != null);
            if (config.programConfig() != null) {
                assertEquals(test.layout().channels(), config.programConfig().channels());
                assertEquals(1, config.programConfig().objectType());
                assertEquals(3, config.programConfig().samplingIndex());
            }
            var decoder = new AacDecoder(AacLayoutsTest.track(new AacEncoder(options).codecConfig().orElseThrow()));
            assertEquals(sorted(test.layout()), decoder.layout().speakers());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacEncoderLayoutsTest#cases")
        @DisplayName("puts every input channel on its loudspeaker through this module's decoder")
        void decoder(Case test) {
            var input = tones(test.layout(), RATE, RATE);
            var encoded = AacEncoderTest.encode(AacEncoderOptions.of(RATE, test.layout(), 64000 * input.length), input);
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacLayoutsTest.track(encoded.config())), encoded.frames());
            assertEquals(sorted(test.layout()), decoded.layout().speakers());
            for (var speaker : test.layout().speakers()) {
                assertTones("decoder", test.layout(), speaker, decoded.planes()[decoded.layout().indexOf(speaker)]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacEncoderLayoutsTest#cases")
        @DisplayName("puts every input channel on the loudspeaker ffmpeg assigns it, as this module's decoder does")
        void ffmpeg(Case test) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = tones(test.layout(), RATE, RATE);
            var encoded = AacEncoderTest.encode(AacEncoderOptions.of(RATE, test.layout(), 64000 * input.length), input);
            var output = new ChannelLayout(sorted(test.layout()));
            var file = mp4(test.name(), encoded, RATE, output);
            Validators.assertValid(file);
            Oracle.assertFfmpegDecodes(file);
            var reference = ffmpegDecode(file);
            assertEquals(input.length, reference.planes().length);
            var carried = new ArrayList<Speaker>();
            for (var plane : reference.planes()) {
                carried.add(dominant(test.layout(), plane));
            }
            var speakers = ffmpegSpeakers(reference, encoded.config());
            assertEquals(speakers, carried, "the tones of ffmpeg's layout " + reference.description());
            assertEquals(output.speakers(), sorted(new ChannelLayout(speakers)), "ffmpeg's loudspeakers");
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacLayoutsTest.track(encoded.config())), encoded.frames());
            for (var c = 0; c < speakers.size(); c++) {
                var speaker = speakers.get(c);
                assertTones("ffmpeg", test.layout(), speaker, reference.planes()[c]);
                var ours = decoded.planes()[decoded.layout().indexOf(speaker)];
                var length = Math.min(ours.length, reference.planes()[c].length);
                Tolerance.maxAbs(1e-4).assertPcm(test.name() + " " + speaker, Arrays.copyOf(reference.planes()[c], length),
                        Arrays.copyOf(ours, length));
            }
        }

        @ParameterizedTest(name = "{0} Hz, {1} channels")
        @CsvSource({"48000, 7", "44100, 8", "32000, 11", "48000, 24"})
        @DisplayName("keeps the constant bitrate of multichannel layouts within 3 percent over 10 seconds")
        void bitrate(int rate, int channels) {
            var layout = switch (channels) {
                case 7 -> ChannelLayout.defaultFor(7);
                case 8 -> ChannelLayout.SURROUND_7_1;
                case 11 -> cases().filter(c -> c.layout().channels() == 11).findFirst().orElseThrow().layout();
                default -> AudioConfigs.aacLayout(13);
            };
            var bitrate = 48000 * channels;
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, rate, channels, 10);
            var encoded = AacEncoderTest.encode(AacEncoderOptions.of(rate, layout, bitrate), input);
            var actual = encoded.bytes() * 8.0 * rate / (encoded.packets().size() * 1024.0);
            assertEquals(bitrate, actual, bitrate * 0.03, "actual bitrate " + actual);
            for (var packet : encoded.packets()) {
                assertTrue(packet.size() * 8 <= 6144 * channels, "frame exceeds the decoder buffer");
            }
        }

        @Test
        @DisplayName("codes the LFE channel band-limited below 240 Hz, the other channels at full band")
        void lowFrequency() {
            var input = TestSignals.generate(TestSignals.Kind.NOISE, RATE, 6, 3);
            var layout = ChannelLayout.SURROUND_5_1;
            var encoded = AacEncoderTest.encode(AacEncoderOptions.of(RATE, layout, 384000), input);
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacLayoutsTest.track(encoded.config())), encoded.frames());
            for (var c = 0; c < 6; c++) {
                var plane = decoded.planes()[decoded.layout().indexOf(layout.speakers().get(c))];
                var energy = 0.0;
                var difference = 0.0;
                for (var i = 1; i < plane.length; i++) {
                    energy += plane[i] * plane[i];
                    var step = plane[i] - plane[i - 1];
                    difference += step * step;
                }
                var ratio = difference / energy;
                if (layout.speakers().get(c) == Speaker.LOW_FREQUENCY) {
                    // A first difference attenuates 240 Hz by 2 sin(pi 240 / 48000), about 30 dB.
                    assertTrue(energy > 0 && ratio < 0.003, "LFE first-difference energy ratio " + ratio);
                } else {
                    assertTrue(ratio > 0.01, layout.speakers().get(c) + " first-difference energy ratio " + ratio);
                }
            }
        }

        @Test
        @DisplayName("writes the program config element bit for bit")
        void programConfigBits() {
            var options = AacEncoderOptions.of(RATE, ChannelLayout.defaultFor(4), 128000);
            var config = new AacEncoder(options).codecConfig().orElseThrow();
            // AAC LC, index 3, configuration 0, three zero GASpecificConfig flags, then the element: tag 0, profile 1,
            // index 3, one front, no side, one back, no LFE, no associated data or coupling, no mixdowns, the front
            // CPE tag 0, the back CPE tag 1, alignment and an empty comment.
            var expected = new byte[]{0x11, (byte) 0x80, 0x04, (byte) 0xC4, 0x04, 0x00, 0x21, 0x10, 0x00};
            assertArrayEquals(expected, config, Arrays.toString(config));
        }

        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350})
        @DisplayName("gives the program config element the sampling frequency index of the AudioSpecificConfig")
        void programConfigRate(int rate) {
            var options = AacEncoderOptions.of(rate, ChannelLayout.defaultFor(4), 32000);
            var bytes = new AacEncoder(options).codecConfig().orElseThrow();
            // The index sits in bits 5 to 8 of the configuration; 7350 Hz has index 12 but shares the 8 kHz band tables.
            var index = ((bytes[0] & 7) << 1) | ((bytes[1] & 0xFF) >> 7);
            assertEquals(AacRates.index(rate), index);
            assertEquals(index, AudioSpecificConfig.parse(bytes).programConfig().samplingIndex());
        }

        @Test
        @DisplayName("refuses ADTS for the streams its header cannot describe")
        void adts() {
            for (var options : List.of(AacEncoderOptions.of(RATE, ChannelLayout.defaultFor(4), 128000),
                    AacEncoderOptions.of(RATE, ChannelLayout.SURROUND_7_1, 512000),
                    AacEncoderOptions.defaults().toBuilder().frameLength(960).build())) {
                var muxer = new AdtsMuxer(MediaSink.memory());
                var track = AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(RATE))
                        .codecConfig(new AacEncoder(options).codecConfig().orElseThrow()).build();
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track), options.toString());
            }
        }
    }

    @Nested
    @DisplayName("960-sample frames")
    class ShortFrames {
        static AacEncoderOptions options(int rate, ChannelLayout layout, int bitrate) {
            return AacEncoderOptions.of(rate, layout, bitrate).toBuilder().frameLength(960).build();
        }

        /** Returns the window sequence of a frame of a single channel element. */
        static int sequence(byte[] frame) {
            return (frame[2] >> 6) & 3;
        }

        @Test
        @DisplayName("sets the frameLengthFlag and frames 960 samples per packet")
        void contract() {
            var options = options(RATE, ChannelLayout.STEREO, 128000);
            var encoder = new AacEncoder(options);
            assertEquals(960, encoder.frameSize());
            assertEquals(960, encoder.delay());
            var config = encoder.codecConfig().orElseThrow();
            assertArrayEquals(new byte[]{0x11, (byte) 0x94}, config);
            assertEquals(960, AudioSpecificConfig.parse(config).frameLength());
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, RATE, 2, 1);
            var encoded = AacEncoderTest.encode(options, input);
            assertEquals((input[0].length + 959) / 960 + 1, encoded.packets().size());
            for (var i = 0; i < encoded.packets().size(); i++) {
                var packet = encoded.packets().get(i);
                assertEquals(960L * i, packet.pts());
                assertEquals(960, packet.duration());
            }
        }

        @ParameterizedTest(name = "{0} Hz, {1}, {2} bps")
        @CsvSource({"48000, stereo, 128000", "48000, mono, 64000", "32000, stereo, 96000", "24000, mono, 32000",
                "16000, stereo, 32000", "48000, 5.1, 320000", "48000, 7.0, 336000"})
        @DisplayName("decodes cleanly with ffmpeg and this module's decoder, identically, with short windows")
        void roundTrip(int rate, String name, int bitrate) {
            var layout = switch (name) {
                case "mono" -> ChannelLayout.MONO;
                case "stereo" -> ChannelLayout.STEREO;
                case "5.1" -> ChannelLayout.SURROUND_5_1;
                default -> cases().filter(c -> c.name().equals("7.0 (PCE)")).findFirst().orElseThrow().layout();
            };
            var input = TestSignals.generate(TestSignals.Kind.TRANSIENTS, rate, layout.channels(), 4);
            var encoded = AacEncoderTest.encode(options(rate, layout, bitrate), input);
            if (layout.channels() == 1) {
                assertTrue(encoded.frames().stream().anyMatch(frame -> sequence(frame) == 2), "no eight-short frame");
            }
            var decoded = AacTestSupport.decodeAll(new AacDecoder(track(encoded.config(), rate)), encoded.frames());
            assertEquals(rate, decoded.sampleRate());
            assertEquals(960L * encoded.packets().size(), decoded.planes()[0].length);
            var ordered = new float[layout.channels()][];
            for (var c = 0; c < ordered.length; c++) {
                var plane = decoded.planes()[decoded.layout().indexOf(layout.speakers().get(c))];
                ordered[c] = Arrays.copyOfRange(plane, 960, plane.length);
            }
            var snr = TestSignals.segmentalSnr(input, ordered);
            var reference = AacEncoderTest.encode(AacEncoderOptions.of(rate, layout, bitrate), input);
            var long1024 = AacTestSupport.decodeAll(new AacDecoder(track(reference.config(), rate)),
                    reference.frames());
            var referenceOrdered = new float[layout.channels()][];
            for (var c = 0; c < ordered.length; c++) {
                var plane = long1024.planes()[long1024.layout().indexOf(layout.speakers().get(c))];
                referenceOrdered[c] = Arrays.copyOfRange(plane, 1024, plane.length);
            }
            var referenceSnr = TestSignals.segmentalSnr(input, referenceOrdered);
            assertTrue(snr > 10 && snr > referenceSnr - 1.5, "segmental SNR " + snr + ", 1024-sample frames "
                    + referenceSnr);
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = mp4("960-" + rate + "-" + name, encoded, rate, decoded.layout());
            Oracle.assertDecodesCleanly(file);
            var ffmpeg = ffmpegDecode(file);
            var speakers = ffmpegSpeakers(ffmpeg, encoded.config());
            assertEquals(decoded.layout().speakers(), sorted(new ChannelLayout(speakers)), ffmpeg.description());
            var ours = new float[speakers.size()][];
            var theirs = new float[speakers.size()][];
            for (var c = 0; c < speakers.size(); c++) {
                var plane = decoded.planes()[decoded.layout().indexOf(speakers.get(c))];
                var length = Math.min(plane.length, ffmpeg.planes()[c].length);
                assertTrue(length > plane.length - 2048, "ffmpeg decoded " + ffmpeg.planes()[c].length);
                ours[c] = Arrays.copyOf(plane, length);
                theirs[c] = Arrays.copyOf(ffmpeg.planes()[c], length);
            }
            var agreement = AacTestSupport.snr(theirs, ours);
            assertTrue(agreement > 80, "ffmpeg and this decoder differ: SNR " + agreement);
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {2, 8})
        @DisplayName("codes VBR at the rate control parameter of 1024-sample frames: bitrates within 10 percent")
        void variable(int channels) {
            var layout = channels == 2 ? ChannelLayout.STEREO : ChannelLayout.SURROUND_7_1;
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, RATE, channels, 6);
            var nominal = AacEncoderOptions.of(RATE, layout, 64000 * channels).toBuilder()
                    .bitrateMode(AacBitrateMode.VBR).build();
            var reference = AacEncoderTest.encode(nominal, input);
            var encoded = AacEncoderTest.encode(nominal.toBuilder().frameLength(960).build(), input);
            var referenceRate = reference.bytes() * 8.0 * RATE / (reference.packets().size() * 1024.0);
            var actual = encoded.bytes() * 8.0 * RATE / (encoded.packets().size() * 960.0);
            assertEquals(referenceRate, actual, referenceRate * 0.1, "960-sample VBR bitrate");
            var decoded = AacTestSupport.decodeAll(new AacDecoder(track(encoded.config(), RATE)), encoded.frames());
            assertEquals(960L * encoded.packets().size(), decoded.planes()[0].length);
        }

        @Test
        @DisplayName("keeps the constant bitrate within 3 percent over 10 seconds")
        void bitrate() {
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, RATE, 2, 10);
            var encoded = AacEncoderTest.encode(options(RATE, ChannelLayout.STEREO, 96000), input);
            var actual = encoded.bytes() * 8.0 * RATE / (encoded.packets().size() * 960.0);
            assertEquals(96000, actual, 96000 * 0.03, "actual bitrate " + actual);
        }
    }

    @Nested
    @DisplayName("1024-sample mono, stereo and 5.1")
    class Pinned {
        /** Returns tones, noise and noise bursts computed with StrictMath and an integer generator, the same on every platform. */
        static float[][] signal(int rate, int channels) {
            var samples = 2 * rate;
            var planes = new float[channels][samples];
            for (var c = 0; c < channels; c++) {
                var seed = 0x9E3779B97F4A7C15L * (c + 1);
                for (var i = 0; i < samples; i++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    var noise = ((seed >>> 40) / (double) (1L << 24) - 0.5) * 0.05;
                    var t = i / (double) rate;
                    var tone = 0.25 * StrictMath.sin(2 * StrictMath.PI * (220 + 110 * c) * t)
                            + 0.1 * StrictMath.sin(2 * StrictMath.PI * (3000 + 500 * c) * t);
                    var burst = (i % (rate / 2)) < rate / 50 ? 6 * noise : 0;
                    planes[c][i] = (float) (tone + noise + burst);
                }
            }
            return planes;
        }

        // The SHA-256 of the configuration and of each packet's size and bytes. The values are the output of the
        // encoder that coded only mono, stereo and 5.1, so these layouts keep their bitstream; a change to them must
        // be measured quality-neutral or better first.
        @ParameterizedTest(name = "{0}")
        @CsvSource({"mono-48000-64000-cbr, 281ad6d4f2ebd81ab3b512acaa4c899d43597cb1657eb2f3cb54ab01f6eff185",
                "stereo-44100-128000-cbr, ac2435eb2e2f2fc5d8fd575b5cb712b19a5c24f31516bc82053c13fcd36de777",
                "stereo-48000-128000-vbr, 2e3a18ac9a582f4fd3e0c8874776e590fe9d7c85c0378aa661ad314b3a7ca1dc",
                "5.1-48000-384000-cbr, b15201fe555134a89ecb8a34b0a430d5bf9509a8ae5388bbfc568c76b33b692d",
                "5.1-32000-192000-vbr, a737c9c9fbef443ad93d8316b5f4e97776fcdd61ad9536af5ccde6a75cc66469"})
        @DisplayName("keeps its bitstream, pinned by SHA-256")
        void bitstream(String name, String expected) throws NoSuchAlgorithmException {
            var fields = name.split("-");
            var layout = switch (fields[0]) {
                case "mono" -> ChannelLayout.MONO;
                case "stereo" -> ChannelLayout.STEREO;
                default -> ChannelLayout.SURROUND_5_1;
            };
            var rate = Integer.parseInt(fields[1]);
            var options = AacEncoderOptions.of(rate, layout, Integer.parseInt(fields[2])).toBuilder()
                    .bitrateMode(fields[3].equals("vbr") ? AacBitrateMode.VBR : AacBitrateMode.CBR).build();
            var encoded = AacEncoderTest.encode(options, signal(rate, layout.channels()));
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(encoded.config());
            for (var packet : encoded.packets()) {
                digest.update(ByteBuffer.allocate(4).putInt(packet.size()).array());
                digest.update(packet.data());
            }
            assertEquals(expected, HexFormat.of().formatHex(digest.digest()));
        }
    }

    @Nested
    @DisplayName("program config element")
    class Program {
        @Test
        @DisplayName("parses back what it writes for every element mix")
        void roundTrip() {
            var random = new java.util.Random(7);
            for (var trial = 0; trial < 500; trial++) {
                var lists = new ArrayList<List<ProgramConfig.Element>>();
                for (var list = 0; list < 3; list++) {
                    var elements = new ArrayList<ProgramConfig.Element>();
                    var count = random.nextInt(16);
                    for (var i = 0; i < count; i++) {
                        elements.add(new ProgramConfig.Element(random.nextBoolean(), random.nextInt(16)));
                    }
                    lists.add(elements);
                }
                var lfe = new ArrayList<Integer>();
                var lfeCount = random.nextInt(4);
                for (var i = 0; i < lfeCount; i++) {
                    lfe.add(random.nextInt(16));
                }
                var channels = lfe.size();
                for (var list : lists) {
                    for (var element : list) {
                        channels += element.pair() ? 2 : 1;
                    }
                }
                if (channels == 0) {
                    continue;
                }
                var config = new ProgramConfig(random.nextInt(16), random.nextInt(4), random.nextInt(13), lists.get(0),
                        lists.get(1), lists.get(2), lfe, List.of(), channels);
                var bytes = AudioSpecificConfig.lowComplexity(RATE, 0, 1024, config);
                assertEquals(config, AudioSpecificConfig.parse(bytes).programConfig());
            }
        }

        @Test
        @DisplayName("goes with channel configuration 0 only")
        void pairing() {
            assertThrows(IllegalArgumentException.class, () -> AudioSpecificConfig.lowComplexity(RATE, 0, 1024, null));
            var config = new ProgramConfig(0, 1, 3, List.of(new ProgramConfig.Element(true, 0)), List.of(), List.of(),
                    List.of(), List.of(), 2);
            assertThrows(IllegalArgumentException.class, () -> AudioSpecificConfig.lowComplexity(RATE, 2, 1024, config));
            assertNull(AudioSpecificConfig.parse(AudioSpecificConfig.lowComplexity(RATE, 2, 960, null)).programConfig());
            assertFalse(AudioSpecificConfig.parse(AudioSpecificConfig.lowComplexity(RATE, 2, 1024, null)).frameLength()
                    == 960);
        }
    }
}
