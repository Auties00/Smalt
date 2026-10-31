package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the scalable (SVC) bitstreams of ITU-T H.264.1 (the Scalable Baseline, Scalable High and
 * Scalable High Intra profiles: coarse-grain and medium-grain quality layers, dyadic and extended
 * spatial layers with cropping, temporal layers, key pictures, CAVLC and CABAC, progressive and interlaced
 * layers with field pictures and macroblock-adaptive frames) at every layer the
 * suite defines and compares each with its reference decoding. Every archive holds, per layer, the
 * sub-bitstream that layer needs and its decoding; the test decodes that sub-bitstream at its highest
 * layer, and the sub-bitstream of the highest layer at each lower layer and temporal level, except for
 * the layers marked {@code -sub}, whose sub-bitstream keeps the quality layers of some temporal levels
 * only, so that no target layer and temporal level select it. The expected MD5 is that of the archive's
 * decoding, or, for the layers marked {@code jsvm}, that of the JSVM 9.19.15 decoder: their decoding
 * predates the deblocking rule of ITU-T H.264 F.8.7.4.3 for the transform coefficients of the reference
 * layer in Scalable Baseline streams, which the JSVM decoder and this decoder apply
 * ({@code tools/testdata/README.md}). Only the sub-bitstreams of each archive are downloaded, through
 * the archive sets {@code h264-SVC-*} of {@code tools/testdata/archives.tsv}.
 */
