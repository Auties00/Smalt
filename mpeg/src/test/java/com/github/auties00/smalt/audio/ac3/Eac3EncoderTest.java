package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
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
import java.util.Random;

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
            var noCenter = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
            assertEquals(5, Eac3EncoderOptions.of(48000, noCenter).layout().channels());
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
                    assertTrue(location >= 512 && location < 1536, "location " + location);
                    var preNoise = location - (location / 256 - 1) * 256;
                    var source = input.plane(ch);
                    for (var k = location - preNoise - frame.lengths()[ch] - 256; k < location; k++) {
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
}
