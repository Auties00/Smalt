package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the ITU-T H.264.1 conformance bitstreams (JVT draft conformance suite, AVCv1 and
 * FRExt) and requires every frame to match the reference YUV byte for byte, progressive, field
 * and MBAFF streams alike, 4:2:0 and 4:2:2, 8 and 10 bits, the Extended profile's SP slices
 * included. The streams that come without a reference YUV are checked by
 * {@link H264HighBitDepthTest}, {@link H264Chroma422Test} and {@link H264Chroma444Test}; the
 * Extended profile tools the suite does not exercise (slice data partitioning, switching SP and
 * SI slices) by {@link H264ExtendedProfileTest}. Each archive is pinned by its SHA-256 and cached
 * by {@link Conformance#fetch}.
 */
@DisplayName("H.264 conformance bitstreams (ITU-T H.264.1)")
class H264ConformanceTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/";

    /**
     * Name | directory | archive | SHA-256 | bitstream entry | reference entry | expectation
     * ("frames:N", or "frames16:N" for a reference of 16-bit little-endian samples).
     */
    private static final String MANIFEST = """
            AUD_MW_E|AVCv1|AUD_MW_E.zip|ecaed4a05d02c8ffc0ec7dd02a640f87ba9d9f8c2dab3273ef1d6da3d7f8568e|AUD_MW_E.264|AUD_MW_E_rec.qcif|frames:100
            BA1_FT_C|AVCv1|BA1_FT_C.zip|f21e1b0b7b820ac6f078bc5e9aa3284e068d2fdf1192e6c5c42509a070b8bb84|BA1_FT_C.264|BA1_FT_C.yuv|frames:299
            BA1_Sony_D|AVCv1|BA1_Sony_D.zip|399eae2eb03f82fc291bdc4fe9cedd110b40a96368ffe1e1bcf9ff6436fa9df7|BA1_Sony_D.jsv|BA1_Sony_D.yuv|frames:17
            BA2_Sony_F|AVCv1|BA2_Sony_F.zip|7ee27c0879fd43d3203cc14d5f2381e737bfb287fc6bdc4b64ff77f16c574397|BA2_Sony_F.jsv|BA2_Sony_F.yuv|frames:300
            BA3_SVA_C|AVCv1|BA3_SVA_C.zip|1c7003ff7b4507204f50e0b279149fd183c0890ee2a12c4c2d008db387299b82|BA3_SVA_C.264|BA3_SVA_C_rec.yuv|frames:33
            BAMQ1_JVC_C|AVCv1|BAMQ1_JVC_C.zip|4d37b02cd96ce57f91db306bcd29976ac26b88efbc1c8b3322101f7914378303|BAMQ1_JVC_C.264|BAMQ1_JVC_C.yuv|frames:30
            BAMQ2_JVC_C|AVCv1|BAMQ2_JVC_C.zip|eef3aba4a9c99a4ea61ec35aab77246a1c4ff3249588e1e3fb153e80bc05d256|BAMQ2_JVC_C.264|BAMQ2_JVC_C.yuv|frames:30
            BANM_MW_D|AVCv1|BANM_MW_D.zip|b50e2e7ca2a355087e9d993174626a027cea2da5f84f704cc0b2c6cb9145dfbd|BANM_MW_D.264|BANM_MW_D_rec.qcif|frames:100
            BASQP1_Sony_C|AVCv1|BASQP1_Sony_C.zip|f7cdbfaee43ff2e88b78d0b41ddcde83ac5148867e7b7df1759d97e0294f6c19|BASQP1_Sony_C.jsv|BASQP1_Sony_C.yuv|frames:4
            BA_MW_D|AVCv1|BA_MW_D.zip|f85d0c1e6a8317ecff19bf11d1496b327ee11e391de27453031062ea62241843|BA_MW_D.264|BA_MW_D_rec.qcif|frames:100
            CABA1_SVA_B|AVCv1|CABA1_SVA_B.zip|d0cf94006df2319a6c98683a6013f85ea5a1c0d382c796523b751c263e8c8989|CABA1_SVA_B.264|CABA1_SVA_B_rec.yuv|frames:17
            CABA1_Sony_D|AVCv1|CABA1_Sony_D.zip|9726f1c1012daef198c98810a88a7776a33159c1bc8daa6e557b55959db50ead|CABA1_Sony_D.jsv|CABA1_Sony_D.yuv|frames:50
            CABA2_SVA_B|AVCv1|CABA2_SVA_B.zip|68c700914c0926b5dcb75ef3fe601a3434c1de3af6c4906a3573baf9998752db|CABA2_SVA_B.264|CABA2_SVA_B_rec.yuv|frames:17
            CABA2_Sony_E|AVCv1|CABA2_Sony_E.zip|bfa6862fea1bd761d501ce4613d00ae08839dc9eda711e91b2ab214186e4578c|CABA2_Sony_E.jsv|CABA2_Sony_E.yuv|frames:300
            CABA3_SVA_B|AVCv1|CABA3_SVA_B.zip|375996d3de61bf6f7a58f48c46a477804636d118fe743501eef48c04fb3993cf|CABA3_SVA_B.264|CABA3_SVA_B_rec.yuv|frames:33
            CABA3_Sony_C|AVCv1|CABA3_Sony_C.zip|c3b61cff228f09847f17bb0d037f4275f82759565b3f19ab39f6cde482d90755|CABA3_Sony_C.jsv|CABA3_Sony_C.yuv|frames:300
            CABA3_TOSHIBA_E|AVCv1|CABA3_TOSHIBA_E.zip|7ed0d82ebd6fc96fe3b1b2daf0969360140833e180c8fb217e8c8ad817d40178|CABA3_TOSHIBA_E.264|CABA3_TOSHIBA_E_dec.yuv|frames:300
            CABACI3_Sony_B|AVCv1|CABACI3_Sony_B.zip|fcf6b713ec73a0035a1240b2951d8c98e9095e1ef03694f17afa5d896d752597|CABACI3_Sony_B.jsv|CABACI3_Sony_B.yuv|frames:300
            CABAST3_Sony_E|AVCv1|CABAST3_Sony_E.zip|8271a1756ccfd7b434f189e1aaa8144d3ca026531f4d2b58759ae7300c1c9d68|CABAST3_Sony_E.jsv|CABAST3_Sony_E.yuv|frames:25
            CABASTBR3_Sony_B|AVCv1|CABASTBR3_Sony_B.zip|6488ae24a721612d3dcbf5fbecafd3d3ae4896e7704b246a5bde2730e34074db|CABASTBR3_Sony_B.jsv|CABASTBR3_Sony_B.yuv|frames:25
            CABREF3_Sand_D|AVCv1|CABREF3_Sand_D.zip|11ff2b3d72eb815fe7651ebeeb3a835cc70a030f76495b31edc3aadd84bfa032|CABREF3_Sand_D.264|CABREF3_Sand_D.yuv|frames:50
            CACQP3_Sony_D|AVCv1|CACQP3_Sony_D.zip|c8efdbc5d0af62b99d1e357717c26eb34ed13f3cf8bacb04a60bf87a0bd2a054|CACQP3_Sony_D.jsv|CACQP3_Sony_D.yuv|frames:50
            CAFI1_SVA_C|AVCv1|CAFI1_SVA_C.zip|71c13765c64e02d81ae72cc2873150d45eda8172fd4ef7066b7bff03a2e84231|CAFI1_SVA_C.264|CAFI1_SVA_C_rec.yuv|frames:33
            CAMA1_Sony_C|AVCv1|CAMA1_Sony_C.zip|e2aaf99d934f60a5410d400696290fcc894fcb916c268703d61adade1ca854eb|CAMA1_Sony_C.jsv|CAMA1_Sony_C.yuv|frames:5
            CAMA1_TOSHIBA_B|AVCv1|CAMA1_TOSHIBA_B.zip|081f1bdd1d38238d55d0e4d2373df8641eef0db3290d4cacefb1d6a5d991b0c6|CAMA1_TOSHIBA_B.264|CAMA1_TOSHIBA_B_dec.yuv|frames:90
            CAMA3_Sand_E|AVCv1|CAMA3_Sand_E.zip|449a6591ff16a27dc7a6b05cc077a324598e348794998e46062003167915a6a9|CAMA3_Sand_E.264|CAMA3_Sand_E.yuv|frames:50
            CAMACI3_Sony_C|AVCv1|CAMACI3_Sony_C.zip|da020750950a89277ff10b53d5983ab525d61ba9b180562fc4fc1da9af721227|CAMACI3_Sony_C.jsv|CAMACI3_Sony_C.yuv|frames:17
            CAMANL1_TOSHIBA_B|AVCv1|CAMANL1_TOSHIBA_B.zip|9f9027280afb6d897144fa5f3851bfc471ee28bc37d8d23fc16ae3754d698608|CAMANL1_TOSHIBA_B.264|CAMANL1_TOSHIBA_B_dec.yuv|frames:90
            CAMANL2_TOSHIBA_B|AVCv1|CAMANL2_TOSHIBA_B.zip|cb04530ad98ea7d8a09e412462ea487b11f5a6ec7911fd882db7cbf7407e682f|CAMANL2_TOSHIBA_B.264|CAMANL2_TOSHIBA_B_dec.yuv|frames:90
            CAMANL3_Sand_E|AVCv1|CAMANL3_Sand_E.zip|09c6b842c801f2368cfe055fa20f33204e221a01e3ac4454fd8c656299bbf41a|CAMANL3_Sand_E.264|CAMANL3_Sand_E.yuv|frames:50
            CAMASL3_Sony_B|AVCv1|CAMASL3_Sony_B.zip|4e675b3701e5ed42c7f94c8d04ec755111a2bb595a759e512dc1361fb2f59ccd|CAMASL3_Sony_B.jsv|CAMASL3_Sony_B.yuv|frames:17
            CAMP_MOT_MBAFF_L30|AVCv1|CAMP_MOT_MBAFF_L30.zip|af27c0fa152615a648eb1989b1ef046f2713422f20405497ae863b4062b1f477|CAMP_MOT_MBAFF_L30.26l|CAMP_MOT_MBAFF_L30_rec.yuv|frames:30
            CAMP_MOT_MBAFF_L31|AVCv1|CAMP_MOT_MBAFF_L31.zip|7115ad1c7e69589cb0d216fc20c14f8fe9f4e4ef94ba151601c93b3f3af44f8f|CAMP_MOT_MBAFF_L31.26l|CAMP_MOT_MBAFF_L31_rec.yuv|frames:30
            CANL1_SVA_B|AVCv1|CANL1_SVA_B.zip|2f364e4ff117f9fce0b55ae417f2f5ee992d6e4042e207eed0a212b71580ae27|CANL1_SVA_B.264|CANL1_SVA_B_rec.yuv|frames:17
            CANL1_Sony_E|AVCv1|CANL1_Sony_E.zip|7703f187dba588c1b6df1385e15f4a58d87a8d34d492e819652e991acfd32eb2|CANL1_Sony_E.jsv|CANL1_Sony_E.yuv|frames:50
            CANL1_TOSHIBA_G|AVCv1|CANL1_TOSHIBA_G.zip|c554e995999afa117362ef0733910c3d2cb322f8ba8dc7b3f467239e8478bf46|CANL1_TOSHIBA_G.264|CANL1_TOSHIBA_G_dec.yuv|frames:300
            CANL2_SVA_B|AVCv1|CANL2_SVA_B.zip|0936f0816c5b44e0115c5dcea65717c678f3de8cf858308283f1328358733186|CANL2_SVA_B.264|CANL2_SVA_B_rec.yuv|frames:17
            CANL2_Sony_E|AVCv1|CANL2_Sony_E.zip|256039bbdfb6eee69f9bf1f20e696dc83ccea976c1f3df8c2a54b85342d2aca6|CANL2_Sony_E.jsv|CANL2_Sony_E.yuv|frames:300
            CANL3_SVA_B|AVCv1|CANL3_SVA_B.zip|2398eca7b8da95820ccb025b37c1103d8abd9f9a06eb45ccc3b9704ab1f2ff2e|CANL3_SVA_B.264|CANL3_SVA_B_rec.yuv|frames:17
            CANL3_Sony_C|AVCv1|CANL3_Sony_C.zip|4e2fbaa65c253bc05e028f068d652290afd2f679fd4969ed78eb89592bf9339f|CANL3_Sony_C.jsv|CANL3_Sony_C.yuv|frames:300
            CANL4_SVA_B|AVCv1|CANL4_SVA_B.zip|a19e259438917a68f026de4045eff25d2dfe416c081d215bca40901214617cab|CANL4_SVA_B.264|CANL4_SVA_B_rec.yuv|frames:33
            CANLMA2_Sony_C|AVCv1|CANLMA2_Sony_C.zip|b4c822b6fe6c3bea6be22b2bb6ea21b5964f4fc566f14a9df06d34671b6f5d31|CANLMA2_Sony_C.jsv|CANLMA2_Sony_C.yuv|frames:17
            CANLMA3_Sony_C|AVCv1|CANLMA3_Sony_C.zip|6c65593a84800ad92d5ee4169f3e33e3f74d56055e82ab309fe82a885f07ef1e|CANLMA3_Sony_C.jsv|CANLMA3_Sony_C.yuv|frames:17
            CAPA1_TOSHIBA_B|AVCv1|CAPA1_TOSHIBA_B.zip|57d4366805e18525b7f780dafdd0df7adaf09d4f84b742487a96d903384fa833|CAPA1_TOSHIBA_B.264|CAPA1_TOSHIBA_B_dec.yuv|frames:90
            CAPAMA3_Sand_F|AVCv1|CAPAMA3_Sand_F.zip|964118db0951061cb766b3df0e5518ff95f7f027adc78a447c760cc5bc91085c|CAPAMA3_Sand_F.264|CAPAMA3_Sand_F.yuv|frames:50
            CAPCM1_Sand_E|AVCv1|CAPCM1_Sand_E.zip|41fcf69567a11a6a80a1a966d64295b5c81568f4b69345e4c97e33d7cf7eeffe|CAPCM1_Sand_E.264|CAPCM1_Sand_E.yuv|frames:30
            CAPCMNL1_Sand_E|AVCv1|CAPCMNL1_Sand_E.zip|57869ee07c31339301af6c7f98e779ae27d048aadd180943b794dcc6e427c225|CAPCMNL1_Sand_E.264|CAPCMNL1_Sand_E.yuv|frames:30
            CAPM3_Sony_D|AVCv1|CAPM3_Sony_D.zip|61dbb73d5acc16a10f5f75dfa8a368a71f87d119d802f7c2bbebb5abcd32f075|CAPM3_Sony_D.jsv|CAPM3_Sony_D.yuv|frames:300
            CAQP1_Sony_B|AVCv1|CAQP1_Sony_B.zip|1b2055d67af3861da9fdb05ce140d79645b454f369fc62a41f64a9ce2b21e8a7|CAQP1_Sony_B.jsv|CAQP1_Sony_B.yuv|frames:50
            CAWP1_TOSHIBA_E|AVCv1|CAWP1_TOSHIBA_E.zip|f68cbc4590176c80a9652b2da9835f64d09a8613c976b345e5c2f2ed4b2e821a|CAWP1_TOSHIBA_E/CAWP1_TOSHIBA_E.264|CAWP1_TOSHIBA_E/CAWP1_TOSHIBA_E_dec.yuv|frames:90
            CAWP5_TOSHIBA_E|AVCv1|CAWP5_TOSHIBA_E.zip|f569e578b8d63d4e0a9e973cb87fac00a1ab0a1c5895a2336489140e3f595552|CAWP5_TOSHIBA_E/CAWP5_TOSHIBA_E.264|CAWP5_TOSHIBA_E/CAWP5_TOSHIBA_E_dec.yuv|frames:90
            CI1_FT_B|AVCv1|CI1_FT_B.zip|b44b82586b4be8232d77e7d0971691004f7aed5ef7d8730b20f81405fbc77d1a|CI1_FT_B.264|CI1_FT_B.yuv|frames:291
            CI_MW_D|AVCv1|CI_MW_D.zip|6e41825983037e3af50a3e4add6ac9dbde3ea6f41ab6f77cc432de22a2920d49|CI_MW_D.264|CI_MW_D_rec.qcif|frames:100
            CVBS3_Sony_C|AVCv1|CVBS3_Sony_C.zip|4cdb6b8d5346192094794b968590aa256c36d8dddcf22b2890e8f4a73f8d8b03|CVBS3_Sony_C.jsv|CVBS3_Sony_C.yuv|frames:300
            CVCANLMA2_Sony_C|AVCv1|CVCANLMA2_Sony_C.zip|9a579006f2935e75bb497814eda0abd5f49f740be40f3a81b643f8e3a63c7552|CVCANLMA2_Sony_C.jsv|CVCANLMA2_Sony_C.yuv|frames:17
            CVFC1_Sony_C|AVCv1|CVFC1_Sony_C.zip|99aa2f1501cd1ca8af1a292bb1f1f1cb49e6189a3131bc5bc8168b3dd322905a|CVFC1_Sony_C.jsv|CVFC1_Sony_C_nogray.yuv|frames:50
            CVFI1_SVA_C|AVCv1|CVFI1_SVA_C.zip|3f3da0c2ae0400e9549af1bea567ee0833db3141d39b2d10e7612f8c246029de|CVFI1_SVA_C.264|CVFI1_SVA_C_rec.yuv|frames:7
            CVFI1_Sony_D|AVCv1|CVFI1_Sony_D.zip|880545a19b8a9c002c26dabccfd679ebbea624bc7d1104d6a8aa653d96913f7b|CVFI1_Sony_D.jsv|CVFI1_Sony_D.yuv|frames:17
            CVFI2_SVA_C|AVCv1|CVFI2_SVA_C.zip|0c46afd124d9931ad349e9355e405f9f61b2f168223618691d023ab8e04a2877|CVFI2_SVA_C.264|CVFI2_SVA_C_rec.yuv|frames:13
            CVFI2_Sony_H|AVCv1|CVFI2_Sony_H.zip|4efe1e1022fe9464988a19f53abe5e0bb2f067dc417fa724f9f7f792cb8c4d51|CVFI2_Sony_H.jsv|CVFI2_Sony_H.yuv|frames:17
            CVMA1_Sony_D|AVCv1|CVMA1_Sony_D.zip|bf0090a609c27ca8938765cffd226910e4b31ddbe993e0c8c16b27b8130f5a6c|CVMA1_Sony_D.jsv|CVMA1_Sony_D.yuv|frames:5
            CVMA1_TOSHIBA_B|AVCv1|CVMA1_TOSHIBA_B.zip|87d71708f215a5b9146be6904ddc3d092118df7aead3c119994c203928c37924|CVMA1_TOSHIBA_B.264|CVMA1_TOSHIBA_B_dec.yuv|frames:90
            CVMANL1_TOSHIBA_B|AVCv1|CVMANL1_TOSHIBA_B.zip|fa180a01db3437eb22f75786509a7f675f812ba30660b2aefbeb58e31488e44a|CVMANL1_TOSHIBA_B.264|CVMANL1_TOSHIBA_B_dec.yuv|frames:90
            CVMANL2_TOSHIBA_B|AVCv1|CVMANL2_TOSHIBA_B.zip|39f70912ae4ee1087a8760e0a20db0008d063073345cb4536193112bf6a2062d|CVMANL2_TOSHIBA_B.264|CVMANL2_TOSHIBA_B_dec.yuv|frames:90
            CVMAPAQP3_Sony_E|AVCv1|CVMAPAQP3_Sony_E.zip|356fc1336cc704e8478c1fd19e3a9ea7235f642c9c72749f0eac82dc9a55e910|CVMAPAQP3_Sony_E.jsv|CVMAPAQP3_Sony_E.yuv|frames:8
            CVMAQP2_Sony_G|AVCv1|CVMAQP2_Sony_G.zip|7ba8640b584bcf5d96e942800b9f2e6a70d4fefa4c70768913c7cb559ddcd379|CVMAQP2_Sony_G.jsv|CVMAQP2_Sony_G.yuv|frames:17
            CVMAQP3_Sony_D|AVCv1|CVMAQP3_Sony_D.zip|867360dcec6f9da7572be6717e20182ed5ff7fb574ba665b0d22fce4fb106822|CVMAQP3_Sony_D.jsv|CVMAQP3_Sony_D.yuv|frames:17
            CVMP_MOT_FLD_L30_B|AVCv1|CVMP_MOT_FLD_L30_B.zip|6dca7c2a67fc166c76e1c17e37dc428f163f223d3c63abaf32f71cf4db60eecb|CVMP_MOT_FLD_L30_B.26l|CVMP_MOT_FLD_L30_B_rec.yuv|frames:30
            CVMP_MOT_FRM_L31_B|AVCv1|CVMP_MOT_FRM_L31_B.zip|3901a1df74ab2a10c2b184b78ada16d2207bb193035c3898f2493c170227875f|CVMP_MOT_FRM_L31_B.26l|CVMP_MOT_FRM_L31_B_rec.yuv|frames:30
            CVNLFI1_Sony_C|AVCv1|CVNLFI1_Sony_C.zip|7937f4626b09676e296db0f26c02ec38f2ad578d68a8b0cc528e8910baa1913a|CVNLFI1_Sony_C.jsv|CVNLFI1_Sony_C.yuv|frames:17
            CVNLFI2_Sony_H|AVCv1|CVNLFI2_Sony_H.zip|793c255c15db648ff4f7a77d826dd7d7c16200280f1478a0e014f4554cd73180|CVNLFI2_Sony_H.jsv|CVNLFI2_Sony_H.yuv|frames:17
            CVPA1_TOSHIBA_B|AVCv1|CVPA1_TOSHIBA_B.zip|6f349eb8fae64ed2c8e00ba8b8e194ef32b1ebc498f47c348687b6ac7da3c9b3|CVPA1_TOSHIBA_B.264|CVPA1_TOSHIBA_B_dec.yuv|frames:90
            CVPCMNL1_SVA_C|AVCv1|CVPCMNL1_SVA_C.zip|4d7d78a590acaf36f197d746c122281f3c0629abcb964471215b17e8c39f41d8|CVPCMNL1_SVA_C.264|CVPCMNL1_SVA_C_rec.yuv|frames:30
            CVPCMNL2_SVA_C|AVCv1|CVPCMNL2_SVA_C.zip|90b0574aa7d02f8b250c4997b78fd4f112983fd3eb9eb7c12df6d5b7560ceb1d|CVPCMNL2_SVA_C.264|CVPCMNL2_SVA_C_rec.yuv|frames:2
            CVSE2_Sony_B|AVCv1|CVSE2_Sony_B.zip|0d19398d5a8bc02d5df0906bd3d7c52e6341e992037eb67c73fc782852fbe17d|CVSE2_Sony_B.jsv|CVSE2_Sony_B-norpt.yuv|frames:278
            CVSE3_Sony_H|AVCv1|CVSE3_Sony_H.zip|8866d96f4531296bdf45f2f1ba30b54f8635255625fc42069fa5dd87f5e152d7|CVSE3_Sony_H.jsv|CVSE3_Sony_H-norpt.yuv|frames:278
            CVSEFDFT3_Sony_E|AVCv1|CVSEFDFT3_Sony_E.zip|b98a88904113f1ed59271168fbcdd54fec7bde63eaf869bdcada9fe4752be4b5|CVSEFDFT3_Sony_E.jsv|CVSEFDFT3_Sony_E-norpt.yuv|frames:200
            CVWP1_TOSHIBA_E|AVCv1|CVWP1_TOSHIBA_E.zip|4402349be955730a67401e218c6fd20874e1bba4f33ea5c5123e25a80682c6ba|CVWP1_TOSHIBA_E/CVWP1_TOSHIBA_E.264|CVWP1_TOSHIBA_E/CVWP1_TOSHIBA_E_dec.yuv|frames:90
            CVWP2_TOSHIBA_E|AVCv1|CVWP2_TOSHIBA_E.zip|65e5b208b67328a4fa442c34b456fe1c622fad77713d30837e0a25181c2ee087|CVWP2_TOSHIBA_E/CVWP2_TOSHIBA_E.264|CVWP2_TOSHIBA_E/CVWP2_TOSHIBA_E_dec.yuv|frames:90
            CVWP3_TOSHIBA_E|AVCv1|CVWP3_TOSHIBA_E.zip|b5e42b95fc7425eb80d0fabc27cfeec25cc528796e12f5612c8897977e4c3b56|CVWP3_TOSHIBA_E/CVWP3_TOSHIBA_E.264|CVWP3_TOSHIBA_E/CVWP3_TOSHIBA_E_dec.yuv|frames:90
            CVWP5_TOSHIBA_E|AVCv1|CVWP5_TOSHIBA_E.zip|498fd8557e2642927701a0e043e70baba4e25ab561214ef026064dade0645b2e|CVWP5_TOSHIBA_E/CVWP5_TOSHIBA_E.264|CVWP5_TOSHIBA_E/CVWP5_TOSHIBA_E_dec.yuv|frames:90
            FI1_Sony_E|AVCv1|FI1_Sony_E.zip|9ce036bcd91bf89aa6256b4d5461231e74f8482dcf76bed075abddbcf4108f9f|FI1_Sony_E.jsv|FI1_Sony_E.yuv|frames:17
            FM1_BT_B|AVCv1|FM1_BT_B.zip|9e8de6bc7e87eef28f603a089438eb50c7af542d3958253cbf2c1f1ef05e816f|FM1_BT_B.h264|FM1_BT_B.yuv|frames:400
            FM1_FT_E|AVCv1|FM1_FT_E.zip|6474ac25708195b297d7f284a7531be5f181c5a6e0f1e65f79ee8e2fdae3a9cb|FM1_FT_E.264|FM1_FT_E.yuv|frames:300
            FM2_SVA_C|AVCv1|FM2_SVA_C.zip|7e27a8776c70e47e4b4441809a6ec23c8a62e1a06eea364a6407839fc8650d4f|FM2_SVA_C.264|FM2_SVA_C_rec.yuv|frames:17
            HCBP1_HHI_A|AVCv1|HCBP1_HHI_A.zip|9038147c4300ae373c07c41d0a172ac593339978f36470a2561e6b4c5da65645|HCBP1_HHI_A.264|HCBP1_HHI_A.yuv|frames:250
            HCBP2_HHI_A|AVCv1|HCBP2_HHI_A.zip|9847f5d4941ac05c0736f9777398005d7fa72faea6f283a18e2fa72c8ea42659|HCBP2_HHI_A.264|HCBP2_HHI_A.yuv|frames:250
            HCMP1_HHI_A|AVCv1|HCMP1_HHI_A.zip|4dd7649e42c4e26e22e3877f3d0b30cd42dd3c456d97ee1c19a465322971221e|HCMP1_HHI_A.264|HCMP1_HHI_A.yuv|frames:250
            LS_SVA_D|AVCv1|LS_SVA_D.zip|b34a28852630abe770b680dbd7e0c619a01eacfffd2d928ab4aff3bc76746796|LS_SVA_D.264|LS_SVA_D_rec.yuv|frames:1700
            MIDR_MW_D|AVCv1|MIDR_MW_D.zip|69e7d04bd4b782f93401b12b79d58a96b39319d932743af8bc7134c752d6b577|MIDR_MW_D.264|MIDR_MW_D_rec.qcif|frames:100
            MPS_MW_A|AVCv1|MPS_MW_A.zip|dd96a4e890224097f4f7d8e4c3b47aba3bb986e86b6374d6a50af44453841575|MPS_MW_A.264|MPS_MW_A_rec.qcif|frames:150
            MR1_BT_A|AVCv1|MR1_BT_A.zip|a76d4e199e6ffb27292d1a07c516e5adea251e63e3f756de490c153b23d4684d|MR1_BT_A.h264|MR1_BT_A.yuv|frames:62
            MR1_MW_A|AVCv1|MR1_MW_A.zip|63171ed2251df09bee5560db87cc063e7a54f1602596734d03779a6239bc09d8|MR1_MW_A.264|MR1_MW_A_rec.qcif|frames:150
            MR2_MW_A|AVCv1|MR2_MW_A.zip|97e703fef42704e5e91dc96d92416790f38b4df0a3f97b02c138f9def4e87325|MR2_MW_A.264|MR2_MW_A_rec.qcif|frames:300
            MR2_TANDBERG_E|AVCv1|MR2_TANDBERG_E.zip|a1fef6ee3e9709360033cc5d0a1a39a36827c4a28f4fc9693904db5a87e5a374|MR2_TANDBERG_E.264|MR2_TANDBERG_E.yuv|frames:300
            MR3_TANDBERG_B|AVCv1|MR3_TANDBERG_B.zip|007ed664447935137a5789af31d6c6c37af2cae2b98b9cf047c936dde698f068|MR3_TANDBERG_B.264|MR3_TANDBERG_B.yuv|frames:300
            MR4_TANDBERG_C|AVCv1|MR4_TANDBERG_C.zip|a1012779b3d6a60a0459d1961025194c1c016ee31f012f5014c076de78b09ecb|MR4_TANDBERG_C.264|MR4_TANDBERG_C.yuv|frames:300
            MR5_TANDBERG_C|AVCv1|MR5_TANDBERG_C.zip|fb86b6f6512d14fbfe1a369e1cfa230a87a2e2d630fa7762dfc3b33e3b219865|MR5_TANDBERG_C.264|MR5_TANDBERG_C.yuv|frames:300
            MR6_BT_B|AVCv1|MR6_BT_B.zip|6d854331b7fefdc3c644bab4378a738bc0af6e981623e5a5b65b6401692f3310|MR6_BT_B.h264|MR6_BT_B.yuv|frames:60
            MR7_BT_B|AVCv1|MR7_BT_B.zip|020a5a3346634c5633def426c2c145a7ad64805d58c8dde94ebfac8c4c703046|MR7_BT_B.h264|MR7_BT_B.yuv|frames:60
            MR8_BT_B|AVCv1|MR8_BT_B.zip|2033f449755837336d7a5830dbfa8ccc36c1c84384d7f89d1cba738240395353|MR8_BT_B.h264|MR8_BT_B.yuv|frames:58
            MR9_BT_B|AVCv1|MR9_BT_B.zip|fd10a0be32f6fbe5d35e4a4b73707af97b3d8c1999bc5680c53d7068c20bf72e|MR9_BT_B.h264|MR9_BT_B.yuv|frames:58
            MV1_BRCM_D|AVCv1|MV1_BRCM_D.zip|6527786ffacbf00d917d3b54f56e8a16d11e46ac6806610685088d0ed0ee6b5b|src19td.IBP.264|src19td_rec.yuv|frames:257
            NL1_Sony_D|AVCv1|NL1_Sony_D.zip|1b26bfe8d05cf54b95e410779e48f90cc55305186e0c6184b5ea28ce9dc1b02d|NL1_Sony_D.jsv|NL1_Sony_D.yuv|frames:17
            NL2_Sony_H|AVCv1|NL2_Sony_H.zip|201e351e9c9680d514029cf4ec11a3009368d6955ec082cff9c244c6c03b818c|NL2_Sony_H.jsv|NL2_Sony_H.yuv|frames:300
            NL3_SVA_E|AVCv1|NL3_SVA_E.zip|09d7a3fcf430e0635b55bc4308c56842863cdc1d52cc3e3ec6014487d13c1977|NL3_SVA_E.264|NL3_SVA_E_rec.yuv|frames:33
            NLMQ1_JVC_C|AVCv1|NLMQ1_JVC_C.zip|079cebd9a12e9569164bffe91dd2ed830dc8cd25186652530d9a2622f5ddc398|NLMQ1_JVC_C.264|NLMQ1_JVC_C.yuv|frames:30
            NLMQ2_JVC_C|AVCv1|NLMQ2_JVC_C.zip|35ce821ebf67b4a4c706df90fc1a3a27a86b0d0fc3cd5fcc52624ef352b381f1|NLMQ2_JVC_C.264|NLMQ2_JVC_C.yuv|frames:30
            NRF_MW_E|AVCv1|NRF_MW_E.zip|c48f067fe8ac1309b99292d024324a0a7561bf28312a133995a42d7a98ae9835|NRF_MW_E.264|NRF_MW_E_rec.qcif|frames:100
            SL1_SVA_B|AVCv1|SL1_SVA_B.zip|60d61046c3aee6b9aa6b3dd4fad4081be9c959affec2bd641808632edffed1b2|SL1_SVA_B.264|SL1_SVA_B_rec.yuv|frames:33
            SP1_BT_A|AVCv1|SP1_BT_A.zip|aa9a1fa4fedad3d0437b8d77caefbf1b3cfb60add57b231dc67ebbded60d9d8a|sp1_bt_a.h264|sp1_bt_a.yuv|frames:400
            SVA_BA1_B|AVCv1|SVA_BA1_B.zip|1d19e5339d677ba6920912e7ca2d0a2889ff61fe0c4bbf0167da371fa855061b|SVA_BA1_B.264|SVA_BA1_B_rec.yuv|frames:17
            SVA_BA2_D|AVCv1|SVA_BA2_D.zip|e0cefee7e9206d06cad7bbdbe1441b6566208d693bcebc4396fa896be3a2c8d4|SVA_BA2_D.264|SVA_BA2_D_rec.yuv|frames:17
            SVA_Base_B|AVCv1|SVA_Base_B.zip|0ab8582893c671db242d2a645745a96b45bd4e479ccce8b0a6d989ee87e259a7|SVA_Base_B.264|SVA_Base_B_rec.yuv|frames:17
            SVA_CL1_E|AVCv1|SVA_CL1_E.zip|636098f10f2cdc80e31451b198c688bcc868fe536707c46ce2e0eaf54a33fa98|SVA_CL1_E.264|SVA_CL1_E_rec.yuv|frames:50
            SVA_FM1_E|AVCv1|SVA_FM1_E.zip|9db49088a67d960d1363605c40a603d38c87361f3b83e730e6541c5a1db4c10f|SVA_FM1_E.264|SVA_FM1_E_rec.yuv|frames:17
            SVA_NL1_B|AVCv1|SVA_NL1_B.zip|571504481df461f11c4f028df8754d06d1f5dc24171c571b9b4808b0cb595dbf|SVA_NL1_B.264|SVA_NL1_B_rec.yuv|frames:17
            SVA_NL2_E|AVCv1|SVA_NL2_E.zip|f21e06d253b205aa65e796158daecfbcfc4ea2b5c41af1412f6bdd54a0a59977|SVA_NL2_E.264|SVA_NL2_E_rec.yuv|frames:17
            Sharp_MP_Field_1_B|AVCv1|Sharp_MP_Field_1_B.zip|8beb25c4b8c93a0ad711a88f05688d530f7a4d43f37f2f7ad56e2b1bb652e4e0|Sharp_MP_Field_1_B.jvt|Sharp_MP_Field_1_rec_B.yuv|frames:15
            Sharp_MP_Field_2_B|AVCv1|Sharp_MP_Field_2_B.zip|bba6b48dc3f09d9b3fe8f274d196f3504247adbab4dd9d48e533985f83336221|Sharp_MP_Field_2_B.jvt|Sharp_MP_Field_2_rec_B.yuv|frames:15
            Sharp_MP_Field_3_B|AVCv1|Sharp_MP_Field_3_B.zip|d339172dc02bc4291cc2566e11e7f759b016095c0d3ee90c99ea7c16bd940ece|Sharp_MP_Field_3_B.jvt|Sharp_MP_Field_3_rec_B.yuv|frames:15
            Sharp_MP_PAFF_1r2|AVCv1|Sharp_MP_PAFF_1r2.zip|bb7c350423ed3b917fbb0410b95c1bf95af810f1208fea9036cfee0b84bc20b6|Sharp_MP_PAFF_1r2.jvt|Sharp_MP_PAFF_1r2_rec.yuv|frames:15
            Sharp_MP_PAFF_2r|AVCv1|Sharp_MP_PAFF_2r.zip|da4458eb33224792eb452d27e674267b72e78396a16130fa890ff8e646d2ef19|Sharp_MP_PAFF_2.jvt|Sharp_MP_PAFF_2_rec.yuv|frames:15
            cabac_mot_fld0_full|AVCv1|cabac_mot_fld0_full.zip|7e6df7be8558f42b1946100e406f89141f2bb5a9ecfb58b8a19f98beda047c34|camp_mot_fld0_full.26l|camp_mot_fld0_full_rec.yuv|frames:30
            cabac_mot_frm0_full|AVCv1|cabac_mot_frm0_full.zip|28ca7ebc897b4f7c95964dd5de882a83adecd048e7f5e2d039b5c4c78837a99a|camp_mot_frm0_full.26l|camp_mot_frm0_full_rec.yuv|frames:30
            cabac_mot_mbaff0_full|AVCv1|cabac_mot_mbaff0_full.zip|b6b51dbd19cbe3281e6ef6f36c84a26eda4a61f7794afd569dfdacd112273732|camp_mot_mbaff0_full.26l|camp_mot_mbaff0_full_rec.yuv|frames:30
            cabac_mot_picaff0_full|AVCv1|cabac_mot_picaff0_full.zip|0ad6d73cad2f9d9d998fffed6f5af46142c0a3e1518e2963d609e8eee8e36226|camp_mot_picaff0_full.26l|camp_mot_picaff0_full_rec.yuv|frames:30
            cama1_vtc_c|AVCv1|cama1_vtc_c.zip|afa7667a400f89367a6f585380ba19c843adaba72b8e272e8dbf9b7ffe80bcf4|cama1_vtc_c.avc|cama1_vtc_c_recon.yuv|frames:4
            cama2_vtc_b|AVCv1|cama2_vtc_b.zip|c5456e181479b40376ddce7ee7f86cd19e9d342cd1c17ea7853d85db401e3f3a|cama2_vtc_b.avc|cama2_vtc_b_recon.yuv|frames:4
            cama3_vtc_b|AVCv1|cama3_vtc_b.zip|bddfe943d4e698ccfeb874996965d2bea9b01c8557bc438cca28992cf19b3ce9|cama3_vtc_b.avc|cama3_vtc_b_recon.yuv|frames:4
            cavlc_mot_fld0_full_B|AVCv1|cavlc_mot_fld0_full_B.zip|ab77ca4cc5d868775109c10b568b38dccd3a419482664675b21c61d4128bcf16|cvmp_mot_fld0_full_B.26l|cvmp_mot_fld0_full_B_rec.yuv|frames:30
            cavlc_mot_frm0_full_B|AVCv1|cavlc_mot_frm0_full_B.zip|42ce5e512d90ac77ec51f88a45baaf02e288dbb4b7b1237cdd8b18779000bfab|cvmp_mot_frm0_full_B.26l|cvmp_mot_frm0_full_rec_B.yuv|frames:30
            cavlc_mot_mbaff0_full_B|AVCv1|cavlc_mot_mbaff0_full_B.zip|23da6f6f4ebd5ccb0e122422f55f8a2897d53bb8b4fdaa8f4ad03b24d4a6c92f|cvmp_mot_mbaff0_full_B.26l|cvmp_mot_mbaff0_full_B_rec.yuv|frames:30
            cavlc_mot_picaff0_full_B|AVCv1|cavlc_mot_picaff0_full_B.zip|0bc97dac5b4977da638ffa7ec285e226289f6935468e71b4760ceaeffa8a3de3|cvmp_mot_picaff0_full_B.26l|cvmp_mot_picaff0_full_B_rec.yuv|frames:30
            sp2_bt_b|AVCv1|sp2_bt_b.zip|61190770cec9376fe0ece9067670074570683f4547528d6a5fa6386af08a4b8a|H26L/BitstreamExchange/sp2_bt_b.h264|H26L/BitstreamExchange/sp2_bt_b.yuv|frames:400
            FREXT01_JVC_D|FRExt|FREXT01_JVC_D.zip|60803de47692a4199b72c16cefb89f604568a12d4574e6565deb05837f947ffe|FREXT01_JVC_D.264|FREXT01_JVC_D.yuv|frames:16
            FREXT02_JVC_C|FRExt|FREXT02_JVC_C.zip|df73856939fd1d1576233c5b8d98bd17f4286f0c0e0a4cae6a96ddb1fcedbc86|FREXT02_JVC_C.264|FREXT02_JVC_C.yuv|frames:16
            FREXT1_TANDBERG_A|FRExt|FREXT1_TANDBERG_A.zip|b90fa06d0b498dcda5a16ac81857270543e5d0614c62387e649a559eda775bc5|FREXT1_TANDBERG_A.264|FREXT1_TANDBERG_A.yuv|frames:5
            FREXT2_TANDBERG_A|FRExt|FREXT2_TANDBERG_A.zip|fc87fbab0b9a242d2b25528d687300fd313d511b1d50667695c3a610b61001da|FREXT2_TANDBERG_A.264|FREXT2_TANDBERG_A.yuv|frames:5
            FREXT3_TANDBERG_A|FRExt|FREXT3_TANDBERG_A.zip|0c90c5a21bf70af92f128586f9d18f4633e25f0a01b9aa2617d4f1267a8743b1|FREXT3_TANDBERG_A.264|FREXT3_TANDBERG_A.yuv|frames:5
            FRExt1_Panasonic_D|FRExt|FRExt1_Panasonic_D.zip|832ea7fae6fe6296c3f0377585a24cb3bf73fbfb51a3adbd852cf571a95ae16d|FRExt1_Panasonic.avc|FRExt1_Panasonic_rec.yuv|frames:8
            FRExt2_Panasonic_C|FRExt|FRExt2_Panasonic_C.zip|856a09639c813435f5ca3f8915af18107eb5d084f9b6549d0cfe0aef01472476|FRExt2_Panasonic.avc|FRExt2_Panasonic_rec.yuv|frames:15
            FRExt3_Panasonic_E|FRExt|FRExt3_Panasonic_E.zip|8449616e2244e7830e5256adb55ba9ac875b1c2c8f65b5b64e2ffa3f6326c68a|FRExt3_Panasonic.avc|FRExt3_Panasonic_rec.yuv|frames:11
            FRExt4_Panasonic_B|FRExt|FRExt4_Panasonic_B.zip|c406f93e4092d592c982bd30edec90f92debd4ececc1ecad12eb9437dacd7b9e|FRExt4_Panasonic.avc|FRExt4_Panasonic_rec.yuv|frames:10
            FRExt_MMCO4_Sony_B|FRExt|FRExt_MMCO4_Sony_B.zip|892be63d194dfbac15bbc0a4f57ff33d5420bfdd194e6423fff81304a10ea2ab|FRExt_MMCO4_Sony_B/FRExt_MMCO4_Sony_B.264|FRExt_MMCO4_Sony_B/FRExt_MMCO4_Sony_B.yuv|frames:60
            HCAFF1_HHI_B|FRExt|HCAFF1_HHI_B.zip|70b26364f98a21fddfe1daca09549985e3309d3c2146c3d75575587e9e60582d|HCAFF1_HHI.264|HCAFF1_HHI.yuv|frames:10
            HCAFR1_HHI_C|FRExt|HCAFR1_HHI_C.zip|d0d83d03aef6065715c0f91d1b31a6c445c1d4ae58aac2505f856a1a54962f94|HCAFR1_HHI.264|HCAFR1_HHI.yuv|frames:10
            HCAFR2_HHI_A|FRExt|HCAFR2_HHI_A.zip|53df88af90b976bb4b51ca7764571aa1f359d7f355ea402b7f17c78ea86caf38|HCAFR2_HHI.264|HCAFR2_HHI.yuv|frames:10
            HCAFR3_HHI_A|FRExt|HCAFR3_HHI_A.zip|e456f0abfbb93ce173960b9a49d23f926fbd038de3147d875906bcfb1fa3accf|HCAFR3_HHI.264|HCAFR3_HHI.yuv|frames:10
            HCAFR4_HHI_A|FRExt|HCAFR4_HHI_A.zip|7c24743a3d374cd2cd204129165fdb342f906ed750f7b235d53b57bc50350f94|HCAFR4_HHI.264|HCAFR4_HHI.yuv|frames:10
            HCAMFF1_HHI_B|FRExt|HCAMFF1_HHI_B.zip|086ccbc6aeee47c743eef58dde90335858ef25ec71526ed2e82cef5c71a235d5|HCAMFF1_HHI.264|HCAMFF1_HHI.yuv|frames:10
            HCHP1_HHI_B|FRExt|HCHP1_HHI_B.zip|4086bd59719b55d27ee3f247e04974988a06dd30d49cb0c10ca19c7bd8b7e1c6|HCHP1_HHI_B.264|HCHP1_HHI_B.yuv|frames:250
            HCHP2_HHI_A|FRExt|HCHP2_HHI_A.zip|f7eca062d9e84d55e0d42ecea9b886904d53a88c6d38420c408054f2255f534c|HCHP2_HHI_A.264|HCHP2_HHI_A.yuv|frames:250
            HCHP3_HHI_A|FRExt|HCHP3_HHI_A.zip|2c3012a0ac5bc704b20ce9369a0fb41bcceb723e2207ce637f549ded3476bc7f|HCHP3_HHI_A.264|HCHP3_HHI_A.yuv|frames:250
            HPCADQ_BRCM_B|FRExt|HPCADQ_BRCM_B.zip|008368755656a8613a79e42c9a1907b833da3a19030b4320e9cea930a850ccdc|HPCADQ_BRCM_B.264|HPCADQ_BRCM_B.yuv|frames:300
            HPCAFLNL_BRCM_C|FRExt|HPCAFLNL_BRCM_C.zip|527642aadf0d8e492240a9cd230fc2e985b31e1ef097cc3eea4e4c8a4184f014|HPCAFLNL_BRCM_C.264|HPCAFLNL_BRCM_C.yuv|frames:300
            HPCAFL_BRCM_C|FRExt|HPCAFL_BRCM_C.zip|f72207c406ec4461fe3197b5928f8095e8da1db1dd97bc8733f4faf82069abe5|HPCAFL_BRCM_C.264|HPCAFL_BRCM_C.yuv|frames:300
            HPCALQ_BRCM_B|FRExt|HPCALQ_BRCM_B.zip|078673f744f82756736712f719083cc4fd70e1e0263a486f3a816872d8c36638|HPCALQ_BRCM_B.264|HPCALQ_BRCM_B.yuv|frames:300
            HPCAMAPALQ_BRCM_B|FRExt|HPCAMAPALQ_BRCM_B.zip|0d29b5483dbe5801ddb6a0736e7213d5529cec7852973add7e62a05829e21634|HPCAMAPALQ_BRCM_B.264|HPCAMAPALQ_BRCM_B.yuv|frames:300
            HPCAMOLQ_BRCM_B|FRExt|HPCAMOLQ_BRCM_B.zip|5ca9d2b2b9f444821e97070461aa716e29d1c7090826bb61031b3c733ddd2010|HPCAMOLQ_BRCM_B.264|HPCAMOLQ_BRCM_B.YUV|frames:100
            HPCANL_BRCM_C|FRExt|HPCANL_BRCM_C.zip|6fca664ab926c5ae7021b0ed49e1a406aa2766339f8e93c0e78328f0e28e1735|HPCANL_BRCM_C.264|HPCANL_BRCM_C.yuv|frames:300
            HPCAQ2LQ_BRCM_B|FRExt|HPCAQ2LQ_BRCM_B.zip|62f9b0300d735c65f793ae41d7bb5118618182fe01d57b7333819efb48a42224|HPCAQ2LQ_BRCM_B.264|HPCAQ2LQ_BRCM_B.YUV|frames:100
            HPCA_BRCM_C|FRExt|HPCA_BRCM_C.zip|d4f9feef91a89f70611ae017bc18cccdc889f9aba8b54a1b559efb932edee5e3|HPCA_BRCM_C.264|HPCA_BRCM_C.yuv|frames:300
            HPCVFLNL_BRCM_A|FRExt|HPCVFLNL_BRCM_A.zip|de5586725eee3e33238aecf17079689db097320fa1dac1e66de7e5734e9e8265|HPCVFLNL_BRCM_A.264|HPCVFLNL_BRCM_A.YUV|frames:300
            HPCVFL_BRCM_A|FRExt|HPCVFL_BRCM_A.zip|b2a66b38e7a83436fd51227a2e87931dd3d4851b2337c05a2160b5c62bde9453|HPCVFL_BRCM_A.264|HPCVFL_BRCM_A.YUV|frames:300
            HPCVMOLQ_BRCM_B|FRExt|HPCVMOLQ_BRCM_B.zip|9dfb888137c79746474672c1284e7a2c3c3a5a5e37223c74ecdce1fb9fc122ce|HPCVMOLQ_BRCM_B.264|HPCVMOLQ_BRCM_B.YUV|frames:100
            HPCVNL_BRCM_A|FRExt|HPCVNL_BRCM_A.zip|c10d99f463417a75e0e21b7435f6c0755b2d332b214b0871b79de990d179a7c1|HPCVNL_BRCM_A.264|HPCVNL_BRCM_A.YUV|frames:300
            HPCV_BRCM_A|FRExt|HPCV_BRCM_A.zip|2d68dde9e6a533a77d1db84bdf75948eed99712e435857317952b3cf33c8e447|HPCV_BRCM_A.264|HPCV_BRCM_A.YUV|frames:300
            HVLCFI0_Sony_B|FRExt|HVLCFI0_Sony_B.zip|a0ed4a845b62cb61c439b3f9831ebc4bae0f46ceaf05a6837adead47f40a8740|HVLCFI0_Sony_B/HVLCFI0_Sony_B.264|HVLCFI0_Sony_B/HVLCFI0_Sony_B.yuv|frames:19
            HVLCMFF0_Sony_B|FRExt|HVLCMFF0_Sony_B.zip|9bfe4555d05cfa430cc67e5531214d3c976bfcaef6c5b7b2252cb5625466c632|HVLCMFF0_Sony_B/HVLCMFF0_Sony_B.264|HVLCMFF0_Sony_B/HVLCMFF0_Sony_B.yuv|frames:19
            HVLCPFF0_Sony_B|FRExt|HVLCPFF0_Sony_B.zip|b10e5552f91daebc896fc78a2a5ba8826d25cc5be9231c23bec3a36fbe7777ea|HVLCPFF0_Sony_B/HVLCPFF0_Sony_B.264|HVLCPFF0_Sony_B/HVLCPFF0_Sony_B.yuv|frames:19
            Hi422FR10_SONY_A|FRExt|Hi422FR10_SONY_A.zip|c5778e6b48561dcaad279f3a8e6b9a682847fbfad400c8156911c8035ada2b4f|Hi422FR10_SONY_B.264|Hi422FR10_SONY_B.yuv|frames:5
            Hi422FR11_SONY_A|FRExt|Hi422FR11_SONY_A.zip|5a3afdaf48161a8103baef615a5c9aec74bebe8ed484d1a130c0bf9abeb4a2df|Hi422FR11_SONY_B.264|Hi422FR11_SONY_B.yuv|frames:5
            Hi422FR12_SONY_A|FRExt|Hi422FR12_SONY_A.zip|b761bc972057bbff5b2f51d0bf28f117aea7007ba77cafffc3d96b9f4be39da3|Hi422FR12_SONY_B.264|Hi422FR12_SONY_B.yuv|frames:5
            Hi422FR13_SONY_A|FRExt|Hi422FR13_SONY_A.zip|84930d5376caed4f1e518fafff559aed25e2f1950a42818e0fd3b8e03e56a85a|Hi422FR13_SONY_B.264|Hi422FR13_SONY_B.yuv|frames16:5
            Hi422FR14_SONY_A|FRExt|Hi422FR14_SONY_A.zip|6dd967910fff318588e16b551337f252b059a4c4e144bd6352c8ea04a9aef5e0|Hi422FR14_SONY_B.264|Hi422FR14_SONY_B.yuv|frames16:5
            Hi422FR15_SONY_A|FRExt|Hi422FR15_SONY_A.zip|4b1eb69f2623789440eff01f1fbd6c5218985d70ff1e6c682df161debd17fcd2|Hi422FR15_SONY_B.264|Hi422FR15_SONY_B.yuv|frames16:5
            alphaconformanceG|FRExt|alphaconformanceG.zip|a0a853f1b280b5d86e9d9f0834dec150488faad31ab26fe59a7ca1fa74ca71cd|alphaconformanceG/alphaconformanceG.264|alphaconformanceG/test8b43.yuv|frames:40
            alphaconformanceG-alpha|FRExt|alphaconformanceG.zip|a0a853f1b280b5d86e9d9f0834dec150488faad31ab26fe59a7ca1fa74ca71cd|alphaconformanceG/test7a43.264patched|alphaconformanceG/test7a43.yuv|frames:43
            alphaconformanceG-foreground|FRExt|alphaconformanceG.zip|a0a853f1b280b5d86e9d9f0834dec150488faad31ab26fe59a7ca1fa74ca71cd|alphaconformanceG/test8b43.264|alphaconformanceG/test8b43.yuv|frames:43
            brcm_freh10|FRExt|brcm_freh10.zip|72126b95cade92e1e8b0589ce4bc8044846920a5a650cc6269c92b5c59c8af94|freh10.264|Freh10_dec.yuv|frames:100
            brcm_freh11|FRExt|brcm_freh11.zip|4a06c9c663fcff104d416c0dd740cdc24fe8c7adbbb63ec8e23dbe192b631b8d|freh11.264|Freh11_dec.yuv|frames:100
            brcm_freh3|FRExt|brcm_freh3.zip|6d90c062d80e872495ee1bc50ab3dd32f7e91555a7b322ce45c3ea7c71a01ef6|freh3.264|Freh3_dec.yuv|frames:100
            brcm_freh4|FRExt|brcm_freh4.zip|d5ea4b1e0d9b577e139b30de767418a84373c5d6533035e591be2f7cd6decddb|freh4.264|Freh4_dec.yuv|frames:100
            brcm_freh5|FRExt|brcm_freh5.zip|c80e426d0fc3bf7626acba0e04a3d8cea2f7af953f5c11ca8e8da254b25da3b1|freh5.264|Freh5_dec.yuv|frames:100
            brcm_freh6|FRExt|brcm_freh6.zip|6760d4447bc1b004074f06ebc630d3dd58fbbe7dc3851c43c88b80bb4e8bc4a2|freh6.264|Freh6_dec.yuv|frames:100
            brcm_freh8|FRExt|brcm_freh8.zip|00635d0975659751befcccee1f23baa3952639eeeebf5ba3521cba751502fc5b|freh8.264|Freh8_dec.yuv|frames:100
            brcm_freh9|FRExt|brcm_freh9.zip|2a7d460a697accc862fd519ed375d1bede1cf74b521e18316313f1a417463d4a|freh9.264|Freh9_dec.yuv|frames:100
            freh12_b|FRExt|freh12_b.zip|531557454ce0d13aaa94997121e64d60726eba144c7756d930be2c5df19ec104|Freh12_B.264|Freh12_B_dec.yuv|frames:100
            freh1_b|FRExt|freh1_b.zip|858632242f0c6f4b134c6a907682427ae09ac552c9a8bcc2d164b03896043326|Freh1_B.264|Freh1_B_dec.yuv|frames:100
            freh2_b|FRExt|freh2_b.zip|beaebac568a1dbefe0552d97e20773d29ce96a7eccd40f9d163f9b11a3eb012c|Freh2_B.264|Freh2_B_dec.yuv|frames:100
            freh7_b|FRExt|freh7_b.zip|e322ba08095d37b9afe0c612f5843649a5ff91efd30a3d1b6745dd110820ddc3|Freh7_B.264|Freh7_B_dec.yuv|frames:100
            """;

    record Vector(String name, String directory, String archive, String sha256, String bitstream, String reference,
                  String expectation) {
        @Override
        public String toString() {
            return name;
        }

        boolean wide() {
            return expectation.startsWith("frames16:");
        }

        int frames() {
            return Integer.parseInt(expectation.substring(expectation.indexOf(':') + 1));
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|", -1);
            result.add(new Vector(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]));
        }
        return result;
    }

    static Stream<Vector> decodable() {
        return vectors().stream();
    }

    static Stream<Vector> threaded() {
        return decodable().filter(vector -> vector.name().matches(
                "CABA3_Sony_C|CAPM3_Sony_D|MR4_TANDBERG_C|CVWP1_TOSHIBA_E|HCBP2_HHI_A|FRExt_MMCO4_Sony_B|HPCV_BRCM_A"
                        + "|freh12_b|MV1_BRCM_D|FM1_FT_E|CAPCM1_Sand_E|cabac_mot_frm0_full"
                        + "|CVPA1_TOSHIBA_B|CAPAMA3_Sand_F|CAMA3_Sand_E|CAMANL2_TOSHIBA_B|CVMP_MOT_FLD_L30_B"
                        + "|cabac_mot_mbaff0_full|cavlc_mot_picaff0_full_B|FRExt2_Panasonic_C|HCAMFF1_HHI_B|MR9_BT_B"
                        + "|SP1_BT_A|sp2_bt_b"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decodable")
    @DisplayName("decodes every frame bit-exactly")
    void bitExact(Vector vector) throws IOException {
        check(vector, 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threaded")
    @DisplayName("decodes bit-exactly with four frame threads")
    void bitExactThreaded(Vector vector) throws IOException {
        check(vector, 4);
    }

    static byte[] bitstream(Vector vector) throws IOException {
        var archive = Conformance.fetch("h264-" + vector.directory() + "-" + vector.archive(),
                BASE + vector.directory() + "/" + vector.archive(), vector.sha256());
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(vector.bitstream())).readAllBytes();
        }
    }

    static byte[] reference(Vector vector) throws IOException {
        var archive = Conformance.fetch("h264-" + vector.directory() + "-" + vector.archive(),
                BASE + vector.directory() + "/" + vector.archive(), vector.sha256());
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(vector.reference())).readAllBytes();
        }
    }

    static void check(Vector vector, int threads) throws IOException {
        var archive = Conformance.fetch("h264-" + vector.directory() + "-" + vector.archive(),
                BASE + vector.directory() + "/" + vector.archive(), vector.sha256());
        byte[] stream;
        byte[] reference;
        try (var zip = new ZipFile(archive.toFile())) {
            stream = zip.getInputStream(zip.getEntry(vector.bitstream())).readAllBytes();
            reference = zip.getInputStream(zip.getEntry(vector.reference())).readAllBytes();
        }
        var count = new int[1];
        var options = H264DecoderOptions.builder().threads(threads).highBitDepth(vector.wide()).build();
        try (var decoder = new H264Decoder(track(), options)) {
            java.util.function.Consumer<com.github.auties00.smalt.video.VideoFrame> sink = frame -> {
                if (vector.wide()) {
                    compareWide(vector, (Yuv16Picture) frame.picture(), reference, count[0]);
                } else {
                    compare(vector, (YuvPicture) frame.picture(), reference, count[0]);
                }
                count[0]++;
            };
            for (var nal : H264TestStreams.splitAnnexB(stream)) {
                decoder.decode(packet(nal), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(vector.frames(), count[0], "frame count of " + vector.name());
    }

    static void compare(Vector vector, YuvPicture picture, byte[] reference, int index) {
        var width = picture.width();
        var height = picture.height();
        var monochrome = picture.chromaFormat() == ChromaFormat.MONOCHROME;
        var chromaWidth = monochrome ? (width + 1) / 2 : picture.chromaWidth();
        var chromaHeight = monochrome ? (height + 1) / 2 : picture.chromaHeight();
        var frameSize = width * height + 2 * chromaWidth * chromaHeight;
        var base = (long) index * frameSize;
        if (base + frameSize > reference.length) {
            fail(vector.name() + ": decoder produced more frames than the reference holds");
        }
        var at = (int) base;
        for (var y = 0; y < height; y++) {
            var row = y * picture.yStride();
            for (var x = 0; x < width; x++) {
                if (picture.y()[row + x] != reference[at + y * width + x]) {
                    fail(vector.name() + ": frame " + index + " luma mismatch at " + x + "," + y);
                }
            }
        }
        at += width * height;
        for (var plane = 0; plane < 2; plane++) {
            for (var y = 0; y < chromaHeight; y++) {
                for (var x = 0; x < chromaWidth; x++) {
                    var expected = reference[at + plane * chromaWidth * chromaHeight + y * chromaWidth + x];
                    byte actual;
                    if (picture.chromaFormat() == ChromaFormat.MONOCHROME) {
                        actual = (byte) 128;
                    } else {
                        var samples = plane == 0 ? picture.u() : picture.v();
                        actual = samples[y * picture.uvStride() + x];
                    }
                    if (actual != expected) {
                        fail(vector.name() + ": frame " + index + " chroma " + plane + " mismatch at " + x + "," + y);
                    }
                }
            }
        }
    }

    /**
     * Compares a frame of 16-bit samples with a reference of 16-bit little-endian samples.
     */
    static void compareWide(Vector vector, Yuv16Picture picture, byte[] reference, int index) {
        var width = picture.width();
        var height = picture.height();
        var chromaWidth = picture.chromaWidth();
        var chromaHeight = picture.chromaHeight();
        var frameSize = 2 * (width * height + 2 * chromaWidth * chromaHeight);
        var base = (long) index * frameSize;
        if (base + frameSize > reference.length) {
            fail(vector.name() + ": decoder produced more frames than the reference holds");
        }
        var at = (int) base;
        short[][] planes = {picture.y(), picture.u(), picture.v()};
        for (var plane = 0; plane < 3; plane++) {
            var w = plane == 0 ? width : chromaWidth;
            var h = plane == 0 ? height : chromaHeight;
            var stride = plane == 0 ? picture.yStride() : picture.uvStride();
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var expected = (reference[at] & 0xFF) | (reference[at + 1] & 0xFF) << 8;
                    at += 2;
                    if (planes[plane][y * stride + x] != expected) {
                        fail(vector.name() + ": frame " + index + " plane " + plane + " mismatch at " + x + "," + y);
                    }
                }
            }
        }
    }

    static VideoTrack track() {
        return VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).build();
    }

    private static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
    }
}
