package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcVps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the texture conformance bitstreams of the JCT-3V 3D-HEVC suite (ITU-T H.265.1,
 * {@code 3DHC_T_*}): three texture views with their depth views, the dependent texture views coded
 * with inter-view motion prediction (with sub-block partitions in {@code 3DHC_T_B}), advanced
 * residual prediction ({@code 3DHC_T_A}), illumination compensation ({@code 3DHC_T_C}) and all of
 * them combined ({@code 3DHC_T_D}, and {@code 3DHC_T_E} without depth views), the depth views with
 * their integer motion vectors, and the disparity vectors of the texture views with
 * {@code depth_ref_enabled_flag} (which the reference decoder refines only with depth-based block partitioning) with the
 * inter-view predicted merging candidates of the depth views ({@code 3DHC_DT_A}). The streams with tools
 * the decoder does not implement must name them: view synthesis prediction ({@code 3DHC_DT_B}) and
 * the depth coding tools ({@code 3DHC_TD_A}). Every layer of every stream must match the reference decoder: the
 * MD5 of each output picture (luma and chroma, a depth picture's chroma mid-gray 4:2:0 planes)
 * against the per-picture MD5s of the output of the 3D-HEVC reference decoder HTM-16.3 kept with
 * the test ({@code threeD/frame-md5.txt}, written by {@code tools/testdata/hevc-3d/frame-md5.py},
 * whose whole-layer MD5s equal those the archives ship), and the MD5 of each layer's whole output
 * against the archive's. Each archive is pinned by its SHA-256 and cached by
 * {@link Conformance#fetch}.
 */
@DisplayName("HEVC 3D conformance bitstreams (ITU-T H.265.1, 3D-HEVC texture)")
class Hevc3dConformanceTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jctvc-site/bitstream_exchange/draft_conformance/3D-HEVC/";

    /**
     * Name | key in the per-picture MD5 list | SHA-256 of the archive | bitstream entry.
     */
    private static final String MANIFEST = """
            3DHC_T_A_Qualcomm_3|A|1e9a1c0752db4f8a04c9fcc036dbecab039f4d423851fd4ee1c20cc9a0d3676f|3DHC_T_A_Qualcomm_3/3DHC_T_A.bit
            3DHC_T_B_MediaTek_3|B|c7acbe5a2adcc61cea2b6512ec76152d36072bbe330e1a06ee06828ce18c4e7b|3DHC_T_B_MediaTek_3/3DHC_T_B.bit
            3DHC_T_C_Sharp_3|C|0165b8cce3d6c5f00bd50c5efd9c363b8c153146b4caefbf9aa63730ac26ce96|3DHC_T_C_Sharp_3/3DHC_T_C.bit
            3DHC_T_D_Sharp_3|D|fbb808616d3de98d0bdb8beceb01e29f665948dcf361d333fa960e2e34b6ad19|3DHC_T_D_Sharp_3/3DHC_T_D.bit
            3DHC_T_E_HHI_3|E|bdb7a7f8d891aea19aef4fc752e0a91a7e7bc0b9135d92fece74b2c25c8854a6|3DHC_T_E.bit
            3DHC_DT_A_MediaTek_3|DA|5d297a751acb27476e6174fd3189a595f8b379d231443daae3a88f41c19175f1|3DHC_DT_A.bit
            """;

    record Vector(String name, String key, String sha256, String bitstream) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|");
            result.add(new Vector(parts[0], parts[1], parts[2], parts[3]));
        }
        return result;
    }

    static ZipFile archive(Vector vector) throws IOException {
        var file = Conformance.fetch("hevc-3dhevc-" + vector.name() + ".zip", BASE + vector.name() + ".zip",
                vector.sha256());
        return new ZipFile(file.toFile());
    }

    static byte[] stream(Vector vector) throws IOException {
        try (var zip = archive(vector)) {
            return HevcMultilayerConformanceTest.entry(zip, vector.bitstream());
        }
    }

    /**
     * Returns the reference decoder's per-picture MD5s of a vector, by layer, in output order.
     */
    static Map<Integer, List<String>> expectedPictures(Vector vector) throws IOException {
        var result = new TreeMap<Integer, List<String>>();
        try (var in = Hevc3dConformanceTest.class.getResourceAsStream("threeD/frame-md5.txt")) {
            if (in == null) {
                throw new AssertionError("missing threeD/frame-md5.txt");
            }
            for (var line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\\R")) {
                var parts = line.strip().split(" ");
                if (parts.length == 4 && parts[0].equals(vector.key())) {
                    result.computeIfAbsent(Integer.parseInt(parts[1]), _ -> new ArrayList<>()).add(parts[3]);
                }
            }
        }
        return result;
    }

    private static final Pattern YUV_MD5 = Pattern.compile("^([0-9a-fA-F]{32})\\s+\\*?\\S*_(\\d+)\\.yuv\\s*$");

    /**
     * Returns the MD5 of each layer's whole output that the archive ships, by layer identifier.
     */
    static Map<Integer, String> expectedLayers(Vector vector) throws IOException {
        var result = new TreeMap<Integer, String>();
        try (var zip = archive(vector)) {
            for (var entry : zip.stream().toList()) {
                if (!entry.getName().endsWith(".md5")) {
                    continue;
                }
                var text = new String(HevcMultilayerConformanceTest.entry(zip, entry.getName()), StandardCharsets.US_ASCII);
                for (var line : text.split("\\R")) {
                    var matcher = YUV_MD5.matcher(line.strip());
                    if (matcher.find()) {
                        result.put(Integer.parseInt(matcher.group(2)), matcher.group(1).toLowerCase());
                    }
                }
            }
        }
        return result;
    }

    /**
     * Decodes a stream with the engine for an output layer set and returns the MD5 of every output
     * picture by layer, in output order.
     */
    static Map<Integer, List<String>> decodePictures(byte[] stream, int ols, int threads,
                                                     Map<Integer, MessageDigest> whole) {
        return decodePictures(stream, ols, threads, whole, null, null);
    }

    static Map<Integer, List<String>> decodePictures(byte[] stream, int ols, int threads,
                                                     Map<Integer, MessageDigest> whole,
                                                     Map<Integer, List<String>> expected, String name) {
        var result = new TreeMap<Integer, List<String>>();
        var dumped = new TreeMap<Integer, Integer>();
        var engine = new HevcDecoderEngine(MediaLimits.DEFAULT, true, threads, ols, false, frame -> {
            var planes = planes(frame);
            var digest = HevcTestStreams.md5();
            for (var plane : planes) {
                digest.update(plane);
            }
            var hash = HexFormat.of().formatHex(digest.digest());
            var list = result.computeIfAbsent(frame.layerId, _ -> new ArrayList<>());
            if (expected != null) {
                var wanted = expected.getOrDefault(frame.layerId, List.of());
                var index = list.size();
                if ((index >= wanted.size() || !wanted.get(index).equals(hash)) && dumped.merge(frame.layerId, 1, Integer::sum) <= 2) {
                    dump(name + "_L" + frame.layerId + "_" + index + "_poc" + frame.outputPoc, planes, frame);
                }
            }
            list.add(hash);
            if (whole != null) {
                var layer = whole.computeIfAbsent(frame.layerId, _ -> HevcTestStreams.md5());
                for (var plane : planes) {
                    layer.update(plane);
                }
            }
        });
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

    @org.junit.jupiter.api.Test
    @DisplayName("the depth views are output as depth layers and the texture views as primary views")
    void layerTypes() throws IOException {
        var vector = vectors().getFirst();
        var stream = stream(vector);
        var vps = HevcMultilayerConformanceTest.vps(stream);
        var types = new TreeMap<Integer, com.github.auties00.smalt.video.VideoLayer>();
        var ols = 0;
        var most = 0;
        for (var i = 0; i < vps.numOutputLayerSets(); i++) {
            var outputs = 0;
            for (var flag : vps.outputLayerFlag[i]) {
                outputs += flag ? 1 : 0;
            }
            if (outputs > most) {
                most = outputs;
                ols = i;
            }
        }
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), HevcMultilayerConformanceTest.options(ols, 1))) {
            java.util.function.Consumer<com.github.auties00.smalt.video.VideoFrame> sink = frame ->
                    frame.sideData().layer().ifPresent(layer -> types.put(layer.id(), layer));
            for (var nal : HevcTestStreams.splitAnnexB(stream)) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5), List.copyOf(types.keySet()));
        for (var layer : types.values()) {
            var depth = vps.depthLayer[layer.id()];
            assertEquals(depth ? com.github.auties00.smalt.video.VideoLayer.Type.DEPTH
                    : com.github.auties00.smalt.video.VideoLayer.Type.PRIMARY, layer.type(), "layer " + layer.id());
            assertEquals(vps.viewId[layer.id()], layer.viewId(), "view of layer " + layer.id());
        }
    }

    record UnsupportedVector(String name, String sha256, String bitstream, String feature) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<UnsupportedVector> unsupportedVectors() {
        return Stream.of(
                new UnsupportedVector("3DHC_DT_B_NTT_3", "28e9b6a3bd87f9e70f01c82a9f68d3b5398dbdaa9a77b21e80f1fba7ced2f6d4",
                        "3DHC_DT_B.bit", "hevc 3D view synthesis prediction"),
                new UnsupportedVector("3DHC_TD_A_Qualcomm_3", "9ca38e438ea4ffd8e2e76c2943389534ddbd9d6a071f1af9dba227071f8b80a9",
                        "3DHC_TD_A_Qualcomm_3/3DHC_TD_A.bit", "hevc 3D depth coding tools"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedVectors")
    @DisplayName("a stream with a tool the decoder lacks names it, and its base texture view still decodes")
    void unsupportedTools(UnsupportedVector vector) throws IOException {
        var file = Conformance.fetch("hevc-3dhevc-" + vector.name() + ".zip", BASE + vector.name() + ".zip",
                vector.sha256());
        byte[] stream;
        try (var zip = new ZipFile(file.toFile())) {
            stream = HevcMultilayerConformanceTest.entry(zip, vector.bitstream());
        }
        var vps = HevcMultilayerConformanceTest.vps(stream);
        var error = org.junit.jupiter.api.Assertions.assertThrows(MediaException.Unsupported.class,
                () -> decodePictures(stream, vps.numOutputLayerSets() - 1, 1, null));
        assertEquals(vector.feature(), error.format());
        var base = decodePictures(stream, 0, 1, null);
        assertEquals(List.of(0), List.copyOf(base.keySet()));
        assertFalse(base.get(0).isEmpty());
    }

    /**
     * Writes the planes and the motion of a mismatching picture where the CI uploads them.
     */
    static void dump(String name, byte[][] planes, HevcFrame frame) {
        try {
            var directory = java.nio.file.Path.of("target", "media-fixtures", "threeD");
            java.nio.file.Files.createDirectories(directory);
            try (var out = java.nio.file.Files.newOutputStream(directory.resolve(name + ".yuv"))) {
                for (var plane : planes) {
                    out.write(plane);
                }
            }
            var motion = java.nio.ByteBuffer.allocate(frame.mv0.length * 10).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < frame.mv0.length; i++) {
                motion.putInt(frame.mv0[i]).putInt(frame.mv1[i]).put(frame.ref0[i]).put(frame.ref1[i]);
            }
            java.nio.file.Files.write(directory.resolve(name + ".mv"), motion.array());
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /**
     * Returns the 8-bit planes of a decoded picture, a monochrome picture's chroma as mid-gray
     * 4:2:0 planes.
     */
    static byte[][] planes(HevcFrame frame) {
        var y = Arrays.copyOf(frame.y8, frame.width * frame.height);
        if (frame.cb8 == null) {
            var gray = new byte[((frame.width + 1) / 2) * ((frame.height + 1) / 2)];
            Arrays.fill(gray, (byte) 128);
            return new byte[][]{y, gray, gray};
        }
        var chroma = frame.chromaWidth * frame.chromaHeight;
        return new byte[][]{y, Arrays.copyOf(frame.cb8, chroma), Arrays.copyOf(frame.cr8, chroma)};
    }

    /**
     * Returns, for each layer, an output layer set that outputs it: the last set naming the layer.
     */
    static Map<Integer, Integer> outputLayerSets(HevcVps vps, Iterable<Integer> layers) {
        var result = new LinkedHashMap<Integer, Integer>();
        for (var layer : layers) {
            for (var ols = vps.numOutputLayerSets() - 1; ols >= 0; ols--) {
                var list = vps.layerSetLayerIdList[vps.olsIdxToLsIdx[ols]];
                var found = false;
                for (var j = 0; j < list.length; j++) {
                    if (list[j] == layer && vps.outputLayerFlag[ols][j]) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    result.put(layer, ols);
                    break;
                }
            }
        }
        return result;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every picture of every layer matches the reference decoder")
    void pictures(Vector vector) throws IOException {
        assertPictures(vector, 1);
    }

    static Stream<Vector> threadedVectors() {
        return vectors().stream().filter(vector -> vector.key().equals("D") || vector.key().equals("E"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threadedVectors")
    @DisplayName("decodes every layer bit-exactly on several threads")
    void picturesThreaded(Vector vector) throws IOException {
        assertPictures(vector, 4);
    }

    static void assertPictures(Vector vector, int threads) throws IOException {
        var stream = stream(vector);
        var expected = expectedPictures(vector);
        var layers = expectedLayers(vector);
        assertFalse(expected.isEmpty(), "the list gives picture MD5s");
        assertEquals(layers.keySet(), expected.keySet(), "the archive and the list name the same layers");
        var vps = HevcMultilayerConformanceTest.vps(stream);
        var sets = outputLayerSets(vps, expected.keySet());
        assertEquals(expected.keySet(), sets.keySet(), "an output layer set outputs every layer");
        var decoded = new TreeMap<Integer, List<String>>();
        var whole = new TreeMap<Integer, String>();
        for (var ols : new java.util.TreeSet<>(sets.values()).descendingSet()) {
            var digests = new TreeMap<Integer, MessageDigest>();
            Map<Integer, List<String>> output;
            try {
                output = decodePictures(stream, ols, threads, digests, expected, vector.key() + "_t" + threads + "_ols" + ols);
            } catch (MediaException exception) {
                throw new AssertionError(vector + " OLS " + ols + ": " + exception.getMessage(), exception);
            }
            for (var entry : output.entrySet()) {
                if (!decoded.containsKey(entry.getKey()) && sets.get(entry.getKey()) == ols) {
                    decoded.put(entry.getKey(), entry.getValue());
                    whole.put(entry.getKey(), HexFormat.of().formatHex(digests.get(entry.getKey()).digest()));
                }
            }
        }
        var mismatches = new StringBuilder();
        for (var entry : expected.entrySet()) {
            var layer = entry.getKey();
            var actual = decoded.getOrDefault(layer, List.of());
            var wanted = entry.getValue();
            var bad = new ArrayList<Integer>();
            for (var i = 0; i < Math.max(actual.size(), wanted.size()); i++) {
                if (i >= actual.size() || i >= wanted.size() || !actual.get(i).equals(wanted.get(i))) {
                    bad.add(i);
                }
            }
            if (!bad.isEmpty()) {
                mismatches.append(" layer ").append(layer).append(": ").append(bad.size()).append(" of ")
                        .append(wanted.size()).append(" pictures differ, first ").append(bad.subList(0, Math.min(8, bad.size())))
                        .append(" (").append(actual.size()).append(" output);");
            }
        }
        if (!mismatches.isEmpty()) {
            fail(vector + " on " + threads + " threads:" + mismatches);
        }
        assertEquals(layers, whole, vector + " per-layer MD5s");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("the default output layer set decodes the base texture view alone")
    void baseLayer(Vector vector) throws IOException {
        var stream = stream(vector);
        var expected = expectedPictures(vector).get(0);
        var output = decodePictures(stream, 0, 1, null);
        assertEquals(List.of(0), List.copyOf(output.keySet()), "only the base layer is output");
        assertEquals(expected, output.get(0), vector + " base layer pictures");
        assertTrue(expected.size() > 1);
    }
}
