package com.github.auties00.smalt.audio.opus.internal.celt;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the CELT encoder and decoder to the pure-C libopus 1.5.2 reference (float build, no
 * intrinsics): every expected SHA-256 below was produced by the reference with the harnesses
 * {@code encceltt.c}, {@code decraw.c} and {@code decbit.c} (driving {@code celt_encode_with_ec},
 * {@code celt_decode_with_ec} and {@code opus_decode_float}) on the same inputs, so a match means
 * bit-identical packets, final ranges and float samples.
 */
class CeltGoldenTest {
    private static CeltEncodeHarness.Config parse(String args) {
        var v = args.trim().split(" ");
        var i = new int[v.length];
        for (var k = 0; k < v.length; k++) {
            i[k] = Integer.parseInt(v[k]);
        }
        return new CeltEncodeHarness.Config(i[0], i[1], i[2], i[3], i[4], i[5], i[6], i[7], i[8], i[9], i[10], i[11],
                i[12], i[13], i[14], i[15], i[16]);
    }

    @Nested
    @DisplayName("encoder and decoder against celt_encode_with_ec / celt_decode_with_ec")
    class Raw {
        @ParameterizedTest(name = "rate ch frame bitrate vbr cvbr cx pred ana lsb start lfe mask silk inv sch loss = {0}")
        @CsvSource({
            "48000 1 960 64000 1 1 10 2 0 24 0 0 0 0 0 1 0,74e1d8092277a3ffa93ce0afaff34a482fdff3c69bdb81a98608530d5c177de7,7588c4ada9381d20c20bf081cd3b9bdf84b8104d60cee2b703bd0cf88308882d,3d15714b8f3ab9cc6e06007e05664fe6f618999215999327cd366faad3701b64",
            "48000 2 960 128000 1 1 10 2 0 24 0 0 0 0 0 2 0,495191148997acece4ae6e51010447af864be2c55030ce8b772fb7baae85f7bd,fbfc9ad7f62cbbfd9ccbeafef6844cd563306c429703646ca771ff938356d25a,f4cd3e27347e6d6bdf21991c8c043c9e73d2c80c054d315199ad713a9e0c1144",
            "48000 2 960 128000 1 0 10 2 1 24 0 0 0 0 0 2 0,ee873f00fe44e33e20addacaab04762c7093b9423cc33918eea653c0fd9f5ae9,2bbfe725c29a8651d58762f5619369de4fc4b5da85f266e1ae36769c7ba5c928,d747af71a2cdada9eaf68f46d76fa4418f9fd318cd23093025d5ef0fb25b8524",
            "48000 2 960 96000 0 0 10 2 0 24 0 0 0 0 0 2 0,03dbdbe16b2c5bb6dc4f35a99311ce361c3af8bea3d75d610f6aec061db77b5b,b320224e1decbb1b610d8174700b3a86b658dad4928bdd5b5157334bf81d3c45,ace57f1aad043343ac5b36b4a7cd27915aacfca964f8b618a0e8284f3646f4af",
            "48000 1 480 32000 1 1 5 2 0 16 0 0 0 0 0 1 0,1f013f83fdc61383ae4c5d8a15f0b0fb7a27cc9355bfe9511195864c87f1614f,37e8a14228c73ddaeef83495a3499f132fc9c7f94f44139b826d3b573f673e47,65f3d0637b3d4894b14f0af2185216bdf353cb2e6a3928bbb3692f82b6b9066c",
            "48000 2 240 64000 0 0 8 2 0 24 0 0 0 0 0 2 0,ec0be8179ee8e7163934d465439f9c99f0f535840e9d92cb4a9635f088f4db5e,73f9bb04b37537359e368b37aad68841abd3b03908b9bb842a2bc2dcdf1d1914,6b6d5ac2ead262b588ae23ad00fc482c738f082d42aca16b688b5f1eeae4c8e3",
            "48000 2 120 256000 1 1 10 2 0 24 0 0 0 0 0 2 0,1b332004abda4342207ba6fc5c050a59001042767a059291170d06ea57ec5d2b,77191215e5fda4c73c2eaed45c9c294bde1b986e3d52c60a5b01fc7d23d54197,20a22277029180c30860dfab8a0fa5a2bd28ff937ad40380b819efac728a98ff",
            "48000 1 120 24000 0 0 3 1 0 24 0 0 0 0 0 1 0,5b8a7a34f6be85374325ad0f12f01371b58e79e617909bcc2932916ad43ca63e,1bd8b08ca75fcbc2c2cd680145f7d02ba0671cb3f6ca19873158b99aad942f14,bc5835352713b88f4f7af602501aba031d21695c585d1b1038165e83a25b190c",
            "48000 2 960 510000 1 0 10 2 0 24 0 0 0 0 1 2 0,773dbcf86ab8402194a52a9a0b9de003c6c3b7b4594adafc5579c5bab6562f65,dcb7f821ad2c57eea9972179cdeaccd7a897ec4347e3dda28f2a18f4de46da82,e14ee3ef73602313200fbfb9931ba2223b5e36315af8409ec59174dd0db5919a",
            "48000 2 960 12000 0 0 0 0 0 24 0 0 0 0 0 2 0,39fbd5c028b20bde2ae30a6c6c5b602de5898c16580511e5851543e6de3bbe75,09f22c9838ff3c25fb9bbb5026d83765220b9647f1144d8c5ea49964d4885392,59742344f7b36ecce73ca7e30b55fa2daa5b83653da7963e678c2ee771afbab5",
            "48000 2 960 48000 1 1 7 2 1 24 0 0 0 0 0 1 0,556cba743bf97d7699a73f4043163488f3d9e863df0e6776df723e477a2e4418,4d85b1db2b1e5805afcc7df7327b97a9e98d505337176be9b3c3ba5a6811c7ba,ba292b36920c013335d1afe304b6a195ccc5db13cc36490305ba9e61aa1c6005",
            "16000 1 320 20000 1 1 10 2 0 24 0 0 0 0 0 1 0,39115b386d6665dd6f187cfb998686e9edce7ac74ddea0b1dbb21c6b67304b00,6a7e4a50917bd64bc360ca1ed00687f2451fa63ed7a585656d41cf862129ae2d,4fa442166eb0dc7d19b1bda2c1071914b661cc76fd94c9c150b75bf0dcbc1284",
            "24000 2 480 40000 1 0 9 2 1 24 0 0 0 0 0 2 0,c8544e0a5696e8f4cef9ce2f9c25b3795f8828f15398d2ffacd7b727536de21d,291f2adc1755169a5093918029a6063b22baeb140f640aad14fc26fc79e5b7e5,666d1f11e22040208e5b72e8b702ab8d6d12eba4aceb1453bbb107446bf8decf",
            "8000 1 160 16000 0 0 4 2 0 16 0 0 0 0 0 1 0,cae48ed5e24ee5089cc15ccdcad9e9be88b38ebb5763593d5b309175b7b94a02,f42bf8eff2e3987bc92276aef160a154d668e8f2b07d29740f172bc4e961d9a7,04f52df8ea15cd49e5a6cb8f5133511c83d7841f938df63ed4e879ceb8917338",
            "48000 2 960 32000 1 1 10 2 0 24 17 0 0 1 0 2 0,9c5e2abf70d27b78ad15764f8a6c000b5233f5ddc00ae56b30eab29604ae8772,57fcf112b763773ac1a0d53acaead696c0f8100b41768bea1ac453086110c036,33728253b8323bc7673ac1b45390846f63529f01b0bd2c571b861414c453c058",
            "48000 1 480 20000 1 1 10 2 0 24 17 0 0 1 0 1 0,801c5106ec24b964e7c412eeacc8d39322c029c182cae78ed4f6ddb10a92895d,f06066c640a57d4b673cdcd5ff654271914b4ff2285d7fa8fa1570a24257c5a3,3fa971098208d846386163c6dcd002b4f2e19742925fcd4a1fe36f21f612f2a1",
            "48000 1 960 64000 1 1 10 2 0 24 0 1 0 0 0 1 0,c7d94ea356c68e17e2f33f084ee03cdb46ff8d0a3c275c2352f394cd86bb8cb3,3a557fc45ccd9b924933078e668fe3190e4b4a328365b2edab50e9c6db03712a,dd54ed703b11baca8ba9d402250e3f4993dc6e8634e38ba01b791fdbeb39412d",
            "48000 2 960 96000 1 1 10 2 0 24 0 0 1 0 0 2 0,01fbaa7b470fb346ebdceca4b0569b225e3a9c1a3f3eb2914aa36b1fd0ee9028,b003a1671f6e744e8010e83005d25080845eca1499eed657f9b683c3953025d7,e784ade260463f1487d13f8c824cc68178c6123ff780e9c874b749acc37270ea",
            "48000 2 960 64000 1 1 10 2 0 24 0 0 0 0 0 2 15,cfb1a27d512eb873c19051a6fcd11b685551a7d79683c32a63546d9eacae6c8a,9121ebb134d735626d296a385ae096d5f5b4517775ae8dc186584cb3f61a1ce1,ca2eb41b81a1545ed5c8a0e3f1becd889e3c35c85641e7ead4f65743ebf0a0e3",
            "48000 2 480 64000 1 1 0 2 0 24 0 0 0 0 0 2 0,68c9f50a24a257114be31d42afea9501410febb6e6b9169b2914b37d0b4d8728,d71f2df6670e0b0572c4c8a39fb05742897445c0386122ffd66c8b72ade04c7d,e65baa1696fbb3fe3b4243150fc51c28d1d742c8d2947c25afa92f0168a37587",
            "48000 2 480 64000 1 1 1 2 0 24 0 0 0 0 0 2 0,0769f4b0072bfed72ce410b041b7c476d9ffb89176518a00f74ad42a95d7a57d,0ac82d4cca1fc4f2642abd4c8742bd56fd0c883677626adc0864d1acf7e31f24,4d9339ef73a7cce4990187ba9106cf1ae239cecd6c2f670489287a52b20dc8d4",
            "48000 2 480 64000 1 1 2 2 0 24 0 0 0 0 0 2 0,ee47f57017c7dbad5e366714d5c5db2f73d3e980c7ab9251fc393dbbdd3b8a4a,63531e1a13001c7dd843428defe61792eb6b68909cd1681aa11815de82bfa937,59493e696ad7123e96638ce7645c74a4b563ebc8096844b22835d788a2e305af",
            "48000 2 480 64000 1 1 3 2 0 24 0 0 0 0 0 2 0,f8c5f44feb88ac775b9236e15d0fd58270ddd29c3bf5d570edf63555b1b7bea3,6f75260d2fd41a6f90fb25e588f6440f19cc6c7713e3d541e811efeb953bf1d0,f0fbbf78ff150e41a50d7b5aca2088dbdbbed632a5f2694f9adee463f24fc069",
            "48000 2 480 64000 1 1 4 2 0 24 0 0 0 0 0 2 0,cc7c4688b5fa2be46fcc101b3ec422e8ff140c20dfcc9100945190258e67967e,c0c06d68d70a8e3d026234a7555e914c3b273dd16bb33725bff0b5139d36ea88,7ae7088ef9810e3074097ea89fbd7232d5ffd51084ca2fb444e63cc7e6031356",
            "48000 2 480 64000 1 1 5 2 0 24 0 0 0 0 0 2 0,0f40ab9161132e11ac1cd805e61ee589e48d469ac7a46eb588fdbc7ec1277cac,59e61ae59391eb9b84dadc65a70f44d93f3e28170bcc0e76df9ed092f3af6ba5,42ec73047fd27a3f56dd1827f28f6d1e895fd6c748087a3c8afb516e0dbe9042",
            "48000 2 480 64000 1 1 6 2 0 24 0 0 0 0 0 2 0,0f40ab9161132e11ac1cd805e61ee589e48d469ac7a46eb588fdbc7ec1277cac,59e61ae59391eb9b84dadc65a70f44d93f3e28170bcc0e76df9ed092f3af6ba5,42ec73047fd27a3f56dd1827f28f6d1e895fd6c748087a3c8afb516e0dbe9042",
            "48000 2 480 64000 1 1 7 2 0 24 0 0 0 0 0 2 0,0f40ab9161132e11ac1cd805e61ee589e48d469ac7a46eb588fdbc7ec1277cac,59e61ae59391eb9b84dadc65a70f44d93f3e28170bcc0e76df9ed092f3af6ba5,42ec73047fd27a3f56dd1827f28f6d1e895fd6c748087a3c8afb516e0dbe9042",
            "48000 2 480 64000 1 1 8 2 0 24 0 0 0 0 0 2 0,dc61c2ca75ad14ac496d4871ebb45a3f564b1fd0e025bdf51a0dc14b278c98d4,c332a741b7fef327054b745e664e7c86bd8e25bd03652ab68c34de28fd930a04,17feb869ea60e9db11259571fc17ac3ead5de6287075f04aa18b5e81c52cea2d",
            "48000 2 480 64000 1 1 9 2 0 24 0 0 0 0 0 2 0,dc61c2ca75ad14ac496d4871ebb45a3f564b1fd0e025bdf51a0dc14b278c98d4,c332a741b7fef327054b745e664e7c86bd8e25bd03652ab68c34de28fd930a04,17feb869ea60e9db11259571fc17ac3ead5de6287075f04aa18b5e81c52cea2d",
            "48000 2 480 64000 1 1 10 2 0 24 0 0 0 0 0 2 0,dc61c2ca75ad14ac496d4871ebb45a3f564b1fd0e025bdf51a0dc14b278c98d4,c332a741b7fef327054b745e664e7c86bd8e25bd03652ab68c34de28fd930a04,17feb869ea60e9db11259571fc17ac3ead5de6287075f04aa18b5e81c52cea2d",
            "48000 2 960 160000 1 1 8 2 0 24 0 0 0 0 0 2 0,d176e678ae41f8523eeebbcf59bb7f7df38449995c0636fea4321b36f0cafe81,fa0414056ab36ef9f08e26990348df4d259358e8265a1e5b76dbcab99f2cba18,61a14ca6ec1968a56b82702173b61cf7d958128cea3ed761b95450cdf89b6fcb",
            "48000 1 960 64000 1 1 10 0 0 24 0 0 0 0 0 1 0,dfa95a6207842757404c2f608248b61a3dab694a3f276b5247103260dae2b499,48c20a59bf1b7a1abfecb5f8c7f35281dadf459c79df68fdb48805264c462d39,f7b8a6d6f098467c662e1b0009ac356326a9ee2cfb0cb4a2731564b9766c841b",
            "48000 2 960 64000 1 1 10 1 0 24 0 0 0 0 0 2 0,18963ec0eccc8722b330735be334c8ecf29590fe3553a81a3d5ab3df5262704a,8f1f1fe79b74af2cbbfe5b61fd86fc481b34a54c676a6d1fa3a1bb5bdabe6b40,a954daf48c9dfe2450092f56774b50c7504db8ae1089bace4a6b34449c13364c"
        })
        @DisplayName("produces identical packets and decodes them to identical samples")
        void encodeDecode(String args, String encoded, String decoded48, String decoded16) {
            var config = parse(args);
            var pcm = CeltEncodeHarness.signal(config.rate(), config.channels(), 3.0);
            var stream = CeltEncodeHarness.encode(config, pcm);
            assertEquals(encoded, CeltOnlyHarness.sha256(stream), "encoded stream");
            var packets = CeltOnlyHarness.parseBit(stream);
            var frame48 = config.frame() * (48000 / config.rate());
            var at48 = CeltOnlyHarness.decodeRaw(packets, 48000, config.channels(), frame48, config.startBand(),
                    config.streamChannels(), 9);
            assertEquals(0, at48.rangeMismatches(), "final ranges");
            assertEquals(decoded48, CeltOnlyHarness.sha256(CeltOnlyHarness.toLittleEndian(at48.samples())), "48 kHz decode");
            var at16 = CeltOnlyHarness.decodeRaw(packets, 16000, config.channels(), frame48, config.startBand(),
                    config.streamChannels(), 0);
            assertEquals(decoded16, CeltOnlyHarness.sha256(CeltOnlyHarness.toLittleEndian(at16.samples())), "16 kHz decode");
        }
    }

