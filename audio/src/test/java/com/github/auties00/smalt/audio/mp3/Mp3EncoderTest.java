package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.PerceptualDistortion;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link Mp3Encoder} end to end: every stream is decoded back with Smalt's own decoder
 * and, when ffmpeg is installed, checked with {@code ffmpeg -xerror} and compared with ffmpeg's
 * float decoder, which must agree with Smalt's to 90 dB.
 */
class Mp3EncoderTest {
    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(44100, 2, 128), Arguments.of(48000, 2, 192), Arguments.of(32000, 2, 96),
                Arguments.of(44100, 1, 64), Arguments.of(24000, 2, 64), Arguments.of(22050, 2, 56),
                Arguments.of(16000, 1, 32), Arguments.of(12000, 2, 32), Arguments.of(11025, 1, 16),
                Arguments.of(8000, 1, 8), Arguments.of(48000, 1, 32), Arguments.of(44100, 2, 320));
    }

    static Mp3EncoderOptions cbr(int rate, int channels, int kbps) {
        return Mp3EncoderOptions.builder().format(rate, Mp3Encoding.layout(channels)).bitrate(kbps * 1000).build();
    }

    static Path save(String name, byte[] data) {
        try {
            var file = Files.createTempFile("mp3enc-" + name, ".mp3");
            file.toFile().deleteOnExit();
            Files.write(file, data);
            return file;
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static float[] ffmpegDecode(Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-xerror", "-c:a", "mp3float",
                "-i", file.toString(), "-f", "f32le", "-"));
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrip {
        @ParameterizedTest(name = "{0} Hz, {1} channels, {2} kbit/s")
        @MethodSource("com.github.auties00.smalt.audio.mp3.Mp3EncoderTest#formats")
        @DisplayName("decodes with Smalt and ffmpeg alike, at the encoder delay and with the frame count of the input")
        void everyFormat(int rate, int channels, int kbps) {
            var options = cbr(rate, channels, kbps);
            var length = rate * 2 + 123;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, channels, rate, length, rate + channels);
            var packets = Mp3Encoding.encode(options, input);
            var frameSize = rate >= 32000 ? 1152 : 576;
            assertEquals((length + Mp3Encoder.DELAY + frameSize - 1) / frameSize, packets.size());
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                var header = MpegAudioHeader.read(packet.data(), 0);
                assertTrue(MpegAudioHeader.isValid(header));
                assertEquals(rate, MpegAudioHeader.sampleRate(header));
                assertEquals(kbps, MpegAudioHeader.bitrateKbps(header));
                assertEquals(channels, MpegAudioHeader.channels(header));
                assertEquals(MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header), packet.size());
                assertEquals((long) i * frameSize, packet.pts());
            }
            var stream = Mp3Encoding.concatenate(packets);
            var decoded = Mp3Encoding.decode(stream);
            assertEquals(packets.size() * frameSize, decoded.frames());
            var snr = Mp3Encoding.snr(input, decoded.interleaved(), Mp3Encoder.DELAY);
            var minimum = kbps / channels >= 64 ? 12 : kbps / channels >= 32 ? 8 : 5;
            assertTrue(snr >= minimum, "SNR " + snr + " dB below " + minimum);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var reference = ffmpegDecode(save("rt", stream));
                var start = 0;
                var ours = decoded.interleaved();
                assertTrue(reference.length >= ours.length - frameSize * channels, "ffmpeg decoded fewer samples");
                var common = Math.min(reference.length, ours.length);
                var a = java.util.Arrays.copyOfRange(reference, start, common);
                var b = java.util.Arrays.copyOfRange(ours, start, common);
                assertTrue(Quality.snr(a, b) >= 90, "Smalt and ffmpeg decode differently");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Mp3StereoMode.class)
        @DisplayName("codes every stereo mode")
        void stereoModes(Mp3StereoMode mode) {
            var options = Mp3EncoderOptions.defaults().toBuilder().stereoMode(mode).build();
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, 44100, 7);
            var packets = Mp3Encoding.encode(options, input);
            var expected = switch (mode) {
                case STEREO -> 0;
                case JOINT_STEREO -> 1;
                case DUAL_CHANNEL -> 2;
            };
            for (var packet : packets) {
                assertEquals(expected, MpegAudioHeader.mode(MpegAudioHeader.read(packet.data(), 0)));
            }
            var decoded = Mp3Encoding.decode(Mp3Encoding.concatenate(packets));
            assertTrue(Mp3Encoding.snr(input, decoded.interleaved(), Mp3Encoder.DELAY) >= 12);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Mp3Encoding.Shape.class)
        @DisplayName("codes every signal shape, including silence, full scale and transients")
        void shapes(Mp3Encoding.Shape shape) {
            var options = Mp3EncoderOptions.defaults();
            var input = Mp3Encoding.signal(shape, 2, 44100, 44100 + 17, 3);
            var packets = Mp3Encoding.encode(options, input);
            var stream = Mp3Encoding.concatenate(packets);
            var decoded = Mp3Encoding.decode(stream);
            if (shape == Mp3Encoding.Shape.SILENCE) {
                for (var sample : decoded.interleaved()) {
                    assertEquals(0, sample);
                }
            } else if (shape != Mp3Encoding.Shape.NOISE && shape != Mp3Encoding.Shape.IMPULSE) {
                assertTrue(Mp3Encoding.snr(input, decoded.interleaved(), Mp3Encoder.DELAY) >= 6, shape.toString());
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(save(shape.name(), stream));
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"128", "64", "V2"})
        @DisplayName("perceptual distortion stays within 1.5 dB of LAME's at the same setting")
        void versusLame(String setting) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var length = 44100 * 4;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, length, 53);
            var variable = setting.startsWith("V");
            var options = variable ? Mp3EncoderOptions.ofVariableBitrate(Integer.parseInt(setting.substring(1)))
                    : Mp3EncoderOptions.ofConstantBitrate(Integer.parseInt(setting) * 1000);
            var ours = Mp3Encoding.decode(Mp3Encoding.concatenate(Mp3Encoding.encode(options, input))).interleaved();
            var raw = ByteBuffer.allocate(length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < length; i++) {
                raw.putFloat(input[0][i]).putFloat(input[1][i]);
            }
            var source = save("src", raw.array());
            var lameOptions = variable ? List.of("-q:a", setting.substring(1)) : List.of("-b:a", setting + "k");
            var arguments = new ArrayList<>(List.of("-v", "error", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", source.toString(),
                    "-c:a", "libmp3lame"));
            arguments.addAll(lameOptions);
            arguments.addAll(List.of("-f", "mp3", "-"));
            // LAME's own tag carries its delay, so ffmpeg's decode of its file is aligned with the input
            var lame = Oracle.decodeAudio(save("lame", Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), arguments)));
            var interleaved = Mp3Encoding.interleave(input);
            var oursNmr = PerceptualDistortion.measureInterleaved(interleaved, ours, 2, Mp3Encoder.DELAY, 44100);
            var lameNmr = PerceptualDistortion.measureInterleaved(interleaved, lame, 2, 0, 44100);
            assertTrue(oursNmr.nmr() <= lameNmr.nmr() + 1.5, oursNmr + " versus " + lameNmr);
        }

        @Test
        @DisplayName("writes a CRC that ffmpeg verifies")
        void crc() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var options = Mp3EncoderOptions.defaults().toBuilder().crc(true).build();
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.TRANSIENTS, 2, 44100, 44100, 5);
            var packets = Mp3Encoding.encode(options, input);
            for (var packet : packets) {
                assertTrue(MpegAudioHeader.hasCrc(MpegAudioHeader.read(packet.data(), 0)));
            }
            var file = save("crc", Mp3Encoding.concatenate(packets));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-xerror", "-err_detect", "crccheck",
                    "-i", file.toString(), "-f", "null", "-"));
        }

        @Test
        @DisplayName("without the bit reservoir every frame starts its own main data")
        void noReservoir() {
            var options = Mp3EncoderOptions.forRealtime();
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.TRANSIENTS, 2, 44100, 44100, 9);
            for (var packet : Mp3Encoding.encode(options, input)) {
                var data = packet.data();
                var mainDataBegin = (data[4] & 0xFF) << 1 | (data[5] & 0xFF) >> 7;
                assertEquals(0, mainDataBegin);
            }
        }
    }

    @Nested
    @DisplayName("bitrate control")
    class BitrateControl {
        @ParameterizedTest(name = "{0} kbit/s")
        @ValueSource(ints = {32, 64, 128, 256, 320})
        @DisplayName("constant bitrate keeps the exact average frame size with padding slots")
        void constant(int kbps) {
            var options = Mp3EncoderOptions.ofConstantBitrate(kbps * 1000);
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, 44100 * 3, 11);
            var packets = Mp3Encoding.encode(options, input);
            var bytes = Mp3Encoding.concatenate(packets).length;
            var expected = packets.size() * 1152.0 / 44100 * kbps * 1000 / 8;
            assertTrue(Math.abs(bytes - expected) <= 1, bytes + " bytes, expected " + expected);
        }

        @Test
        @DisplayName("variable bitrate grows with the quality and uses several bitrates")
        void variable() {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, 44100 * 3, 13);
            var previous = Integer.MAX_VALUE;
            for (var quality = 0; quality <= 9; quality += 3) {
                var packets = Mp3Encoding.encode(Mp3EncoderOptions.ofVariableBitrate(quality), input);
                var bytes = Mp3Encoding.concatenate(packets).length;
                assertTrue(bytes < previous, "quality " + quality + " is not smaller");
                previous = bytes;
                var decoded = Mp3Encoding.decode(Mp3Encoding.concatenate(packets));
                assertTrue(Mp3Encoding.snr(input, decoded.interleaved(), Mp3Encoder.DELAY) >= 8);
            }
            var rates = new java.util.HashSet<Integer>();
            for (var packet : Mp3Encoding.encode(Mp3EncoderOptions.ofVariableBitrate(4), Mp3Encoding.signal(Mp3Encoding.Shape.TRANSIENTS, 2, 44100, 44100 * 3, 1))) {
                rates.add(MpegAudioHeader.bitrateKbps(MpegAudioHeader.read(packet.data(), 0)));
            }
            assertTrue(rates.size() > 2, rates.toString());
        }

        @ParameterizedTest(name = "{0} kbit/s")
        @ValueSource(ints = {96, 160})
        @DisplayName("average bitrate lands within 15% of the target")
        void average(int kbps) {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, 44100 * 8, 17);
            var packets = Mp3Encoding.encode(Mp3EncoderOptions.ofAverageBitrate(kbps * 1000), input);
            var bits = Mp3Encoding.concatenate(packets).length * 8.0;
            var seconds = packets.size() * 1152.0 / 44100;
            var actual = bits / seconds / 1000;
            assertTrue(Math.abs(actual - kbps) <= kbps * 0.15, actual + " kbit/s for a target of " + kbps);
        }
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        @Test
        @DisplayName("an .mp3 file gets an Info tag frame with the exact frame count and a LAME tag giving the input length")
        void constantBitrateFile() {
            var options = Mp3EncoderOptions.defaults();
            var length = 44100 * 2 + 5;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, length, 19);
            var packets = Mp3Encoding.encode(options, input);
            var file = Mp3Encoding.file(options, packets, length);
            var tag = packets.get(0).size();
            assertEquals("Info", new String(file, 4 + 32, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
            assertEquals(packets.size(), ByteBuffer.wrap(file, 4 + 32 + 8, 4).getInt());
            assertEquals(file.length, ByteBuffer.wrap(file, 4 + 32 + 12, 4).getInt());
            assertEquals(file.length - tag, Mp3Encoding.concatenate(packets).length);
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(file))) {
                var track = AudioPipeline.firstAudio(demuxer);
                assertEquals(length, track.duration());
                assertEquals(Mp3Encoder.DELAY, track.skipSamples());
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(save("info", file));
            }
        }

        @Test
        @DisplayName("a variable bitrate .mp3 gets a Xing tag whose seek table points at the right frames")
        void variableBitrateSeekTable() {
            var options = Mp3EncoderOptions.ofVariableBitrate(2);
            var length = 44100 * 20;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.TRANSIENTS, 2, 44100, length, 23);
            var packets = Mp3Encoding.encode(options, input);
            var file = Mp3Encoding.file(options, packets, length);
            var side = 4 + 32;
            assertEquals("Xing", new String(file, side, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
            var total = ByteBuffer.wrap(file, side + 12, 4).getInt();
            var toc = java.util.Arrays.copyOfRange(file, side + 16, side + 116);
            var offsets = new long[packets.size()];
            var position = (long) (file.length - Mp3Encoding.concatenate(packets).length);
            for (var i = 0; i < packets.size(); i++) {
                offsets[i] = position;
                position += packets.get(i).size();
            }
            var previous = 0;
            for (var i = 0; i < 100; i++) {
                var entry = toc[i] & 0xFF;
                assertTrue(entry >= previous, "seek table not monotonic at " + i);
                previous = entry;
                var frame = (int) ((long) i * packets.size() / 100);
                var expected = offsets[frame] * 256 / total;
                assertTrue(Math.abs(entry - expected) <= 1, "entry " + i + ": " + entry + " for " + expected);
            }
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(file))) {
                var track = AudioPipeline.firstAudio(demuxer);
                assertEquals(length, track.duration());
                demuxer.seek(10_000_000);
                var packet = demuxer.read();
                assertTrue(Math.abs(packet.pts() - 441_000) <= 1152, "seek landed at " + packet.pts());
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = save("xing", file);
                Oracle.assertDecodesCleanly(path);
                var probe = Oracle.probe(path);
                var matcher = java.util.regex.Pattern.compile("\"duration\": \"([0-9.]+)\"").matcher(probe);
                assertTrue(matcher.find(), probe);
                assertEquals(packets.size() * 1152.0 / 44100, Double.parseDouble(matcher.group(1)), 0.03);
            }
        }

        @ParameterizedTest(name = "{0} Hz, {1} channel(s)")
        @org.junit.jupiter.params.provider.CsvSource({"44100, 2, 128", "48000, 1, 64", "22050, 2, 64", "8000, 1, 16"})
        @DisplayName("an .mp3 file trims the encoder delay and padding with its LAME tag, in Smalt and in ffmpeg")
        void lameTagGapless(int rate, int channels, int kbps) {
            var options = cbr(rate, channels, kbps);
            var length = rate + 777;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.IMPULSE, channels, rate, length, 0);
            var packets = Mp3Encoding.encode(options, input);
            var file = Mp3Encoding.file(options, packets, length);
            var decoded = decodeMp4(file);
            assertEquals((long) length * channels, decoded.length);
            assertEquals(length / 3, peak(decoded, channels), 1);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = save("lame-" + rate + "-" + channels, file);
                Oracle.assertDecodesCleanly(path);
                var reference = Oracle.decodeAudio(path);
                assertEquals((long) length * channels, reference.length, "ffmpeg keeps exactly the input samples");
                assertEquals(length / 3, peak(reference, channels), 1);
            }
        }

        private static int peak(float[] interleaved, int channels) {
            var peak = 0;
            for (var i = 0; i < interleaved.length / channels; i++) {
                if (Math.abs(interleaved[channels * i]) > Math.abs(interleaved[channels * peak])) {
                    peak = i;
                }
            }
            return peak;
        }

        @Test
        @DisplayName("an MP4 file trims the encoder delay with its edit list, restoring the input timing")
        void mp4EditList() {
            var options = Mp3EncoderOptions.defaults();
            var length = 44100 + 777;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.IMPULSE, 2, 44100, length, 0);
            var packets = Mp3Encoding.encode(options, input);
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink)) {
                muxer.addTrack(Mp3Encoding.track(options, length));
                for (var packet : packets) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var decoded = decodeMp4(sink.toByteArray());
            assertEquals(length * 2, decoded.length);
            var peak = 0;
            for (var i = 0; i < decoded.length / 2; i++) {
                if (Math.abs(decoded[2 * i]) > Math.abs(decoded[2 * peak])) {
                    peak = i;
                }
            }
            assertTrue(Math.abs(peak - length / 3) <= 2, "impulse at " + peak + " instead of " + length / 3);
        }

        private float[] decodeMp4(byte[] file) {
            try (var reader = com.github.auties00.smalt.audio.AudioReader.open(MediaSource.of(file))) {
                var out = new java.util.ArrayList<Float>();
                AudioBuffer buffer;
                while ((buffer = reader.read()) != null) {
                    for (var i = 0; i < buffer.frames(); i++) {
                        for (var c = 0; c < buffer.channels(); c++) {
                            out.add(buffer.plane(c)[i]);
                        }
                    }
                }
                var result = new float[out.size()];
                for (var i = 0; i < result.length; i++) {
                    result[i] = out.get(i);
                }
                return result;
            }
        }
    }

    @Nested
    @DisplayName("encoder contract")
    class Contract {
        @Test
        @DisplayName("reports MP3, the frame size and the delay, and no codec configuration")
        void properties() {
            var encoder = new Mp3Encoder();
            assertEquals(Codec.MP3, encoder.codec());
            assertEquals(44100, encoder.sampleRate());
            assertEquals(ChannelLayout.STEREO, encoder.layout());
            assertEquals(1152, encoder.frameSize());
            assertEquals(1057, encoder.delay());
            assertTrue(encoder.codecConfig().isEmpty());
            assertEquals(576, new Mp3Encoder(Mp3EncoderOptions.of(22050, ChannelLayout.MONO)).frameSize());
        }

        @Test
        @DisplayName("the decoded output is the input delayed by exactly delay() samples")
        void delay() {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.IMPULSE, 1, 44100, 20000, 0);
            var decoded = Mp3Encoding.decode(Mp3Encoding.concatenate(
                    Mp3Encoding.encode(Mp3EncoderOptions.of(44100, ChannelLayout.MONO), input))).interleaved();
            var peak = 0;
            for (var i = 0; i < decoded.length; i++) {
                if (Math.abs(decoded[i]) > Math.abs(decoded[peak])) {
                    peak = i;
                }
            }
            assertEquals(20000 / 3 + Mp3Encoder.DELAY, peak, 1);
        }

        @Test
        @DisplayName("produces the same bytes whatever the input chunking, and on a second run")
        void deterministic() {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.TRANSIENTS, 2, 44100, 44100 * 2, 29);
            var reference = Mp3Encoding.concatenate(Mp3Encoding.encode(new Mp3Encoder(), input, 44100 * 2));
            for (var chunk : new int[]{1, 575, 576, 1153}) {
                assertArrayEquals(reference, Mp3Encoding.concatenate(Mp3Encoding.encode(new Mp3Encoder(), input, chunk)));
            }
        }

        @Test
        @DisplayName("clips out-of-range samples and codes NaN and infinities as silence or full scale")
        void hostileSamples() {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, 20000, 31);
            for (var i = 0; i < 20000; i += 7) {
                input[0][i] = Float.NaN;
                input[1][i] = i % 2 == 0 ? Float.POSITIVE_INFINITY : -1e30f;
            }
            var packets = Mp3Encoding.encode(Mp3EncoderOptions.defaults(), input);
            var decoded = Mp3Encoding.decode(Mp3Encoding.concatenate(packets)).interleaved();
            for (var sample : decoded) {
                assertTrue(Float.isFinite(sample) && Math.abs(sample) < 2);
            }
        }

        @Test
        @DisplayName("an empty stream produces no frames, one sample produces a full stream")
        void tinyInputs() {
            var encoder = new Mp3Encoder();
            var packets = new ArrayList<Packet>();
            encoder.flush(packets::add);
            assertTrue(packets.isEmpty());
            var one = Mp3Encoding.encode(Mp3EncoderOptions.defaults(), new float[][]{{0.5f}, {-0.5f}});
            assertEquals((1 + Mp3Encoder.DELAY + 1151) / 1152, one.size());
        }

        @Test
        @DisplayName("rejects a mismatched input and input after flush")
        void misuse() {
            var encoder = new Mp3Encoder();
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 44100, 16), packet -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, 16), packet -> {
            }));
            encoder.flush(packet -> {
            });
            assertThrows(IllegalStateException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 44100, 16), packet -> {
            }));
        }

        @Test
        @DisplayName("finishes long noise streams at every mode within a time bound")
        void bounded() {
            var noise = Mp3Encoding.signal(Mp3Encoding.Shape.NOISE, 2, 8000, 8000 * 4, 37);
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                for (var kbps : new int[]{8, 160}) {
                    var options = Mp3EncoderOptions.builder().format(8000, ChannelLayout.STEREO).bitrate(kbps * 1000).build();
                    assertFalse(Mp3Encoding.encode(options, noise).isEmpty());
                }
                assertFalse(Mp3Encoding.encode(Mp3EncoderOptions.ofVariableBitrate(0), Mp3Encoding.signal(Mp3Encoding.Shape.NOISE, 2, 44100, 44100 * 2, 3)).isEmpty());
            });
        }
    }

    @Nested
    @DisplayName("registry")
    class Registry {
        @Test
        @DisplayName("AudioEncoders creates an MP3 encoder at the nearest Layer III rate and bitrate")
        void nearest() {
            assertTrue(AudioEncoders.isSupported(Codec.MP3));
            var encoder = AudioEncoders.create(Codec.MP3, AudioEncoderOptions.of(96000, ChannelLayout.STEREO)
                    .toBuilder().bitrate(130_000).build());
            assertEquals(48000, encoder.sampleRate());
            assertEquals(128_000, ((Mp3Encoder) encoder).options().bitrate());
            var realtime = AudioEncoders.create(Codec.MP3, AudioEncoderOptions.forRealtime(16000, ChannelLayout.MONO));
            assertFalse(((Mp3Encoder) realtime).options().bitReservoir());
            assertEquals(32_000, ((Mp3Encoder) realtime).options().bitrate());
            assertThrows(IllegalArgumentException.class, () -> AudioEncoders.create(Codec.MP3,
                    AudioEncoderOptions.of(48000, ChannelLayout.SURROUND_5_1)));
        }
    }

}
