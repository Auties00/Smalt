package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.dts.DtsDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the DTS decoder with ffmpeg's, used as a black box, on the dcadec streams of the FFmpeg FATE suite and on
 * streams ffmpeg's DTS encoder writes: the lossy core and its extensions within the float rounding of two float
 * decoders, the lossless XLL output sample for sample.
 */
final class DtsDecoderDifferentialTest {
    /**
     * Two float implementations of the same synthesis differ by float rounding only.
     */
    private static final Tolerance LOSSY = Tolerance.snr(90);

    /**
     * The LFE channel of a 96 kHz stream: this decoder interpolates it twice with the filter of ETSI TS 102 114
     * Table 6-11, ffmpeg ({@code lfe_x96_float}) linearly, so the two agree to about 54 dB there.
     */
    private static final Tolerance X96_LFE = Tolerance.snr(50);

    /**
     * The index of the LFE channel in ffmpeg's channel order of the 5.1, 6.1 and 7.1 layouts.
     */
    private static final int LFE_INDEX = 3;


    /**
     * Asserts a decode of an X96 stream against ffmpeg's channel by channel: within float rounding but in the LFE
     * channel ({@link #X96_LFE}).
     */
    private static void assertX96LikeFfmpeg(String name, float[] reference, DtsTestSupport.Decoded decoded) {
        var channels = decoded.channels();
        var frames = reference.length / channels;
        for (var ch = 0; ch < channels; ch++) {
            var expected = new float[frames];
            var actual = new float[frames];
            for (var i = 0; i < frames; i++) {
                expected[i] = reference[i * channels + ch];
                actual[i] = decoded.samples()[i * channels + ch];
            }
            (ch == LFE_INDEX ? X96_LFE : LOSSY).assertPcm(name + " channel " + ch, expected, actual);
        }
    }

    private static Path vector(String name) {
        return Conformance.vector("dts-" + name + ".dtshd");
    }

