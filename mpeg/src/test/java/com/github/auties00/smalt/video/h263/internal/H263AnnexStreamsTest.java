package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the streams of tools/testdata/h263-annexes, which exercise the optional modes of ITU-T H.263 the baseline
 * engine lacks (Annexes C, E, G, K with rectangular slices, M, N, O, P, Q, R and U), with the annex engine alone.
 *
 * <p>Every stream comes with the MD5 of the reconstruction its encoder computed with the double precision reference
 * inverse DCT of the test models, the TMN 3.2 encoder for the tmn-* streams and an encoder written from the text of the
 * Recommendation for the smalt-* ones. Decoded with the same transform, every sub-bitstream must reproduce it sample
 * for sample; decoded with the integer transform, every picture must stay within its noise floor.
 */
@DisplayName("H.263 annex streams")
class H263AnnexStreamsTest {
    private static final String VECTORS = "/com/github/auties00/smalt/video/h263/annex/";

    /**
     * The integer transform differs from the reference one by less than the IEEE 1180 tolerance; the lowest PSNR
     * between the two decodes of a committed stream is 57 dB.
     */
    private static final double INTEGER_TRANSFORM_PSNR = 50;

    record Vector(String file, String origin, String sha256, int[] pictures, String[] sizes, String[] md5s) {
        @Override
        public String toString() {
            return file;
        }
    }

    record Decoded(int width, int height, byte[] samples, long time, boolean keyframe, int layer) {
    }

