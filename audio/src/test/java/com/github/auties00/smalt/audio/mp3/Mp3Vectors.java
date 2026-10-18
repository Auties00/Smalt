package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared access to the ISO/IEC 11172-4 and 13818-4 compliance bitstreams (and the extra
 * robustness streams) of the minimp3 repository, pinned to one commit and verified by SHA-256.
 */
final class Mp3Vectors {
    static final String BASE = "https://raw.githubusercontent.com/lieff/minimp3/ea99364f61c14656440e8d77e9c233ccf3124633/vectors/";

    private static final String[] HASHES = {
            "ILL2_center2.bit", "a4b718b0d9e3c67fb85ed6f41504dd371630abffb3470fc5f49ec31012aa768e",
            "ILL2_center2.pcm", "5279bec8bada4ee3a23f22f19df30460247d0a985ffa0e88bb236815c5b63cd9",
            "ILL2_dual.bit", "f906f106c5fb5102e46f3d10f223d64dc7317abfd6cb83db5b6b0ccfcda66ec0",
            "ILL2_dual.pcm", "5279bec8bada4ee3a23f22f19df30460247d0a985ffa0e88bb236815c5b63cd9",
            "ILL2_dynx22.bit", "d2b787b9ed554064bff5554d6ee1777589148055cffa3acc89739189b67db272",
            "ILL2_dynx22.pcm", "0660d3ccc1a64e17e61ce31cd23d3d009eeb93110181709aa077b08885578fb5",
            "ILL2_dynx31.bit", "c11eea48f735831902f161183f5cc7721be53653ee8b1a99cc4af74cfd834cf2",
            "ILL2_dynx31.pcm", "3b2515ed18591593f9b47acca0e419cc4b32a37df8a99349d7d4e1adcc71f01d",
            "ILL2_dynx32.bit", "6c2d2a6fbdbc9d250c93b5ebffa89eee50f94c2d7a71935585ebdf81c1f64e7e",
            "ILL2_dynx32.pcm", "25747b197daf11879c0425088962138cc29c1d8190c41be8e0ff34bad0b6329e",
            "ILL2_ext_switching.bit", "d167d0d963138947c6f0d234b1bebed1eeee30b7b615ccfffd841627c3fd98b1",
            "ILL2_ext_switching.pcm", "c80e386aaf373323ac4e7017083bb99bcb6d713b3e78700361a43a76b98b5f5f",
            "ILL2_layer1.bit", "0de2908fb75400fa84f40a6939c1a188f475146980711eedb7c43cc79c867726",
            "ILL2_layer1.pcm", "42ee6309cfb601e78509cdd95dcf4f324e3a328533b153245101459b19b30d00",
            "ILL2_layer3.bit", "5af6ab144de30aa837b63608c850611b0b4ae40a640404130be324805f0f0337",
            "ILL2_layer3.pcm", "a54afbda4900d0f4583779f86a85de70b244b01a98bfb8a92460d91a338bb822",
            "ILL2_mono.bit", "2f222b098faef7653213887ac215c29668164a14212bae5495c30b1e8185582f",
            "ILL2_mono.pcm", "e7aa5cc93ba02fcf280da9de260a9cea9bebd78df10ebbb77264c7ac9339cac8",
            "ILL2_multilingual.bit", "04d29d0a7d5f85b90f66152b5bde0347b178f8e67be16b4129f87bd49699e298",
            "ILL2_multilingual.pcm", "6ee723332af3b4f00e5236c25887d76d47abc247cd8f747b7973a39488e72b32",
            "ILL2_overalloc1.bit", "d56e1807aa144654b149b0881977e662e992b1dfef9114674be0aaaf47469715",
            "ILL2_overalloc1.pcm", "1d78f2e6d395c5bb67c28fd3fd3a94e2740d32d9fc4ea1bb93d15dc31391a2e8",
            "ILL2_overalloc2.bit", "e04460a8a94dff43a3c6e399092612b43fb0241acfc7092fde71fb29a8878658",
            "ILL2_overalloc2.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "ILL2_prediction.bit", "bbea796739a42a03f56b9084bbcc82aefcd2cf2354570edbf8747b2fd0347857",
            "ILL2_prediction.pcm", "d89bf5682d5bd9486b01b500343fd851fc6259f5b7af0794caadd3b4b427e814",
            "ILL2_samples.bit", "d519b4827bed79044f2efe0d6b23b464b96246cf753e554b0590c9586509a49a",
            "ILL2_samples.pcm", "5279bec8bada4ee3a23f22f19df30460247d0a985ffa0e88bb236815c5b63cd9",
            "ILL2_scf63.bit", "d3bfaeec8f26bcb14ad826d0188b19d2f7d41e1caf3e0d1d6dba1aa42baeac94",
            "ILL2_scf63.pcm", "5279bec8bada4ee3a23f22f19df30460247d0a985ffa0e88bb236815c5b63cd9",
            "ILL2_tca21.bit", "c896abac0956816466710e97ebedff807edf735ac4997d06558887426b429b35",
            "ILL2_tca21.pcm", "aecd0ef9b318ff07ba4508c104d04ca0c298ea2fce2e2def76b68aeaf755a624",
            "ILL2_tca30.bit", "5e7b017d4db2026d8e90ce21c2016745038d7566fc949c266e7f2e9797af4fc9",
            "ILL2_tca30.pcm", "aecd0ef9b318ff07ba4508c104d04ca0c298ea2fce2e2def76b68aeaf755a624",
            "ILL2_tca30_PC.bit", "5c91b4a75ffc9e3a1a8be2db098dbf7b941f6c6d50407d863c07e0eae4e68bd8",
            "ILL2_tca30_PC.pcm", "6ae78af74f8c1f15fdf6bc2ae088380635a6e54f829d9546a51e8d61aea69845",
            "ILL2_tca31_PC.bit", "2affd276d4edf313a8f48892b9bbe82e2140f0b84cbbbe4ddbf12f0edb61348c",
            "ILL2_tca31_PC.pcm", "eb396c640c7cdb809cba8c0c8130560ba41338d6bf4a2f24766f3d3bfbf647a4",
            "ILL2_tca31_mtx0.bit", "4542635a76ab1e71fbb5314fea7515cd50879b7e0b9e4487f450f706f77adad5",
            "ILL2_tca31_mtx0.pcm", "4b39e5ab6f4233373c1c7959efcd687ef03da3ee10eae8d03d8c045543a2ae97",
            "ILL2_tca31_mtx2.bit", "a4848f478dd4010edc2845cf7fe94d5aa4ff8cd8718530458aaa49752c128422",
            "ILL2_tca31_mtx2.pcm", "d8924265a6594a317f844143fc103ee35c08544ac83ee3ccc6b00523df1b3775",
            "ILL2_tca32_PC.bit", "06ef407d0d5c6598875022180dac152252520b4a4bb2b382856e902a6f38908c",
            "ILL2_tca32_PC.pcm", "c7cb94618108d7c83374b2e411493eae6545a607550e4dd7a1b3fc946d2f01ba",
            "ILL2_wrongcrc.bit", "1cf3e6d063223d53cf368bbe7a8944fa382f0471e4293c87f65cdb2225f5050a",
            "ILL2_wrongcrc.pcm", "5279bec8bada4ee3a23f22f19df30460247d0a985ffa0e88bb236815c5b63cd9",
            "ILL4_ext_id1.bit", "2d86ea33ffdffbefa1b51db4ed3c2663033b118256b824b32a4ae7ac51dff323",
            "ILL4_ext_id1.pcm", "5279bec8bada4ee3a23f22f19df30460247d0a985ffa0e88bb236815c5b63cd9",
            "ILL4_sync.bit", "33524d5367f05ee4269da1e6600d2638bd1daa619282f81094ebeca6c0bddf5c",
            "ILL4_sync.pcm", "40a72759fc1a661e827924002303b730363552c22812b086d68b74faaf4f9216",
            "ILL4_wrong_length1.bit", "4fe3d97c152abd504b6181cca102d9761a71b34908258f7b9ffd0732773bfe7d",
            "ILL4_wrong_length1.pcm", "ecd996318963038b7b7fb9ee3fdc06d8e404d41a0f100246c77c7f088ff528e9",
            "ILL4_wrong_length2.bit", "6a4a5722a0450cfba6377ba4300e89d6b2c074e2bba977314717cd6fb54a0a63",
            "ILL4_wrong_length2.pcm", "7c64f73170984709104873882cbcca781e84ee4820f9369e356c298c62e8e9a8",
            "ILL4_wrongcrc.bit", "33524d5367f05ee4269da1e6600d2638bd1daa619282f81094ebeca6c0bddf5c",
            "ILL4_wrongcrc.pcm", "40a72759fc1a661e827924002303b730363552c22812b086d68b74faaf4f9216",
            "M2L3_bitrate_16_all.bit", "69577544aa28b87f6217ad2060f4cec3b1835593c659683ed8437429a74d3640",
            "M2L3_bitrate_16_all.pcm", "e575c8900be1f8949cfcd3e797ed512a3179ce7d36112ad01cea15a1be1e83be",
            "M2L3_bitrate_22_all.bit", "d636dc9e1108daac730fa23da1b45142c4cda610f0fd50d83cdab0a04264f876",
            "M2L3_bitrate_22_all.pcm", "0d9d5ba8a81dba7592a25d619e5c2474f2c87fac812c19245f66b864a6062afd",
            "M2L3_bitrate_24_all.bit", "0bb8dda0f56562889d157a2cd37478d58eb7f18df533ec1187d6fbc5b54f1e7a",
            "M2L3_bitrate_24_all.pcm", "ef4fa61d0a041de7ed27ce727c734b719a69871d456d8907c0d26b9fe42797df",
            "M2L3_compl24.bit", "856df786497a394091212086edbb77cc5f5c5572f0381f96be4273d2a7dccabf",
            "M2L3_compl24.pcm", "c8ffa0ee2d763a7ff0b7cdb9662867c4e86bf3a53923b28cf7dfdf947a0985ee",
            "M2L3_noise.bit", "d61b64a058f202a9d075f6c58a83b9ef88eb1aab2a7144c9ab6d788fe58fccec",
            "M2L3_noise.pcm", "b750f748429ee011ede3ed03eb08b42c54d513d6a755737e422c3d951f7cc43f",
            "l1-fl1.bit", "8bffe46c1a1ba709b35f4782c1a661d0081e8ef84222edcf518ee5e4c2e77f13",
            "l1-fl1.pcm", "ea5e871ffefa4fd110d7ba7bbad8b79b569a3e69a69bd6669607efbdf23319eb",
            "l1-fl2.bit", "319b47dd9f32ad723587b40b11994ee7f1a5e7a357c84fd574916f4f26d6efbc",
            "l1-fl2.pcm", "f539587943476b3b4d5f74a0e7e1f03f00115fda80393a009b6023d8db2401f8",
            "l1-fl3.bit", "1b77186bb7244aea1f259fed8fca17db7706eb3e1ddd904e564ca74db3ce7228",
            "l1-fl3.pcm", "b5af38c7fae7f39468bb74f31e941e1a38b47dea61c43d74ab50304bcb118490",
            "l1-fl4.bit", "9ad3be8edffd7563cc4b9dd1598ac66d0dbf6905f3e24784e07548c6088d971b",
            "l1-fl4.pcm", "b536704b47e22ebe9c64bf93e5fe3b85ace28dfd126828885b70e9c04671e5e1",
            "l1-fl5.bit", "910db322c5f78b679deca9dc26c4f0cf8de9547ffca478020fdde336771004d0",
            "l1-fl5.pcm", "c5c98cfcd6dc388ccaf355dfcfc503250b6cb6dc1936b597648a802659fd41d4",
            "l1-fl6.bit", "0cc8f30202d0f93a8fd3d97f59a62dfbf164fe03a36055fcc347016bf96802a3",
            "l1-fl6.pcm", "d6fb75a39947ee2d21bb02beca9c138f9dde19581ecdefda7f72f82952a056de",
            "l1-fl7.bit", "f78c974104a4493cfbbf2e134c631c6c218c7f5d58e830a8daf9ae90e9df44b4",
            "l1-fl7.pcm", "9f3b4da3bca6fb9dddaec8413c694b12df5045dfff8b60cfacbcdfcb3e0c5ff1",
            "l1-fl8.bit", "181761a26692ff235ce3471bacb41cefa3470fa6e3b77de6a40d02bf30cd1f9e",
            "l1-fl8.pcm", "5b3d2e7b74fa41f47b84fdb859300ed5282598edacfcc5ebf7837e161fbd664d",
            "l2-fl10.bit", "558341f604de32eb16e21b21cb9697f5371bd035055c5a40acc96a9482524a1b",
            "l2-fl10.pcm", "dd7788af5f3a6d26d7d6dfdf27aa81f1cc54739321e03262de00c147c5678ab5",
            "l2-fl11.bit", "e6af28cc7366d14a11b46e92cc0cbc06eba22b15e2b3b2d4fa34cc5288bb36ec",
            "l2-fl11.pcm", "30115595038e50bf565affb015f791ca33c850bb91c444b7d99e669030709aca",
            "l2-fl12.bit", "fd29728eaee1ac180636de641c58b1fda92e191d99a945283788fde9c00792fa",
            "l2-fl12.pcm", "be889663ea8b3329f25862a570b9260f6b14ef41a672c38d6e68051ddcbcc1f9",
            "l2-fl13.bit", "833af5ebf15e83fdd902f0d1b77286b177dba08cfac31cae0dd81270e01b7ce0",
            "l2-fl13.pcm", "a358ac3867fef3303eb4793822498e1252b19c0b7917cac343e98b7f6a5f2503",
            "l2-fl14.bit", "8acb4beffd38fb155c5bcc9aa167e686421c3654bf0ba75d736c0420ee9dbb0f",
            "l2-fl14.pcm", "a1c38bc6909ebfaf1c2cbe1eef5489eacaea791b2b17f73ee9a311846bc8f35f",
            "l2-fl15.bit", "e43a3578b125bc378c6b79ba9d66a4ae870ff52ad289967402b3b067036efdd1",
            "l2-fl15.pcm", "eaa421895151021139b83873de3024aa8ef9ed7dfcd2265e8998bf3e51074ce2",
            "l2-fl16.bit", "414cb87559646d6744d6d89f5e9aacee21d57934f71915b6a35acb9da7fdd4b2",
            "l2-fl16.pcm", "0e6b1a3fa0589c0284f4dbb56f7f7a1342bfea6d140a6bba7709baf922156183",
            "l2-nonstandard-fl1_fl2_ff.bit", "8b39cf95920e12d511b048914649478201bc415eee539420773f4c2724b5eadf",
            "l2-nonstandard-fl1_fl2_ff.pcm", "c6fcd56c9228d5836124aa61d20b9d6b64799b197316366d8e9d8b9bd4b76760",
            "l2-nonstandard-free_format.bit", "c00eaec0ee4a1cde84694dd8906c5d2c8c33acba4f243b0b6ae60a7aaa309ea5",
            "l2-nonstandard-free_format.pcm", "27442a21c21fa3c74d0dbe38b00f3b2df21513d192407513056d63adf1f6c9d7",
            "l2-nonstandard-test32-size.bit", "2bf8923ff890b06d7212e220e5f2b6ad6e9c246025f7105769c871d0f8a2c651",
            "l2-nonstandard-test32-size.pcm", "22c5e25bf47a0fab7aebf972d0871db048ab4132ff2694cdba0b4c07602bbd26",
            "l2-test32.bit", "726a86740cc00287561c9eb0a76a510276b7dd69127ff9782720fbb89d008749",
            "l2-test32.pcm", "30f61f5bed051604246607d3fccfe598070d083bec0c09371682ee1ec1987fb4",
            "l3-compl.bit", "3b93f3675821a5af4826abcff7097fa2e42efcc46154e02c992995f7e31dbc8e",
            "l3-compl.pcm", "b33610eba44b3c1c0eb2dbc90bd55aba8c57e82c71b133266780d7182e2d994e",
            "l3-he_32khz.bit", "965065cdcac3f45a61a9f54b4f94d59d0334f4b5cb74840a5aec7d405e2e050f",
            "l3-he_32khz.pcm", "61c5a85e3057905e47402bb85b36fadeba23be9a04b8090e42670eab621acf08",
            "l3-he_44khz.bit", "a64212281a8896d90e00f06c9b21f2c8f21ef9cb7367cae692a92ea421984441",
            "l3-he_44khz.pcm", "fa9a4f4ab1e4dd8b2d08211d8c20a35c08237a0615172e8572d4b43645a9db2c",
            "l3-he_48khz.bit", "dd0383075710570648b8961f3cd0ce692f07a679b265fc4997e50883b3958299",
            "l3-he_48khz.pcm", "61c5a85e3057905e47402bb85b36fadeba23be9a04b8090e42670eab621acf08",
            "l3-he_free.bit", "b55afc2a21492c4b3035d423f36fe87560ef34f65eb997da60af9b01f300362c",
            "l3-he_free.pcm", "9e89d9481c68261e070e575174c85739b630553ddc9f7feaf1c61605fb15b3d2",
            "l3-he_mode.bit", "fb90396d9bb13311be35284d061e19196830d213d823e05297b18172c88d90ce",
            "l3-he_mode.pcm", "12c1c72d0a99648698216a0014134259087838b3f3266cb19943ef78206317b0",
            "l3-hecommon.bit", "21999b77716aeba047c7f55c2c1ce17b54c780b6dc007912f251b7ea189676ef",
            "l3-hecommon.pcm", "5af4952d4451dc977b1344f836975e7545a66e38425de1764f3c28994cc38397",
            "l3-nonstandard-apetag.bit", "9d1d76cb5ea14ba9d5ed102c8dab386155c597e230f2692c6427cc51b287aeb2",
            "l3-nonstandard-apetag.pcm", "5f55938e8905e85e0b163c6db7cacd41f3ec1442f179f4ca3516203ef3756f9c",
            "l3-nonstandard-big-iscf.bit", "731c21eab0f289c80c7a21370ca58f64cda390bb795107222657cd16592a6e4b",
            "l3-nonstandard-compl-sideinfo-bigvalues.bit", "1cb83da481a4c744cbc5a2c4932929e955f0b72234dd73150dd86b0dd9d9d76f",
            "l3-nonstandard-compl-sideinfo-bigvalues.pcm", "55a0a3ce572a18bac4b4101d380dc7a52f60d74f5eb4a67c2d98c36aeef0b9d6",
            "l3-nonstandard-compl-sideinfo-blocktype.bit", "915ae8832581358c78d66d720038dba4edc85a41eea119098eb27ef4ebcc6c72",
            "l3-nonstandard-compl-sideinfo-blocktype.pcm", "00318845d9640237b0e5b20f378862cb987a52f10f84858aa4da61b0a1e0101a",
            "l3-nonstandard-compl-sideinfo-size.bit", "f2e981e6dff11bd0af4919340c36703a035be5fbe7988b266d1f3eec04e50788",
            "l3-nonstandard-compl-sideinfo-size.pcm", "adaa8ba0aa0cdaba49ae09e29763ecee8a89d8e5dba75a7f86decf87bb5da7b1",
            "l3-nonstandard-he_44_48khz.bit", "f1a554f6de8832cb064c47cd022fd76f826d51839c80c8b3d5d7c55d0db1fbcf",
            "l3-nonstandard-he_44_48khz.pcm", "ff8fdfd6303efec17fd802366afed20068a768792cb4fa713281aa6693411735",
            "l3-nonstandard-id3v1-apetag.bit", "c3b35b047bcba8bec560d464637e90479439ac4c4cc14d52958086d3edeadd20",
            "l3-nonstandard-id3v1-apetag.pcm", "5f55938e8905e85e0b163c6db7cacd41f3ec1442f179f4ca3516203ef3756f9c",
            "l3-nonstandard-id3v1.bit", "9e573fd0d56b0f4e7546029abb4b02ba086ae9731b2fe1e4886c9eca09dfb330",
            "l3-nonstandard-id3v1.pcm", "5f55938e8905e85e0b163c6db7cacd41f3ec1442f179f4ca3516203ef3756f9c",
            "l3-nonstandard-id3v2-only.bit", "a4926652d84fb6107b117b06e4416fb8917a80fdc0ca910a54f33fb8aa02463a",
            "l3-nonstandard-id3v2-only.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "l3-nonstandard-id3v2.bit", "fc053ef8ce0ab53951eab8f2063491a0bd1d93c2da80fe49d749fb105b4015e8",
            "l3-nonstandard-id3v2.pcm", "5f55938e8905e85e0b163c6db7cacd41f3ec1442f179f4ca3516203ef3756f9c",
            "l3-nonstandard-sideinfo-size.bit", "a1988fd67eb5a453c1ddf81d34e4784109e4f065674733a6dc2d90617ad77101",
            "l3-nonstandard-sideinfo-size.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "l3-nonstandard-sin1k0db_lame_vbrtag.bit", "c59cc31ed4cc3556f3c1aad35ec7860a56449a3a2ca16b6f29579f0ca7684b71",
            "l3-nonstandard-sin1k0db_lame_vbrtag.pcm", "ef62d3dc31f44589b539b5563e4206b4218cbdde301c95172e0b8b56ad8e3759",
            "l3-nonstandard-small.bit", "81db8ebbbbc69c6c6ad4a6aa92b76e0c08af547da236b9e2c9dbe1d8285a8130",
            "l3-nonstandard-small.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "l3-nonstandard-vbrtag-corrupted.bit", "7a4ba90fdb30232c1722b04b12169b10331f0e1b4bdbf2a48de8ed74548513cd",
            "l3-nonstandard-vbrtag-corrupted.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "l3-nonstandard-vbrtag-empty.bit", "ea9c2f207a3d8b468fcdb700df1069bc8d8640278fe8097277be69073463af9a",
            "l3-nonstandard-vbrtag-empty.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "l3-nonstandard-vbrtag-noframes.bit", "b55e70173b6eb60b5a73cc9de2f350bd11d4f1f4a34c43c44887d61d03f15384",
            "l3-nonstandard-vbrtag-noframes.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "l3-nonstandard-vbrtag-only.bit", "4fdd2a072ac506767c2985a0312a24da21e339046fc3e42cb44b5451e452b9bc",
            "l3-nonstandard-vbrtag-only.pcm", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "l3-nonstandard-vbrtag-oob-read.bit", "fdb5c22872880061e3d3e56e277e13e58e748a960ccc88f6f134a7db144f25f6",
            "l3-nonstandard-vbrtag-oob-read.pcm", "606f558e014930f9c1669f03c71c28945c4631568e39cd308c6c7f4077c7bfb9",
            "l3-si.bit", "4f3382de1d9265013d42249ff108023d4a90ecf3c0136489d199243a90a5d0f9",
            "l3-si.pcm", "331c1e57fd6161ee63eac62650337d5f73cbf70dcdd76e50a11acb8016d8669d",
            "l3-si_block.bit", "1d12956840638bf6b81164fc510c3e8269eeb038a51dc4548469e21a71e52b69",
            "l3-si_block.pcm", "7ed181c7dad40e7033d73303131345ac57b914fab9eb1ae8c00a263aa9b0b7cb",
            "l3-si_huff.bit", "fda6d8a7d4e1df3cb17d3560b57013ef98abbf9c60d940455c2e3ebf63eae0ef",
            "l3-si_huff.pcm", "468cd5d833fa3e017300128d3a80126412e6d77bdbdcca9e97abace6752cc0be",
            "l3-sin1k0db.bit", "64775e23c29aa84e27f68777460f88e0717af2ed64e732d287a20c5a92c9fa3e",
            "l3-sin1k0db.pcm", "4a31caa4ec395b4f4c8efbb1b7fe739f7390a83b78320d078ef53bcb84939e63",
            "l3-sin1k0db_ofs633.pcm", "9221ee8029be1eb123e074ec4c12f1717cc7656b1aaba2da7bd57ae81ed9505e",
            "l3-test45.bit", "384ccca2d558a3505bb2e7c2860e12aa3b651473ace655cda3a2c8cfb1b2fc53",
            "l3-test45.pcm", "bd79ae04a9437ffa9794df9540c119b45c89b29488c6b6584cc32c3ccde21fe0",
            "l3-test46.bit", "c44f835c6efbfad67359aa327d78f0feaf22c443e191d68f80e6d2fefd6d8af5",
            "l3-test46.pcm", "5038a9c90f640474261da37833d3ad9c172301938a8e75c06d39e7ef1610edf1"
    };

