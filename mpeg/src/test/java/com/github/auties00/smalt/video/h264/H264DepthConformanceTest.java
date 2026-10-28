package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the frame-compatible stereo (MFC High), frame-compatible stereo with depth (MFC Depth High) and
 * multiview with depth (Multiview Depth High) bitstreams of ITU-T H.264.1 (02/2016) and compares every texture
 * view, depth view and enhanced resolution view with its reference: inter-view prediction from the processed
 * base view (side by side and top-bottom packing, default and signaled grid positions, the filter on and off,
 * field processing), depth views of the texture's size or smaller, coded as frames or fields, depth views
 * missing for some views, the non-linear depth representation, and two and three views. The expected MD5 is
 * that of the suite's md5 file ({@code ref}), or, marked {@code atm}, that of 3DV-ATM 14.0 (ITU-T H.264.2)
 * with direct prediction following the NOTE of ITU-T H.264 G.8.4, which makes {@code colZeroFlag} 0 when
 * {@code RefPicList1[0]} is an inter-view reference: the suite's md5s of MVDIV-1 views 6 and MVPCT-2 views 5
 * were made by 3DV-ATM without that rule, and the md5 file of MVDDR-4 lists depth view 5 in place of depth view
 * 4 ({@code tools/testdata/README.md}). Monochrome depth frames are hashed as planar 4:2:0 with chroma 128, as
 * the reference decoders write them. Only the inner archives of each suite are downloaded, through the archive
 * sets {@code h264-MFC-2016}, {@code h264-MFCD-2016} and {@code h264-MVCD-2016}.
 */