    @Nested
    @DisplayName("RFC 8251 CELT-only vectors against opus_decode_float")
    class Rfc8251 {
        private static final Map<String, byte[]> VECTORS = new HashMap<>();

        private static synchronized byte[] vector(String number) throws IOException {
            if (VECTORS.isEmpty()) {
                var archive = Conformance.fetch("opus_testvectors-rfc8251.tar.gz",
                        "https://opus-codec.org/static/testvectors/opus_testvectors-rfc8251.tar.gz",
                        "6b26a22f9ba87b2b836906a9bb7afec5f8e54d49553b1200382520ee6fedfa55");
                try (var in = new GZIPInputStream(Files.newInputStream(archive), 1 << 16)) {
                    readTar(in);
                }
            }
            return VECTORS.get("testvector" + number + ".bit");
        }

        private static void readTar(InputStream in) throws IOException {
            var header = new byte[512];
            while (in.readNBytes(header, 0, 512) == 512) {
                var nameEnd = 0;
                while (nameEnd < 100 && header[nameEnd] != 0) {
                    nameEnd++;
                }
                if (nameEnd == 0) {
                    break;
                }
                var name = new String(header, 0, nameEnd, java.nio.charset.StandardCharsets.US_ASCII);
                var size = Long.parseLong(new String(header, 124, 11, java.nio.charset.StandardCharsets.US_ASCII).trim(), 8);
                var padded = (size + 511) / 512 * 512;
                var base = name.substring(name.lastIndexOf('/') + 1);
                if (base.equals("testvector01.bit") || base.equals("testvector07.bit") || base.equals("testvector11.bit")) {
                    var data = in.readNBytes((int) size);
                    VECTORS.put(base, data);
                    in.skipNBytes(padded - size);
                } else {
                    in.skipNBytes(padded);
                }
            }
        }

