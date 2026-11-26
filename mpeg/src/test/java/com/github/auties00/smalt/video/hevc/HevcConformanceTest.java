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
 * Decodes every bitstream of the JCT-VC HEVC_v1 conformance suite (ITU-T H.265.1) and requires the
 * output to match the reference decoder's: the MD5 of every output picture in output order (8-bit
 * samples for 8-bit streams, 16-bit little-endian samples for the Main 10 streams, which are
 * decoded at their own bit depth) and the frame count, with every decoded picture hash SEI message
 * verified on the way. The expected MD5s are those of the HM reference decoder's output, which equal
 * the MD5 or YUV files the archives carry. Each archive is read from the hosted test data by
 * {@link Conformance#vector}.
 */
@DisplayName("HEVC conformance bitstreams (ITU-T H.265.1, HEVC_v1)")
class HevcConformanceTest {
    /**
     * Name | archive | bitstream entry | output frames | MD5 of the output.
     */
    private static final String MANIFEST = """
            AMP_A_Samsung_7|AMP_A_Samsung_7.zip|AMP_A_Samsung_7.bin|17|93a5875d58072db5539c04e1e943ed9d
            AMP_B_Samsung_7|AMP_B_Samsung_7.zip|AMP_B_Samsung_7.bin|17|4e051fad4145d5127e16ac2999500069
            AMP_D_Hisilicon_3|AMP_D_Hisilicon_3.zip|AMP_D_Hisilicon.bit|100|277006085088cf68b9cafbbba0c19443
            AMP_E_Hisilicon_3|AMP_E_Hisilicon_3.zip|AMP_E_Hisilicon.bit|100|1a301e997917128420e3c33e6c14693a
            AMP_F_Hisilicon_3|AMP_F_Hisilicon_3.zip|AMP_F_Hisilicon_3.bit|100|6be6b5fb49a9514914f4ea812f737076
            AMVP_A_MTK_4|AMVP_A_MTK_4.zip|AMVP_A_MTK_4/AMVP_A_MTK_4.bit|30|5dd1b1099391ad659225fbf94965a6c7
            AMVP_B_MTK_4|AMVP_B_MTK_4.zip|AMVP_B_MTK_4/AMVP_B_MTK_4.bit|41|7fecb4fe8ee4d4838f4a9873b062b819
            AMVP_C_Samsung_7|AMVP_C_Samsung_7.zip|AMVP_C_Samsung_7.bin|60|75155ad034919a2846bbdcba13b5f733
            BUMPING_A_ericsson_1|BUMPING_A_ericsson_1.zip|BUMPING_A_ericsson_1.bit|49|64f213eb1cf45614f0eeb50fd1b725bf
            CAINIT_A_SHARP_4|CAINIT_A_SHARP_4.zip|CAINIT_A_SHARP_4.bit|50|550c5a918fecda6a284aa5f44c0e2f33
            CAINIT_B_SHARP_4|CAINIT_B_SHARP_4.zip|CAINIT_B_SHARP_4.bit|50|0c633f8ee5a4c5779cff48ca86273685
            CAINIT_C_SHARP_3|CAINIT_C_SHARP_3.zip|CAINIT_C_SHARP_3.bit|50|9c6c5363724a8bad54e2869b655fbf3b
            CAINIT_D_SHARP_3|CAINIT_D_SHARP_3.zip|CAINIT_D_SHARP_3.bit|50|0231e327044e45971fc30a43a1bd8275
            CAINIT_E_SHARP_3|CAINIT_E_SHARP_3.zip|CAINIT_E_SHARP_3.bit|50|55af76d5527af999d12ccc37b1f6b7ae
            CAINIT_F_SHARP_3|CAINIT_F_SHARP_3.zip|CAINIT_F_SHARP_3.bit|50|88d8cd2d303b662f0a92a25256957b69
            CAINIT_G_SHARP_3|CAINIT_G_SHARP_3.zip|CAINIT_G_SHARP_3.bit|50|a7036eac7798f6e8691f0975786d6811
            CAINIT_H_SHARP_3|CAINIT_H_SHARP_3.zip|CAINIT_H_SHARP_3.bit|50|cfdd3ce0f4cf1eafd012dcb266311d06
            CIP_A_Panasonic_3|CIP_A_Panasonic_3.zip|CIP_A_Panasonic_3/CIP_A_Panasonic_3.bit|2|4cbf601ba98d63f642defab5eaa12c8d
            cip_B_NEC_3|cip_B_NEC_3.zip|cip_B_NEC_3/cip_B_NEC_3.bit|5|1315714c7b3f3237b469259f66d9b7aa
            CIP_C_Panasonic_2|CIP_C_Panasonic_2.zip|CIP_C_Panasonic_2/CIP_C_Panasonic_2.bit|2|ed985932fef2aa8a74ded970070bdab2
            CONFWIN_A_Sony_1|CONFWIN_A_Sony_1.zip|CONFWIN_A_Sony_1/CONFWIN_A_Sony_1.bit|60|a3ce3f936ff69ff1ec2621a622dd37ac
            DBLK_A_MAIN10_VIXS_4|DBLK_A_MAIN10_VIXS_4.zip|DBLK_A_MAIN10_VIXS_4/DBLK_A_MAIN10_VIXS_4.bit|8|c4594956bb9e8303f1662f9eb1bcdf50
            DBLK_A_SONY_3|DBLK_A_SONY_3.zip|DBLK_A_SONY_3.bit|30|42486a48ea12d5ab6cd98ed2e2807cfe
            DBLK_B_SONY_3|DBLK_B_SONY_3.zip|DBLK_B_SONY_3.bit|30|cf5de25a94a33e2d4237b8d1c773db06
            DBLK_C_SONY_3|DBLK_C_SONY_3.zip|DBLK_C_SONY_3.bit|30|41f89edc63b1175ffc1fae909f4fed82
            DBLK_D_VIXS_2|DBLK_D_VIXS_2.zip|DBLK_D_VIXS_2/DBLK_D_VIXS_2.bit|8|f6f3075edca573f5dfe4e352d26170f1
            DBLK_E_VIXS_2|DBLK_E_VIXS_2.zip|DBLK_E_VIXS_2/DBLK_E_VIXS_2.bit|8|1f94d56d9e462967cad73284ed0f43f9
            DBLK_F_VIXS_2|DBLK_F_VIXS_2.zip|DBLK_F_VIXS_2/DBLK_F_VIXS_2.bit|8|c94d0c0d80c93a2edd372a1fd12f93a1
            DBLK_G_VIXS_2|DBLK_G_VIXS_2.zip|DBLK_G_VIXS_2/DBLK_G_VIXS_2.bit|8|1f94d56d9e462967cad73284ed0f43f9
            DELTAQP_A_BRCM_4|DELTAQP_A_BRCM_4.zip|DELTAQP_A_BRCM_4/DELTAQP_A_BRCM_4.bit|96|1ceef83183197fd1f21472c17b53cbff
            DELTAQP_B_SONY_3|DELTAQP_B_SONY_3.zip|DELTAQP_B_SONY_3.bit|30|f02fa96633c63bd5e1ce0f103447d1ed
            DELTAQP_C_SONY_3|DELTAQP_C_SONY_3.zip|DELTAQP_C_SONY_3.bit|30|1c2be685b1ae953787e0e93c656ee502
            DSLICE_A_HHI_5|DSLICE_A_HHI_5.zip|DSLICE_A_HHI_5/DSLICE_A_HHI_5.bin|50|c7caf3164b0a316549ac7244f66f1294
            DSLICE_B_HHI_5|DSLICE_B_HHI_5.zip|DSLICE_B_HHI_5/DSLICE_B_HHI_5.bin|50|2e072130f116a4d0f7fc69fa76cfcdfb
            DSLICE_C_HHI_5|DSLICE_C_HHI_5.zip|DSLICE_C_HHI_5/DSLICE_C_HHI_5.bin|50|505a47c5ce4bc66ef5661aeb778b0f1f
            ENTP_A_QUALCOMM_1|ENTP_A_QUALCOMM_1.zip|ENTP_A_Qualcomm_1.bit|24|25f3c7facf9e7b75e81247206b4fc60d
            ENTP_B_Qualcomm_1|ENTP_B_Qualcomm_1.zip|ENTP_B_Qualcomm_1.bit|24|6577f102b93577eb899e0bff1a20a401
            ENTP_C_Qualcomm_1|ENTP_C_Qualcomm_1.zip|ENTP_C_Qualcomm_1.bit|24|0b607e6e7946e6e8d0f92a1a294d135d
            EXT_A_ericsson_4|EXT_A_ericsson_4.zip|EXT_A_ericsson_4.bit|3|5797b3114fe12daf1ce522ed70ce4078
            FILLER_A_Sony_1|FILLER_A_Sony_1.zip|FILLER_A_Sony_1/FILLER_A_Sony_1.bit|60|f92a928770a41b63e7cfc89ea198790f
            HRD_A_Fujitsu_3|HRD_A_Fujitsu_3.zip|HRD_A_Fujitsu_3.bin|96|f6d04dba2ef09bcadbea7b8ab5c8c917
            INITQP_A_Sony_1|INITQP_A_Sony_1.zip|INITQP_A_Sony_1/INITQP_A_Sony_1.bit|60|2716af552e71ef61221b86130ad3d444
            INITQP_B_Main10_Sony_1|INITQP_B_Main10_Sony_1.zip|INITQP_B_Main10_Sony_1/INITQP_B_Main10_Sony_1.bit|90|f63c71e5f6b9c6ddf2b080524f9f14b1
            ipcm_A_NEC_3|ipcm_A_NEC_3.zip|ipcm_A_NEC_3/ipcm_A_NEC_3.bit|1|8049988c383486e076ea2494edda3831
            ipcm_B_NEC_3|ipcm_B_NEC_3.zip|ipcm_B_NEC_3/ipcm_B_NEC_3.bit|1|23a3b7024fd9bc64b946b9961ab0f51e
            ipcm_C_NEC_3|ipcm_C_NEC_3.zip|ipcm_C_NEC_3/ipcm_C_NEC_3.bit|1|c3e74c399b73a5ab2dbd20523f583464
            ipcm_D_NEC_3|ipcm_D_NEC_3.zip|ipcm_D_NEC_3/ipcm_D_NEC_3.bit|1|aa64a16240064bc2a90fadf979a62a7b
            ipcm_E_NEC_2|ipcm_E_NEC_2.zip|ipcm_E_NEC_2/ipcm_E_NEC_2.bit|1|e1cd7a16d3f6a342855044ccba3e41f5
            IPRED_A_docomo_2|IPRED_A_docomo_2.zip|IPRED_A_docomo_2.bit|20|0e4e9bd00b74e30e400de46ee7ad8023
            IPRED_B_Nokia_3|IPRED_B_Nokia_3.zip|IPRED_B_Nokia_3.bit|1|ec3bcad4f9174404e52d132206b6617b
            IPRED_C_Mitsubishi_3|IPRED_C_Mitsubishi_3.zip|IPRED_C_Mitsubishi_3/IPRED_C_Mitsubishi_3.bit|4|6043c04fecc60ee88f4247aaca96441f
            LS_A_Orange_2|LS_A_Orange_2.zip|LS_A_Orange_2/LS_A_Orange_2.bit|9|e14f82958f87a87be73a6f51e76ffdab
            LS_B_Orange_4|LS_B_Orange_4.zip|LS_B_Orange_4/LS_B_Orange_4.bit|25|05ac9b36977c98001cc6c5a45e363662
            LTRPSPS_A_Qualcomm_1|LTRPSPS_A_Qualcomm_1.zip|LTRPSPS_A_Qualcomm_1.bit|500|e925d467c20180090c09c02d02c3aa25
            MAXBINS_A_TI_5|MAXBINS_A_TI_5.zip|MAXBINS_A_TI_5/MAXBINS_A_TI_5.bit|2|1386fb149ddc11bd5a1d9111879acfa4
            MAXBINS_B_TI_5|MAXBINS_B_TI_5.zip|MAXBINS_B_TI_5/MAXBINS_B_TI_5.bit|3|21d6910760ea4963eab28b26709471fe
            MAXBINS_C_TI_5|MAXBINS_C_TI_5.zip|MAXBINS_C_TI_5/MAXBINS_C_TI_5.bit|3|7a7e51fdd40c57d70af418f5082416fa
            MERGE_A_TI_3|MERGE_A_TI_3.zip|MERGE_A_TI_3/MERGE_A_TI_3.bit|8|3a15886a0158ef1aaf557869b2e9c522
            MERGE_B_TI_3|MERGE_B_TI_3.zip|MERGE_B_TI_3/MERGE_B_TI_3.bit|8|83b4dc9f229a8fba3777d9f4f87ce144
            MERGE_C_TI_3|MERGE_C_TI_3.zip|MERGE_C_TI_3/MERGE_C_TI_3.bit|8|216f0cc869e3989c8fb2cf261243160b
            MERGE_D_TI_3|MERGE_D_TI_3.zip|MERGE_D_TI_3/MERGE_D_TI_3.bit|8|104cccf2b8441862624777955d6ecdc4
            MERGE_E_TI_3|MERGE_E_TI_3.zip|MERGE_E_TI_3/MERGE_E_TI_3.bit|8|6e74dbd450123246c22bf01b389bbe11
            MERGE_F_MTK_4|MERGE_F_MTK_4.zip|MERGE_F_MTK_4/MERGE_F_MTK_4.bit|41|a8f0982f026dfda1c02b4d4d3eb88132
            MERGE_G_HHI_4|MERGE_G_HHI_4.zip|MERGE_G_HHI_4/MERGE_G_HHI_4.bit|2|c82f4c88d9b2c73fabbeac8944da35a1
            MVCLIP_A_qualcomm_3|MVCLIP_A_qualcomm_3.zip|MVCLIP_A_qualcomm_3/MVCLIP_A_qualcomm_3.bit|5|78b398c201089b6b17bcbda2bbccf3ae
            MVDL1ZERO_A_docomo_4|MVDL1ZERO_A_docomo_4.zip|MVDL1ZERO_A_docomo_4.bit|500|593d1936f6fc2e1775ea2a691a601c34
            MVEDGE_A_qualcomm_3|MVEDGE_A_qualcomm_3.zip|MVEDGE_A_qualcomm_3/MVEDGE_A_qualcomm_3.bit|17|101c5bfe16ff1739909161794af29a2d
            NoOutPrior_A_Qualcomm_1|NoOutPrior_A_Qualcomm_1.zip|NoOutPrior_A_Qualcomm_1.bit|40|1c5f20a08ab8dfa0f44d9a0ca23d174d
            NoOutPrior_B_Qualcomm_1|NoOutPrior_B_Qualcomm_1.zip|NoOutPrior_B_Qualcomm_1.bit|47|048bca0ec216a3f88cf9fbb4bb5a61ac
            NUT_A_ericsson_5|NUT_A_ericsson_5.zip|NUT_A_ericsson_5.bit|34|9dbe33badd0cc6dfcd1b53e546968fbd
            OPFLAG_A_Qualcomm_1|OPFLAG_A_Qualcomm_1.zip|OPFLAG_A_Qualcomm_1.bit|500|35eeeca175938ee262f72b7485146f94
            OPFLAG_B_Qualcomm_1|OPFLAG_B_Qualcomm_1.zip|OPFLAG_B_Qualcomm_1.bit|98|516811eeff3ae5ee056dda290dcf2735
            OPFLAG_C_Qualcomm_1|OPFLAG_C_Qualcomm_1.zip|OPFLAG_C_Qualcomm_1.bit|96|a82c4f6ee843150067bba60b9342fc50
            PICSIZE_A_Bossen_1|PICSIZE_A_Bossen_1.zip|PICSIZE_A_Bossen_1/PICSIZE_A_Bossen_1.bin|10|910ff0e6b3739af0b4aba4f6aa6704f8
            PICSIZE_B_Bossen_1|PICSIZE_B_Bossen_1.zip|PICSIZE_B_Bossen_1/PICSIZE_B_Bossen_1.bin|10|e5c4c20a8871aa446a344efb1755bcf9
            PICSIZE_C_Bossen_1|PICSIZE_C_Bossen_1.zip|PICSIZE_C_Bossen_1/PICSIZE_C_Bossen_1.bin|10|674f9f11eb956257d86782949ec00399
            PICSIZE_D_Bossen_1|PICSIZE_D_Bossen_1.zip|PICSIZE_D_Bossen_1/PICSIZE_D_Bossen_1.bin|10|5b76e0591618f5affe8b15b03d9c0bef
            PMERGE_A_TI_3|PMERGE_A_TI_3.zip|PMERGE_A_TI_3/PMERGE_A_TI_3.bit|8|6e74dbd450123246c22bf01b389bbe11
            PMERGE_B_TI_3|PMERGE_B_TI_3.zip|PMERGE_B_TI_3/PMERGE_B_TI_3.bit|8|7d0e57ebdbbc6633c972083a17d97550
            PMERGE_C_TI_3|PMERGE_C_TI_3.zip|PMERGE_C_TI_3/PMERGE_C_TI_3.bit|8|7e23d874b198ec7a9f6086a20de0db76
            PMERGE_D_TI_3|PMERGE_D_TI_3.zip|PMERGE_D_TI_3/PMERGE_D_TI_3.bit|8|9771b8c8a71e41547f14032e07be7d61
            PMERGE_E_TI_3|PMERGE_E_TI_3.zip|PMERGE_E_TI_3/PMERGE_E_TI_3.bit|8|26aed56afdb6cb7011fd5c3303379a9d
            POC_A_Bossen_3|POC_A_Bossen_3.zip|POC_A_Bossen_3/POC_A_Bossen_3.bin|5|faf33bec64d3e58c426ed2772903a0bf
            PPS_A_qualcomm_7|PPS_A_qualcomm_7.zip|PPS_A_qualcomm_7/PPS_A_qualcomm_7.bit|81|bccf29fab6744215fe007ea3a5d6da42
            PS_B_VIDYO_3|PS_B_VIDYO_3.zip|PS_B_VIDYO_3/PS_B_VIDYO_3.bit|25|eb85521ee103333eac61a8253637eac4
            RAP_A_docomo_6|RAP_A_docomo_6.zip|RAP_A_docomo_6.bit|86|8a536a80ed42b37b1ac5810bc046f82a
            RAP_B_Bossen_2|RAP_B_Bossen_2.zip|RAP_B_Bossen_2.bit|80|f38befcc280f2fd0d23f48f807f843c2
            RPLM_A_qualcomm_4|RPLM_A_qualcomm_4.zip|RPLM_A_qualcomm_4/RPLM_A_qualcomm_4.bit|300|011794872709c341880ee42ade036ed3
            RPLM_B_qualcomm_4|RPLM_B_qualcomm_4.zip|RPLM_B_qualcomm_4/RPLM_B_qualcomm_4.bit|300|653ac7c46fa7b9d7d966e3db317eb938
            RPS_A_docomo_5|RPS_A_docomo_5.zip|RPS_A_docomo_5.bit|44|7f4ad6c6b3de54558b0db59629b87db9
            RPS_B_qualcomm_5|RPS_B_qualcomm_5.zip|RPS_B_qualcomm_5/RPS_B_qualcomm_5.bit|300|6d1ed392b067050ebd3a24a37281da03
            RPS_C_ericsson_5|RPS_C_ericsson_5.zip|RPS_C_ericsson_5.bit|40|0306b9e3ec365115ccb7060c0a437b32
            RPS_D_ericsson_6|RPS_D_ericsson_6.zip|RPS_D_ericsson_6.bit|68|d0fcd71e6266b1ae1f66b9cb1802ea03
            RPS_E_qualcomm_5|RPS_E_qualcomm_5.zip|RPS_E_qualcomm_5/RPS_E_qualcomm_5.bit|300|c30d38bbd7ea483dc7ffe81325118e16
            RPS_F_docomo_2|RPS_F_docomo_2.zip|RPS_F_docomo_2.bit|50|fd6e6d973d39622d197dbdf4543643f2
            RQT_A_HHI_4|RQT_A_HHI_4.zip|RQT_A_HHI_4/RQT_A_HHI_4.bit|2|aa7f6ef07bf76ebddd52f7e528cd6ab8
            RQT_B_HHI_4|RQT_B_HHI_4.zip|RQT_B_HHI_4/RQT_B_HHI_4.bit|2|a63f4f4e966e77a5eb4e65dcdcc488a4
            RQT_C_HHI_4|RQT_C_HHI_4.zip|RQT_C_HHI_4/RQT_C_HHI_4.bit|2|c82f4c88d9b2c73fabbeac8944da35a1
            RQT_D_HHI_4|RQT_D_HHI_4.zip|RQT_D_HHI_4/RQT_D_HHI_4.bit|2|33b58b954fa82753526fda9e9e6bc8cb
            RQT_E_HHI_4|RQT_E_HHI_4.zip|RQT_E_HHI_4/RQT_E_HHI_4.bit|2|65f22431d0eb149e04c327f881ce6581
            RQT_F_HHI_4|RQT_F_HHI_4.zip|RQT_F_HHI_4/RQT_F_HHI_4.bit|2|56ba354d919d06a33b44862dd18e9437
            RQT_G_HHI_4|RQT_G_HHI_4.zip|RQT_G_HHI_4/RQT_G_HHI_4.bit|2|2fb67256014a6840b69ca6dad2426304
            SAO_A_MediaTek_4|SAO_A_MediaTek_4.zip|SAO_A_MediaTek_4.bit|60|272e694a2262a2b34f6248f787a4431d
            SAO_B_MediaTek_5|SAO_B_MediaTek_5.zip|SAO_B_MediaTek_5.bit|60|90cc57237e3ce47eb3b4c901e200112d
            SAO_C_Samsung_5|SAO_C_Samsung_5.zip|SAO_C_Samsung_5/SAO_C_Samsung_5.bin|5|962290491f2f1a1e70ab7aa63d2264bc
            SAO_D_Samsung_5|SAO_D_Samsung_5.zip|SAO_D_Samsung_5/SAO_D_Samsung_5.bin|5|6db9575a7737816b1d7a3a3bf3ba126c
            SAO_E_Canon_4|SAO_E_Canon_4.zip|SAO_E_Canon_4.bit|16|0b04a1378274a6ffbb8f6121e027290c
            SAO_F_Canon_3|SAO_F_Canon_3.zip|SAO_F_Canon_3.bit|16|5c295ecba132d1e68a12dd8209e86a02
            SAO_G_Canon_3|SAO_G_Canon_3.zip|SAO_G_Canon_3.bit|16|1f743a3e28617b8bae3e348096edcf76
            SAO_H_Parabola_1|SAO_H_Parabola_1.zip|SAO_H_Parabola_1.bit|4|7e23c16898b14831b25d7857b69a9a9a
            SAODBLK_A_MainConcept_4|SAODBLK_A_MainConcept_4.zip|SAODBLK_A_MainConcept_4/SAODBLK_A_MainConcept_4.bin|500|b9c98937bcb6be445348fdc89c208f1e
            SAODBLK_B_MainConcept_4|SAODBLK_B_MainConcept_4.zip|SAODBLK_B_MainConcept_4/SAODBLK_B_MainConcept_4.bin|500|031043495e9b9f4ce120afa316aa5705
            SDH_A_Orange_4|SDH_A_Orange_4.zip|SDH_A_Orange_4/SDH_A_Orange_4.bit|2|0a1a6a76a86dbdc7ca479e4235f5b822
            SLICES_A_Rovi_3|SLICES_A_Rovi_3.zip|SLICES_A_Rovi_3.bin|9|c2d63a4d145a5713afecd822032ec271
            SLIST_A_Sony_5|SLIST_A_Sony_5.zip|SLIST_A_Sony_5/SLIST_A_Sony_5.bin|65|b00bc5362e6fad1a0e704aad8e69b863
            SLIST_B_Sony_9|SLIST_B_Sony_9.zip|SLIST_B_Sony_9/SLIST_B_Sony_9.bin|65|312b4ebb3e885587d5e12b78aa95977a
            SLIST_C_Sony_4|SLIST_C_Sony_4.zip|SLIST_C_Sony_4/SLIST_C_Sony_4.bin|65|61024c25cbd60e9bf86dbe3bc5b9b48b
            SLIST_D_Sony_9|SLIST_D_Sony_9.zip|SLIST_D_Sony_9/str.bin|65|8d38ce43b17627ff1f86e526bfb7ffc4
            SLPPLP_A_VIDYO_2|SLPPLP_A_VIDYO_2.zip|SLPPLP_A_VIDYO_2/SLPPLP_A_VIDYO_2.bit|33|99e13916ede2bc9b77718787579104a9
            STRUCT_A_Samsung_7|STRUCT_A_Samsung_7.zip|STRUCT_A_Samsung_7.bin|60|02f02cc9a67222004ae18ee6bebd475a
            STRUCT_B_Samsung_7|STRUCT_B_Samsung_7.zip|STRUCT_B_Samsung_7.bin|60|1f472e014aff120681e8482c8a04ef93
            TILES_A_Cisco_2|TILES_A_Cisco_2.zip|TILES_A_Cisco_2/TILES_A_Cisco_2.bin|100|946f0771fa87d49f684f96871c9c3c88
            TILES_B_Cisco_1|TILES_B_Cisco_1.zip|TILES_B_Cisco_1/TILES_B_Cisco_1.bin|100|3382291f2b19ee2d760647d4cc756e95
            TMVP_A_MS_3|TMVP_A_MS_3.zip|TMVP_A_MS_3.bit|17|168f8096e56b8a8c1aa03fa5fffdfd23
            TSCL_A_VIDYO_5|TSCL_A_VIDYO_5.zip|TSCL_A_VIDYO_5/TSCL_A_VIDYO_5.bit|73|27cbe23eabe1b174d745c286cc2ed8ff
            TSCL_B_VIDYO_4|TSCL_B_VIDYO_4.zip|TSCL_B_VIDYO_4/TSCL_B_VIDYO_4.bit|73|95e7910b85c08a01e2e9ce72c92d7d62
            TSKIP_A_MS_3|TSKIP_A_MS_3.zip|TSKIP_A_MS_3.bit|17|4280cb848219176f4fcad51f3290210d
            TSUNEQBD_A_MAIN10_Technicolor_2|TSUNEQBD_A_MAIN10_Technicolor_2.zip|TSUNEQBD_A_MAIN10_Technicolor_2.bit|8|1bed23df7253170eeb8efc3fd1b740a3
            TUSIZE_A_Samsung_1|TUSIZE_A_Samsung_1.zip|TUSIZE_A_Samsung_1.bin|5|5e6c198c852e92cdcca0e137fc2610c4
            VPSID_A_VIDYO_2|VPSID_A_VIDYO_2.zip|VPSID_A_VIDYO_2/VPSID_A_VIDYO_2.bit|33|99e13916ede2bc9b77718787579104a9
            VPSSPSPPS_A_MainConcept_1|VPSSPSPPS_A_MainConcept_1.zip|VPSSPSPPS_A_MainConcept_1.bin|6|1ddf74263cb4953cfdfcf99c563d88ea
            WP_A_MAIN10_Toshiba_3|WP_A_MAIN10_Toshiba_3.zip|WP_A_MAIN10_Toshiba_3/WP_A_MAIN10_Toshiba_3.bit|256|c48e853010a234786dc87790d053b03a
            WP_A_Toshiba_3|WP_A_Toshiba_3.zip|WP_A_Toshiba_3.bit|256|127b99d0c11c930b6f248750b5848a0b
            WP_B_Toshiba_3|WP_B_Toshiba_3.zip|WP_B_Toshiba_3.bit|256|82d39f8cd09e532b8fa9d35894c96258
            WP_MAIN10_B_Toshiba_3|WP_MAIN10_B_Toshiba_3.zip|WP_MAIN10_B_Toshiba_3.bit|256|ed891c60a17f5cbc64e1848f0ea9cf45
            WPP_A_ericsson_MAIN10_2|WPP_A_ericsson_MAIN10_2.zip|WPP_A_ericsson_MAIN10_2.bit|48|e68b9d75ff9cf1d9486e964051351f69
            WPP_A_ericsson_MAIN_2|WPP_A_ericsson_MAIN_2.zip|WPP_A_ericsson_MAIN_2.bit|48|cd7e815eb47e8138fec2185d4de84304
            WPP_B_ericsson_MAIN10_2|WPP_B_ericsson_MAIN10_2.zip|WPP_B_ericsson_MAIN10_2.bit|48|d8290fc1eec3a196aa796c92e71a779b
            WPP_B_ericsson_MAIN_2|WPP_B_ericsson_MAIN_2.zip|WPP_B_ericsson_MAIN_2.bit|48|e37c7e561a1226640a7bf98e81df78b1
            WPP_C_ericsson_MAIN10_2|WPP_C_ericsson_MAIN10_2.zip|WPP_C_ericsson_MAIN10_2.bit|48|aadac4d468d5950551d606c1016abc9c
            WPP_C_ericsson_MAIN_2|WPP_C_ericsson_MAIN_2.zip|WPP_C_ericsson_MAIN_2.bit|48|e067aa3a6a12cd5743849ded793c8d3f
            WPP_D_ericsson_MAIN10_2|WPP_D_ericsson_MAIN10_2.zip|WPP_D_ericsson_MAIN10_2.bit|48|dde9aa65b78a8ca5602d834f234655d4
            WPP_D_ericsson_MAIN_2|WPP_D_ericsson_MAIN_2.zip|WPP_D_ericsson_MAIN_2.bit|48|f710612103f386c415be3e6300693451
            WPP_E_ericsson_MAIN10_2|WPP_E_ericsson_MAIN10_2.zip|WPP_E_ericsson_MAIN10_2.bit|48|f12e746306c59544d6162de0b62c4649
            WPP_E_ericsson_MAIN_2|WPP_E_ericsson_MAIN_2.zip|WPP_E_ericsson_MAIN_2.bit|48|485798dbf95ad61232075df2f294aa3f
            WPP_F_ericsson_MAIN10_2|WPP_F_ericsson_MAIN10_2.zip|WPP_F_ericsson_MAIN10_2.bit|48|210055a33d7d912fda6a3c40764b0482
            WPP_F_ericsson_MAIN_2|WPP_F_ericsson_MAIN_2.zip|WPP_F_ericsson_MAIN_2.bit|48|2aaf16274fe8e799d72fa08a4963850d
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
                "WPP_A_ericsson_MAIN_2|WPP_D_ericsson_MAIN10_2|TILES_A_Cisco_2|TILES_B_Cisco_1|ENTP_A_QUALCOMM_1|DSLICE_A_HHI_5|RAP_B_Bossen_2|PICSIZE_C_Bossen_1|WP_B_Toshiba_3|LTRPSPS_A_Qualcomm_1|POC_A_Bossen_3|NoOutPrior_A_Qualcomm_1"));
    }

    static byte[] bitstream(Vector vector) throws IOException {
        var archive = Conformance.vector("hevc-v1-" + vector.archive());
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
        // Luma of 10 bits with chroma of 9: the decoder scales chroma up to 10 bits.
        var chromaShift = vector.name().equals("TSUNEQBD_A_MAIN10_Technicolor_2") ? 1 : 0;
        var result = HevcTestStreams.decode(bitstream(vector), options, 0, chromaShift);
        assertEquals(vector.frames(), result.frames(), "frame count of " + vector.name());
        assertEquals(vector.md5(), result.md5(), "output MD5 of " + vector.name());
    }
}
