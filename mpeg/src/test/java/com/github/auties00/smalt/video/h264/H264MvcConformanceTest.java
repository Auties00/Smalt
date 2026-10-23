package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the multiview (MVC) bitstreams of ITU-T H.264.1 (the Multiview High and Stereo High
 * profiles: two to eight views, inter-view prediction in anchor and non-anchor access units, list
 * modifications naming inter-view references, field and MBAFF view components, memory management
 * and gaps in {@code frame_num}) and compares every view with the output of the ITU-T reference
 * decoders. The suite ships bitstreams only, so the expected views are the MD5 of the planar 4:2:0
 * output of JM 19.1 ({@code ldecod -p DecodeAllLayers=1}) for the two-view streams and of JMVC 8.5
 * ({@code H264AVCDecoderLibTestStatic <stream> <out> <views>}) for the others; both decoders agree
 * on every view they decode. Each bitstream is pinned by its SHA-256 and cached by
 * {@link Conformance#fetch}.
 */
@DisplayName("H.264 MVC conformance bitstreams (ITU-T H.264.1)")
class H264MvcConformanceTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/MVC/";

    /**
     * Name | size | SHA-256 | picture size | views, each {@code view_id:frames:md5} in view order of the
     * stream's sequence parameter set extension.
     */
    private static final String MANIFEST = """
            MVCDS1|6888085|145b8c0f4b9c758bcaacdb214062cecc53bd8c652e276a15d61a99dce30d7d2a|640x480|0:290:4e93953a232fa6f4cd8b72339ce8a2f4 1:290:ee492e7c6b28fa8cee019ddca4f2c5a9
            MVCDS2|2401933|44c5f766165e6fe0eadff13b9b9c1c36d9d8482d007de23becabf44915d64235|640x480|0:290:3d279d41cf52d70ca9af8ee7e950b504 1:290:db290c34b0fa061e595f35ea55840d40
            MVCDS3|3744381|a86a3393f0260a6179dd1ceeedac074941695d032810a2b6f989d548f49f3c92|640x480|0:290:505cb8ad90da9a59e42d0a08569806e9 1:290:86c5b96ff60ecf09b80bdb42754478f2
            MVCDS-4|57572|b131f20cf93055fc4880c0ad7774e5a38790cfcde79cf29ebdf969f3c1201660|640x480|0:9:4a15b286d65dc66099398e5ac008c06f 2:9:10eb62af1d466c19193cd886b5ef4177
            MVCDS-5|57071|b25b804d3bd16c7b020845e337dd30b303aa575c3b4226f0c53a9499f002f86f|640x480|0:9:4a15b286d65dc66099398e5ac008c06f 2:9:e076cb77e0e944c93c49590abe163199
            MVCDS-6|57204|5765aeb76ff03aa498c0dbc9f0f75867b0647ab7e889dc1b128d48497e7dfa43|640x480|0:9:4a15b286d65dc66099398e5ac008c06f 2:9:26cbdf1d13c0c1aade8007fc3d3cdc93
            MVCICT-1|1348764|53f4e2d298793359b04d18c025b819f1a747488e795ac649c0b42d354308cd5c|720x480|0:150:cfb27cf69ad9147bff1b6b528552fdb4 1:150:6f69a2819678202ce57336bd1f62e347
            MVCICT-2|2281273|e7bbff59b2e0ca40a02920a32f92dd94021d0669fa9c56a8e908f64e6d001d75|720x480|0:150:140bb029c34692b67d15d4c5c957df7b 1:150:b11a91ee32705ca15ab52404ea3dcf18
            MVCNV1|3187769|91589955e0b4f7b3ac254ee62ea0262e2c791b1c376162593f7c0c782df4e1cc|640x480|0:290:3d279d41cf52d70ca9af8ee7e950b504 1:290:adbba5e0825d77389d906c46becd6555 2:290:d27943f945109b0b495d5832c58dbdf9
            MVCNV-2|182212|9ec767b457c3a5da6be6691848e072869cfc6bcaef654f2b4eaa8c584b436e65|640x480|0:9:4a15b286d65dc66099398e5ac008c06f 1:9:c662647223762c4e61881200f93588ee 2:9:d9b7e827ec22e6494e0d1eadac6785a7 3:9:207c22445bee9339928d34bb0a3f02f7 4:9:9750e406f20525443a9c064a073b5faf 5:9:3af6f5c76731b3478d0aa7692c050890 6:9:6e2fca576cf2f7559c5997a44636b212 7:9:3f99300f35156d399845e758392eba7c
            MVCNV-3|91815|fe0dce3898df8231b2ebda3324c1e19e6df79b037731488f96410651b8fea87e|640x480|0:9:4a15b286d65dc66099398e5ac008c06f 1:9:2d5cb50b6f84a1cb631a7c6d77aa7a10 2:9:a348cd5779387dca8156ea1f64583963
            MVCNV4|6727031|f843d1cff418d97f7d8196c3d1b68ed7fc3942559100a87baa1812fbdb941413|640x480|0:290:505cb8ad90da9a59e42d0a08569806e9 1:290:5931c52e82b9429530b8fb6c17398c1e 2:290:1e47385fea2c64b9f7c97355236beefc 3:290:eccb59448b034ded1289f2201fc1ce5c
            MVCRP_1|1430967|5f084172d8c4f58134d9e0f082a58bdd52a81ad95b1d013811a2d585ddca1fc0|640x480|0:200:1312ce1b578bdd66dbfaed67d3b13678 1:200:111b96d6caf8678dcdc597e9e3e5608c
            MVCRP_2|963624|60927c1e201a487af797ebd71914c4fbe69f159e8f38e6db0002ed13ea765d9d|640x480|0:250:b89fdf72007d76e4611237ca113a9e47 1:250:5dc61db7e682982679042ef554968245
            MVCRP_3|880327|2ba384cdea1d37dab2d854e53377445c0e502e2c87af427261c65c5ec6211d33|640x480|0:250:b89fdf72007d76e4611237ca113a9e47 1:250:7d57344b40186e0a9253faade0f2aa4c
            MVCRP_4|1488559|1992da5f749a79c94f5d1a1c4d98b512334058d5dbd90d749ea11e6e44b9eb53|640x480|0:250:198afdb2a23f707b36be0c3e6e1dde96 1:250:bbde66c44cec2854b4f5401ec03442c0
            MVCSPS-1|81870|ef76663ef781ae5b57a8636de7af11e372f7d92784d1d16b9ceffca2303b3840|640x480|0:9:3db35ab5bd1b052b456f2e7ea7f3fb74 2:9:39f8caa97dfd72e858893a83f41091f7
            MVCSPS-2|76489|ebd860decd017e3b14e84c4502e5718ba164be3a20f321616c4d8ce8781c2a53|640x480|0:9:3db35ab5bd1b052b456f2e7ea7f3fb74 1:9:850d60138ad2c757a7061e7df68d598b
            """;

    /**
     * One view of a vector: its {@code view_id}, frame count and the MD5 of its planar output.
     */
    record View(int viewId, int frames, String md5) {
    }

    record Vector(String name, int size, String sha256, int width, int height, List<View> views) {
        @Override
        public String toString() {
            return name;
        }

        View view(int viewId) {
            return views.stream().filter(view -> view.viewId == viewId).findFirst().orElseThrow();
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : MANIFEST.strip().split("\n")) {
            var parts = line.strip().split("\\|", -1);
            var dimensions = parts[3].split("x");
            var views = new ArrayList<View>();
            for (var entry : parts[4].split(" ")) {
                var fields = entry.split(":");
                views.add(new View(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), fields[2]));
            }
            result.add(new Vector(parts[0], Integer.parseInt(parts[1]), parts[2], Integer.parseInt(dimensions[0]),
                    Integer.parseInt(dimensions[1]), List.copyOf(views)));
        }
        return result;
    }

    static Stream<Vector> all() {
        return vectors().stream();
    }

    static Stream<Vector> threaded() {
        return all().filter(vector -> vector.name().matches("MVCICT-1|MVCICT-2|MVCNV-2|MVCRP_4|MVCSPS-1"));
    }

    static Vector vector(String name) {
        return vectors().stream().filter(vector -> vector.name().equals(name)).findFirst().orElseThrow();
    }

    static byte[] bitstream(Vector vector) throws IOException {
        var file = Conformance.fetch("h264-MVC-" + vector.name() + ".264", BASE + vector.name() + ".264",
                vector.sha256());
        return Files.readAllBytes(file);
    }

    /**
     * The frames a decode produced: per {@code view_id} the running MD5 and frame count, plus every
     * frame's view and MD5 in output order.
     */
    static final class Output implements Consumer<VideoFrame> {
        final Map<Integer, MessageDigest> digests = new LinkedHashMap<>();

        final Map<Integer, Integer> counts = new LinkedHashMap<>();

        final List<VideoLayer> layers = new ArrayList<>();

        final List<String> frames = new ArrayList<>();

        @Override
        public void accept(VideoFrame frame) {
            var layer = frame.sideData().layer().orElse(null);
            var viewId = layer == null ? -1 : layer.viewId();
            var samples = H264TestStreams.serialize((YuvPicture) frame.picture());
            digests.computeIfAbsent(viewId, id -> digest()).update(samples);
            counts.merge(viewId, 1, Integer::sum);
            layers.add(layer);
            frames.add(HexFormat.of().formatHex(digest().digest(samples)));
        }

        String md5(int viewId) {
            var digest = digests.get(viewId);
            return digest == null ? "none" : HexFormat.of().formatHex(digest.digest());
        }

        int count(int viewId) {
            return counts.getOrDefault(viewId, 0);
        }

        private static MessageDigest digest() {
            try {
                return MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    static Output decode(byte[] stream, H264DecoderOptions options) {
        var output = new Output();
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var nal : H264TestStreams.splitAnnexB(stream)) {
                decoder.decode(packet(nal), output);
            }
            decoder.flush(output);
        }
        return output;
    }

    static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
    }

    /**
     * Requires every view of a vector to match the reference, and the frames of each access unit to
     * come out together in view order: the layer identifiers rise within an access unit and every
     * access unit holds every view.
     */
    static void assertViews(Vector vector, Output output) {
        for (var view : vector.views()) {
            assertEquals(view.frames(), output.count(view.viewId()), vector + " frame count of view " + view.viewId());
            assertEquals(view.md5(), output.md5(view.viewId()), vector + " samples of view " + view.viewId());
        }
        assertEquals(vector.views().size(), output.counts.size(), vector + " views output: " + output.counts.keySet());
        var views = vector.views().size();
        var unit = new java.util.HashSet<Integer>();
        for (var i = 0; i < output.layers.size(); i++) {
            var layer = output.layers.get(i);
            assertEquals(VideoLayer.Type.PRIMARY, layer.type());
            assertEquals(i % views, layer.id(), vector + " frame " + i + " is out of access unit or view order");
            if (i % views == 0) {
                unit.clear();
            }
            assertTrue(unit.add(layer.viewId()), vector + " frame " + i + " repeats a view of its access unit");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes every view bit-exactly, access unit by access unit in view order")
    void allViews(Vector vector) throws IOException {
        var output = decode(bitstream(vector), H264DecoderOptions.builder().allViews().build());
        assertViews(vector, output);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threaded")
    @DisplayName("decodes every view bit-exactly with four frame threads")
    void allViewsThreaded(Vector vector) throws IOException {
        var output = decode(bitstream(vector), H264DecoderOptions.builder().allViews().threads(4).build());
        assertViews(vector, output);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("outputs the base view alone by default, without naming its layer")
    void baseView(Vector vector) throws IOException {
        var output = decode(bitstream(vector), H264DecoderOptions.defaults());
        var base = vector.views().getFirst();
        assertEquals(List.of(-1), List.copyOf(output.counts.keySet()), "frames name no layer");
        assertEquals(base.frames(), output.count(-1));
        assertEquals(base.md5(), output.md5(-1));
    }

    @Nested
    @DisplayName("view selection")
    class Selection {
        @Test
        @DisplayName("decodes a non-base view through the views it predicts from and outputs it alone")
        void singleView() throws IOException {
            var vector = vector("MVCNV-2");
            var output = decode(bitstream(vector), H264DecoderOptions.builder().outputViews(4).build());
            assertEquals(List.of(4), List.copyOf(output.counts.keySet()));
            assertEquals(vector.view(4).md5(), output.md5(4));
            assertEquals(vector.view(4).frames(), output.count(4));
            for (var layer : output.layers) {
                assertEquals(4, layer.viewId());
            }
        }

        @Test
        @DisplayName("outputs the base view only when the list names it")
        void namedViews() throws IOException {
            var vector = vector("MVCNV-2");
            var output = decode(bitstream(vector), H264DecoderOptions.builder().outputViews(7, 0).build());
            assertEquals(List.of(0, 7), output.counts.keySet().stream().sorted().toList());
            assertEquals(vector.view(0).md5(), output.md5(0));
            assertEquals(vector.view(7).md5(), output.md5(7));
            for (var i = 0; i < output.layers.size(); i++) {
                assertEquals(i % 2 == 0 ? 0 : 7, output.layers.get(i).viewId(), "frame " + i);
            }
        }

        @Test
        @DisplayName("decodes an independently coded non-base view")
        void independentView() throws IOException {
            var vector = vector("MVCNV-3");
            var output = decode(bitstream(vector), H264DecoderOptions.builder().outputViews(2).build());
            assertEquals(vector.view(2).md5(), output.md5(2));
        }

        @Test
        @DisplayName("ignores views the stream does not code")
        void absentView() throws IOException {
            var vector = vector("MVCSPS-2");
            var output = decode(bitstream(vector), H264DecoderOptions.builder().outputViews(0, 1, 5).build());
            assertEquals(List.of(0, 1), output.counts.keySet().stream().sorted().toList());
            assertEquals(vector.view(1).md5(), output.md5(1));
        }

        @Test
        @DisplayName("outputs nothing when the list names no coded view")
        void noCodedView() throws IOException {
            var vector = vector("MVCDS-4");
            var output = decode(bitstream(vector), H264DecoderOptions.builder().outputViews(9).build());
            assertTrue(output.counts.isEmpty(), "frames output: " + output.counts);
        }
    }

    @Nested
    @DisplayName("temporal layers")
    class Temporal {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264MvcConformanceTest#temporalVectors")
        @DisplayName("keeps exactly the access units of the lower temporal layers, unchanged")
        void maxTemporalId(Vector vector) throws IOException {
            var stream = bitstream(vector);
            var full = decode(stream, H264DecoderOptions.builder().allViews().build());
            for (var temporalId = 0; temporalId < 3; temporalId++) {
                var expected = accessUnits(stream, temporalId);
                var thinned = decode(stream, H264DecoderOptions.builder().allViews().maxTemporalId(temporalId)
                        .build());
                var views = vector.views().size();
                assertEquals(expected * views, thinned.frames.size(), vector + " frames at temporal_id " + temporalId);
                var position = 0;
                for (var frame : thinned.frames) {
                    while (position < full.frames.size() && !full.frames.get(position).equals(frame)) {
                        position++;
                    }
                    if (position == full.frames.size()) {
                        fail(vector + ": a frame decoded at temporal_id " + temporalId + " is not a frame of the full "
                                + "decode, in order");
                    }
                    position++;
                }
            }
        }
    }

    static Stream<Vector> temporalVectors() {
        return all().filter(vector -> vector.name().matches("MVCDS-4|MVCNV-2|MVCSPS-1"));
    }

    /**
     * Counts the access units whose prefix NAL unit carries a {@code temporal_id} at most a limit.
     */
    static int accessUnits(byte[] stream, int maxTemporalId) {
        var count = 0;
        for (var nal : H264TestStreams.nalUnits(stream)) {
            if ((nal[0] & 0x1F) == 14 && (nal[3] >> 3 & 7) <= maxTemporalId) {
                count++;
            }
        }
        return count;
    }

    @Nested
    @DisplayName("low delay")
    class LowDelay {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264MvcConformanceTest#lowDelayVectors")
        @DisplayName("outputs the views of each access unit together, in the decode call that completes them")
        void accessUnits(Vector vector) throws IOException {
            var stream = bitstream(vector);
            var output = new Output();
            var options = H264DecoderOptions.builder().realtime(true).allViews().build();
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
                for (var unit : mvcAccessUnits(H264TestStreams.nalUnits(stream))) {
                    var before = output.frames.size();
                    decoder.decode(packet(H264TestStreams.annexB(unit)), output);
                    var produced = output.frames.size() - before;
                    assertEquals(0, produced % vector.views().size(),
                            vector + ": a packet produced " + produced + " frames, not whole access units");
                }
                decoder.flush(output);
            }
            assertViews(vector, output);
        }
    }

    static Stream<Vector> lowDelayVectors() {
        return all().filter(vector -> vector.name().matches("MVCDS-5|MVCNV-3|MVCSPS-1"));
    }

    /**
     * Groups NAL units into the access units of a multiview stream: a new unit starts at a prefix NAL
     * unit or a base view slice with {@code first_mb_in_slice} 0 that follows a slice, or at an access
     * unit delimiter, parameter set or SEI after a slice.
     */
    static List<List<byte[]>> mvcAccessUnits(List<byte[]> nals) {
        var units = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        var sawSlice = false;
        var afterPrefix = false;
        for (var nal : nals) {
            var type = nal[0] & 0x1F;
            var boundary = false;
            if (sawSlice) {
                boundary = type == 9 || type == 7 || type == 8 || type == 6 || type == 15 || type == 14
                        || ((type == 1 || type == 5) && !afterPrefix && (nal[1] & 0x80) != 0);
            }
            if (boundary && !current.isEmpty()) {
                units.add(current);
                current = new ArrayList<>();
                sawSlice = false;
            }
            current.add(nal);
            afterPrefix = type == 14;
            if (type == 1 || type == 5 || type == 20) {
                sawSlice = true;
            }
        }
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    @Nested
    @DisplayName("damage")
    class Damage {
        @Test
        @DisplayName("decodes the base view intact when the non-base views are lost")
        void lostViews() throws IOException {
            var vector = vector("MVCDS-5");
            var nals = new ArrayList<byte[]>();
            for (var nal : H264TestStreams.nalUnits(bitstream(vector))) {
                if ((nal[0] & 0x1F) != 20) {
                    nals.add(nal);
                }
            }
            var output = decode(H264TestStreams.annexB(nals), H264DecoderOptions.builder().allViews().build());
            assertEquals(vector.views().getFirst().md5(), output.md5(0));
            assertEquals(0, output.count(2));
        }

        @Test
        @DisplayName("survives every truncation of a multiview stream")
        void truncation() throws IOException {
            var vector = vector("MVCDS-4");
            var stream = bitstream(vector);
            var options = H264DecoderOptions.builder().allViews().build();
            for (var length = 0; length < stream.length; length += 997) {
                var cut = java.util.Arrays.copyOf(stream, length);
                var output = new Output();
                try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
                    try {
                        decoder.decode(packet(cut), output);
                        decoder.flush(output);
                    } catch (com.github.auties00.smalt.MediaException.Malformed expected) {
                        // a cut slice is reported as corrupt
                    }
                }
                assertFalse(output.frames.size() > 18, "more frames than the stream holds");
            }
        }

        @Test
        @DisplayName("reports frames damaged when an inter-view reference is lost, intact otherwise")
        void interViewLoss() throws IOException {
            var vector = vector("MVCDS-5");
            var nals = H264TestStreams.nalUnits(bitstream(vector));
            var units = mvcAccessUnits(nals);
            var damaged = new ArrayList<Boolean>();
            var views = new ArrayList<Integer>();
            try (var decoder = new H264Decoder(H264ConformanceTest.track(),
                    H264DecoderOptions.builder().allViews().build())) {
                H264Decoder.FrameConsumer sink = (frame, broken) -> {
                    damaged.add(broken);
                    views.add(frame.sideData().layer().orElseThrow().viewId());
                };
                for (var i = 0; i < units.size(); i++) {
                    var unit = units.get(i);
                    if (i == 2) {
                        // drop the base view of the third access unit, which the non-base view predicts from
                        var kept = new ArrayList<byte[]>();
                        for (var nal : unit) {
                            var type = nal[0] & 0x1F;
                            if (type != 1 && type != 5 && type != 14) {
                                kept.add(nal);
                            }
                        }
                        unit = kept;
                        decoder.reportLoss();
                    }
                    decoder.decode(packet(H264TestStreams.annexB(unit)), sink);
                }
                decoder.flush(sink);
            }
            assertTrue(damaged.contains(true), "the loss damages frames");
            assertFalse(damaged.get(0) || damaged.get(1), "the frames before the loss are intact");
        }
    }
}
