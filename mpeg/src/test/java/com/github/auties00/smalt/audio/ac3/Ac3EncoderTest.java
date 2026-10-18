package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic multichannel signals in every audio coding mode, sampling rate and a range of
 * bit rates, then checks the frames three ways: Smalt's decoder reproduces the input, ffmpeg
 * decodes them with {@code -xerror}, and ffmpeg's output matches Smalt's (the decoders differ only
 * in their dither generators).
 */
class Ac3EncoderTest {
    @TempDir
    Path directory;

    /**
     * The layouts by name: every audio coding mode, with and without LFE.
     */
    static final Map<String, ChannelLayout> LAYOUTS = Map.of(
            "1.0", ChannelLayout.MONO,
            "2.0", ChannelLayout.STEREO,
            "3.0", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER),
            "2.1", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER),
            "3.1", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_CENTER),
            "2.2", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT),
            "5.0", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT),
            "5.1", ChannelLayout.SURROUND_5_1,
            "2.0+LFE", layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY),
            "1.0+LFE", layout(Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY));

    private static ChannelLayout layout(Speaker... speakers) {
        return new ChannelLayout(List.of(speakers));
    }

    /**
     * Returns a deterministic test signal: a different tone pair per channel, a low tone on the
     * LFE, and short 3 kHz bursts in the second half that trigger block switching.
     */
    static AudioBuffer signal(ChannelLayout layout, int sampleRate, double seconds) {
        var frames = (int) (sampleRate * seconds);
        var planes = new float[layout.channels()][frames];
        for (var ch = 0; ch < layout.channels(); ch++) {
            var lfe = layout.speakers().get(ch) == Speaker.LOW_FREQUENCY;
            var f1 = lfe ? 60.0 : 220.0 * (ch + 1);
            var f2 = lfe ? 90.0 : 1375.0 + 610.0 * ch;
            for (var i = 0; i < frames; i++) {
                var t = (double) i / sampleRate;
                var value = 0.3 * Math.sin(2 * Math.PI * f1 * t) + (lfe ? 0.1 : 0.15) * Math.sin(2 * Math.PI * f2 * t);
                var burst = i % (sampleRate / 5);
                if (!lfe && i > frames / 2 && burst < 96) {
                    value += 0.4 * Math.sin(Math.PI * burst / 96) * Math.sin(2 * Math.PI * 3000 * t);
                }
                planes[ch][i] = (float) value;
            }
        }
        return new AudioBuffer(layout, sampleRate, planes, frames);
    }

    static List<Packet> encode(Ac3EncoderOptions options, AudioBuffer input) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Ac3Encoder(options)) {
            encoder.encode(input, packets::add);
            encoder.flush(packets::add);
        }
        return packets;
    }

    static byte[] concatenate(List<Packet> packets) {
        var output = new ByteArrayOutputStream();
        packets.forEach(packet -> output.writeBytes(packet.data()));
        return output.toByteArray();
    }

    /**
     * Returns the SNR of the decoded output against the input, delay compensated, over the
     * full-bandwidth channels.
     */
    static double roundTripSnr(AudioBuffer input, Ac3TestSupport.Decoded decoded, int delay) {
        var signal = 0.0;
        var noise = 0.0;
        for (var ch = 0; ch < input.channels(); ch++) {
            var speaker = input.layout().speakers().get(ch);
            if (speaker == Speaker.LOW_FREQUENCY) {
                continue;
            }
            var index = decoded.layout().indexOf(speaker);
            if (index < 0) {
                index = decoded.layout().indexOf(switch (speaker) {
                    case BACK_LEFT -> Speaker.SIDE_LEFT;
                    case BACK_RIGHT -> Speaker.SIDE_RIGHT;
                    default -> speaker;
                });
            }
            var output = decoded.planes().get(index);
            var source = input.plane(ch);
            for (var i = 0; i < Math.min(input.frames(), output.length - delay); i++) {
                var difference = (double) source[i] - output[i + delay];
                signal += (double) source[i] * source[i];
                noise += difference * difference;
            }
        }
        return 10 * Math.log10(signal / noise);
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
    @DisplayName("audio coding modes")
    class Modes {
        @ParameterizedTest(name = "{0} at {1} Hz, {2} bit/s")
        @CsvSource({
                "1.0, 48000, 96000, 40",
                "1.0+LFE, 44100, 96000, 40",
                "2.0, 48000, 192000, 45",
                "2.0, 44100, 128000, 38",
                "2.0, 32000, 96000, 38",
                "2.0+LFE, 48000, 192000, 45",
                "3.0, 48000, 320000, 45",
                "2.1, 48000, 256000, 45",
                "3.1, 48000, 320000, 40",
                "2.2, 44100, 320000, 40",
                "5.0, 48000, 448000, 45",
                "5.1, 48000, 448000, 45",
                "5.1, 32000, 640000, 55",
                "5.1, 48000, 192000, 30"})
        @DisplayName("round-trip through Smalt and decode cleanly in ffmpeg")
        void roundTrip(String name, int sampleRate, int bitrate, double floor) {
            var layout = LAYOUTS.get(name);
            var options = Ac3EncoderOptions.of(sampleRate, layout).toBuilder().bitrate(bitrate).build();
            var input = signal(layout, sampleRate, 1.5);
            var packets = encode(options, input);
            var stream = concatenate(packets);
            var expectedBytes = (long) bitrate * 1536 * packets.size() / 8 / sampleRate;
            assertTrue(Math.abs(stream.length - expectedBytes) <= 2L * packets.size(), "constant bit rate: " + stream.length);
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            assertEquals(sampleRate, decoded.sampleRate());
            assertEquals(layout.channels(), decoded.layout().channels());
            assertTrue(decoded.frames() >= input.frames() + 256, "the flush covers the delay");
            var snr = roundTripSnr(input, decoded, 256);
            System.out.printf("%s %d Hz %d bit/s: round-trip SNR %.2f dB%n", name, sampleRate, bitrate, snr);
            assertTrue(snr >= floor, "round-trip SNR " + snr);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = write("out.ac3", stream);
                Oracle.assertDecodesCleanly(path);
                // ffmpeg 7.1 mis-synthesizes the short blocks of every channel after the first, so
                // only the first output channel (the first coded channel) is held to its decode
                var ffmpeg = Ac3TestSupport.ffmpeg(path, "-drc_scale", "0");
                var oracle = Ac3TestSupport.channelSnr(ffmpeg, decoded.interleaved(), layout.channels())[0];
                System.out.printf("  first channel against ffmpeg's decode: %.2f dB%n", oracle);
                assertTrue(oracle >= 40, "ffmpeg agreement " + oracle);
            }
        }

        @ParameterizedTest(name = "{0} bit/s")
        @CsvSource({"64000, 25", "128000, 44", "256000, 55", "448000, 55", "640000, 55"})
        @DisplayName("raise the quality with the bit rate")
        void bitrates(int bitrate, double floor) {
            var layout = ChannelLayout.STEREO;
            var input = signal(layout, 48000, 1);
            var decoded = Ac3TestSupport.decode(concatenate(encode(Ac3EncoderOptions.of(48000, layout).toBuilder().bitrate(bitrate).build(),
                    input)), Ac3DecoderOptions.fullRange());
            var snr = roundTripSnr(input, decoded, 256);
            System.out.printf("stereo %d bit/s: %.2f dB%n", bitrate, snr);
            assertTrue(snr >= floor, "SNR " + snr);
        }

        @Test
        @DisplayName("matches the quality of ffmpeg's encoder, which never switches blocks, with switching off")
        void againstFfmpeg() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var layout = ChannelLayout.STEREO;
            var input = signal(layout, 48000, 1.5);
            var ours = roundTripSnr(input, Ac3TestSupport.decode(concatenate(encode(Ac3EncoderOptions.of(48000, layout)
                    .toBuilder().bitrate(192000).blockSwitching(false).build(), input)), Ac3DecoderOptions.fullRange()), 256);
            var pcm = new byte[input.frames() * 8];
            var buffer = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < input.frames(); i++) {
                buffer.putFloat(input.plane(0)[i]).putFloat(input.plane(1)[i]);
            }
            var theirs = Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), pcm, List.of("-v", "error", "-f", "f32le",
                    "-ar", "48000", "-ac", "2", "-i", "-", "-c:a", "ac3", "-b:a", "192k", "-f", "ac3", "-"));
            var decoded = Ac3TestSupport.decode(theirs, Ac3DecoderOptions.fullRange());
            var reference = roundTripSnr(input, decoded, 256);
            System.out.printf("192 kbit/s stereo: Smalt %.2f dB, ffmpeg %.2f dB%n", ours, reference);
            assertTrue(ours >= reference - 3, "Smalt " + ours + " vs ffmpeg " + reference);
        }
    }

    /**
     * Returns the front pair of the music-like 5.1 signal of {@link Ac3Benchmark#music(int, int)}.
     */
    static AudioBuffer stereoMusic(int sampleRate, int seconds) {
        var surround = Ac3Benchmark.music(sampleRate, seconds);
        var planes = new float[][]{surround.plane(0), surround.plane(1)};
        return new AudioBuffer(ChannelLayout.STEREO, sampleRate, planes, surround.frames());
    }

    @Nested
    @DisplayName("block switching")
    class BlockSwitching {
        private int shortBlocks(int bitrate, AudioBuffer input) {
            var options = Ac3EncoderOptions.of(48000, ChannelLayout.STEREO).toBuilder().bitrate(bitrate).build();
            return StreamInspector.frames(concatenate(encode(options, input)))
                    .stream().mapToInt(StreamInspector.Frame::shortBlocks).sum();
        }

        @Test
        @DisplayName("switch fewer blocks on moderate bursts from 64 kbit/s per channel, still on sharp onsets")
        void relaxed() {
            var bursts = signal(ChannelLayout.STEREO, 48000, 1.5);
            var onsets = Eac3EncoderTest.onsets(ChannelLayout.STEREO, 1.5);
            assertTrue(shortBlocks(128000, bursts) < shortBlocks(96000, bursts));
            assertEquals(shortBlocks(96000, onsets), shortBlocks(256000, onsets));
            assertTrue(shortBlocks(256000, onsets) >= 10);
            var options = Ac3EncoderOptions.of(48000, ChannelLayout.STEREO).toBuilder().bitrate(256000).build();
            var snr = roundTripSnr(bursts, Ac3TestSupport.decode(concatenate(encode(options, bursts)),
                    Ac3DecoderOptions.fullRange()), 256);
            System.out.printf("bursts at 256 kbit/s stereo: round-trip SNR %.2f dB%n", snr);
            // the short blocks the bursts drew at the peak ratios of A/52 section 8.2.2 held it to about 57 dB
            assertTrue(snr >= 70, "round-trip SNR " + snr);
        }
    }

    @Nested
    @DisplayName("channel coupling")
    class Coupling {
        @ParameterizedTest(name = "{0} at {1} bit/s: coupled from mantissa {2}")
        @CsvSource({"2.0, 64000, 37", "2.0, 128000, 49", "2.0, 192000, 0", "5.1, 256000, 37", "5.1, 384000, 97",
                "5.1, 448000, 0", "1.0, 32000, 0", "2.0+LFE, 96000, 37"})
        @DisplayName("couple the high band below 80 kbit/s per full-bandwidth channel, from higher up with more channels")
        void policy(String name, int bitrate, int start) {
            var options = Ac3EncoderOptions.of(48000, LAYOUTS.get(name)).toBuilder().bitrate(bitrate).build();
            var lfe = options.layout().indexOf(Speaker.LOW_FREQUENCY) >= 0;
            var acmod = switch (options.layout().channels() - (lfe ? 1 : 0)) {
                case 1 -> 1;
                case 2 -> 2;
                default -> 7;
            };
            var engine = new com.github.auties00.smalt.audio.ac3.internal.FrameEncoder(acmod, lfe, 48000, bitrate, -31, true,
                    options.channelCoupling(), false, 6, com.github.auties00.smalt.audio.ac3.internal.EncoderTools.NONE, false,
                    com.github.auties00.smalt.audio.ac3.internal.Ac3Kernels.scalar());
            assertEquals(start != 0, engine.coupling());
            assertEquals(start, engine.couplingStart());
            var disabled = new com.github.auties00.smalt.audio.ac3.internal.FrameEncoder(acmod, lfe, 48000, bitrate, -31, true,
                    false, false, 6, com.github.auties00.smalt.audio.ac3.internal.EncoderTools.NONE, false,
                    com.github.auties00.smalt.audio.ac3.internal.Ac3Kernels.scalar());
            assertTrue(!disabled.coupling());
        }

        @ParameterizedTest(name = "{0} bit/s")
        @CsvSource({"64000", "96000", "128000"})
        @DisplayName("raise the quality of music at low bit rates and decode cleanly in ffmpeg")
        void quality(int bitrate) {
            var input = stereoMusic(48000, 4);
            var options = Ac3EncoderOptions.of(48000, ChannelLayout.STEREO).toBuilder().bitrate(bitrate).build();
            var coupledStream = concatenate(encode(options, input));
            var coupled = roundTripSnr(input, Ac3TestSupport.decode(coupledStream, Ac3DecoderOptions.fullRange()), 256);
            var plain = roundTripSnr(input, Ac3TestSupport.decode(concatenate(encode(options.toBuilder().channelCoupling(false).build(), input)),
                    Ac3DecoderOptions.fullRange()), 256);
            System.out.printf("music %d bit/s: coupled %.2f dB, uncoupled %.2f dB%n", bitrate, coupled, plain);
            assertTrue(coupled >= plain - 1, "coupled " + coupled + " vs " + plain);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("coupled.ac3", coupledStream));
                // the coupled band dithers zero-bit mantissas, which the decoders generate
                // differently, so the agreement is checked on the stream without dither
                var undithered = com.github.auties00.smalt.audio.ac3.internal.DitherClearing.clear(coupledStream);
                var ffmpeg = Ac3TestSupport.ffmpeg(write("undithered.ac3", undithered), "-drc_scale", "0");
                var decoded = Ac3TestSupport.decode(undithered, Ac3DecoderOptions.fullRange());
                var oracle = Ac3TestSupport.channelSnr(ffmpeg, decoded.interleaved(), 2)[0];
                assertTrue(oracle >= 40, "ffmpeg agreement " + oracle);
            }
        }

        @Test
        @DisplayName("keep channels whose high band the others do not share out of coupling")
        void unrelatedChannels() {
            var layout = ChannelLayout.SURROUND_5_1;
            var input = signal(layout, 48000, 1.5);
            var options = Ac3EncoderOptions.of(48000, layout).toBuilder().bitrate(256000).build();
            var coupled = roundTripSnr(input, Ac3TestSupport.decode(concatenate(encode(options, input)),
                    Ac3DecoderOptions.fullRange()), 256);
            var plain = roundTripSnr(input, Ac3TestSupport.decode(concatenate(encode(options.toBuilder().channelCoupling(false).build(), input)),
                    Ac3DecoderOptions.fullRange()), 256);
            System.out.printf("5.1 tones at 256 kbit/s: coupling allowed %.2f dB, off %.2f dB%n", coupled, plain);
            assertTrue(coupled >= plain - 1, "coupled " + coupled + " vs " + plain);
        }
    }

    @Nested
    @DisplayName("real time")
    class Realtime {
        @Test
        @DisplayName("take the faster search in the real-time preset only")
        void preset() {
            assertTrue(Ac3EncoderOptions.forRealtime().fastSearch());
            assertTrue(!Ac3EncoderOptions.defaults().fastSearch());
            assertTrue(!Ac3EncoderOptions.forRealtime().equals(Ac3EncoderOptions.defaults()));
            assertEquals(Ac3EncoderOptions.forRealtime(), Ac3EncoderOptions.defaults().toBuilder().fastSearch(true).build());
            assertTrue(Ac3EncoderOptions.forRealtime().toBuilder().build().fastSearch());
            assertTrue(Ac3EncoderOptions.forRealtime().toString().contains("fastSearch=true"));
            var registry = AudioEncoderOptions.forRealtime(48000, ChannelLayout.STEREO);
            try (var encoder = AudioEncoders.create(Codec.AC3, registry)) {
                assertTrue(((Ac3Encoder) encoder).options().fastSearch());
                assertEquals(1536, encoder.frameSize());
            }
        }

        @ParameterizedTest(name = "{0} bit/s")
        @CsvSource({"96000", "192000"})
        @DisplayName("code music within half a dB of the full search and decode cleanly in ffmpeg")
        void quality(int bitrate) {
            var input = stereoMusic(48000, 4);
            var options = Ac3EncoderOptions.of(48000, ChannelLayout.STEREO).toBuilder().bitrate(bitrate).build();
            var full = roundTripSnr(input, Ac3TestSupport.decode(concatenate(encode(options, input)),
                    Ac3DecoderOptions.fullRange()), 256);
            var fastStream = concatenate(encode(options.toBuilder().fastSearch(true).build(), input));
            var fast = roundTripSnr(input, Ac3TestSupport.decode(fastStream, Ac3DecoderOptions.fullRange()), 256);
            assertTrue(fast >= full - 0.5, "fast search " + fast + " dB against " + full + " dB");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("realtime.ac3", fastStream));
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("map unsupported rates and layouts to the closest codable ones")
        void mapping() {
            var options = Ac3EncoderOptions.of(22050, ChannelLayout.SURROUND_7_1);
            assertEquals(48000, options.sampleRate());
            assertTrue(options.layout().channels() <= 6);
            assertEquals(640000, Ac3EncoderOptions.closestBitrate(1_000_000));
            assertEquals(32000, Ac3EncoderOptions.closestBitrate(1));
            assertEquals(192000, Ac3EncoderOptions.closestBitrate(190000));
            assertTrue(Ac3EncoderOptions.defaults().channelCoupling());
            assertTrue(!Ac3EncoderOptions.defaults().toBuilder().channelCoupling(false).build().channelCoupling());
            assertTrue(!Ac3EncoderOptions.defaults().equals(Ac3EncoderOptions.defaults().toBuilder().channelCoupling(false).build()));
        }

        @Test
        @DisplayName("write the dialogue level into every frame")
        void dialogueLevel() {
            var options = Ac3EncoderOptions.defaults().toBuilder().dialogueLevel(-27).build();
            var stream = concatenate(encode(options, signal(options.layout(), options.sampleRate(), 0.2)));
            try (var decoder = new Ac3Decoder(Ac3TestSupport.track(Codec.AC3))) {
                decoder.decode(new Packet(0, Ac3TestSupport.units(stream).getFirst(), 0, 0, 1536, true), buffer -> {
                });
                assertEquals(-27, decoder.dialogueLevel().orElseThrow());
            }
        }

        @Test
        @DisplayName("reject input in another format than the options declare")
        void rejectsOtherFormats() {
            try (var encoder = new Ac3Encoder(Ac3EncoderOptions.defaults())) {
                var mono = new AudioBuffer(ChannelLayout.MONO, 48000, 1536);
                assertThrows(IllegalArgumentException.class, () -> encoder.encode(mono, packet -> {
                }));
            }
        }

        @Test
        @DisplayName("are reachable through AudioEncoders with the closest standard bit rate")
        void registry() {
            assertTrue(AudioEncoders.isSupported(Codec.AC3));
            try (var encoder = AudioEncoders.create(Codec.AC3, AudioEncoderOptions.builder().bitrate(200000).build())) {
                assertEquals(Codec.AC3, encoder.codec());
                assertEquals(1536, encoder.frameSize());
                assertEquals(256, encoder.delay());
                assertEquals(192000, ((Ac3Encoder) encoder).options().bitrate().orElseThrow());
            }
        }
    }
}
