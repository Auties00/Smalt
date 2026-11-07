package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the 3D-AVC stream {@code avc3d/syntax.264}, written from scratch by
 * {@code tools/testdata/h264-3davc/make-stream.py}, and checks every view against the samples the generator coded:
 * its base view components are I_PCM macroblocks, so their samples are known exactly, and every other view
 * component is skipped from the other view or the previous access unit with deblocking off, so it copies them. The
 * depth samples come out through the inverse of the depth parameter set's piecewise linear depth representation,
 * computed here from its nodes. A variant with 10-bit texture views using depth-based motion vector prediction,
 * which the Enhanced Multiview Depth High profile forbids, must lose those views alone.
 */
@DisplayName("H.264 3D-AVC syntax from a generated stream")
class H264Avc3dSyntaxTest {
    private static final int WIDTH = 32;

    private static final int HEIGHT = 16;

    static byte[] stream() throws IOException {
        return resource("avc3d/syntax.264");
    }

    static byte[] resource(String name) throws IOException {
        try (var input = H264Avc3dSyntaxTest.class.getResourceAsStream(name)) {
            return input.readAllBytes();
        }
    }

    /**
     * The luma samples of a picture of two I_PCM macroblocks of the generator, the first with {@code seed}.
     */
    static byte[] luma(int seed) {
        var out = new byte[WIDTH * HEIGHT];
        for (var mb = 0; mb < 2; mb++) {
            for (var i = 0; i < 256; i++) {
                var value = ((seed + mb) * 37 + i * 11 + (i >> 4) * 7) & 0xFF;
                out[(i >> 4) * WIDTH + mb * 16 + (i & 15)] = (byte) value;
            }
        }
        return out;
    }

    /**
     * The inverse of the piecewise linear depth representation with the nodes 12 and 5 the depth parameter set
     * codes, as 3DV-ATM draws each segment.
     */
    static int[] inverseRepresentation() {
        int[] points = {0, 12, 5, 0};
        var lut = new int[256];
        var segments = points.length - 1;
        for (var k = 0; k < segments; k++) {
            var x1 = 255 * k / segments - points[k];
            var y1 = 255 * k / segments + points[k];
            var x2 = 255 * (k + 1) / segments - points[k + 1];
            var y2 = 255 * (k + 1) / segments + points[k + 1];
            var half = y2 - y1 > 0 ? (x2 - x1) >> 1 : -((x2 - x1) >> 1);
            for (var x = Math.max(x1, 0); x <= Math.min(x2, 255); x++) {
                lut[x] = Math.clamp(((x - x1) * (y2 - y1) + half) / (x2 - x1) + y1, 0, 255);
            }
        }
        return lut;
    }

    static Map<String, List<byte[]>> decode(H264DecoderOptions options) throws IOException {
        return decode(stream(), options, null);
    }

