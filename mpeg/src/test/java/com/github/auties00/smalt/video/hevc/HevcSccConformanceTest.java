package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.hevc.internal.HevcPps;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes every bitstream of the JCT-VC screen content coding conformance suite (ITU-T H.265.1, SCC): palette mode
 * with predictor initializers in the SPS and the PPS and with zero and one entry palettes, intra block copy through
 * current picture referencing (with the 8x8 bi-prediction restriction and the loop filters of a picture referencing
 * itself), the adaptive color transform with its PPS and slice QP offsets, adaptive motion vector resolution, disabled
 * intra boundary filtering, and their combination with wavefronts, tiles, extended precision and 4:2:0 and 4:4:4
 * pictures of 8 and 10 bits. The output must match the reference decoder's: the MD5 of every output picture in output
 * order (8-bit samples for 8-bit streams, 16-bit little-endian samples otherwise) and the frame count, with every
 * decoded picture hash SEI message verified on the way. The expected MD5s are those of the output of HM-16.21+SCM-8.8
 * (with {@code RExt__HIGH_BIT_DEPTH_SUPPORT} for the extended precision stream), equal to the MD5s the archives ship,
 * except for {@code Slice_ACT_QP_Offsets_A_Qualcomm_2}, whose archive hashes the RGB-ordered planes and whose expected
 * MD5 here is that of the decoded planes in coded order. Each archive is pinned by its SHA-256 and cached by
 * {@link Conformance#fetch}.
 *
 * <p>The suite's archives are fetched, never committed, so short streams the HM-SCM reference encoder coded from
 * ffmpeg's {@code testsrc} pattern are kept with the test ({@code scc/scc.tsv}, written by
 * {@code tools/testdata/hevc-scc/make-vectors.py}, which checks that HM verified every picture hash): all intra and low
 * delay 4:4:4 at 8 bits with palette mode, predictor initializers in the SPS and the PPS, intra block copy, the
 * adaptive color transform, adaptive motion vector resolution, wavefronts and tiles; random access 4:2:0 at 10
 * bits; low delay monochrome at 8 bits and 4:2:2 at 10 bits with escape samples; lossless 4:4:4 (fixed-length escape
 * samples, the lossless color transform), lossless and lossy coding units mixed, and 8-bit luma with 10-bit chroma
 * (the bit depth alignment of the color transform). Each must decode to HM's output, picture by picture, on one and
 * several threads. The parameter set tests check the extension fields parsed from these streams and the rejection
 * of extensions that contradict their sequence.
 */
