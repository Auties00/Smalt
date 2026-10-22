package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Pins the files of the OpenJPEG test data repository (uclouvain/openjpeg-data) the JPEG 2000
 * tests use: the ISO/IEC 15444-4 conformance codestreams and JP2 files with their reference
 * decodes, and the Kakadu and Richter conformance files.
 */
final class Jp2TestVectors {
    private static final String BASE = "https://raw.githubusercontent.com/uclouvain/openjpeg-data/"
            + "39524bd3a601d90ed8e0177559400d23945f96a9/";

    private static final Map<String, String> HASHES = Map.ofEntries(
            Map.entry("input/nonregression/htj2k/Bretagne1_ht.j2k", "fa82708024118771d58d90231e406ec40ad3eedf623927697c3659c123d89ec4"),
            Map.entry("input/nonregression/htj2k/Bretagne1_ht_lossy.j2k", "372c5b8c5f136c8cabca266d1af7bd8078c0fee14441e7dc850f228d28152d2d"),
            Map.entry("input/nonregression/htj2k/byte.jph", "87841874ed8f530babe1ea49aef3cbdf1a605f1984b9c06713fc9d9ca0fcd5a0"),
            Map.entry("input/nonregression/htj2k/byte_causal.jhc", "8d4a48d1cfff47420203283f3eb1b5c48f92dd1a6c23916a18934a44f154002f"),
            Map.entry("baseline/conformance/a1_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/a2_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/a3_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/a4_colr.ppm", "29130dfd0e970bea6735fafe35b8983be56f7bbe0b644f598db3b5d6e37039b6"),
            Map.entry("baseline/conformance/a5_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/a6_mono_colr_0.pgm", "050f7bb306619727606a3ca461001405441b7bc61eb12c50580b0a29d79bcf7f"),
            Map.entry("baseline/conformance/a6_mono_colr_1.pgm", "6bc429fb71fd0224247b091beca04171e8a85359ebc36521a8729f11c086417a"),
            Map.entry("baseline/conformance/a6_mono_colr_2.pgm", "e3cc74a05d5bb9ee2edc4589675e54446b48f7190b1192582669f80f7be54148"),
            Map.entry("baseline/conformance/a6_mono_colr_3.pgm", "82416e43e8edf30def88a82e051ae0faa5665a5e8108257f029e391c2253e065"),
            Map.entry("baseline/conformance/b1_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/b2_mono.ppm", "0d727fa0969d55b261a95933bcd27231822719f8ad2880c8913d86b44f0593da"),
            Map.entry("baseline/conformance/b3_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/c0p0_01.pgx", "268a50614b74bb375092d11aa21594069666568a73fe0b83f3ca9cfbda389139"),
            Map.entry("baseline/conformance/c0p0_02.pgx", "7d7924248477c4368eadd508234545acca9f790e5cd2f888651de44b0ae9e9f9"),
            Map.entry("baseline/conformance/c0p0_03r0.pgx", "29aa1042c71b3868bf607e4e3b29436604f512c392131925523a060554aea9f5"),
            Map.entry("baseline/conformance/c0p0_03r1.pgx", "3e3e2055b0b7783d16a339447a0dcc50d6ddb2381ac5d2769d3bd4206f30a68d"),
            Map.entry("baseline/conformance/c0p0_04.pgx", "3bd0ba5ab481a5415e7272fd03a5c8463ffc223c09b3954ead086c7556502cea"),
            Map.entry("baseline/conformance/c0p0_05.pgx", "008e9c31c4409706048d59d3dd9f79c87db0ea8c45eb49d53449c56c8919a2df"),
            Map.entry("baseline/conformance/c0p0_06.pgx", "2a2c37396620bc14f37c578c74b2894ecfeb5e20bd30dc67fc0e8db4bbfb592b"),
            Map.entry("baseline/conformance/c0p0_07.pgx", "55ee08494d259e5879aa4695d8c9d1afca4f4bb0be65bde61b55c7120d425d38"),
            Map.entry("baseline/conformance/c0p0_08.pgx", "ad58ffadc846fc3ce72d1280ef3f2300c3697bfa754c6eb292167f9f835716a6"),
            Map.entry("baseline/conformance/c0p0_09.pgx", "ce4dcf09fbd8076527f8d3ea83d9a6c23e24c8aa50f74ac7cd943695f59c875a"),
            Map.entry("baseline/conformance/c0p0_10.pgx", "c58b9eb2901a78519af6eec0946a38a9ebb01bc9bb429fb3ea7cc4fc7e9a0bfb"),
            Map.entry("baseline/conformance/c0p0_11.pgx", "4a4457e31466d9a78d332fea16237da57cd4b2f3d4fa6dee0074fa568b282765"),
            Map.entry("baseline/conformance/c0p0_12.pgx", "69a27310574c9849c6af457de29ba5eec34fd2b2037cd96e6ac7eca0a4fa40e9"),
            Map.entry("baseline/conformance/c0p0_13.pgx", "7f2bc12d19df4b2dd7b651c48d87c419ba8caa0cb7ec76424f672ba0ab53261c"),
            Map.entry("baseline/conformance/c0p0_14.pgx", "c556ab741f9b7d0a519830d9d609fd68366d7af200a9c2dd740e18218ced8fba"),
            Map.entry("baseline/conformance/c0p0_15r0.pgx", "12e00c98f9ad94527542d99c0763955614352ece12f22afa8e418c77fdcb9d35"),
            Map.entry("baseline/conformance/c0p0_15r1.pgx", "b95be5fbc4272595678af5955805ea073decb7bbb4e60708eaf41853c5c1311a"),
            Map.entry("baseline/conformance/c0p0_16.pgx", "48876e9905a149b70299072d8f148bf697589c6c5aa15b328af8566bb4daecf5"),
            Map.entry("baseline/conformance/c0p1_01.pgx", "00bdf8d02daa6eafb36a4563dd6c0f6125e2df75b2eb66f38b122a1c105849db"),
            Map.entry("baseline/conformance/c0p1_02.pgx", "23b2f1215dc6581c3f7014224f02f208ae7b05bc977300bc75a516160e4c7f27"),
            Map.entry("baseline/conformance/c0p1_03.pgx", "d3836ed021026ec9b5b13ad0d0cbc00446318134143a8dcba32ba6b5f6d78d6f"),
            Map.entry("baseline/conformance/c0p1_04r0.pgx", "63cc22c9b256116fae5976fdce1f7254f9743cba571dc2b4f5ddba1b11883a6a"),
            Map.entry("baseline/conformance/c0p1_04r3.pgx", "caddf11fa88f7ca8bc80ad11188203e69496d1437f7edb3930370b862cfd5489"),
            Map.entry("baseline/conformance/c0p1_05.pgx", "06595f8b3d171dbb5538bba8c9ba701e4e8e6c50d2208a28623d01a02d65a279"),
            Map.entry("baseline/conformance/c0p1_06.pgx", "25053fa746c9c552ab67b7da59b60384b6e1b8aba688d0314550beb5ba561f80"),
            Map.entry("baseline/conformance/c0p1_07.pgx", "ee0f0dd90be110241c60dbed84e03c94ff4f83d5cbdae603db9182f24cf5f8a4"),
            Map.entry("baseline/conformance/c1_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/c1p0_01_0.pgx", "d1c24494c873c6c6bf84021bcbbf4606ade7c1755f9bf4ee0c2fd999ce9ae211"),
            Map.entry("baseline/conformance/c1p0_02_0.pgx", "f8928b6d8d200c7dbc56e754c1826caf773d99e52c2c1bc4bbfdea7bc1e0d236"),
            Map.entry("baseline/conformance/c1p0_03_0.pgx", "edbdfc559b314d6ec3b3f75d94c50ba2b65040cf02ef0017a97ba92e9fedaa86"),
            Map.entry("baseline/conformance/c1p0_04_0.pgx", "51705f9c5e9a0abf2890a6d05d8e5cc509bc0dedc46a590acf04255318e25001"),
            Map.entry("baseline/conformance/c1p0_04_1.pgx", "9949ce4c13203132f6200926fec8c24ccb2381b8888ade607d9b73f403ee6c42"),
            Map.entry("baseline/conformance/c1p0_04_2.pgx", "81c8dda3f54ac601b0e9a4e18d7556ec69fddbb7997ae8aed75e3b512e6cbd60"),
            Map.entry("baseline/conformance/c1p0_05_0.pgx", "87122bc8f4a15c2b507d3d766c91b3fee54142c1935c0cdef43444a319f0a77f"),
            Map.entry("baseline/conformance/c1p0_05_1.pgx", "92876bda577d5ea7b2ba7c4c2c2a646251043f681f9e3cbf4f92260fac3ee3b7"),
            Map.entry("baseline/conformance/c1p0_05_2.pgx", "8183c2a7682d34b62cb09a747ab803207282f882590f5a533e61a9d6b1922511"),
            Map.entry("baseline/conformance/c1p0_05_3.pgx", "1d52fc3949c1110036d7f478f378a11bc7d91a2d02743d6c5eccde72e345cc6f"),
            Map.entry("baseline/conformance/c1p0_06_0.pgx", "8e4fe489e03fd5579f28c527bb966526a4cbc8bd2957277f9ef71f4d366ad286"),
            Map.entry("baseline/conformance/c1p0_06_1.pgx", "37cd7cbb59d8a8a6868d90b458fe7f1c1ca9cc8c3d1aaeb3279401cdac0a007f"),
            Map.entry("baseline/conformance/c1p0_06_2.pgx", "37da1bfb1f6fc90e65ecb62efab284daaf8828235e808bb3f1dcc819e242a87f"),
            Map.entry("baseline/conformance/c1p0_06_3.pgx", "e3a2ddfc7a37a16dde4c51d4bca43bc07fe65505d1d03986f9516713e6527f57"),
            Map.entry("baseline/conformance/c1p0_07_0.pgx", "2f59fff782b20c1648b68019ce6069960aac5efb2bf98a4df509ad4ba5a72b3b"),
            Map.entry("baseline/conformance/c1p0_07_1.pgx", "967181e82d33e3442160bf0ae3e5ffa6db99a21584e6736acb2719030ad1e412"),
            Map.entry("baseline/conformance/c1p0_07_2.pgx", "2d8d2ae1d2e33a486c3187206599944856b8a75525a165069042f4cfc295dc74"),
            Map.entry("baseline/conformance/c1p0_08_0.pgx", "69a2af90074593b007d50c96b1c5e4ecb1e591e265dc4f2ab5a230359ab2c87d"),
            Map.entry("baseline/conformance/c1p0_08_1.pgx", "fa2598b68dde7613b50caa1e5692dcecec934b33e2eb8327f44a274869bc3025"),
            Map.entry("baseline/conformance/c1p0_08_2.pgx", "7c3c05a5c5d4fe8efa55975e1b6d24869b3e1bf140db2b32b5dd14e42d65f77e"),
            Map.entry("baseline/conformance/c1p0_09_0.pgx", "174557fca48a83f7a474d2307984051326350266fec1acf623466e8f92bc8a29"),
            Map.entry("baseline/conformance/c1p0_10_0.pgx", "feee51c4c4519bd4ddbd53fb9e8186ddc95c4e64b42ffc9681d65d26172f76c4"),
            Map.entry("baseline/conformance/c1p0_10_1.pgx", "4857f0f3247bac8bef2354dc88727cece95bb5713cbe1e83c346601c8cd395d1"),
            Map.entry("baseline/conformance/c1p0_10_2.pgx", "460dede2e04d2be3f2c74334784b58bac59bd640c2f2fbad64489e788d2c511a"),
            Map.entry("baseline/conformance/c1p0_11_0.pgx", "2255812746edf02b988b0efefedf99ad2a9c1e01f2260a223490480d53e69c99"),
            Map.entry("baseline/conformance/c1p0_12_0.pgx", "2336f73b2596e62ce5fd46a09813c4e88f2b57eabdfa5178008f8ef6982de8bd"),
            Map.entry("baseline/conformance/c1p0_13_0.pgx", "a3250795aa7c31d0caf0a9ad706e21652060768b404210624e5dd695787dbc07"),
            Map.entry("baseline/conformance/c1p0_13_1.pgx", "ff079e7fe2ca5fd542f6b85bda838fa9f11428a59d7584f6523cb649cb8b79c7"),
            Map.entry("baseline/conformance/c1p0_13_2.pgx", "83d2079dcfaee85fcd75e8a23d6dd69686b966ba334d2c8ca9a25b2bbc7079b0"),
            Map.entry("baseline/conformance/c1p0_13_3.pgx", "d1bb447c46cf81b0673ff570eb5d5f940aeebd8e6fee68f21e061c6d5cb064ae"),
            Map.entry("baseline/conformance/c1p0_14_0.pgx", "c4e96524ccf8f0520e92f335dc4bf968c74187ab491660992fff4740e6a1cd63"),
            Map.entry("baseline/conformance/c1p0_14_1.pgx", "b1e1b60cda396c16803da500f8b3006a2bf7375f39f1c79cf79f827881bbd714"),
            Map.entry("baseline/conformance/c1p0_14_2.pgx", "c4e96524ccf8f0520e92f335dc4bf968c74187ab491660992fff4740e6a1cd63"),
            Map.entry("baseline/conformance/c1p0_15_0.pgx", "edbdfc559b314d6ec3b3f75d94c50ba2b65040cf02ef0017a97ba92e9fedaa86"),
            Map.entry("baseline/conformance/c1p0_16_0.pgx", "48876e9905a149b70299072d8f148bf697589c6c5aa15b328af8566bb4daecf5"),
            Map.entry("baseline/conformance/c1p1_01_0.pgx", "fd1730adccecdfa90c31e895ea58083670ccedbe6fe053b20c3bb7c7cf8fb96b"),
            Map.entry("baseline/conformance/c1p1_02_0.pgx", "0ed11a110b40f1f7174546decf075a12ba201e1d5bbba18dcfa638d6b320bc3f"),
            Map.entry("baseline/conformance/c1p1_02_1.pgx", "23551bc04b229de63344f7a7f4b0cc19d8512681ad3fbd5d7b3fbd55d23228dc"),
            Map.entry("baseline/conformance/c1p1_02_2.pgx", "46349e1ccb1df738b58ce7d0f706bd6c2937eb6457596e9ec9f858ed0d9b47a4"),
            Map.entry("baseline/conformance/c1p1_03_0.pgx", "416ff6c9b3d917ff20d3bd6405e224049137d04ed80dec9f8f42dfa070deb794"),
            Map.entry("baseline/conformance/c1p1_03_1.pgx", "9cfbc8ebb61d6447cc4fc98d8cca63016a288037a4609b7424685fd72a984ca1"),
            Map.entry("baseline/conformance/c1p1_03_2.pgx", "71fd301fb0da107998a8805a46faff754c14316d1544e4159778b43055b210a5"),
            Map.entry("baseline/conformance/c1p1_03_3.pgx", "1d52fc3949c1110036d7f478f378a11bc7d91a2d02743d6c5eccde72e345cc6f"),
            Map.entry("baseline/conformance/c1p1_04_0.pgx", "46208571732cee8d274ec11629da785c6358599a040ddeb3d5e8f0b76e38594a"),
            Map.entry("baseline/conformance/c1p1_05_0.pgx", "e60798e11ca7af7fba5af5805f0cdb0682d903f698b4f4fc3f2bceab56331ad0"),
            Map.entry("baseline/conformance/c1p1_05_1.pgx", "c9858b293f37c4c831cec4c7fc21cae83493b6f60995ddd8cfe67e5f7c85c1c1"),
            Map.entry("baseline/conformance/c1p1_05_2.pgx", "c9279428c9c879a590fe70e991ddc76ab41a9441b43c349020a1b8582fb2671a"),
            Map.entry("baseline/conformance/c1p1_06_0.pgx", "5f1fea232444c1afa6f1e06738a64910436d122f2fe69b0bf5ea59a55ac7f66e"),
            Map.entry("baseline/conformance/c1p1_06_1.pgx", "52acc2a2c4374dcdcdf4b795c430fef1e632e5fc35590401c7c0a618935f6749"),
            Map.entry("baseline/conformance/c1p1_06_2.pgx", "01cb2817388ee05f1f4524b0420f86580e62e098ac17680f18ec53cf7f470fc0"),
            Map.entry("baseline/conformance/c1p1_07_0.pgx", "ee0f0dd90be110241c60dbed84e03c94ff4f83d5cbdae603db9182f24cf5f8a4"),
            Map.entry("baseline/conformance/c1p1_07_1.pgx", "b72f60a12ec7dcb1360cc9adfd86bfb2e81d929262df5e0167d00c02e61b4df4"),
            Map.entry("baseline/conformance/c2_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/d1_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/d2_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/e1_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/e2_colr.ppm", "8a0fb6d7a60514931d2cde618e4a07069d756a4f06202e09f145d123a8116b3c"),
            Map.entry("baseline/conformance/f1_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/f2_mono.ppm", "be285266a1c2f803f40b2d7deb7a449956a00882c4923fc4d26a988696081c7f"),
            Map.entry("baseline/conformance/g1_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/g2_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/g3_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/g4_colr.ppm", "22d53aea0bd3005724f08f0fad01e20716bea433fe5d2befa8d3466ce1404145"),
            Map.entry("baseline/conformance/jp2_1.tif", "1d88f16d47e304affde8fdb3a593a18b328dfa28c04884ac6c889cdefa154d19"),
            Map.entry("baseline/conformance/jp2_2.tif", "9850a8d54b227dcb931f43c96e3522fcf106629c60e9126381f3b39009c40c91"),
            Map.entry("baseline/conformance/jp2_3.tif", "512a8827b98d71051c3cba52b96a323e879870ba90dc254016befaa1aa90dbd5"),
            Map.entry("baseline/conformance/jp2_4.tif", "97395ed8d03798ef25ab7e53d904c288a37c8a07d1510ec1e897cdd923cf8eb1"),
            Map.entry("baseline/conformance/jp2_5.tif", "434b199ca176bb87de22d3e7413bf791602a7d0c21056745fb052d395dd52751"),
            Map.entry("baseline/conformance/jp2_6.tif", "927541305c4f46e1eb43e5e551260bfee8b0c395b5ffba95a77483b0216bd2c6"),
            Map.entry("baseline/conformance/jp2_7.tif", "65972c102d9277fbc901fbc60ee2253542f665b558c4548f986834a5488d6ed8"),
            Map.entry("baseline/conformance/jp2_8.tif", "4f0187ac2cf3ab95739f642cafcf96fcb1f42d0de9a0ce92464d7b10f80d4b31"),
            Map.entry("baseline/conformance/jp2_9.tif", "f092ba8dcffe34f289dbb258de2c3c22eb1eab7bd511096eacf812c29c2883fb"),
            Map.entry("baseline/conformance/subsampling_1.ppm", "0ee70bf85f17cd754ff75244fdfca4ecdb8c5b01297d558386643c66dfca41fd"),
            Map.entry("baseline/conformance/subsampling_2.ppm", "e03e1d852c97ea688748390052cad7a462928a887d55a1ab40d7492cb29745e8"),
            Map.entry("baseline/conformance/zoo1.ppm", "b7037f22411c21d40633c7b0683cbed7e088b28a7ff37369f9c1d4c759259bcc"),
            Map.entry("baseline/conformance/zoo2.ppm", "bc8de03193f66cb4b9dbec88968a20bae9f5c27568e061360981adf838fecf22"),
            Map.entry("input/conformance/a1_mono.j2c", "fcdef2e91367a6e2e94849e7fdc818f186eb9685d6e7cdeab6d49d4399c8064a"),
            Map.entry("input/conformance/a2_colr.j2c", "fb9f75203ec45fa88bcb98f583e086a42c172da272a9038fc538d6a568a73a12"),
            Map.entry("input/conformance/a3_mono.j2c", "35e2a38ebfad1a1f626e0684a0021ee9397e853100619be26e51bcb6b6fc7ee6"),
            Map.entry("input/conformance/a4_colr.j2c", "558c23bda95e902dc879f0cecbc2a67f524678217096d875618c3ffd1ddc2fec"),
            Map.entry("input/conformance/a5_mono.j2c", "778236f572254390472d6271879c71a0d6508fb850fe86b919dc36753859e0e0"),
            Map.entry("input/conformance/a6_mono_colr.j2c", "0991410ac3588b922ec8b72362b1149a63e8b117be76298eb5378696757968d4"),
            Map.entry("input/conformance/b1_mono.j2c", "e4d59f0bf721bb9d0878f7d15a858e44f1c0d7b19449a6f7cdca2fb3cd586826"),
            Map.entry("input/conformance/b2_mono.j2c", "f77e0269a1ff7028a116efcab5a97becfccc7e85bb5d58e1493bbf363971ded4"),
            Map.entry("input/conformance/b3_mono.j2c", "58a74bd5e1c395d56aee9bf34e3c3a21ea1a8d6789c6d50da2a7f848f44d86fd"),
            Map.entry("input/conformance/c1_mono.j2c", "7ed01bd12e2123579b3177200162c9f6b684dc9ee92585cc4f89d2a7cd9e9b50"),
            Map.entry("input/conformance/c2_mono.j2c", "488b87bd0849c565016a163f23627ba51f63236a77d2bdcf42e48c088a6b4afd"),
            Map.entry("input/conformance/d1_colr.j2c", "2ae646d5ac862a8d242a16791cdcee7d09b7014611c0ad0b3b6e9d86e0e3db09"),
            Map.entry("input/conformance/d2_colr.j2c", "93d15b6eb0e13bf8dbda4426c15eb7fe2cb2b7949cb7d562ee316a8bbd9415cc"),
            Map.entry("input/conformance/e1_colr.j2c", "a85d660414998c4390e61004a7c8d9d788b93e9f98210a2e0ad068b960ea535d"),
            Map.entry("input/conformance/e2_colr.j2c", "dd990146bd92a4ade81f26f4a7187a83251755745779a64965ca8de06d1c84eb"),
            Map.entry("input/conformance/f1_mono.j2c", "0311a126c25e5c9631d1bf634dd9ddd6d90da24e03be6c186a78eed71a1d04c3"),
            Map.entry("input/conformance/f2_mono.j2c", "0c9a9ff964f58629544d81e8405091f7393dcc304ff067212500635ebb63e3f6"),
            Map.entry("input/conformance/file1.jp2", "4e3d51df7bc66cf367162acfff88b0889d2b2c79ea8d99d93b2d2bd165398deb"),
            Map.entry("input/conformance/file2.jp2", "c87b78e7c3298ddb5e2c4b9a27485ae52ed0906184ae4eecf13b7525ad160960"),
            Map.entry("input/conformance/file3.jp2", "fe922461d6928f9b9c86c222a133c42c19d119351400d5e8dd6a1e60db437e66"),
            Map.entry("input/conformance/file4.jp2", "b3474f23bcf622f4f82c8ede5c473a4a1bae1c287b713fbd8447442e37edcee0"),
            Map.entry("input/conformance/file5.jp2", "b47d7d162d6f31b7b159b6df8a60c24146f6893d14a4043a570c6ece99c063d3"),
            Map.entry("input/conformance/file6.jp2", "78ae553dc97b22352a2417aba739160e3b6c5b187b2efb867fb50f9814f1bc67"),
            Map.entry("input/conformance/file7.jp2", "c9e6c845fde3db494035b9be8728239da45791b3269687a043a1fcedd8d11dbf"),
            Map.entry("input/conformance/file8.jp2", "81e8a8bae1e3d7e632091a120a8a6650a04636b2276a550889b153290348eb01"),
            Map.entry("input/conformance/file9.jp2", "6dfea011b013b62353fedcce9520e7c061dbbe8bf0c06d9edad4ac8889274b91"),
            Map.entry("input/conformance/g1_colr.j2c", "e292d2317f4bc4fb444073e9ae77f95ea59e376d2c2c54a991049209274d07de"),
            Map.entry("input/conformance/g2_colr.j2c", "b59354b8bcccd0cb85a22e77fe2ea0e213c508228354a9935f45e9a100af1971"),
            Map.entry("input/conformance/g3_colr.j2c", "9109b94831477e76dec18723ac1163a40992b7e6080f7cc46f81764716277c29"),
            Map.entry("input/conformance/g4_colr.j2c", "b4f779dbfbe7252e6fc07eee3bf180e12a63cdec67e462408f729cb7f966a68d"),
            Map.entry("input/conformance/p0_01.j2k", "a61ea8d21ad0f7f9b76796e0f841c3e125240f83e308e3b8d626f4356b9a1113"),
            Map.entry("input/conformance/p0_02.j2k", "150c8554b827c25b48029f3455b43ce3c029a26bea06eea2c60076c868bb07be"),
            Map.entry("input/conformance/p0_03.j2k", "0aa26db75e8414d554e1fa93a0c7e101f16bb203f1fe06983fdd0c01fa61ddee"),
            Map.entry("input/conformance/p0_04.j2k", "3d35a4eeffb00e150bb69611afec80fba9570d5fca18a04c4e644badfd052c6f"),
            Map.entry("input/conformance/p0_05.j2k", "35f3840030802898d5ff2fc6ff39eea3b8080cad65db859830b869d48579cf9f"),
            Map.entry("input/conformance/p0_06.j2k", "8a98292f29c4f04ef57c9a2974a1c254cca8297ce270c8baeb911bfe975c013d"),
            Map.entry("input/conformance/p0_07.j2k", "46c4f5d64880a2df20d3faa22f6697384a6599dedb1b283e41a2efb1009cd9d1"),
            Map.entry("input/conformance/p0_08.j2k", "4eb4eb2b356e16640ee94a24327fb66385c387657863072d774d633cf4379ef8"),
            Map.entry("input/conformance/p0_09.j2k", "409c62a227497e7f2fc7e49055c02530cce742b6b385800b8a5aa4ae5bfab1a4"),
            Map.entry("input/conformance/p0_10.j2k", "0146480434740e7580ec5a413fd19654a2f1273dd7db25dc5d0b75ab98a9d706"),
            Map.entry("input/conformance/p0_11.j2k", "d20d8a13b570ef4adb0c8d4166568b043d00124c2db652ce7f78d85ac1d77b62"),
            Map.entry("input/conformance/p0_12.j2k", "741885b801b7b8a901b61792f52e0d67d05b7a87a7fc2a718c92cb6c969416fc"),
            Map.entry("input/conformance/p0_13.j2k", "12463d0c67e803fac6637d384263abbb6b546826419ecb725aed18fd45bcabb0"),
            Map.entry("input/conformance/p0_14.j2k", "3e8df4a1dc7e5edcd9478d2e5d350e16f802ef8da3161d63a979f94906440e54"),
            Map.entry("input/conformance/p0_15.j2k", "0aa26db75e8414d554e1fa93a0c7e101f16bb203f1fe06983fdd0c01fa61ddee"),
            Map.entry("input/conformance/p0_16.j2k", "853d78ff805eff1aa9d6b97cea42ea4975d334b0d4d6b36f217baa0bc1314d27"),
            Map.entry("input/conformance/p1_01.j2k", "26919bf56968ab57137bb478badb0bde8d2d53dfa26233935eecfbd40f235b81"),
            Map.entry("input/conformance/p1_02.j2k", "9fb9a26fbd769ea4fce1ca450ed605764af9931783eba49b3551b3a37b7a6f87"),
            Map.entry("input/conformance/p1_03.j2k", "0ff5572be1d0411550ceaf3b935f2c9f100bbd0399efbb8d48143add8d56ffb7"),
            Map.entry("input/conformance/p1_04.j2k", "fbbe812a6ce6abfc5d1eef02c2e3838c023c24f0ff0b1dcd79c4e1d018b3351f"),
            Map.entry("input/conformance/p1_05.j2k", "6b1eb070cb40585a9c0ce8569a957dcdbb1948e6129c0af50ec38ee9c0cef994"),
            Map.entry("input/conformance/p1_06.j2k", "08a7fb96ba93bb3cd57183eee56ee92e91cb58193a378c6a4208d8cf1877459b"),
            Map.entry("input/conformance/p1_07.j2k", "59576c42568098e4e5d9967eefb0a59b0002fedd7776c201c2c9f9aeb3cf1949"),
            Map.entry("input/conformance/subsampling_1.jp2", "f670eca22afcef910205c4af2478a310ce4a0c01434fe13994695e000d43f3f9"),
            Map.entry("input/conformance/subsampling_2.jp2", "fc891e0852f13a484df07db802024512d47c6a88d45d42cb01de258582caa1a8"),
            Map.entry("input/conformance/zoo1.jp2", "42e767e42436649b3ee0dc3589af02501cf4592b81ff7d70201cc10b223bbc00"),
            Map.entry("input/conformance/zoo2.jp2", "4022bf677f35d35f092eada25769a6f8add65f727ad57d2b3f9593bf6ebda1f3")
    );

    private Jp2TestVectors() {
        throw new AssertionError();
    }

    /** Returns a pinned file, by its path in the repository ({@code input/conformance/p0_01.j2k}). */
    static byte[] fetch(String path) {
        var hash = HASHES.get(path);
        if (hash == null) {
            throw new IllegalArgumentException("unpinned vector " + path);
        }
        var file = Conformance.fetch("opj-" + path.substring(path.lastIndexOf('/') + 1), BASE + path, hash);
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns whether a path is pinned. */
    static boolean pinned(String path) {
        return HASHES.containsKey(path);
    }
}