    /**
     * Decodes a stream to the luma samples of each view's pictures, collecting the failures the decoder reports
     * when {@code failures} is not {@code null}.
     */
    static Map<String, List<byte[]>> decode(byte[] stream, H264DecoderOptions options, List<MediaException> failures) {
        var frames = new TreeMap<String, List<byte[]>>();
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            java.util.function.Consumer<VideoFrame> sink = frame -> {
                var layer = frame.sideData().layer().orElse(null);
                var key = layer == null ? "base" : (layer.type() == VideoLayer.Type.DEPTH ? "d" : "v") + layer.viewId();
                var picture = (YuvPicture) frame.picture();
                assertEquals(WIDTH, picture.width());
                assertEquals(HEIGHT, picture.height());
                var luma = new byte[WIDTH * HEIGHT];
                for (var y = 0; y < HEIGHT; y++) {
                    System.arraycopy(picture.y(), y * picture.yStride(), luma, y * WIDTH, WIDTH);
                }
                frames.computeIfAbsent(key, ignored -> new ArrayList<>()).add(luma);
            };
            for (var nal : H264TestStreams.splitAnnexB(stream)) {
                try {
                    decoder.decode(H264MvcConformanceTest.packet(nal), sink);
                } catch (MediaException failure) {
                    if (failures == null) {
                        throw failure;
                    }
                    failures.add(failure);
                }
            }
            decoder.flush(sink);
        }
        return frames;
    }

    /**
     * The depth samples the generator's depth views decode to: its I_PCM pattern through the inverse depth
     * representation.
     */
    static byte[] depth() {
        var lut = inverseRepresentation();
        var coded = luma(5);
        var depth = new byte[coded.length];
        for (var i = 0; i < coded.length; i++) {
            depth[i] = (byte) lut[coded[i] & 0xFF];
        }
        return depth;
    }

    @Test
    @DisplayName("the stream is the one streams.tsv pins")
    void pinned() throws IOException, NoSuchAlgorithmException {
        assertEquals("f4e1b62425288165eb5c1d55649bbe78bf79220135c3d90b563ac161645b7d0a",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream())));
    }

    @ParameterizedTest(name = "threads {0}")
    @ValueSource(ints = {1, 4})
    @DisplayName("decodes every texture and depth view component to the samples the generator coded")
    void allViews(int threads) throws IOException {
        var frames = decode(H264DecoderOptions.builder().allViews().depth(true).threads(threads).build());
        assertEquals(List.of("d0", "d1", "v0", "v1"), List.copyOf(frames.keySet()));
        var texture = luma(1);
        var depth = depth();
        for (var key : frames.keySet()) {
            var expected = key.startsWith("v") ? texture : depth;
            assertEquals(2, frames.get(key).size(), key);
            for (var picture : frames.get(key)) {
                assertArrayEquals(expected, picture, key);
            }
        }
    }

    @ParameterizedTest(name = "threads {0}")
    @ValueSource(ints = {1, 4})
    @DisplayName("drops the texture views whose profile_idc 139 set codes 10-bit samples as malformed")
    void highBitDepthTexture(int threads) throws IOException, NoSuchAlgorithmException {
        // make-stream.py --high-bit-depth-texture: the texture views' subset sequence parameter set codes 10-bit
        // samples and their P slices set dmvp_flag, which ITU-T H.264 I.10.1.1 forbids
        var stream = resource("avc3d/syntax-high-bit-depth-texture.264");
        assertEquals("a24c09e2d0628da38403724994ca9ee7ccbe46e247e39892be0377529e72dd45",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream)));
        var failures = new ArrayList<MediaException>();
        var frames = decode(stream, H264DecoderOptions.builder().allViews().depth(true).threads(threads).build(),
                failures);
        assertEquals(List.of("d0", "d1", "v0"), List.copyOf(frames.keySet()));
        for (var key : frames.keySet()) {
            var expected = key.startsWith("v") ? luma(1) : depth();
            assertEquals(2, frames.get(key).size(), key);
            for (var picture : frames.get(key)) {
                assertArrayEquals(expected, picture, key);
            }
        }
        assertEquals(1, failures.size(), failures.toString());
        var failure = assertInstanceOf(MediaException.Malformed.class, failures.getFirst());
        assertTrue(failure.getMessage().contains("samples above 8 bits"), failure.getMessage());
    }

    @Test
    @DisplayName("undoes a non-linear depth representation that is not the identity")
    void representation() {
        var lut = inverseRepresentation();
        assertEquals(0, lut[0]);
        assertEquals(255, lut[255]);
        assertEquals(false, Arrays.equals(lut, java.util.stream.IntStream.range(0, 256).toArray()));
    }

    @Test
    @DisplayName("outputs the base texture view alone by default")
    void baseView() throws IOException {
        var frames = decode(H264DecoderOptions.defaults());
        assertEquals(List.of("base"), List.copyOf(frames.keySet()));
        assertEquals(2, frames.get("base").size());
        assertArrayEquals(luma(1), frames.get("base").getFirst());
    }

    @Test
    @DisplayName("reports a predicted slice header whose source view component is missing as malformed")
    void missingSource() throws IOException {
        var nals = H264TestStreams.splitAnnexB(stream());
        // NAL unit 6 is the base view's slice, which every predicted header of the first access unit
        // copies from directly or through another view component
        var failures = new ArrayList<MediaException>();
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().allViews()
                .depth(true).build())) {
            for (var k = 0; k < nals.size(); k++) {
                if (k == 6) {
                    continue;
                }
                try {
                    decoder.decode(H264MvcConformanceTest.packet(nals.get(k)), frame -> { });
                } catch (MediaException failure) {
                    failures.add(failure);
                }
            }
            decoder.flush(frame -> { });
        }
        assertEquals(false, failures.isEmpty());
        assertThrows(MediaException.Malformed.class, () -> {
            throw failures.getFirst();
        });
    }
}