@DisplayName("H.264 MFC, MFC depth and MVCD conformance bitstreams (ITU-T H.264.1)")
class H264DepthConformanceTest {
    /**
     * Family | inner archive | bitstream entry | {@code view_id} of the base view | layers, each {@code key:md5:source}, the key being {@code v}
     * and the {@code view_id} for a texture view, {@code d} for a depth view, {@code e} for a view reconstructed
     * at enhanced resolution, the source {@code ref} for the suite's md5 file and {@code atm} for 3DV-ATM 14.0.
     */
    private static final String MANIFEST = """
            MFC|MFC/MFCFLD-1.zip|MFCFLD-1/MFCFLD-1.264|0|v0:a3fe9e7fdd7e2985abe7a870adafbbd5:ref v1:1e3bc889984507b48e91e45f28896a34:ref e0:cf7bb787eb33de08e7e8fcbcfe363294:ref e1:981a3de05e3ab1f50c692cf0730acbf7:ref
            MFC|MFC/MFCFLD-2.zip|MFCFLD-2/MFCFLD-2.264|0|v0:9952559525b9fe53cd5fee23eb6b51a2:ref v1:ebe09ace8cf1fdeebad8cce1ad3a312a:ref e0:2166930b292878a64815e6c50cbff26c:ref e1:0ca1d6eb561427a6effcdb9d20de9051:ref
            MFC|MFC/MFCFLD-3.zip|MFCFLD-3/MFCFLD-3.264|0|v0:084f2f025c03ecdf6637032d2b09a18d:ref v1:ce978fc1f154e0740e4117afe023d224:ref e0:89b928696a59d36c4777c8d4c71370e2:ref e1:9f90e76b7325737673f997815ec419db:ref
            MFC|MFC/MFCGRD-1.zip|MFCGRD-1/MFCGRD-1.264|0|v0:4c2f6b4cc8484284774224a986c9bab1:ref v1:c9f69cbf33d1fb48f342b90f7af31968:ref e0:61d6f2f195b6f8c9c68d2b691b1f45dc:ref e1:13e40e8d80596c1acda1851f95a70f90:ref
            MFC|MFC/MFCMFM-1.zip|MFCMFM-1/MFCMFM-1.264|0|v0:5e88fa4e4d59fa1ced1030dd582dbe30:ref v1:bccf690b2ba898484e34961d41bc2a8c:ref e0:61a50bfb8e55e1fc1270eb5ab00dab00:ref e1:95adffea8f2b8b71ca6f9f994b3b81e3:ref
            MFC|MFC/MFCRFT-1.zip|MFCRFT-1/MFCRFT-1.264|0|v0:4998c463ff9e308e75dfb12d81fda2b9:ref v1:de7f182611c193ae79edd92c174c162d:ref e0:e294ea41e4a6466492b306ec3c0c9618:ref e1:5bac05e7411d2cd65284b26cc4d8e601:ref
            MFC|MFC/MFCRFT-2.zip|MFCRFT-2/MFCRFT-2.264|0|v0:4998c463ff9e308e75dfb12d81fda2b9:ref v1:488fac6d7d1a883b0396ac129ec9168a:ref e0:f55b28334923686cee201175a869365b:ref e1:ce4df3c9f915ff82db096b8089742526:ref
            MFCD|MFCD/MFCDDR-1.zip|MFCDDR-1/MFCDDR-1.bin|0|v0:60915c7b9e2f1b6bd0fd80973bf47b52:ref v1:3b9ad677be0ce160df9521347b53b6e7:ref d0:1a9bf41a5faae9df51b507867d39722e:ref d1:d29962ca5aaae6e0673228cfd6382463:ref e0:1fd6e058505147a6d797d5ac11c67f95:ref e1:6fe3262fbb6edb387bb87146ef8a11cb:ref
            MFCD|MFCD/MFCDDR-2.zip|MFCDDR-2/MFCDDR-2.bin|0|v0:60915c7b9e2f1b6bd0fd80973bf47b52:ref v1:3b9ad677be0ce160df9521347b53b6e7:ref d0:a0c9fa783327981da86c2cbfd538604c:ref d1:2e8b987a16aa76b588cc7a11c865853f:ref e0:1fd6e058505147a6d797d5ac11c67f95:ref e1:6fe3262fbb6edb387bb87146ef8a11cb:ref
            MFCD|MFCD/MFCDFLD-1.zip|MFCDFLD-1/MFCDFLD-1.bin|0|v0:27f48ed81e551b25cbbd59a0b4217276:ref v1:585a03c9456d0e9fcf3a0983139dd321:ref d0:1a9bf41a5faae9df51b507867d39722e:ref d1:d29962ca5aaae6e0673228cfd6382463:ref e0:b3c7bcd4694ebb175d3ab20f98c2c388:ref e1:90ec082dbcbd4a2d8ae346759984ae90:ref
            MFCD|MFCD/MFCDFLD-2.zip|MFCDFLD-2/MFCDFLD-2.bin|0|v0:27f48ed81e551b25cbbd59a0b4217276:ref v1:585a03c9456d0e9fcf3a0983139dd321:ref d0:a353c2be40cff4dc38d54bcf02376c5b:ref d1:d79eccd7998dd3598e3ae48c98efc58c:ref e0:b3c7bcd4694ebb175d3ab20f98c2c388:ref e1:90ec082dbcbd4a2d8ae346759984ae90:ref
            MFCD|MFCD/MFCDMFC-1.zip|MFCDMFC-1/MFCDMFC-1.bin|0|v0:2b10727a937614df7ce07b9c1ae403cc:ref v1:11083d6085b508cec09f2e86f7d00fcd:ref d0:1a9bf41a5faae9df51b507867d39722e:ref d1:d29962ca5aaae6e0673228cfd6382463:ref e0:7823b009bd5c09bb1ee793975752fed7:ref e1:22a24c2943d9e4d346386f4c6eb7f1d6:ref
            MFCD|MFCD/MFCDTDC-1.zip|MFCDTDC-1/MFCDTDC-1.bin|0|v0:60915c7b9e2f1b6bd0fd80973bf47b52:ref v1:3b9ad677be0ce160df9521347b53b6e7:ref d0:1a9bf41a5faae9df51b507867d39722e:ref d1:d29962ca5aaae6e0673228cfd6382463:ref e0:1fd6e058505147a6d797d5ac11c67f95:ref e1:6fe3262fbb6edb387bb87146ef8a11cb:ref
            MFCD|MFCD/MFCDTDC-2.zip|MFCDTDC-2/MFCDTDC-2.bin|0|v0:60915c7b9e2f1b6bd0fd80973bf47b52:ref v1:3b9ad677be0ce160df9521347b53b6e7:ref d0:1a9bf41a5faae9df51b507867d39722e:ref d1:d29962ca5aaae6e0673228cfd6382463:ref e0:1fd6e058505147a6d797d5ac11c67f95:ref e1:6fe3262fbb6edb387bb87146ef8a11cb:ref
            MVCD|MVCD/MVDDR-3_r1.zip|MVDDR-3_r1/MVDDR-3_r1.bin|3|v1:555a6eb73c49ed01af6ad92db4cc33c5:ref v3:c2987ac342e1afc30718a5035ba4c9af:ref d1:2db60058d842304b6e16a036b9f2c027:ref d3:9b90f0e7dd77cebc9954077d7488b0b5:ref
            MVCD|MVCD/MVDDR-4_r1.zip|MVDDR-4_r1/MVDDR-4_r1.bin|4|v4:c08e4d2d75411d16851015a453a4fbfa:ref v5:d0e195d823097217e9ab92b93a3e1786:ref d4:9405d6df6293637d563b8117e02d2267:atm d5:6c0ff23533c9e9ec633f77450cf95bc5:ref
            MVCD|MVCD/MVDIL-1_r1.zip|MVDIL-1_r1/MVDIL-1_r1.bin|3|v1:2011c640e87ac02630bdc7041603edf7:ref v3:ca8916cd6ec63077d7508623c12ce300:ref d1:b9caaec19e16fc584a66b94210bdf209:ref d3:43c3e472594ea87c5d5cdcd66a8c8b5c:ref
            MVCD|MVCD/MVDIL-2_r1.zip|MVDIL-2_r1/MVDIL-2_r1.bit|3|v1:761c332f540fa2fc46ab93344a102b94:ref v3:d99a5c5a6e5daf0e1d8e0a4b07ff497f:ref d1:5139d58549e13ca2b21a4cd932467312:ref d3:b77f87d04366921e94cae0f661c0be4d:ref
            MVCD|MVCD/MVDIV-1_r1.zip|MVDIV-1_r1.bit|7|v5:ef8c56004a8bebe504f5371bfe236f57:ref v6:ffdb54258a3cb8d11aded84652d7e927:atm v7:3977a9fee811117d5911c975a381c28d:ref d5:bc9764f83a96c412ec6f1a2d802cd919:ref d6:5bd12969970090218b2e729b8e878836:atm d7:b7aa19a17f1b36985c9d270f287b2862:ref
            MVCD|MVCD/MVDIV-2_r1.zip|MVDIV-2_r1/MVDIV-2_r1.bin|1|v1:abba81e29def7b65b550a1dff1f53811:ref v3:308438c4f8b10eaf25bdd4accbcd15b4:ref v5:420b18f3f9d4e113cbb534d9c8990559:ref d1:4a4ecd1615b2a2510f1c16c055bf118b:ref d3:12604e91ed9907982b0748d10ba64584:ref d5:0400d057ccc719a13cddf80654e0fa50:ref
            MVCD|MVCD/MVDIV-3_r1.zip|mvdiv-3_r1/mvdiv-3_r1.bit|4|v3:3a7b658b376f31760d11b107b2dc76a6:ref v4:eb2526eebf5e10d03b9f6174e423a3e2:ref v5:f6f8c4149bcf90defc8ba05d505b463f:ref d3:25a2a4e75769c4d823d608abb12eb12f:ref d4:c0515db9e25948269b12edbe2a4241bb:ref d5:b354c109c48b8a0ea8c1758a671037c5:ref
            MVCD|MVCD/MVDIV-4_r1.zip|mvdiv-4_r1/mvdiv-4_r1.bit|6|v5:a8285d4794c227c868320591ef9af596:ref v6:583c24e21319feeefd9540c925828f8d:ref v7:db713e7a76d88e4f79f1b3b495c3edc3:ref d5:2cd8caa63c53ec7696b0bea67b4efbb9:ref d6:bb2edcf0206deaa0c7c929b8062d6419:ref d7:04ef4a8bec76a110632f5cd60fdd2e75:ref
            MVCD|MVCD/MVDRS-1_r1.zip|MVDRS-1_r1.bit|3|v1:6e03f485a74ad65fff947dbbdd8dfb35:ref v3:03669fee41b93c2276c0b17945dea45a:ref v5:1ded9fd4d3b3b08757c4d4e3a83bcf37:ref d1:70028603c9733291a7b9a265f1a0ca6d:ref d5:fba598cdde7d85f589c429916ccb5899:ref
            MVCD|MVCD/MVDVC-1_r1.zip|MVDVC-1_r1/MVDVC-1_r1.bin|3|v1:d90f17dd13e4d794b22f580d57b2f49f:ref v3:b21ef958883af7ccea8204af20ae4bb3:ref d1:7fd899a97e52b0ef1458e0ec251a73e7:ref d3:063c71bd71e26e409afd2e9b62317c9d:ref
            MVCD|MVCD/MVDVC-2_r2.zip|MVDVC-2/MVDVC-2.bin|4|v4:797815e60a55f9f394716e18892c02af:ref v5:a1d7990d6a10df218be699f4f7cf0527:ref d4:291dd9ca2abf993e16bdbca5b61d6a0f:ref d5:3ba307afd9a02f7a4491f9eebbbc3602:ref
            MVCD|MVCD/MVDVC-3_r1.zip|MVDVC-3_r1.bit|3|v1:6e03f485a74ad65fff947dbbdd8dfb35:ref v3:03669fee41b93c2276c0b17945dea45a:ref v5:1ded9fd4d3b3b08757c4d4e3a83bcf37:ref d1:aad86d380b3d047a308f228373b28a04:ref d3:e7ac80630d9ff7a0351842f892d5bbf3:ref d5:2c82d01f541d2767fee495acc969fb90:ref
            MVCD|MVCD/MVDVC-4_r1.zip|MVDVC-4_r1.bit|6|v5:73bad68ec4cf74cce50dcd5b66914ef4:ref v6:50eb1667a62addde10ce9d9d6ac6e484:ref v7:6435f884ca1e1359faed32a00c2e4735:ref d5:702188cce3c9da6a124f0b55906579e7:ref d6:a7d3f31bca1a2a36b68bbc229312ab2b:ref d7:d4b77cb35762833827dba792da4d53f7:ref
            MVCD|MVCD/MVPCT-1_r1.zip|MVPCT-1_r1/MVPCT-1_r1.bit|1|v1:f952273b5996eb75276c168af7d76689:ref v5:efa9a853c6ffcfe5b638e72d42092e47:ref v9:4413692718d50dedfeb7673276b3a848:ref d1:80fc57d9ea12a7b2f19b34b0e68f8bbb:ref d5:d044ab71c5a85466a9627db4eeb7ec11:ref d9:ed380add2af11e3e0ceea83e1de233db:ref
            MVCD|MVCD/MVPCT-2.zip|MVPCT-2.bit|1|v1:653cabdef5b7c22cb1f8aaad4b067688:ref v5:7e7a5a9f7f4f81cfb4e98343a93abcfd:atm v9:6db03450367f57b398e777fd7d233c90:ref d1:7f5ca35fed4d4529599a489f41758a60:ref d5:47196c858da060941b1d1394796ccba8:atm d9:6a7387c2b0afb73590c4f8180572ddbb:ref
            """;

