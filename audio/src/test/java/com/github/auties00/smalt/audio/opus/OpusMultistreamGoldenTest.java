package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamDecoder;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the multistream encoder is bit-exact with libopus 1.5.2: for each configuration the
 * SHA-256 of the packets and final ranges (in {@code opus_demo} bitstream form) must equal the hash
 * the reference C library produces ({@code opus_multistream_surround_encoder_create} and
 * {@code opus_multistream_encode_float}, or {@code opus_multistream_encode} for 16-bit input, float
 * build without SIMD or FMA contraction, the same controls and the same silence padding) on the
 * same input, and Smalt's multistream decoder must reproduce, sample for sample, the float output
 * the libopus multistream decoder gives for those packets.
 *
 * <p>{@code tools/testdata/opus-oracle} regenerates the reference hashes: its generator writes the
 * inputs below as 16-bit PCM with the same integer operations, and its harness also reproduces the
 * single-stream goldens of {@link OpusEncoderGoldenTest}, which validates the build. The inputs
 * mix the decoded RFC 8251 test vectors 03 (speech), 07 and 11 (music) with per-channel time
 * offsets, so the channels differ, and a 64-tap moving average of music as the LFE channel.
 */
class OpusMultistreamGoldenTest {
    /**
     * Name, input, rate, channels, family, application, bitrate, mode, complexity, frame us, signal,
     * max bandwidth, FEC, loss, DTX, 16-bit input, SHA-256 of the bitstream, SHA-256 of the libopus
     * decoder's float output for it.
     */
    static final String[] GOLDENS = {
            "s51_256 surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0 e1ebaeb1f1e31ebf68430981198ec385c948c0e68bcaf28df19dc270bf0f73d8 94ea1c44ab2b5b0cae6d0965f06ab25b2258ff824da9d9c1d5de8768bb1aa206",
            "s51_128cvbr surround6_4 48000 6 1 audio 128000 cvbr 10 20000 auto auto 0 0 0 0 3b0e73cc9e8033462c320cd4e410a2ee310cec2bbecf66e2e992e27d8057d8b6 346f40c504dec8811729afc1d62a7184c921c4ff82383bbaf25229727e83364c",
            "s51_96cbr surround6_4 48000 6 1 audio 96000 cbr 10 20000 auto auto 0 0 0 0 7dee94825973f3e1bd021edffdf0524781b10b49e08914daefccfc99bcc19370 4ec4e4d34cea14530e673c296d249107b3f8f21cb7f9a9709e55902ce87cb01b",
            "s51_64 surround6_4 48000 6 1 audio 64000 vbr 10 20000 auto auto 0 0 0 0 2370946c6a763bd7f801eac7821a064281698e370d4d130d25e8d6c4d2a196f3 52de0bce51723934766b3995548834b0eaedb068f16662dee0f7f504dd26bb4f",
            "s51_voip48 surround6_4 48000 6 1 voip 48000 vbr 10 20000 auto auto 0 0 0 0 9f49ebf68a826bf0cba6e86b277d57b9b4ef8ab226a9038831893d8b24051104 6348367403954ea75e29c407e7a93d36085f4181d488d80170bf41ae263111bb",
            "s51_voip32_fec surround6_4 48000 6 1 voip 32000 vbr 9 20000 voice auto 1 10 1 0 5909737b24e673782a51f9a1c7f29696b8f47f133341a2b0e0e095110369ba06 504f5cae149396138eb5c60c80d94bf6f157e4968bd17071230772f0a483bfd4",
            "s51_10ms surround6_4 48000 6 1 audio 192000 vbr 10 10000 auto auto 0 0 0 0 d6ad83cbd18c9a671401f983b5ed799454fc272a162f2f34998ac3ee11f22176 e0414a04575142d171ba09d40763a3653abff9a9d4a392925197adb568014808",
            "s51_2_5ms surround6_4 48000 6 1 audio 320000 vbr 10 2500 auto auto 0 0 0 0 1a5dada07ab06558759fdb8a64b6efc4b0fac9642d750f4e60c4e93856f945e0 78574f13b9972a79939fe33881810ae8942e8eb7cb169c7485cb4b5bbb0e7f91",
            "s51_40ms surround6_4 48000 6 1 audio 160000 vbr 10 40000 auto auto 0 0 0 0 f526e7775885da8c4f99d3f916ab6c7dc739831aaf3c083dab2bc5cd85447736 8d0db25ce75fb9754a8feb5118477fe6305b5d5e43c50e9df2c3864994fb94a5",
            "s51_60ms_cbr surround6_4 48000 6 1 audio 128000 cbr 10 60000 auto auto 0 0 0 0 59dd06135045f1c0c174c587d6910b166203194759eb4102fe13417df4cb90b4 91bd5a3100c07dd6558cf5e812447674b36afe8c1a302dd14c162f3591abee10",
            "s51_120ms surround6_4 48000 6 1 voip 64000 vbr 10 120000 auto auto 0 0 0 0 0df2c15e6b4938bce3f7db2c3a7ae656941e7482f4eab8da81fc0fc6550ac77f e9339ba18dc137a77ca6423ef5ce09b26e5527624f9ade515dc4221c193381be",
            "s51_100ms surround6_4 48000 6 1 audio 128000 vbr 10 100000 auto auto 0 0 0 0 5c903372654a8d01188ca6b972474418d0078a7eb19b9c8b6f3ccbed0b315bce ba0851ad79d3e28c0d7d974c27087675fba99156039d6f8e4240e76b26e93cdb",
            "s51_c5 surround6_4 48000 6 1 audio 192000 vbr 5 20000 auto auto 0 0 0 0 f8938c1869d2c5c224b104c83f9aaab1bb283e48aefbe38d0b1a16d5bdd451be 9562689c4017c2031a667c0d53be62b6e0e1228f2ae18d4f62f37a55344b1dc0",
            "s51_c0 surround6_4 48000 6 1 audio 192000 vbr 0 20000 auto auto 0 0 0 0 3eddf71f1d1ddab15a2c33c333c185406ba8e8e539d1a691163577d60294d5e2 ab7c3cd65dc757bee27e69e1f3c011efd6af16d4bda0e01b195223ff1b058d43",
            "s51_24k surround6_4 24000 6 1 audio 160000 vbr 10 20000 auto auto 0 0 0 0 f009178bdb8fbae6f98402c5d5eec732a5242e308ad0dd6eb14dcbd30636932f d35cdd00978cfc2b4ba47d184cd07ce9634937d942a1fe48c8191513517591a8",
            "s51_16k surround6_4 16000 6 1 voip 96000 vbr 10 20000 auto auto 0 0 0 0 dbfa0dece029603231dbe4f4a929b72966a4eea1cb84f9226122f953f8ae265c 0797650c9af9582c64c0f5db48ac65af3fee7970971be987f674eefb79afcea2",
            "s51_8k surround6_4 8000 6 1 voip 48000 vbr 10 20000 auto auto 0 0 0 0 813759f218dbc7b06d97f2b4bb7ca0744d163d8ba3542c27a6d6e6d780add7e5 e81c7c856ca641b6718c5c238eec5a49b0019a6a7dacf388b986b56ece29158e",
            "s51_lowdelay surround6_4 48000 6 1 lowdelay 256000 vbr 10 10000 auto auto 0 0 0 0 5523db24322979ab430823568d28e9f1f7594a633ddf68b9274fd7e38895a85a c80b0449bb3482b3ca6fe957c7321277dc1f0e36ac4f0031453de0d1beddf169",
            "s51_music surround6_4 48000 6 1 audio 192000 vbr 10 20000 music auto 0 0 0 0 b3c6bb5b3717b7de50088137fa5723be28d54105e1ad35c3d0a18372bab9c2d3 e596b29f464656a56ddc28d85a1dbbee66bbe8c82c76e155019293f1edc010ba",
            "d6_music_swb surround6_4 48000 6 255 audio 192000 vbr 10 20000 music swb 0 0 0 0 b6d103b7256727a9d3ee62ec600cb148aec899b6a484f9b4fe5dda6c2cdedd30 828b6fa019708ea6238f3b0a2765838a3f5827a4096edc2cdad565f04ab7fae3",
            "d4_wb_voip surround4_4 16000 4 255 voip 64000 vbr 10 20000 voice wb 0 0 0 0 b61ed43a3dbc02a7c049ef055faad9a79de762f4058b12e20e99be89214b1e00 e19367c7cdd3fe492270bf4239a0600d760672a8ea34ef7fdb4aa7670ba66a8c",
            "s51_s16 surround6_4 48000 6 1 audio 224000 vbr 10 20000 auto auto 0 0 0 1 715519a76ae7ed3707960faa675f7a777437a315f222d9be930d0ed23dace140 a9685086fd0f70972717aaad101effbead3164c38acf5c6bd3e09e27db1ea93b",
            "s71_320 surround8_4 48000 8 1 audio 320000 vbr 10 20000 auto auto 0 0 0 0 43b9bbe3e652bd804caf9bdcce6fe377580465f9d2ffd34ccccaef762a8c1cdd 61f9d3e65e889c00579fd4f5a203de227d2ae33031b83d202ef94bc50b668fe7",
            "s71_cbr160 surround8_4 48000 8 1 audio 160000 cbr 8 20000 auto auto 0 0 0 0 9eec5dacc3a7a76b355598f88f2348917e7efa89180d9354ad9c7c1f8af0e9a3 6de48275c9feba13b55b51f6b61271e302c768d00ac8a7f61e8469ebb7b8f2eb",
            "s71_cvbr96 surround8_4 48000 8 1 audio 96000 cvbr 10 20000 auto auto 0 0 0 0 2149808a6dbb7239ad1ff5c07b61948f83a0939af4d42d66ba3a07a53d5c7ca6 a8721ec0f2f88d5e1f6a4d81e2d15177ffff2d8d515349a58ed310a3f3826417",
            "s30 surround3_4 48000 3 1 audio 128000 vbr 10 20000 auto auto 0 0 0 0 f38836529e2c3470ffe3d027d7acdd09b6f47eacc7d7a45533b173eac66dc285 913a7b6a892c71c001573b463c159ce5675316ca7c0ea99288919cad777765ce",
            "s40 surround4_4 48000 4 1 audio 160000 vbr 10 20000 auto auto 0 0 0 0 68fb792eeb31283c1d74993142d0487a605e74d42f977584a7d7e2162bd86cc5 9f62833cc9f8f578df5049985dfb37e326a6c8b823b02516bdf73d34cab0571d",
            "s50 surround5_4 48000 5 1 audio 192000 vbr 10 20000 auto auto 0 0 0 0 fedaa7299cd2c1d784df5a61769c9063c44ae89f3ae182bb1168f173187e9e6c 7efbb3f02406145b983db7abfb1d4b3eff3292571543f1f5ad04536f4ee152ef",
            "s61 surround7_4 48000 7 1 audio 224000 vbr 10 20000 auto auto 0 0 0 0 2b4286c25a250e29d5cee5297ae43b7b5e0427fbfb673ac0929cf57c5014db54 7db27640e2d2e0447d223e289135ff3422cb05078e0c88472bf785d261d75d48",
            "v2 surround2_4 48000 2 1 audio 96000 vbr 10 20000 auto auto 0 0 0 0 32fa670e50f101a3683284544bc6d1f5e484d7d89a1c7db32ae7b29eea98c6f1 9b983601b118c231f28c0b2b7332d8b949fa01eb2d75379b89d9203657cc02f5",
            "v1 surround1_4 48000 1 1 audio 48000 vbr 10 20000 auto auto 0 0 0 0 79b616f83550867610d2e861739defe0faaa5ad608b98dc9ac181ddf2454d801 54417581746166316822ea7d452572b8e104991dfaad397b36f60366f4d0ba47",
            "d6 surround6_4 48000 6 255 audio 192000 vbr 10 20000 auto auto 0 0 0 0 125401daae299edc9ec325b507bb50dc164e2a84185c84c9be82b5c46593453f d9ab771cd8660e4bb79407fb73b34e08c535ae6e43dae3981898f0bc1ec4ff61",
            "d6_auto surround6_4 48000 6 255 audio auto vbr 10 20000 auto auto 0 0 0 0 710b87d84f335c0e25c6551a76936fe783df60c806fd97195b0639fc359f32c3 34a2247cc176828bcac119c23fab4a72105c426cddcbf700528fd32082fcc8a5",
            "d6_max surround6_4 48000 6 255 audio max vbr 10 20000 auto auto 0 0 0 0 1aa7ecfc04dc6f30628949d5511cb293bb94242f12c44cab692ce57d21fe640a e805e3a58401009c385eb32c30834b5b16cc564233997c48fca8102bedf06f56",
            "d6_cbr_auto surround6_4 48000 6 255 audio auto cbr 10 20000 auto auto 0 0 0 0 c27c9d87904ca86ffc12744fe1f3c74132e512b58451c71fabd3d56311b8c860 5cef5605e4cd05a629c24e1d846b608f3675fa58d6fe0ae03ee55b80b9eae418",
            "d2 surround2_4 48000 2 255 voip 48000 vbr 10 20000 auto auto 0 0 0 0 7b506ad48923babc82706663bc5e4cf3ea177d0bef23a4b96c3402892d5900c6 8699aebdb7279c4f10abb6a155cd907019208d2e58bace7fa92f6a133a7ae797",
            "d3_16k surround3_4 16000 3 255 voip 36000 vbr 10 20000 auto auto 0 0 0 0 1108915d5d64f6fd745e8b7df482ec1497d9f9469a9f9c3c5c2aeb421cae1648 4ffb03f53bbdc56efc1459b1a373b7f1c10d9d62a7001fada85f63736fb329bb",
            "amb4 surround4_4 48000 4 2 audio 128000 vbr 10 20000 auto auto 0 0 0 0 69474a4dfa8a813deabbf0ec28872add85763447c808c78e3ed7d5c426f04192 71c7b0197691a5054fdff69b003f2fd6f8f1daf1d45e3b32047f2fdcdb898ebe",
            "amb6 surround6_4_nolfe 48000 6 2 audio 160000 vbr 10 20000 auto auto 0 0 0 0 a8873f44aa6d105aa2818474a94c8b85ed23162382d425c2eee0bd887376c43b b7acc6bd47ab00d677431c1b4c623710dc8ba20ad5d2ea2c270512cb869aee71",
            "amb9_auto surround8_4_nolfe 48000 8 255 audio auto vbr 10 20000 auto auto 0 0 0 0 e640ad60c36baac5f2f08772b0c83711d2b34995666a87dc5aa39ddde6d058e0 664cd2da561fa12d1050316cc71057120d8a3c212c84a0527e2107890a7d7e53",
            "q51 quiet6_4 48000 6 1 audio 128000 vbr 10 20000 auto auto 0 0 0 0 80c28f0323d17b4a5a401eb9b0c9225965412e9bdb10fd2340b09481e15bc564 1269f68f78429d417eb1fb30822cba2f5272347fd348ce8cc07653d64259c481",
            "q51_s16 quiet6_4 48000 6 1 audio 128000 vbr 10 20000 auto auto 0 0 0 1 4123f5379bf749febdd582c79e3b80ed26790f324c4445ab8733b6f47b6d94f0 72e820ce6bcb6327ea49150f71c4e4f62b5107489aba318d5a0f132724432397",
            "qd6 quiet6_4 48000 6 255 audio 128000 vbr 10 20000 auto auto 0 0 0 0 778bab387e57fd8c3d0541898a3afad36dfda356741263b7f8d0102700ea55af a45ce8acce609e5659962d0560379528808e4931929b8ab25ea944c143f1af4e",
            "amb9_f2_auto amb9_2 48000 9 2 audio auto vbr 10 20000 auto auto 0 0 0 0 d307926d6c6b91be41f45a7959422452057c20a445d4b5e0121df27be9e6888f 6f21b0277ec4f230426114b4b48721bd24941ed4257809bc5b7a5ffd53e9506a",
            "amb11_f2_max amb11_2 48000 11 2 audio max vbr 10 20000 auto auto 0 0 0 0 99b2b4454dca8cb4539ef8dc9ea407da7a6515f81558ce83b451248fac7a3ba4 fcf9223209ec10c475246c4e0f8d0009f92b9e3e1fa405dc5a6e6b739effcd57",
            "amb4_f2_cbr amb4_4 48000 4 2 audio 96000 cbr 10 20000 auto auto 0 0 0 0 2b8dd8866e50009d2bd597a025ba1c5053bb7f861e7a3885ee146102604b6651 fb1dab2015e478448e00bc73f7c38da70fdeb7de6c42cbb64c51c25334e253b3",
            "amb9_f2_cbr_auto amb9_2 48000 9 2 audio auto cbr 10 20000 auto auto 0 0 0 0 f9ca0ab5dea643dbe551aec5aa89e14216a3a8815f82b972d270ed448da3b7f2 61761f8e9a75a5da3eb95a11d76122c881efebec1d251692975b461eb78e441f",
            "amb6_f2_voip_fec amb6_4 16000 6 2 voip 64000 vbr 10 20000 voice auto 1 10 1 0 9d1db4f17b1e62764181b88ac80a43e930ec1588607ca02ab04911f5b5f0562a 2a376a6a46e5b6cdba37dbe4c16955d65854e8788a321548907567f778e6a764",
            "amb4_f2_lowdelay amb4_4 48000 4 2 lowdelay 128000 vbr 10 5000 auto auto 0 0 0 0 cb55a6d7eda3cbb2194c51b1c9f77eb34b14ea046d2d005f4d4edf7138e65321 f1b1f20807425a8c98c9a5da39c24821f885120cf08cf42585cfa34335083e3e",
            "amb11_f2_s16_60ms amb11_2 48000 11 2 audio 200000 cvbr 10 60000 auto auto 0 0 0 1 277f667533763524d1134752bb9c63da9e1a438364de6a907a01d8b3dc6b1173 0179aa0102bdf82ad85eca8f73501c8c3857647ba4edd6773ab70d6478076769",
            "s51_12k surround6_4 12000 6 1 voip 64000 vbr 10 20000 auto auto 0 0 0 0 ad647627f4582b6ca0d4f605dd37b9c164c2d184ee18d2212e53fce1f7ae325e ceffafdc85520b74acb95d43d722a3bd4f58b15387000756975df8b5a313f791",
            "s71_100ms_cbr surround8_4 48000 8 1 audio 256000 cbr 10 100000 auto auto 0 0 0 0 f1ee30b4ac707af3fa431dff3288adafacbabf0a5264e347ce7711c707938a7b dfb05ee3bff70b84613b5173e193169f762c1561075fbdc84ec9e75af2bc94ef",
            "s51_cbr_2_5ms surround6_4 48000 6 1 audio 64000 cbr 10 2500 auto auto 0 0 0 0 79ce31da83aca0c7b08b0ceafd3453a6817b371b43d2beaa2056883d2c425f5b 3e942df98106a8c5b93f5fce0e65b0a7cbf683800e22da1c866798325ae9e200",
            "d6_cbr120 surround6_4 48000 6 255 audio 192000 cbr 10 120000 auto auto 0 0 0 0 20af228b5c092d17eead274190f03de49c149926a363d36385e3c20a36667ea6 30857d023f469844f4a3c3385fb319b1821f95d8d615548ea0271cc12858010c",
            "q51_dtx quiet6_4 48000 6 1 audio 96000 vbr 10 20000 auto auto 0 0 1 0 700f33aa8cfda07401b95747776439b77b96fc975794479c273a51d992697855 b8a1066e6395f2b90acb49bafa88a0983a7090d58f53f710f6c07a9f65ba7b1a",
            "s51_cvbr60_loss surround6_4 48000 6 1 audio 160000 cvbr 10 60000 auto auto 0 15 0 0 fd909fb39e440da80aaa4ac8b0f83ecc52207503b5a76f37223894043c8e82f4 3a1ea5d00c7d7470800136424b8ae6c783256d13dc7ab3bf75872b2fdee28a43",
    };

