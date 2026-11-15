package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.VideoLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the 3D-AVC bitstreams (Enhanced Multiview Depth High) of ITU-T H.264.1 (02/2016) and compares every
 * texture and depth view with its reference, bit for bit: the subset sequence parameter sets with the 3D-AVC
 * extension, depth parameter sets, the 3D-AVC NAL unit header and slice header (slice header prediction from
 * texture to depth and between views), depth views at half the texture resolution, the non-linear depth
 * representation, and the texture coding tools of the non-base views: depth-based motion vector prediction
 * (MVDCT-2, MVDCT-9), adaptive luminance compensation (MVDCT-3), view synthesis prediction from the view's own
 * depth (MVDCT-1) and, in texture-first coding, with the neighboring block disparity refined from the base view's
 * depth (MVDCT-7), the neighboring block disparity alone (MVDCT-6), run-length skip coding and their combinations
 * (MVDCT-5, MVDCT-8, MVDCT-10, MVDDR3D-1, MVDDR3D-2). The expected MD5 is that of the suite's md5 file
 * ({@code ref}) or, marked {@code atm}, that of 3DV-ATM 14.0 (ITU-T H.264.2): the md5 file of MVDCT-4 hashes the
 * depth views after the reference decoder's optional depth normalization, and that of MVDCT-10 lists another
 * decode of view 4 ({@code tools/testdata/README.md}).
 */
@DisplayName("H.264 3D-AVC conformance bitstreams (ITU-T H.264.1)")
class H264Avc3dConformanceTest {
    /**
     * The vectors, in the layout of {@link H264DepthConformanceTest}, from the archive set {@code h264-3DAVC-2016}.
     */
    private static final String MANIFEST = """
            3DAVC|3DAVC/MVDCT-1_r2.zip|MVDCT-1_r2/MVDCT-1_r2.bit|5|v1:0380472ff13dbead90a5a82c9a6dd619:ref v5:cedb61470aaf0f3b79fbf5a1768a89c5:ref v9:f707d282f056385a816dbfd3817276b3:ref d1:1123095ce2bd86a705330ac0bbf60b57:ref d5:8bf7b22bacd5efeddb010e6196a316f4:ref d9:1e7a31d71e92b270d65ff61105abd7cb:ref
            3DAVC|3DAVC/MVDCT-2_r2.zip|MVDCT-2_r2.bin|5|v1:07b0a2bdcb60acba73dc83a7f0b5d7b6:ref v5:2bfe0c1efe4ddadd0a02209674e56877:ref v9:7463850b72082c6a4844bcb417c4405b:ref d1:49b3d204112797530a674cef8cb67dbf:ref d5:e7def6623a01d322ec3c15665061baa9:ref d9:4f9e3338fddddbbba3ad817379932362:ref
            3DAVC|3DAVC/MVDCT-3_r1.zip|MVDCT-3_r1.bit|3|v1:642e7d5950282485eeb377495074e824:ref v3:c339312c4644417353eed0617fbccfd2:ref v5:36062333b99f35cb2412cf01e3b47c4d:ref d1:916220413d2298f51ce943874f102b28:ref d3:af49305e3f52dce825d3b3549fa599c6:ref d5:3ca4e006f00457ce22a7063c1d506a79:ref
            3DAVC|3DAVC/MVDCT-4_r2.zip|MVDCT-4_r2/MVDCT-4_r2.bit|4|v3:e6b88c61a75336414ed626a3e2304634:ref v4:75069059d6317f5a00f7f46d259bdf5a:ref v5:898d974e254e012655337fd1263e45f8:ref d3:f17f8a1f0c9fe2737c685a6847583a2f:atm d4:5598b6a3aba72314afc1ac3a221e567e:atm d5:f8c796e125291b3af1512382ddff9fba:atm
            3DAVC|3DAVC/MVDCT-5-r1.zip|Balloons.bit|3|v1:a813eebfb942493354ee45062a5535c6:ref v3:86d5562c8840cbccb0c6f92c7344a135:ref v5:b37b5067b3c301be4cb24503a57e81a5:ref d1:a452ee696e127e0de8ee835b99e52eef:ref d3:b1a5389ec72115d0603cd5634a1641a5:ref d5:cef716fb618cd7f3587f1b16bd03e27a:ref
            3DAVC|3DAVC/MVDCT-6-r1.zip|MVDCT-6.bit|3|v1:f4b891e9edd92a99509b815964e35ef7:ref v3:441004daca7e8ab039f02f441b70f6d8:ref d1:7bf6c077f8b1b9cf6b8693e024a81b89:ref d3:995d43f87ced3a3c82160ae9808d61c6:ref
            3DAVC|3DAVC/MVDCT-7-r1.zip|MVDCT-7.bit|3|v1:97851c15f30d63a61550d1d095905656:ref v3:441004daca7e8ab039f02f441b70f6d8:ref d1:1c9e613c30743ae0ea7d42b1ba08d46b:ref d3:995d43f87ced3a3c82160ae9808d61c6:ref
            3DAVC|3DAVC/MVDCT-8_r2.zip|MVDCT8_r2/MVDCT-8_r2.bit|1|v1:ff55aba95706a6da81db0e716797f4b3:ref v3:f9b431afa0719f60fcafee9ad7d622db:ref v5:09ca4051455843da92c16faa5d2753e9:ref d1:45ef44c70174ce9adb6daa5e52d90825:ref d3:3bdac0d8e15368d9a8140f83938ed22f:ref d5:e258009216af39901453ec87d87989b6:ref
            3DAVC|3DAVC/MVDCT-9_r1.zip|MVDCT-9_r1.bit|6|v5:87e120f5eb827c91b13905b55e615f40:ref v6:5149e4593d82e26e6bde6868e0f04964:ref v7:326d0736cc4143f05dd8906c462e4052:ref d5:394fad62d67d90ab060d92c83c8899a8:ref d6:81a6c78f02997c33fb0a018fb65f24c1:ref d7:604705ed8faf5c8100c165c3914678e6:ref
            3DAVC|3DAVC/MVDCT-10_r2.zip|MVDCT-10_r2.bit|5|v3:a6193ff42c56ec1aca78f836c71d09b9:ref v4:dfb4b5d3791f1f29e4019981537413ea:atm v5:70006db91ac4df14a14a71afc1f7b093:ref d3:ecd9e8deceb732bea07b5d2aef70a763:ref d4:fba66b01fdb3f3fd637c57a0c3b17c59:atm d5:6447c5e972679f18bca3aa8cec4a11e6:ref
            3DAVC|3DAVC/MVDDR3D-1_r1.zip|MVDDR3D-1-r1/MVDDR3D-1-r1/MVDDR3D-1-r1.BIT|3|v1:cb5f60b0ed563114127531b6b7563c3a:ref v3:dbd671e2d3a08dff6e5ffa7b20b3efd7:ref v5:33680e05c63b33ca20fd21ce736ccd12:ref d1:14f41695e9c3d2f9710cc3589a0f36f6:ref d3:11d1c33e8e4823cb1a44cdd98f41cdef:ref d5:f0b014c88442de5dbf78c9aaec98c01c:ref
            3DAVC|3DAVC/MVDDR3D-2_r1.zip|MVDDR3D-2-r1/MVDDR3D-2-r1/MVDDR3D-2-r1.BIT|5|v1:b940e2f76161ffa37c2e6bb23a0e25a1:ref v5:ba4ca8e1d2897dbc9090e1c25d3af82d:ref v9:4c3863ca5b702d9c40a14f1fb2c85589:ref d1:f67f7133192bc3e21e687a25cd9dc03c:ref d5:6afab86e022ed5625ca705ddcdc7fa58:ref d9:52ee8c1932ed38a9144b2164e701f767:ref
            """;

