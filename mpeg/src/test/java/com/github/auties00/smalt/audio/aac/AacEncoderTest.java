package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the encoder through independent decoders (ffmpeg and this module's decoder): clean
 * decoding, exact long-term bitrate, quality against ffmpeg's native encoder at the same
 * bitrate, pre-echo, silence, clipping and the {@code AudioEncoder} contract.
 */
class AacEncoderTest {
    private static final Path WORK = Path.of("target", "aac-encoder-tests");

    record Encoded(List<Packet> packets, byte[] config) {
        long bytes() {
            var total = 0L;
            for (var packet : packets) {
                total += packet.size();
            }
            return total;
        }

        List<byte[]> frames() {
            return packets.stream().map(Packet::data).toList();
        }
    }

    static Encoded encode(AacEncoderOptions config, float[][] input) {
        var encoder = new AacEncoder(config);
        var packets = new ArrayList<Packet>();
        encoder.encode(new AudioBuffer(config.layout(), config.sampleRate(), input, input[0].length), packets::add);
        encoder.flush(packets::add);
        return new Encoded(packets, encoder.codecConfig().orElseThrow());
    }

    static Path write(String name, Encoded encoded) {
        try {
            Files.createDirectories(WORK);
            var file = WORK.resolve(name + ".aac");
            Files.write(file, AacTestSupport.writeAdts(encoded.config(), encoded.frames()));
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes with ffmpeg and drops the encoder delay. */
    static float[][] ffmpegDecode(Path file, int channels) {
        var planes = AacTestSupport.deinterleave(Oracle.decodeAudio(file), channels);
        for (var c = 0; c < channels; c++) {
            planes[c] = Arrays.copyOfRange(planes[c], 1024, planes[c].length);
        }
        return planes;
    }

    /** Encodes with ffmpeg's native encoder and decodes the result, dropping its delay. */
    static float[][] ffmpegRoundTrip(String name, float[][] input, int rate, int bitrate) {
        try {
            Files.createDirectories(WORK);
            var wav = WORK.resolve(name + ".wav");
            Files.write(wav, TestSignals.wav(input, rate));
            var output = WORK.resolve(name + "-ffmpeg.aac");
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-i", wav.toString(),
                    "-c:a", "aac", "-b:a", Integer.toString(bitrate), "-f", "adts", output.toString()));
            return ffmpegDecode(output, input.length);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static ChannelLayout layout(int channels) {
        return switch (channels) {
            case 1 -> ChannelLayout.MONO;
            case 2 -> ChannelLayout.STEREO;
            default -> ChannelLayout.SURROUND_5_1;
        };
    }

    @Nested
    @DisplayName("bitstream validity")
    class Validity {
        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {7350, 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000})
        @DisplayName("decodes cleanly with ffmpeg at every sampling rate, identically with this decoder")
        void rates(int rate) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, rate, 2, 2);
            var bitrate = Math.min(6 * rate * 2 - 1000, Math.max(24000, rate * 2));
            var encoded = encode(AacEncoderOptions.of(rate, ChannelLayout.STEREO, bitrate), input);
            var file = write("rate-" + rate, encoded);
            Oracle.assertDecodesCleanly(file);
            var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), 2);
            var mine = AacTestSupport.decodeAll(new AacDecoder(AacDecoderRobustnessTest.track(encoded.config())), encoded.frames());
            assertEquals(reference[0].length, mine.frames());
            assertTrue(AacTestSupport.snr(reference, mine.planes()) > 80);
            assertTrue(TestSignals.segmentalSnr(input, ffmpegDecode(file, 2)) > 10, "the round trip resembles the input");
        }

        @ParameterizedTest(name = "{0} channels at {1} bps")
        @CsvSource({"1, 16000", "1, 64000", "2, 32000", "2, 320000", "6, 384000"})
        @DisplayName("decodes cleanly with ffmpeg for every layout and bitrate extreme")
        void layouts(int channels, int bitrate) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = TestSignals.generate(TestSignals.Kind.TRANSIENTS, 48000, channels, 3);
            var encoded = encode(AacEncoderOptions.of(48000, layout(channels), bitrate), input);
            var file = write("layout-" + channels + "-" + bitrate, encoded);
            Oracle.assertDecodesCleanly(file);
            var decoded = ffmpegDecode(file, channels);
            var wave = AacTestSupport.decodeAll(new AacDecoder(AacDecoderRobustnessTest.track(encoded.config())), encoded.frames());
            assertEquals(layout(channels), wave.layout());
            for (var c = 0; c < channels; c++) {
                var reference = new float[][]{Arrays.copyOf(input[c], input[c].length)};
                var snr = TestSignals.segmentalSnr(reference, new float[][]{decoded[c]});
                assertTrue(snr > (c == 3 && channels == 6 ? -1 : 10), "channel " + c + " segmental SNR " + snr);
            }
        }

        @Test
        @DisplayName("codes silence as silence in minimal frames")
        void silence() {
            var input = new float[2][48000 * 3];
            var encoded = encode(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 128000), input);
            var decoder = new AacDecoder(AacDecoderRobustnessTest.track(encoded.config()));
            var decoded = AacTestSupport.decodeAll(decoder, encoded.frames());
            for (var plane : decoded.planes()) {
                for (var value : plane) {
                    assertEquals(0, value, 1e-6);
                }
            }
            var variable = encode(AacEncoderOptions.defaults().toBuilder().bitrateMode(AacBitrateMode.VBR).build(), input);
            for (var frame : variable.frames()) {
                assertTrue(frame.length <= 8, "a silent frame takes " + frame.length + " bytes");
            }
        }

        @Test
        @DisplayName("clips out-of-range input instead of wrapping and codes NaN as silence")
        void clipping() {
            var input = new float[1][48000];
            for (var i = 0; i < input[0].length; i++) {
                input[0][i] = (float) (3 * Math.sin(2 * Math.PI * 1000 * i / 48000.0));
            }
            input[0][100] = Float.NaN;
            var encoded = encode(AacEncoderOptions.of(48000, ChannelLayout.MONO, 128000), input);
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacDecoderRobustnessTest.track(encoded.config())), encoded.frames());
            var clipped = new float[1][input[0].length];
            for (var i = 0; i < input[0].length; i++) {
                clipped[0][i] = Float.isNaN(input[0][i]) ? 0 : Math.max(-1, Math.min(1, input[0][i]));
            }
            var aligned = new float[][]{Arrays.copyOfRange(decoded.planes()[0], 1024, 1024 + clipped[0].length)};
            assertTrue(TestSignals.segmentalSnr(clipped, aligned) > 20, "clipped full-scale input survives the round trip");
            var peak = 0.0f;
            for (var value : aligned[0]) {
                peak = Math.max(peak, Math.abs(value));
            }
            assertTrue(peak < 1.3f, "peak " + peak);
        }

        @Test
        @DisplayName("keeps the energy before an attack low (pre-echo)")
        void preEcho() {
            var input = new float[1][48000];
            var random = new Random(3);
            for (var i = 24000; i < 24000 + 2000; i++) {
                input[0][i] = (float) (random.nextGaussian() * 0.5 * Math.exp(-(i - 24000) / 400.0));
            }
            var encoded = encode(AacEncoderOptions.of(48000, ChannelLayout.MONO, 64000), input);
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacDecoderRobustnessTest.track(encoded.config())), encoded.frames());
            var aligned = Arrays.copyOfRange(decoded.planes()[0], 1024, 1024 + input[0].length);
            double before = 0;
            for (var i = 24000 - 1024; i < 24000 - 256; i++) {
                before += aligned[i] * aligned[i];
            }
            double attack = 0;
            for (var i = 24000; i < 24000 + 768; i++) {
                attack += aligned[i] * aligned[i];
            }
            var ratio = 10 * Math.log10(before / attack);
            assertTrue(ratio < -35, "energy before the attack is " + ratio + " dB relative to the attack");
        }
    }

    @Nested
    @DisplayName("rate control")
    class RateControl {
        @ParameterizedTest(name = "{0} Hz, {1} channels, {2} bps")
        @CsvSource({"48000, 1, 16000", "44100, 1, 32000", "48000, 2, 64000", "48000, 2, 128000", "44100, 2, 128000",
                "48000, 2, 192000", "48000, 2, 256000", "48000, 2, 320000", "24000, 1, 24000", "96000, 2, 256000"})
        @DisplayName("keeps the bitrate within 5 percent over 30 seconds")
        void accuracy(int rate, int channels, int bitrate) {
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, rate, channels, 30);
            var encoded = encode(AacEncoderOptions.of(rate, layout(channels), bitrate), input);
            var actual = encoded.bytes() * 8.0 * rate / (encoded.packets().size() * 1024.0);
            assertEquals(bitrate, actual, bitrate * 0.05, "actual bitrate " + actual);
            for (var packet : encoded.packets()) {
                assertTrue(packet.size() * 8 <= 6144 * channels, "frame exceeds the decoder buffer");
            }
        }

        @Test
        @DisplayName("codes at a constant quality in VBR mode: near the nominal bitrate, better with it, sizes following the signal")
        void variable() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, 48000, 2, 10);
            var previousSnr = Double.NEGATIVE_INFINITY;
            for (var bitrate : new int[]{64000, 128000, 192000}) {
                var encoded = encode(AacEncoderOptions.defaults().toBuilder().bitrate(bitrate).bitrateMode(AacBitrateMode.VBR).build(), input);
                var actual = encoded.bytes() * 8.0 * 48000 / (encoded.packets().size() * 1024.0);
                assertEquals(bitrate, actual, bitrate * 0.3, "VBR bitrate " + actual);
                var file = write("vbr-" + bitrate, encoded);
                Oracle.assertDecodesCleanly(file);
                var snr = TestSignals.segmentalSnr(input, ffmpegDecode(file, 2));
                assertTrue(snr > previousSnr, "quality grows with the nominal bitrate");
                assertTrue(snr > 15, "segmental SNR " + snr);
                previousSnr = snr;
                var sizes = encoded.packets().stream().mapToInt(Packet::size).summaryStatistics();
                assertTrue(sizes.getMax() > 1.5 * sizes.getAverage(), "VBR frame sizes follow the signal");
            }
        }
    }

    @Nested
    @DisplayName("quality against ffmpeg's native encoder")
    class Quality {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TestSignals.Kind.class, names = {"SPEECH", "MUSIC", "TRANSIENTS", "NOISE"})
        @DisplayName("matches or beats ffmpeg's segmental SNR (within 1 dB) at every tested bitrate")
        void segmentalSnr(TestSignals.Kind kind) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            int[][] configs = {{48000, 1, 16000}, {48000, 1, 32000}, {48000, 2, 64000}, {48000, 2, 128000},
                    {48000, 2, 192000}, {44100, 2, 256000}};
            for (var config : configs) {
                var rate = config[0];
                var channels = config[1];
                var bitrate = config[2];
                var input = TestSignals.generate(kind, rate, channels, 8);
                var name = kind + "-" + rate + "-" + channels + "-" + bitrate;
                var mine = ffmpegDecode(write(name, encode(AacEncoderOptions.of(rate, layout(channels), bitrate), input)), channels);
                var theirs = ffmpegRoundTrip(name, input, rate, bitrate);
                var mineSnr = TestSignals.segmentalSnr(input, mine);
                var theirSnr = TestSignals.segmentalSnr(input, theirs);
                assertTrue(mineSnr >= theirSnr - 1, name + ": segmental SNR " + mineSnr + " vs ffmpeg " + theirSnr);
            }
        }

        @ParameterizedTest(name = "{0} bps per channel")
        @ValueSource(ints = {16000, 24000, 32000, 48000, 64000, 96000, 128000})
        @DisplayName("keeps at least ffmpeg's audio bandwidth")
        void bandwidth(int channelBitrate) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var random = new Random(5);
            var input = new float[2][48000 * 3];
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < input[c].length; i++) {
                    input[c][i] = (float) (random.nextGaussian() * 0.1);
                }
            }
            var name = "bandwidth-" + channelBitrate;
            var mine = ffmpegDecode(write(name, encode(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 2 * channelBitrate), input)), 2);
            var theirs = ffmpegRoundTrip(name, input, 48000, 2 * channelBitrate);
            var mineWidth = TestSignals.bandwidth(input[0], mine[0], 48000);
            var theirWidth = TestSignals.bandwidth(input[0], theirs[0], 48000);
            assertTrue(mineWidth >= theirWidth * 0.97, "bandwidth " + mineWidth + " vs ffmpeg " + theirWidth);
        }
    }

    @Nested
    @DisplayName("encoder contract")
    class Contract {
        @Test
        @DisplayName("reports its format, delay and AudioSpecificConfig")
        void properties() {
            var encoder = new AacEncoder(AacEncoderOptions.of(44100, ChannelLayout.STEREO, 128000));
            assertEquals(Codec.AAC, encoder.codec());
            assertEquals(44100, encoder.sampleRate());
            assertEquals(ChannelLayout.STEREO, encoder.layout());
            assertEquals(1024, encoder.frameSize());
            assertEquals(1024, encoder.delay());
            assertArrayEquals(new byte[]{0x12, 0x10}, encoder.codecConfig().orElseThrow());
            assertArrayEquals(new byte[]{0x11, (byte) 0x88},
                    new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.MONO, 64000)).codecConfig().orElseThrow());
            assertArrayEquals(new byte[]{0x11, (byte) 0xB0},
                    new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.SURROUND_5_1, 384000)).codecConfig().orElseThrow());
        }

        @Test
        @DisplayName("produces identical packets whatever the input block sizes, with sample timestamps")
        void blockSizes() {
            var input = TestSignals.generate(TestSignals.Kind.MUSIC, 48000, 2, 2);
            var whole = encode(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 128000), input);
            var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 128000));
            var packets = new ArrayList<Packet>();
            var random = new Random(9);
            var position = 0;
            while (position < input[0].length) {
                var count = Math.min(input[0].length - position, random.nextInt(3000));
                var planes = new float[2][];
                for (var c = 0; c < 2; c++) {
                    planes[c] = Arrays.copyOfRange(input[c], position, position + count);
                }
                encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, planes, count), packets::add);
                position += count;
            }
            encoder.flush(packets::add);
            assertEquals(whole.packets().size(), packets.size());
            for (var i = 0; i < packets.size(); i++) {
                assertArrayEquals(whole.packets().get(i).data(), packets.get(i).data());
                assertEquals(i * 1024L, packets.get(i).pts());
                assertEquals(1024, packets.get(i).duration());
            }
            assertEquals((input[0].length + 1023) / 1024 + 1, packets.size(), "flush covers every input sample");
        }

        @Test
        @DisplayName("emits nothing for no input and rejects input after a flush or of the wrong format")
        void states() {
            var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.MONO, 64000));
            var packets = new ArrayList<Packet>();
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, 16), packets::add));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 44100, 16), packets::add));
            encoder.flush(packets::add);
            assertTrue(packets.isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 48000, 16), packets::add));
        }

        @Test
        @DisplayName("validates its configuration")
        void configuration() {
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.of(44000, ChannelLayout.STEREO, 128000));
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.of(48000, ChannelLayout.defaultFor(10), 640000));
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.of(48000, ChannelLayout.STEREO, 1000));
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.of(8000, ChannelLayout.STEREO, 320000));
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.defaults().toBuilder().bandwidth(30000).build());
            assertEquals(12345, AacEncoderOptions.defaults().toBuilder().bandwidth(12345).build().effectiveBandwidth());
            assertEquals(4000, AacEncoderOptions.of(8000, ChannelLayout.MONO, 24000).effectiveBandwidth());
        }
    }
}
