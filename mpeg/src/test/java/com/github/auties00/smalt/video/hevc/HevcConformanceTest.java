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
 * the MD5 or YUV files the archives carry. Each archive is pinned by its SHA-256 and cached by
 * {@link Conformance#fetch}.
 */
@DisplayName("HEVC conformance bitstreams (ITU-T H.265.1, HEVC_v1)")
class HevcConformanceTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jctvc-site/bitstream_exchange/draft_conformance/HEVC_v1/";

    /**
     * Name | archive | SHA-256 | bitstream entry | output frames | MD5 of the output.
     */
    private static final String MANIFEST = """
            AMP_A_Samsung_7|AMP_A_Samsung_7.zip|3b8c7e4f77759351893470d87cd5c8252146ae19523786510914a9e8d5c7e340|AMP_A_Samsung_7.bin|17|93a5875d58072db5539c04e1e943ed9d
            AMP_B_Samsung_7|AMP_B_Samsung_7.zip|ff24fb7455b5b789a2cb7c738aadaba1406267349ac443d3127905b025f19414|AMP_B_Samsung_7.bin|17|4e051fad4145d5127e16ac2999500069
            AMP_D_Hisilicon_3|AMP_D_Hisilicon_3.zip|0ceb48fd2593f3d298adfb845c09a6aaee377f243583ad980921f48eea97dc92|AMP_D_Hisilicon.bit|100|277006085088cf68b9cafbbba0c19443
            AMP_E_Hisilicon_3|AMP_E_Hisilicon_3.zip|49a84077b46689de74557ba5393e8364306675226390568aa5bf0273f847d733|AMP_E_Hisilicon.bit|100|1a301e997917128420e3c33e6c14693a
            AMP_F_Hisilicon_3|AMP_F_Hisilicon_3.zip|2f69f7d7befbc5f7483b18d2640f0aa84304a5778deba659ebf3a40d64162aee|AMP_F_Hisilicon_3.bit|100|6be6b5fb49a9514914f4ea812f737076
            AMVP_A_MTK_4|AMVP_A_MTK_4.zip|b89aa3d113bdff9aff61d4c190b9035155db44a9c9b08b5a40df89fde2e6a2c7|AMVP_A_MTK_4/AMVP_A_MTK_4.bit|30|5dd1b1099391ad659225fbf94965a6c7
            AMVP_B_MTK_4|AMVP_B_MTK_4.zip|7d062666643f319d419a6d133656eba7a774a667f1e1245dea764e030cdfe04a|AMVP_B_MTK_4/AMVP_B_MTK_4.bit|41|7fecb4fe8ee4d4838f4a9873b062b819
            AMVP_C_Samsung_7|AMVP_C_Samsung_7.zip|42d4f2f1bf13c3d6fa129608c8cbcac4c41f482ac55a337f0dfd8e6dc4818c9b|AMVP_C_Samsung_7.bin|60|75155ad034919a2846bbdcba13b5f733
            BUMPING_A_ericsson_1|BUMPING_A_ericsson_1.zip|3fef73e8e6263fd0f9f1bc6095e2a2cfba3451132e24f7ad08fa7c6510fafe5c|BUMPING_A_ericsson_1.bit|49|64f213eb1cf45614f0eeb50fd1b725bf
            CAINIT_A_SHARP_4|CAINIT_A_SHARP_4.zip|58d66213064bac9444cf13cc2f62d6f044bdb4d8ec9488640fb86090de976b43|CAINIT_A_SHARP_4.bit|50|550c5a918fecda6a284aa5f44c0e2f33
            CAINIT_B_SHARP_4|CAINIT_B_SHARP_4.zip|5cd1c28961a30bdcae7c3b06b510d2673700474a7bfbaae125a4967bfcfef82d|CAINIT_B_SHARP_4.bit|50|0c633f8ee5a4c5779cff48ca86273685
            CAINIT_C_SHARP_3|CAINIT_C_SHARP_3.zip|ca0306d6363ea8acc5eeb39cba8c498ed2331fe3abeba741b1a929323329fa10|CAINIT_C_SHARP_3.bit|50|9c6c5363724a8bad54e2869b655fbf3b
            CAINIT_D_SHARP_3|CAINIT_D_SHARP_3.zip|c9e125d79cc775207ea32d1c49eaf0485cfe56066812e1e7c3ad7c57ddef851e|CAINIT_D_SHARP_3.bit|50|0231e327044e45971fc30a43a1bd8275
            CAINIT_E_SHARP_3|CAINIT_E_SHARP_3.zip|34e460bd10cfef2b7c1d42952f32f45dd117fbe562728efba0d2b64372cf85a6|CAINIT_E_SHARP_3.bit|50|55af76d5527af999d12ccc37b1f6b7ae
            CAINIT_F_SHARP_3|CAINIT_F_SHARP_3.zip|93e0a9daa7390cf4222ef5100e0998d6ec4558224839e19a19d5b8ff9075c833|CAINIT_F_SHARP_3.bit|50|88d8cd2d303b662f0a92a25256957b69
            CAINIT_G_SHARP_3|CAINIT_G_SHARP_3.zip|2ab24677a332124077e365068a76b029113dce438456e9fe9cdfc6d2381ae36d|CAINIT_G_SHARP_3.bit|50|a7036eac7798f6e8691f0975786d6811
            CAINIT_H_SHARP_3|CAINIT_H_SHARP_3.zip|e667eb78ba7cf0a741f73f3d1dbb496c748c6d47ff3eb7990319c75be25e9d87|CAINIT_H_SHARP_3.bit|50|cfdd3ce0f4cf1eafd012dcb266311d06
            CIP_A_Panasonic_3|CIP_A_Panasonic_3.zip|5c55b3594e5c951c9f5312913961750283eb2dadd15844fe35f9beb94a9a548c|CIP_A_Panasonic_3/CIP_A_Panasonic_3.bit|2|4cbf601ba98d63f642defab5eaa12c8d
            cip_B_NEC_3|cip_B_NEC_3.zip|47dfb7ef2b81bb10a0bb5330138211770d179e1ad58af984294cef2518d85187|cip_B_NEC_3/cip_B_NEC_3.bit|5|1315714c7b3f3237b469259f66d9b7aa
            CIP_C_Panasonic_2|CIP_C_Panasonic_2.zip|daae3f29cc687ee8181d03b89282dbe539c1f033c2298e2f2cabfeda7d3b9b33|CIP_C_Panasonic_2/CIP_C_Panasonic_2.bit|2|ed985932fef2aa8a74ded970070bdab2
            CONFWIN_A_Sony_1|CONFWIN_A_Sony_1.zip|c3c823ffb9e526f5d9506ba2f1239b1f0323572850789230d762f4e4678fc298|CONFWIN_A_Sony_1/CONFWIN_A_Sony_1.bit|60|a3ce3f936ff69ff1ec2621a622dd37ac
            DBLK_A_MAIN10_VIXS_4|DBLK_A_MAIN10_VIXS_4.zip|d86ec87f98fccbcec3758954a7cf430e8af8cf021d347dfbbed2f1d5dbb00f25|DBLK_A_MAIN10_VIXS_4/DBLK_A_MAIN10_VIXS_4.bit|8|c4594956bb9e8303f1662f9eb1bcdf50
            DBLK_A_SONY_3|DBLK_A_SONY_3.zip|ce18cd42375359b9ff293d12a95793cc6b56eb75d899ad012c68e25dfa54dfbf|DBLK_A_SONY_3.bit|30|42486a48ea12d5ab6cd98ed2e2807cfe
            DBLK_B_SONY_3|DBLK_B_SONY_3.zip|cfdfab2f022aa13d218ba96c1315626c27b68e4f38107f05cbe158f39ab4e409|DBLK_B_SONY_3.bit|30|cf5de25a94a33e2d4237b8d1c773db06
            DBLK_C_SONY_3|DBLK_C_SONY_3.zip|d7dd1e3707cd3b3d9c69dea1a1307c8c43e8e2794f47bc4843f0039c839bab6c|DBLK_C_SONY_3.bit|30|41f89edc63b1175ffc1fae909f4fed82
            DBLK_D_VIXS_2|DBLK_D_VIXS_2.zip|1b9c3dec3685760cac19fd8667510b34fdeaaccfb5b137803ea859233a2bdd7e|DBLK_D_VIXS_2/DBLK_D_VIXS_2.bit|8|f6f3075edca573f5dfe4e352d26170f1
            DBLK_E_VIXS_2|DBLK_E_VIXS_2.zip|5415d67db3d190bf363825ef2f259bbbc635551f94ced6f8cf965ff8d72b285b|DBLK_E_VIXS_2/DBLK_E_VIXS_2.bit|8|1f94d56d9e462967cad73284ed0f43f9
            DBLK_F_VIXS_2|DBLK_F_VIXS_2.zip|8f62f346aaf0716162c5ca4c85b3e213a0104d666680f620f81d92be676ca5aa|DBLK_F_VIXS_2/DBLK_F_VIXS_2.bit|8|c94d0c0d80c93a2edd372a1fd12f93a1
            DBLK_G_VIXS_2|DBLK_G_VIXS_2.zip|f87570664f9e0b88a843ea545dd8909584dd1bb31ce121e00aba9ee609a12675|DBLK_G_VIXS_2/DBLK_G_VIXS_2.bit|8|1f94d56d9e462967cad73284ed0f43f9
            DELTAQP_A_BRCM_4|DELTAQP_A_BRCM_4.zip|63abbbaf43d334d7952eab7bbc5b3e34b4ad0259eddcd63cdb164dbcf9a36ca6|DELTAQP_A_BRCM_4/DELTAQP_A_BRCM_4.bit|96|1ceef83183197fd1f21472c17b53cbff
            DELTAQP_B_SONY_3|DELTAQP_B_SONY_3.zip|67ac96be5c1917b93e3736b790af62405b738357d8b307947dfe9a97263f3574|DELTAQP_B_SONY_3.bit|30|f02fa96633c63bd5e1ce0f103447d1ed
            DELTAQP_C_SONY_3|DELTAQP_C_SONY_3.zip|826387887ef919bb05934379ab057b859c746a62f692e575b5be931cfbf37ae7|DELTAQP_C_SONY_3.bit|30|1c2be685b1ae953787e0e93c656ee502
            DSLICE_A_HHI_5|DSLICE_A_HHI_5.zip|c41c1a1e1e700af201eaf4cb0d11d4361b41ba0828e80c78d1835d1de187f80c|DSLICE_A_HHI_5/DSLICE_A_HHI_5.bin|50|c7caf3164b0a316549ac7244f66f1294
            DSLICE_B_HHI_5|DSLICE_B_HHI_5.zip|993b933ac6801899b6f04ae5dd2c20fbc2498fd9710a924611e66de2f690716b|DSLICE_B_HHI_5/DSLICE_B_HHI_5.bin|50|2e072130f116a4d0f7fc69fa76cfcdfb
            DSLICE_C_HHI_5|DSLICE_C_HHI_5.zip|6836566f0a4465775b4757b84d7a89f453b635eba2b941ae07132f7c7268c116|DSLICE_C_HHI_5/DSLICE_C_HHI_5.bin|50|505a47c5ce4bc66ef5661aeb778b0f1f
            ENTP_A_QUALCOMM_1|ENTP_A_QUALCOMM_1.zip|66abdf60455ece468acf160274316c51dca3858d93a8ffce93646cbb5568fe43|ENTP_A_Qualcomm_1.bit|24|25f3c7facf9e7b75e81247206b4fc60d
            ENTP_B_Qualcomm_1|ENTP_B_Qualcomm_1.zip|6d5a455d70e245c463b66945ce34cbeff26dc826665334def407a29b2c0db722|ENTP_B_Qualcomm_1.bit|24|6577f102b93577eb899e0bff1a20a401
            ENTP_C_Qualcomm_1|ENTP_C_Qualcomm_1.zip|6cfdfe2f6ba8e1a860909550e5a486e7bf712fed00674869f45c1d82d05833f5|ENTP_C_Qualcomm_1.bit|24|0b607e6e7946e6e8d0f92a1a294d135d
            EXT_A_ericsson_4|EXT_A_ericsson_4.zip|a70af63f710753f4157bc5de33e29c1a6622e823f50549c83a62a605fb2d7f66|EXT_A_ericsson_4.bit|3|5797b3114fe12daf1ce522ed70ce4078
            FILLER_A_Sony_1|FILLER_A_Sony_1.zip|abf6cd7525378d2e1c8f32b67af01c594553c342c21dcd36ab677c3c77b528db|FILLER_A_Sony_1/FILLER_A_Sony_1.bit|60|f92a928770a41b63e7cfc89ea198790f
            HRD_A_Fujitsu_3|HRD_A_Fujitsu_3.zip|7241c304d8326ae182ae245b83eb4f95e299d0f4fe254e0e8844c56ee5d37aee|HRD_A_Fujitsu_3.bin|96|f6d04dba2ef09bcadbea7b8ab5c8c917
            INITQP_A_Sony_1|INITQP_A_Sony_1.zip|38b1d9396bab068fa701b08db73c3a900e893ef4cd00c82fc7b4d2b150a2f749|INITQP_A_Sony_1/INITQP_A_Sony_1.bit|60|2716af552e71ef61221b86130ad3d444
            INITQP_B_Main10_Sony_1|INITQP_B_Main10_Sony_1.zip|c3f87037640953691dee02d4c9f28cd6a3b99de43e30bdcfdf583888191fa23e|INITQP_B_Main10_Sony_1/INITQP_B_Main10_Sony_1.bit|90|f63c71e5f6b9c6ddf2b080524f9f14b1
            ipcm_A_NEC_3|ipcm_A_NEC_3.zip|89a71f4b9b7e22c481378f83bacc5aac6c8f204999691d3fa21f2804337e84c9|ipcm_A_NEC_3/ipcm_A_NEC_3.bit|1|8049988c383486e076ea2494edda3831
            ipcm_B_NEC_3|ipcm_B_NEC_3.zip|36486e9b9cf21ccdd2f1c49cfd6e2806915b732b84e94940553ef501fd197ce6|ipcm_B_NEC_3/ipcm_B_NEC_3.bit|1|23a3b7024fd9bc64b946b9961ab0f51e
            ipcm_C_NEC_3|ipcm_C_NEC_3.zip|12da972f8fcf2d75825535022b7a437d95b1c8272af0b738dc651f34e94c2175|ipcm_C_NEC_3/ipcm_C_NEC_3.bit|1|c3e74c399b73a5ab2dbd20523f583464
            ipcm_D_NEC_3|ipcm_D_NEC_3.zip|4ef6cdc8fd330d339e1e9e9e04487ba84a21e40a02d111b2ee10c28549bddc94|ipcm_D_NEC_3/ipcm_D_NEC_3.bit|1|aa64a16240064bc2a90fadf979a62a7b
            ipcm_E_NEC_2|ipcm_E_NEC_2.zip|1d6f8f5d1f08c9bc3f12179bcd6b28a56a7e18500f2c1dfd9799d8b407e78607|ipcm_E_NEC_2/ipcm_E_NEC_2.bit|1|e1cd7a16d3f6a342855044ccba3e41f5
            IPRED_A_docomo_2|IPRED_A_docomo_2.zip|5ee6e90cbcbf0547e43e72536ab39af1d831363f95c368c175267d6e48d264da|IPRED_A_docomo_2.bit|20|0e4e9bd00b74e30e400de46ee7ad8023
            IPRED_B_Nokia_3|IPRED_B_Nokia_3.zip|738e35af4ced173e8ccb558464dc6a1b8b791fb1bb957c073633aa6037288cd0|IPRED_B_Nokia_3.bit|1|ec3bcad4f9174404e52d132206b6617b
            IPRED_C_Mitsubishi_3|IPRED_C_Mitsubishi_3.zip|0121352acf8ee0a90c1a61de2c621368b6199e1344d13c5a8cca7d219c8a44ac|IPRED_C_Mitsubishi_3/IPRED_C_Mitsubishi_3.bit|4|6043c04fecc60ee88f4247aaca96441f
            LS_A_Orange_2|LS_A_Orange_2.zip|4fe01633a4748338939713ea33d8cf82d64a1601255e71aef36f8d676e835d50|LS_A_Orange_2/LS_A_Orange_2.bit|9|e14f82958f87a87be73a6f51e76ffdab
            LS_B_Orange_4|LS_B_Orange_4.zip|8f6b9111ca7a1f968ff705c09157974f5f347aaeb6591925c07c29493d65cbfe|LS_B_Orange_4/LS_B_Orange_4.bit|25|05ac9b36977c98001cc6c5a45e363662
            LTRPSPS_A_Qualcomm_1|LTRPSPS_A_Qualcomm_1.zip|ddab4ef4b919ada1185a1abe0ac843077073bcdd7dc5b9380f8a8f50aebde2b0|LTRPSPS_A_Qualcomm_1.bit|500|e925d467c20180090c09c02d02c3aa25
            MAXBINS_A_TI_5|MAXBINS_A_TI_5.zip|a6d1ed217b232aec8faff836c180a3368e5dd8ed3f8bf912c3527297ee89d498|MAXBINS_A_TI_5/MAXBINS_A_TI_5.bit|2|1386fb149ddc11bd5a1d9111879acfa4
            MAXBINS_B_TI_5|MAXBINS_B_TI_5.zip|9963fb4ff680764c828b6255286a61e8af7578ca9e894e998799d34c6262643a|MAXBINS_B_TI_5/MAXBINS_B_TI_5.bit|3|21d6910760ea4963eab28b26709471fe
            MAXBINS_C_TI_5|MAXBINS_C_TI_5.zip|d65eff1cf251777315fd75045f561f1e5a10dae6c24d3eedcd434dc22d2f9f44|MAXBINS_C_TI_5/MAXBINS_C_TI_5.bit|3|7a7e51fdd40c57d70af418f5082416fa
            MERGE_A_TI_3|MERGE_A_TI_3.zip|b38d58301c0a8bd41ce982e40552850eabe9f140d2ff6ceca3375f9f5abc8a65|MERGE_A_TI_3/MERGE_A_TI_3.bit|8|3a15886a0158ef1aaf557869b2e9c522
            MERGE_B_TI_3|MERGE_B_TI_3.zip|3caadff6efe206a4916fc77b00dee960a4abf7c0c318bc74567e978990ad6f4a|MERGE_B_TI_3/MERGE_B_TI_3.bit|8|83b4dc9f229a8fba3777d9f4f87ce144
            MERGE_C_TI_3|MERGE_C_TI_3.zip|8eb22636f0df729f172e86bd76c9dacb1baf5e1732ec82b70163479150c4eaf8|MERGE_C_TI_3/MERGE_C_TI_3.bit|8|216f0cc869e3989c8fb2cf261243160b
            MERGE_D_TI_3|MERGE_D_TI_3.zip|210a72652b522927306daf64c536b503bce3e96136a9174f737edbb0796e461e|MERGE_D_TI_3/MERGE_D_TI_3.bit|8|104cccf2b8441862624777955d6ecdc4
            MERGE_E_TI_3|MERGE_E_TI_3.zip|e993282028a10dd1acb2acf2ef288cf722a45afdf6f38be2e14aa4ac4e2c9313|MERGE_E_TI_3/MERGE_E_TI_3.bit|8|6e74dbd450123246c22bf01b389bbe11
            MERGE_F_MTK_4|MERGE_F_MTK_4.zip|e6362acba9b420abbeca1e6dfceb7097d0ef67c52eacaf620b6837eb70de39ad|MERGE_F_MTK_4/MERGE_F_MTK_4.bit|41|a8f0982f026dfda1c02b4d4d3eb88132
            MERGE_G_HHI_4|MERGE_G_HHI_4.zip|a373946851412a3aab96bfc8d7e9fdb28f1bd3f0cfdd7296aaf2caf1aa9d6367|MERGE_G_HHI_4/MERGE_G_HHI_4.bit|2|c82f4c88d9b2c73fabbeac8944da35a1
            MVCLIP_A_qualcomm_3|MVCLIP_A_qualcomm_3.zip|59362bb9db5ead0245ec7ff1e40d2ddd80eb81d03bb5cceb6d4a971210888221|MVCLIP_A_qualcomm_3/MVCLIP_A_qualcomm_3.bit|5|78b398c201089b6b17bcbda2bbccf3ae
            MVDL1ZERO_A_docomo_4|MVDL1ZERO_A_docomo_4.zip|2b65608d995b143c71ff22da120b5dab5863c75d324906c6f4fc64c1340669cd|MVDL1ZERO_A_docomo_4.bit|500|593d1936f6fc2e1775ea2a691a601c34
            MVEDGE_A_qualcomm_3|MVEDGE_A_qualcomm_3.zip|077887906b30e89aec88d09ff99e549fa1c99067a4f66a264a0ef03fd5f57f5e|MVEDGE_A_qualcomm_3/MVEDGE_A_qualcomm_3.bit|17|101c5bfe16ff1739909161794af29a2d
            NoOutPrior_A_Qualcomm_1|NoOutPrior_A_Qualcomm_1.zip|f1d17de98fc0d69bc28793f74d5b31c5c113ba2c72983bfad03413459b95a07f|NoOutPrior_A_Qualcomm_1.bit|40|1c5f20a08ab8dfa0f44d9a0ca23d174d
            NoOutPrior_B_Qualcomm_1|NoOutPrior_B_Qualcomm_1.zip|c1fc9cc8681f762a121db670dde6f75bf158139ed80c6d7895c38ba9a0ffbbd0|NoOutPrior_B_Qualcomm_1.bit|47|048bca0ec216a3f88cf9fbb4bb5a61ac
            NUT_A_ericsson_5|NUT_A_ericsson_5.zip|caabf3005c37b5ad7497b110f30f04ffb603fc154250058edc23da95b7c73d88|NUT_A_ericsson_5.bit|34|9dbe33badd0cc6dfcd1b53e546968fbd
            OPFLAG_A_Qualcomm_1|OPFLAG_A_Qualcomm_1.zip|c48c1f9d0553a26d06d9c3090cc61e7e0f14c48c0d718d3b30eaab8872f6b8af|OPFLAG_A_Qualcomm_1.bit|500|35eeeca175938ee262f72b7485146f94
            OPFLAG_B_Qualcomm_1|OPFLAG_B_Qualcomm_1.zip|7b20964afbe404b42d142fa9f3d3673c4a795a6424fc621a2eb3f4e67dd3517a|OPFLAG_B_Qualcomm_1.bit|98|516811eeff3ae5ee056dda290dcf2735
            OPFLAG_C_Qualcomm_1|OPFLAG_C_Qualcomm_1.zip|032c38a4fd9f35838040e00cc60ef7786937362edce86c174a621e2d3d72d563|OPFLAG_C_Qualcomm_1.bit|96|a82c4f6ee843150067bba60b9342fc50
            PICSIZE_A_Bossen_1|PICSIZE_A_Bossen_1.zip|ffdffecff6ba03ce0220eb89e83bc3043af5953ab6864ad6acefee382e49ee7c|PICSIZE_A_Bossen_1/PICSIZE_A_Bossen_1.bin|10|910ff0e6b3739af0b4aba4f6aa6704f8
            PICSIZE_B_Bossen_1|PICSIZE_B_Bossen_1.zip|aa3d40fa418873793110bbff17890781586b391d69cd91a3973f0dbfd9144b3f|PICSIZE_B_Bossen_1/PICSIZE_B_Bossen_1.bin|10|e5c4c20a8871aa446a344efb1755bcf9
            PICSIZE_C_Bossen_1|PICSIZE_C_Bossen_1.zip|d9a3e925af1c1eabc634bdb04a8cc0a48076956b92e1c22e65a89e154309d2ee|PICSIZE_C_Bossen_1/PICSIZE_C_Bossen_1.bin|10|674f9f11eb956257d86782949ec00399
            PICSIZE_D_Bossen_1|PICSIZE_D_Bossen_1.zip|bb63815e8817b5a58aefaf235a7b144b3664e4cc3b4f7b7807a9371222356f67|PICSIZE_D_Bossen_1/PICSIZE_D_Bossen_1.bin|10|5b76e0591618f5affe8b15b03d9c0bef
            PMERGE_A_TI_3|PMERGE_A_TI_3.zip|7d3c8f4c07c56b2f3ac943f8d93b4f68f6ef8f6f4b5ce9df6967f1162c596dfe|PMERGE_A_TI_3/PMERGE_A_TI_3.bit|8|6e74dbd450123246c22bf01b389bbe11
            PMERGE_B_TI_3|PMERGE_B_TI_3.zip|9e50bf7b5b04a143dfecb216010c5f7f5d1af62c787d0f93715ec69deff3f1c3|PMERGE_B_TI_3/PMERGE_B_TI_3.bit|8|7d0e57ebdbbc6633c972083a17d97550
            PMERGE_C_TI_3|PMERGE_C_TI_3.zip|6daafac8aaca517e83bc7c1443adbc0360f3397a31bef373e1f3fb2617e7525e|PMERGE_C_TI_3/PMERGE_C_TI_3.bit|8|7e23d874b198ec7a9f6086a20de0db76
            PMERGE_D_TI_3|PMERGE_D_TI_3.zip|dc9b99198683ded041acdde73e77a259a24b262aac8aabdb048d84b6db116fc8|PMERGE_D_TI_3/PMERGE_D_TI_3.bit|8|9771b8c8a71e41547f14032e07be7d61
            PMERGE_E_TI_3|PMERGE_E_TI_3.zip|28e5e7a0eb72c1f51dabb9fde6f33c683cb197392c879aeafeeebfca689292a3|PMERGE_E_TI_3/PMERGE_E_TI_3.bit|8|26aed56afdb6cb7011fd5c3303379a9d
            POC_A_Bossen_3|POC_A_Bossen_3.zip|aec93a088b81d68d87eda99e4a0e64bed7012b571158c8a2242223d1f9db8ccd|POC_A_Bossen_3/POC_A_Bossen_3.bin|5|faf33bec64d3e58c426ed2772903a0bf
            PPS_A_qualcomm_7|PPS_A_qualcomm_7.zip|39058b857df8d60c92135e1ae32a5e09b1ee76be6ed06e19014d2dc18c6df778|PPS_A_qualcomm_7/PPS_A_qualcomm_7.bit|81|bccf29fab6744215fe007ea3a5d6da42
            PS_B_VIDYO_3|PS_B_VIDYO_3.zip|df6adc3d47b8c2b80404f5b892b2f24071731be97a1ea4604205f6a914cd9ba7|PS_B_VIDYO_3/PS_B_VIDYO_3.bit|25|eb85521ee103333eac61a8253637eac4
            RAP_A_docomo_6|RAP_A_docomo_6.zip|5031bac0b1520ed826bf3112d000e59c76677c01dee706738af0d206cde031be|RAP_A_docomo_6.bit|86|8a536a80ed42b37b1ac5810bc046f82a
            RAP_B_Bossen_2|RAP_B_Bossen_2.zip|162e16fa34535085f58a3128782a820ae41430281508ab0a7ef036a0d7cf4707|RAP_B_Bossen_2.bit|80|f38befcc280f2fd0d23f48f807f843c2
            RPLM_A_qualcomm_4|RPLM_A_qualcomm_4.zip|d085cf26bc0e2f903071cdf2c2aae04c1ea6dc19628004c402ed19a1a32beb58|RPLM_A_qualcomm_4/RPLM_A_qualcomm_4.bit|300|011794872709c341880ee42ade036ed3
            RPLM_B_qualcomm_4|RPLM_B_qualcomm_4.zip|00cc75255e2ec35010cb635daac2bfa0217feb9b14f353d01645a2528a2f57cf|RPLM_B_qualcomm_4/RPLM_B_qualcomm_4.bit|300|653ac7c46fa7b9d7d966e3db317eb938
            RPS_A_docomo_5|RPS_A_docomo_5.zip|45427f66400a033e7a47cc6ca9b21d456e9e06450db6ca8d8fa92c438125324b|RPS_A_docomo_5.bit|44|7f4ad6c6b3de54558b0db59629b87db9
            RPS_B_qualcomm_5|RPS_B_qualcomm_5.zip|1925e1a9867b5380395af9b2b52a4b1f2c0ccfa5c62abb330c481fa26aca5307|RPS_B_qualcomm_5/RPS_B_qualcomm_5.bit|300|6d1ed392b067050ebd3a24a37281da03
            RPS_C_ericsson_5|RPS_C_ericsson_5.zip|72eaf0e85d1f5809a1b1ebe2051a171c4361ae39fa206e198b69eb9972795459|RPS_C_ericsson_5.bit|40|0306b9e3ec365115ccb7060c0a437b32
            RPS_D_ericsson_6|RPS_D_ericsson_6.zip|ad3a34befc07b88538442859c2c7a4faa96ad8e48875f0189b93875bb7fef302|RPS_D_ericsson_6.bit|68|d0fcd71e6266b1ae1f66b9cb1802ea03
            RPS_E_qualcomm_5|RPS_E_qualcomm_5.zip|74f59448bb458875bc8081bd724de999902e7422fd77b74a7ca1c48381d9e96a|RPS_E_qualcomm_5/RPS_E_qualcomm_5.bit|300|c30d38bbd7ea483dc7ffe81325118e16
            RPS_F_docomo_2|RPS_F_docomo_2.zip|db62853318f3a7bc5af8b3fb193fbc1f7e2384353dfe184c75dd678e7543b2f1|RPS_F_docomo_2.bit|50|fd6e6d973d39622d197dbdf4543643f2
            RQT_A_HHI_4|RQT_A_HHI_4.zip|958d958baedbe4f2c864f5d9b3b87e69884170db403b4e190d1d33a0490ec3c6|RQT_A_HHI_4/RQT_A_HHI_4.bit|2|aa7f6ef07bf76ebddd52f7e528cd6ab8
            RQT_B_HHI_4|RQT_B_HHI_4.zip|901a5697989fd5dca6e6f780e33008e17a1fe501752daa15643a6ed8f2a51f13|RQT_B_HHI_4/RQT_B_HHI_4.bit|2|a63f4f4e966e77a5eb4e65dcdcc488a4
            RQT_C_HHI_4|RQT_C_HHI_4.zip|64c05810393d2eea2d3aa3da2e88eb8a7a15d18dc3773649609dd3633daf5c66|RQT_C_HHI_4/RQT_C_HHI_4.bit|2|c82f4c88d9b2c73fabbeac8944da35a1
            RQT_D_HHI_4|RQT_D_HHI_4.zip|b388926a52db194ffd5850f19881daa4d9cdac05defedf22d0effd23ddedd4ea|RQT_D_HHI_4/RQT_D_HHI_4.bit|2|33b58b954fa82753526fda9e9e6bc8cb
            RQT_E_HHI_4|RQT_E_HHI_4.zip|025675b85ef5e10fa8ca7f995ed8cecf93ef9cc35e348d5d1b47a4d389b5d866|RQT_E_HHI_4/RQT_E_HHI_4.bit|2|65f22431d0eb149e04c327f881ce6581
            RQT_F_HHI_4|RQT_F_HHI_4.zip|1c83c4de3818e827e106083c381aa4f8ed179a086f928e3f139605be74df9e03|RQT_F_HHI_4/RQT_F_HHI_4.bit|2|56ba354d919d06a33b44862dd18e9437
            RQT_G_HHI_4|RQT_G_HHI_4.zip|43f05990af976fbd49ee454bc6fcc8c872cff1d9e424bef16a775b98e4133912|RQT_G_HHI_4/RQT_G_HHI_4.bit|2|2fb67256014a6840b69ca6dad2426304
            SAO_A_MediaTek_4|SAO_A_MediaTek_4.zip|e0b5c8f4ab4fb592971f06469db630d9e7ef72b4d37d729604e2fdaa9df706c9|SAO_A_MediaTek_4.bit|60|272e694a2262a2b34f6248f787a4431d
            SAO_B_MediaTek_5|SAO_B_MediaTek_5.zip|f8d4c91e443b02d13ee180c5ca02769048766a338993bb90d97fbe683cbb979f|SAO_B_MediaTek_5.bit|60|90cc57237e3ce47eb3b4c901e200112d
            SAO_C_Samsung_5|SAO_C_Samsung_5.zip|6152998e7dfa1aa0933437201b5abbc319d949cf13411d60ae324f2f407ef243|SAO_C_Samsung_5/SAO_C_Samsung_5.bin|5|962290491f2f1a1e70ab7aa63d2264bc
            SAO_D_Samsung_5|SAO_D_Samsung_5.zip|9dec8b2172d0b07babdfd866abd783c344b328ebf83af826cf76da1ae2e9cd77|SAO_D_Samsung_5/SAO_D_Samsung_5.bin|5|6db9575a7737816b1d7a3a3bf3ba126c
            SAO_E_Canon_4|SAO_E_Canon_4.zip|a6c7a169386ad20bfc4b249d152c5426e5cd17b906e213993f86c4c2fc55b47e|SAO_E_Canon_4.bit|16|0b04a1378274a6ffbb8f6121e027290c
            SAO_F_Canon_3|SAO_F_Canon_3.zip|9bc5e53214261ea3cefc035327b6c110096d8d933f0ed82fff6fcf8270de5e7c|SAO_F_Canon_3.bit|16|5c295ecba132d1e68a12dd8209e86a02
            SAO_G_Canon_3|SAO_G_Canon_3.zip|7a7566dcf043a9adf6007b1d15182d82d998566e243c8f64bec76917653923e2|SAO_G_Canon_3.bit|16|1f743a3e28617b8bae3e348096edcf76
            SAO_H_Parabola_1|SAO_H_Parabola_1.zip|c823ce5b11e759970b0e5996f05f8d2e8bd0fa65b461f40dd74df1d9a74ac7c6|SAO_H_Parabola_1.bit|4|7e23c16898b14831b25d7857b69a9a9a
            SAODBLK_A_MainConcept_4|SAODBLK_A_MainConcept_4.zip|2ca335887ff3262f4a1a2653b5feaa34d2fd313e0f268966ce316549e654e1e0|SAODBLK_A_MainConcept_4/SAODBLK_A_MainConcept_4.bin|500|b9c98937bcb6be445348fdc89c208f1e
            SAODBLK_B_MainConcept_4|SAODBLK_B_MainConcept_4.zip|575165fe6d0a474abdbfb2a628e8c1c2399827fca9f9f47c11aaf71976b51441|SAODBLK_B_MainConcept_4/SAODBLK_B_MainConcept_4.bin|500|031043495e9b9f4ce120afa316aa5705
            SDH_A_Orange_4|SDH_A_Orange_4.zip|ddf41877f10b47bad946712954e4a4b35cae435bac187d8c404c6bcfcb4acad0|SDH_A_Orange_4/SDH_A_Orange_4.bit|2|0a1a6a76a86dbdc7ca479e4235f5b822
            SLICES_A_Rovi_3|SLICES_A_Rovi_3.zip|d0141e111c1c854930eb16430cc5d3e5b82ccfa1458805fce20dd410b08c61ad|SLICES_A_Rovi_3.bin|9|c2d63a4d145a5713afecd822032ec271
            SLIST_A_Sony_5|SLIST_A_Sony_5.zip|c51997394e58293b6bedbc5493056fee1b0e7fd41e266468d6a4664b1699b568|SLIST_A_Sony_5/SLIST_A_Sony_5.bin|65|b00bc5362e6fad1a0e704aad8e69b863
            SLIST_B_Sony_9|SLIST_B_Sony_9.zip|7d449a938659d1588662f8dfd88aa060ca34457209282b72a93d3f63719cc404|SLIST_B_Sony_9/SLIST_B_Sony_9.bin|65|312b4ebb3e885587d5e12b78aa95977a
            SLIST_C_Sony_4|SLIST_C_Sony_4.zip|0af7bda8eae57ab56e96e6d9796c5dbe449036229fd177657147eb5ee6be1369|SLIST_C_Sony_4/SLIST_C_Sony_4.bin|65|61024c25cbd60e9bf86dbe3bc5b9b48b
            SLIST_D_Sony_9|SLIST_D_Sony_9.zip|81c28ef25fd48762dc92f510a216404bd2275971b66377f1f04b4e5b5cc007a4|SLIST_D_Sony_9/str.bin|65|8d38ce43b17627ff1f86e526bfb7ffc4
            SLPPLP_A_VIDYO_2|SLPPLP_A_VIDYO_2.zip|05e36c3313678556e498cfeb3c4f3f73786f9608b8cd3c8b79ddf65c1ee42b4f|SLPPLP_A_VIDYO_2/SLPPLP_A_VIDYO_2.bit|33|99e13916ede2bc9b77718787579104a9
            STRUCT_A_Samsung_7|STRUCT_A_Samsung_7.zip|56a83b66b9e20dbfb20b196797c8136a9c19d3df39d4c1142e0b96e0159b617b|STRUCT_A_Samsung_7.bin|60|02f02cc9a67222004ae18ee6bebd475a
            STRUCT_B_Samsung_7|STRUCT_B_Samsung_7.zip|d2ddaebfefdd2e6189030f75d88b351c027aaabed3cd71ce6752b44f120f2078|STRUCT_B_Samsung_7.bin|60|1f472e014aff120681e8482c8a04ef93
            TILES_A_Cisco_2|TILES_A_Cisco_2.zip|bd63079966105a3bdac764979cb82b708f586cc7c34859a1d466e18cc6a5c345|TILES_A_Cisco_2/TILES_A_Cisco_2.bin|100|946f0771fa87d49f684f96871c9c3c88
            TILES_B_Cisco_1|TILES_B_Cisco_1.zip|75b3c9b2981cda59d497750322cde7ca23d6cef39fa9a8e11f36926758b7f83b|TILES_B_Cisco_1/TILES_B_Cisco_1.bin|100|3382291f2b19ee2d760647d4cc756e95
            TMVP_A_MS_3|TMVP_A_MS_3.zip|78fa4422f42adda3f8d7ef1f9b3b92d9428af3140bbfb52bafd9bf3dc129c4c2|TMVP_A_MS_3.bit|17|168f8096e56b8a8c1aa03fa5fffdfd23
            TSCL_A_VIDYO_5|TSCL_A_VIDYO_5.zip|987ff999aac9b3c9828763b9aeb67a393ef32dede683356f2702f2686c905313|TSCL_A_VIDYO_5/TSCL_A_VIDYO_5.bit|73|27cbe23eabe1b174d745c286cc2ed8ff
            TSCL_B_VIDYO_4|TSCL_B_VIDYO_4.zip|2a2bcebdc2ca70ad1124d9f95c95f3ea89cab05df194b88296e65b31131e16bf|TSCL_B_VIDYO_4/TSCL_B_VIDYO_4.bit|73|95e7910b85c08a01e2e9ce72c92d7d62
            TSKIP_A_MS_3|TSKIP_A_MS_3.zip|7f09bd323228975260b14c1b8e92cb15341771b29addd71cc0c2471d5e070a80|TSKIP_A_MS_3.bit|17|4280cb848219176f4fcad51f3290210d
            TSUNEQBD_A_MAIN10_Technicolor_2|TSUNEQBD_A_MAIN10_Technicolor_2.zip|615696a911a824988da0d883ab573fb673fd72bf45275d75549abc763602f814|TSUNEQBD_A_MAIN10_Technicolor_2.bit|8|1bed23df7253170eeb8efc3fd1b740a3
            TUSIZE_A_Samsung_1|TUSIZE_A_Samsung_1.zip|f4d82ace7374c115c17ae8759994393a9353cd4902520bea20f6d3f864c60b2c|TUSIZE_A_Samsung_1.bin|5|5e6c198c852e92cdcca0e137fc2610c4
            VPSID_A_VIDYO_2|VPSID_A_VIDYO_2.zip|c7fe4e7c81b22146930b3840cf11eef02d8da4328ea2428d9107d8dd53dd75aa|VPSID_A_VIDYO_2/VPSID_A_VIDYO_2.bit|33|99e13916ede2bc9b77718787579104a9
            VPSSPSPPS_A_MainConcept_1|VPSSPSPPS_A_MainConcept_1.zip|fcbe56da7f4fcf1d870736ddf4ce0a3d572f53f78d418e49b5adc376e1cb04df|VPSSPSPPS_A_MainConcept_1.bin|6|1ddf74263cb4953cfdfcf99c563d88ea
            WP_A_MAIN10_Toshiba_3|WP_A_MAIN10_Toshiba_3.zip|26b3bc442265222032c1960cc02fe2bd55121fe933e70e5039521785073d33b2|WP_A_MAIN10_Toshiba_3/WP_A_MAIN10_Toshiba_3.bit|256|c48e853010a234786dc87790d053b03a
            WP_A_Toshiba_3|WP_A_Toshiba_3.zip|1eb0245131b15b10e671eff84e9c04e1f7413136908cf2120a8eac4ae2e2a1a2|WP_A_Toshiba_3.bit|256|127b99d0c11c930b6f248750b5848a0b
            WP_B_Toshiba_3|WP_B_Toshiba_3.zip|1138929b7df554d07e61052a222a8a501611fcdea520fb89519a0356a3ff76ad|WP_B_Toshiba_3.bit|256|82d39f8cd09e532b8fa9d35894c96258
            WP_MAIN10_B_Toshiba_3|WP_MAIN10_B_Toshiba_3.zip|9e54d047e0e3ee79724d64d6fbc09c3ea25f01c45e34797d8640c64f31f97e01|WP_MAIN10_B_Toshiba_3.bit|256|ed891c60a17f5cbc64e1848f0ea9cf45
            WPP_A_ericsson_MAIN10_2|WPP_A_ericsson_MAIN10_2.zip|1827b7111a9ce76f6c04332b61cd24d9daf818fd203aa1d0b7d1087b9b83a6aa|WPP_A_ericsson_MAIN10_2.bit|48|e68b9d75ff9cf1d9486e964051351f69
            WPP_A_ericsson_MAIN_2|WPP_A_ericsson_MAIN_2.zip|81c601e9301ba5f562ebf9918e73d1c7153e0a6fe8fa4509f61035a212188216|WPP_A_ericsson_MAIN_2.bit|48|cd7e815eb47e8138fec2185d4de84304
            WPP_B_ericsson_MAIN10_2|WPP_B_ericsson_MAIN10_2.zip|ecc6e54eb2f37f2398d3eedafde156969e684ed343a73a70b143e557ad8d9f5f|WPP_B_ericsson_MAIN10_2.bit|48|d8290fc1eec3a196aa796c92e71a779b
            WPP_B_ericsson_MAIN_2|WPP_B_ericsson_MAIN_2.zip|57bfa7dc2417976825187b43829f1e107d87ebe661cfa4a02087efae3bd1148e|WPP_B_ericsson_MAIN_2.bit|48|e37c7e561a1226640a7bf98e81df78b1
            WPP_C_ericsson_MAIN10_2|WPP_C_ericsson_MAIN10_2.zip|97e267a3593dab465c137a349188b2bde024b3c6339c1e49195944a79680b2cf|WPP_C_ericsson_MAIN10_2.bit|48|aadac4d468d5950551d606c1016abc9c
            WPP_C_ericsson_MAIN_2|WPP_C_ericsson_MAIN_2.zip|4d1f3e7fc1b05e22f2c53fabc670a3db9a642bb511004c4d785b49582890bdf2|WPP_C_ericsson_MAIN_2.bit|48|e067aa3a6a12cd5743849ded793c8d3f
            WPP_D_ericsson_MAIN10_2|WPP_D_ericsson_MAIN10_2.zip|0c365aa50f6bcdabb72e62349e45a02a5cdbcb93f52d02764a1cb04890f1eb1e|WPP_D_ericsson_MAIN10_2.bit|48|dde9aa65b78a8ca5602d834f234655d4
            WPP_D_ericsson_MAIN_2|WPP_D_ericsson_MAIN_2.zip|04c166df6e6d68584910fce77055f6c16428fc5c907bb1befd10bbf40c16b939|WPP_D_ericsson_MAIN_2.bit|48|f710612103f386c415be3e6300693451
            WPP_E_ericsson_MAIN10_2|WPP_E_ericsson_MAIN10_2.zip|79df12a77f9c2369c94bf68d3c550e0965adf364ae82bd67e7b612c624020e9c|WPP_E_ericsson_MAIN10_2.bit|48|f12e746306c59544d6162de0b62c4649
            WPP_E_ericsson_MAIN_2|WPP_E_ericsson_MAIN_2.zip|10f4a50870f763d7ccbd975adce20176022a1b9712664244a94c069322534faf|WPP_E_ericsson_MAIN_2.bit|48|485798dbf95ad61232075df2f294aa3f
            WPP_F_ericsson_MAIN10_2|WPP_F_ericsson_MAIN10_2.zip|08ec305637fb1dd9731f1befd882f44e19f2bc54d1b6faca662d9c191c2c0b65|WPP_F_ericsson_MAIN10_2.bit|48|210055a33d7d912fda6a3c40764b0482
            WPP_F_ericsson_MAIN_2|WPP_F_ericsson_MAIN_2.zip|39f5ffaa4c273c800b4e05715e2d4002e31b3bbe39505ea59b08c5fdabe6af21|WPP_F_ericsson_MAIN_2.bit|48|2aaf16274fe8e799d72fa08a4963850d
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
                "WPP_A_ericsson_MAIN_2|WPP_D_ericsson_MAIN10_2|TILES_A_Cisco_2|TILES_B_Cisco_1|ENTP_A_QUALCOMM_1|DSLICE_A_HHI_5|RAP_B_Bossen_2|PICSIZE_C_Bossen_1|WP_B_Toshiba_3|LTRPSPS_A_Qualcomm_1|POC_A_Bossen_3|NoOutPrior_A_Qualcomm_1"));
    }

    static byte[] bitstream(Vector vector) throws IOException {
        var archive = Conformance.fetch("hevc-v1-" + vector.archive(), BASE + vector.archive(), vector.sha256());
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