@DisplayName("HEVC conformance bitstreams (ITU-T H.265.1, screen content coding)")
class HevcSccConformanceTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jctvc-site/bitstream_exchange/draft_conformance/SCC/";

    /**
     * Name | SHA-256 of the archive | bitstream entry | output frames | MD5 of the output.
     */
    private static final String MANIFEST = """
            Bipred_8by8_A_Qualcomm_2|ddcede6a4f68c6758afe01627805f5e96231ad9379cfea6cfc2b8ab2a57c6360|Bipred_8by8_A_Qualcomm_2.bit|3|77ca7bf37442cdc5a468797f8597aa07
            DPB_Loop_Filters_A_MediaTek_2|4aab798e6e4458139f6407f0957b40e35403686f341802b370da0651459f36f3|DPB_Loop_Filters_A_MediaTek_2.bit|10|8dba33e8843e05bca8eb8c0fb4131a6b
            Delta_QP_Chroma_QP_Offsets_A_Qualcomm_2|d83387f525e3c22410e21d6e95b0a623d3e27b36f3fa2974c5a919215cee109f|Delta_QP_Chroma_QP_Offsets_A_Qualcomm_2.bit|2|b5535b153c9ea0a01b22236d9a753e0c
            HT_A_SCC_Apple_2|5190f537362160bc1bcc20766d981c4652d55fdafb23a387b10afbfa6af85c36|HT_A_SCC_Apple_2.bit|3|ec93dfc5ca3fc54bdc948b6e33f7863d
            HT_B_SCC_Apple_3|064d8cec3c49812b576984ba9ff7dbed8c29abc6ba252401c7e36c87558ca244|HT_B_SCC_Apple_3.bit|3|eb55b86d87b76690a41f9f7b28fe3bb7
            HT_C_SCC_Apple_3|4f50c637ec8afe24078fb1ac1082cb43c75605756ba26cf114b8c6f59455c471|HT_C_SCC_Apple_3.bit|3|270a7fe953a82fd3ac71d364a85279d0
            IBF_Disabled_A_MediaTek_2|0493e8fdce70b2b53d3e8518b0dd79a144cb5895358db1dfcf7509ccad24759b|IBF_Disabled_A_MediaTek_2.bit|33|cf2d0bf228b256b8f70fe4b4d2e7f90d
            IBF_Disabled_B_MediaTek_2|1ad506252895fa9664868b9d77f0b75083b88fbe198e7d0d9cf6d410b1df4c8e|IBF_Disabled_B_MediaTek_2.bit|33|44bcdae6a7357c23f10dea63769e7598
            MVRESIDC_A_MS_2|5abab0a04c1efaca94f66695a031291e2b1a5068a84421f32f88dca28c4a5419|MVRESIDC_A_MS_2.bit|9|ea1cd8869c86bf56e89e20516c1abb26
            MVRESIDC_B_MS_2|1ec54e45db7512e5cb9f5ca0f06b00218fd1a9b90e5472e7ef781cf611331211|MVRESIDC_B_MS_2.bit|9|478cb2d63bc753db71abeca08975094e
            MVRESIDC_C_MS_3|e78affc826fc7386f81791974d024154c9de205838b29acd205feb0b62df49ad|MVRESIDC_C_MS_3.bit|9|2098828aaab226883c4226b6efac85e7
            PPI_A_InterDigital_2|8592f1b9073874c9b386b6d5472dfdb1b8b1c492b02bf3ac05fcdd7701699f05|PPI_A_InterDigital_2.bit|33|220400063b5369438ba85b90e7ca0ae3
            PPI_B_InterDigital_2|4126a3101d627aaeaf70a47fbebd942a805c27001b10f4b0553484e759baaac1|PPI_B_InterDigital_2.bit|33|1b11363ec651d23985dae30033ffb20f
            Slice_ACT_QP_Offsets_A_Qualcomm_2|f10a758f73b930a49e12cc94e9ce23b376a73713ad4300972ef6152f092d20fd|Slice_ACT_QP_Offsets_A_Qualcomm_2.bit|2|4c5a48f2bbdcf0ad625701a997134eeb
            Zero_and_One_Palette_Size_A_Canon_2|a707590d1dd9770a6c5544a421de44c384bfa22d68e5513911a2f8b7c13aca80|Zero_and_One_Palette_Size_A_Canon_2.bit|1|a7794b555c9ba89f7f01bb0ce5b66676
            """;

    record Vector(String name, String sha256, String bitstream, int frames, String md5) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            result.add(new Vector(parts[0], parts[1], parts[2], Integer.parseInt(parts[3]), parts[4]));
        }
        return result;
    }

    static Stream<Vector> all() {
        return vectors().stream();
    }

    static Stream<Vector> threaded() {
        return vectors().stream().filter(vector -> vector.name().matches("HT_.*|PPI_A_InterDigital_2|MVRESIDC_C_MS_3"
                + "|DPB_Loop_Filters_A_MediaTek_2|IBF_Disabled_A_MediaTek_2"));
    }

    static byte[] bitstream(Vector vector) throws IOException {
        var archive = Conformance.fetch("hevc-scc-" + vector.name() + ".zip", BASE + vector.name() + ".zip",
                vector.sha256());
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(vector.bitstream())).readAllBytes();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes every picture bit-exactly")
    void bitExact(Vector vector) throws IOException {
        check(vector, 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threaded")
    @DisplayName("decodes bit-exactly on several threads")
    void bitExactThreaded(Vector vector) throws IOException {
        check(vector, 4);
    }

    record Coded(String name, int frames, String streamSha256, String decodedSha256, List<String> frameMd5s) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> coded() throws IOException {
        try (var input = HevcSccConformanceTest.class.getResourceAsStream("scc/scc.tsv")) {
            var result = new ArrayList<Arguments>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                var vector = new Coded(columns[0], Integer.parseInt(columns[1]), columns[3], columns[4],
                        List.of(columns[5].split(",")));
                result.add(Arguments.of(vector, 1));
                result.add(Arguments.of(vector, 4));
            }
            return result.stream();
        }
    }

    @ParameterizedTest(name = "{0} on {1} threads")
    @MethodSource("coded")
    @DisplayName("decodes the HM-SCM streams kept with the test like the HM reference decoder")
    void hmCoded(Coded vector, int threads) throws IOException {
        byte[] stream;
        try (var input = HevcSccConformanceTest.class.getResourceAsStream("scc/" + vector.name() + ".hevc")) {
            stream = input.readAllBytes();
        }
        assertEquals(vector.streamSha256(), digest("SHA-256", stream), vector + " stream");
        var options = HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).threads(threads).build();
        var frames = new ArrayList<byte[]>();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> frames.add(HevcTestStreams.raw(frame.picture()));
            for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(vector.frames(), frames.size(), vector + " frames");
        var all = new ByteArrayOutputStream();
        for (var i = 0; i < frames.size(); i++) {
            assertEquals(vector.frameMd5s().get(i), digest("MD5", frames.get(i)), vector + " frame " + i);
            all.writeBytes(frames.get(i));
        }
        assertEquals(vector.decodedSha256(), digest("SHA-256", all.toByteArray()), vector + " decoded");
    }

    @Nested
    @DisplayName("screen content parameter set extensions")
    class ParameterSets {
        private HevcSps sps;
        private HevcPps pps;

        private void parse() throws IOException {
            parse("scc-intra-444-8");
        }

        private void parse(String name) throws IOException {
            byte[] stream;
            try (var input = HevcSccConformanceTest.class.getResourceAsStream("scc/" + name + ".hevc")) {
                stream = input.readAllBytes();
            }
            for (var nal : HevcTestStreams.nalUnits(stream)) {
                var type = HevcTestStreams.type(nal);
                if (type != 33 && type != 34) {
                    continue;
                }
                var rbsp = new byte[nal.length + H264BitReader.PADDING];
                var size = NalUnits.unescape(nal, 2, nal.length - 2, rbsp);
                var reader = new H264BitReader();
                reader.init(Arrays.copyOf(rbsp, size + H264BitReader.PADDING), 0, size);
                if (type == 33) {
                    sps = HevcSps.parse(reader);
                } else {
                    pps = HevcPps.parse(reader);
                }
            }
        }

        @Test
        @DisplayName("parses the palette, intra block copy and color transform fields HM-SCM wrote")
        void fields() throws IOException {
            parse();
            // encoder_intra_main_scc.cfg: PaletteMaxSize 63, PaletteMaxPredSize 128, IntraBoundaryFilterDisabled 1
            assertTrue(sps.paletteModeEnabled);
            assertEquals(63, sps.paletteMaxSize);
            assertEquals(128, sps.paletteMaxPredictorSize);
            assertNotNull(sps.paletteInitializers);
            assertEquals(3, sps.paletteInitializers.length);
            assertTrue(sps.currPicRefEnabled);
            assertTrue(sps.intraBoundaryFilteringDisabled);
            assertTrue(pps.currPicRefEnabled);
            assertTrue(pps.actEnabled);
            assertTrue(pps.paletteInitializersPresent);
            assertTrue(pps.numPaletteInitializers > 0);
            assertEquals(8, pps.paletteLumaBitDepth);
            assertEquals(8, pps.paletteChromaBitDepth);
            pps.activate(sps);
        }

        @Test
        @DisplayName("parses the monochrome palette predictor initializers")
        void monochrome() throws IOException {
            parse("scc-ld-400-8");
            assertEquals(2, sps.paletteMaxSize);
            assertEquals(4, sps.paletteMaxPredictorSize);
            assertEquals(1, sps.paletteInitializers.length);
            assertEquals(4, sps.paletteInitializers[0].length);
            assertEquals(2, sps.mvResolutionControl);
            assertEquals(4, pps.numPaletteInitializers);
            assertEquals(1, pps.paletteInitializers.length);
            assertEquals(8, pps.paletteLumaBitDepth);
            pps.activate(sps);
        }

        @Test
        @DisplayName("rejects palette predictor initializers whose bit depths differ from the sequence")
        void initializerDepth() throws IOException {
            parse();
            pps.paletteChromaBitDepth = 10;
            assertThrows(MediaException.Malformed.class, () -> pps.activate(sps));
            parse();
            pps.paletteLumaBitDepth = 16;
            assertThrows(MediaException.Malformed.class, () -> pps.activate(sps));
        }

        @Test
        @DisplayName("rejects pictures referencing themselves under a sequence that disables it")
        void currentPictureReference() throws IOException {
            parse();
            sps.currPicRefEnabled = false;
            assertThrows(MediaException.Malformed.class, () -> pps.activate(sps));
        }
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void check(Vector vector, int threads) throws IOException {
        var options = HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).threads(threads).build();
        var result = HevcTestStreams.decode(bitstream(vector), options);
        assertEquals(vector.frames(), result.frames(), "frame count of " + vector.name());
        assertEquals(vector.md5(), result.md5(), "output MD5 of " + vector.name());
    }
}
