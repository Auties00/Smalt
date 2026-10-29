package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.internal.Ac3Crc;
import com.github.auties00.smalt.audio.ac3.internal.ChannelMap;
import com.github.auties00.smalt.audio.ac3.internal.DitherClearing;
import com.github.auties00.smalt.audio.ac3.internal.StreamInspector;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic signals to E-AC-3 independent substreams with every frame size, at the AC-3
 * rates and the reduced ones, and at bit rates beyond the AC-3 table, then checks the frames as
 * {@link Ac3EncoderTest} checks AC-3: Smalt's decoder reproduces the input, ffmpeg decodes them
 * with {@code -xerror}, and its decode matches Smalt's.
 */
class Eac3EncoderTest {
    @TempDir
    Path directory;

    static List<Packet> encode(Eac3EncoderOptions options, AudioBuffer input) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Eac3Encoder(options)) {
            encoder.encode(input, packets::add);
            encoder.flush(packets::add);
        }
        return packets;
    }

    private Path write(String name, byte[] data) {
        try {
            var path = directory.resolve(name);
            Files.write(path, data);
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("frames")
    class Frames {
        @ParameterizedTest(name = "{0} at {1} Hz, {2} bit/s, {3} blocks per frame")
        @CsvSource({
                "2.0, 48000, 192000, 6, 50",
                "2.0, 48000, 192000, 3, 45",
                "2.0, 48000, 192000, 2, 40",
                "2.0, 48000, 192000, 1, 30",
                "1.0, 44100, 96000, 6, 45",
                "2.0, 32000, 128000, 6, 45",
                "2.0+LFE, 48000, 256000, 3, 45",
                "5.1, 48000, 448000, 6, 50",
                "2.0, 24000, 96000, 6, 40",
                "5.0, 22050, 192000, 6, 35",
                "1.0, 16000, 48000, 6, 35",
                "5.1, 48000, 1000000, 6, 60",
                "2.0, 48000, 3000000, 1, 55",
                "2.0, 48000, 64000, 6, 20"})
        @DisplayName("round-trip through Smalt and decode cleanly in ffmpeg")
        void roundTrip(String name, int sampleRate, int bitrate, int blocks, double floor) {
            var layout = Ac3EncoderTest.LAYOUTS.get(name);
            var options = Eac3EncoderOptions.of(sampleRate, layout).toBuilder().blocksPerFrame(blocks).bitrate(bitrate).build();
            assertEquals(256 * blocks, options.frameSize());
            var input = Ac3EncoderTest.signal(layout, sampleRate, 1.5);
            var packets = encode(options, input);
            for (var packet : packets) {
                assertEquals(256 * blocks, packet.duration());
                assertTrue(packet.data().length <= 4096, "frame of " + packet.data().length + " bytes");
                assertEquals(16, (packet.data()[5] & 0xFF) >> 3, "bsid");
            }
            var stream = Ac3EncoderTest.concatenate(packets);
            var expectedBytes = (double) bitrate * 256 * blocks * packets.size() / 8 / sampleRate;
            assertTrue(Math.abs(stream.length - expectedBytes) <= 2.0 * packets.size(), "constant bit rate: " + stream.length);
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            assertEquals(sampleRate, decoded.sampleRate());
            assertEquals(layout.channels(), decoded.layout().channels());
            assertTrue(decoded.frames() >= input.frames() + 256, "the flush covers the delay");
            var snr = Ac3EncoderTest.roundTripSnr(input, decoded, 256);
            System.out.printf("E-AC-3 %s %d Hz %d bit/s %d blocks: round-trip SNR %.2f dB%n", name, sampleRate, bitrate,
                    blocks, snr);
            assertTrue(snr >= floor, "round-trip SNR " + snr);
            // ffmpeg 7.1 does not implement the reduced sampling rates of E-AC-3
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent() && sampleRate >= 32000) {
                Oracle.assertDecodesCleanly(write("out.ec3", stream));
                // ffmpeg 7.1 mis-synthesizes the short blocks of every channel after the first,
                // so only the first output channel is held to its decode, and the decoders
                // generate dither differently, so both decode the stream without dither
                var undithered = com.github.auties00.smalt.audio.ac3.internal.DitherClearing.clear(stream);
                var ffmpeg = Ac3TestSupport.ffmpeg(write("undithered.ec3", undithered), "-drc_scale", "0");
                var reference = Ac3TestSupport.decode(undithered, Ac3DecoderOptions.fullRange());
                var oracle = Ac3TestSupport.channelSnr(ffmpeg, reference.interleaved(), layout.channels())[0];
                System.out.printf("  first channel against ffmpeg's decode: %.2f dB%n", oracle);
                assertTrue(oracle >= 40, "ffmpeg agreement " + oracle);
            }
        }

        @Test
        @DisplayName("match the AC-3 encoder's quality at the same rate with six blocks per frame")
        void againstAc3() {
            var layout = ChannelLayout.SURROUND_5_1;
            var input = Ac3EncoderTest.signal(layout, 48000, 1.5);
            var enhanced = Ac3EncoderTest.roundTripSnr(input, Ac3TestSupport.decode(Ac3EncoderTest.concatenate(
                    encode(Eac3EncoderOptions.of(48000, layout).toBuilder().bitrate(384000).build(), input)), Ac3DecoderOptions.fullRange()), 256);
            var core = Ac3EncoderTest.roundTripSnr(input, Ac3TestSupport.decode(Ac3EncoderTest.concatenate(
                    Ac3EncoderTest.encode(Ac3EncoderOptions.of(48000, layout).toBuilder().bitrate(384000).build(), input)),
                    Ac3DecoderOptions.fullRange()), 256);
            System.out.printf("5.1 at 384 kbit/s: E-AC-3 %.2f dB, AC-3 %.2f dB%n", enhanced, core);
            assertTrue(enhanced >= core - 0.5, "E-AC-3 " + enhanced + " vs AC-3 " + core);
        }

        @Test
        @DisplayName("probe as E-AC-3 in ffprobe at the configured bit rate")
        void probe() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var layout = ChannelLayout.SURROUND_5_1;
            var options = Eac3EncoderOptions.of(48000, layout).toBuilder().bitrate(768000).build();
            var path = write("probe.ec3", Ac3EncoderTest.concatenate(encode(options, Ac3EncoderTest.signal(layout, 48000, 0.5))));
            var probe = Oracle.probe(path);
            assertTrue(probe.contains("\"eac3\"") && probe.contains("\"channels\": 6"), probe);
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("default to six blocks per frame and offer a one-block real-time preset")
        void presets() {
            assertEquals(6, Eac3EncoderOptions.defaults().blocksPerFrame());
            assertEquals(1536, Eac3EncoderOptions.defaults().frameSize());
            assertEquals(192000, Eac3EncoderOptions.defaults().effectiveBitrate());
            assertEquals(1, Eac3EncoderOptions.forRealtime().blocksPerFrame());
            try (var encoder = new Eac3Encoder(Eac3EncoderOptions.forRealtime())) {
                assertEquals(256, encoder.frameSize());
                assertEquals(256, encoder.delay());
                assertEquals(Codec.EAC3, encoder.codec());
            }
        }

        @Test
        @DisplayName("map unsupported rates and layouts to the closest codable ones, keeping the reduced rates")
        void mapping() {
            assertEquals(48000, Eac3EncoderOptions.of(96000, ChannelLayout.STEREO).sampleRate());
            assertEquals(24000, Eac3EncoderOptions.of(24000, ChannelLayout.STEREO).sampleRate());
            assertEquals(22050, Eac3EncoderOptions.of(22050, ChannelLayout.STEREO).sampleRate());
            assertEquals(ChannelLayout.SURROUND_7_1, Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_7_1).layout());
            assertEquals(ChannelLayout.SURROUND_7_1, Eac3EncoderOptions.of(48000, ChannelLayout.defaultFor(8)).layout());
            assertEquals(7, Eac3EncoderOptions.of(48000, new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                    Speaker.FRONT_CENTER, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT)))
                    .layout().channels());
            // without a center the side pair forms the 2/2 core and the back pair a dependent substream
            var noCenter = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
            assertEquals(noCenter, Eac3EncoderOptions.of(48000, noCenter).layout());
            var unknown = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.TOP_BACK_CENTER,
                    Speaker.BOTTOM_FRONT_LEFT, Speaker.BOTTOM_FRONT_RIGHT));
            assertEquals(ChannelLayout.STEREO, Eac3EncoderOptions.of(48000, unknown).layout());
        }

        @Test
        @DisplayName("bound the bit rate by the frame size and accept rates beyond the AC-3 table")
        void bitrateRange() {
            var options = Eac3EncoderOptions.defaults();
            assertEquals(1024000, options.maximumBitrate());
            assertEquals(6144000, options.toBuilder().blocksPerFrame(1).build().maximumBitrate());
            assertTrue(options.minimumBitrate() >= 32000);
            assertTrue(options.toBuilder().blocksPerFrame(1).build().minimumBitrate() > options.minimumBitrate());
            assertEquals(999_999, options.toBuilder().bitrate(999_999).build().effectiveBitrate());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(1_100_000).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(8000).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().blocksPerFrame(4).build());
            assertThrows(IllegalArgumentException.class, () -> Eac3EncoderOptions.of(24000, ChannelLayout.STEREO)
                    .toBuilder().blocksPerFrame(3).build());
            var surround = Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_5_1).toBuilder().blocksPerFrame(1).build();
            assertTrue(surround.effectiveBitrate() >= surround.minimumBitrate()
                    && surround.effectiveBitrate() <= surround.maximumBitrate());
        }

        @Test
        @DisplayName("allow spectral extension and keep the adaptive hybrid transform and pre-noise processing off by default")
        void tools() {
            var defaults = Eac3EncoderOptions.defaults();
            assertTrue(defaults.spectralExtension());
            assertFalse(defaults.hybridTransform());
            assertFalse(defaults.preNoiseProcessing());
            var changed = defaults.toBuilder().spectralExtension(false).hybridTransform(true).preNoiseProcessing(true).build();
            assertFalse(changed.spectralExtension());
            assertTrue(changed.hybridTransform());
            assertTrue(changed.preNoiseProcessing());
            assertEquals(changed, Eac3EncoderOptions.builder().spectralExtension(false).hybridTransform(true)
                    .preNoiseProcessing(true).build());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(defaults, changed);
            assertTrue(changed.toString().contains("hybridTransform=true"), changed.toString());
        }

        @Test
        @DisplayName("default 7.1 to 768 kbit/s and bound it by the frames of both substreams")
        void surroundBitrate() {
            var options = Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_7_1);
            assertEquals(768000, options.effectiveBitrate());
            var stereo = Eac3EncoderOptions.defaults();
            assertTrue(options.maximumBitrate() > stereo.maximumBitrate());
            assertTrue(options.minimumBitrate() > stereo.minimumBitrate());
            assertEquals(options.maximumBitrate(), options.toBuilder().bitrate(options.maximumBitrate()).build()
                    .effectiveBitrate());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(options.maximumBitrate() + 1000).build());
        }

        @Test
        @DisplayName("are reachable through AudioEncoders, clamping the bit rate and mapping real time to one block")
        void registry() {
            assertTrue(AudioEncoders.isSupported(Codec.EAC3));
            try (var encoder = AudioEncoders.create(Codec.EAC3, AudioEncoderOptions.builder().bitrate(5_000_000).build())) {
                assertEquals(Codec.EAC3, encoder.codec());
                assertEquals(1536, encoder.frameSize());
                assertEquals(1024000, ((Eac3Encoder) encoder).options().bitrate().orElseThrow());
            }
            try (var encoder = AudioEncoders.create(Codec.EAC3, AudioEncoderOptions.forRealtime(48000, ChannelLayout.STEREO))) {
                assertEquals(256, encoder.frameSize());
            }
        }
    }

    /**
     * Returns the program of a pinned FATE stream, decoded by Smalt, cut to a duration.
     */
    static AudioBuffer program(int sample, double seconds) {
        var decoded = Ac3TestSupport.decode(Ac3TestSupport.read(Ac3TestSupport.SAMPLES.get(sample).fetch()),
                Ac3DecoderOptions.fullRange());
        var frames = Math.min(decoded.frames(), (int) (48000 * seconds));
        var planes = new float[decoded.planes().size()][];
        for (var ch = 0; ch < planes.length; ch++) {
            planes[ch] = Arrays.copyOf(decoded.planes().get(ch), frames);
        }
        return new AudioBuffer(decoded.layout(), 48000, planes, frames);
    }

    /**
     * Returns a tone per channel: 200 Hz for the first channel and 150 Hz more for each next one,
     * at an amplitude of 0.3.
     */
    static AudioBuffer tones(ChannelLayout layout, double seconds) {
        var frames = (int) (48000 * seconds);
        var planes = new float[layout.channels()][frames];
        for (var ch = 0; ch < layout.channels(); ch++) {
            for (var i = 0; i < frames; i++) {
                planes[ch][i] = (float) (0.3 * Math.sin(2 * Math.PI * (200 + 150 * ch) * i / 48000.0));
            }
        }
        return new AudioBuffer(layout, 48000, planes, frames);
    }

    /**
     * Returns the frequency of the tone {@link #tones(ChannelLayout, double)} gives a speaker.
     */
    static double toneOf(ChannelLayout layout, Speaker speaker) {
        return 200 + 150 * layout.indexOf(speaker);
    }

    /**
     * Returns sharp onsets of noise and a 2.5 kHz tone every 200 ms over a quiet tone.
     */
    static AudioBuffer onsets(ChannelLayout layout, double seconds) {
        var frames = (int) (48000 * seconds);
        var planes = new float[layout.channels()][frames];
        var random = new Random(7);
        for (var ch = 0; ch < layout.channels(); ch++) {
            for (var i = 0; i < frames; i++) {
                planes[ch][i] = (float) (0.003 * Math.sin(2 * Math.PI * (300 + 40 * ch) * i / 48000.0));
            }
            for (var hit = 0; hit < seconds * 5; hit++) {
                var start = (int) (hit * 48000 * 0.2 + 977 * ch + 131 * (hit % 7));
                for (var i = 0; i < 1200 && start + i < frames; i++) {
                    var envelope = Math.exp(-i / 180.0) * Math.min(1, i / 8.0);
                    planes[ch][start + i] += (float) (0.6 * envelope * (random.nextGaussian() * 0.5
                            + Math.sin(2 * Math.PI * 2500 * i / 48000.0)));
                }
            }
        }
        return new AudioBuffer(layout, 48000, planes, frames);
    }

    /**
     * Interleaves the full-bandwidth channels of the input and of the delay-compensated decoded
     * output, in the input's order.
     */
    static float[][] aligned(AudioBuffer input, Ac3TestSupport.Decoded decoded) {
        var channels = new ArrayList<Integer>();
        for (var ch = 0; ch < input.channels(); ch++) {
            if (input.layout().speakers().get(ch) != Speaker.LOW_FREQUENCY) {
                channels.add(ch);
            }
        }
        var frames = Math.min(input.frames(), decoded.frames() - 256);
        var expected = new float[frames * channels.size()];
        var actual = new float[frames * channels.size()];
        for (var c = 0; c < channels.size(); c++) {
            var ch = channels.get(c);
            var source = input.plane(ch);
            var output = decoded.planes().get(decoded.layout().indexOf(input.layout().speakers().get(ch)));
            for (var i = 0; i < frames; i++) {
                expected[i * channels.size() + c] = source[i];
                actual[i * channels.size() + c] = output[i + 256];
            }
        }
        return new float[][]{expected, actual, {channels.size()}};
    }

    /**
     * Returns the SNR of the decoded output against the input over the frequencies below an MDCT
     * coefficient.
     */
    static double inBandSnr(AudioBuffer input, Ac3TestSupport.Decoded decoded, int below) {
        var pair = aligned(input, decoded);
        return Ac3TestSupport.spectralAgreement(pair[0], pair[1], (int) pair[2][0], below, below, below)[0];
    }

    @Nested
    @DisplayName("spectral extension")
    class SpectralExtension {
        @ParameterizedTest(name = "{0} at {1} bit/s: coefficients {2} to {3}")
        @CsvSource({"2.0, 64000, -1, -1", "2.0, 96000, 85, 157", "2.0, 128000, 109, 181", "2.0, 160000, 133, 181",
                "2.0, 192000, -1, -1", "5.1, 256000, 85, 157", "5.1, 320000, 109, 181", "5.1, 448000, 133, 181",
                "5.1, 640000, -1, -1", "1.0, 48000, 85, 157"})
        @DisplayName("extend the band from 44 to 92 kbit/s per full-bandwidth channel, from higher up at higher rates")
        void tiers(String name, int bitrate, int begin, int end) {
            var layout = Ac3EncoderTest.LAYOUTS.get(name);
            var input = Ac3EncoderTest.signal(layout, 48000, 0.3);
            var options = Eac3EncoderOptions.of(48000, layout).toBuilder().bitrate(bitrate).build();
            for (var frame : StreamInspector.frames(Ac3EncoderTest.concatenate(encode(options, input)))) {
                assertEquals(begin, frame.spxBegin());
                assertEquals(end, frame.spxEnd());
            }
            for (var frame : StreamInspector.frames(Ac3EncoderTest.concatenate(encode(options.toBuilder().spectralExtension(false).build(),
                    input)))) {
                assertEquals(-1, frame.spxBegin());
            }
        }

        @ParameterizedTest(name = "{1} bit/s, {2} blocks per frame")
        @CsvSource({"4, 96000, 6", "4, 128000, 6", "5, 128000, 6", "4, 128000, 1"})
        @DisplayName("raise the SNR below the extension region of film sound")
        void gain(int sample, int bitrate, int blocks) {
            var input = program(sample, 4);
            var options = Eac3EncoderOptions.of(48000, input.layout()).toBuilder().bitrate(bitrate).blocksPerFrame(blocks).build();
            var begin = StreamInspector.frames(Ac3EncoderTest.concatenate(encode(options, input))).getFirst().spxBegin();
            var extended = inBandSnr(input, Ac3TestSupport.decode(Ac3EncoderTest.concatenate(encode(options, input)),
                    Ac3DecoderOptions.fullRange()), begin);
            var plain = inBandSnr(input, Ac3TestSupport.decode(Ac3EncoderTest.concatenate(encode(
                    options.toBuilder().spectralExtension(false).build(), input)), Ac3DecoderOptions.fullRange()), begin);
            System.out.printf("%s at %d bit/s, %d blocks: SNR below coefficient %d %.2f dB with, %.2f dB without%n",
                    Ac3TestSupport.SAMPLES.get(sample), bitrate, blocks, begin, extended, plain);
            assertTrue(extended >= plain + 0.5, "with " + extended + " without " + plain);
        }

        @Test
        @DisplayName("decode in ffmpeg as in Smalt below the extension, with the same band energies above it")
        void ffmpeg() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = Ac3EncoderTest.stereoMusic(48000, 4);
            // ffmpeg 7.1 synthesizes the short blocks of the second channel differently, so no switching
            var options = Eac3EncoderOptions.defaults().toBuilder().bitrate(128000).blockSwitching(false).build();
            var stream = DitherClearing.clear(Ac3EncoderTest.concatenate(encode(options, input)));
            var frame = StreamInspector.frames(stream).getFirst();
            assertEquals(109, frame.spxBegin());
            var ffmpeg = Ac3TestSupport.ffmpeg(write("spx.ec3", stream), "-drc_scale", "0");
            var smalt = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()).interleaved();
            // the extension region blends in noise that each decoder draws from its own generator
            var agreement = Ac3TestSupport.spectralAgreement(ffmpeg, smalt, 2, frame.spxBegin() - 12, frame.spxBegin(),
                    frame.spxEnd());
            System.out.printf("ffmpeg agreement below the extension %.2f dB, band energies within %.2f dB%n", agreement[0],
                    agreement[1]);
            assertTrue(agreement[0] >= 80, "below the extension " + agreement[0]);
            assertTrue(agreement[1] <= 1.0, "band energies " + agreement[1]);
        }
    }

    @Nested
    @DisplayName("dependent substreams")
    class Dependent {
        @Test
        @DisplayName("code 7.1 as a 5.1 downmix and a dependent substream with the side and back pairs")
        void surround71() {
            var layout = ChannelLayout.SURROUND_7_1;
            var input = Ac3EncoderTest.signal(layout, 48000, 1.5);
            var options = Eac3EncoderOptions.of(48000, layout);
            var packets = encode(options, input);
            var stream = Ac3EncoderTest.concatenate(packets);
            var frames = StreamInspector.frames(stream);
            assertEquals(2 * packets.size(), frames.size());
            for (var index = 0; index < frames.size(); index += 2) {
                assertEquals(0, frames.get(index).streamType());
                assertEquals(7, frames.get(index).acmod());
                assertEquals(1, frames.get(index + 1).streamType());
                assertEquals(ChannelMap.EXTENSION_MAP, frames.get(index + 1).channelMap());
                assertEquals(0x1A00, frames.get(index + 1).channelMap());
            }
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            assertEquals(layout, decoded.layout());
            var snr = Ac3EncoderTest.roundTripSnr(input, decoded, 256);
            System.out.printf("7.1 at 768 kbit/s: round-trip SNR %.2f dB%n", snr);
            assertTrue(snr >= 40, "round-trip SNR " + snr);
            var core = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange().toBuilder().channelExtensions(false).build());
            assertEquals(6, core.layout().channels());
            var side = input.plane(layout.indexOf(Speaker.SIDE_LEFT));
            var back = input.plane(layout.indexOf(Speaker.BACK_LEFT));
            var downmix = core.planes().get(core.layout().indexOf(Speaker.SIDE_LEFT));
            var expected = new float[input.frames()];
            var actual = new float[input.frames()];
            for (var i = 0; i < input.frames(); i++) {
                expected[i] = (float) Math.sqrt(0.5) * (side[i] + back[i]);
                actual[i] = downmix[i + 256];
            }
            var downmixSnr = Ac3TestSupport.compare(expected, actual)[0];
            System.out.printf("  5.1 core surround against the -3 dB mix: %.2f dB%n", downmixSnr);
            assertTrue(downmixSnr >= 30, "core surround " + downmixSnr);
        }

        @Test
        @DisplayName("probe as 7.1 in ffprobe and decode in ffmpeg as in Smalt")
        void ffmpeg() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var layout = ChannelLayout.SURROUND_7_1;
            var input = Ac3EncoderTest.signal(layout, 48000, 1);
            var stream = Ac3EncoderTest.concatenate(encode(Eac3EncoderOptions.of(48000, layout).toBuilder().bitrate(1100000).build(), input));
            var path = write("71.ec3", stream);
            Oracle.assertDecodesCleanly(path);
            var probe = Oracle.probe(path);
            assertTrue(probe.contains("\"channels\": 8") && probe.contains("\"channel_layout\": \"7.1\""), probe);
            // ffmpeg 7.1 synthesizes the short blocks of every channel after the first differently
            var plain = DitherClearing.clear(Ac3EncoderTest.concatenate(encode(Eac3EncoderOptions.of(48000, layout)
                    .toBuilder().bitrate(1100000).blockSwitching(false).build(), input)));
            var ffmpeg = Ac3TestSupport.ffmpeg(write("71-plain.ec3", plain), "-drc_scale", "0");
            var smalt = Ac3TestSupport.decode(plain, Ac3DecoderOptions.fullRange());
            var agreement = Ac3TestSupport.channelSnr(ffmpeg, smalt.interleaved(), 8);
            System.out.println("7.1 agreement with ffmpeg per channel: " + Arrays.toString(agreement));
            for (var value : agreement) {
                assertTrue(value >= 90, Arrays.toString(agreement));
            }
            var seven = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
            var path70 = write("70.ec3", Ac3EncoderTest.concatenate(encode(Eac3EncoderOptions.of(48000, seven),
                    Ac3EncoderTest.signal(seven, 48000, 0.5))));
            Oracle.assertDecodesCleanly(path70);
            assertTrue(Oracle.probe(path70).contains("\"channels\": 7"), Oracle.probe(path70));
        }

        @ParameterizedTest(name = "custom channel map {0}")
        @CsvSource({
                "0x0060, SURROUND_DIRECT_LEFT SURROUND_DIRECT_RIGHT WIDE_LEFT WIDE_RIGHT, true",
                "0x018A, BACK_CENTER TOP_CENTER TOP_FRONT_CENTER LOW_FREQUENCY_2, false",
                "0x0014, TOP_FRONT_LEFT TOP_FRONT_RIGHT TOP_BACK_LEFT TOP_BACK_RIGHT, true",
                "0x0600, FRONT_LEFT_OF_CENTER FRONT_RIGHT_OF_CENTER BACK_LEFT BACK_RIGHT, false"
        })
        @DisplayName("name the locations of a custom channel map, keep the core surrounds, and match ffmpeg's layout")
        void customMap(String map, String locations, boolean ffmpegSides) {
            var channelMap = Integer.decode(map);
            var relabeled = Arrays.stream(locations.split(" ")).map(Speaker::valueOf).toList();
            var layout = ChannelLayout.SURROUND_7_1;
            var input = Ac3EncoderTest.signal(layout, 48000, 1);
            var options = Eac3EncoderOptions.of(48000, layout).toBuilder().bitrate(1100000).blockSwitching(false).build();
            var stream = remap(DitherClearing.clear(Ac3EncoderTest.concatenate(encode(options, input))), channelMap);
            assertTrue(StreamInspector.frames(stream).stream().filter(frame -> frame.streamType() == 1)
                    .allMatch(frame -> frame.channelMap() == channelMap));
            var smalt = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            var speakers = new ArrayList<>(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
            speakers.addAll(relabeled);
            speakers.sort(null);
            assertEquals(new ChannelLayout(speakers), smalt.layout());
            // the dependent substream codes the side and rear pairs of the input, now under the map's locations
            var coded = List.of(Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT);
            for (var ch = 0; ch < 4; ch++) {
                var source = input.plane(layout.indexOf(coded.get(ch)));
                var decoded = smalt.planes().get(smalt.layout().indexOf(relabeled.get(ch)));
                var actual = Arrays.copyOfRange(decoded, 256, 256 + input.frames());
                var snr = Ac3TestSupport.compare(source, actual)[0];
                assertTrue(snr >= 30, relabeled.get(ch) + " carries " + coded.get(ch) + " at " + snr + " dB");
            }
            var core = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange().toBuilder().channelExtensions(false)
                    .build());
            for (var side : List.of(Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT)) {
                assertArrayEquals(core.planes().get(core.layout().indexOf(side)),
                        smalt.planes().get(smalt.layout().indexOf(side)), side + " is not the core surround");
            }
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = write("custom-" + map + ".ec3", stream);
            var probe = Oracle.probe(path);
            assertEquals(smalt.layout().speakers(), ffmpegSpeakers(probe), probe);
            var ffmpeg = Ac3TestSupport.ffmpeg(path, "-drc_scale", "0");
            var agreement = Ac3TestSupport.channelSnr(ffmpeg, smalt.interleaved(), smalt.layout().channels());
            System.out.println("custom map " + map + " agreement with ffmpeg per channel: " + Arrays.toString(agreement));
            for (var ch = 0; ch < agreement.length; ch++) {
                var speaker = smalt.layout().speakers().get(ch);
                // ffmpeg 7.1 fills its side pair from the dependent substream instead of the core surrounds when
                // the map has the back center or the front-of-center pair, duplicating channels
                if (!ffmpegSides && (speaker == Speaker.SIDE_LEFT || speaker == Speaker.SIDE_RIGHT)) {
                    continue;
                }
                assertTrue(agreement[ch] >= 90, speaker + ": " + Arrays.toString(agreement));
            }
        }
    }

    /**
     * Rewrites the custom channel map of every dependent substream frame of a 7.1 stream, whose
     * map names four locations, and recomputes the frame CRC.
     */
    static byte[] remap(byte[] stream, int channelMap) {
        var out = stream.clone();
        for (var position = 0; position < out.length; ) {
            var bytes = 2 * (((out[position + 2] & 7) << 8 | out[position + 3] & 0xFF) + 1);
            if ((out[position + 2] & 0xC0) >>> 6 == 1) {
                // chanmape follows compre (bit 34 after the sync word) and its optional 8-bit compr
                var compre = readBits(out, position * 8 + 16 + 34, 1);
                var chanmape = position * 8 + 16 + 35 + 8 * compre;
                assertEquals(1, readBits(out, chanmape, 1));
                assertEquals(ChannelMap.EXTENSION_MAP, readBits(out, chanmape + 1, 16));
                for (var i = 0; i < 16; i++) {
                    var bit = chanmape + 1 + i;
                    var mask = 0x80 >>> (bit & 7);
                    out[bit >>> 3] = (byte) ((channelMap & 0x8000 >>> i) != 0 ? out[bit >>> 3] | mask
                            : out[bit >>> 3] & ~mask);
                }
                var crc = Ac3Crc.compute(out, position + 2, bytes - 4);
                out[position + bytes - 2] = (byte) (crc >>> 8);
                out[position + bytes - 1] = (byte) crc;
            }
            position += bytes;
        }
        return out;
    }

    /** Reads an MSB-first bit field. */
    static int readBits(byte[] data, int bit, int count) {
        var value = 0;
        for (var i = 0; i < count; i++) {
            var at = bit + i;
            value = value << 1 | (data[at >>> 3] >>> (7 - (at & 7)) & 1);
        }
        return value;
    }

    /** Returns the speakers of the channel layout ffprobe reports, in ffmpeg's channel order. */
    static List<Speaker> ffmpegSpeakers(String probe) {
        var matcher = Pattern.compile("\"channel_layout\": \"[^\"]*?\\(?([A-Z0-9+]+)\\)?\"")
                .matcher(probe);
        assertTrue(matcher.find(), probe);
        var names = Map.ofEntries(Map.entry("FL", Speaker.FRONT_LEFT),
                Map.entry("FR", Speaker.FRONT_RIGHT), Map.entry("FC", Speaker.FRONT_CENTER),
                Map.entry("LFE", Speaker.LOW_FREQUENCY), Map.entry("BL", Speaker.BACK_LEFT),
                Map.entry("BR", Speaker.BACK_RIGHT), Map.entry("FLC", Speaker.FRONT_LEFT_OF_CENTER),
                Map.entry("FRC", Speaker.FRONT_RIGHT_OF_CENTER), Map.entry("BC", Speaker.BACK_CENTER),
                Map.entry("SL", Speaker.SIDE_LEFT), Map.entry("SR", Speaker.SIDE_RIGHT),
                Map.entry("TC", Speaker.TOP_CENTER), Map.entry("TFL", Speaker.TOP_FRONT_LEFT),
                Map.entry("TFC", Speaker.TOP_FRONT_CENTER), Map.entry("TFR", Speaker.TOP_FRONT_RIGHT),
                Map.entry("TBL", Speaker.TOP_BACK_LEFT), Map.entry("TBC", Speaker.TOP_BACK_CENTER),
                Map.entry("TBR", Speaker.TOP_BACK_RIGHT), Map.entry("WL", Speaker.WIDE_LEFT),
                Map.entry("WR", Speaker.WIDE_RIGHT), Map.entry("SDL", Speaker.SURROUND_DIRECT_LEFT),
                Map.entry("SDR", Speaker.SURROUND_DIRECT_RIGHT),
                Map.entry("LFE2", Speaker.LOW_FREQUENCY_2), Map.entry("TSL", Speaker.TOP_SIDE_LEFT),
                Map.entry("TSR", Speaker.TOP_SIDE_RIGHT), Map.entry("BFC", Speaker.BOTTOM_FRONT_CENTER),
                Map.entry("BFL", Speaker.BOTTOM_FRONT_LEFT),
                Map.entry("BFR", Speaker.BOTTOM_FRONT_RIGHT));
        return Arrays.stream(matcher.group(1).split("\\+")).map(name -> {
            var speaker = names.get(name);
            assertTrue(speaker != null, "unmapped ffmpeg channel " + name + " in " + probe);
            return speaker;
        }).toList();
    }

    @Nested
    @DisplayName("adaptive hybrid transform")
    class HybridTransform {
        @Test
        @DisplayName("code stationary channels of film sound when allowed, raising the SNR, and decode cleanly in ffmpeg")
        void allowed() {
            var input = program(4, 4);
            var options = Eac3EncoderOptions.of(48000, input.layout()).toBuilder().bitrate(128000).spectralExtension(false).build();
            var plainStream = Ac3EncoderTest.concatenate(encode(options, input));
            assertTrue(StreamInspector.frames(plainStream).stream().allMatch(frame -> frame.hybrid() == 0));
            var stream = Ac3EncoderTest.concatenate(encode(options.toBuilder().hybridTransform(true).build(), input));
            var hybrid = StreamInspector.frames(stream).stream().mapToInt(StreamInspector.Frame::hybrid).sum();
            var with = inBandSnr(input, Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()), 253);
            var without = inBandSnr(input, Ac3TestSupport.decode(plainStream, Ac3DecoderOptions.fullRange()), 253);
            System.out.printf("adaptive hybrid transform in %d channel frames: SNR %.2f dB with, %.2f dB without%n", hybrid,
                    with, without);
            assertTrue(hybrid > 0, "no channel used the transform");
            assertTrue(with >= without + 0.2, "with " + with + " without " + without);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("aht.ec3", stream));
            }
        }
    }

    @Nested
    @DisplayName("transient pre-noise processing")
    class PreNoise {
        @Test
        @DisplayName("send onset locations when allowed, lowering the error ahead of the onsets, and decode cleanly in ffmpeg")
        void allowed() {
            var input = onsets(ChannelLayout.STEREO, 4);
            var options = Eac3EncoderOptions.defaults().toBuilder().bitrate(128000).blockSwitching(false).build();
            var plainStream = Ac3EncoderTest.concatenate(encode(options, input));
            assertTrue(StreamInspector.frames(plainStream).stream().allMatch(frame -> frame.locations()[0] < 0
                    && frame.locations()[1] < 0));
            var stream = Ac3EncoderTest.concatenate(encode(options.toBuilder().preNoiseProcessing(true).build(), input));
            var with = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            var without = Ac3TestSupport.decode(plainStream, Ac3DecoderOptions.fullRange());
            var signal = 0.0;
            var errorWith = 0.0;
            var errorWithout = 0.0;
            var corrections = 0;
            var frameStart = 0;
            for (var frame : StreamInspector.frames(stream)) {
                for (var ch = 0; ch < 2; ch++) {
                    var location = frame.locations()[ch];
                    if (location < 0) {
                        continue;
                    }
                    corrections++;
                    // the transient lies 256 decoded samples after the location, in the last block of
                    // the frame or in the next frame, where decoders apply the data
                    assertTrue(location >= 1024 && location < 2560, "location " + location);
                    var transient_ = location + 256;
                    var preNoise = transient_ - (transient_ / 256 - 1) * 256;
                    var source = input.plane(ch);
                    for (var k = transient_ - preNoise - frame.lengths()[ch] - 256; k < transient_; k++) {
                        var at = frameStart + k;
                        var original = source[at - 256];
                        signal += original * original;
                        errorWith += Math.pow(original - with.planes().get(ch)[at], 2);
                        errorWithout += Math.pow(original - without.planes().get(ch)[at], 2);
                    }
                }
                frameStart += 1536;
            }
            var gain = 10 * Math.log10(errorWithout / errorWith);
            System.out.printf("%d corrections: SNR ahead of the onsets %.2f dB with, %.2f dB without%n", corrections,
                    10 * Math.log10(signal / errorWith), 10 * Math.log10(signal / errorWithout));
            assertTrue(corrections >= 5, corrections + " corrections");
            assertTrue(gain >= 1, "error reduction " + gain + " dB");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("tpnp.ec3", stream));
            }
        }
    }

    /**
     * Parses a space-separated list of speakers.
     */
    static ChannelLayout speakers(String names) {
        var list = new ArrayList<Speaker>();
        for (var name : names.split(" ")) {
            list.add(Speaker.valueOf(name));
        }
        return new ChannelLayout(List.copyOf(list));
    }

    @Nested
    @DisplayName("programs beyond 7.1")
    class Extended {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "5.1.2, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY SIDE_LEFT SIDE_RIGHT TOP_FRONT_LEFT TOP_FRONT_RIGHT, a010",
                "6.1, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_CENTER SIDE_LEFT SIDE_RIGHT, 1900",
                "5.1 with a top center, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY SIDE_LEFT SIDE_RIGHT TOP_CENTER, b880",
                "5.1 with a second LFE, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY SIDE_LEFT SIDE_RIGHT LOW_FREQUENCY_2, 0003",
                "7.1.2, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT SIDE_LEFT SIDE_RIGHT TOP_FRONT_LEFT TOP_FRONT_RIGHT, 0210",
                "9.1, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT SIDE_LEFT SIDE_RIGHT WIDE_LEFT WIDE_RIGHT, 0220",
                "7.1.4, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT SIDE_LEFT SIDE_RIGHT TOP_FRONT_LEFT TOP_FRONT_RIGHT TOP_BACK_LEFT TOP_BACK_RIGHT, b800 0210 0004",
                "11.1, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT FRONT_LEFT_OF_CENTER FRONT_RIGHT_OF_CENTER SIDE_LEFT SIDE_RIGHT WIDE_LEFT WIDE_RIGHT, f800 0600 0020"})
        @DisplayName("code every channel through dependent substreams with custom channel maps, a tone per channel decoding in its place")
        void tones(String name, String names, String maps) {
            var layout = speakers(names);
            var options = Eac3EncoderOptions.of(48000, layout);
            assertEquals(layout, options.layout());
            var input = Eac3EncoderTest.tones(layout, 2);
            var stream = Ac3EncoderTest.concatenate(encode(options, input));
            var frames = StreamInspector.frames(stream);
            var expected = maps.split(" ");
            var dependents = expected.length;
            for (var index = 0; index < 1 + dependents; index++) {
                var frame = frames.get(index);
                assertEquals(index == 0 ? 0 : 1, frame.streamType());
                assertEquals(index == 0 ? 0 : index - 1, frame.substreamId());
                if (index > 0) {
                    assertEquals(Integer.parseInt(expected[index - 1], 16), frame.channelMap(), "channel map");
                }
            }
            assertEquals(0, frames.get(1 + dependents).streamType(), "the next access unit");
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            assertEquals(layout, decoded.layout());
            var samples = decoded.interleaved();
            var channels = layout.channels();
            for (var ch = 0; ch < channels; ch++) {
                var speaker = layout.speakers().get(ch);
                if (speaker == Speaker.LOW_FREQUENCY) {
                    continue;
                }
                var own = MediaFoundation.tone(samples, channels, ch, 9600, 48000, toneOf(layout, speaker) / 48000);
                assertEquals(0.3, own, 0.01, speaker + " carries its tone");
                for (var other : layout.speakers()) {
                    if (other == speaker || other == Speaker.LOW_FREQUENCY) {
                        continue;
                    }
                    var leak = MediaFoundation.tone(samples, channels, ch, 9600, 48000, toneOf(layout, other) / 48000);
                    assertTrue(leak < 0.003, speaker + " carries " + other + " at " + leak);
                }
            }
            if (dependents == 1 && Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var file = write(name + ".ec3", stream);
                var probe = Oracle.probe(file);
                assertTrue(probe.contains("\"channels\": " + channels), probe);
                Oracle.assertDecodesCleanly(file);
            }
        }

        @Test
        @DisplayName("mix extra channels into the core only while one dependent substream carries them")
        void core() {
            var layout = speakers("FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_CENTER SIDE_LEFT SIDE_RIGHT");
            var input = Eac3EncoderTest.tones(layout, 2);
            var stream = Ac3EncoderTest.concatenate(encode(Eac3EncoderOptions.of(48000, layout), input));
            var core = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange().toBuilder().channelExtensions(false).build());
            assertEquals(6, core.layout().channels());
            var samples = core.interleaved();
            var left = core.layout().indexOf(Speaker.SIDE_LEFT);
            // the back center reaches each side surround at -3 dB, which the mix scales by -3 dB
            var center = MediaFoundation.tone(samples, 6, left, 9600, 48000, toneOf(layout, Speaker.BACK_CENTER) / 48000);
            var own = MediaFoundation.tone(samples, 6, left, 9600, 48000, toneOf(layout, Speaker.SIDE_LEFT) / 48000);
            assertEquals(0.15, center, 0.01);
            assertEquals(0.3 * Math.sqrt(0.5), own, 0.01);
        }

        @Test
        @DisplayName("bound the bit rate by the frames of every substream")
        void bitrates() {
            var layout = speakers("FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT SIDE_LEFT SIDE_RIGHT "
                    + "TOP_FRONT_LEFT TOP_FRONT_RIGHT TOP_BACK_LEFT TOP_BACK_RIGHT");
            var options = Eac3EncoderOptions.of(48000, layout);
            assertEquals(768000 + 96000 * 4, options.effectiveBitrate());
            assertTrue(options.maximumBitrate() > 1024000 * 2);
            var input = Eac3EncoderTest.tones(layout, 0.5);
            for (var bitrate : new int[]{options.minimumBitrate(), options.maximumBitrate()}) {
                var stream = Ac3EncoderTest.concatenate(encode(options.toBuilder().bitrate(bitrate).build(), input));
                for (var frame : StreamInspector.frames(stream)) {
                    assertTrue(frame.blocks() == 6);
                }
            }
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(options.maximumBitrate() + 1).build());
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().bitrate(options.minimumBitrate() - 1).build());
        }
    }

    @Nested
    @DisplayName("enhanced coupling")
    class EnhancedCoupling {
        @Test
        @DisplayName("is off by default, codes the coupled bands with amplitudes when allowed, and keeps the quality of standard coupling")
        void allowed() {
            assertFalse(Eac3EncoderOptions.defaults().enhancedCoupling());
            var input = Eac3MediaFoundationTest.coupledStereo(6);
            var standard = Eac3EncoderOptions.defaults().toBuilder().bitrate(96000).spectralExtension(false).build();
            var enhanced = standard.toBuilder().enhancedCoupling(true).build();
            assertNotEquals(standard, enhanced);
            var plainStream = Ac3EncoderTest.concatenate(encode(standard, input));
            var stream = Ac3EncoderTest.concatenate(encode(enhanced, input));
            assertEquals(0, StreamInspector.frames(plainStream).stream().mapToInt(StreamInspector.Frame::enhancedCoupling).sum());
            var blocks = StreamInspector.frames(stream).stream().mapToInt(StreamInspector.Frame::enhancedCoupling).sum();
            assertTrue(blocks >= 50, blocks + " blocks with enhanced coupling");
            var plainSnr = Ac3EncoderTest.roundTripSnr(input, Ac3TestSupport.decode(plainStream, Ac3DecoderOptions.fullRange()), 256);
            var snr = Ac3EncoderTest.roundTripSnr(input, Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()), 256);
            System.out.printf("enhanced coupling: %d blocks, round-trip SNR %.2f dB, standard coupling %.2f dB%n", blocks,
                    snr, plainSnr);
            assertTrue(snr >= plainSnr - 3, "round-trip SNR " + snr + " against " + plainSnr);
        }

        @ParameterizedTest(name = "{0} channels at {1} bit/s, spectral extension {2}")
        @CsvSource({"2, 96000, true, 30", "6, 256000, true, 300", "6, 224000, false, 300"})
        @DisplayName("code enhanced coupling with spectral extension and with the coupled channel flags of multichannel programs")
        void layouts(int channels, int bitrate, boolean spectralExtension, int least) {
            var layout = channels == 2 ? ChannelLayout.STEREO : ChannelLayout.SURROUND_5_1;
            var frames = 48000 * 4;
            var planes = new float[channels][frames];
            var random = new Random(5);
            for (var ch = 0; ch < channels; ch++) {
                var speaker = layout.speakers().get(ch);
                for (var i = 0; i < frames; i++) {
                    var t = i / 48000.0;
                    var common = 0.08 * Math.sin(2 * Math.PI * 4000 * t) + 0.04 * Math.sin(2 * Math.PI * 7000 * t);
                    planes[ch][i] = speaker == Speaker.LOW_FREQUENCY ? (float) (0.2 * Math.sin(2 * Math.PI * 60 * t))
                            : (float) (0.15 * Math.sin(2 * Math.PI * (220 + 90 * ch) * t) + common * (1 - 0.1 * ch)
                            + 0.005 * random.nextGaussian());
                }
            }
            var input = new AudioBuffer(layout, 48000, planes, frames);
            var standard = Eac3EncoderOptions.of(48000, layout).toBuilder().bitrate(bitrate)
                    .spectralExtension(spectralExtension).build();
            var plainStream = Ac3EncoderTest.concatenate(encode(standard, input));
            var stream = Ac3EncoderTest.concatenate(encode(standard.toBuilder().enhancedCoupling(true).build(), input));
            var parsed = StreamInspector.frames(stream);
            var blocks = parsed.stream().mapToInt(StreamInspector.Frame::enhancedCoupling).sum();
            assertTrue(blocks >= least, blocks + " blocks with enhanced coupling in " + parsed.size() + " frames");
            if (spectralExtension) {
                assertTrue(parsed.stream().allMatch(frame -> frame.spxBegin() >= 0), "spectral extension in every frame");
            }
            var plainSnr = Ac3EncoderTest.roundTripSnr(input, Ac3TestSupport.decode(plainStream, Ac3DecoderOptions.fullRange()), 256);
            var snr = Ac3EncoderTest.roundTripSnr(input, Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()), 256);
            System.out.printf("enhanced coupling, %d channels: %d blocks, round-trip SNR %.2f dB, standard coupling %.2f dB%n",
                    channels, blocks, snr, plainSnr);
            assertTrue(snr >= plainSnr - 3 && snr > 10, "round-trip SNR " + snr + " against " + plainSnr);
        }
    }

    @Nested
    @DisplayName("transient pre-noise processing lookahead")
    class PreNoiseLookahead {
        @ParameterizedTest(name = "{0} blocks per frame")
        @CsvSource({"6, 1", "3, 3"})
        @DisplayName("holds each frame until the frames its data concerns are coded, then emits every packet in order")
        void lookahead(int blocks, int held) {
            var input = onsets(ChannelLayout.STEREO, 2);
            var options = Eac3EncoderOptions.defaults().toBuilder().bitrate(128000).blocksPerFrame(blocks)
                    .blockSwitching(false).build();
            var plain = encode(options, input);
            var withData = new ArrayList<Packet>();
            try (var encoder = new Eac3Encoder(options.toBuilder().preNoiseProcessing(true).build())) {
                var frameSize = 256 * blocks;
                var frames = new float[2][frameSize];
                for (var f = 0; f < held; f++) {
                    for (var ch = 0; ch < 2; ch++) {
                        System.arraycopy(input.plane(ch), f * frameSize, frames[ch], 0, frameSize);
                    }
                    encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, frames, frameSize), withData::add);
                }
                assertTrue(withData.isEmpty(), "no packet before the lookahead is coded");
                var rest = input.frames() - held * frameSize;
                var tail = new float[2][rest];
                for (var ch = 0; ch < 2; ch++) {
                    System.arraycopy(input.plane(ch), held * frameSize, tail[ch], 0, rest);
                }
                encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, tail, rest), withData::add);
                encoder.flush(withData::add);
            }
            assertEquals(plain.size(), withData.size());
            for (var index = 0; index < plain.size(); index++) {
                assertEquals(plain.get(index).pts(), withData.get(index).pts());
                assertEquals(plain.get(index).data().length, withData.get(index).data().length, "constant bit rate");
            }
            var frames = StreamInspector.frames(Ac3EncoderTest.concatenate(withData));
            var corrections = 0;
            for (var frame : frames) {
                for (var location : frame.locations()) {
                    if (location >= 0) {
                        corrections++;
                        assertTrue(location >= 1024 && location < 2560, "location " + location);
                    }
                }
            }
            assertTrue(corrections >= 4, corrections + " corrections");
        }
    }
}
