package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the libaom scalability vectors (one spatial layer and two temporal layers, two spatial layers, and two of
 * each) through the public decoder at every operating point their sequence headers declare, and at one they do not,
 * with and without {@link Av1DecoderOptions#allLayers()}, and requires every output frame, in order, to have the size
 * and MD5 of libaom's aomdec output for the same settings ({@code layers/layers.tsv}, written by
 * {@code tools/testdata/av1-layers/make-references.py}). Each case also runs on the calling thread and with frames in
 * flight, whose output must be the same.
 */
@DisplayName("AV1 operating points and spatial layers")
class Av1LayersTest {
    private static final List<String> VECTORS = List.of("av1-1-b8-22-svc-L1T2.ivf", "av1-1-b8-22-svc-L2T1-2.ivf",
            "av1-1-b8-22-svc-L2T2-2.ivf");

    /** The frames of one decode: width, height and MD5 of each, in output order. */
    record Frame(int width, int height, String md5) {
    }

    private static final Map<String, List<Frame>> REFERENCES = references();

    private static Map<String, List<Frame>> references() {
        try (var input = Av1LayersTest.class.getResourceAsStream("layers/layers.tsv")) {
            var references = new LinkedHashMap<String, List<Frame>>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                var key = key(columns[0], Integer.parseInt(columns[1]), columns[2].equals("1"));
                var frames = references.computeIfAbsent(key, _ -> new ArrayList<>());
                assertEquals(frames.size(), Integer.parseInt(columns[3]), "frame index of " + key);
                frames.add(new Frame(Integer.parseInt(columns[4]), Integer.parseInt(columns[5]), columns[6]));
            }
            return references;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String key(String vector, int point, boolean allLayers) {
        return vector + " " + point + " " + allLayers;
    }

    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (var vector : VECTORS) {
            for (var point = 0; point < 32; point++) {
                for (var allLayers : new boolean[]{false, true}) {
                    if (REFERENCES.containsKey(key(vector, point, allLayers))) {
                        cases.add(Arguments.of(vector, point, allLayers));
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} operating point {1}, all layers {2}")
    @MethodSource("cases")
    @DisplayName("every frame matches aomdec's output for the operating point and layer setting")
    void operatingPoint(String vector, int point, boolean allLayers) {
        var expected = REFERENCES.get(key(vector, point, allLayers));
        var options = Av1DecoderOptions.builder().operatingPoint(point).allLayers(allLayers);
        assertEquals(expected, decode(vector, options.build()), "default threads");
        assertEquals(expected, decode(vector, options.threads(1).build()), "calling thread");
        assertEquals(expected, decode(vector, options.threads(0).maxFrameDelay(4).build()), "frames in flight");
    }

    @Test
    @DisplayName("the operating points of a two-layer stream decode different frames, and every layer adds frames")
    void pointsDiffer() {
        var vector = "av1-1-b8-22-svc-L2T2-2.ivf";
        var full = decode(vector, Av1DecoderOptions.defaults());
        var base = decode(vector, Av1DecoderOptions.builder().operatingPoint(3).build());
        var layered = decode(vector, Av1DecoderOptions.builder().allLayers(true).build());
        assertNotEquals(full, base);
        assertTrue(layered.size() > full.size(), "all layers output " + layered.size() + " frames");
        assertTrue(layered.containsAll(full), "the highest layer is among all layers");
    }

    static List<Frame> decode(String vector, Av1DecoderOptions options) {
        var frames = new ArrayList<Frame>();
        Consumer<VideoFrame> sink = frame -> {
            var picture = (YuvPicture) frame.picture();
            frames.add(new Frame(picture.width(), picture.height(), md5(picture)));
        };
        try (var source = MediaSource.of(Files.readAllBytes(Conformance.fetchListed("av1-" + vector)));
             var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            try (var decoder = new Av1Decoder(track, options)) {
                for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return frames;
    }

    static String md5(YuvPicture picture) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        for (var y = 0; y < picture.height(); y++) {
            digest.update(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var plane : new byte[][]{picture.u(), picture.v()}) {
            for (var y = 0; y < picture.chromaHeight(); y++) {
                digest.update(plane, y * picture.uvStride(), picture.chromaWidth());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