    /**
     * The SHA-256 of each input as interleaved little-endian 16-bit PCM, as the generator writes it.
     */
    private static final Map<String, String> INPUT_HASHES = Map.ofEntries(
            Map.entry("surround1_4", "de5ae0ec720a533e3d60eabb2478fab2ea78192f28b6b2ada5ee5118b344f936"),
            Map.entry("surround2_4", "befd973f8628fc32f2ca7d6e581e4a9d93073f255f3490d7cb9ab0390e1ef1a1"),
            Map.entry("surround3_4", "f33163d384283a59b853d882510a216562c63d3d8f3b2045c0f47332ddb842c2"),
            Map.entry("surround4_4", "c5cd6f6276f7716448e9edd71a701f5417aae61adeeeeebbeb6a0a6d6bce6c26"),
            Map.entry("surround5_4", "b6c3f95dd1be6d9cac62199d550b3f745f1a0020cd8c77fceae1ce6725b290f0"),
            Map.entry("surround6_4", "071d0fd04c400edcfa181353ce804379bcf8bab5fb6baeb379163f28a9853f23"),
            Map.entry("surround6_4_nolfe", "80f69b95a27446e96890d5552c8276833f64f024e5c41190531c5d4b262e087a"),
            Map.entry("surround7_4", "ece3d87ac57a14650b0d3073c177caa85a6f2f28c157c78ab434cb16fba12f14"),
            Map.entry("surround8_4", "93af79faa01a2d723e4b8ada1337c5da98428455f1e94c7edd2930a35da02b90"),
            Map.entry("surround8_4_nolfe", "b665797a089d60e9e1eb69561aa89f806b9d7b21eb3a963c5823e3a072072db5"),
            Map.entry("quiet6_4", "c09e6b509768f21a62a6a2ee600965c2fb26d887f9a940a4f045bd623eeea40f"),
            Map.entry("amb4_4", "697f44a1afe3da96781c7b2dd669cb7fc6a9bdb703e519e95e34fefcd8aeba1b"),
            Map.entry("amb6_4", "360675e45336dcf9980400ffd61b5ba2950ac66770005911123d801c739deb9f"),
            Map.entry("amb9_2", "771613e6228afff74a685bf910f6353c087dcb12701151a63c70d4d90a277adb"),
            Map.entry("amb11_2", "820f5da9b4d5b643616adab3b58a2afa7f0955ce0af62f3390e8682e45b83ac2"),
            Map.entry("amb16_2", "3578bd1d90c34dca335632ed3e56b1b4b3ebf5e7e4b0a1be5eb9f3465dc62969"),
            Map.entry("amb18_2", "14682c5082983d19438b20f8ff08df3af249f99648ec73c7248185f408af7269"),
            Map.entry("amb25_1", "1349d0330bfb05eeddb6a8d8d7c08e2857f67fe2d1117049d5411ed246514e25"),
            Map.entry("amb36_1", "d10e18c1de72e0de284b9e6d83406648dcc31ea99c9f09599322893a2131ae33"),
            Map.entry("amb38_1", "4cf2d0a29701f0ac864e842e7eb42487e2adb24ee62dbd7ddc1d1f7b54dc2702"));