    static List<H264DepthConformanceTest.Vector> vectors() {
        var result = new ArrayList<H264DepthConformanceTest.Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            var layers = new ArrayList<H264DepthConformanceTest.Layer>();
            for (var item : parts[4].split(" ")) {
                var fields = item.split(":");
                layers.add(new H264DepthConformanceTest.Layer(fields[0], fields[1], fields[2].equals("atm")));
            }
            result.add(new H264DepthConformanceTest.Vector(parts[0], parts[1], parts[2], Integer.parseInt(parts[3]),
                    List.copyOf(layers)));
        }
        return result;
    }

    static Stream<H264DepthConformanceTest.Vector> all() {
        return vectors().stream();
    }

    static Stream<H264DepthConformanceTest.Vector> threaded() {
        return all().filter(vector -> vector.toString().matches("MVDCT-3_r1|MVDCT-5-r1|MVDCT-7-r1|MVDCT-8_r2|MVDCT-10_r2"
                + "|MVDDR3D-1_r1"));
    }

    static Stream<H264DepthConformanceTest.Vector> small() {
        return all().filter(vector -> !vector.toString().equals("MVDCT-4_r2"));
    }

    /**
     * The expected MD5 of every decoded layer.
     */
    static Map<String, String> expected(H264DepthConformanceTest.Vector vector) {
        var result = new TreeMap<String, String>();
        for (var layer : vector.layers()) {
            result.put(layer.key(), layer.md5());
        }
        return result;
    }

    /**
     * The expected MD5 of the texture views.
     */
    static Map<String, String> textures(H264DepthConformanceTest.Vector vector) {
        var result = new TreeMap<String, String>();
        for (var layer : vector.layers()) {
            if (layer.key().startsWith("v")) {
                result.put(layer.key(), layer.md5());
            }
        }
        return result;
    }

    /**
     * Decodes a vector, collecting the {@link MediaException.Unsupported} failures the decoder reports.
     */
    static H264DepthConformanceTest.Output decode(byte[] stream, H264DecoderOptions options,
                                                 List<MediaException.Unsupported> failures) {
        var output = new H264DepthConformanceTest.Output("v");
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var nal : H264TestStreams.splitAnnexB(stream)) {
                try {
                    decoder.decode(H264MvcConformanceTest.packet(nal), output);
                } catch (MediaException.Unsupported failure) {
                    failures.add(failure);
                }
            }
            decoder.flush(output);
        }
        return output;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes every texture and depth view bit-exactly")
    void allLayers(H264DepthConformanceTest.Vector vector) throws IOException {
        var failures = new ArrayList<MediaException.Unsupported>();
        var output = decode(H264DepthConformanceTest.bitstream(vector), H264DecoderOptions.builder().allViews()
                .depth(true).build(), failures);
        assertEquals(expected(vector), output.md5s(), vector + " " + vector.layers());
        var frames = output.counts.values().iterator().next();
        output.counts.forEach((key, count) -> assertEquals(frames, count, vector + " frames of " + key));
        assertEquals(List.of(), failures, vector.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threaded")
    @DisplayName("decodes the same layers bit-exactly with four frame threads")
    void allLayersThreaded(H264DepthConformanceTest.Vector vector) throws IOException {
        var output = decode(H264DepthConformanceTest.bitstream(vector), H264DecoderOptions.builder().allViews()
                .depth(true).threads(4).build(), new ArrayList<>());
        assertEquals(expected(vector), output.md5s(), vector.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("small")
    @DisplayName("decodes the texture views bit-exactly without outputting the depth views their tools sample")
    void texturesWithoutDepthOutput(H264DepthConformanceTest.Vector vector) throws IOException {
        var failures = new ArrayList<MediaException.Unsupported>();
        var output = decode(H264DepthConformanceTest.bitstream(vector), H264DecoderOptions.builder().allViews()
                .threads(2).build(), failures);
        assertEquals(textures(vector), output.md5s(), vector.toString());
        assertEquals(List.of(), failures, vector.toString());
    }

    @Test
    @DisplayName("outputs one non-base texture view coded with view synthesis prediction through the views it needs")
    void singleTextureView() throws IOException {
        var vector = all().filter(candidate -> candidate.toString().equals("MVDCT-7-r1")).findFirst().orElseThrow();
        var output = decode(H264DepthConformanceTest.bitstream(vector), H264DecoderOptions.builder().outputViews(1)
                .build(), new ArrayList<>());
        assertEquals(Map.of("v1", textures(vector).get("v1")), output.md5s());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("outputs the base texture view alone by default, without reporting a failure")
    void baseView(H264DepthConformanceTest.Vector vector) throws IOException {
        var failures = new ArrayList<MediaException.Unsupported>();
        var output = decode(H264DepthConformanceTest.bitstream(vector), H264DecoderOptions.defaults(), failures);
        assertEquals(List.of("base"), List.copyOf(output.md5s().keySet()));
        assertEquals(expected(vector).get("v" + vector.baseViewId()), output.md5s().get("base"), vector.toString());
        assertEquals(List.of(), failures);
    }

    @Test
    @DisplayName("outputs the depth view of the base view next to it, as layer 0 of type DEPTH")
    void baseDepth() throws IOException {
        var vector = all().filter(candidate -> candidate.toString().equals("MVDCT-3_r1")).findFirst().orElseThrow();
        var output = decode(H264DepthConformanceTest.bitstream(vector), H264DecoderOptions.builder().depth(true)
                .build(), new ArrayList<>());
        assertEquals(List.of("base", "d3"), List.copyOf(output.md5s().keySet()));
        assertEquals(expected(vector).get("d3"), output.md5s().get("d3"));
        for (var layer : output.layers) {
            if (layer != null) {
                assertEquals(new VideoLayer(0, 3, VideoLayer.Type.DEPTH), layer);
            }
        }
    }

    @Test
    @DisplayName("decodes a non-base depth view through the depth views it predicts from and outputs it alone")
    void singleDepthView() throws IOException {
        var vector = all().filter(candidate -> candidate.toString().equals("MVDDR3D-2_r1")).findFirst()
                .orElseThrow();
        var output = decode(H264DepthConformanceTest.bitstream(vector), H264DecoderOptions.builder().outputViews(9)
                .depth(true).build(), new ArrayList<>());
        assertTrue(output.md5s().containsKey("d9"), "" + output.md5s());
        assertEquals(expected(vector).get("d9"), output.md5s().get("d9"));
    }
}