        @ParameterizedTest(name = "testvector{0} at {1} Hz x{2}, loss every {3} (burst {4})")
        @CsvSource({
            "01,48000,2,0,1,8fdb38732bf51361843f3924bc7e10f23611663f47a48be09428d1f883778212",
            "01,48000,1,7,1,d5efc8b229659aee66065ef6c5b984a62869d83611994386c08298d9ff2adab6",
            "01,16000,2,7,1,399e2ba2d5f006a60b40444ad470287566155fbc5c84c3fc73fe6949ca64a769",
            "01,8000,1,0,1,9ddd92a5cca30e07f2ceab0d3bffdb463e1da6dc7e71ccd511bb01e8b402eb9c",
            "01,24000,2,7,1,a8314ea29d056d5e472149e1806768f936f125233a601ff981ea5e4ba9e177dc",
            "01,12000,1,0,1,98a9e58d9ebd2889b9411045ac477ea92d3079aa49a9eeb7524d5cf50a23b0a8",
            "01,16000,1,0,1,00470b49d1c230127d797b09107e36bcd909dd294cb26a8478691c5400ab5d3d",
            "01,8000,2,7,1,db6198ebed5c28baa4e3d54c654585aaf8078bce72b6ea20140d486e3e399577",
            "01,48000,2,40,12,52707a91f34073fa83f1a217f146ca799c90333a93b05c30f951a557a4be9895",
            "01,48000,1,40,12,f56a123b1238f8ec1a295bc897bd1e590153ee1a285705ffec0bc2bff2fa2ec4",
            "01,16000,1,25,3,39d7210d61f0c523f5b9dabbc0cd710d0e56b051181acf739a41d772c37fe451",
            "01,16000,2,25,3,66011539e73181e18fe53ce5ec5f166912e9c60ec26948008897ddbf9a143352",
            "07,48000,2,0,1,2ef9496cc071e5bc666ad3e9271e015e458ace070435048b48d7fb91d8416e41",
            "07,48000,1,7,1,0d3113b4555c2d069a8b5a3e54a133ebb0efb2217328bb6a10133f43a7140ec4",
            "07,16000,2,7,1,796b11b35bde8103a4becf7a1452b982f892f21f1d165590b28cf38659eb7e34",
            "07,8000,1,0,1,b21b700917ea868f012cad47682ca2c833b436c306eee6e4b4848df2a92ab475",
            "07,24000,2,7,1,8b48c59dd2cf0c62e5b4c9ff5dcc8bb342e62ec19a9572816bc583dd39d91daa",
            "07,12000,1,0,1,68633eab59061b2559e1a94273067ad67af1f6df5658c1377656c29c7b1f5c6e",
            "07,16000,1,0,1,df7576c4e1433d1d5eabea93627dd88ff4f51220a2f3d65a3f6c205324cb4c13",
            "07,8000,2,7,1,3744889f90847d3f1b33e5653d96ee8005540eebedd385f23a6f9a15e7e28bb9",
            "07,48000,2,40,12,1c32c2a6c2b6607982b6064b0ea64d856c9af96f25d01c5e3097fbab092ab39d",
            "07,48000,1,40,12,c728d9dfaa72dd6691dfdd75ddf9b1c4152bb4d35cbca887912c3541cf653618",
            "07,16000,1,25,3,f35f4ed18fa04e1b0cff7f2e8879209f799fc5abf2f0da0963877fde051c01f4",
            "07,16000,2,25,3,86d9a925176d06e5c49382c293dc5cdf77accfdaa7726a7eadaf42e188b3b133",
            "11,48000,2,0,1,a62b731910882b82615e2ce47c8d959b94ccacedc72bd34a9c952eac786120ff",
            "11,48000,1,7,1,1e37a1cb02791a31dea38d2d5bf9f607bf094f52efafce8e25e2aa3c495d0ba0",
            "11,16000,2,7,1,c902fbdff3c6a17263385c3620e476f8880a34eef1f67bdc68ffddfe14ac8e5d",
            "11,8000,1,0,1,567e4c1b0b51dd2774f9a9328bb83de3c0c222a4a8e8e02d5a5ecf00649a195d",
            "11,24000,2,7,1,df744028c74189a33141c4ebeed418afb5d6d007f5261fd6b4aeecd73171fa61",
            "11,12000,1,0,1,ea6aeebf2348856689d2982ba6a99380c4db1714c43e8056de0c948d91e7393d",
            "11,16000,1,0,1,e48eb08eeda947b07422f64349e66f0f81706dcfe663ee5634a14b64c622b565",
            "11,8000,2,7,1,ec5f085af30ed16337a576ba2d5bda568b0b35b47be5ed1b8216cca180ae0065",
            "11,48000,2,40,12,95b2af4928b60f70d620b75d21567d8d3fd87e65b99a8b4e6273ea875bec6a85",
            "11,48000,1,40,12,96ab166d4dc54a081a49a869f415c16226d0ca77483c151807bcbe587faef174",
            "11,16000,1,25,3,c982334b8b0ed60f8d4623a823a7f3e2453ba14c81ae9772331c40020238591a",
            "11,16000,2,25,3,1159dc8d79a6d2afbecf240ab6656d5388ccdb5e0516a9584db1e1332dfbad20"
        })
        @DisplayName("decodes with matching final ranges and identical samples")
        void decode(String number, int rate, int channels, int lossEvery, int burst, String expected) throws IOException {
            var packets = CeltOnlyHarness.parseBit(vector(number));
            var decoded = CeltOnlyHarness.decode(packets, rate, channels, lossEvery, burst);
            assertEquals(0, decoded.rangeMismatches(), "final ranges");
            assertEquals(expected, CeltOnlyHarness.sha256(CeltOnlyHarness.toLittleEndian(decoded.samples())));
        }
    }
}
