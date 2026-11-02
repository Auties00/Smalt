package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the libjxl conformance suite: every displayed frame of every case, decoded at full
 * precision, must stay within the case's published RMSE and peak error of the reference decode, in
 * every color and extra channel the reference holds; and the recompressed JPEG cases must rebuild
 * their original JPEG files bit for bit.
 */
class JxlConformanceTest {
    /** The reference holds the samples at the suite's precision; both renders round them to 16 bits. */
    static final Tolerance SPOTS = Tolerance.maxAbs(2);

    static java.util.List<JxlVectors.Case> cases() {
        return JxlVectors.CASES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches the reference decode within the published tolerance")
    void conformance(JxlVectors.Case test) {
        var data = JxlVectors.read(test.input());
        var codestream = JxlCodestream.open(data, MediaLimits.DEFAULT);
        if (test.referenceSha256() == null) {
            assertNotNull(codestream.nextKeyframe(), "the case decodes");
            return;
        }
        var reference = test.reference();
        var orientation = codestream.header().orientation();
        var frames = 0;
        var worstRmse = 0.0;
        var worstPeak = 0.0;
        JxlCodestream.Keyframe keyframe;
        while ((keyframe = codestream.nextKeyframe()) != null) {
            assertTrue(frames < reference.frames(), "more frames than the reference");
            var canvas = keyframe.canvas();
            var width = canvas.width;
            var height = canvas.height;
            var transposed = orientation > 4;
            assertEquals(reference.width(), transposed ? height : width, "displayed width");
            assertEquals(reference.height(), transposed ? width : height, "displayed height");
            var channels = Math.min(canvas.planes.length, reference.channels());
            assertEquals(reference.channels(), canvas.planes.length, "channel count");
            var sums = new double[channels];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var shown = orient(x, y, width, height, orientation);
                    for (var c = 0; c < channels; c++) {
                        var error = Math.abs((double) canvas.planes[c][y * width + x]
                                - reference.sample(frames, shown[0], shown[1], c));
                        if (Double.isNaN(error)) {
                            error = Double.POSITIVE_INFINITY;
                        }
                        sums[c] += error * error;
                        worstPeak = Math.max(worstPeak, error);
                    }
                }
            }
            for (var sum : sums) {
                worstRmse = Math.max(worstRmse, Math.sqrt(sum / ((double) width * height)));
            }
            frames++;
        }
        assertEquals(reference.frames(), frames, "frame count");
        assertTrue(worstRmse <= test.rmse(), "RMSE " + worstRmse + " above " + test.rmse());
        assertTrue(worstPeak <= test.peak(), "peak error " + worstPeak + " above " + test.peak());
        if (test.name().equals("spot")) {
            assertSpotsRendered(data, reference);
        }
    }

    /**
     * Checks that the RGBA picture of the spot case composites both spot channels of the reference over its
     * color, in the output color space, as libjxl's default render does.
     */
    static void assertSpotsRendered(byte[] data, JxlVectors.Reference reference) {
        var actual = (Rgba16Picture) JxlDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder()
                .highBitDepth(true).build()).picture();
        var infos = JxlCodestream.open(data, MediaLimits.DEFAULT).header().extraChannels();
        var expected = new short[actual.width() * actual.height() * 3];
        var got = new short[expected.length];
        for (var y = 0; y < actual.height(); y++) {
            for (var x = 0; x < actual.width(); x++) {
                var rgb = new float[3];
                for (var c = 0; c < 3; c++) {
                    rgb[c] = reference.sample(0, x, y, c);
                }
                for (var e = 0; e < infos.size(); e++) {
                    var spot = infos.get(e).spotColor();
                    if (spot != null) {
                        var mix = spot[3] * reference.sample(0, x, y, 3 + e);
                        for (var c = 0; c < 3; c++) {
                            rgb[c] = mix * spot[c] + (1 - mix) * rgb[c];
                        }
                    }
                }
                for (var c = 0; c < 3; c++) {
                    var i = (y * actual.width() + x) * 3 + c;
                    expected[i] = (short) Math.round(Math.min(Math.max(rgb[c], 0), 1) * 65535);
                    got[i] = actual.pixels()[y * actual.stride() + x * 4 + c];
                }
            }
        }
        SPOTS.assertWithin("spot colors rendered", expected, got, 16);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "cafe, b6f6e4f820ac69234184434e5b77156401fb782bb46b96e26255ff51be1ec290",
            "bench_oriented_brg, cad665c67d74e3e5cf775ef618c73b0e70dfece33db7dbe0130bc889f2214e1b",
            "grayscale_jpeg, a170600cc02b2b029dc79c5ee72dbf9107e8de5a64f1d8b1732c759e09a3d41d"
    })
    @DisplayName("rebuilds the original JPEG file bit for bit")
    void jpegReconstruction(String name, String sha256) throws Exception {
        // The digests are those of the conformance suite's reconstructed.jpg references.
        var data = JxlVectors.read(JxlVectors.find(name).input());
        var jpeg = JxlDecoder.reconstructJpeg(MediaSource.of(data)).orElseThrow();
        assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg)));
    }

    /**
     * Maps a stored pixel to its displayed position under an EXIF orientation.
     */
    static int[] orient(int x, int y, int width, int height, int orientation) {
        return switch (orientation) {
            case 2 -> new int[]{width - 1 - x, y};
            case 3 -> new int[]{width - 1 - x, height - 1 - y};
            case 4 -> new int[]{x, height - 1 - y};
            case 5 -> new int[]{y, x};
            case 6 -> new int[]{height - 1 - y, x};
            case 7 -> new int[]{height - 1 - y, width - 1 - x};
            case 8 -> new int[]{y, width - 1 - x};
            default -> new int[]{x, y};
        };
    }
}