    /**
     * The inner archives of the multiview with depth suite that the smoke tier holds.
     */
    private static final String SMOKE = "h264-MVCD-2016-smoke";

    record Layer(String key, String md5, boolean atm) {
        @Override
        public String toString() {
            return key + (atm ? " (3DV-ATM)" : "");
        }
    }

    record Vector(String family, String member, String entry, int baseViewId, List<Layer> layers) {
        @Override
        public String toString() {
            return member.substring(member.indexOf('/') + 1, member.length() - 4);
        }

        List<Layer> decoded() {
            return layers.stream().filter(layer -> !layer.key().startsWith("e")).toList();
        }

        List<Layer> enhanced() {
            return layers.stream().filter(layer -> layer.key().startsWith("e")).toList();
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            var layers = new ArrayList<Layer>();
            for (var item : parts[4].split(" ")) {
                var fields = item.split(":");
                layers.add(new Layer(fields[0], fields[1], fields[2].equals("atm")));
            }
            result.add(new Vector(parts[0], parts[1], parts[2], Integer.parseInt(parts[3]), List.copyOf(layers)));
        }
        return result;
    }

    static Stream<Vector> all() {
        return vectors().stream();
    }

    static Stream<Vector> frameCompatible() {
        return all().filter(vector -> !vector.family().equals("MVCD"));
    }

