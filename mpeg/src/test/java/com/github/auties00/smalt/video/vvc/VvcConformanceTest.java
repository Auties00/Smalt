package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the bitstreams of the JVET VVC conformance suite (ITU-T H.266.1, the FDIS_r1 set) that the Main 10 and
 * Main 10 Still Picture profiles cover and requires the output to match the reference decoder's: the MD5 of every
 * output picture in output order (8-bit samples for the 8-bit streams, 16-bit little-endian samples for the 10-bit
 * streams, which are decoded at their own bit depth; luma only for 4:0:0) and the frame count, with every decoded
 * picture hash SEI message verified on the way. The expected MD5s are the {@code .yuv.md5} files of the archives
 * (the field streams' are of the woven frames). The base layer of the multi-layer streams must match the MD5 of the
 * reference decoder's layer 0 output, and the streams of the 4:2:2 and 4:4:4 profiles must be rejected as
 * unsupported. Each archive is pinned in {@code tools/testdata/vectors.tsv} and cached by
 * {@link Conformance#fetchListed}.
 */
@DisplayName("VVC conformance bitstreams (ITU-T H.266.1, FDIS_r1)")
class VvcConformanceTest {
    /**
     * Name | bitstream entry | output frames | MD5 of the output.
     */
    private static final String MANIFEST = """
            10b400_A_2|10b400_A_2.bit|49|9607a0890e78ad7b3ca728356e919670
            10b400_B_2|10b400_B_2.bit|49|b5fcc1c52de8ed51844a9bd2895bda75
            8b400_A_2|8b400_A_2.bit|49|87e14541c2b6568a109a5505c73950db
            8b400_B_2|8b400_B_2.bit|49|7fc9d86c78d08a8cdcaf097367ad2754
            8b420_A_2|8b420_A_2.bit|49|da63784a3f1adb4eb171a0207e7c8d0f
            8b420_B_2|8b420_B_2.bit|49|8bf6496f8768b68d8aa1106a89d3e38a
            ACTPIC_A_3|ACTPIC_A_3.bit|33|d0e3566e21ee4a969f8a7d14885834b9
            ACTPIC_B_3|ACTPIC_B_3.bit|33|adc5cd053f833bd677ffdaa04d018c00
            ACTPIC_C_3|ACTPIC_C_3.bit|33|d0e3566e21ee4a969f8a7d14885834b9
            AFF_A_2|AFF_A_2.bit|10|8732ee1301282050a105f5549c2e18ed
            AFF_B_2|AFF_B_2.bit|10|8ebbbd37e7837550d98cf62996eb89be
            ALF_A_3|ALF_A_3.bit|3|c74f2112dcf7c7621d5780e70c304f16
            ALF_B_3|ALF_B_3.bit|3|708118e71c27cc409dfd7310dc787cc7
            ALF_C_3|ALF_C_3.bit|4|3abac29208179fef1664347126da5dbc
            ALF_D_2|ALF_D_2.bit|8|7aebfea44c63e17ca00d39886e71d1fb
            AMVR_A_3|AMVR_A_3.bit|85|df0859af7e5e787c7875e81cc5232ea2
            AMVR_B_3|AMVR_B_3.bit|129|d6ca91941fbed14c437549be665bbcd0
            APSALF_A_2|APSALF_A_2.bit|8|7aebfea44c63e17ca00d39886e71d1fb
            APSLMCS_A_3|APSLMCS_A_3.bit|200|391ffc3c77fb4ec244030bbb45d0464d
            APSLMCS_B_3|APSLMCS_B_3.bit|200|c07378acf6b111fd506d89458f592993
            APSLMCS_C_2|APSLMCS_C_2.bit|200|7ae6e3706bbaab4cb025b77ed917146d
            APSLMCS_D_1|APSLMCS_D_1.bit|32|2aa1e8a92bb5fb2b93099d56ee9d53e2
            APSLMCS_E_1|APSLMCS_E_1.bit|64|1adcd17ef8d441243507de20cb4914d5
            APSMULT_A_4|APSMULT_A_4.bit|48|29957adb94778ef3f4fcbaadd54a8095
            APSMULT_B_4|APSMULT_B_4.bit|48|18d54fb93415be2c2902ffa37565162c
            AUD_A_3|AUD_A_3.bit|30|5318620364f1f8f6153da3212a62a1f6
            BCW_A_4|BCW_A_4.bit|44|9804d9d9c53fd4732a4fc7acce633fb6
            BDOF_A_4|BDOF_A_4.bit|44|0494d8059dcb77344e3489c6fb4e3dfe
            BDPCM_A_2|BDPCM_A_2.bit|3|8add42eff974256a352c802e95ec9196
            BOUNDARY_A_3|BOUNDARY_A_3.bit|1280|2d194d115e7e9c05d7883571a2908e3b
            BUMP_A_2|BUMP_A_2.bit|40|390895aec22ab1cb9a38a2e08b1b297b
            BUMP_B_2|BUMP_B_2.bit|40|390895aec22ab1cb9a38a2e08b1b297b
            BUMP_C_2|BUMP_C_2.bit|40|390895aec22ab1cb9a38a2e08b1b297b
            CCALF_A_3|CCALF_A_3.bit|33|3749afdcffa73b9da0e53b57c7f7bbe8
            CCALF_B_3|CCALF_B_3.bit|33|430ea233ad4f9d72a92fbece14dc010d
            CCALF_C_3|CCALF_C_3.bit|33|dba16561d87ae452d95fdba7512c81b4
            CCALF_D_3|CCALF_D_3.bit|33|81ffb84c91b73c6858322423d2355570
            CCLM_A_2|CCLM_A_2.bit|7|deffc9c6f33da9d28f6e17955bc939d8
            CIIP_A_4|CIIP_A_4.bit|64|5e2ecadc49c897ad466f325474d78467
            CROP_A_4|CROP_A_4.bit|100|9a1d81e857d26dd2e6675625ed73b42a
            CST_A_4|CST_A_4.bit|21|f920c5e0d02fd24f0dc68a6f9ff9b439
            CTU_A_4|CTU_A_4.bit|64|86bd5f24f6cd633d1a81e1cb38b1c3bc
            CTU_B_4|CTU_B_4.bit|64|9a99f7fc6a19bde180169773098384db
            CTU_C_4|CTU_C_4.bit|64|aab83fe630972dc3c98d93baa853e3a6
            CUBEMAP_A_3|CUBEMAP_A_3.bit|60|c21e46ca755ac54bc08b5a10087effb7
            CUBEMAP_B_3|CUBEMAP_B_3.bit|60|78cc534a99ea16595d9393afb551a7f5
            CUBEMAP_C_3|CUBEMAP_C_3.bit|60|98a8fa9f24e1bb019484f58bd3ad7e3d
            CodingToolsSets_A_2|CodingToolsSets_A_2.bit|2|fda2476f1f0ca046c0b3428689db314c
            CodingToolsSets_B_2|CodingToolsSets_B_2.bit|9|ef5596c9a128c97b9511c215a12dbc35
            CodingToolsSets_C_2|CodingToolsSets_C_2.bit|2|0d71aaa3bd6449f58deeca24fd9f4789
            CodingToolsSets_D_2|CodingToolsSets_D_2.bit|9|23746a9ccab972cf26e6948a56d2bb96
            CodingToolsSets_E_1|CodingToolsSets_E_1.bit|9|304093bb838857d32d8d2f197b13da7d
            DCI_A_3|DCI_A_3.bit|2|3c28960461ee746886032ada5ca32336
            DCI_B_3|DCI_B_3.bit|2|3c28960461ee746886032ada5ca32336
            DEBLOCKING_A_3|DEBLOCKING_A_3.bit|17|566bbee74cd034e892f82a7a49099a22
            DEBLOCKING_B_2|DEBLOCKING_B_2.bit|65|6a8992d65d430a4ce086f26b87997ea3
            DEBLOCKING_C_3|DEBLOCKING_C_3.bit|33|df88d9af5bf2452aff0b9121d79d835d
            DEBLOCKING_E_3|DEBLOCKING_E_3.bit|8|dc522c0c4e901f111b2f1d26cd7f551e
            DEBLOCKING_F_2|DEBLOCKING_F_2.bit|132|9c278adbfdbb06a5c59184e275638fd0
            DMVR_A_3|DMVR_A_3.bit|9|1b912fde6dbafeca0abb3097a592abf2
            DMVR_B_4|DMVR_B_4.bit|11|e83247cc74d5af9405f111db983ccfe5
            DPB_A_2|DPB_A_2.bit|50|51e5086343b7eb68328a254fd7dade66
            DPB_B_2|DPB_B_2.bit|5|23906b3a201def46f1e807f9d24c2ffd
            DQ_A_3|DQ_A_3.bit|99|ffe7baaeb44540b641c517a5c056286d
            DQ_B_3|DQ_B_3.bit|720|af3463963b4aed8d1ebaf5e885f68fcb
            ENTHIGHTIER_A_3|ENTHIGHTIER_A_3.bit|3|29ddbf7c22c7e58c89e774f1ae643047
            ENTHIGHTIER_B_3|ENTHIGHTIER_B_3.bit|3|0dc20ad0c41c042b69e1660b4f3f3ac9
            ENTHIGHTIER_C_3|ENTHIGHTIER_C_3.bit|3|9cee630da86a81c7b8f8c6c99a0f6987
            ENTHIGHTIER_D_3|ENTHIGHTIER_D_3.bit|3|3736a21a66f595e9a7406de54a3ede8c
            ENTMAINTIER_A_3|ENTMAINTIER_A_3.bit|3|86a8dd47aa908bc8d5f833e38d8e127d
            ENTMAINTIER_B_3|ENTMAINTIER_B_3.bit|3|2d1835bcf0588189f16ad0e83360a544
            ENTMAINTIER_C_3|ENTMAINTIER_C_3.bit|3|7dbd4bfa9ca5dee6fc11189f2e22154e
            ENTMAINTIER_D_3|ENTMAINTIER_D_3.bit|3|1fceaaa35c03a1b9547b6df6b76b742e
            ENTROPY_A_2|ENTROPY_A_2.bit|60|113e64270f3473869efb739e0273ffed
            ENTROPY_B_2|ENTROPY_B_2.bit|17|2277bc22b8c8fc0f6578c377e032b237
            ENTROPY_C_1|ENTROPY_C_1.bit|374|6ebeb9db422e0b9b7077dc25268891a6
            ERP_A_3|ERP_A_3.bit|60|3772b6d395d348d7b6323a3f908ada83
            FIELD_A_4|FIELD_A_4.bit|10|4c05faea2d7bbc3f242280a14dc12696
            FIELD_B_2|FIELD_B_2.bit|1|b2eea065bd3a014bb8045f24d792f40c
            FILLER_A_1|FILLER_A_1.bit|64|d3d03203c69728d6926582b1bbe9f40a
            GDR_A_2|GDR_A_2.bit|29|19b31563d3a927f0e60acffd6a54646b
            GDR_B_2|GDR_B_2.bit|74|cc74c53ae82d761f0868ecdf11e951ae
            GDR_C_2|GDR_C_2.bit|11|f554ac1c59d053992e201703a5abb2d9
            GDR_D_1|GDR_D_1.bit|29|72800687190db582b46b1d0e7ce04285
            GPM_A_3|GPM_A_3.bit|17|468e46fd7d6c362677eeda53d0695b38
            GPM_B_1|GPM_B_1.bit|45|82892deee3497f789882e4d7eb959254
            HLG_A_4|HLG_A_4.bit|65|5b1710cdb45fb0e0f519c118589c1b50
            HLG_B_4|HLG_B_4.bit|65|5b1710cdb45fb0e0f519c118589c1b50
            HRD_A_3|HRD_A_3.bit|60|cde6c0d2416335173cf9d2ff8d32e460
            HRD_B_2|HRD_B_2.bit|60|3bd31f8094e9df345aba8cc401a97c23
            IBC_A_2|IBC_A_2.bit|17|1ed62c56e2a2f2698aadf59d8a3bd60d
            IBC_B_2|IBC_B_2.bit|17|62f32ae1c7a4f56f1492468dd85095f2
            IBC_C_2|IBC_C_2.bit|17|cbea0370a4a5bf4fab9dcd18054d857a
            IBC_D_2|IBC_D_2.bit|17|42f812cebfd702e5bf20bf143a60c1e0
            IBC_E_1|IBC_E_1.bit|11|99920a857856771a6073b86bd0838c99
            IP_A_2|IP_A_2.bit|8|3bee6bf5a5ed09a87063d213c3a7c0d9
            IP_B_1|IP_B_1.bit|19|3284d53e34752c301c982c41f89551d8
            ISP_A_3|ISP_A_3.bit|34|dde5df2c0d28719a341b3b8123b53155
            ISP_B_3|ISP_B_3.bit|161|0c7dc45ca345ec005513cb5448aec238
            JCCR_A_2|JCCR_A_2.bit|3|a25211dcd6cf0a8e6b0323fe91fbf8e0
            JCCR_B_2|JCCR_B_2.bit|3|bb19fad491b1ff045ac4cea15b4984fa
            JCCR_C_3|JCCR_C_3.bit|66|c0303878a568dc0a29a12728c53dd999
            JCCR_D_3|JCCR_D_3.bit|480|6832b0cb62cb894383c3421ca3df39d4
            JCCR_E_1|JCCR_E_1.bit|3|a25211dcd6cf0a8e6b0323fe91fbf8e0
            JCCR_F_1|JCCR_F_1.bit|3|bb19fad491b1ff045ac4cea15b4984fa
            LFNST_A_4|LFNST_A_4.bit|53|f3e07a3fcc920c67d32ecf8b52b3e732
            LFNST_B_4|LFNST_B_4.bit|46|b220dae185f1ef32a6b593f7415f133b
            LFNST_C_3|LFNST_C_3.bit|152|bcc285f77fe11485add459e4f99160c1
            LFNST_D_3|LFNST_D_3.bit|300|52546e593e2c40db8cf98029773191f9
            LMCS_A_3|LMCS_A_3.bit|64|0922d6d08c4ebc7b5d063144cfa95b04
            LMCS_B_2|LMCS_B_2.bit|64|fddd9675977585a0d823c0e8b0f9188e
            LMCS_C_1|LMCS_C_1.bit|32|298c63181d0d367314c9692c204de426
            LOSSLESS_A_3|LOSSLESS_A_3.bit|100|b2b6a0a09e171c00b9df9c620cf47b7d
            LOSSLESS_B_3|LOSSLESS_B_3.bit|17|7c5b7623ecff86b67a52f7707d187307
            LTRP_A_3|LTRP_A_3.bit|80|b0b9bc26555669594149b3ef81d80c5c
            MERGE_A_2|MERGE_A_2.bit|10|6da2ed0dad1473e70cadadd9ca43e76a
            MERGE_B_2|MERGE_B_2.bit|10|843465c6a50fa031499d99c24e0f11d5
            MERGE_C_2|MERGE_C_2.bit|10|c680825dd0c616ad6367a2af8e6b8497
            MERGE_D_2|MERGE_D_2.bit|10|b7138942cbbf26fad54c0810591e9323
            MERGE_E_2|MERGE_E_2.bit|10|4a09f879018317fda2973a15cdda1695
            MERGE_F_2|MERGE_F_2.bit|65|a5f9950269eac2507baf0d7a243eea41
            MERGE_G_2|MERGE_G_2.bit|65|502a76151adedf1b8beee7f0d67ee735
            MERGE_H_2|MERGE_H_2.bit|65|ebe3ff7bbacaf3dc3d7b0ca94f44e854
            MERGE_I_2|MERGE_I_2.bit|65|a8c0daf0591151f2b6824ca6daecdc55
            MERGE_J_2|MERGE_J_2.bit|65|93712d08d1efc2d9c2db4c32a9f1fe59
            MIP_A_3|MIP_A_3.bit|39|38833c21863182d37467f3fabf13e72c
            MIP_B_3|MIP_B_3.bit|17|43708959d70cf4ea1704bd11ba8ce4f8
            MMVD_A_3|MMVD_A_3.bit|300|e07e524588f9a7e939924ae0a2a419e8
            MNUT_A_4|MNUT_A_4.bit|65|6b70f964a5bb78a87271565544b91234
            MNUT_B_3|MNUT_B_3.bit|20|c43da8e90f24e72d521761269374455a
            MPM_A_3|MPM_A_3.bit|37|5c1aa18256a778df681d18c536c2f42b
            MRLP_A_2|MRLP_A_2.bit|16|4251da59fb65df2b036ff82ae4558911
            MRLP_B_2|MRLP_B_2.bit|16|47b850cabc37c8d2bd75fdd6877f1a73
            MTS_A_4|MTS_A_4.bit|21|5ea1937a54fbd2c7920a08a835ee7e96
            MTS_B_4|MTS_B_4.bit|92|5ab7a01af1ee383fcf8edf7c77831b19
            MTS_LFNST_A_4|MTS_LFNST_A_4.bit|248|b52aaff2beb076d5f9413719387647ed
            MTS_LFNST_B_4|MTS_LFNST_B_4.bit|227|7aaa05fa83a36d627f30a4723df26106
            MVCOMP_A_2|MVCOMP_A_2.bit|17|cfcc312b90ec8822524c12e5c6ce9917
            OPI_A_1|OPI_A_1.bit|17|efc9fbae919838646cda71e21dc2e2f4
            PDPC_A_3|PDPC_A_3.bit|100|2732a1789fb6b2a264b3fb47c8c711e2
            PDPC_B_3|PDPC_B_3.bit|100|0ffe729c9090ce9f57e9fe3ea97349d2
            PDPC_C_2|PDPC_C_2.bit|100|04491709971aad732f4f1ff74ecb68b4
            PHSH_B_1|PHSH_B_1.bit|6|5ccbb8c904da44dd3ab1245e7db65682
            PMERGE_A_1|PMERGE_A_1.bit|17|1b14b6dcf16db28c23533a33d8fafac3
            PMERGE_B_1|PMERGE_B_1.bit|17|bff5571bad527e2f5369e6dbfda7e6b0
            PMERGE_C_1|PMERGE_C_1.bit|17|f18acbdd88b341d1d92ae73bc99190a5
            PMERGE_D_1|PMERGE_D_1.bit|17|701f1885de764fba9e8ab3e66c4a9afe
            PMERGE_E_1|PMERGE_E_1.bit|17|b92953ba977a56c73445fed54da2f8a6
            POC_A_1|POC_A_1.bit|20|7679445289c4eed21cc87e15b88dfb48
            POUT_A_2|POUT_A_2.bit|8|498e121d4fe57d1edc55505e9631a7bf
            PPS_A_1|PPS_A_1.bit|250|bf74179530bf7e837ae3ebaf82079d2c
            PPS_B_1|PPS_B_1.bit|64|6e3577283bb1e8e26d883c03d89cb3f8
            PPS_C_1|PPS_C_1.bit|64|ad797d5dbd052dc9ef6bdf9fc522f340
            PQ_A_1|PQ_A_1.bit|200|c07378acf6b111fd506d89458f592993
            PROF_A_3|PROF_A_3.bit|16|65badf01204e757ec8fc70c325db5107
            PROF_B_3|PROF_B_3.bit|16|1b643d2c1bb439410a80d12519506abe
            PSEXT_A_2|PSEXT_A_2.bit|9|ce9bd27e6a5ce5b839d054b8513f794b
            PSEXT_B_2|PSEXT_B_2.bit|9|ce9bd27e6a5ce5b839d054b8513f794b
            QTBTT_A_4|QTBTT_A_4.bit|64|136ea6884f665a18104fbd056b95038a
            QUANT_A_2|QUANT_A_2.bit|5|f2c06ae9b5195a4e5cd10d3e288a42ae
            QUANT_B_2|QUANT_B_2.bit|17|12f717e96ec4fcc91b69a2faacfae962
            QUANT_C_2|QUANT_C_2.bit|3|1451088ca92ef66af7cb03f13d7e11ad
            QUANT_D_4|QUANT_D_4.bit|3|08dc6d75369d466436b179ba13de71b3
            QUANT_E_1|QUANT_E_1.bit|33|2a0c27f6481e7226faa54920bd6fd561
            RAP_A_1|RAP_A_1.bit|1|f46da2475bd22db8757dfa82f036a84f
            RAP_B_1|RAP_B_1.bit|33|562412a45e9592b8bfe54bc64d0a7092
            RAP_C_1|RAP_C_1.bit|65|2c75ed985df67e09dfea70a16fc3d91a
            RAP_D_1|RAP_D_1.bit|33|4907419d59e00a8c2789732205e31a47
            RPL_A_2|RPL_A_2.bit|60|d7fed587fea32a2b107adcc9e35e6980
            RPR_A_4|RPR_A_4.bit|4|8ec0e217cde95699eebec47cacc9679f
            RPR_B_3|RPR_B_3.bit|4|d677077f1a2ae9bd7ed74aae1ea66855
            RPR_C_3|RPR_C_3.bit|4|68d5f70493dfef6dfc3a1d83477f138f
            RPR_D_1|RPR_D_1.bit|8|a2ec75e0438865d2189b6f29e0416807
            SAO_A_3|SAO_A_3.bit|60|85176b10e59d49a985c62e24fb23ee10
            SAO_B_3|SAO_B_3.bit|60|403e8104a15f82129a9048ffedb25ef0
            SAO_C_3|SAO_C_3.bit|60|0c303d7f065249226990eb26edda7431
            SBT_A_2|SBT_A_2.bit|57|953c9457641688e0f3cd2bd6e292d696
            SCALING_A_1|SCALING_A_1.bit|64|f990d21ef21896b64d0ba2dfbca1b338
            SCALING_B_1|SCALING_B_1.bit|64|c301642f54815ee04ef5ab9f51dd63e4
            SCALING_C_1|SCALING_C_1.bit|16|9c1ae8953c24d97c85f57739a78b7019
            SDH_A_2|SDH_A_2.bit|64|c4b9d94df3afa92008aad37398ee5741
            SLICES_A_3|SLICES_A_3.bit|25|d3fbdf6d2e7873aade2b4dcc00d59692
            SMVD_A_2|SMVD_A_2.bit|10|37ac855d34621ace68384c0fd87f8947
            SPS_A_1|SPS_A_1.bit|250|bf74179530bf7e837ae3ebaf82079d2c
            SPS_B_1|SPS_B_1.bit|128|301ec80d334d4b5c4420c50c026b8167
            SPS_C_1|SPS_C_1.bit|128|4c4930c353e2668f61079b8fed0c25da
            STILL_A_1|STILL_A_1.bit|1|a01e9805e94c9995edc54cac45b12918
            STILL_B_1|STILL_B_1.bit|1|24295aa7c9060124f5bceb6a6694eddd
            SUBPIC_A_3|SUBPIC_A_3.bit|4|0676202fde6a161a7025193465b685a2
            SUBPIC_B_3|SUBPIC_B_3.bit|30|c5733d26eaed5e85f5b384cb2d843e5a
            SUBPIC_C_1|SUBPIC_C_1.bit|32|cab9bc6ae7f0bb6045b4b4d533a97495
            SUBPIC_D_1|SUBPIC_D_1.bit|50|4b7461f334c4711fa2f5e8b60a3c31c8
            SUBPIC_E_1|SUBPIC_E_1.bit|64|d9f68237f59ebd24ada82e89bccad1ea
            SUFAPS_A_1|SUFAPS_A_1.bit|17|2b708610cca907b56bfb2dc1fcbc1792
            SbTMVP_A_3|SbTMVP_A_3.bit|49|bf62526a8cd73e71be6d5c81d511324e
            SbTMVP_B_3|SbTMVP_B_3.bit|49|e102e6b1d16def184273c33fc6555102
            TEMPSCAL_A_4|TEMPSCAL_A_4.bit|600|be24468840eef19a7e1e8efdd259ba28
            TEMPSCAL_B_7|TEMPSCAL_B_7.bit|240|1c39250c25317ede21677aaf24c39760
            TEMPSCAL_C_4|TEMPSCAL_C_4.bit|200|f816c0003ffaf0b4f70056fc7a32c95d
            TILE_A_2|TILE_A_2.bit|9|84c1ac923243f008cf04234bd2b8515d
            TILE_B_2|TILE_B_2.bit|9|43bff2d63819814129c9d2744687b16b
            TILE_C_2|TILE_C_2.bit|9|66261992cba7a828058d6761cdddc888
            TILE_D_2|TILE_D_2.bit|9|6d56ab4ee6636c39b2a6fef135a5f11e
            TILE_E_2|TILE_E_2.bit|9|964fb8ba8b9147917e2cfc2087811123
            TILE_F_2|TILE_F_2.bit|9|fc2e6f73676d9f9ecfe4b76bf576340c
            TILE_G_2|TILE_G_2.bit|9|81aad2be0e559542380c09b2d1afd613
            TMVP_A_3|TMVP_A_3.bit|64|a41e06dc095f81648a691ef05d611911
            TMVP_B_3|TMVP_B_3.bit|64|a6eae3a8165d41c5879894e3221a33db
            TMVP_C_3|TMVP_C_3.bit|64|673d7c7c39fe0e20d5953aa3ce7cf94d
            TMVP_D_3|TMVP_D_3.bit|64|fa27e8c13579eb41fb85dace741b48d6
            TRANS_A_2|TRANS_A_2.bit|63|a8e5514d7f9b91cdc8c72dc1585b4624
            TRANS_B_2|TRANS_B_2.bit|63|973c3fb027287a9da0cd7b08dbe435c8
            TRANS_C_4|TRANS_C_4.bit|64|ae3d11d9c86ce906d8d76b4d65ae72c1
            TRANS_D_4|TRANS_D_4.bit|64|cd6886010ea573c14f310400e09f8e52
            TREE_A_3|TREE_A_3.bit|1479|fffc6bc2778989543bca5112c1de97e7
            TREE_B_3|TREE_B_3.bit|3247|8547c6f156ad1cf9838d7844430cc24a
            TREE_C_3|TREE_C_3.bit|4267|9776ba9d54f57a33be2d9807bc33c783
            VIRTUAL_A_3|VIRTUAL_A_3.bit|60|ec790ccc6e1e4e1ded0689821e11e787
            VIRTUAL_B_3|VIRTUAL_B_3.bit|60|5c64969ce1cb2d4b960f5809ac998874
            WPP_A_3|WPP_A_3.bit|49|8b2803b4d95dc20adf94e077a139860b
            WPP_B_2|WPP_B_2.bit|49|62a5d70c50c13842ac85e55aa1fc0f3d
            WP_A_3|WP_A_3.bit|17|40223c81e308ddb9e98ab5524960e0aa
            WP_B_3|WP_B_3.bit|17|eb9aa1b005b6777e8aa3b3f47548e84d
            WRAP_A_4|WRAP_A_4.bit|9|d7f2687231a1f2f9ec70fa3ff5782f38
            WRAP_B_4|WRAP_B_4.bit|17|871713a03837f406d524f372d3c97bdc
            WRAP_C_4|WRAP_C_4.bit|9|7d2db606e7ffefd254a73f5faac5e164
            WRAP_D_4|WRAP_D_4.bit|9|e369f002a46e92338db1271dd6166157
            """;

    /**
     * Name | bitstream entry | base layer output frames | MD5 of the reference decoder's layer 0 output.
     */
    private static final String LAYERED = """
            OLS_A_5|OLS_A_5.bit|5|0dd8fadf466dc68466af7848b7cc85f0
            OLS_B_5|OLS_B_5.bit|5|0dd8fadf466dc68466af7848b7cc85f0
            OLS_C_5|OLS_C_5.bit|5|0dd8fadf466dc68466af7848b7cc85f0
            VPS_A_3|VPS_A_3.bit|9|083ba0039cf01e461abb990b0965ae50
            VPS_B_1|VPS_B_1.bit|10|71f312807953304f6bec1d4b568de172
            VPS_C_2|VPS_C_2.bit|64|10f5abbddce19b9290ff73567757f0a0
            ILRPL_A_2|ILRPL_A_2.bit|5|6dcef29e83c3a7d2c024c259976f6c95
            OPI_B_3|OPI_B_3.bit|17|ac58502ff7032f8f1e1a64df628d1543
            """;

    /**
     * The stream whose expected MD5 covers its first picture only: it conforms to the Main 10 profile, and a Main 10
     * Still Picture decoder needs to decode only its first picture, a GDR picture with {@code ph_recovery_poc_cnt}
     * equal to 0 (H.266 A.3.1); the reference output is that picture.
     */
    private static final String FIRST_PICTURE_ONLY = "STILL_B_1";

    private static final byte[] START_CODE = {0, 0, 0, 1};

    record Vector(String name, String bitstream, int frames, String md5) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> all() {
        return parse(MANIFEST);
    }

    static Stream<Vector> layered() {
        return parse(LAYERED);
    }

    static Stream<Vector> accessUnits() {
        return all().filter(vector -> vector.name().matches(
                "AMVR_A_3|BDOF_A_4|CCALF_A_3|DMVR_A_3|GPM_A_3|IBC_A_2|LMCS_A_3|MMVD_A_3|PPS_B_1|RPR_A_4|SUBPIC_A_3"
                        + "|TILE_B_2|WPP_A_3|FIELD_A_4"));
    }

    private static Stream<Vector> parse(String manifest) {
        var result = new ArrayList<Vector>();
        for (var line : manifest.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            result.add(new Vector(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3]));
        }
        return result.stream();
    }

    static byte[] bitstream(String name, String entry) throws IOException {
        var archive = Conformance.fetchListed("vvc-fdis-" + name + ".zip");
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(entry)).readAllBytes();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes every picture bit-exactly")
    void bitExact(Vector vector) throws IOException {
        var options = VvcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        var stream = bitstream(vector.name(), vector.bitstream());
        if (vector.name().equals(FIRST_PICTURE_ONLY)) {
            assertEquals(vector.md5(), firstPicture(stream, options), "MD5 of the first picture of " + vector.name());
            return;
        }
        var result = VvcTestStreams.decode(stream, options);
        assertEquals(vector.frames(), result.frames(), "frame count of " + vector.name());
        assertEquals(vector.md5(), result.md5(), "output MD5 of " + vector.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accessUnits")
    @DisplayName("decodes bit-exactly from length-prefixed access units after a vvcC record")
    void bitExactAccessUnits(Vector vector) throws IOException {
        var options = VvcDecoderOptions.builder().highBitDepth(true).threads(4).build();
        var result = VvcTestStreams.decodeAccessUnits(bitstream(vector.name(), vector.bitstream()), options);
        assertEquals(vector.frames(), result.frames(), "frame count of " + vector.name());
        assertEquals(vector.md5(), result.md5(), "output MD5 of " + vector.name());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CodingToolsSets_E_1", "SLICES_A_3", "TILE_B_2"})
    @DisplayName("decodes bit-exactly with the parameter sets repeated between the slices of every picture")
    void repeatedParameterSets(String name) throws IOException {
        var vector = all().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
        var stream = withRepeatedParameterSets(bitstream(name, vector.bitstream()));
        var options = VvcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        var result = VvcTestStreams.decode(stream, options);
        assertEquals(vector.frames(), result.frames(), "frame count of " + name);
        assertEquals(vector.md5(), result.md5(), "output MD5 of " + name);
    }

    /**
     * Inserts a copy of the last sequence and picture parameter sets before every slice but the first of each
     * picture, as H.266 clause 7.4.2.4.4 allows (a repetition has the content of the set it repeats).
     */
    private static byte[] withRepeatedParameterSets(byte[] stream) {
        var out = new ByteArrayOutputStream();
        byte[] sps = null;
        byte[] pps = null;
        var inPicture = false;
        var inserted = 0;
        for (var nal : VvcTestStreams.nalUnits(stream)) {
            var type = VvcTestStreams.type(nal);
            var baseLayer = (nal[0] & 0x3F) == 0;
            if (baseLayer && type == 15) {
                sps = nal;
            } else if (baseLayer && type == 16) {
                pps = nal;
            } else if (baseLayer && type == 19) {
                inPicture = false;
            } else if (baseLayer && type <= 11) {
                var pictureHeaderInSlice = (nal[2] & 0x80) != 0;
                if (inPicture && !pictureHeaderInSlice && sps != null && pps != null) {
                    out.writeBytes(START_CODE);
                    out.writeBytes(sps);
                    out.writeBytes(START_CODE);
                    out.writeBytes(pps);
                    inserted++;
                }
                inPicture = true;
            }
            out.writeBytes(START_CODE);
            out.writeBytes(nal);
        }
        assertTrue(inserted > 0, "no picture with several slices");
        return out.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layered")
    @DisplayName("decodes the base layer of multi-layer streams bit-exactly")
    void baseLayer(Vector vector) throws IOException {
        var options = VvcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        var result = VvcTestStreams.decode(bitstream(vector.name(), vector.bitstream()), options);
        assertEquals(vector.frames(), result.frames(), "frame count of " + vector.name());
        assertEquals(vector.md5(), result.md5(), "base layer MD5 of " + vector.name());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"10b422_B_5", "8b444_A_2", "PALETTE_E_2"})
    @DisplayName("rejects the streams of the 4:2:2 and 4:4:4 profiles as unsupported")
    void unsupported(String name) throws IOException {
        var stream = bitstream(name, name + ".bit");
        assertThrows(MediaException.Unsupported.class, () -> VvcTestStreams.decode(stream,
                VvcDecoderOptions.defaults()));
    }

    private static String firstPicture(byte[] stream, VvcDecoderOptions options) {
        var digest = VvcTestStreams.md5();
        var count = new int[1];
        try (var decoder = new VvcDecoder(VvcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                if (count[0]++ == 0) {
                    VvcTestStreams.hash(digest, frame.picture());
                }
            };
            for (var nal : VvcTestStreams.splitAnnexB(stream)) {
                decoder.decode(VvcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
