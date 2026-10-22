package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the ISO/IEC 14496-26 conformance streams of AAC Main (the {@code am} suite: backward-adaptive
 * prediction with reset groups, intensity stereo and multichannel programs), AAC LTP ({@code ap}), AAC
 * SSR ({@code as}: the PQF filterbank, gain control and every window sequence transition) and ER AAC LTP
 * ({@code er_ap10}), and compares them with the reference waveforms under the criterion of
 * {@link AacConformanceTest}, on the output clipped to full scale as the references are. The ER LTP stream
 * stored with {@code epConfig} 1 must decode like its {@code epConfig} 0 twin, and seeking through an audio
 * reader is checked to be exact. The ER LTP streams with the error resilience tools are covered by
 * {@link AacErrorResilientConformanceTest}.
 */
class AacObjectTypesConformanceTest {
    private static final Map<String, String> VECTORS = Map.ofEntries(
            Map.entry("am00_08.mp4", "DVD1:a4a7936b32dabd8b8ac866245aac9888bfcdcaa1dee815bcb208d476036eed2f"),
            Map.entry("am00_11.mp4", "DVD1:17c4237e8d6cb1b435cd912404ba83b7ba45a97f6a8073b625ed5317f8bbfbfa"),
            Map.entry("am00_12.mp4", "DVD1:3b55a8abbebd19e8ddec3f2b49c4e436d1ed771ee5847714ffe5e1dda23fc97c"),
            Map.entry("am00_16.mp4", "DVD1:63ff7617e0ca48a34a96522a36acc508b20679647e14cd43790e0533be344ecb"),
            Map.entry("am00_22.mp4", "DVD1:c83d4629ddf8b42a130121e647d919360edae7da56614a08a6916cb901a36710"),
            Map.entry("am00_24.mp4", "DVD1:91c8fa29b868ee948bee845bddb750344a052793ef309d7196032cc876dfe2dc"),
            Map.entry("am00_32.mp4", "DVD1:523b0bcaa2c67fc8ebf2fc6c33b2ca0493901761ad9a23ef1ed365780acf6212"),
            Map.entry("am00_44.mp4", "DVD1:570eb004beb8fa25336d6d5991f7791c2fc6a91a32a66482f4ed728db3ed473c"),
            Map.entry("am00_48.mp4", "DVD1:c7a8afd61ec5ff6a10d96458022006a2d8eea3a8bce4f709684bb4e7d1dfe5f6"),
            Map.entry("am00_64.mp4", "DVD1:0d5efb9affb32bfd6a9de5c65c56f0cdc58da69221a1ffd81792428488271960"),
            Map.entry("am00_88.mp4", "DVD1:83e83c47115f443a8707412c1deb2c04ddd7258c52f38595b934ba1290767d09"),
            Map.entry("am00_96.mp4", "DVD1:5748696b1fece38b19f3eb9d4d8260867276065045b0a742eedce7501d3a91dc"),
            Map.entry("am01_48.mp4", "DVD1:d4288f1a3d619717039eeaaccb45e9dcacd450c0fe62976ddf5b43f5050c3fb3"),
            Map.entry("am02_48.mp4", "DVD1:c3bb88d36db238dcf21b77ac076719943aabd23ad38a809a0473d737d8c5cc40"),
            Map.entry("am04_48.mp4", "DVD1:6371820fcdc51e48b29aee8a1c7d3a172e3ecf9288056f9a1ca4aa8043dc21c0"),
            Map.entry("am05_48.mp4", "DVD1:0731540ce3d4dcbb186b65eb75f8233e23a9dab8dade7d156f7ae02ad0890a02"),
            Map.entry("am06_48.mp4", "DVD1:632c00328467dd7ce78a13b5dced38a45d9b0aba1c7c84be106c60f883a34806"),
            Map.entry("am07_48.mp4", "DVD1:457e038750ea594e7aa162277a9eb69c64da67d44d8bd184ce3020afb12f16d5"),
            Map.entry("ap01_48.mp4", "DVD1:1e6c18053533c471db3474505e14a51af855790e2090319588e0c55084993bdd"),
            Map.entry("ap02_48.mp4", "DVD1:af969a1b5f660bcb111711c8c806b52f5ab0bb9ae0a6e8ace4be203fb89a8924"),
            Map.entry("ap03_48.mp4", "DVD1:2f43512728ead7ab8af76dbcb5dc089eecc19790f5c2aa86152dccfb16c01772"),
            Map.entry("ap04_48.mp4", "DVD1:908687f9759378bc92efa1a29d388b21c9160fe330ce950f0cc433a7ffaf0d81"),
            Map.entry("ap05_48.mp4", "DVD1:8dc2c9a9f65c1fb7a6a797a4a9fd3574f4bffc1fa9d0b6e4550e9c530e4e2a76"),
            Map.entry("as00_08.mp4", "DVD1:b2fb9846ab72126d09884d7ba79ddcb28edf8baabf2e7d0bbcb37b197a88312a"),
            Map.entry("as00_11.mp4", "DVD1:5d7de253cd2ed5e991da42ddd8dc0b63ccee02ed1246eda868ee41aa5d851669"),
            Map.entry("as00_12.mp4", "DVD1:d054d8789cb997f9ffc5ee88948d26a9959b1a21922cb84bb10fb942dba90719"),
            Map.entry("as00_16.mp4", "DVD1:fc6dcf0f06dcbc94a0ab0a7d969976527f071122c46c4669981b1e22344712d2"),
            Map.entry("as00_22.mp4", "DVD1:198d6a0bf1f49f42603dffc9c2f64720bfd459e8fc821abe4df9183dd2ec88f4"),
            Map.entry("as00_24.mp4", "DVD1:9cb6605202d504bb7a46d32b77710235d5a39ceb524687192236b8a9bfcbb816"),
            Map.entry("as00_32.mp4", "DVD1:49675f27469f35c085e7b84cf0b7c8e2b46eb0ea28b05e66d784c01bcbff6fbf"),
            Map.entry("as00_44.mp4", "DVD1:721c642e28c0259c8991fba3b57cb8fc3a3d877923b05aa4674f668e96e2bc8a"),
            Map.entry("as00_48.mp4", "DVD1:6fa6addcc93f7e5b9625d1751195376cb6fbfa3d0609aec19761fd4ae6dfe153"),
            Map.entry("as00_64.mp4", "DVD1:a4522cda8f211516f19c0d9d6450cfead320e089022cc953903acd6a8aff88bc"),
            Map.entry("as00_88.mp4", "DVD1:8a6ba6ac09c6c2b37cead38de5a5b90cc3e7a178d7b9c7e0b2c8fbec3edb0cf6"),
            Map.entry("as00_96.mp4", "DVD1:1b2b5214760a2cb0fd45ba490470a38cef5cca27cc09efc214a6e0d831c19517"),
            Map.entry("as01_48.mp4", "DVD1:6545a439253fd7470fa95cea48d681525ff031bdf509e8c5bf28831618bc36af"),
            Map.entry("as02_48.mp4", "DVD1:9247d703e7ffb198265f0f78099c31ccdc34b07c0a31bbeb35b1f6b7facc4182"),
            Map.entry("as03_48.mp4", "DVD1:6191794e2029b91a0f9c89c24bcc271331c36eda0b3077a91f0657457bc8bcbe"),
            Map.entry("as04_48.mp4", "DVD1:cc01c8a794465de6bc78c2338ece0f95816c7fa4b93c26be4a680153bd79161c"),
            Map.entry("as05_48.mp4", "DVD1:6982da3df2962f1c999f1638f14adfdc43b3645d3d94223362224be34d2a4d33"),
            Map.entry("as06_48.mp4", "DVD1:147921ef34dcc9919fc2c1ec1b8ec3cc095eed2d4211bf2c5f3e15140d923c08"),
            Map.entry("as07_48.mp4", "DVD1:d351f325389b08f48e1fba056fd027c04322f8a9298a6e1154c65a18fea60a01"),
            Map.entry("as08_48.mp4", "DVD1:89a061afb701bff41125b74d369db2a3707280dd6212c29c1b277079bd3b9927"),
            Map.entry("as09_48.mp4", "DVD1:abea628a4fcce933fcd21850dc56ea18d06831e330673e3a0359c1c6da64c8d6"),
            Map.entry("as10_48.mp4", "DVD1:0da5a0bbf816501fca680e603c6e7019960baaeedcab2a1aa008b2fa17901bbc"),
            Map.entry("as11_48.mp4", "DVD1:0a3cd0d959a91f55e5b8b97064b7118a061169f1f08f99d9fedd311c3d5dc325"),
            Map.entry("as12_48.mp4", "DVD1:892e05f4408d21ee19ac1a0c9bac94d002bc7fdd95aa690b4ea68a558604f651"),
            Map.entry("as13_48.mp4", "DVD1:4fadcfb9b351c30ecfbcf0571aa80ecd0ae2a9e894dfa7fc28363d72c60cef2c"),
            Map.entry("as14_48.mp4", "DVD1:dab211445e5cc04154c73b46b49638d50db1c33d52594a269209ec69e8d2178b"),
            Map.entry("as15_48.mp4", "DVD1:764455f330766a57d91348513aa9e3cdde83a36720a8f4965ebe96a5a042f4b0"),
            Map.entry("as16_48.mp4", "DVD1:ce5d58c3233d81a2e85507fd8137c6bc77754989e5260c0d2851f8486d460639"),
            Map.entry("as17_48.mp4", "DVD1:00f790a328bcbde7f51dfe94dbda4f728fa96ce8d03f6caa7189b5979f67a9c8"),
            Map.entry("er_ap10_08_ep0.mp4", "DVD1:8aa78af9e31bcb8e364a296f4a3a63346350b59c59807065617fff150b979a3d"),
            Map.entry("er_ap10_11_ep0.mp4", "DVD1:0031d503c8a87a2a765bb4554fe4b67f20e89dc657b8ba5141dc205222563f88"),
            Map.entry("er_ap10_12_ep0.mp4", "DVD1:9157a51b7e978b0ed95f3d721d1ef8e064b7aae61f1128df52adbd0b7d0a0588"),
            Map.entry("er_ap10_16_ep0.mp4", "DVD1:4bc471768b34fca33b9c15b904d300fd95a5c7c78f1444a31aab17726ed0a7ea"),
            Map.entry("er_ap10_22_ep0.mp4", "DVD1:df2b8f862f59d45bbb936cdf5d4171fa6a8ad9be253cf8fc33c7efb9ecc61548"),
            Map.entry("er_ap10_24_ep0.mp4", "DVD1:e75d1d9ecc8d83d69633afb53b3a9252ae3909c859bebc2331f58004bc12b5c0"),
            Map.entry("er_ap10_32_ep0.mp4", "DVD1:5dde18cabcf7df74846cb78592df0e1e4c092a66178926a77b8733dc16709f87"),
            Map.entry("er_ap10_44_ep0.mp4", "DVD1:e9962fc58119ac4affb2ecca10dd807143c752449a390e99b3b8819a1bc8d197"),
            Map.entry("er_ap10_48_ep0.mp4", "DVD1:955cc4c773df11afd7af208f47ba8322e8ea580c553b45be6c4e5b2686bd2f53"),
            Map.entry("er_ap10_48_ep1.mp4", "DVD1:bf7e88f1bd2d8f0bc9b58610e57d07a52a521cd670c1464e0cbfacb38e1224ce"),
            Map.entry("er_ap10_64_ep0.mp4", "DVD1:9318fa2be2159963ac111f9457893def1d3af8fdd2f31ed1d17f08624cb96a81"),
            Map.entry("er_ap10_88_ep0.mp4", "DVD1:66416b61b3e32a636aac07b766b661f695eae0262853b567563f4852f6167f47"),
            Map.entry("er_ap10_96_ep0.mp4", "DVD1:9e9709f948735f956b02da360c99e4dc73ecbb78029f21f325a0043b7d7ebdf1"),
            Map.entry("am00_08.wav", "DVD2:1dc1c83e0164e033e8d4eedeb0077327b75b25786fa8ad00f460647c64774870"),
            Map.entry("am00_11.wav", "DVD2:8fead8bfe48c947b61472f1ed6e14bb3061c72af36155a7f43cc85d112a617a8"),
            Map.entry("am00_12.wav", "DVD2:dfde659d7fffed4186e4c4d2497a2ada8ec780fe8b200df42b1a60233c725a4f"),
            Map.entry("am00_16.wav", "DVD2:044d4cb9884288b3b362865e3f27ff09461e4e3518cd7f952c1b3827dde34af3"),
            Map.entry("am00_22.wav", "DVD2:98b630a544e62d0be3153018899d1ed74193a70c270415dec010162d461fcde7"),
            Map.entry("am00_24.wav", "DVD2:b0927bc6fb4cf9bce580945a4f8f17f85f41ca3abde7248df081c96f4b8a3679"),
            Map.entry("am00_32.wav", "DVD2:37e9c24237bce43e602bbdf649ede06f97a0bf725890adeb5feb034bc9e229f2"),
            Map.entry("am00_44.wav", "DVD2:599faa8fcdbddba62b15de40b3598f24d70395c6d7a2fe88aa41c630534a6041"),
            Map.entry("am00_48.wav", "DVD2:3d6f7c52e04942a90f6f7f2d62a8869122b777fe83cd09c4c55118f37875288b"),
            Map.entry("am00_64.wav", "DVD2:e6d98ba1cfec0d2d29b11d71a8e2208da6f6eb504546452442e7712174add8de"),
            Map.entry("am00_88.wav", "DVD2:49379e2945113bce1ceed5d3d33cd70b3b718465804893da2e35db6b4d0be86c"),
            Map.entry("am00_96.wav", "DVD2:3bf855734bf184fd595d293dc78354f3dd5432a81f22d1ee9da73ae38fdd1d15"),
            Map.entry("am01_48.wav", "DVD2:81585c8e490cba790eab379f220f219c354b514450286360f6e1dc10385844aa"),
            Map.entry("am02_48.wav", "DVD2:487f82340bdc6ea4c63cdc2179f323d6ab603a113953db9ca4adb7aa74beaacf"),
            Map.entry("am04_48.wav", "DVD2:4a0fd0a188592bc07e4d22dcd2ac83139d75c335ebdf9734d7ff6397bba75aed"),
            Map.entry("am05_48_b00.wav", "DVD2:3482ed5d9a52bb4d71e51f05faad5b4d6da3c36a56dd243852f78e4595d7fe58"),
            Map.entry("am05_48_b01.wav", "DVD2:beeac83d99986241925b5d80a705dd4db9f171c7f2b91e136fff2f9bc03f1cc6"),
            Map.entry("am05_48_f00.wav", "DVD2:0d69ff70c1f3699bb36de673cc0b573f6fcfffaad7b26f6e7945a674bcc3eadd"),
            Map.entry("am05_48_f01.wav", "DVD2:0e913aab2419ee8d15a913571bd9553f7eed297c73a34d14031c767b76fdaedf"),
            Map.entry("am05_48_f02.wav", "DVD2:1ddf055d7d2c5239747391f9376222724c02eca0a8e35e237db92d3c6da16866"),
            Map.entry("am05_48_l00.wav", "DVD2:9e818d7b77c8c87aa312169b16ba52cc1dcd8b5d26ce4ca710ad686274a751c3"),
            Map.entry("am06_48_f00.wav", "DVD2:24e67b16cd036426564e338fee4ed14ce002619ee0df9c035e5cec8cf9bb7a9c"),
            Map.entry("am06_48_f01.wav", "DVD2:3ebbb44f1f66d47fc18cbe2690af09550f53001967533056f0c5b54ce7c8bf31"),
            Map.entry("am06_48_f02.wav", "DVD2:2533c1960491398d4cd643aea77e3229d43e784b87f55eadecb4365efff06033"),
            Map.entry("am07_48_b00.wav", "DVD2:e5168658ed23588b3714249f5f596ed7b83057e69afd5f63e94b9440c7ff293f"),
            Map.entry("am07_48_b01.wav", "DVD2:80f91d40e053f72b3322d95738b67ba61dab94ef4185fe30ec700801775a7c3a"),
            Map.entry("am07_48_f00.wav", "DVD2:4b920170a16645b4dffe6918f6f0df52b363d61421cb2ebdbe8120c9cff1d0ad"),
            Map.entry("am07_48_f01.wav", "DVD2:e5a2c7496cf9b481aefff9515ff35d5671ed24c8db5595cb812803e199375743"),
            Map.entry("am07_48_f02.wav", "DVD2:05109ac7a9c861c2851593f5c1e25c2df27a44ed110565023df57bcbae7ccec5"),
            Map.entry("am07_48_l00.wav", "DVD2:7161c74c903706194310aa09a41407f0f92300af9c1919e90b6a9a35b24944a7"),
            Map.entry("ap01_48.wav", "DVD2:b9395ada3568ec5371b36942e92ef69e4c7baba5d74fcfbfa6dd82344e39c198"),
            Map.entry("ap02_48.wav", "DVD2:12324412ca05b79dc5ba3cb0417f185b5d1eec320c56988982373633661b2a80"),
            Map.entry("ap03_48.wav", "DVD2:e4fbd1b873e2194f7bbbbdeb67e0e7e18d138e53b1117c7509048d33c1f20342"),
            Map.entry("ap04_48.wav", "DVD2:a2cf689978aa57b0397a8ec437e8813b338fb3b36bce0df4514f6c7de3372d68"),
            Map.entry("ap05_48.wav", "DVD2:6185a0b481dc9f5496fe591540e8bf53dcf33e0d28a8964e36e809add5c11687"),
            Map.entry("as00_08.wav", "DVD2:3d2fa7f058c4c911c3cfcc979b67b35d736439706949b537eb0a787a5ee86031"),
            Map.entry("as00_11.wav", "DVD2:f9911a5c94f76f5f081087195167b97720e59f2061e399e08444c7d63867d58c"),
            Map.entry("as00_12.wav", "DVD2:f511a8333e76e04356f62c97d3e7a6482ac4d12578c584bb7ce8fa859f2c1ac1"),
            Map.entry("as00_16.wav", "DVD2:142850db6ca02b157d6710c587f11af2f0d69e261fbe5fc3b2b8f81163b96933"),
            Map.entry("as00_22.wav", "DVD2:0d08b1b7650eef61d2966e74ca495d559127a5a8edb9108e29b57ad408546187"),
            Map.entry("as00_24.wav", "DVD2:90e1cd2511af9bc71ff84cc4eb8486b0bb6e0cefaf89c98e69c814cee94c3c99"),
            Map.entry("as00_32.wav", "DVD2:ae22fe52dca331f8c829390eee8b74304cda5f25068be02326a59c1e4c5e184f"),
            Map.entry("as00_44.wav", "DVD2:be3fb5e03b97fde61b283bc99ac2ece22d2652cbe3383455c2654ad49d5f45ec"),
            Map.entry("as00_48.wav", "DVD2:7d2a119f524479822d1ae618df3e1ac0a1587ecb84cd1fa115b6aacf9a9ab89c"),
            Map.entry("as00_64.wav", "DVD2:cd5434cfd534c9e9d31710aa243567a3dd042485d9f0066cb517c2665d9ca176"),
            Map.entry("as00_88.wav", "DVD2:742a28128bf6133b8a7c68aaabb95d236061ae7e27baeb1d14441a56ec7ceda4"),
            Map.entry("as00_96.wav", "DVD2:9040f327dd2a67cee3e3daa0416857c88a5828ce6b194b3db94ec60476b741dd"),
            Map.entry("as01_48.wav", "DVD2:e9b149f93aab29d450124c66865356227c65765d95b42ab25ed5691e7376b126"),
            Map.entry("as02_48.wav", "DVD2:8203ae8ad70517883ca86ede0f74a8f3907667b6bfecad70d160d5aaf434db2c"),
            Map.entry("as03_48.wav", "DVD2:bdf426f59a76644b83af3da900c00308865140958b0d395b467de2824c92bfd7"),
            Map.entry("as04_48.wav", "DVD2:b575fac46331cedad196102d7edf10dd2c9f30d2cbb54083d0efe3d26c0c6d67"),
            Map.entry("as05_48.wav", "DVD2:d4c8974af78f07aa3c5501f7234c578db55a6a071dc5f06b28601508e87f64a5"),
            Map.entry("as06_48.wav", "DVD2:1f5d282c08e903b061c8eb0dd7b590124ac9ca2322f31bfd5e95f5afbc2bf9ce"),
            Map.entry("as07_48.wav", "DVD2:de1f10587ea7245fed3d824d738d0b5e390c6969db079ae1de091dd141962403"),
            Map.entry("as08_48.wav", "DVD2:84a88022dd1a824375e666bfc187949f9bb8dc63f64ea4d62807c658635b8382"),
            Map.entry("as09_48.wav", "DVD2:2d2fff77ea98ac14676c2c7344cc5d7269bd02d5ef601d0606ab62e9ed920549"),
            Map.entry("as10_48.wav", "DVD2:bfaec0afbc3adf76f47e8873aeca83962725977423f2b31ff5b7d51fae9ca327"),
            Map.entry("as11_48.wav", "DVD2:efafee81df702eb03f9d09c45ed2f6e67335174dfaa8962a193a64f292aa2476"),
            Map.entry("as12_48.wav", "DVD2:6d1be0dedc971d2f31a0d480328dd1d8f958d7598425111c16e9d42f94d6cd77"),
            Map.entry("as13_48_b00.wav", "DVD2:b644f6424e7927bc25b9f8e0097058c886b1b4fc05a2fc43bed6d897d71704a1"),
            Map.entry("as13_48_b01.wav", "DVD2:a4f2d53be98cdfcf59995dce549501bfe93788348b90042f853587da587645f2"),
            Map.entry("as13_48_f00.wav", "DVD2:89eb97c8d10cafd64c0972523eee9103672fdc8c69adf27fe157cae00316d992"),
            Map.entry("as13_48_f01.wav", "DVD2:7dedf596dd74e72eb4aefe92632b5e3771a07dfdc0abb1e98cf42943e7e125fa"),
            Map.entry("as13_48_f02.wav", "DVD2:c67811819c38ec64ee70bebd8f09f889aeeb81ccf77b19c37c33d2008f591169"),
            Map.entry("as13_48_l00.wav", "DVD2:e08a6991f843e609d204ef9b2db6038f230fae9cbf30f4c1535e1096462fb8c4"),
            Map.entry("as14_48_b00.wav", "DVD2:6166c05f925c259dd72202ba75efbaf37f1bc70bd191c805daad515dadc2ae32"),
            Map.entry("as14_48_b01.wav", "DVD2:ca7953afc39cff6239f745511e665cb3ba6d56225c334facd0871ce54f9d9565"),
            Map.entry("as14_48_f00.wav", "DVD2:b9b74d3bd873ff8cbf33e2e9fe630b533517623092c8b034292e5640410684d0"),
            Map.entry("as14_48_f01.wav", "DVD2:3911a4a359306da1ffd84d27ec432dab5b67c7b56b82f0900f5071fe80cd6f47"),
            Map.entry("as14_48_f02.wav", "DVD2:8fe41888c099758c391d35cea064e3a16190372dcd416441c8106682eff39ce4"),
            Map.entry("as14_48_l00.wav", "DVD2:cded7156d3b6a65e8d82e9a9684946995da00c59becb3b5305f7ad0664a26c2c"),
            Map.entry("as15_48_b00.wav", "DVD2:e2d8521c0bc80135b7fd245caecf2fc5ec3602c2f2147ced753548a3e89df6e2"),
            Map.entry("as15_48_b01.wav", "DVD2:7030e51360eaabff9d654dd3e726f2b05d9d0737af1a225566cfb862c581a979"),
            Map.entry("as15_48_f00.wav", "DVD2:10064f3be91c005ebfa327c84c657cee159702bec002645f8da42037377bc8d1"),
            Map.entry("as15_48_f01.wav", "DVD2:57339fedf6184fbda0ad3611db1d7b8a4b39e58cd47d38b7798fdfbbb3fbcc3f"),
            Map.entry("as15_48_f02.wav", "DVD2:90c5410c60508be94e6462d9bc49638648d86e03e498dda31d4a5cfe6edf9763"),
            Map.entry("as15_48_f03.wav", "DVD2:df766d09f5bcb9245a8383fe75b73b20bcd76a05724d2599cdc3144722f8350d"),
            Map.entry("as15_48_f04.wav", "DVD2:9d364616a3a771eefca155ba7baeb26732a173906e467bcd2f19713fb9f6f99b"),
            Map.entry("as15_48_l00.wav", "DVD2:a5de232aaf0adf81eb51ee7686fb4fc22c6de3ec73049d87a526104ad7a84f9f"),
            Map.entry("as16_48_b00.wav", "DVD2:22a56faef638fd8e6e85a9c9f75db647762be38b4300b163c2abff1c9d1849e0"),
            Map.entry("as16_48_b01.wav", "DVD2:584aa4b6992ba9d727dc906f0cb73a048dc8e72b493099d7145226bd8ecc8fe6"),
            Map.entry("as16_48_f00.wav", "DVD2:51d56edefa97df5f14fab2e59e9ce6b6de7302f3cc9c1e465eaed2835fe2d554"),
            Map.entry("as16_48_f01.wav", "DVD2:f950c20d4901e7e44339a59b6228cae230b7666c9c9feae669256592966404c1"),
            Map.entry("as16_48_f02.wav", "DVD2:6f305daf1c673557c96e7df78b910f29223ad3b900a58754ea2f9c940f8cc08e"),
            Map.entry("as16_48_f03.wav", "DVD2:7b89c6537227521d6f63b44af190b151b8833c65496d24cdf7aa7f31702c2ad1"),
            Map.entry("as16_48_f04.wav", "DVD2:caa1b74f7b31c6f2b68e8ca506515cdfcb33258146fa03a9967675a51d69f83f"),
            Map.entry("as16_48_l00.wav", "DVD2:7264b842e0a711f0dc9c5d914ed25db1fc5285dff25982f745f2386b3eb67102"),
            Map.entry("as17_48.wav", "DVD2:0b35582badbc3b96549db7eef1c66330caebab043093d68bd8667ddd7678c8f1"),
            Map.entry("er_ap10_08.wav", "DVD3:0cf36f802957ed3c14d3d64add81a3310c128b0cd979fb738ae0f972111e927c"),
            Map.entry("er_ap10_11.wav", "DVD3:5bb5e425f893ce543fef0d23e3865c40e74ab5388b1ae51b0a6612b426ff5111"),
            Map.entry("er_ap10_12.wav", "DVD3:575f731750bfa0a829bad88cce81f8de612e061c07de75cdd0713655d4976784"),
            Map.entry("er_ap10_16.wav", "DVD3:3a22b3acc3eb9f3c04785701228c6b1c48d8bef6baec49099a66bdf97e25f275"),
            Map.entry("er_ap10_22.wav", "DVD3:b8c7df04086d40258388d082e81da23e7d4eafdf2f13be186dc789213b33a738"),
            Map.entry("er_ap10_24.wav", "DVD3:8c6ca79f5f400b85ae4762b5c21801f581512598673e1f09194bb406df52aa52"),
            Map.entry("er_ap10_32.wav", "DVD3:159607a32cc88281aed6b818aec496f99a08bc4b12e521c2d1ca833c785880ab"),
            Map.entry("er_ap10_44.wav", "DVD3:456f3b5d82f6ccbe88407f7b15695116480fa91e05605f5aaf11b089fcfc3055"),
            Map.entry("er_ap10_48.wav", "DVD3:eb0d8c70164e4d04063ea75df6f4d695b76dd5ef5ca1c5cac7bf3fec223c3868"),
            Map.entry("er_ap10_64.wav", "DVD3:7893457652872a72e2163414cc7f2f506278cce56b80ed589c3fc73f32a98494"),
            Map.entry("er_ap10_88.wav", "DVD3:9409ac42cec9d209d9b17652a45bbe1a5e9938df3ff680ebf9b2b413f68c7ceb"),
            Map.entry("er_al21_48_ep0.mp4", "DVD1:071808f027bd5d276732dca409bdf273fb35352c12b725757afa8757c16d5470"),
            Map.entry("er_al21_48.wav", "DVD3:90a9470ef09ef0b331526218c96c18aff6552873151a564ab8f0439d0fd0b544"),
            Map.entry("er_ap10_96.wav", "DVD3:807575c7a92309ceb1c21d61576f1b136f13a27e403db9a0e3cd36d3efd889e3"));