    /**
     * The source of each surround channel in Vorbis order: test vector, side (0 left, 1 right) and
     * offset in frames.
     */
    private static final int[][] SOURCES = {
            {7, 0, 0}, {11, 0, 24000}, {7, 1, 0}, {11, 1, 72000}, {3, 0, 0}, {7, 0, 120000}, {11, 0, 168000}, {3, 1, 96000}
    };

    private static final Map<String, short[]> INPUTS = new ConcurrentHashMap<>();

    private static final Map<Integer, short[]> VECTORS = new ConcurrentHashMap<>();

    static Stream<String> goldens() {
        return Stream.of(GOLDENS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    @DisplayName("produces the same packets and final ranges as the libopus reference")
    void bitExact(String line) throws Exception {
        bitExact(line, 6 * 1275 + 12 + 2);
    }

    static Stream<String> variableGoldens() {
        return Stream.of(GOLDENS).filter(line -> !line.split(" ")[7].equals("cbr"));
    }

    // Below one maximal packet per stream the streams are coded one after the other, which must not
    // change a variable-bitrate stream whose packets stay far smaller.
    @ParameterizedTest(name = "{0}")
    @MethodSource("variableGoldens")
    @DisplayName("codes the streams one after the other with the same result when the packet space is smaller")
    void sequential(String line) throws Exception {
        bitExact(line, 6 * 1275 + 12 - 1);
    }

    private void bitExact(String line, int capacityPerStream) throws Exception {
        var f = line.split(" ");
        var input = input(f[1]);
        var rate = Integer.parseInt(f[2]);
        var channels = Integer.parseInt(f[3]);
        var encoder = new OpusMultistreamEncoder(rate, channels, Integer.parseInt(f[4]), switch (f[5]) {
            case "voip" -> OpusDefines.OPUS_APPLICATION_VOIP;
            case "audio" -> OpusDefines.OPUS_APPLICATION_AUDIO;
            default -> OpusDefines.OPUS_APPLICATION_RESTRICTED_LOWDELAY;
        });
        configure(encoder, f);
        var frame = (int) ((long) rate * Integer.parseInt(f[9]) / 1_000_000);
        var s16 = f[15].equals("1");
        var total = input.length / channels;
        var target = (long) total + encoder.lookahead();
        var floats = new float[frame * channels];
        var shorts = new short[frame * channels];
        var packet = new byte[encoder.streamCount() * capacityPerStream];
        var out = new ByteArrayOutputStream();
        var decoder = new OpusMultistreamDecoder(rate, channels, encoder.streamCount(), encoder.coupledStreamCount(),
                encoder.mapping());
        var planes = new float[channels][decoder.maxFrameSize()];
        var route = new int[channels];
        for (var c = 0; c < channels; c++) {
            route[c] = c;
        }
        var decodedDigest = MessageDigest.getInstance("SHA-256");
        var interleaved = ByteBuffer.allocate(4 * channels * decoder.maxFrameSize()).order(ByteOrder.LITTLE_ENDIAN);
        long coded = 0;
        long position = 0;
        var index = 0;
        while (coded < target) {
            for (var i = 0; i < frame * channels; i++) {
                var sample = position * channels + i;
                var value = sample < (long) total * channels ? input[(int) sample] : 0;
                shorts[i] = value;
                floats[i] = (1.0f / 32768) * value;
            }
            position += frame;
            var length = s16 ? encoder.encode(shorts, 0, frame, packet, 0, packet.length)
                    : encoder.encodeFloat(floats, 0, frame, packet, 0, packet.length);
            assertTrue(length > 0, "encode failed at packet " + index + ": " + length);
            writeInt(out, length);
            writeInt(out, encoder.finalRange());
            out.write(packet, 0, length);
            var samples = decoder.decode(packet, 0, length, planes, 0, route, decoder.maxFrameSize(), false, false);
            assertEquals(frame, samples, "decoded duration of packet " + index);
            assertEquals(encoder.finalRange(), decoder.finalRange(), "decoder final range of packet " + index);
            interleaved.clear();
            for (var i = 0; i < samples; i++) {
                for (var c = 0; c < channels; c++) {
                    interleaved.putFloat(planes[c][i]);
                }
            }
            decodedDigest.update(interleaved.array(), 0, interleaved.position());
            coded += frame;
            index++;
        }
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        assertEquals(f[16], digest, "bitstream of " + f[0]);
        assertEquals(f[17], HexFormat.of().formatHex(decodedDigest.digest()), "libopus decode of " + f[0]);
    }

    @ParameterizedTest(name = "{0} channels, family {1}")
    @org.junit.jupiter.params.provider.CsvSource({"0, 0", "256, 255", "3, 0", "9, 1", "0, 1", "2, 2", "5, 2", "228, 2",
            "49, 3", "1, 3", "6, 4", "4, 254"})
    @DisplayName("rejects a family that does not code the channel count, as opus_multistream_surround_encoder_init")
    void rejects(int channels, int family) {
        assertThrows(IllegalArgumentException.class,
                () -> new OpusMultistreamEncoder(48000, channels, family, OpusDefines.OPUS_APPLICATION_AUDIO));
    }

    @ParameterizedTest(name = "{0} channels, family {1}")
    @org.junit.jupiter.params.provider.CsvSource({"1, 0, 1, 0, 0", "2, 0, 1, 1, 0;1", "3, 1, 2, 1, 0;2;1",
            "6, 1, 4, 2, 0;4;1;2;3;5", "8, 1, 5, 3, 0;6;1;2;3;4;5;7", "4, 2, 4, 0, 0;1;2;3",
            "11, 2, 10, 1, 2;3;4;5;6;7;8;9;10;0;1", "3, 255, 3, 0, 0;1;2"})
    @DisplayName("lays out the streams and the mapping table as libopus")
    void layout(int channels, int family, int streams, int coupled, String table) {
        var encoder = new OpusMultistreamEncoder(48000, channels, family, OpusDefines.OPUS_APPLICATION_AUDIO);
        assertEquals(streams, encoder.streamCount());
        assertEquals(coupled, encoder.coupledStreamCount());
        assertArrayEquals(Stream.of(table.split(";")).mapToInt(Integer::parseInt).toArray(), encoder.mapping());
    }

    static Stream<String> publicGoldens() {
        return Stream.of(GOLDENS).filter(line -> {
            var f = line.split(" ");
            return !(f[4].equals("1") && Integer.parseInt(f[3]) <= 2);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicGoldens")
    @DisplayName("the public encoder emits the reference packets from planar input in any chunking")
    void publicEncoder(String line) throws Exception {
        var f = line.split(" ");
        var input = input(f[1]);
        var channels = Integer.parseInt(f[3]);
        var encoder = new OpusEncoder(options(f));
        if (f[6].equals("max")) {
            encoder.setMaximumBitrate();
        }
        var s16 = f[15].equals("1");
        var total = input.length / channels;
        var wave = waveOrder(f);
        var out = new ByteArrayOutputStream();
        if (s16) {
            var frame = encoder.options().frameSize();
            var interleaved = new short[frame * channels];
            var packet = new byte[encoder.streamCount() * 1500];
            long coded = 0;
            long target = (long) total + encoder.delay();
            while (coded < target) {
                for (var i = 0; i < frame; i++) {
                    for (var v = 0; v < channels; v++) {
                        var sample = (coded + i) * channels + v;
                        interleaved[i * channels + wave[v]] = sample < (long) total * channels ? input[(int) sample] : 0;
                    }
                }
                var length = encoder.encode(interleaved, 0, frame, packet, 0, packet.length);
                writeInt(out, length);
                writeInt(out, encoder.finalRange());
                out.write(packet, 0, length);
                coded += frame;
            }
        } else {
            var random = new java.util.Random(total);
            var position = 0;
            var packets = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
            java.util.function.Consumer<com.github.auties00.smalt.track.Packet> sink = packet -> {
                packets.add(packet);
                writeInt(out, packet.data().length);
                writeInt(out, encoder.finalRange());
                out.writeBytes(packet.data());
            };
            while (position < total) {
                var count = Math.min(total - position, 1 + random.nextInt(3000));
                var planes = new float[channels][count];
                for (var v = 0; v < channels; v++) {
                    for (var i = 0; i < count; i++) {
                        planes[wave[v]][i] = (1.0f / 32768) * input[(position + i) * channels + v];
                    }
                }
                encoder.encode(new com.github.auties00.smalt.audio.AudioBuffer(encoder.layout(),
                        encoder.sampleRate(), planes, count), sink);
                position += count;
            }
            encoder.flush(sink);
            for (var i = 0; i < packets.size(); i++) {
                assertEquals((long) i * encoder.frameSize(), packets.get(i).pts(), "timestamp of packet " + i);
            }
        }
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        assertEquals(f[16], digest, "public bitstream of " + f[0]);
    }

    /** Returns the public options of a golden line. */
    static OpusEncoderOptions options(String[] f) {
        var channels = Integer.parseInt(f[3]);
        var builder = OpusEncoderOptions.builder()
                .format(Integer.parseInt(f[2]), channels)
                .channelMapping(switch (f[4]) {
                    case "1" -> OpusChannelMapping.VORBIS;
                    case "2" -> OpusChannelMapping.AMBISONIC;
                    case "3" -> OpusChannelMapping.AMBISONIC_PROJECTION;
                    default -> OpusChannelMapping.DISCRETE;
                })
                .application(switch (f[5]) {
                    case "voip" -> OpusApplication.VOIP;
                    case "audio" -> OpusApplication.AUDIO;
                    default -> OpusApplication.RESTRICTED_LOWDELAY;
                })
                .bitrateMode(switch (f[7]) {
                    case "vbr" -> OpusBitrateMode.VBR;
                    case "cvbr" -> OpusBitrateMode.CONSTRAINED_VBR;
                    default -> OpusBitrateMode.CBR;
                })
                .complexity(Integer.parseInt(f[8]))
                .frameDurationMicros(Integer.parseInt(f[9]))
                .signal(switch (f[10]) {
                    case "voice" -> OpusSignal.VOICE;
                    case "music" -> OpusSignal.MUSIC;
                    default -> OpusSignal.AUTO;
                })
                .maxBandwidth(switch (f[11]) {
                    case "nb" -> OpusBandwidth.NARROWBAND;
                    case "mb" -> OpusBandwidth.MEDIUMBAND;
                    case "wb" -> OpusBandwidth.WIDEBAND;
                    case "swb" -> OpusBandwidth.SUPERWIDEBAND;
                    default -> OpusBandwidth.FULLBAND;
                })
                .inbandFec(f[12].equals("1"))
                .packetLossPercent(Integer.parseInt(f[13]))
                .dtx(f[14].equals("1"));
        if (!f[6].equals("auto") && !f[6].equals("max")) {
            builder.bitrate(Integer.parseInt(f[6]));
        }
        return builder.build();
    }

    /**
     * Returns the plane of the public layout each coded channel of a golden line feeds: the WAVE
     * position for the Vorbis mapping, the coded position otherwise.
     */
    static int[] waveOrder(String[] f) {
        var channels = Integer.parseInt(f[3]);
        var order = new int[channels];
        for (var v = 0; v < channels; v++) {
            order[v] = f[4].equals("1") ? com.github.auties00.smalt.internal.header.VorbisChannelOrder.waveIndex(channels, v) : v;
        }
        return order;
    }

    static void configure(OpusMultistreamEncoder encoder, String[] f) {
        encoder.setBitrate(switch (f[6]) {
            case "auto" -> OpusDefines.OPUS_AUTO;
            case "max" -> OpusDefines.OPUS_BITRATE_MAX;
            default -> Integer.parseInt(f[6]);
        });
        encoder.setVbr(!f[7].equals("cbr"));
        encoder.setVbrConstraint(f[7].equals("cvbr"));
        encoder.setComplexity(Integer.parseInt(f[8]));
        encoder.setSignal(switch (f[10]) {
            case "voice" -> OpusDefines.OPUS_SIGNAL_VOICE;
            case "music" -> OpusDefines.OPUS_SIGNAL_MUSIC;
            default -> OpusDefines.OPUS_AUTO;
        });
        switch (f[11]) {
            case "nb" -> encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_NARROWBAND);
            case "mb" -> encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_MEDIUMBAND);
            case "wb" -> encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_WIDEBAND);
            case "swb" -> encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_SUPERWIDEBAND);
            default -> {
            }
        }
        if (f[12].equals("1")) {
            encoder.setInbandFec(1);
        }
        if (!f[13].equals("0")) {
            encoder.setPacketLossPerc(Integer.parseInt(f[13]));
        }
        if (f[14].equals("1")) {
            encoder.setDtx(true);
        }
    }

    static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /** Returns one of the deterministic inputs, as interleaved 16-bit samples in Vorbis order. */
    static short[] input(String name) {
        return INPUTS.computeIfAbsent(name, OpusMultistreamGoldenTest::build);
    }

    private static short[] vector(int n) {
        return VECTORS.computeIfAbsent(n, k -> OpusVectors.readPcm16(
                OpusVectors.directory().resolve(String.format("testvector%02d.dec", k))));
    }

    private static short[] build(String name) {
        if (name.startsWith("amb")) {
            // Channel c: vector 07, 11 or 03, side (c / 3) % 2, 24000 c frames later, divided by 1 + isqrt(c).
            var parts = name.substring("amb".length()).split("_");
            var channels = Integer.parseInt(parts[0]);
            var frames = Integer.parseInt(parts[1]) * 48000;
            var out = new short[frames * channels];
            int[] vectors = {7, 11, 3};
            for (var c = 0; c < channels; c++) {
                var source = vector(vectors[c % 3]);
                var n = source.length / 2;
                var side = (c / 3) % 2;
                var divisor = 1 + (int) Math.sqrt(c);
                for (var i = 0; i < frames; i++) {
                    out[i * channels + c] = (short) Math.floorDiv(source[2 * ((i + 24000 * c) % n) + side], divisor);
                }
            }
            checkHash(name, out);
            return out;
        }
        if (name.startsWith("quiet")) {
            // The surround input 48 dB down, where the effective input depth changes the coding.
            var loud = build("surround" + name.substring("quiet".length()));
            var out = new short[loud.length];
            for (var i = 0; i < out.length; i++) {
                out[i] = (short) Math.floorDiv(loud[i], 256);
            }
            checkHash(name, out);
            return out;
        }
        var parts = name.substring("surround".length()).split("_");
        var channels = Integer.parseInt(parts[0]);
        var frames = Integer.parseInt(parts[1]) * 48000;
        var lfe = parts.length < 3;
        var lfeIndex = switch (channels) {
            case 6 -> 5;
            case 7 -> 6;
            case 8 -> 7;
            default -> -1;
        };
        var out = new short[frames * channels];
        for (var c = 0; c < channels; c++) {
            if (lfe && c == lfeIndex) {
                var source = vector(7);
                var n = source.length / 2;
                var acc = 0;
                for (var i = 0; i < frames; i++) {
                    acc += source[2 * (i % n)];
                    if (i >= 64) {
                        acc -= source[2 * ((i - 64) % n)];
                    }
                    out[i * channels + c] = (short) Math.floorDiv(acc, 64);
                }
                continue;
            }
            var source = vector(SOURCES[c][0]);
            var n = source.length / 2;
            for (var i = 0; i < frames; i++) {
                out[i * channels + c] = source[2 * ((i + SOURCES[c][2]) % n) + SOURCES[c][1]];
            }
        }
        checkHash(name, out);
        return out;
    }

    private static void checkHash(String name, short[] out) {
        var bytes = ByteBuffer.allocate(out.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(out);
        try {
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
            assertEquals(INPUT_HASHES.get(name), digest, "input " + name);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