    static Stream<Vector> threaded() {
        return all().filter(vector -> vector.toString().matches("MFCFLD-1|MFCMFM-1|MFCDFLD-2|MVDIL-2_r1|MVDRS-1_r1"
                + "|MVPCT-2|MVDVC-3_r1"));
    }

    static Vector vector(String name) {
        return all().filter(vector -> vector.toString().equals(name)).findFirst().orElseThrow();
    }

    /**
     * Reads the bitstream of a vector out of its inner archive.
     */
    static byte[] bitstream(Vector vector) throws IOException {
        Conformance.Archive archive = null;
        if (vector.family().equals("MVCD") && Conformance.archive(SMOKE).members().contains(vector.member())) {
            archive = Conformance.archive(SMOKE);
        }
        if (archive == null) {
            archive = Conformance.archive("h264-" + vector.family() + "-2016");
        }
        return entry(Files.readAllBytes(archive.fetch(vector.member())), vector.entry());
    }

    static byte[] entry(byte[] zip, String name) throws IOException {
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return input.readAllBytes();
                }
            }
        }
        throw new AssertionError("no entry " + name);
    }

    /**
     * The frames a decode produced, by layer key: the running MD5 and count, plus the layer of every frame in
     * output order.
     */
    static final class Output implements Consumer<VideoFrame> {
        final Map<String, MessageDigest> digests = new TreeMap<>();

        final Map<String, Integer> counts = new LinkedHashMap<>();

        final List<VideoLayer> layers = new ArrayList<>();

        private final String prefix;

        Output(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public void accept(VideoFrame frame) {
            var layer = frame.sideData().layer().orElse(null);
            var key = layer == null ? "base" : (layer.type() == VideoLayer.Type.DEPTH ? "d" : prefix) + layer.viewId();
            digests.computeIfAbsent(key, ignored -> digest()).update(samples((YuvPicture) frame.picture()));
            counts.merge(key, 1, Integer::sum);
            layers.add(layer);
        }

        Map<String, String> md5s() {
            var result = new TreeMap<String, String>();
            digests.forEach((key, digest) -> {
                try {
                    result.put(key, HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()));
                } catch (CloneNotSupportedException exception) {
                    throw new AssertionError(exception);
                }
            });
            return result;
        }

        private static MessageDigest digest() {
            try {
                return MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    /**
     * Returns the planar samples of a picture, a monochrome one with 4:2:0 chroma planes of 128.
     */
    static byte[] samples(YuvPicture picture) {
        if (picture.chromaFormat() != ChromaFormat.MONOCHROME) {
            return H264TestStreams.serialize(picture);
        }
        var width = picture.width();
        var height = picture.height();
        var out = new byte[width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)];
        for (var y = 0; y < height; y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), out, y * width, width);
        }
        Arrays.fill(out, width * height, out.length, (byte) 128);
        return out;
    }

    static Output decode(byte[] stream, H264DecoderOptions options, String prefix) {
        var output = new Output(prefix);
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var nal : H264TestStreams.splitAnnexB(stream)) {
                decoder.decode(H264MvcConformanceTest.packet(nal), output);
            }
            decoder.flush(output);
        }
        return output;
    }

    static Map<String, String> expected(List<Layer> layers, String prefix) {
        var result = new TreeMap<String, String>();
        for (var layer : layers) {
            result.put(layer.key().startsWith("e") ? prefix + layer.key().substring(1) : layer.key(), layer.md5());
        }
        return result;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes every texture and depth view bit-exactly")
    void allLayers(Vector vector) throws IOException {
        var output = decode(bitstream(vector), H264DecoderOptions.builder().allViews().depth(true).build(), "v");
        assertEquals(expected(vector.decoded(), "e"), output.md5s(), vector + " " + vector.decoded());
        var frames = output.counts.values().iterator().next();
        output.counts.forEach((key, count) -> assertEquals(frames, count, vector + " frames of " + key));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threaded")
    @DisplayName("decodes every texture and depth view bit-exactly with four frame threads")
    void allLayersThreaded(Vector vector) throws IOException {
        var output = decode(bitstream(vector), H264DecoderOptions.builder().allViews().depth(true).threads(4).build(),
                "v");
        assertEquals(expected(vector.decoded(), "e"), output.md5s(), vector.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameCompatible")
    @DisplayName("reconstructs the left and right views at enhanced resolution bit-exactly")
    void enhancedResolution(Vector vector) throws IOException {
        var output = decode(bitstream(vector), H264DecoderOptions.builder().enhancedResolution(true).build(), "e");
        assertEquals(expected(vector.enhanced(), "e"), output.md5s(), vector.toString());
        for (var i = 0; i < output.layers.size(); i++) {
            assertEquals(i % 2, output.layers.get(i).id(), vector + " frame " + i + " is out of left, right order");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameCompatible")
    @DisplayName("reconstructs the views at enhanced resolution bit-exactly with four frame threads")
    void enhancedResolutionThreaded(Vector vector) throws IOException {
        var output = decode(bitstream(vector), H264DecoderOptions.builder().enhancedResolution(true).threads(4)
                .build(), "e");
        assertEquals(expected(vector.enhanced(), "e"), output.md5s(), vector.toString());
    }

    @Nested
    @DisplayName("layer selection")
    class Selection {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264DepthConformanceTest#all")
        @DisplayName("outputs the base texture view alone by default")
        void baseView(Vector vector) throws IOException {
            var output = decode(bitstream(vector), H264DecoderOptions.defaults(), "v");
            assertEquals(List.of("base"), List.copyOf(output.md5s().keySet()));
            assertEquals(expected(vector.decoded(), "e").get("v" + vector.baseViewId()), output.md5s().get("base"),
                    vector.toString());
        }

        @Test
        @DisplayName("outputs the depth view of the base view next to it, as layer 0 of type DEPTH")
        void baseDepth() throws IOException {
            var vector = vector("MVDVC-3_r1");
            var output = decode(bitstream(vector), H264DecoderOptions.builder().depth(true).build(), "v");
            var expected = expected(vector.decoded(), "e");
            assertEquals(List.of("base", "d3"), List.copyOf(output.md5s().keySet()));
            assertEquals(expected.get("v3"), output.md5s().get("base"));
            assertEquals(expected.get("d3"), output.md5s().get("d3"));
            for (var layer : output.layers) {
                if (layer != null) {
                    assertEquals(new VideoLayer(0, 3, VideoLayer.Type.DEPTH), layer);
                }
            }
        }

        @Test
        @DisplayName("outputs no depth frame for a base view without a depth view")
        void baseWithoutDepth() throws IOException {
            var output = decode(bitstream(vector("MVDRS-1_r1")), H264DecoderOptions.builder().depth(true).build(), "v");
            assertEquals(List.of("base"), List.copyOf(output.md5s().keySet()));
        }

        @Test
        @DisplayName("names the view order index of a depth view among all views when an earlier view has none")
        void depthViewOrder() throws IOException {
            var output = decode(bitstream(vector("MVDRS-1_r1")), H264DecoderOptions.builder().outputViews(1, 5)
                    .depth(true).build(), "v");
            var depth = output.layers.stream().filter(layer -> layer != null
                    && layer.type() == VideoLayer.Type.DEPTH).map(layer -> layer.viewId() + "@" + layer.id())
                    .distinct().sorted().toList();
            // MVDRS-1 codes views 3, 1 and 5 in that view order, with depth views for views 1 and 5 only
            assertEquals(List.of("1@1", "5@2"), depth);
        }

        @Test
        @DisplayName("decodes a non-base depth view through the depth views it predicts from and outputs it alone")
        void singleDepthView() throws IOException {
            var vector = vector("MVPCT-2");
            var output = decode(bitstream(vector), H264DecoderOptions.builder().outputViews(9).depth(true).build(),
                    "v");
            var expected = expected(vector.decoded(), "e");
            assertEquals(List.of("d9", "v9"), List.copyOf(output.md5s().keySet()));
            assertEquals(expected.get("v9"), output.md5s().get("v9"));
            assertEquals(expected.get("d9"), output.md5s().get("d9"));
        }
    }

    @Nested
    @DisplayName("3D-AVC streams")
    class ThreeDimensional {
        @Test
        @DisplayName("decode their base view, reporting their other views unsupported once")
        void unsupported() throws IOException {
            var stream = entry(Files.readAllBytes(Conformance.archive(SMOKE).fetch("MVCD/MVDDR-1_r2.zip")),
                    "MVDDR-1_r2/MVDDR-1_r2.BIT");
            var output = new Output("v");
            var failures = new ArrayList<MediaException.Unsupported>();
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().allViews()
                    .depth(true).build())) {
                for (var nal : H264TestStreams.splitAnnexB(stream)) {
                    try {
                        decoder.decode(H264MvcConformanceTest.packet(nal), output);
                    } catch (MediaException.Unsupported failure) {
                        failures.add(failure);
                    }
                }
                decoder.flush(output);
            }
            assertEquals(1, failures.size(), "failures: " + failures);
            assertEquals("h264 3d-avc", failures.getFirst().format());
            // the base texture view of MVDDR-1, Dec_HPC3S06R1TtV3 of its md5 file
            assertEquals("dbd671e2d3a08dff6e5ffa7b20b3efd7", output.md5s().get("v3"));
            assertTrue(output.md5s().keySet().stream().allMatch(key -> key.equals("v3")), "" + output.md5s());
        }
    }
}
