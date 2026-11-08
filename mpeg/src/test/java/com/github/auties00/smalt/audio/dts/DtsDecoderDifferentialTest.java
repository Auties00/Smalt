package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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


    private static Path vector(String name) {
        return Conformance.fetchListed("dts-" + name + ".dtshd");
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

        // X96 fills the subbands without bits with noise from a generator the specification leaves unstated, so the
        // outputs agree only to the level of that noise; over XXCH the rear downmix is also undone at 96 kHz in the
        // subband domain, which ffmpeg's decoder undoes on the PCM
        @ParameterizedTest(name = "{0}")
        @CsvSource({"x96_51_24_96_1509, 50", "x96_xch_61_24_96_3840, 50", "x96_xxch_71_24_96_3840, 40"})
        @DisplayName("96 kHz streams match ffmpeg to the level of the noise substitution")
        void x96(String name, double snr) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of());
            assertEquals(96000, decoded.sampleRate());
            assertEquals(reference.length, decoded.samples().length, "sample count");
            DtsTestSupport.report(name, reference, decoded.samples(), decoded.channels());
            Tolerance.snr(snr).assertPcm(name, reference, decoded.samples());
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
        @ValueSource(strings = {"xll_51_24_48_768", "xll_71_24_48_768_0", "xll_71_24_48_768_1",
                "xll_71_24_96_768"})
        @DisplayName("lossless streams with core output their core, as ffmpeg's core-only decoding does")
        void losslessWithCore(String name) {
            // the XLL extension of a stream with a core is not decoded yet (TODO in ExtensionSubstream)
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var reference = DtsTestSupport.ffmpegFloats(file, List.of("-core_only", "1"));
            assertEquals(48000, decoded.sampleRate());
            assertEquals(reference.length, decoded.samples().length, "sample count");
            DtsTestSupport.report(name, reference, decoded.samples(), decoded.channels());
            LOSSY.assertPcm(name, reference, decoded.samples());
        }

        @Test
        @DisplayName("a lossless stream with an X96 core outputs the 96 kHz core")
        void losslessWithX96Core() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector("xll_x96_51_24_96_1509");
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
            var lossless = DtsTestSupport.ffmpegIntegers(file);
            assertEquals(96000, decoded.sampleRate());
            assertEquals(lossless.length, decoded.samples().length, "sample count");
        }
    }

    @Nested
    @DisplayName("lossless streams above 96 kHz")
    final class Above96 {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"xll_51_16_192_768_0", "xll_51_16_192_768_1"})
        @DisplayName("output their core, as ffmpeg's core-only decoding does")
        void core(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = vector(name);
            var decoded = DtsTestSupport.decodeFile(file, DtsDecoderOptions.defaults());
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
}