    @Nested
    @DisplayName("FATE dcadec streams")
    final class Fate {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"core_51_24_48_768_0", "core_51_24_48_768_1", "xch_61_24_48_768",
                "xxch_71_24_48_2046", "xbr_51_24_48_3840", "xbr_xch_61_24_48_3840", "xbr_xxch_71_24_48_3840"})
        @DisplayName("lossy streams match ffmpeg within float rounding")
        void lossy(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of());
            assertEquals(reference.length, decoded.samples().length, "sample count");
            DtsTestSupport.report(name, reference, decoded.samples(), decoded.channels());
            LOSSY.assertPcm(name, reference, decoded.samples());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"x96_51_24_96_1509", "x96_xch_61_24_96_3840", "x96_xxch_71_24_96_3840"})
        @DisplayName("96 kHz streams match ffmpeg within float rounding but in the LFE channel")
        void x96(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of());
            assertEquals(96000, decoded.sampleRate());
            assertEquals(reference.length, decoded.samples().length, "sample count");
            DtsTestSupport.report(name, reference, decoded.samples(), decoded.channels());
            assertX96LikeFfmpeg(name, reference, decoded);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"core_51_24_48_768_0", "xch_61_24_48_768", "xxch_71_24_48_2046",
                "x96_51_24_96_1509", "xbr_51_24_48_3840", "xll_xch_61_24_48_768"})
        @DisplayName("core-only decoding matches ffmpeg's core-only decoding")
        void coreOnly(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.coreOnly());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of("-core_only", "1"));
            assertEquals(reference.length, decoded.samples().length, "sample count");
            DtsTestSupport.report(name, reference, decoded.samples(), decoded.channels());
            LOSSY.assertPcm(name, reference, decoded.samples());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"xll_51_24_48_none"})
        @DisplayName("lossless streams without core match ffmpeg sample for sample")
        void losslessWithoutCore(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegIntegers(file);
            assertArrayEquals(reference, DtsTestSupport.toInt32(decoded.samples()), name);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"xll_51_24_48_768, false", "xll_xch_61_24_48_768, true", "xll_71_24_48_768_0, true",
                "xll_71_24_48_768_1, true", "xll_71_24_96_768, true", "xll_x96_51_24_96_1509, false"})
        @DisplayName("lossless streams over a core match ffmpeg sample for sample, every frame")
        void losslessOverCore(String name, boolean firstFrameIsCore) {
            // ffmpeg outputs the fixed-point core without the residuals for the first frame of the streams with several
            // channel sets (DtsFixedCoreDifferentialTest.fateFirstFrame pins it), so that frame is compared where it
            // follows a frame with the extension
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.assertLosslessLikeFfmpeg(name, file, firstFrameIsCore);
            if (firstFrameIsCore) {
                // the streams start with the source's silence, which the same source in one channel set
                // (xll_51_24_48_768) decodes to in ffmpeg from its first frame, where ffmpeg's first frame of these
                // holds the LFE of the core
                var first = decoded.samples().length / DtsTestSupport.units(file).size();
                for (var i = 0; i < first; i++) {
                    assertEquals(0.0f, decoded.samples()[i], "first frame sample " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("lossless streams above 96 kHz")
    final class Above96 {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"xll_51_16_192_768_0", "xll_51_16_192_768_1"})
        @DisplayName("match ffmpeg sample for sample, every frame")
        void lossless(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var decoded = DtsTestSupport.assertLosslessLikeFfmpeg(name, vector(name), false);
            assertEquals(192000, decoded.sampleRate());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"xll_51_16_192_768_0", "xll_51_16_192_768_1"})
        @DisplayName("core-only decoding outputs their core, as ffmpeg's core-only decoding does")
        void core(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.coreOnly());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of("-core_only", "1"));
            assertEquals(48000, decoded.sampleRate());
            assertEquals(reference.length, decoded.samples().length, "sample count");
            DtsTestSupport.report(name, reference, decoded.samples(), decoded.channels());
            LOSSY.assertPcm(name, reference, decoded.samples());
        }
    }

    @Nested
    @DisplayName("ffmpeg-encoded streams")
    final class Encoded {
        @ParameterizedTest(name = "adpcm={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("5.1 streams of ffmpeg's encoder decode as ffmpeg decodes them")
        void surround(boolean adpcm) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var directory = Scratch.directory("dts-encoded");
            var file = directory.resolve("tones-" + adpcm + ".dts");
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=1,aformat=channel_layouts=mono", "-f", "lavfi",
                    "-i", "anoisesrc=color=pink:sample_rate=48000:duration=1:amplitude=0.3", "-filter_complex",
                    "[0][1][0][1][0][1]amerge=inputs=6,aformat=channel_layouts=5.1(side)", "-c:a", "dca",
                    "-strict", "-2", "-dca_adpcm", adpcm ? "1" : "0", "-b:a", "1509k", file.toString()));
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of());
            assertEquals(reference.length, decoded.samples().length, "sample count");
            LOSSY.assertPcm("ffmpeg-encoded", reference, decoded.samples());
        }

        @Test
        @DisplayName("stereo at a low bit rate, where the encoder uses Huffman codes, decodes as ffmpeg decodes it")
        void stereoLowRate() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = Scratch.directory("dts-encoded").resolve("stereo-low.dts");
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                    "anoisesrc=color=brown:sample_rate=44100:duration=1:amplitude=0.5,aformat=channel_layouts=stereo",
                    "-c:a", "dca", "-strict", "-2", "-b:a", "320k", file.toString()));
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of());
            assertEquals(reference.length, decoded.samples().length, "sample count");
            LOSSY.assertPcm("stereo", reference, decoded.samples());
        }
    }

    /**
     * The dcadec test suite's own references (public domain, {@code dts-dcadec-README}): per channel 24-bit WAV files
     * of dcadec's decode of the lossy streams, and the original PCM that the lossless (XLL) streams encode, one WAV
     * file per channel (several channels of a stream share a file when they carry the same signal). The lossless
     * streams must reproduce the original PCM sample for sample, the lossy ones dcadec's fixed-point decode at least as
     * closely as ffmpeg's float decoder does. The references hold the first 2048 samples of
     * dcadec's output, which starts at an offset into the stream's output; the comparison finds that offset.
     */
    private static byte[] readAll(Path file) {
        try {
            return java.nio.file.Files.readAllBytes(file);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("dcadec references")
    final class Dcadec {
        /** Stream, then channel=reference per channel of its output. */
        private static final String TABLE = """
            core_51_24_48_768_0 C=reference-core_51_24_48_768_0_C.wav L=reference-core_51_24_48_768_0_L.wav LFE=reference-core_51_24_48_768_0_LFE.wav Ls=reference-core_51_24_48_768_0_Ls.wav R=reference-core_51_24_48_768_0_R.wav Rs=reference-core_51_24_48_768_0_Rs.wav
            core_51_24_48_768_1 C=reference-core_51_24_48_768_1_C.wav L=reference-core_51_24_48_768_1_L.wav LFE=reference-core_51_24_48_768_0_LFE.wav Ls=reference-core_51_24_48_768_1_Ls.wav R=reference-core_51_24_48_768_1_R.wav Rs=reference-core_51_24_48_768_1_Rs.wav
            x96_51_24_96_1509 C=reference-x96_51_24_96_1509_C.wav L=reference-x96_51_24_96_1509_L.wav LFE=reference-x96_51_24_96_1509_LFE.wav Ls=reference-x96_51_24_96_1509_Ls.wav R=reference-x96_51_24_96_1509_L.wav Rs=reference-x96_51_24_96_1509_Rs.wav
            x96_xch_61_24_96_3840 C=reference-x96_xch_61_24_96_3840_C.wav Cs=reference-x96_xch_61_24_96_3840_Cs.wav L=reference-x96_xch_61_24_96_3840_L.wav LFE=reference-x96_51_24_96_1509_LFE.wav Ls=reference-x96_xch_61_24_96_3840_Ls.wav R=reference-x96_xch_61_24_96_3840_L.wav Rs=reference-x96_xch_61_24_96_3840_Rs.wav
            x96_xxch_71_24_96_3840 C=reference-x96_xxch_71_24_96_3840_C.wav L=reference-x96_xxch_71_24_96_3840_L.wav LFE=reference-x96_xxch_71_24_96_3840_LFE.wav Lsr=reference-x96_xxch_71_24_96_3840_Lsr.wav Lss=reference-x96_xxch_71_24_96_3840_Lss.wav R=reference-x96_xxch_71_24_96_3840_R.wav Rsr=reference-x96_xxch_71_24_96_3840_Rsr.wav Rss=reference-x96_xxch_71_24_96_3840_Rss.wav
            xbr_51_24_48_3840 C=reference-xbr_51_24_48_3840_C.wav L=reference-xbr_51_24_48_3840_L.wav LFE=reference-core_51_24_48_768_0_LFE.wav Ls=reference-xbr_51_24_48_3840_Ls.wav R=reference-xbr_51_24_48_3840_R.wav Rs=reference-xbr_51_24_48_3840_Rs.wav
            xbr_xch_61_24_48_3840 C=reference-xbr_xch_61_24_48_3840_C.wav Cs=reference-xbr_xch_61_24_48_3840_Cs.wav L=reference-xbr_xch_61_24_48_3840_L.wav LFE=reference-core_51_24_48_768_0_LFE.wav Ls=reference-xbr_xch_61_24_48_3840_Ls.wav R=reference-xbr_xch_61_24_48_3840_R.wav Rs=reference-xbr_xch_61_24_48_3840_Rs.wav
            xbr_xxch_71_24_48_3840 C=reference-xbr_xxch_71_24_48_3840_C.wav L=reference-xbr_xxch_71_24_48_3840_L.wav LFE=reference-xbr_xxch_71_24_48_3840_LFE.wav Lsr=reference-xbr_xxch_71_24_48_3840_Lsr.wav Lss=reference-xbr_xxch_71_24_48_3840_Lss.wav R=reference-xbr_xxch_71_24_48_3840_R.wav Rsr=reference-xbr_xxch_71_24_48_3840_Rsr.wav Rss=reference-xbr_xxch_71_24_48_3840_Rss.wav
            xch_61_24_48_768 C=reference-xch_61_24_48_768_C.wav Cs=reference-xch_61_24_48_768_Cs.wav L=reference-xch_61_24_48_768_L.wav LFE=reference-core_51_24_48_768_0_LFE.wav Ls=reference-xch_61_24_48_768_Ls.wav R=reference-xch_61_24_48_768_L.wav Rs=reference-xch_61_24_48_768_Rs.wav
            xll_51_16_192_768_0 C=original-sine_16_192_2.wav L=original-sine_16_192_1.wav LFE=original-sine_16_192_0.wav Ls=original-sine_16_192_3.wav R=original-sine_16_192_1.wav Rs=original-sine_16_192_3.wav
            xll_51_16_192_768_1 C=original-sine_16_192_2.wav L=original-sine_16_192_1.wav LFE=original-sine_16_192_0.wav Ls=original-sine_16_192_3.wav R=original-sine_16_192_1.wav Rs=original-sine_16_192_3.wav
            xll_51_24_48_768 C=original-sine_24_48_2.wav L=original-sine_24_48_1.wav LFE=original-sine_24_48_0.wav Ls=original-sine_24_48_3.wav R=original-sine_24_48_1.wav Rs=original-sine_24_48_3.wav
            xll_51_24_48_none C=original-noise_24_48_2.wav L=original-noise_24_48_0.wav LFE=original-noise_24_48_3.wav Ls=original-noise_24_48_4.wav R=original-noise_24_48_1.wav Rs=original-noise_24_48_5.wav
            xll_71_24_48_768_0 C=original-sine_24_48_2.wav L=original-sine_24_48_1.wav LFE=original-sine_24_48_0.wav Lsr=original-sine_24_48_4.wav Lss=original-sine_24_48_3.wav R=original-sine_24_48_1.wav Rsr=original-sine_24_48_4.wav Rss=original-sine_24_48_3.wav
            xll_71_24_48_768_1 C=original-sine_24_48_2.wav L=original-sine_24_48_1.wav LFE=original-sine_24_48_0.wav Lsr=original-sine_24_48_4.wav Lss=original-sine_24_48_3.wav R=original-sine_24_48_1.wav Rsr=original-sine_24_48_4.wav Rss=original-sine_24_48_3.wav
            xll_71_24_96_768 C=original-sine_24_96_2.wav L=original-sine_24_96_1.wav LFE=original-sine_24_96_0.wav Lsr=original-sine_24_96_4.wav Lss=original-sine_24_96_3.wav R=original-sine_24_96_1.wav Rsr=original-sine_24_96_4.wav Rss=original-sine_24_96_3.wav
            xll_x96_51_24_96_1509 C=original-sine_24_96_2.wav L=original-sine_24_96_1.wav LFE=original-sine_24_96_0.wav Ls=original-sine_24_96_3.wav R=original-sine_24_96_1.wav Rs=original-sine_24_96_3.wav
            xll_xch_61_24_48_768 C=original-sine_24_48_2.wav Cs=original-sine_24_48_4.wav L=original-sine_24_48_1.wav LFE=original-sine_24_48_0.wav Ls=original-sine_24_48_3.wav R=original-sine_24_48_1.wav Rs=original-sine_24_48_3.wav
            xxch_71_24_48_2046 C=reference-xxch_71_24_48_2046_C.wav L=reference-xxch_71_24_48_2046_L.wav LFE=reference-xbr_xxch_71_24_48_3840_LFE.wav Lsr=reference-xxch_71_24_48_2046_Lsr.wav Lss=reference-xxch_71_24_48_2046_Lss.wav R=reference-xxch_71_24_48_2046_L.wav Rsr=reference-xxch_71_24_48_2046_Rsr.wav Rss=reference-xxch_71_24_48_2046_Rss.wav
            """;

        static Stream<String> streams() {
            return TABLE.strip().lines().map(line -> line.strip().split(" ")[0]);
        }

        private static Map<String, String> channels(String stream) {
            var result = new LinkedHashMap<String, String>();
            for (var line : TABLE.strip().lines().toList()) {
                var fields = line.strip().split(" ");
                if (fields[0].equals(stream)) {
                    for (var i = 1; i < fields.length; i++) {
                        var pair = fields[i].split("=");
                        result.put(pair[0], pair[1]);
                    }
                }
            }
            return result;
        }

        /** Returns the dcadec name of the channel a speaker of an output layout carries. */
        private static String channelName(Speaker speaker, Map<String, String> names) {
            var sevenOne = names.containsKey("Lss");
            return switch (speaker) {
                case FRONT_LEFT -> "L";
                case FRONT_RIGHT -> "R";
                case FRONT_CENTER -> "C";
                case LOW_FREQUENCY -> "LFE";
                case SIDE_LEFT -> sevenOne ? "Lss" : "Ls";
                case SIDE_RIGHT -> sevenOne ? "Rss" : "Rs";
                case BACK_LEFT -> sevenOne ? "Lsr" : "Ls";
                case BACK_RIGHT -> sevenOne ? "Rsr" : "Rs";
                case BACK_CENTER -> "Cs";
                default -> throw new AssertionError("unexpected speaker " + speaker);
            };
        }

        /** Reads a mono PCM WAV file as integers and its bit depth. */
        private static int[] wav(String name, int[] depth) {
            var data = readAll(Conformance.vector("dts-dcadec-" + name));
            var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            var position = 12;
            var bits = 0;
            while (position + 8 <= data.length) {
                var id = new String(data, position, 4, StandardCharsets.US_ASCII);
                var size = buffer.getInt(position + 4);
                if (id.equals("fmt ")) {
                    assertEquals(1, buffer.getShort(position + 10), name + " channels");
                    bits = buffer.getShort(position + 22);
                } else if (id.equals("data")) {
                    var bytes = bits / 8;
                    var samples = new int[size / bytes];
                    for (var i = 0; i < samples.length; i++) {
                        var at = position + 8 + i * bytes;
                        var value = 0;
                        for (var b = 0; b < bytes; b++) {
                            value |= (data[at + b] & 0xFF) << (8 * b);
                        }
                        samples[i] = value << (32 - bits) >> (32 - bits);
                    }
                    depth[0] = bits;
                    return samples;
                }
                position += 8 + size + (size & 1);
            }
            throw new AssertionError(name + " has no data chunk");
        }

        /** Returns ffmpeg's float decode of a stream, one array per channel. */
        private static float[][] ffmpegChannels(String stream, int channels) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var interleaved = DtsTestSupport.ffmpegFloats(vector(stream), List.of());
            var frames = interleaved.length / channels;
            var result = new float[channels][frames];
            for (var i = 0; i < frames; i++) {
                for (var c = 0; c < channels; c++) {
                    result[c][i] = interleaved[i * channels + c];
                }
            }
            return result;
        }

        /** Returns the SNR of a channel against a reference at the delay that matches best. */
        private static double bestSnr(int[] expected, float[] channel, double scale) {
            var best = Double.NEGATIVE_INFINITY;
            for (var offset = 0; offset + expected.length <= channel.length; offset++) {
                double signal = 0;
                double noise = 0;
                for (var i = 0; i < expected.length; i++) {
                    var difference = channel[offset + i] * scale - expected[i];
                    signal += (double) expected[i] * expected[i];
                    noise += difference * difference;
                }
                best = Math.max(best, noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise));
            }
            return best;
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("streams")
        @DisplayName("every channel matches its dcadec reference")
        void matches(String stream) {
            var names = channels(stream);
            var lossless = stream.startsWith("xll");
            var buffers = new ArrayList<AudioBuffer>();
            ChannelLayout layout;
            try (var source = MediaSource.of(vector(stream)); var demuxer = new DtsDemuxer(source)) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var decoder = new DtsDecoder(track, DtsDecoderOptions.defaults());
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet, buffer -> buffers.add(buffer.copy()));
                }
                layout = buffers.getFirst().layout();
            }
            var decoded = DtsTestSupport.interleave(buffers);
            assertEquals(names.size(), decoded.channels(), stream + " channels " + layout);
            var frames = decoded.samples().length / decoded.channels();
            for (var ch = 0; ch < decoded.channels(); ch++) {
                var speaker = layout.speakers().get(ch);
                var reference = names.get(channelName(speaker, names));
                assertNotNull(reference, stream + " has no reference for " + speaker);
                var depth = new int[1];
                var expected = wav(reference, depth);
                var scale = (double) (1L << (depth[0] - 1));
                var actual = new double[frames];
                for (var i = 0; i < frames; i++) {
                    actual[i] = decoded.samples()[i * decoded.channels() + ch] * scale;
                }
                var bestOffset = -1;
                var bestSnr = Double.NEGATIVE_INFINITY;
                var bestMismatches = Integer.MAX_VALUE;
                for (var offset = 0; offset + expected.length <= frames; offset++) {
                    double signal = 0;
                    double noise = 0;
                    var mismatches = 0;
                    for (var i = 0; i < expected.length; i++) {
                        var rounded = Math.clamp(Math.round(actual[offset + i]), -(long) scale, (long) scale - 1);
                        mismatches += rounded == expected[i] ? 0 : 1;
                        var difference = actual[offset + i] - expected[i];
                        signal += (double) expected[i] * expected[i];
                        noise += difference * difference;
                    }
                    var snr = noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
                    if (lossless ? mismatches < bestMismatches : snr > bestSnr) {
                        bestOffset = offset;
                        bestSnr = snr;
                        bestMismatches = mismatches;
                    }
                }
                System.out.printf("DCADEC %s %s (%s): offset %d, SNR %.2f dB, %d samples differ%n", stream, speaker,
                        reference, bestOffset, bestSnr, bestMismatches);
                assertTrue(bestOffset >= 0, stream + ": the output is shorter than " + reference);
                if (lossless) {
                    assertEquals(0, bestMismatches, stream + " " + speaker + " against " + reference);
                } else {
                    // dcadec's references of the lossy streams are its fixed-point core, which a float decoder
                    // reaches only to the precision ffmpeg's own float decoder reaches it: the decode must be at
                    // least as close to the reference as ffmpeg's float decode of the channel
                    var ffmpeg = ffmpegChannels(stream, decoded.channels());
                    var oracle = Double.NEGATIVE_INFINITY;
                    for (var channel : ffmpeg) {
                        oracle = Math.max(oracle, bestSnr(expected, channel, scale));
                    }
                    System.out.printf("DCADEC %s %s: ffmpeg float SNR %.2f dB%n", stream, speaker, oracle);
                    // past 110 dB both are float rounding
                    assertTrue(bestSnr >= Math.min(oracle - 1, 110), stream + " " + speaker + " against "
                            + reference + ": SNR " + bestSnr + ", ffmpeg's float decode " + oracle);
                }
            }
        }

        @Test
        @DisplayName("the samples are in the public domain")
        void licence() {
            var text = new String(readAll(Conformance.vector("dts-dcadec-README")),
                    StandardCharsets.US_ASCII);
            assertTrue(text.contains("public domain"), text);
        }
    }


    /**
     * Real-world streams: DTS 96/24 (X96), DTS-ES 6.1 (XCh) with ffmpeg's reference decode of FATE, DTS-HD High
     * Resolution 5.1 (XBR) and a DTS:X 7.1.4 stream, whose object extension the decoder does not read.
     */
    @Nested
    @DisplayName("real-world streams")
    final class RealWorld {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"dts-96-24.dts, 96000", "dts-dts_es.dts, 48000", "dts-hr-5.1-24bit.dts, 48000"})
        @DisplayName("match ffmpeg within float rounding, but in the LFE channel of X96")
        void lossy(String name, int rate) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = Conformance.vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of());
            assertEquals(rate, decoded.sampleRate(), name + " rate");
            assertEquals(reference.length, decoded.samples().length, name + " sample count");
            DtsTestSupport.report(name, reference, decoded.samples(), decoded.channels());
            if (rate == 96000) {
                assertX96LikeFfmpeg(name, reference, decoded);
            } else {
                LOSSY.assertPcm(name, reference, decoded.samples());
            }
        }

        /**
         * FATE's reference of the DTS-ES stream is ffmpeg's decode as 16-bit samples in ffmpeg's channel order: the
         * decode must match it within the 16-bit criterion (an RMS error of a uniform 16-bit rounding, a peak of
         * two steps) over the reference's length.
         */
        @Test
        @DisplayName("DTS-ES 6.1 matches FATE's 16-bit reference")
        void discreteExtendedSurround() {
            var decoded = DtsTestSupport.decodeFile(Conformance.vector("dts-dts_es.dts"), DtsDecoderOptions.defaults());
            assertEquals(7, decoded.channels(), "channels");
            var raw = readAll(Conformance.vector("dts-dts_es_2.pcm"));
            var reference = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            assertTrue(reference.remaining() <= decoded.samples().length, "reference length " + reference.remaining());
            assertEquals(0, reference.remaining() % 7, "whole frames");
            double squared = 0;
            double peak = 0;
            for (var i = 0; i < reference.remaining(); i++) {
                var difference = Math.abs(reference.get(i) / 32768.0 - Math.clamp(decoded.samples()[i], -1f, 1f));
                squared += difference * difference;
                peak = Math.max(peak, difference);
            }
            var rms = Math.sqrt(squared / reference.remaining());
            assertTrue(rms <= Math.pow(2, -15) / Math.sqrt(12) * 2, "RMS " + rms);
            assertTrue(peak <= Math.pow(2, -14), "peak " + peak);
        }

        @Test
        @DisplayName("DTS:X 7.1.4 decodes its channel bed or is refused cleanly")
        void dtsX() {
            var file = Conformance.vector("dts-dtsx_7.1.4_24bit.dts");
            try {
                var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
                assertTrue(decoded.channels() >= 6, "channels " + decoded.channels());
                for (var sample : decoded.samples()) {
                    assertTrue(Float.isFinite(sample) && Math.abs(sample) <= 4, "sample " + sample);
                }
            } catch (com.github.auties00.smalt.MediaException.Unsupported expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}