    static Path fetch(String name) {
        var entry = VECTORS.get(name);
        var dvd = entry.substring(0, entry.indexOf(':'));
        var hash = entry.substring(entry.indexOf(':') + 1);
        var folder = name.endsWith(".mp4") ? "compressedMp4/" : "referencesWav/";
        return Conformance.fetch("mpeg4audio-" + name, AacConformanceTest.BASE + dvd + "/mpeg4audio-conformance/" + folder + name,
                hash);
    }

    /** Decodes a stream, clipping every sample to the range of the 24-bit reference waveforms. */
    static float[][] decode(String stream) {
        var planes = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(fetch(stream + ".mp4")))).planes();
        for (var plane : planes) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = Math.clamp(plane[i], -1.0f, 8388607 / 8388608.0f);
            }
        }
        return planes;
    }

    static float[][] reference(String name) {
        return AacTestSupport.readWav(Fixtures.read(fetch(name + ".wav")));
    }

    /** Asserts that every channel of a mono or stereo stream reproduces its reference. */
    static void assertStream(String stream, String reference) {
        var decoded = decode(stream);
        var expected = reference(reference);
        assertEquals(expected.length, decoded.length, stream + " channels");
        for (var channel = 0; channel < expected.length; channel++) {
            AacConformanceTest.assertConformant(expected[channel], decoded[channel], 1024,
                    stream + " channel " + channel);
        }
    }

    /**
     * Asserts that a multichannel stream has one decoded channel per reference waveform and that each
     * reference is reproduced by a channel no other reference claimed.
     */
    static void assertMultichannel(String stream, String channels) {
        var decoded = decode(stream);
        var names = channels.split(" ");
        assertEquals(names.length, decoded.length, stream + " channels");
        var claimed = new ArrayList<Integer>();
        for (var name : names) {
            var expected = reference(stream + "_" + name)[0];
            var match = -1;
            for (var channel = 0; channel < decoded.length && match < 0; channel++) {
                if (claimed.contains(channel)) {
                    continue;
                }
                var error = AacConformanceTest.error(expected, decoded[channel], 1024);
                if (error[0] <= AacConformanceTest.RMS_LIMIT && error[1] <= AacConformanceTest.PEAK_LIMIT) {
                    match = channel;
                }
            }
            assertTrue(match >= 0, stream + ": no channel reproduces " + name);
            claimed.add(match);
        }
    }

    @Nested
    @DisplayName("AAC Main against reference waveforms")
    class Main {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"08", "11", "12", "16", "22", "24", "32", "44", "48", "64", "88", "96"})
        @DisplayName("prediction at every sampling rate")
        void rates(String rate) {
            assertStream("am00_" + rate, "am00_" + rate);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"am01_48", "am02_48", "am04_48"})
        @DisplayName("mono and stereo streams, including intensity stereo with prediction")
        void streams(String stream) {
            assertStream(stream, stream);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"am05_48, f00 f01 f02 b00 b01 l00", "am06_48, f00 f01 f02", "am07_48, f00 f01 f02 b00 b01 l00"})
        @DisplayName("multichannel programs")
        void multichannel(String stream, String channels) {
            assertMultichannel(stream, channels);
        }
    }

    @Nested
    @DisplayName("AAC LTP against reference waveforms")
    class LongTermPrediction {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ap01_48", "ap02_48", "ap03_48", "ap04_48", "ap05_48"})
        @DisplayName("mono and stereo streams")
        void streams(String stream) {
            assertStream(stream, stream);
        }
    }

    @Nested
    @DisplayName("AAC SSR against reference waveforms")
    class ScalableSampleRate {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"08", "11", "12", "16", "22", "24", "32", "44", "48", "64", "88", "96"})
        @DisplayName("the PQF filterbank at every sampling rate")
        void rates(String rate) {
            assertStream("as00_" + rate, "as00_" + rate);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"as01_48", "as02_48", "as03_48", "as04_48", "as05_48", "as06_48", "as07_48", "as08_48",
                "as09_48", "as10_48", "as11_48", "as12_48", "as17_48"})
        @DisplayName("gain control, window shapes and every window sequence transition")
        void streams(String stream) {
            assertStream(stream, stream);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"as13_48, f00 f01 f02 b00 b01 l00", "as14_48, f00 f01 f02 b00 b01 l00",
                "as15_48, f00 f01 f02 f03 f04 b00 b01 l00", "as16_48, f00 f01 f02 f03 f04 b00 b01 l00"})
        @DisplayName("multichannel programs")
        void multichannel(String stream, String channels) {
            assertMultichannel(stream, channels);
        }
    }

    @Nested
    @DisplayName("ER AAC LTP")
    class ErrorResilient {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"08", "11", "12", "16", "22", "24", "32", "44", "48", "64", "88", "96"})
        @DisplayName("matches the reference waveform at every sampling rate")
        void rates(String rate) {
            assertStream("er_ap10_" + rate + "_ep0", "er_ap10_" + rate);
        }

        @Test
        @DisplayName("reads a channel pair in error sensitivity order")
        void channelPair() {
            // er_al21 is ER AAC LC (object type 17), a stereo stream with common windows, M/S and TNS and no
            // resilience tools; without prediction data its payload is valid ER AAC LTP syntax, so relabeling
            // its configuration as object type 19 exercises the channel pair order of the ER payload.
            var stream = AacTestSupport.parseMp4(Fixtures.read(fetch("er_al21_48_ep0.mp4")));
            var config = stream.config().clone();
            config[0] = (byte) (config[0] & 0x07 | 19 << 3);
            var planes = AacTestSupport.decodeAll(new AacTestSupport.Stream(config, stream.frames(), stream.sampleRate(),
                    stream.channels())).planes();
            var expected = reference("er_al21_48");
            assertEquals(2, planes.length);
            for (var channel = 0; channel < 2; channel++) {
                AacConformanceTest.assertConformant(expected[channel], planes[channel], 1024, "er_al21 channel " + channel);
            }
        }

        @Test
        @DisplayName("decodes the error sensitivity categories of separate access units like a single access unit")
        void separateCategories() {
            var tracks = AacTestSupport.parseMp4Tracks(Fixtures.read(fetch("er_ap10_48_ep1.mp4")));
            var separate = AacTestSupport.decodeInstances(tracks).planes();
            var single = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(fetch("er_ap10_48_ep0.mp4"))))
                    .planes();
            assertEquals(single.length, separate.length);
            for (var channel = 0; channel < single.length; channel++) {
                assertArrayEquals(single[channel], separate[channel], "channel " + channel);
            }
            var exception = assertThrows(MediaException.Unsupported.class,
                    () -> AacTestSupport.decodeAll(tracks.getFirst()));
            assertEquals("aac er epconfig 1", exception.format());
        }

        @Test
        @DisplayName("decodes no spectral band replication")
        void noExtensions() {
            var decoder = new AacDecoder(AacTestSupport.parseMp4(Fixtures.read(fetch("er_ap10_48_ep0.mp4"))).track());
            assertFalse(decoder.decodesSpectralBandReplication());
            assertEquals(48000, decoder.sampleRate());
        }
    }

    @Nested
    @DisplayName("seeking with an audio reader")
    class Seeking {
        static float[][] readAll(AudioReader reader) {
            var planes = new float[reader.layout().channels()][1 << 16];
            var count = 0;
            for (var block = reader.read(); block != null; block = reader.read()) {
                if (count + block.frames() > planes[0].length) {
                    for (var channel = 0; channel < planes.length; channel++) {
                        planes[channel] = Arrays.copyOf(planes[channel], Math.max(2 * planes[channel].length,
                                count + block.frames()));
                    }
                }
                for (var channel = 0; channel < planes.length; channel++) {
                    System.arraycopy(block.plane(channel), 0, planes[channel], count, block.frames());
                }
                count += block.frames();
            }
            for (var channel = 0; channel < planes.length; channel++) {
                planes[channel] = Arrays.copyOf(planes[channel], count);
            }
            return planes;
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"am01_48", "ap01_48", "as17_48", "er_ap10_48_ep0"})
        @DisplayName("returns exactly the samples a linear read returns from the target on")
        void exact(String name) {
            // the prediction state of AAC Main and LTP reaches back to the start of the stream, so a seek
            // that pre-rolled only a few frames would differ from the linear read (by up to 0.13 of full
            // scale on am01)
            var bytes = Fixtures.read(fetch(name + ".mp4"));
            float[][] linear;
            int rate;
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                rate = reader.sampleRate();
                linear = readAll(reader);
            }
            for (var micros : new long[]{1_000_000, 2_718_281}) {
                try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                    reader.seek(micros);
                    var tail = readAll(reader);
                    var start = (int) (micros * rate / 1_000_000);
                    for (var channel = 0; channel < linear.length; channel++) {
                        assertArrayEquals(Arrays.copyOfRange(linear[channel], start, linear[channel].length), tail[channel],
                                name + " channel " + channel + " after a seek to " + micros);
                    }
                }
            }
        }
    }
}