@DisplayName("H.264 SVC conformance bitstreams (ITU-T H.264.1)")
class H264SvcConformanceTest {
    /**
     * Name | layers, each {@code label:DQId:temporal_id:md5:source}, the source being {@code ref} for the
     * archive's decoding and {@code jsvm} for the JSVM decoder's, with {@code -sub} for a layer that only its
     * own sub-bitstream selects.
     */
    private static final String MANIFEST = """
            SVCBC-1|L0:0:4:25bf70cb68da66f6e3db267a9d0470dd:ref L1:16:4:00d523a56a17ab2d2e6a1eb2c7dd68a0:ref
            SVCBCT-1|L0:0:3:cd4fe22dbc80483f38f1cf9f6574d4ca:ref L1:16:4:3518c010d8383835da64861306bea61c:ref
            SVCBCTS-1-r1|L0:0:1:a13cd23fc7879604bbcd6555aa17920c:ref L1:16:2:7151d816074d15d9d5a6603b1647514d:ref L2:32:2:256c916813a324c581ba35f957cfb399:jsvm
            SVCBCTS-2-r1|L0:0:1:9dc12487ad0e87893614b0f6c99d4d34:ref L1:16:2:03849c7341d4490a8809bcd18070b888:ref L2:32:2:6f874d36b2b68612877e8a16b582e764:jsvm
            SVCBCTS-3|L0:0:0:4a562e4e41a53310ec8bff3433a66e15:ref L1:16:0:38069b20bb18023b8c0584c824fd8812:ref L2:32:0:f8c67f12e0a7f79a06105b295b4e0955:ref
            SVCBM-1|L0:0:0:3ea721367ff3afdac6e387e164f840d5:ref L1:1:0:7ea5c6ea9490e193c01f01fc7a67e3f6:ref
            SVCBM-2|L0:0:7:2c155c7f929f809450a91c9e0e73a756:ref L1:1:7:d9781efe0716fbe58198de7d73ac1185:ref
            SVCBM-3|L0:0:7:f935b8922c220b19438c4eb26d645309:ref L1:1:7:42efc663faa1cb7636b13abf19d74967:ref
            SVCBM-4-r1|L0:0:7:978259c74faaeed2b65c1ef17c4922fc:ref L1:1:7:f360d3961672b52f8cfd5ca72eb6e8ef:ref L2:2:7:26e2e2c8a6aacedc7c172c1a91dda264:ref
            SVCBM-5|L0:0:4:1e79734a549a879960783df203067204:ref L1:1:4:85dd6c0b4db452db299f627a18f68b4e:ref L2:2:4:acf09bb0d59a940168f41ce8be55b116:ref L3:3:4:aba434d574365614dd373d60c746d6cb:ref
            SVCBMST-1|L0:0:3:3ba403eaeaa5f4115addf459ee4985a4:ref L1:1:3:638f00377fca51e47056bf5441da6ef8:ref L2:16:3:f4e5ee8d9f7c30234fc7ac41ddca3b7e:ref
            SVCBMST-2|L0:0:3:a370da5b69ac5e4e01945695bc3a814a:ref L1:1:3:0deb3f3ce7a86b3f7e5e7f5ac8f14169:ref L2:16:3:fb142396bf007993bbb5e1cff1d19a32:ref
            SVCBMST-3|L0:0:3:5b277856ce6ce34230db51b7b4fbf129:ref L1:1:3:11b685560d313523e161e74c2fb608db:ref L2:16:4:84eb7e7c044f4d8871a33bc8e237febf:ref
            SVCBMT-1|L0:0:3:a1c33070fa00c2af59d8a4d61ef0e07a:ref L1:1:3:620f8a6034eb79aa39450379c1328a4b:ref
            SVCBMT-2|L0:0:3:a1c33070fa00c2af59d8a4d61ef0e07a:ref L1:1:3:620f8a6034eb79aa39450379c1328a4b:ref
            SVCBMT-3|L0:0:3:2aad9b72abe5855066e09e1ad65f9664:ref L1:1:3:6f7ae1a0eabdb85f129c5501a9e17629:ref
            SVCBMT-4|L0:0:3:493d61ea1c238d10cee2a5a707d35ac8:ref L1:1:3:9f60bd9d74af7900ec687f1c0c8b4ef2:ref
            SVCBMT-5|L0:0:2:8f8be2e40f17971ea44ca17961c40c92:ref L1:1:2:3795eb0253f455ff318a08f2713094dc:ref
            SVCBMT-6|L0:0:2:02744aac7c52319284631d62f4c33c71:ref L1:1:2:9b2009fdaedca65e6e5302206769eb02:ref
            SVCBMT-7|L0:0:2:02744aac7c52319284631d62f4c33c71:ref L1:1:2:9b2009fdaedca65e6e5302206769eb02:ref
            SVCBMT-8|L0:0:3:d833e2960cffe18c5486f155635290b6:ref L1:1:3:4aadd1e8783d23d6206cc547805070b3:ref
            SVCBMT-9|L0:0:3:9d74ce732e2d94bba2abce5db91d5e8f:ref L1:1:3:b0ad5735a9c2dec43ed6a242a5691e98:ref
            SVCBMT-10|L0:0:3:493d61ea1c238d10cee2a5a707d35ac8:ref L1:1:3:85d8a8fb5c6637961a6321a5a5cb0de7:ref
            SVCBMT-11|L0:0:3:73f86d38e7c980e2637fc013582d6279:ref L1:1:3:bf410c686c03c21cea06e3fac171ed08:ref
            SVCBMT-12|L0:0:3:95c70d07abb78a055cd139e18567eacc:ref L1:1:3:ebfab06b8c3d8f0905ab138dddca56a8:ref
            SVCBMT-13|L0:0:2:c6fef267dff2ebe6d172e5d0285eea5f:ref L1:1:2:938b4ded693391c40f802d9b8473d584:ref L2:2:2:2cc42817a35d2d7650b73b4b53c99ed9:ref
            SVCBS-1|L0:0:0:91d9c726106a5f4ca911edcc79099410:ref L1:16:0:500e67e1a4f6bc38df2b3424305c0606:ref
            SVCBS-2|L0:0:0:5f8e147dbe856a2811945d3bb52b1149:ref L1:16:0:687bef0d87c6c505628232c0d8a1bab7:ref
            SVCBS-3-r1|L0:0:7:e27c544347c43ee1e582d99aad0b307b:ref L1:16:7:5c88a2fb9e7af41396c3610ac0c6b7d7:jsvm
            SVCBS-4-r1|L0:0:7:1c3c27d3f9f110a99010167bc205593f:ref L1:16:7:96d06d056716bfdc4cf413e852968744:jsvm
            SVCBS-5-r1|L0:0:7:a209dccb4aae17e7ad7b5719357a8320:ref L1:16:7:017d158a8e855f2d7ce0bc58024c6d23:jsvm
            SVCBS-6-r1|L0:0:7:00e369836a9ef02979428f46cd18086f:ref L1:16:7:f6d079060bacbdafba13cfa18c422eac:jsvm L2:32:7:8d9ddbfeabd528585e8cc5046c7b5a01:jsvm
            SVCBS-7|L0:0:7:ef87ddf6cdf02a43d6ab935ab2e615b3:ref L1:16:7:9604bc729809b619ccb85e71ff29c5e8:ref
            SVCBS-8|L0:0:4:d8f2a95bb364f2866624416df7fd6b2b:ref L1:16:4:0e034ebf61d430bd9db6e2db0ba9f245:ref
            SVCBST-1|L0:0:3:ee73dca14a1d74f8cad711b5600b032c:ref L1:16:3:0712d5e3fcfc7d73df872d87951d4bdb:ref
            SVCBST-2|L0:0:3:ee73dca14a1d74f8cad711b5600b032c:ref L1:16:3:0712d5e3fcfc7d73df872d87951d4bdb:ref
            SVCBST-3|L0:0:3:ee73dca14a1d74f8cad711b5600b032c:ref L1:16:3:b97c9b60a1b65fa975bdf89b56f7d72c:ref
            SVCBST-4|L0:0:3:ee73dca14a1d74f8cad711b5600b032c:ref L1:16:3:b97c9b60a1b65fa975bdf89b56f7d72c:ref
            SVCBST-5|L0:0:3:ee73dca14a1d74f8cad711b5600b032c:ref L1:16:3:bd5ca12eb63665f3a9cbe73d6cc0547a:ref
            SVCBST-6-r1|L0:0:3:eae8a328f144b71a7eff9f0315e7ecbf:ref L1:16:3:2bfdf7386feb1052e0a3fed672708c00:jsvm
            SVCBST-7-r1|L0:0:3:1b71c083ffd5b949de093af7bc9b8f43:ref L1:16:3:940644a3e45017adf3cd60aca9f1316c:jsvm
            SVCBST-8-r1|L0:0:3:b1148e2efdbd99305c51797c154ae936:ref L1:16:3:bcd089ea28bd6631f2bd6824f86e2d32:jsvm
            SVCBST-9-r1|L0:0:3:b1148e2efdbd99305c51797c154ae936:ref L1:16:3:f51a08f23e81b3bb95ea096cec1cd76e:jsvm
            SVCBST-10-r1|L0:0:3:eae8a328f144b71a7eff9f0315e7ecbf:ref L1:16:3:d7f8f8b5d14f2c38206dc50d8dc871f9:jsvm
            SVCBST-11-r1|L0:0:3:eae8a328f144b71a7eff9f0315e7ecbf:ref L1:16:3:906dd984771b10dac0591d4d25f5bbdf:jsvm
            SVCBST-12-r1|L0:0:2:20ae5bd86be59ecfe9437dc677a91c82:ref L1:16:2:949628fc472e01fd3cde693260c78b2b:jsvm
            SVCBST-13|L0:0:2:649d65454f9728f7c10d15ca3e154720:ref L1:16:2:06a284053df5a37ab4fb66a7e9a91f94:ref
            SVCBST-14|L0:0:2:f3fbd2ff98c16609fd02ed641ea8159c:ref L1:16:2:5f24ddba1a16100cb2002a7c8366c923:ref L2:32:2:4f9ef740d3928265403033f46c853f7e:ref
            SVCBST-15|L0:0:3:cddcb20ca0ee134b24d34e8738f64818:ref L1:16:3:b9df12817f55be92b9e88b5d9bcb6d4b:ref L2:32:3:5c76c28df5a603071a7560b7908fc7e3:ref
            SVCBST-16-r1|L0:0:3:309e634f0158492932abe492d3aa9582:ref L1:16:3:0d6f99d556c4059b96789e7d5eb566fd:jsvm L2:32:3:ec70699776f4db2e2f0e0692885f9a47:jsvm
            SVCBST-17-r1|L0:0:3:f764d4799b99e0ab7e48a2fcc4e378ac:ref L1:16:3:bb4abb9cbf3c4c7133ad54a08f471aff:jsvm L2:32:3:f0695c534dc8544416ee036ac949b854:jsvm
            SVCBST-18-r1|L0:0:3:f764d4799b99e0ab7e48a2fcc4e378ac:ref L1:16:3:81d6aaad32786b7a50522e37f4f9447f:jsvm L2:32:3:7790317731673b71fd127c105dc694e3:jsvm
            SVCBST-19|L0:0:3:3062f65d01ac9c2360c2345860358def:ref L1:16:4:8e85fd925bc86abd0c9c6df34655d6bb:ref
            SVCBST-20|L0:0:0:d590b8a96c0f2044583471c8229e1a15:ref L1:16:1:38d95a90e93cf6aba4362d9ff0de01fa:ref
            SVCBSTC-1|L0:0:0:4a562e4e41a53310ec8bff3433a66e15:ref L1:16:0:0c843b22c4683e2d4ef3a620585b7142:ref L2:32:0:5939efb7c4e5a68bf76176245ffa5f49:ref
            SVCHCTS-1-r1|L0:0:1:3eb483f41880c4319ed64bcfdb11ce98:ref L1:16:2:1152c6ef4eaa7cafac3dfb825344958b:ref L2:32:2:278332120ee43286c1806ff5073a7379:ref L3:48:2:1a7926efccf5309b7374861fc437af72:ref L4:64:2:95d6c8086d3cbe443f672d9dd154f76c:ref L5:64:2:95d6c8086d3cbe443f672d9dd154f76c:ref L6:80:3:1502d0729022f1a36136a65af31700dc:ref L7:80:3:1502d0729022f1a36136a65af31700dc:ref
            SVCHICS-1|L0:0:0:0e3762f932178957bdfec38117837783:ref L1:16:0:5d63b51a252a9ad54a282d7a51452955:ref L2:32:0:d2598807d29af4087cf1703737f0944a:ref L3:48:0:5cdba001dc2388438d9fd97cfa8af58c:ref
            SVCHIS-1|L0:0:0:5d79ee246e536b1d05793e66d0743673:ref L1:16:0:b28fac54e73737024ffd79fda95d976f:ref L2:32:0:7aef0537b4da0214762ff3818bd8b326:ref
            SVCHIS-2|L0:0:0:b1c65ee31cca845a204c484c93040a8b:ref L1:16:0:f12a49649e890965d0a1bbe5fb2bab22:ref L2:32:0:34bd17af382de7f2c9e24fc38a5187e8:ref
            SVCHIS-3|L0:0:0:4a63b79ace4440fb394a608744720c55:ref L1:16:0:a3f40f728ead8398e7302d48323e0939:ref L2:32:0:6ec5dd35085955eb87cc0a614b0bf87f:ref
            SVCHM-1|L0:0:4:8532534cb0e7161aa3fc861484aef76d:ref L1:1:4:cf613b48440c985c1ee013bfe0397fe8:ref L2:2:4:fb2f9e99fe002a536824d79af2fa9da0:ref L3:3:4:19296840e30b19cd4c81020375d8c3e5:ref
            SVCHM-2|L0:0:0:98b04b6d4537a23d785e376f3b17f67f:ref L1:1:1:6d2e3ec46576ecf56d952cc8fe187b95:ref
            SVCHM-3|L0:0:0:b58f783ea5df8765e5ef90bec7a2f84e:ref L1:1:1:1255233cea747e51ae517c54bc6446d6:ref
            SVCHM-4|L0:0:0:b58f783ea5df8765e5ef90bec7a2f84e:ref L1:1:1:1255233cea747e51ae517c54bc6446d6:ref L2:2:2:eeac217f6597d239f9d2b5f55393220d:ref
            SVCHMTS-1-r1|L0:0:1:19814c565261c716a0d3d49724abdaf3:ref L1:1:1:07fc6793d8fc3f63d3f56bb1b8365842:ref-sub L2:2:1:8ce8797aa0721893e5017db399e4e0a9:ref-sub L3:16:2:3a0b85631d2265485126203d5ee10922:ref L4:17:2:42e5fceea9686498246a4ada89f9a13e:ref-sub L5:17:2:ea87640c390e4e2f20fa8553dcecfe78:ref
            SVCHMTS-2-r1|L0:0:0:5546d1a56d323fde3ddfa5a62cac186a:ref L1:1:0:71fb9a91cd0715fd24416722c2c49a78:ref L2:16:0:394e0a741e77e42633a4fd3c3408103a:ref
            SVCHS-1-r1|L0:0:3:b967a3b809c0a16243c417ffa85afcd0:ref L1:16:3:993972e8fb4bc93fbb214dc8e52f9ea8:ref
            SVCHS-2-r1|L0:0:3:b9fc111412ead50bb77737c9b1c12d13:ref L1:16:3:16d3d81e5e095a79058abec4a0581006:ref
            SVCHST-1-r1|L0:0:1:baba80872ef75c15b2bb24d6d4b4c6b1:ref L1:16:2:9b62015f3643ceff1f86c2e6a6eb6d49:ref L2:32:2:3edc45ae26c4434769e6e9e0cfe94e3d:ref
            SVCHST-2-r1|L0:0:1:4ab6572eebff1caa9c6088cdf43473a3:ref L1:16:2:c14f222f729f382f94b983bbc0bdc7da:ref L2:32:2:9ec385f7a960bbf0f7940d4ef52d6d98:ref
            SVCHST-3-r1|L0:0:3:1a2b15a4bc1af8a9efcf9569b275fae1:ref L1:16:4:4d310c1d19716578d6305dfa4691ed8a:ref
            SVCHST-4-r1|L0:0:1:657085e68fe101c5063a396295e82c84:ref L1:16:2:5d1b01654bde6f00dc9e7fad9bb1e779:ref
            SVCHSTC-1-r1|L0:0:0:5546d1a56d323fde3ddfa5a62cac186a:ref L1:1:0:3d95aac68d1c63d338cac5952365d414:ref L2:16:0:98412dc8e109897d2019451078470e3d:ref
            """;

