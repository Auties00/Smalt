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
 * archive is pinned by its SHA-256 and cached by {@link Conformance#fetch}.
 */
@DisplayName("HEVC conformance bitstreams (ITU-T H.265.1, range extensions)")
class HevcRextConformanceTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jctvc-site/bitstream_exchange/draft_conformance/RExt/";

    /**
     * Name | archive | SHA-256 | bitstream entry | output frames | MD5 of the output.
     */
    private static final String MANIFEST = """
            ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2|ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2.zip|8c915b0554265ccd6fb1347b41788006df9e7c66349fdeaa142d953340b12997|ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2/ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2.bit|3|b045776950c2e06d35348bd03268c07f
            Bitdepth_A_RExt_Sony_1|Bitdepth_A_RExt_Sony_1.zip|34967e2047d59b0f3e96e7501712a056c01f369501d812b170c717ce1d417368|Bitdepth_A_RExt_Sony_1/Bitdepth_A_RExt_Sony_1.bin|16|2b65fa2f384085926deb4f9d20fcd798
            Bitdepth_B_RExt_Sony_1|Bitdepth_B_RExt_Sony_1.zip|6f5f16377e9287ced1ad06a9493249ed1d61e393d779b06e777a92e14b6c6eb6|Bitdepth_B_RExt_Sony_1/Bitdepth_B_RExt_Sony_1.bin|16|cf489cf45491fab54cdba936885836cb
            CCP_10bit_RExt_QCOM_1|CCP_10bit_RExt_QCOM_1.zip|190ab9b4a9aadee32ecfb639223156a3dee721dbba064c28574e176ce07ff032|CCP_10bit_RExt_QCOM/CCP_10bit_RExt_QCOM.bin|9|363f55fc6097a3bb7525392b87a6498a
            CCP_12bit_RExt_QCOM_1|CCP_12bit_RExt_QCOM_1.zip|e54417a143d59ba112d90355be88e535761a22ec44c88bda5c4913ed0b3ce7f3|CCP_12bit_RExt_QCOM/CCP_12bit_RExt_QCOM.bin|9|f3e914fccdb820eac85f46642ea0e168
            CCP_8bit_RExt_QCOM_1|CCP_8bit_RExt_QCOM_1.zip|e5a0216c23f3816107441366b7743160bd0e6a39ce15b14a9fe1a3ffea38bf88|CCP_8bit_RExt_QCOM/CCP_8bit_RExt_QCOM.bin|9|54231c6f121ca65d8b94a747b9504b8b
            ExplicitRdpcm_A_BBC_1|ExplicitRdpcm_A_BBC_1.zip|ce3bdae01cca1f76dfc661997179d7122c71f0c4f9cc9733f64ba4d56e8a140c|ExplicitRdpcm_A_BBC_1/ExplicitRdpcm_A_BBC_1.bit|600|1156aca54db44114c2737e9679942656
            ExplicitRdpcm_B_BBC_2|ExplicitRdpcm_B_BBC_2.zip|b80fa804908c96bf8afebd0235c02484dd77207cfd8fcd55cbf48ba388b1adc8|ExplicitRdpcm_B_BBC_2/ExplicitRdpcm_B_BBC_2/ExplicitRdpcm_B_BBC_2.bit|150|866d75c3530d7b659b3632655c7eb974
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1.zip|445d8ef653c1c37495b62c6afbe11006218131540289cde5b35dfafba4acf975|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1.bit|2|ae359f92c74a42c6733e9adfa7ddbbe5
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1.zip|78df34ac5602d59a67730e231ca86a6f1e41d4b9e6967c6fbb31afd276b2d4f8|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1.bit|2|bcccdfb428885adb0a026d55df207e23
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1.zip|4851c5fbf219cb1df90226d2d327bfab8a6380db486add26cea1bdb84a9274d4|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1.bit|2|23d546b71e9bc7c8a636099ef9a3dfcd
            EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1.zip|b7d3993dc6aa87e86d09c245665fb13f6f970e14dfe75ab25f7ee689c3bb78a0|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1/EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1.bit|2|cd84e70999e4577f23eb2077c7a1d24f
            EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1.zip|1120a6b1cc6d1e8af5cf2d9980a5d4ac2c8c83217e570900d5c45bc6cd92403a|EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1.bit|2|6c45bc0e653c5687acbc4bb483b8a512
            EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1.zip|66c8e24866210b94a63a29c93f8c1490046cc17b26484c5bd81f805d7bfd18d1|EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1.bit|2|b10f17ae9968230f2904216c9551eb2b
            EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1.zip|385cd2bc64044cecdbd8a4a9d932e8968a4bb8625273bad81f8b9532b16bce9d|EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1.bit|2|68d8956971e5b327e90391b18c0dc0eb
            EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1|EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1.zip|7ebc6db0e9bfbceeef1f168843e824cdd2b3a672c9574eceea9ada8f41a37a37|EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1/EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1.bit|2|7ef62c35074ed780790cb32d1b5da436
            GENERAL_10b_420_RExt_Sony_1|GENERAL_10b_420_RExt_Sony_1.zip|fd878ccd5e1c1b306a110fac13237a76d1a3984cf95529e5e569924f074082dd|GENERAL_10b_420_RExt_Sony_1/GENERAL_10b_420_RExt_Sony_1.bit|1|d19c7e1764971983f0e827277db2f69a
            GENERAL_10b_422_RExt_Sony_1|GENERAL_10b_422_RExt_Sony_1.zip|c22c21e0dbe5630fbb009b93799eb1caf7f0f7b445135684846194ef9713fe4d|GENERAL_10b_422_RExt_Sony_1/GENERAL_10b_422_RExt_Sony_1.bit|2|ea5dfb01c95bffb169cd78e549bd39ab
            GENERAL_10b_444_RExt_Sony_2|GENERAL_10b_444_RExt_Sony_2.zip|7a5c47606350bfce116a9f8a9a00afa4101d6eb3317e46d8159eb3d300f03747|GENERAL_10b_444_RExt_Sony_2/GENERAL_10b_444_RExt_Sony_2.bit|12|079dd98d0cb2ab8fe26475a2e946fe53
            GENERAL_12b_400_RExt_Sony_1|GENERAL_12b_400_RExt_Sony_1.zip|8524fa46aee7066ca77f08092f799572809b01e0d5f35c73286cbd0d414d0bf3|GENERAL_12b_400_RExt_Sony_1/GENERAL_12b_400_RExt_Sony_1.bit|1|549ff2b94ede8d83bfdc64a34440817d
            GENERAL_12b_420_RExt_Sony_1|GENERAL_12b_420_RExt_Sony_1.zip|931932aa943957ce18ac693253e8ce8d07ebb1ef9cc32527e06e5cb8c59c3c71|GENERAL_12b_420_RExt_Sony_1/GENERAL_12b_420_RExt_Sony_1.bit|1|27a2b09b136687f7a391bfdbc50ac964
            GENERAL_12b_422_RExt_Sony_1|GENERAL_12b_422_RExt_Sony_1.zip|56ece2fce59fd5aeee5d889c2e46c44f85480293c6164fd6d7f8b5410a26d4fa|GENERAL_12b_422_RExt_Sony_1/GENERAL_12b_422_RExt_Sony_1.bit|2|d4f51ab5405659ad90e788083bcfc18d
            GENERAL_12b_444_RExt_Sony_2|GENERAL_12b_444_RExt_Sony_2.zip|a8bcb6c4f9006ee6a4ac0476d94d24a7c488ea8c99409c11e3e9d2740d06f212|GENERAL_12b_444_RExt_Sony_2/GENERAL_12b_444_RExt_Sony_2.bit|14|0b81d1008ff671da0d930bd38ab3489c
            GENERAL_16b_400_RExt_Sony_1|GENERAL_16b_400_RExt_Sony_1.zip|2168cf2eedd26b8d37cffa3e3329c2fc5399fbac486df184c7ea8e619ae92c90|GENERAL_16b_400_RExt_Sony_1/GENERAL_16b_400_RExt_Sony_1.bit|7|eb432746ce6c66943613397da28526a7
            GENERAL_16b_444_highThroughput_RExt_Sony_2|GENERAL_16b_444_highThroughput_RExt_Sony_2.zip|28340b588332a0ce85407e04e7c7aa68ecc6fe346922838f090304bf72226980|GENERAL_16b_444_highThroughput_RExt_Sony_2/GENERAL_16b_444_highThroughput_RExt_Sony_2.bit|16|7422848e6a99897a8ade34814d396657
            GENERAL_16b_444_RExt_Sony_2|GENERAL_16b_444_RExt_Sony_2.zip|bd1a2510b4be096852102039ddcca9e682c03b6191ca25a06758c4db6dfe3dd6|GENERAL_16b_444_RExt_Sony_2/GENERAL_16b_444_RExt_Sony_2.bit|16|41eb40070aae0bbedd584e1f73512840
            GENERAL_8b_400_RExt_Sony_1|GENERAL_8b_400_RExt_Sony_1.zip|64d8eda9ecd8a86acbd9d91fe6e83927a8a155e8d13c891770f1d0d2a1792980|GENERAL_8b_400_RExt_Sony_1/GENERAL_8b_400_RExt_Sony_1.bit|1|e5223be3da805fb96440dbf2bd170db0
            GENERAL_8b_420_RExt_Sony_1|GENERAL_8b_420_RExt_Sony_1.zip|a3121dd9a59902b4517a395e91a48337d9fc11f4caf60208dbdf9c43fdd6c2db|GENERAL_8b_420_RExt_Sony_1/GENERAL_8b_420_RExt_Sony_1.bit|1|99eb7a5106988759fd0eaecdb66e2cd2
            GENERAL_8b_444_RExt_Sony_2|GENERAL_8b_444_RExt_Sony_2.zip|dd810f2b7e47e2dd4f516599b2a366084ad47f1af01fe2763caa9ab9ca1233c0|GENERAL_8b_444_RExt_Sony_2/GENERAL_8b_444_RExt_Sony_2.bit|14|3ed16fed34c4ed3ad8fe31962fb77aac
            IPCM_A_RExt_NEC_2|IPCM_A_RExt_NEC_2.zip|cc78df0aa70ed8536b002b48ea7feacf72ab703bba7e5286c290d02d1eb51308|IPCM_A_RExt_NEC_2/IPCM_A_RExt_NEC_2.bit|1|8d4eb18812338f9f475035b8da27e74a
            IPCM_B_RExt_NEC|IPCM_B_RExt_NEC.zip|b248e35a32f76f3384ddfb3ea7727b7d088ae977b3c649e152cca06149f546ce|IPCM_B_RExt_NEC/IPCM_B_RExt_NEC.bit|1|eea32e6e1f88b7782515e53f8122be7d
            Main_422_10_A_RExt_Sony_2|Main_422_10_A_RExt_Sony_2.zip|7618ccc9c836eec9a981910f4e0e46dc36936d620755857005cf6f85bbd5cc84|Main_422_10_A_RExt_Sony_2/Main_422_10_A_RExt_Sony_2.bin|9|66530b83be6647f87efbb2b9ae6e1e28
            Main_422_10_B_RExt_Sony_2|Main_422_10_B_RExt_Sony_2.zip|9a8cb40f0ac87635ada427efe80deaad7fe45c8abd2e72bef258d5ec0914c086|Main_422_10_B_RExt_Sony_2/Main_422_10_B_RExt_Sony_2.bin|1|fd163bcbdc24792a23781ee7aace74eb
            PERSIST_RPARAM_A_RExt_Sony_3|PERSIST_RPARAM_A_RExt_Sony_3.zip|8237dfe09f09e9ad390c04a30fa57d66f26ab75b9da90c6b482ba1330dcdbebb|PERSIST_RPARAM_A_RExt_Sony_3/PERSIST_RPARAM_A_RExt_Sony_3.bit|2|d19f5cd87d5b971c4b4abbca22958031
            QMATRIX_A_RExt_Sony_1|QMATRIX_A_RExt_Sony_1.zip|45123e91b5d0dc9bffc09735ec532bc23a32bbcdecc1a0c7972301fef09be065|QMATRIX_A_RExt_Sony_1/QMATRIX_A_RExt_Sony_1.bit|20|e0fe38f19cf0ac875ce6674da989ea38
            SAO_A_RExt_MediaTek_1|SAO_A_RExt_MediaTek_1.zip|d0b5646150b35fb8e4c1d51f3d451aeddf964581132c9c49b253e34556beea4d|SAO_A_RExt_MediaTek_1/SAO_A_RExt_MediaTek_1.bit|8|126cd42a185b327d86640a9269044bc8
            TSCTX_10bit_I_RExt_SHARP_1|TSCTX_10bit_I_RExt_SHARP_1.zip|813f1951c6eb2c1f469098a05d72fbb23be0fd1c9d15fcb93c54da74c0fe9169|TSCTX_10bit_I_RExt_SHARP_1/TSCTX_10bit_I_RExt_SHARP_1.bin|9|2230c35a91ddde5455a36c49fb868e86
            TSCTX_10bit_RExt_SHARP_1|TSCTX_10bit_RExt_SHARP_1.zip|26089f6c63e57b1b69e3df27209e104431e02024d4138f02b4b2bfce63e87600|TSCTX_10bit_RExt_SHARP_1/TSCTX_10bit_RExt_SHARP_1.bin|9|a3015d8f4bd0a90645514bcf2e1fa8a7
            TSCTX_12bit_I_RExt_SHARP_1|TSCTX_12bit_I_RExt_SHARP_1.zip|96cc6851668f4968eb439fc4e4823e73394415c4d26b9032ab5ec401e753f0fc|TSCTX_12bit_I_RExt_SHARP_1/TSCTX_12bit_I_RExt_SHARP_1.bin|9|acebd42c1edea6812f0c07a4e91d943f
            TSCTX_12bit_RExt_SHARP_1|TSCTX_12bit_RExt_SHARP_1.zip|40cf42625152150414c951aa9303d4974bf066051eb510c67317c5a86fa5f685|TSCTX_12bit_RExt_SHARP_1/TSCTX_12bit_RExt_SHARP_1.bin|9|f311f3e96d9fbda375bfce341b99cb99
            TSCTX_8bit_I_RExt_SHARP_1|TSCTX_8bit_I_RExt_SHARP_1.zip|e15d63cf239a2f4297ea6ee1e404cb02f19862929270448d0668bd01a5a3d352|TSCTX_8bit_I_RExt_SHARP_1/TSCTX_8bit_I_RExt_SHARP_1.bin|9|5930ca9acf0bc741fb4dd827214f8db7
            TSCTX_8bit_RExt_SHARP_1|TSCTX_8bit_RExt_SHARP_1.zip|e16681927585e94e6e453f7207a3ac7a482f16812ffe3489f3f0f99bcc1614cd|TSCTX_8bit_RExt_SHARP_1/TSCTX_8bit_RExt_SHARP_1.bin|9|00fa6cd027d3a37cc0d10e5575d955d2
            WAVETILES_RExt_Sony_2|WAVETILES_RExt_Sony_2.zip|9d78d6b831b3be52f19cc74f5b6c9fd2cabf7d2573741db9a8ad7d0346da30cb|WAVETILES_RExt_Sony_2/WAVETILES_RExt_Sony_2.bit|4|d4809354218e6747c70aed951b80acc9
            WPP_AND_TILE_10Bit422Test_HIGH_TP_444_10BIT_RExt_Apple_2|WPP_AND_TILE_10Bit422Test_HIGH_TP_444_10BIT_RExt_Apple_2.zip|c813326651e42576644264ea0f82d03dd79081f6e3163c1c0f283ee6ac03f4c2|WPP_AND_TILE_10Bit422Test_HIGH_TP_444_10BIT_RExt_Apple_2.bit|3|32e6b91c9497e161fe66c1fbd3cec51e
            WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_0_HIGH_TP_444_14BIT_RExt_Apple_2|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_0_HIGH_TP_444_14BIT_RExt_Apple_2.zip|128f5b7a2f60350753e0e05782dd9c318c2c20c0b10a51b0003a06f277a243d5|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_0_HIGH_TP_444_14BIT_RExt_Apple_2.bit|3|e134676d233f3f5b49ab4c252d5d18be
            WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2.zip|a852b1501950e9ce50827fd7e65043511115ffc6aca0e5e68a1b6b25932172b9|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2.bit|3|5347f09153fd54b24a37427ca0bd9b30
            WPP_AND_TILE_AND_CABAC_EXT_PREC_1_HIGH_TP_444_14BIT_RExt_Apple_2|WPP_AND_TILE_AND_CABAC_EXT_PREC_1_HIGH_TP_444_14BIT_RExt_Apple_2.zip|4d8135bd94fe6eeffd58e162999241f6068cccb67c377c18a18c25b33ef34a1d|WPP_AND_TILE_AND_CABAC_EXT_PREC_1_HIGH_TP_444_14BIT_RExt_Apple_2.bit|3|95f0019aed6c9c845fe5e2088aefdc39
            WPP_AND_TILE_HIGH_TP_444_8BIT_RExt_Apple_2|WPP_AND_TILE_HIGH_TP_444_8BIT_RExt_Apple_2.zip|e0703e36a275db2715d2051c72f0b4fc392c3cba9421c069fd58ee037823c8dd|WPP_AND_TILE_HIGH_TP_444_8BIT_RExt_Apple_2.bit|3|e134676d233f3f5b49ab4c252d5d18be
            WPP_HIGH_TP_444_8BIT_RExt_Apple_2|WPP_HIGH_TP_444_8BIT_RExt_Apple_2.zip|fdfd79b04a4faec48e7536e4ffcf692b84b2c5945253a9e8edfa382816b27335|WPP_HIGH_TP_444_8BIT_RExt_Apple_2.bit|3|3c94b5ebc0aed0abae4e619b9dcca9cc
            """;

    record Vector(String name, String archive, String sha256, String bitstream, int frames, String md5) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            result.add(new Vector(parts[0], parts[1], parts[2], parts[3], Integer.parseInt(parts[4]), parts[5]));
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
        var archive = Conformance.fetch("hevc-rext-" + vector.archive(), BASE + vector.archive(), vector.sha256());
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