    static List<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        for (var line : new String(resource("golden.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            assertEquals(6, fields.length, line);
            vectors.add(new Vector(fields[0], fields[1], fields[2],
                    Arrays.stream(fields[3].split(",")).mapToInt(Integer::parseInt).toArray(), fields[4].split(","),
                    fields[5].split(",")));
        }
        assertEquals(28, vectors.size(), "vectors in golden.tsv");
        return vectors;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("reproduces the encoder's reconstruction with the reference inverse DCT")
    void reproducesReference(Vector vector) {
        var data = resource(vector.file());
        assertEquals(vector.sha256(), HexFormat.of().formatHex(messageDigest("SHA-256").digest(data)), "SHA-256 of the stream");
        var decoded = decode(data, true);
        assertEquals(vector.pictures().length, decoded.size(), "sub-bitstreams");
        var index = 0;
        for (var pictures : decoded.values()) {
            assertEquals(vector.pictures()[index], pictures.size(), "pictures of sub-bitstream " + index);
            for (var i = 0; i < pictures.size(); i++) {
                var size = vector.sizes()[vector.sizes().length == 1 ? 0 : i];
                if (index == 0 || vector.sizes().length == 1) {
                    assertEquals(size, pictures.get(i).width() + "x" + pictures.get(i).height(), "size of picture " + i);
                }
            }
            var md5 = messageDigest("MD5");
            for (var picture : pictures) {
                md5.update(picture.samples());
            }
            assertEquals(vector.md5s()[index], HexFormat.of().formatHex(md5.digest()),
                    "MD5 of sub-bitstream " + index);
            assertTrue(pictures.getFirst().keyframe(), "first picture is an intra picture");
            for (var i = 1; i < pictures.size(); i++) {
                assertTrue(pictures.get(i).time() > pictures.get(i - 1).time(), "pictures in display order");
            }
            index++;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("stays within the inverse DCT noise floor with the integer transform")
    void integerTransform(Vector vector) {
        var data = resource(vector.file());
        var reference = decode(data, true);
        var decoded = decode(data, false);
        assertEquals(reference.keySet(), decoded.keySet(), "sub-bitstreams");
        for (var entry : reference.entrySet()) {
            var expected = entry.getValue();
            var actual = decoded.get(entry.getKey());
            assertEquals(expected.size(), actual.size(), "pictures of sub-bitstream " + entry.getKey());
            for (var i = 0; i < expected.size(); i++) {
                var a = expected.get(i);
                var b = actual.get(i);
                assertEquals(a.width() + "x" + a.height(), b.width() + "x" + b.height(), "size of picture " + i);
                assertEquals(a.time(), b.time(), "time of picture " + i);
                assertEquals(a.layer(), b.layer(), "layer of picture " + i);
                var psnr = Tolerance.Difference.of(a.samples(), b.samples()).psnr();
                assertTrue(psnr >= INTEGER_TRANSFORM_PSNR, "sub-bitstream " + entry.getKey() + ", picture " + i
                        + ": " + psnr + " dB");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes the stream again after a reset as a new decoder does")
    void resetRestarts(Vector vector) {
        var data = resource(vector.file());
        var stream = new H263PlusStream(MediaLimits.defaults());
        var starts = pictureStarts(data);
        var ignored = new TreeMap<Integer, List<Decoded>>();
        for (var k = 0; k < starts.size() / 2; k++) {
            decodeUnit(stream, data, starts, k, ignored);
        }
        stream.reset();
        var again = new TreeMap<Integer, List<Decoded>>();
        for (var k = 0; k < starts.size(); k++) {
            decodeUnit(stream, data, starts, k, again);
        }
        stream.flush(sink(again));
        var fresh = decode(data, false);
        assertEquals(fresh.keySet(), again.keySet(), "sub-bitstreams");
        for (var entry : fresh.entrySet()) {
            var expected = entry.getValue();
            var actual = again.get(entry.getKey());
            assertEquals(expected.size(), actual.size(), "pictures");
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i).samples(), actual.get(i).samples(), "picture " + i);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("reports corrupt and truncated streams only as media exceptions")
    void survivesCorruption(Vector vector) {
        var original = resource(vector.file());
        var random = new SplittableRandom(vector.sha256().hashCode());
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (var round = 0; round < 24; round++) {
                var data = original.clone();
                var length = data.length;
                switch (round % 3) {
                    case 0 -> {
                        for (var i = 0; i < 1 + random.nextInt(8); i++) {
                            data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                        }
                    }
                    case 1 -> length = random.nextInt(data.length);
                    default -> {
                        var at = random.nextInt(data.length);
                        var span = Math.min(data.length - at, 1 + random.nextInt(64));
                        for (var i = 0; i < span; i++) {
                            data[at + i] = (byte) random.nextInt(256);
                        }
                    }
                }
                var stream = new H263PlusStream(MediaLimits.defaults());
                var truncated = Arrays.copyOf(data, length);
                var starts = pictureStarts(truncated);
                var out = new TreeMap<Integer, List<Decoded>>();
                for (var k = 0; k < starts.size(); k++) {
                    try {
                        decodeUnit(stream, truncated, starts, k, out);
                    } catch (MediaException expected) {
                        assertNotNull(expected.getMessage());
                    }
                }
                try {
                    stream.flush(sink(out));
                } catch (MediaException expected) {
                    assertNotNull(expected.getMessage());
                }
            }
        });
    }

    /**
     * Decodes a stream with the annex engine, the pictures of each sub-bitstream in display order.
     */
    static Map<Integer, List<Decoded>> decode(byte[] data, boolean referenceTransform) {
        var stream = new H263PlusStream(MediaLimits.defaults());
        stream.useReferenceTransform(referenceTransform);
        var starts = pictureStarts(data);
        var out = new TreeMap<Integer, List<Decoded>>();
        for (var k = 0; k < starts.size(); k++) {
            decodeUnit(stream, data, starts, k, out);
        }
        stream.flush(sink(out));
        return out;
    }

    private static void decodeUnit(H263PlusStream stream, byte[] data, List<Integer> starts, int unit,
                                   Map<Integer, List<Decoded>> out) {
        var start = starts.get(unit);
        var end = unit + 1 < starts.size() ? starts.get(unit + 1) : data.length;
        stream.decode(data, start, end - start, sink(out));
    }

    private static H263PlusStream.Sink sink(Map<Integer, List<Decoded>> out) {
        return (picture, time, keyframe, layer, subBitstream) -> out.computeIfAbsent(subBitstream,
                ignored -> new ArrayList<>()).add(new Decoded(picture.width(), picture.height(), planar(picture), time,
                keyframe, layer));
    }

    /**
     * Returns the offsets of the byte aligned picture start codes, which both encoders align.
     */
    static List<Integer> pictureStarts(byte[] data) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] & 0xFC) == 0x80) {
                starts.add(i);
            }
        }
        return starts;
    }

    /**
     * Returns the planar 4:2:0 samples of a picture without stride padding.
     */
    static byte[] planar(YuvPicture picture) {
        var width = picture.width();
        var chromaWidth = picture.chromaWidth();
        var out = new byte[width * picture.height() + 2 * chromaWidth * picture.chromaHeight()];
        var at = 0;
        for (var row = 0; row < picture.height(); row++, at += width) {
            System.arraycopy(picture.y(), row * picture.yStride(), out, at, width);
        }
        for (var row = 0; row < picture.chromaHeight(); row++, at += chromaWidth) {
            System.arraycopy(picture.u(), row * picture.uvStride(), out, at, chromaWidth);
        }
        for (var row = 0; row < picture.chromaHeight(); row++, at += chromaWidth) {
            System.arraycopy(picture.v(), row * picture.uvStride(), out, at, chromaWidth);
        }
        return out;
    }

    static byte[] resource(String name) {
        try (var input = H263AnnexStreamsTest.class.getResourceAsStream(VECTORS + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