    /**
     * One layer of a vector: the label of its sub-bitstream ({@code L0} for the base layer), its
     * {@code DQId}, the highest {@code temporal_id} it holds, the MD5 of its decoding, whether that
     * decoding is the JSVM decoder's, and whether only its own sub-bitstream selects it.
     */
    record Layer(String label, int dqId, int temporalId, String md5, boolean jsvm, boolean subBitstreamOnly) {
        @Override
        public String toString() {
            return label + (jsvm ? " (JSVM)" : "");
        }
    }

    record Vector(String name, List<Layer> layers) {
        @Override
        public String toString() {
            return name;
        }

        /** Returns the layer whose sub-bitstream holds every other layer. */
        Layer top() {
            return layers.getLast();
        }

        /** Returns the name of the archive entries, the vector name without its revision. */
        String stem() {
            return name.replaceAll("-r\\d+$", "");
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            var layers = new ArrayList<Layer>();
            for (var entry : parts[1].split(" ")) {
                var fields = entry.split(":");
                layers.add(new Layer(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), fields[3],
                        fields[4].startsWith("jsvm"), fields[4].endsWith("-sub")));
            }
            result.add(new Vector(parts[0], List.copyOf(layers)));
        }
        return result;
    }

    static Stream<Arguments> layers() {
        return vectors().stream().flatMap(vector -> vector.layers().stream().map(layer -> Arguments.of(vector, layer)));
    }

    static Stream<Arguments> selectableLayers() {
        return vectors().stream().flatMap(vector -> vector.layers().stream()
                .filter(layer -> !layer.subBitstreamOnly()).map(layer -> Arguments.of(vector, layer)));
    }

    /**
     * Reads the sub-bitstream of a layer from the vector's archive set.
     */
    static byte[] read(Vector vector, Layer layer) throws IOException {
        var archive = Conformance.archive("h264-SVC-" + vector.name());
        var entry = vector.stem() + "-" + layer.label() + ".264";
        var member = archive.members().stream().filter(name -> name.equals(entry) || name.endsWith("/" + entry))
                .findFirst().orElseThrow(() -> new AssertionError(vector + " has no member " + entry));
        return Files.readAllBytes(archive.fetch(member));
    }

    static void assertLayer(Vector vector, Layer layer, H264SvcStreamsTest.Output output) {
        assertEquals(layer.md5(), output.md5(), vector + " " + layer + " (" + output.count() + " frames)");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layers")
    @DisplayName("decodes the sub-bitstream of every layer bit-exactly at its highest layer")
    void subBitstream(Vector vector, Layer layer) throws IOException {
        var output = H264SvcStreamsTest.decode(read(vector, layer),
                H264DecoderOptions.builder().highestLayer().build());
        assertLayer(vector, layer, output);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("selectableLayers")
    @DisplayName("decodes every layer bit-exactly out of the sub-bitstream of the highest layer")
    void selection(Vector vector, Layer layer) throws IOException {
        var options = H264DecoderOptions.builder().targetLayer(layer.dqId() >> 4, layer.dqId() & 15)
                .maxTemporalId(layer.temporalId()).build();
        var output = H264SvcStreamsTest.decode(read(vector, vector.top()), options);
        assertLayer(vector, layer, output);
    }
}
