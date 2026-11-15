package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import com.github.auties00.smalt.video.hevc.internal.HevcVps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the JCT-VC multi-layer conformance suites (ITU-T H.265.1): the SHVC bitstreams (spatial,
 * quality, bit depth and color gamut scalability, auxiliary alpha and depth layers, POC resets, output
 * layer sets, representation formats, reference layer offsets and phases) and the MV-HEVC bitstreams
 * (two and three views, with and without inter-view prediction, texture and depth). Each SHVC output
 * layer set's output must match the reference decoder's output picture log: the layer and the MD5 of
 * every plane of every output picture, in output order (8-bit samples for 8-bit pictures, 16-bit
 * little-endian samples otherwise, a monochrome picture's chroma planes mid-gray 4:2:0 planes). The
 * archives without such logs, and the MV-HEVC ones, give the MD5 of each layer's whole output, which
 * the output of the first output layer set naming the layer must match. Every decoded picture hash SEI
 * message is verified on the way. Each archive is pinned by its SHA-256 and cached by
 * {@link Conformance#fetch}.
 */
@DisplayName("HEVC multi-layer conformance bitstreams (ITU-T H.265.1, SHVC and MV-HEVC)")
class HevcMultilayerConformanceTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jctvc-site/bitstream_exchange/draft_conformance/";

    /**
     * Name | directory | archive | SHA-256 | bitstream entry.
     */
    private static final String MANIFEST = """
            8LAYERS_QUALCOMM_1|SHVC|8LAYERS_QUALCOMM_1.zip|fdfe4fc40fed4a886a36eee3b739cf0c2cb95c0341c2bdcdaf493a043882e307|8LAYERS_QUALCOMM_1.bit
            ADAPTRES_A_ERICSSON_1|SHVC|ADAPTRES_A_ERICSSON_1.zip|a82ac16c59cc3b35239fb6a96650f5a2382358e59585e43de61b32e084354807|ADAPTRES_A_ERICSSON_1.bit
            ALPHA_A_BBC_1|SHVC|ALPHA_A_BBC_1.zip|fb9c29e3006973509f645bc9a8d81ce30cb8709cbd5d9f0e5e7dcfe265294548|ALPHA_A_BBC_1.bit
            CGS_A_TECHNICOLOR_1|SHVC|CGS_A_TECHNICOLOR_1.zip|f1526b6b87877326ab84d90b23f68707b6ba99152375710863d3f96530bb2e7b|CGS_A_TECHNICOLOR_1.bit
            CGS_B_TECHNICOLOR_1|SHVC|CGS_B_TECHNICOLOR_1.zip|6902a8c903a3f6d9b40f5a338dd820d43f68f8f57fc086bdc744fbce5b97d6aa|CGS_B_TECHNICOLOR_1.bit
            CGS_C_TECHNICOLOR_1|SHVC|CGS_C_TECHNICOLOR_1.zip|9326dc348ba3ef701ab5437f314390c8a64746ed15dbe3fdd5289f270befd370|CGS_C_TECHNICOLOR_1.bit
            CGS_D_TECHNICOLOR_1|SHVC|CGS_D_TECHNICOLOR_1.zip|0843a5d40b58679379b25ddc24daaa22d47f7f5786c500494c375c684e25e30e|CGS_D_TECHNICOLOR_1.bit
            CGS_E_TECHNICOLOR_1|SHVC|CGS_E_TECHNICOLOR_1.zip|507ed4d8a89235633921f44f7e7c7a14d498e576dfad9e798a6fa96a9230e3a7|CGS_E_TECHNICOLOR_1.bit
            CGS_F_TECHNICOLOR_1|SHVC|CGS_F_TECHNICOLOR_1.zip|cb8ff0e5879ac85723aeb3e2fce80651afc51a3a856c31e83d54bbc33412d4a8|CGS_F_TECHNICOLOR_1.bit
            CGS_G_TECHNICOLOR_1|SHVC|CGS_G_TECHNICOLOR_1.zip|20d26645bf9078749d36fe32cf3d4fe85fd25308a70a1a90cfe053e2917914b6|CGS_G_TECHNICOLOR_1.bit
            CGS_H_TECHNICOLOR_1|SHVC|CGS_H_TECHNICOLOR_1.zip|f1b059a5b0826062c98505e6354ae92db16dbaec94cbdb96b14946b8d76a0dde|CGS_H_TECHNICOLOR_1.bit
            CGS_I_TECHNICOLOR_1|SHVC|CGS_I_TECHNICOLOR_1.zip|804894ab4a4cc32f0fefd887ca695d5393b3f0f809b9d9bdd3f33959dc4ee8ea|CGS_I_TECHNICOLOR_1.bit
            CONFCROP_A_VIDYO_2|SHVC|CONFCROP_A_VIDYO_2.zip|c3e786a4757df7680ec697d4cbcba7dae97e6077ce4b938a13b9e1dc245e883d|CONFCROP_A_VIDYO_2.bit
            CONFCROP_B_VIDYO_2|SHVC|CONFCROP_B_VIDYO_2.zip|a6b58b195c9348c91a6332bca6c3f81c36e054d4990216626bbddc2891365568|CONFCROP_B_VIDYO_2.bit
            CONFCROP_C_VIDYO_3|SHVC|CONFCROP_C_VIDYO_3.zip|a46f49f45041b957510e8b496ec7855470eeaae3d6500b17c50c2cb69da656a0|CONFCROP_C_VIDYO_3.bit
            DEPTH_A_NOKIA_1|SHVC|DEPTH_A_NOKIA_1.zip|2b172eab5211c3aba2ad635c01dd78b877d342beaaba9b6ef76ddcaf91e6b35f|DEPTH_A_NOKIA_1.bit
            DISFLAG_A_QUALCOMM_1|SHVC|DISFLAG_A_QUALCOMM_1.zip|c77d7ab205908c945b37ba74d8cded93b9c68b99a6c7b8b0e73dc364942189d3|DISFLAG_A_QUALCOMM_1.bit
            DPB_A_VIDYO_2|SHVC|DPB_A_VIDYO_2.zip|9f1e8de941aa3ce9d22425dd0992fd75dd38ed54370c2050145a795d56c118ed|DPB_A_VIDYO_2.bit
            DPB_B_VIDYO_2|SHVC|DPB_B_VIDYO_2.zip|391e40e567c3a2c8d8495c2cab07adea2cd7f6afed5d8e2aeb6c5f4c3877108e|DPB_B_VIDYO_2.bit
            INACTIVE_A_QCOM_1|SHVC|INACTIVE_A_QCOM_1.zip|9cd143b84e217906551a4a5977f15e4daeb933b913286cabd4e5a8bb8a346e9d|INACTIVE_A_QCOM_1.bin
            INBLD_A_NOKIA_2|SHVC|INBLD_A_NOKIA_2.zip|42b5f2f0655f65156dfc43d9692d7ceed4135b13d8e6d33e0552040ac3e8e40e|INBLD_A_NOKIA_2.bit
            LAYERID63_A_HHI_1|SHVC|LAYERID63_A_HHI_1.zip|8ce227d51ee0d33ca565c781f387fec8b8a8645c5103a0aec6611a42e0ed4cef|LAYERID63_A_HHI_1/LAYERID63_A_HHI_1.bin
            LAYERID_A_NOKIA_2|SHVC|LAYERID_A_NOKIA_2.zip|059cb84d5351028cef15b744e5c7d8824314789227b42307958c42c3f969adfe|LAYERID_A_NOKIA_2.bit
            MAXTID_A_ETRI_2|SHVC|MAXTID_A_ETRI_2.zip|2d16eae62662ef984e0e1b6016f10a7133eb598dce2b4fd9aeb71375b4da7072|MAXTID_A_ETRI_2.bit
            MAXTID_B_ETRI_2|SHVC|MAXTID_B_ETRI_2.zip|463b46c182d8616049e742c01ea5ce3da9a6f8e9c2dd2217bfd75a2f95fdb25c|MAXTID_B_ETRI_2.bit
            MAXTID_C_ETRI_2|SHVC|MAXTID_C_ETRI_2.zip|158924d1e4d2685586fd749e059c8a946fa4795547431a9041404883b49986b0|MAXTID_C_ETRI_2.bit
            MVD_A_IDCC_1|SHVC|MVD_A_IDCC_1.zip|7f89ccb7541344d8dfbd15275689c1853d1ad2243211cfcacb68c456b4d1cea1|MVD_A_IDCC_1/MVD_A_IDCC_1.bit
            MVD_A_NOKIA_1|SHVC|MVD_A_NOKIA_1.zip|8be8b39435490ab380efdf20c917829f2644ed90c2bf3b60c2537551403905ed|MVD_A_NOKIA_1.bit
            NONVUI_A_QUALCOMM_1|SHVC|NONVUI_A_QUALCOMM_1.zip|bba327676824ce1b696110139a91bff19f228949f52e9f947e87720686dddee9|NONVUI_A_QUALCOMM_1.bit
            NONVUI_B_QUALCOMM_1|SHVC|NONVUI_B_QUALCOMM_1.zip|77e00027e1c09adc782b732c811454791f706c4acddef2e5155a61865fad6a4b|NONVUI_B_QUALCOMM_1.bit
            NONVUI_C_QUALCOMM_1|SHVC|NONVUI_C_QUALCOMM_1.zip|318f9bdd496e8f9ea065b1d8135e8498424133da8ca25a2497baa6fc07cf50ce|NONVUI_C_QUALCOMM_1.bit
            OLS_A_NOKIA_1|SHVC|OLS_A_NOKIA_1.zip|06b4532d91e4ab92c2dac2849ef1b175b74584595e07ec162713b4780de4a1dc|OLS_A_NOKIA_1.bit
            OLS_B_NOKIA_1|SHVC|OLS_B_NOKIA_1.zip|f5240bf2424890d29d21fc418cc6f442c9ead6c80ae621d2dff508e369df23a4|OLS_B_NOKIA_1.bit
            OLS_C_NOKIA_1|SHVC|OLS_C_NOKIA_1.zip|1d08563c30c2c47d89ec0016aa14b02f3ad4317a0680690353e42c3c6e1866c5|OLS_C_NOKIA_1.bit
            POC_A_Ericsson_1|SHVC|POC_A_Ericsson_1.zip|8cbb0fe09126612ae5b3d2e3c2fbd3052c774045319ec04a619c5446800891f9|POC_A_Ericsson_1.bit
            POC_B_Ericsson_1|SHVC|POC_B_Ericsson_1.zip|06feb0c9adde55e0c876e8c5a7db39b63ae5fd1066f86d8d007a02c4dd0f2cdf|POC_B_Ericsson_1.bit
            PPSSLIST_A_Sony_2|SHVC|PPSSLIST_A_Sony_2.zip|cd418751260d8c9f8621a80457c1f19db10c3c205c24b4cfc0e2a4f7182a2fee|PPSSLIST_A_Sony_2/PPSSLIST_A_Sony_2.bit
            PSEXT_A_VIDYO_2|SHVC|PSEXT_A_VIDYO_2.zip|9f9599aea52ccf50ee3719eed54eff0fa071ba6b643b6523c9a77bffa1a8a9ba|PSEXT_A_VIDYO_2.bit
            REFLAYER_A_VIDYO_2|SHVC|REFLAYER_A_VIDYO_2.zip|3f50a7efddec81831dd96aa06a71d6c211c4a3f639e794ad49a89c03111a9e40|REFLAYER_A_VIDYO_2.bit
            REFLAYER_B_VIDYO_2|SHVC|REFLAYER_B_VIDYO_2.zip|0931df6383f018a18b58fa1d1d4e354a7ff3c2bfbff273fac74af6bb25713a0e|REFLAYER_B_VIDYO_2.bit
            REFLAYER_C_VIDYO_2|SHVC|REFLAYER_C_VIDYO_2.zip|7dbb15a6d5408e80de5c9dd15a29f8fd7fb873f921ec53e9c1292e4dacf3f313|REFLAYER_C_VIDYO_2.bit
            REFLAYER_D_VIDYO_2|SHVC|REFLAYER_D_VIDYO_2.zip|d6cf7071f33ad4c3c1e60aaaa6a52a34f5ea94339bd44722fdb0c030ed97a39d|REFLAYER_D_VIDYO_2.bit
            REFREGOFF_A_SHARP_1|SHVC|REFREGOFF_A_SHARP_1.zip|6d3d4d782973bf57b6dcf4e053148d0b612a761b99a15f8a2e41f44a46ff8c7a|REFREGOFF_A_SHARP_1.bit
            REPFMT_A_VIDYO_2|SHVC|REPFMT_A_VIDYO_2.zip|06992e25cd64ecb8a3e7d8dd8218b046c80a46ce3e4922f724e499412fc4344d|REPFMT_A_VIDYO_2.bit
            REPFMT_B_VIDYO_2|SHVC|REPFMT_B_VIDYO_2.zip|b45baf93251c31efca9e368e42d3afdd86b9bb0576fad377e4b9359bced2df8f|REPFMT_B_VIDYO_2.bit
            REPFMT_C_VIDYO_2|SHVC|REPFMT_C_VIDYO_2.zip|c76f9b9c5b9ed8f56cfa5d57ac66d2448e381467ab41f95b84c3de149ef1730e|REPFMT_C_VIDYO_2.bit
            RESCHANGE_A_VIDYO_1|SHVC|RESCHANGE_A_VIDYO_1.zip|122630b98c6d508751c651c106bd8d7fd9d7d84619f1d62b7fbe3747cf998a0d|RESCHANGE_A_VIDYO_1.bit
            RESPHASE_A_SAMSUNG_2|SHVC|RESPHASE_A_SAMSUNG_2.zip|61893f6f89cfeabaec72ad22135772480ffd9841f562346602b4488b6e612453|RESPHASE_A_SAMSUNG_2.bit
            SCREFOFF_A_QCOM_1|SHVC|SCREFOFF_A_QCOM_1.zip|c910cd9e553b3788fb642b47f81a9d3d7d1ad809d0fcc3fdd2c2261da7920264|SCREFOFF_A_QCOM_1.bit
            SIM_A_IDCC_1|SHVC|SIM_A_IDCC_1.zip|5e6ef73f6c183b514b61c63ea37b8b1b68623f67a273165a26f33d39dad78cc5|SIM_A_IDCC_1/SIM_A_IDCC_1.bit
            SIM_B_IDCC_1|SHVC|SIM_B_IDCC_1.zip|1ada62f8a24effc85b24ae8beaea1c52837c74a09c13375272c294f5a97edae0|SIM_B_IDCC_1/SIM_B_IDCC_1.bit
            SLLEV_A_VIDYO_1|SHVC|SLLEV_A_VIDYO_1.zip|cde1bc2df2f76c2d90ebe95445db5f8d57d7754274f79a0d46da2e673c963d74|SLLEV_A_VIDYO_1.bit
            SNR_A_IDCC_1|SHVC|SNR_A_IDCC_1.zip|d8ae1a10bf496ba677d778b594ef96b7cb344f401909fcb531773bc2c817b27b|SNR_A_IDCC_1/SNR_A_IDCC_1.bit
            SNR_B_IDCC_1|SHVC|SNR_B_IDCC_1.zip|ceeb600dbf916726e340ec8d6d03952b1446dba326a620455516f9e0264b70a5|SNR_B_IDCC_1/SNR_B_IDCC_1.bit
            SNR_C_IDCC_1|SHVC|SNR_C_IDCC_1.zip|f21a09d59246de3c9d3f857f7f19d8510a9bba64eb2e7e6cc1da16a68f4082f2|SNR_C_IDCC_1/SNR_C_IDCC_1.bit
            SPLITFLAG_A_HHI_1|SHVC|SPLITFLAG_A_HHI_1.zip|ea48546d968e13e4498b346b1fc3e2c558314e7a4c3a9568805177903c5c5ae0|SPLITFLAG_A_HHI_1.bit
            SPSREPFMT_A_Sony_2|SHVC|SPSREPFMT_A_Sony_2.zip|4ac97beeb944c7f5e2d204d087902370f778040b8184b7e6f587bb379cd65c71|SPSREPFMT_A_Sony_2/SPSREPFMT_A_Sony_2.bit
            SPSSLIST_A_Sony_2|SHVC|SPSSLIST_A_Sony_2.zip|ba466304f1707f5ede0a1bd14a53f193c1cd2d9b6f456deb1065e43f94cd422e|SPSSLIST_A_Sony_2/SPSSLIST_A_Sony_2.bit
            SRATIOS_A_SAMSUNG_3|SHVC|SRATIOS_A_SAMSUNG_3.zip|ed4ef059b5624f4a88f922232e6b8d83292435ac226c1f23874f70011aa8f888|SRATIOS_A_SAMSUNG_3/SRATIOS_A_SAMSUNG_3.bit
            SRATIOS_B_SAMSUNG_2|SHVC|SRATIOS_B_SAMSUNG_2.zip|500a2db3045758de0b9554f312500c387488ef66f09b31b87dbd1d1ce31afa29|SRATIOS_B_SAMSUNG_2/SRATIOS_B_SAMSUNG_2.bit
            SREXT_A_FUJITSU_1|SHVC|SREXT_A_FUJITSU_1.zip|428714872c17383b430425d86843969fe7ca7efa925629bd7d524162ac17bd34|SREXT_A_FUJITSU_1.bit
            SREXT_B_FUJITSU_1|SHVC|SREXT_B_FUJITSU_1.zip|2d3f475fac5a6e8795e0f346f0820c63b98be53eb1d277810ba2aea79bcbe041|SREXT_B_FUJITSU_1.bit
            SREXT_C_FUJITSU_1|SHVC|SREXT_C_FUJITSU_1.zip|7b396f64cf20a84972c812b5415430ba696eb7f4f72f45f25b9540f2dad15888|SREXT_C_FUJITSU_1.bit
            SREXT_D_FUJITSU_1|SHVC|SREXT_D_FUJITSU_1.zip|1c20a49d06639b892c6a94c7343dd6b99319bb39f7fe0829b3e0d2e2e72a12e2|SREXT_D_FUJITSU_1.bit
            SREXT_E_FUJITSU_1|SHVC|SREXT_E_FUJITSU_1.zip|fdb893ad9337805047985781ef6dd40d18e1084ca54fac5dc2f5f7235f22980c|SREXT_E_FUJITSU_1.bit
            SREXT_F_FUJITSU_1|SHVC|SREXT_F_FUJITSU_1.zip|bd8c26e41abb76b1d5b80bc4f518c2f502563bc0caa4cbdc0a3c78ef8ec48cde|SREXT_F_FUJITSU_1.bit
            VUI_A_QUALCOMM_1|SHVC|VUI_A_QUALCOMM_1.zip|7db1296bce52d79131b3a9c1136bb7e6112886026612c2b1fc0d783639591385|VUI_A_QUALCOMM_1.bit
            VUI_B_QUALCOMM_1|SHVC|VUI_B_QUALCOMM_1.zip|b4f9124ba37bfdbe6db4f8a1de7301a9b547151c56bc540d7edac5d30d50187f|VUI_B_QUALCOMM_1.bit
            VUI_C_QUALCOMM_1|SHVC|VUI_C_QUALCOMM_1.zip|b963e02ab657bf34f345d7d03294b21663a47e208c44255779caa29b8c333671|VUI_C_QUALCOMM_1.bit
            MVHEVCS_A_Qualcomm_3|MV-HEVC|MVHEVCS_A_Qualcomm_3.zip|0f15f37b293112ae39017f99771ceeaa3f11a7812986bb9ca811bab3da7684e4|MVHEVCS_A_Qualcomm_3/MVHEVCS_A.bit
            MVHEVCS_B_Sharp_3|MV-HEVC|MVHEVCS_B_Sharp_3.zip|3a433642c470c432c6de2d82fa2f3a2fde02b7db69ca1c3b8e0070e9b12b7a6e|MVHEVCS_B_Sharp_3/MVHEVCS_B.bit
            MVHEVCS_C_Sony_3|MV-HEVC|MVHEVCS_C_Sony_3.zip|6562fd25e41470daa63c9c4cfab344d011fa7cdd0a032145e0a4dfa5a2b4a050|MVHEVCS_C/MVHEVCS_C.bit
            MVHEVCS_D_NTT_3|MV-HEVC|MVHEVCS_D_NTT_3.zip|98d4d97c1bd5bd9a4519a762f34bb3804df3bf23072bd2cdacfe0c287d3ff7f8|MVHEVCS_D_NTT_3/MVHEVCS_D.bit
            MVHEVCS_E_Qualcomm_3|MV-HEVC|MVHEVCS_E_Qualcomm_3.zip|a53b5d65d9e61f1c4b5f81514526d83c7b1e68497da5c3f4889a74812d1b7bde|MVHEVCS_E_Qualcomm_3/MVHEVCS_E.bit
            MVHEVCS_F_Qualcomm_3|MV-HEVC|MVHEVCS_F_Qualcomm_3.zip|94e3c3b6cedcfbe5f1c7649b7dda52a979cd26fd1ef1108ddfe51aaf3df7db62|MVHEVCS_F_Qualcomm_3/MVHEVCS_F.bit
            MVHEVCS_G_NTT_3|MV-HEVC|MVHEVCS_G_NTT_3.zip|a620a4092dd663d4906658acc6d7cb029d619e26ce5818b65f5daa057fbe8efd|MVHEVCS_G_NTT_3/MVHEVCS_G.bit
            MVHEVCS_H_LGE_3|MV-HEVC|MVHEVCS_H_LGE_3.zip|7a7c2dd044b337d999c601d190dd6ab6513ca359e7718daed65ba52cf8bb6e75|MVHEVCS_H.bit
            MVHEVCS_I_Nokia_3|MV-HEVC|MVHEVCS_I_Nokia_3.zip|170312273e9de6cfa62b20255c05fd1f820395afd3fe27f0f490eb7d86c0be9e|MVHEVCS_I.bit
            """;

    record Vector(String name, String directory, String archive, String sha256, String bitstream) {
        @Override
        public String toString() {
            return name;
        }

        boolean multiview() {
            return directory.equals("MV-HEVC");
        }
    }

    /**
     * One output layer set of a vector and its output picture log.
     */
    record OutputLayerSet(Vector vector, int index, String log) {
        @Override
        public String toString() {
            return vector.name() + " OLS " + index;
        }
    }

    /**
     * One output picture: its layer and the MD5 of each of its planes.
     */
    record Output(int layer, String y, String u, String v) {
    }

    /**
     * One decoded picture of the reference decoder's log or of the engine: its layer, picture order
     * count and the MD5 of each of its planes, the chroma ones {@code null} when the log gives luma
     * only.
     */
    record Logged(int layer, int poc, String y, String u, String v) {
        boolean matches(Logged actual) {
            return poc == actual.poc && matchesSamples(actual);
        }

        boolean matchesSamples(Logged actual) {
            return layer == actual.layer && y.equals(actual.y) && (u == null || u.equals(actual.u) && v.equals(actual.v));
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            result.add(new Vector(parts[0], parts[1], parts[2], parts[3], parts[4]));
        }
        return result;
    }

    static ZipFile archive(Vector vector) throws IOException {
        var prefix = vector.multiview() ? "hevc-mvhevc-" : "hevc-shvc-";
        var file = Conformance.fetch(prefix + vector.archive(), BASE + vector.directory() + "/" + vector.archive(),
                vector.sha256());
        return new ZipFile(file.toFile());
    }

    static byte[] entry(ZipFile zip, String name) throws IOException {
        try (var in = zip.getInputStream(zip.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    private static final Pattern OLS = Pattern.compile("[-_]OLS(\\d+)\\.opl$");

    static Stream<OutputLayerSet> logged() throws IOException {
        var result = new ArrayList<OutputLayerSet>();
        for (var vector : vectors()) {
            if (vector.multiview()) {
                continue;
            }
            try (var zip = archive(vector)) {
                var entries = zip.stream().map(java.util.zip.ZipEntry::getName).sorted().toList();
                for (var name : entries) {
                    var matcher = OLS.matcher(name);
                    if (matcher.find()) {
                        result.add(new OutputLayerSet(vector, Integer.parseInt(matcher.group(1)),
                                new String(entry(zip, name), StandardCharsets.UTF_8)));
                    }
                }
            }
        }
        return result.stream();
    }

    static Stream<Vector> unlogged() throws IOException {
        var result = new ArrayList<Vector>();
        for (var vector : vectors()) {
            try (var zip = archive(vector)) {
                var logs = zip.stream().anyMatch(entry -> OLS.matcher(entry.getName()).find());
                if (!logs) {
                    result.add(vector);
                }
            }
        }
        return result.stream();
    }

    private static final Pattern DECODE_LOG = Pattern.compile("POC\\s+(-?\\d+)\\s+LId:\\s+(\\d+).*MD5:([0-9a-f,]+)");

    /**
     * Parses a reference decoder log: the output picture log ({@code LayerId POC MD5:y,u,v} per
     * line) or the decoder's console log ({@code POC n LId: l ... [MD5:y,u,v,(OK)]}), in which
     * every picture is listed once in decoding order.
     */
    static List<Logged> parseLog(String log) {
        // a console log may hold an interrupted run before the complete one
        var run = log.lastIndexOf("Decoder Version");
        if (run > 0) {
            log = log.substring(run);
        }
        var result = new ArrayList<Logged>();
        for (var line : log.split("\\R")) {
            var console = DECODE_LOG.matcher(line);
            String layer;
            String poc;
            String md5;
            if (console.find()) {
                poc = console.group(1);
                layer = console.group(2);
                md5 = console.group(3);
            } else {
                var parts = line.strip().split("\\s+");
                if (parts.length < 3 || !parts[2].startsWith("MD5:")) {
                    continue;
                }
                layer = parts[0];
                poc = parts[1];
                md5 = parts[2].substring(4);
            }
            var hashes = md5.split(",");
            result.add(new Logged(Integer.parseInt(layer), Integer.parseInt(poc), hashes[0],
                    hashes.length > 1 ? hashes[1] : null, hashes.length > 2 ? hashes[2] : null));
        }
        return result;
    }

    /**
     * Decodes a stream with the engine and returns every output picture, uncropped as the reference
     * decoder logs it, with its layer and picture order count.
     */
    static List<Logged> decodeLogged(byte[] stream, int ols, int threads) {
        var result = new ArrayList<Logged>();
        var engine = new HevcDecoderEngine(MediaLimits.DEFAULT, true, threads, ols, false,
                frame -> result.add(log(frame)));
        try {
            for (var nal : HevcTestStreams.nalUnits(stream)) {
                engine.decodeNal(nal, 0, nal.length);
            }
            engine.flush();
        } finally {
            engine.close();
        }
        return result;
    }

    private static Logged log(HevcFrame frame) {
        var lumaSize = frame.width * frame.height;
        String y;
        String u;
        String v;
        if (frame.eightBit()) {
            y = hex(java.util.Arrays.copyOf(frame.y8, lumaSize));
            if (frame.cb8 != null) {
                var chromaSize = frame.chromaWidth * frame.chromaHeight;
                u = hex(java.util.Arrays.copyOf(frame.cb8, chromaSize));
                v = hex(java.util.Arrays.copyOf(frame.cr8, chromaSize));
            } else {
                var gray = new byte[((frame.width + 1) / 2) * ((frame.height + 1) / 2)];
                java.util.Arrays.fill(gray, (byte) 128);
                u = hex(gray);
                v = u;
            }
        } else {
            y = hex(shorts(frame.y, frame.width, frame.width, frame.height));
            if (frame.cb != null) {
                u = hex(shorts(frame.cb, frame.chromaWidth, frame.chromaWidth, frame.chromaHeight));
                v = hex(shorts(frame.cr, frame.chromaWidth, frame.chromaWidth, frame.chromaHeight));
            } else {
                var width = (frame.width + 1) / 2;
                var height = (frame.height + 1) / 2;
                var gray = new short[width * height];
                java.util.Arrays.fill(gray, (short) (1 << (frame.sps.bitDepthChroma - 1)));
                u = hex(shorts(gray, width, width, height));
                v = u;
            }
        }
        return new Logged(frame.layerId, frame.outputPoc, y, u, v);
    }

    /**
     * Asserts that the engine output the pictures of the log: the same pictures of each layer, in
     * the log's order per layer and picture order count (the logs list pictures in output or in
     * decoding order), and no other picture.
     */
    static void assertLogged(String name, List<Logged> expected, List<Logged> actual) {
        var expectedByKey = new LinkedHashMap<String, List<Logged>>();
        for (var picture : expected) {
            expectedByKey.computeIfAbsent(picture.layer() + "/" + picture.poc(), _ -> new ArrayList<>()).add(picture);
        }
        var actualByKey = new LinkedHashMap<String, List<Logged>>();
        for (var picture : actual) {
            actualByKey.computeIfAbsent(picture.layer() + "/" + picture.poc(), _ -> new ArrayList<>()).add(picture);
        }
        for (var entry : expectedByKey.entrySet()) {
            var pictures = actualByKey.getOrDefault(entry.getKey(), List.of());
            for (var k = 0; k < entry.getValue().size(); k++) {
                var wanted = entry.getValue().get(k);
                if (k >= pictures.size()) {
                    fail(name + ": no output picture for " + wanted + "; output " + summary(actual));
                }
                if (!wanted.matches(pictures.get(k))) {
                    fail(name + ": picture differs: expected " + wanted + ", got " + pictures.get(k));
                }
            }
        }
        assertEquals(expected.size(), actual.size(), name + ": output picture count; output " + summary(actual));
    }

    /**
     * Asserts that the engine output the pictures of the log, matched by their samples alone: the
     * base layer decoded on its own (output layer set 0) takes its picture order counts from the
     * single-layer process, which ignores the picture order count resets of the multi-layer
     * extensions the reference decoder applies, so only the labels of its pictures may differ.
     */
    static void assertLoggedSamples(String name, List<Logged> expected, List<Logged> actual) {
        var remaining = new ArrayList<>(actual);
        for (var wanted : expected) {
            var match = remaining.stream().filter(wanted::matchesSamples).findFirst();
            if (match.isEmpty()) {
                fail(name + ": no output picture with the samples of " + wanted + "; output " + summary(actual));
            }
            remaining.remove(match.get());
        }
        assertEquals(expected.size(), actual.size(), name + ": output picture count; output " + summary(actual));
    }

    private static String summary(List<Logged> pictures) {
        var out = new StringBuilder();
        for (var picture : pictures) {
            out.append(picture.layer()).append(':').append(picture.poc()).append(' ');
        }
        return out.toString();
    }

    /**
     * Decodes a stream fed one NAL unit per packet and returns the output pictures in order.
     */
    static List<Output> decode(byte[] stream, HevcDecoderOptions options, Map<Integer, MessageDigest> whole) {
        var result = new ArrayList<Output>();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                var layer = frame.sideData().layer().map(VideoLayer::id).orElse(0);
                var planes = planes(frame.picture());
                result.add(new Output(layer, hex(planes[0]), hex(planes[1]), hex(planes[2])));
                if (whole != null) {
                    var digest = whole.computeIfAbsent(layer, _ -> HevcTestStreams.md5());
                    for (var plane : planes) {
                        digest.update(plane);
                    }
                }
            };
            for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return result;
    }

    static String hex(byte[] data) {
        return HexFormat.of().formatHex(HevcTestStreams.md5().digest(data));
    }

    /**
     * Returns the three planes of a picture as raw samples, a monochrome picture's chroma as
     * mid-gray 4:2:0 planes.
     */
    static byte[][] planes(Picture picture) {
        return switch (picture) {
            case YuvPicture p -> {
                var y = new byte[p.width() * p.height()];
                for (var row = 0; row < p.height(); row++) {
                    System.arraycopy(p.y(), row * p.yStride(), y, row * p.width(), p.width());
                }
                if (p.u() == null) {
                    var gray = new byte[((p.width() + 1) / 2) * ((p.height() + 1) / 2)];
                    java.util.Arrays.fill(gray, (byte) 128);
                    yield new byte[][]{y, gray, gray};
                }
                var u = new byte[p.chromaWidth() * p.chromaHeight()];
                var v = new byte[u.length];
                for (var row = 0; row < p.chromaHeight(); row++) {
                    System.arraycopy(p.u(), row * p.uvStride(), u, row * p.chromaWidth(), p.chromaWidth());
                    System.arraycopy(p.v(), row * p.uvStride(), v, row * p.chromaWidth(), p.chromaWidth());
                }
                yield new byte[][]{y, u, v};
            }
            case Yuv16Picture p -> {
                var y = shorts(p.y(), p.yStride(), p.width(), p.height());
                if (p.u() == null) {
                    var width = (p.width() + 1) / 2;
                    var height = (p.height() + 1) / 2;
                    var gray = new short[width * height];
                    java.util.Arrays.fill(gray, (short) (1 << (p.bitDepth() - 1)));
                    var plane = shorts(gray, width, width, height);
                    yield new byte[][]{y, plane, plane};
                }
                yield new byte[][]{y, shorts(p.u(), p.uvStride(), p.chromaWidth(), p.chromaHeight()),
                        shorts(p.v(), p.uvStride(), p.chromaWidth(), p.chromaHeight())};
            }
            default -> throw new IllegalStateException("unexpected picture " + picture);
        };
    }

    private static byte[] shorts(short[] plane, int stride, int width, int height) {
        var out = new byte[width * height * 2];
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                var value = plane[row * stride + x];
                out[2 * (row * width + x)] = (byte) value;
                out[2 * (row * width + x) + 1] = (byte) (value >> 8);
            }
        }
        return out;
    }

    static HevcDecoderOptions options(int ols, int threads) {
        return HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).alpha(false).threads(threads)
                .outputLayerSet(ols).build();
    }

    /**
     * Returns the first video parameter set of a stream.
     */
    static HevcVps vps(byte[] stream) {
        for (var start : HevcTestStreams.nalUnits(stream)) {
            if (HevcTestStreams.type(start) == 32) {
                var rbsp = new byte[start.length + H264BitReader.PADDING];
                var removed = new int[start.length];
                var size = (int) NalUnits.unescape(start, 2, start.length - 2, rbsp, removed);
                var reader = new H264BitReader();
                reader.init(rbsp, 0, size);
                return HevcVps.parse(reader);
            }
        }
        throw new AssertionError("no video parameter set");
    }

    /**
     * The output layers the archived logs list beyond those of the specification's output layer
     * sets: the alpha layer of ALPHA_A depends on the base layer and its VPS makes only the highest
     * layer the output layer of output layer set 1 ({@code default_output_layer_idc} 1); its log
     * lists the base layer too, which SHM-12.4 decodes but does not output either.
     */
    private static final Map<String, Integer> LOGGED_NON_OUTPUT = Map.of("ALPHA_A_BBC_1 OLS 1", 0);

    static void assertOutputs(String name, List<Output> expected, List<Output> actual) {
        var count = Math.min(expected.size(), actual.size());
        for (var i = 0; i < count; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail(name + ": output picture " + i + " differs: expected " + expected.get(i) + ", got "
                        + actual.get(i));
            }
        }
        assertEquals(expected.size(), actual.size(), name + ": output picture count");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("logged")
    @DisplayName("each output layer set matches the reference decoder's output picture log")
    void outputLayerSets(OutputLayerSet set) throws IOException {
        byte[] stream;
        try (var zip = archive(set.vector())) {
            stream = entry(zip, set.vector().bitstream());
        }
        var expected = expected(set);
        if (set.log().contains("Decoder Version")) {
            // a console log lists every decoded picture, an output picture log only the output ones
            var vps = vps(stream);
            var list = vps.layerSetLayerIdList[vps.olsIdxToLsIdx[set.index()]];
            var output = new java.util.HashSet<Integer>();
            for (var j = 0; j < list.length; j++) {
                if (vps.outputLayerFlag[set.index()][j]) {
                    output.add(list[j]);
                }
            }
            expected.removeIf(picture -> !output.contains(picture.layer()));
        }
        assertFalse(expected.isEmpty(), "the log lists pictures");
        List<Logged> actual;
        try {
            actual = decodeLogged(stream, set.index(), 1);
        } catch (MediaException exception) {
            throw new AssertionError(set + ": " + exception.getMessage(), exception);
        }
        if (set.index() == 0) {
            assertLoggedSamples(set.toString(), expected, actual);
        } else {
            assertLogged(set.toString(), expected, actual);
        }
    }

    static List<Logged> expected(OutputLayerSet set) {
        var expected = parseLog(set.log());
        var excluded = LOGGED_NON_OUTPUT.get(set.toString());
        if (excluded != null) {
            expected.removeIf(picture -> picture.layer() == excluded);
        }
        return expected;
    }

    static Stream<OutputLayerSet> threadedSets() throws IOException {
        return logged().filter(set -> set.vector().name().matches("SNR_B_IDCC_1|SIM_A_IDCC_1|CGS_E_TECHNICOLOR_1"
                + "|POC_B_Ericsson_1|MVD_A_NOKIA_1|ADAPTRES_A_ERICSSON_1|REFLAYER_D_VIDYO_2"))
                .filter(set -> set.index() > 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threadedSets")
    @DisplayName("decodes the layers bit-exactly on several threads")
    void outputLayerSetsThreaded(OutputLayerSet set) throws IOException {
        byte[] stream;
        try (var zip = archive(set.vector())) {
            stream = entry(zip, set.vector().bitstream());
        }
        assertLogged(set + " threaded", expected(set), decodeLogged(stream, set.index(), 4));
    }

    private static final Pattern LAYER_MD5 = Pattern.compile("(?:[-_]L|_)(\\d+)(?:_rec)?\\.md5$", Pattern.CASE_INSENSITIVE);

    private static final Pattern MD5_LINE = Pattern.compile("^\\s*([0-9a-fA-F]{32})\\b");

    private static final Pattern OUTPUT_FILE = Pattern.compile("(?:[-_]L|_)(\\d+)\\.(?:yuv|alpha)\\s*$",
            Pattern.CASE_INSENSITIVE);

    @ParameterizedTest(name = "{0}")
    @MethodSource("unlogged")
    @DisplayName("each layer's whole output matches the reference decoder's MD5")
    void wholeLayers(Vector vector) throws IOException {
        assertWholeLayers(vector, 1);
    }

    static Stream<Vector> threadedViews() throws IOException {
        // three views whose third predicts from two inter-layer references of one picture order count
        return unlogged().filter(vector -> vector.name().matches("MVHEVCS_H_LGE_3|MVHEVCS_I_Nokia_3"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threadedViews")
    @DisplayName("decodes the views bit-exactly on several threads")
    void wholeLayersThreaded(Vector vector) throws IOException {
        assertWholeLayers(vector, 4);
    }

    static void assertWholeLayers(Vector vector, int threads) throws IOException {
        byte[] stream;
        var expected = new TreeMap<Integer, String>();
        try (var zip = archive(vector)) {
            stream = entry(zip, vector.bitstream());
            for (var entry : zip.stream().toList()) {
                if (!entry.getName().toLowerCase().endsWith(".md5")) {
                    continue;
                }
                var file = LAYER_MD5.matcher(entry.getName());
                var text = new String(entry(zip, entry.getName()), StandardCharsets.US_ASCII);
                for (var line : text.split("\\R")) {
                    var hash = MD5_LINE.matcher(line);
                    if (!hash.find()) {
                        continue;
                    }
                    var named = OUTPUT_FILE.matcher(line);
                    if (named.find()) {
                        expected.put(Integer.parseInt(named.group(1)), hash.group(1).toLowerCase());
                    } else if (file.find(0)) {
                        expected.put(Integer.parseInt(file.group(1)), hash.group(1).toLowerCase());
                    }
                }
            }
        }
        assertFalse(expected.isEmpty(), "the archive gives layer MD5s");
        var vps = vps(stream);
        var actual = new LinkedHashMap<Integer, String>();
        for (var index : expected.keySet()) {
            // the MV-HEVC files number their views by layer index, the SHVC files by layer identifier
            var layerId = vector.multiview() && index < vps.maxLayers ? vps.layerIdInNuh[index] : index;
            var ols = firstOutputting(vps, layerId);
            var whole = new LinkedHashMap<Integer, MessageDigest>();
            try {
                decode(stream, options(ols, threads), whole);
            } catch (MediaException exception) {
                throw new AssertionError(vector + " layer " + layerId + ": " + exception.getMessage(), exception);
            }
            var digest = whole.get(layerId);
            actual.put(index, digest == null ? "no output" : HexFormat.of().formatHex(digest.digest()));
        }
        assertEquals(expected, actual, vector + " per-layer MD5s");
    }

    /**
     * Returns the first output layer set that outputs a layer, or the last one.
     */
    static int firstOutputting(HevcVps vps, int layerId) {
        for (var ols = 0; ols < vps.numOutputLayerSets(); ols++) {
            var list = vps.layerSetLayerIdList[vps.olsIdxToLsIdx[ols]];
            for (var j = 0; j < list.length; j++) {
                if (list[j] == layerId && vps.outputLayerFlag[ols][j]) {
                    return ols;
                }
            }
        }
        return vps.numOutputLayerSets() - 1;
    }

    /** Returns a copy of a recycled picture. */
    static YuvPicture copy(YuvPicture picture) {
        var copy = YuvPicture.allocate(picture.width(), picture.height(), picture.chromaFormat(), false,
                picture.color());
        for (var row = 0; row < picture.height(); row++) {
            System.arraycopy(picture.y(), row * picture.yStride(), copy.y(), row * copy.yStride(), picture.width());
        }
        return copy;
    }

    @Nested
    @DisplayName("external base layer")
    class Hybrid {
        private static final Vector HYBRID = new Vector("HYBRID_A_QUALCOMM_1", "SHVC", "HYBRID_A_QUALCOMM_1.zip",
                "f22bee7b0fec0536df5a44983122d82e547625bdcdbf1ccd877142f1523a8962", "HYBRID_A_QUALCOMM_1.bit");

        @ParameterizedTest(name = "on {0} threads")
        @org.junit.jupiter.params.provider.ValueSource(ints = {1, 4})
        @DisplayName("HYBRID_A decodes its enhancement layer over the base layer pictures the archive supplies")
        void hybrid(int threads) throws IOException {
            byte[] stream;
            byte[] base;
            String log;
            try (var zip = archive(HYBRID)) {
                stream = entry(zip, HYBRID.bitstream());
                base = entry(zip, "BaseLayerSequence.yuv");
                log = new String(entry(zip, "HYBRID_A_QUALCOMM_1-OLS1.opl"), StandardCharsets.UTF_8);
            }
            var result = new ArrayList<Logged>();
            var engine = new HevcDecoderEngine(MediaLimits.DEFAULT, true, threads, 1, false,
                    frame -> result.add(log(frame)));
            var supplied = new int[1];
            // the archive keeps the base layer pictures in output order, as the reference decoder reads them
            engine.externalBaseLayer((format, pocLsb) -> {
                var luma = format.width() * format.height();
                var chroma = luma / 4;
                var start = (long) pocLsb * (luma + 2 * chroma);
                if (start + luma + 2L * chroma > base.length) {
                    return null;
                }
                var frame = new HevcFrame(format.width(), format.height(), format.chromaFormatIdc(), true);
                System.arraycopy(base, (int) start, frame.y8, 0, luma);
                System.arraycopy(base, (int) start + luma, frame.cb8, 0, chroma);
                System.arraycopy(base, (int) start + luma + chroma, frame.cr8, 0, chroma);
                var irap = supplied[0]++ == 0;
                return new HevcDecoderEngine.ExternalBaseLayer.Picture(frame, irap, HevcNal.IDR_W_RADL);
            });
            try {
                for (var nal : HevcTestStreams.nalUnits(stream)) {
                    engine.decodeNal(nal, 0, nal.length);
                }
                engine.flush();
            } finally {
                engine.close();
            }
            assertLogged(HYBRID + " OLS 1", parseLog(log), result);
        }
    }

    @Nested
    @DisplayName("alpha auxiliary layer")
    class Alpha {
        @Test
        @DisplayName("ALPHA_A attaches the alpha layer, interpreted by its alpha channel information, to the frames")
        void attached() throws IOException {
            var vector = vectors().stream().filter(v -> v.name().equals("ALPHA_A_BBC_1")).findFirst().orElseThrow();
            byte[] stream;
            try (var zip = archive(vector)) {
                stream = entry(zip, vector.bitstream());
            }
            // the alpha layer alone (output layer set 1 outputs it as monochrome frames) and the color
            // layer alone (output layer set 0), then the color layer with its alpha attached
            var lumas = new ArrayList<byte[]>();
            var separate = new ArrayList<VideoFrame>();
            for (var ols = 0; ols < 2; ols++) {
                var target = ols == 0 ? null : lumas;
                try (var decoder = new HevcDecoder(HevcTestStreams.track(), options(ols, 1))) {
                    Consumer<VideoFrame> sink = frame -> {
                        if (target != null) {
                            assertEquals(VideoLayer.Type.ALPHA, frame.sideData().layer().orElseThrow().type());
                            target.add(planes(frame.picture())[0]);
                        } else {
                            separate.add(new VideoFrame(copy((YuvPicture) frame.picture()), frame.pts(),
                                    frame.duration(), frame.keyframe()));
                        }
                    };
                    for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                        decoder.decode(HevcTestStreams.packet(nal), sink);
                    }
                    decoder.flush(sink);
                }
            }
            var attached = new ArrayList<byte[]>();
            var colors = new ArrayList<String>();
            var attachOptions = options(0, 1).toBuilder().alpha(true).build();
            try (var decoder = new HevcDecoder(HevcTestStreams.track(), attachOptions)) {
                Consumer<VideoFrame> sink = frame -> {
                    var picture = (YuvPicture) frame.picture();
                    assertTrue(picture.hasAlpha(), "the frame carries alpha");
                    assertEquals(VideoLayer.Type.PRIMARY, frame.sideData().layer().map(VideoLayer::type)
                            .orElse(VideoLayer.Type.PRIMARY));
                    var alpha = new byte[picture.width() * picture.height()];
                    for (var row = 0; row < picture.height(); row++) {
                        System.arraycopy(picture.alpha(), row * picture.alphaStride(), alpha, row * picture.width(),
                                picture.width());
                    }
                    attached.add(alpha);
                    colors.add(hex(planes(picture)[0]));
                };
                for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                    decoder.decode(HevcTestStreams.packet(nal), sink);
                }
                decoder.flush(sink);
            }
            assertEquals(lumas.size(), attached.size(), "one alpha plane per frame");
            assertEquals(separate.size(), attached.size(), "one frame per color picture");
            assertFalse(attached.isEmpty());
            for (var i = 0; i < attached.size(); i++) {
                var expected = lumas.get(i).clone();
                for (var k = 0; k < expected.length; k++) {
                    // transparent 0, opaque 255, clipping of type 0: values above 127 are opaque
                    expected[k] = (byte) ((expected[k] & 0xFF) > 127 ? 255 : 0);
                }
                assertEquals(hex(expected), hex(attached.get(i)), "alpha of frame " + i);
                assertEquals(hex(planes(separate.get(i).picture())[0]), colors.get(i), "color of frame " + i);
            }
        }
    }
}
