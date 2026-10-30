package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the small scalable (SVC) streams coded by the JSVM reference encoder
 * ({@code svc/streams.tsv}, made by {@code tools/testdata/h264-svc/make-vectors.py}) at each of their
 * layers and compares the output with the JSVM decoder's decoding of each layer's sub-bitstream; it also
 * covers the layer options, the layer named in the frames' side data, and damaged and corrupt streams.
 */
@DisplayName("H.264 scalable streams coded by the JSVM encoder")
class H264SvcStreamsTest {
    /**
     * One decoded layer of a stream: its {@code DQId}, the highest {@code temporal_id} decoded, the frame
     * count and the MD5 of the planar output.
     */
    record Layer(int dqId, int temporalId, int frames, String md5) {
        @Override
        public String toString() {
            return "DQId " + dqId + " T" + temporalId;
        }
    }

    record Vector(String name, String sha256, List<Layer> layers) {
        @Override
        public String toString() {
            return name;
        }

        byte[] bytes() {
            var data = resource(name + ".264");
            assertEquals(sha256, H264SvcStreamsTest.sha256(data), name + " changed");
            return data;
        }

        /** Returns the layer decoded at the highest DQId and temporal level. */
        Layer top() {
            return layers.stream().filter(layer -> layer.dqId == maxDqId()).max((a, b) -> a.temporalId - b.temporalId)
                    .orElseThrow();
        }

        Layer base() {
            return layers.getFirst();
        }

        int maxDqId() {
            return layers.stream().mapToInt(Layer::dqId).max().orElseThrow();
        }
    }

