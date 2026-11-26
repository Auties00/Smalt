package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes every bitstream of the JCT-VC range extensions conformance suite (ITU-T H.265.1, RExt):
 * the 4:0:0, 4:2:2 and 4:4:4 chroma formats at 8 to 16 bits, and the range extension coding tools
 * (cross-component prediction, implicit and explicit residual DPCM, transform skip rotation and
 * contexts, large transform skip blocks, extended precision processing, persistent Rice adaptation,
 * CABAC bypass alignment, chroma QP offset lists, SAO offset scaling and disabled intra smoothing).
 * The output must match the reference decoder's: the MD5 of every output picture in output order (8-bit
 * samples for 8-bit streams, 16-bit little-endian samples otherwise, luma only for 4:0:0) and the frame
 * count, with every decoded picture hash SEI message verified on the way. The expected MD5s are those
 * of the output of the HM reference decoder built with {@code RExt__HIGH_BIT_DEPTH_SUPPORT}. Each
 * archive is read from the hosted test data by {@link Conformance#vector}.
 */
@DisplayName("HEVC conformance bitstreams (ITU-T H.265.1, range extensions)")
class HevcRextConformanceTest {
    /**
     * Name | archive | bitstream entry | output frames | MD5 of the output.
     */
    private static final String MANIFEST = """
            ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2|ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2.zip|ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2/ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2.bit|3|b045776950c2e06d35348bd03268c07f
            Bitdepth_A_RExt_Sony_1|Bitdepth_A_RExt_Sony_1.zip|Bitdepth_A_RExt_Sony_1/Bitdepth_A_RExt_Sony_1.bin|16|2b65fa2f384085926deb4f9d20fcd798
            Bitdepth_B_RExt_Sony_1|Bitdepth_B_RExt_Sony_1.zip|Bitdepth_B_RExt_Sony_1/Bitdepth_B_RExt_Sony_1.bin|16|cf489cf45491fab54cdba936885836cb
            CCP_10bit_RExt_QCOM_1|CCP_10bit_RExt_QCOM_1.zip|CCP_10bit_RExt_QCOM/CCP_10bit_RExt_QCOM.bin|9|363f55fc6097a3bb7525392b87a6498a
            CCP_12bit_RExt_QCOM_1|CCP_12bit_RExt_QCOM_1.zip|CCP_12bit_RExt_QCOM/CCP_12bit_RExt_QCOM.bin|9|f3e914fccdb820eac85f46642ea0e168
            CCP_8bit_RExt_QCOM_1|CCP_8bit_RExt_QCOM_1.zip|CCP_8bit_RExt_QCOM/CCP_8bit_RExt_QCOM.bin|9|54231c6f121ca65d8b94a747b9504b8b
            ExplicitRdpcm_A_BBC_1|ExplicitRdpcm_A_BBC_1.zip|ExplicitRdpcm_A_BBC_1/ExplicitRdpcm_A_BBC_1.bit|600|1156aca54db44114c2737e9679942656
            ExplicitRdpcm_B_BBC_2|ExplicitRdpcm_B_BBC_2.zip|ExplicitRdpcm_B_BBC_2/ExplicitRdpcm_B_BBC_2/ExplicitRdpcm_B_BBC_2.bit|150|866d75c3530d7b659b3632655c7eb974
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1.zip|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1.bit|2|ae359f92c74a42c6733e9adfa7ddbbe5
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1.zip|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1.bit|2|bcccdfb428885adb0a026d55df207e23
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1.zip|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1.bit|2|23d546b71e9bc7c8a636099ef9a3dfcd
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1.zip|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1.bit|2|cd84e70999e4577f23eb2077c7a1d24f
            EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1.zip|EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1.bit|2|6c45bc0e653c5687acbc4bb483b8a512
            EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1.zip|EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1.bit|2|b10f17ae9968230f2904216c9551eb2b
            EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1.zip|EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1.bit|2|68d8956971e5b327e90391b18c0dc0eb
            EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1.zip|EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1.bit|2|7ef62c35074ed780790cb32d1b5da436
            GENERAL_10b_420_RExt_Sony_1|GENERAL_10b_420_RExt_Sony_1.zip|GENERAL_10b_420_RExt_Sony_1/GENERAL_10b_420_RExt_Sony_1.bit|1|d19c7e1764971983f0e827277db2f69a
            GENERAL_10b_422_RExt_Sony_1|GENERAL_10b_422_RExt_Sony_1.zip|GENERAL_10b_422_RExt_Sony_1/GENERAL_10b_422_RExt_Sony_1.bit|2|ea5dfb01c95bffb169cd78e549bd39ab
            GENERAL_10b_444_RExt_Sony_2|GENERAL_10b_444_RExt_Sony_2.zip|GENERAL_10b_444_RExt_Sony_2/GENERAL_10b_444_RExt_Sony_2.bit|12|079dd98d0cb2ab8fe26475a2e946fe53
            GENERAL_12b_400_RExt_Sony_1|GENERAL_12b_400_RExt_Sony_1.zip|GENERAL_12b_400_RExt_Sony_1/GENERAL_12b_400_RExt_Sony_1.bit|1|549ff2b94ede8d83bfdc64a34440817d
            GENERAL_12b_420_RExt_Sony_1|GENERAL_12b_420_RExt_Sony_1.zip|GENERAL_12b_420_RExt_Sony_1/GENERAL_12b_420_RExt_Sony_1.bit|1|27a2b09b136687f7a391bfdbc50ac964
            GENERAL_12b_422_RExt_Sony_1|GENERAL_12b_422_RExt_Sony_1.zip|GENERAL_12b_422_RExt_Sony_1/GENERAL_12b_422_RExt_Sony_1.bit|2|d4f51ab5405659ad90e788083bcfc18d
            GENERAL_12b_444_RExt_Sony_2|GENERAL_12b_444_RExt_Sony_2.zip|GENERAL_12b_444_RExt_Sony_2/GENERAL_12b_444_RExt_Sony_2.bit|14|0b81d1008ff671da0d930bd38ab3489c
            GENERAL_16b_400_RExt_Sony_1|GENERAL_16b_400_RExt_Sony_1.zip|GENERAL_16b_400_RExt_Sony_1/GENERAL_16b_400_RExt_Sony_1.bit|7|eb432746ce6c66943613397da28526a7
            GENERAL_16b_444_highThroughput_RExt_Sony_2|GENERAL_16b_444_highThroughput_RExt_Sony_2.zip|GENERAL_16b_444_highThroughput_RExt_Sony_2/GENERAL_16b_444_highThroughput_RExt_Sony_2.bit|16|7422848e6a99897a8ade34814d396657
            GENERAL_16b_444_RExt_Sony_2|GENERAL_16b_444_RExt_Sony_2.zip|GENERAL_16b_444_RExt_Sony_2/GENERAL_16b_444_RExt_Sony_2.bit|16|41eb40070aae0bbedd584e1f73512840
            GENERAL_8b_400_RExt_Sony_1|GENERAL_8b_400_RExt_Sony_1.zip|GENERAL_8b_400_RExt_Sony_1/GENERAL_8b_400_RExt_Sony_1.bit|1|e5223be3da805fb96440dbf2bd170db0
            GENERAL_8b_420_RExt_Sony_1|GENERAL_8b_420_RExt_Sony_1.zip|GENERAL_8b_420_RExt_Sony_1/GENERAL_8b_420_RExt_Sony_1.bit|1|99eb7a5106988759fd0eaecdb66e2cd2
            GENERAL_8b_444_RExt_Sony_2|GENERAL_8b_444_RExt_Sony_2.zip|GENERAL_8b_444_RExt_Sony_2/GENERAL_8b_444_RExt_Sony_2.bit|14|3ed16fed34c4ed3ad8fe31962fb77aac
            IPCM_A_RExt_NEC_2|IPCM_A_RExt_NEC_2.zip|IPCM_A_RExt_NEC_2/IPCM_A_RExt_NEC_2.bit|1|8d4eb18812338f9f475035b8da27e74a
            IPCM_B_RExt_NEC|IPCM_B_RExt_NEC.zip|IPCM_B_RExt_NEC/IPCM_B_RExt_NEC.bit|1|eea32e6e1f88b7782515e53f8122be7d
            Main_422_10_A_RExt_Sony_2|Main_422_10_A_RExt_Sony_2.zip|Main_422_10_A_RExt_Sony_2/Main_422_10_A_RExt_Sony_2.bin|9|66530b83be6647f87efbb2b9ae6e1e28
            Main_422_10_B_RExt_Sony_2|Main_422_10_B_RExt_Sony_2.zip|Main_422_10_B_RExt_Sony_2/Main_422_10_B_RExt_Sony_2.bin|1|fd163bcbdc24792a23781ee7aace74eb
            PERSIST_RPARAM_A_RExt_Sony_3|PERSIST_RPARAM_A_RExt_Sony_3.zip|PERSIST_RPARAM_A_RExt_Sony_3/PERSIST_RPARAM_A_RExt_Sony_3.bit|2|d19f5cd87d5b971c4b4abbca22958031
            QMATRIX_A_RExt_Sony_1|QMATRIX_A_RExt_Sony_1.zip|QMATRIX_A_RExt_Sony_1/QMATRIX_A_RExt_Sony_1.bit|20|e0fe38f19cf0ac875ce6674da989ea38
            SAO_A_RExt_MediaTek_1|SAO_A_RExt_MediaTek_1.zip|SAO_A_RExt_MediaTek_1/SAO_A_RExt_MediaTek_1.bit|8|126cd42a185b327d86640a9269044bc8
            TSCTX_10bit_I_RExt_SHARP_1|TSCTX_10bit_I_RExt_SHARP_1.zip|TSCTX_10bit_I_RExt_SHARP_1/TSCTX_10bit_I_RExt_SHARP_1.bin|9|2230c35a91ddde5455a36c49fb868e86
            TSCTX_10bit_RExt_SHARP_1|TSCTX_10bit_RExt_SHARP_1.zip|TSCTX_10bit_RExt_SHARP_1/TSCTX_10bit_RExt_SHARP_1.bin|9|a3015d8f4bd0a90645514bcf2e1fa8a7
            TSCTX_12bit_I_RExt_SHARP_1|TSCTX_12bit_I_RExt_SHARP_1.zip|TSCTX_12bit_I_RExt_SHARP_1/TSCTX_12bit_I_RExt_SHARP_1.bin|9|acebd42c1edea6812f0c07a4e91d943f
            TSCTX_12bit_RExt_SHARP_1|TSCTX_12bit_RExt_SHARP_1.zip|TSCTX_12bit_RExt_SHARP_1/TSCTX_12bit_RExt_SHARP_1.bin|9|f311f3e96d9fbda375bfce341b99cb99
            TSCTX_8bit_I_RExt_SHARP_1|TSCTX_8bit_I_RExt_SHARP_1.zip|TSCTX_8bit_I_RExt_SHARP_1/TSCTX_8bit_I_RExt_SHARP_1.bin|9|5930ca9acf0bc741fb4dd827214f8db7
            TSCTX_8bit_RExt_SHARP_1|TSCTX_8bit_RExt_SHARP_1.zip|TSCTX_8bit_RExt_SHARP_1/TSCTX_8bit_RExt_SHARP_1.bin|9|00fa6cd027d3a37cc0d10e5575d955d2
            WAVETILES_RExt_Sony_2|WAVETILES_RExt_Sony_2.zip|WAVETILES_RExt_Sony_2/WAVETILES_RExt_Sony_2.bit|4|d4809354218e6747c70aed951b80acc9
            WPP_AND_TILE_10Bit422Test_HIGH_TP_444_10BIT_RExt_Apple_2|WPP_AND_TILE_10Bit422Test_HIGH_TP_444_10BIT_RExt_Apple_2.zip|WPP_AND_TILE_10Bit422Test_HIGH_TP_444_10BIT_RExt_Apple_2.bit|3|32e6b91c9497e161fe66c1fbd3cec51e
            WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_0_HIGH_TP_444_14BIT_RExt_Apple_2|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_0_HIGH_TP_444_14BIT_RExt_Apple_2.zip|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_0_HIGH_TP_444_14BIT_RExt_Apple_2.bit|3|e134676d233f3f5b49ab4c252d5d18be
            WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2.zip|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2.bit|3|5347f09153fd54b24a37427ca0bd9b30
            WPP_AND_TILE_AND_CABAC_EXT_PREC_1_HIGH_TP_444_14BIT_RExt_Apple_2|WPP_AND_TILE_AND_CABAC_EXT_PREC_1_HIGH_TP_444_14BIT_RExt_Apple_2.zip|WPP_AND_TILE_AND_CABAC_EXT_PREC_1_HIGH_TP_444_14BIT_RExt_Apple_2.bit|3|95f0019aed6c9c845fe5e2088aefdc39
            WPP_AND_TILE_HIGH_TP_444_8BIT_RExt_Apple_2|WPP_AND_TILE_HIGH_TP_444_8BIT_RExt_Apple_2.zip|WPP_AND_TILE_HIGH_TP_444_8BIT_RExt_Apple_2.bit|3|e134676d233f3f5b49ab4c252d5d18be
            WPP_HIGH_TP_444_8BIT_RExt_Apple_2|WPP_HIGH_TP_444_8BIT_RExt_Apple_2.zip|WPP_HIGH_TP_444_8BIT_RExt_Apple_2.bit|3|3c94b5ebc0aed0abae4e619b9dcca9cc
            """;

    record Vector(String name, String archive, String bitstream, int frames, String md5) {
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
        return vectors().stream().filter(vector -> vector.name().matches(
                "WAVETILES_RExt_Sony_2|WPP_.*|Main_422_10_A_RExt_Sony_2|GENERAL_16b_444_RExt_Sony_2|CCP_12bit_RExt_QCOM_1"
                        + "|PERSIST_RPARAM_A_RExt_Sony_3|ExplicitRdpcm_A_BBC_1"));
    }

    static byte[] bitstream(Vector vector) throws IOException {
        var archive = Conformance.vector("hevc-rext-" + vector.archive());
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

    private static void check(Vector vector, int threads) throws IOException {
        var options = HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).threads(threads).build();
        // 4:4:4 of 12-bit luma and 8-bit chroma (A) and the reverse (B): the decoder scales the
        // 8-bit component up to 12 bits.
        var lumaShift = vector.name().equals("Bitdepth_B_RExt_Sony_1") ? 4 : 0;
        var chromaShift = vector.name().equals("Bitdepth_A_RExt_Sony_1") ? 4 : 0;
        var result = HevcTestStreams.decode(bitstream(vector), options, lumaShift, chromaShift);
        assertEquals(vector.frames(), result.frames(), "frame count of " + vector.name());
        assertEquals(vector.md5(), result.md5(), "output MD5 of " + vector.name());
    }
}