    static final Map<String, String> SHA256 = new HashMap<>();

    static {
        for (var i = 0; i < HASHES.length; i += 2) {
            SHA256.put(HASHES[i], HASHES[i + 1]);
        }
    }

    private Mp3Vectors() {
        throw new AssertionError();
    }

    static Path fetch(String name) {
        var hash = SHA256.get(name);
        if (hash == null) {
            throw new IllegalArgumentException("unknown vector " + name);
        }
        return Conformance.fetch("minimp3-" + name, BASE + name, hash);
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** Decodes a whole stream through the demuxer and decoder, untrimmed. */
    static AudioPipeline.Decoded decode(byte[] data) {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
            return AudioPipeline.decode(demuxer, Mp3Decoder::new);
        }
    }

    /** Converts float samples to 16-bit with round-half-even and saturation, like minimp3 on x86. */
    static short[] toS16(float[] samples) {
        var out = new short[samples.length];
        for (var i = 0; i < samples.length; i++) {
            var value = Math.rint(samples[i] * 32768.0);
            out[i] = (short) Math.max(-32768, Math.min(32767, value));
        }
        return out;
    }

    static short[] readPcm(byte[] data) {
        var out = new short[data.length / 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    /** Returns minimp3's PSNR over the common length (peak 0x7fff). */
    static double psnr(short[] expected, short[] actual) {
        var length = Math.min(expected.length, actual.length);
        double mse = 0;
        for (var i = 0; i < length; i++) {
            double difference = expected[i] - actual[i];
            mse += difference * difference;
        }
        mse /= Math.max(1, length);
        return mse == 0 ? 99.0 : 10 * Math.log10((double) 0x7fff * 0x7fff / mse);
    }

    static int maxDifference(short[] expected, short[] actual) {
        var length = Math.min(expected.length, actual.length);
        var max = 0;
        for (var i = 0; i < length; i++) {
            max = Math.max(max, Math.abs(expected[i] - actual[i]));
        }
        return max;
    }
}