    static List<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : new String(resource("streams.tsv"), StandardCharsets.UTF_8).replace("\r", "").split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            var layers = new ArrayList<Layer>();
            for (var entry : fields[3].split(" ")) {
                var parts = entry.split(":");
                layers.add(new Layer(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        parts[3]));
            }
            result.add(new Vector(fields[0], fields[1], List.copyOf(layers)));
        }
        return result;
    }

    static Stream<Vector> all() {
        return vectors().stream();
    }

    static Vector vector(String name) {
        return vectors().stream().filter(vector -> vector.name.equals(name)).findFirst().orElseThrow();
    }

    static Stream<Arguments> layers() {
        return vectors().stream().flatMap(vector -> vector.layers.stream().map(layer -> Arguments.of(vector, layer)));
    }

    static byte[] resource(String file) {
        try (var input = H264SvcStreamsTest.class.getResourceAsStream("svc/" + file)) {
            if (input == null) {
                throw new IllegalStateException("missing resource svc/" + file);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * The frames a decode produced: the running MD5 of their samples, their count and the layer each
     * names.
     */
    static final class Output implements Consumer<VideoFrame> {
        private final MessageDigest digest = newDigest();

        final List<Optional<VideoLayer>> layers = new ArrayList<>();

        final List<String> frames = new ArrayList<>();

        @Override
        public void accept(VideoFrame frame) {
            var samples = H264TestStreams.serialize((YuvPicture) frame.picture());
            digest.update(samples);
            layers.add(frame.sideData().layer());
            frames.add(HexFormat.of().formatHex(newDigest().digest(samples)));
        }

        String md5() {
            try {
                return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
            } catch (CloneNotSupportedException exception) {
                throw new AssertionError(exception);
            }
        }

        int count() {
            return frames.size();
        }

        private static MessageDigest newDigest() {
            try {
                return MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
    }

    /** Decodes a stream NAL unit by NAL unit. */
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

    static H264DecoderOptions target(Layer layer) {
        return H264DecoderOptions.builder().targetLayer(layer.dqId >> 4, layer.dqId & 15)
                .maxTemporalId(layer.temporalId).build();
    }

    static void assertLayer(Vector stream, Layer layer, Output output) {
        assertEquals(layer.frames, output.count(), stream + " " + layer + ": frame count");
        assertEquals(layer.md5, output.md5(), stream + " " + layer + ": samples");
    }

    /**
     * Groups the NAL units of a scalable stream into access units: a unit starts, after a slice, at an
     * access unit delimiter, parameter set or SEI message, at a prefix NAL unit whose base layer slice
     * starts a picture, or at a base layer slice without prefix that starts a picture.
     */
    static List<List<byte[]>> accessUnits(List<byte[]> nals) {
        var units = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        var sawSlice = false;
        for (var i = 0; i < nals.size(); i++) {
            var nal = nals.get(i);
            var type = nal[0] & 0x1F;
            var boundary = false;
            if (sawSlice) {
                if (type == 6 || type == 7 || type == 8 || type == 9 || type == 15) {
                    boundary = true;
                } else if (type == 14 && i + 1 < nals.size()) {
                    var next = nals.get(i + 1);
                    boundary = ((next[0] & 0x1F) == 1 || (next[0] & 0x1F) == 5) && (next[1] & 0x80) != 0;
                } else if ((type == 1 || type == 5) && (i == 0 || (nals.get(i - 1)[0] & 0x1F) != 14)) {
                    boundary = (nal[1] & 0x80) != 0;
                }
            }
            if (boundary && !current.isEmpty()) {
                units.add(current);
                current = new ArrayList<>();
                sawSlice = false;
            }
            current.add(nal);
            if (type == 1 || type == 5 || type == 20) {
                sawSlice = true;
            }
        }
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layers")
    @DisplayName("decodes every layer bit-exactly as the JSVM decoder decodes its sub-bitstream")
    void layer(Vector stream, Layer layer) {
        var output = decode(stream.bytes(), target(layer));
        assertLayer(stream, layer, output);
        for (var i = 0; i < output.layers.size(); i++) {
            var named = output.layers.get(i);
            if (layer.dqId == 0) {
                assertEquals(Optional.empty(), named, "frame " + i + " of the base layer names no layer");
            } else {
                assertEquals(Optional.of(new VideoLayer(layer.dqId, 0, VideoLayer.Type.PRIMARY)), named,
                        "frame " + i);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes the highest layer of every access unit")
    void highestLayer(Vector stream) {
        var output = decode(stream.bytes(), H264DecoderOptions.builder().highestLayer().build());
        assertLayer(stream, stream.top(), output);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes the base layer alone by default")
    void baseLayerByDefault(Vector stream) {
        var output = decode(stream.bytes(), H264DecoderOptions.defaults());
        assertLayer(stream, stream.base(), output);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes the highest layer below a target the stream does not code")
    void targetAboveLayers(Vector stream) {
        var output = decode(stream.bytes(), H264DecoderOptions.builder().targetLayer(7, 15).build());
        assertLayer(stream, stream.top(), output);
    }

    @Test
    @DisplayName("decodes the highest layer below a target between two layers")
    void targetBetweenLayers() {
        var stream = vector("svc-spatial");
        var output = decode(stream.bytes(), H264DecoderOptions.builder().targetLayer(1, 3).build());
        var expected = stream.layers.stream().filter(layer -> layer.dqId == 16).findFirst().orElseThrow();
        assertLayer(stream, expected, output);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes bit-exactly with four frame threads")
    void threaded(Vector stream) {
        var output = decode(stream.bytes(), H264DecoderOptions.builder().highestLayer().threads(4).build());
        assertLayer(stream, stream.top(), output);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes bit-exactly in low-delay mode, one access unit per packet")
    void lowDelay(Vector stream) {
        var output = new Output();
        var options = H264DecoderOptions.builder().realtime(true).highestLayer().build();
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var unit : accessUnits(H264TestStreams.nalUnits(stream.bytes()))) {
                decoder.decode(packet(H264TestStreams.annexB(unit)), output);
            }
            decoder.flush(output);
        }
        assertLayer(stream, stream.top(), output);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    @DisplayName("decodes the same frames whether the stream arrives whole or NAL unit by NAL unit")
    void wholeStream(Vector stream) {
        var output = new Output();
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().highestLayer()
                .build())) {
            decoder.decode(packet(stream.bytes()), output);
            decoder.flush(output);
        }
        assertLayer(stream, stream.top(), output);
    }

    @Nested
    @DisplayName("damage")
    class Damage {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264SvcStreamsTest#all")
        @DisplayName("decodes the base layer intact when every enhancement slice is lost")
        void lostEnhancement(Vector stream) {
            var nals = new ArrayList<byte[]>();
            for (var nal : H264TestStreams.nalUnits(stream.bytes())) {
                if ((nal[0] & 0x1F) != 20) {
                    nals.add(nal);
                }
            }
            var output = decode(H264TestStreams.annexB(nals), H264DecoderOptions.builder().highestLayer().build());
            assertLayer(stream, stream.base(), output);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264SvcStreamsTest#all")
        @DisplayName("keeps one frame per access unit when the enhancement of one access unit is lost")
        void lostAccessUnitEnhancement(Vector stream) {
            var units = accessUnits(H264TestStreams.nalUnits(stream.bytes()));
            var pictures = 0;
            var nals = new ArrayList<byte[]>();
            for (var unit : units) {
                var slices = unit.stream().anyMatch(nal -> (nal[0] & 0x1F) == 1 || (nal[0] & 0x1F) == 5);
                for (var nal : unit) {
                    if (!(slices && pictures == 2 && (nal[0] & 0x1F) == 20)) {
                        nals.add(nal);
                    }
                }
                if (slices) {
                    pictures++;
                }
            }
            var output = new Output();
            var options = H264DecoderOptions.builder().highestLayer().build();
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
                for (var nal : nals) {
                    try {
                        decoder.decode(packet(H264TestStreams.annexB(List.of(nal))), output);
                    } catch (MediaException expected) {
                        // a reference picture of the lost layer is missing
                    }
                }
                decoder.flush(output);
            }
            assertEquals(stream.top().frames, output.count(), "one frame per access unit");
            var base = Optional.of(new VideoLayer(stream.base().dqId, 0, VideoLayer.Type.PRIMARY));
            var top = Optional.of(new VideoLayer(stream.top().dqId, 0, VideoLayer.Type.PRIMARY));
            assertEquals(1, output.layers.stream().filter(base::equals).count(),
                    "the access unit without enhancement is output at the base layer: " + output.layers);
            assertEquals(output.count() - 1, output.layers.stream().filter(top::equals).count(), output.layers.toString());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264SvcStreamsTest#all")
        @DisplayName("flags every frame that differs from the intact decoding after a lost access unit")
        void lossesAreFlagged(Vector stream) {
            var units = accessUnits(H264TestStreams.nalUnits(stream.bytes()));
            var intact = decodeFlagged(units, -1);
            assertTrue(intact.damaged.isEmpty(), "an intact stream decodes without damage: " + intact.damaged);
            for (var lost = 1; lost < units.size(); lost++) {
                if (units.get(lost).stream().noneMatch(nal -> (nal[0] & 0x1F) == 1 || (nal[0] & 0x1F) == 5
                        || (nal[0] & 0x1F) == 20)) {
                    continue;
                }
                var decoded = decodeFlagged(units, lost);
                for (var entry : decoded.frames.entrySet()) {
                    var pts = entry.getKey();
                    if (!entry.getValue().equals(intact.frames.get(pts)) && !decoded.damaged.contains(pts)) {
                        throw new AssertionError(stream + ": access unit " + pts + " differs from the intact "
                                + "decoding without being flagged after the loss of access unit " + lost);
                    }
                }
                assertTrue(decoded.keyframeNeededAfterLoss, stream + ": a key frame is needed after losing access unit "
                        + lost);
            }
        }

        /** The frames of a decode by access unit index, the indices of the damaged ones, and the key frame state. */
        private record Flagged(java.util.Map<Long, String> frames, java.util.Set<Long> damaged,
                               boolean keyframeNeededAfterLoss) {
        }

        /**
         * Decodes the access units of a stream at the highest layer, one per packet with its index as timestamp,
         * losing the slices of one of them and reporting the loss.
         */
        private static Flagged decodeFlagged(List<List<byte[]>> units, int lost) {
            var frames = new java.util.HashMap<Long, String>();
            var damaged = new java.util.HashSet<Long>();
            var keyframeNeeded = false;
            H264Decoder.FrameConsumer sink = (frame, isDamaged) -> {
                frames.put(frame.pts(), HexFormat.of().formatHex(md5(H264TestStreams.serialize((YuvPicture) frame.picture()))));
                if (isDamaged) {
                    damaged.add(frame.pts());
                }
            };
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().highestLayer()
                    .build())) {
                for (var i = 0; i < units.size(); i++) {
                    var kept = new ArrayList<byte[]>();
                    for (var nal : units.get(i)) {
                        var type = nal[0] & 0x1F;
                        if (i != lost || (type != 1 && type != 5 && type != 14 && type != 20)) {
                            kept.add(nal);
                        }
                    }
                    if (i == lost) {
                        decoder.reportLoss();
                        keyframeNeeded = decoder.keyframeNeeded();
                    }
                    if (kept.isEmpty()) {
                        continue;
                    }
                    try {
                        decoder.decode(new Packet(1, H264TestStreams.annexB(kept), i, i, 1, false), sink);
                    } catch (MediaException expected) {
                        // a reference of the lost access unit is missing
                    }
                }
                decoder.flush(sink);
            }
            return new Flagged(frames, damaged, keyframeNeeded);
        }

        private static byte[] md5(byte[] data) {
            try {
                return MessageDigest.getInstance("MD5").digest(data);
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264SvcStreamsTest#all")
        @DisplayName("survives every truncation of a scalable stream")
        void truncation(Vector stream) {
            var data = stream.bytes();
            var options = H264DecoderOptions.builder().highestLayer().build();
            for (var length = 0; length < data.length; length += 13) {
                var output = new Output();
                try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
                    try {
                        decoder.decode(packet(Arrays.copyOf(data, length)), output);
                        decoder.flush(output);
                    } catch (MediaException expected) {
                        // a cut slice is reported as corrupt
                    }
                }
                assertFalse(output.count() > stream.top().frames, "more frames than the stream holds");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264SvcStreamsTest#all")
        @DisplayName("reports corrupt scalable slices as media failures and keeps decoding")
        void corruption(Vector stream) {
            var data = stream.bytes();
            var random = new SplittableRandom(stream.name.hashCode());
            var options = H264DecoderOptions.builder().highestLayer().build();
            for (var round = 0; round < 60; round++) {
                var nals = H264TestStreams.nalUnits(data);
                var damaged = new ArrayList<byte[]>();
                for (var nal : nals) {
                    var copy = nal.clone();
                    // corrupt the scalable slices past their NAL unit header
                    if ((copy[0] & 0x1F) == 20 && copy.length > 5 && random.nextInt(3) == 0) {
                        for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                            copy[4 + random.nextInt(copy.length - 4)] ^= (byte) (1 << random.nextInt(8));
                        }
                    }
                    damaged.add(copy);
                }
                var output = new Output();
                try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
                    for (var nal : damaged) {
                        try {
                            decoder.decode(packet(H264TestStreams.annexB(List.of(nal))), output);
                        } catch (MediaException expected) {
                            // the corrupt slice is reported, decoding continues
                        }
                    }
                    try {
                        decoder.flush(output);
                    } catch (MediaException expected) {
                        // as above
                    }
                }
                assertFalse(output.count() > stream.top().frames, "round " + round + ": more frames than the stream holds");
            }
        }
    }

    @Nested
    @DisplayName("temporal layers")
    class Temporal {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264SvcStreamsTest#all")
        @DisplayName("keeps a subset of the frames of the full decode, in order")
        void subset(Vector stream) {
            var full = decode(stream.bytes(), H264DecoderOptions.builder().highestLayer().build());
            for (var temporalId = 0; temporalId < stream.top().temporalId; temporalId++) {
                var thinned = decode(stream.bytes(), H264DecoderOptions.builder().highestLayer()
                        .maxTemporalId(temporalId).build());
                assertTrue(thinned.count() > 0 && thinned.count() < full.count(), "temporal_id " + temporalId);
                var position = 0;
                for (var frame : thinned.frames) {
                    while (position < full.frames.size() && !full.frames.get(position).equals(frame)) {
                        position++;
                    }
                    assertTrue(position < full.frames.size(), stream + ": a frame at temporal_id " + temporalId
                            + " is not a frame of the full decode, in order");
                    position++;
                }
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("default to the base layer and every temporal layer")
        void defaults() {
            var options = H264DecoderOptions.defaults();
            assertEquals(OptionalInt.of(0), options.targetLayer());
            assertEquals(OptionalInt.empty(), options.maxTemporalId());
        }

        @Test
        @DisplayName("hold the target as a DQId, or empty for the highest layer")
        void target() {
            assertEquals(OptionalInt.of(2 * 16 + 3), H264DecoderOptions.builder().targetLayer(2, 3).build().targetLayer());
            assertEquals(OptionalInt.of(127), H264DecoderOptions.builder().targetLayer(7, 15).build().targetLayer());
            assertEquals(OptionalInt.empty(), H264DecoderOptions.builder().highestLayer().build().targetLayer());
            assertEquals(OptionalInt.of(5), H264DecoderOptions.builder().highestLayer().targetLayer(0, 5).build()
                    .targetLayer());
            assertEquals(OptionalInt.of(4), H264DecoderOptions.builder().maxTemporalId(4).build().maxTemporalId());
            assertEquals(OptionalInt.empty(), H264DecoderOptions.builder().maxTemporalId(4).allTemporalLayers().build()
                    .maxTemporalId());
        }

        @Test
        @DisplayName("reject identifiers out of range")
        void ranges() {
            assertThrows(IllegalArgumentException.class, () -> H264DecoderOptions.builder().targetLayer(8, 0).build());
            assertThrows(IllegalArgumentException.class, () -> H264DecoderOptions.builder().targetLayer(0, 16).build());
            assertThrows(IllegalArgumentException.class, () -> H264DecoderOptions.builder().targetLayer(-1, 0).build());
            assertThrows(IllegalArgumentException.class, () -> H264DecoderOptions.builder().targetLayer(0, -1).build());
            assertThrows(IllegalArgumentException.class, () -> H264DecoderOptions.builder().maxTemporalId(8).build());
            assertThrows(IllegalArgumentException.class, () -> H264DecoderOptions.builder().maxTemporalId(-1).build());
            assertEquals(OptionalInt.empty(), H264DecoderOptions.builder().targetLayer(8, 0).highestLayer().build()
                    .targetLayer(), "a later valid setting replaces an invalid one");
        }

        @Test
        @DisplayName("round-trip through toBuilder and compare by value")
        void values() {
            var options = H264DecoderOptions.builder().targetLayer(1, 2).maxTemporalId(3).allViews().alpha(false)
                    .build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().highestLayer().build());
            assertNotEquals(options, options.toBuilder().allTemporalLayers().build());
            assertNotEquals(H264DecoderOptions.defaults(), H264DecoderOptions.builder().highestLayer().build());
            assertTrue(options.toString().contains("18"), options.toString());
        }
    }
}
