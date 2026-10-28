package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes 4:4:4 streams coded with separate color planes ({@code separate_colour_plane_flag}), which no encoder
 * writes: {@code tools/testdata/hevc-separate-planes/make-vectors.py} assembles each from three monochrome streams
 * the HM reference encoder coded with one configuration, one per plane, rewriting the parameter sets to 4:4:4 with
 * separate planes and every slice segment header to carry its {@code colour_plane_id}, and appends a decoded picture
 * hash SEI message of the three planes as HM decodes the monochrome streams ({@code planes/vectors.tsv} pins the
 * streams and HM's output). The vectors cover low delay B pictures with wavefronts and the planes one after the other
 * (later planes starting with a slice segment that is not flagged as the first of the picture), random access
 * pictures at 10 bits with temporal motion vector prediction and the slices of the planes interleaved (each plane
 * starting with a slice segment flagged as the first of the picture), and an intra picture that is also decoded from
 * an HEIC item. Every output must match HM's on one and several threads with the hash SEI messages verified, each
 * plane must equal the decode of its monochrome stream, and a picture that lacks a plane or names plane 3 must be
 * reported as malformed.
 */
@DisplayName("HEVC separate color planes")
class HevcSeparatePlanesTest {
    record Vector(String name, int width, int height, int frames, int bitDepth, String streamSha256,
                  String decodedSha256, List<String> frameMd5s) {
        @Override
        public String toString() {
            return name;
        }

        byte[] stream() {
            var data = resource(name + ".bit");
            assertEquals(streamSha256, sha256(data), name + " stream");
            return data;
        }

        byte[] plane(int plane) {
            return resource(name + "-" + plane + ".bit");
        }
    }

    static byte[] resource(String name) {
        try (var input = HevcSeparatePlanesTest.class.getResourceAsStream("planes/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing vector " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Stream<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        for (var line : new String(resource("vectors.tsv"), StandardCharsets.US_ASCII).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var columns = line.strip().split("\t");
            vectors.add(new Vector(columns[0], Integer.parseInt(columns[1]), Integer.parseInt(columns[2]),
                    Integer.parseInt(columns[3]), Integer.parseInt(columns[4]), columns[5], columns[6],
                    List.of(columns[7].split(","))));
        }
        return vectors.stream();
    }

    static Stream<Arguments> threads() {
        return vectors().flatMap(vector -> Stream.of(Arguments.of(vector, 1), Arguments.of(vector, 4)));
    }

    @ParameterizedTest(name = "{0} on {1} threads")
    @MethodSource("threads")
    @DisplayName("decodes like the HM reference decoder with every picture hash verified")
    void reference(Vector vector, int threads) {
        var frames = decode(HevcTestStreams.splitAnnexB(vector.stream()), threads);
        assertEquals(vector.frames(), frames.size(), vector + " frames");
        var all = new ByteArrayOutputStream();
        for (var i = 0; i < frames.size(); i++) {
            assertEquals(vector.frameMd5s().get(i), md5(frames.get(i)), vector + " frame " + i);
            all.writeBytes(frames.get(i));
        }
        assertEquals(vector.decodedSha256(), sha256(all.toByteArray()), vector + " decoded");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes each plane as its monochrome stream decodes")
    void planes(Vector vector) {
        var frames = decode(HevcTestStreams.splitAnnexB(vector.stream()), 1);
        var planeSize = vector.width() * vector.height() * (vector.bitDepth() > 8 ? 2 : 1);
        for (var plane = 0; plane < 3; plane++) {
            var mono = decode(HevcTestStreams.splitAnnexB(vector.plane(plane)), 1);
            assertEquals(frames.size(), mono.size(), vector + " plane " + plane + " frames");
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(planeSize, mono.get(i).length, vector + " monochrome output");
                assertArrayEquals(mono.get(i), Arrays.copyOfRange(frames.get(i), plane * planeSize,
                        (plane + 1) * planeSize), vector + " plane " + plane + " frame " + i);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("outputs 4:4:4 pictures at the luma bit depth")
    void format(Vector vector) {
        var options = HevcDecoderOptions.builder().highBitDepth(true).build();
        var formats = new ArrayList<ChromaFormat>();
        var depths = new ArrayList<Integer>();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                switch (frame.picture()) {
                    case YuvPicture picture -> {
                        formats.add(picture.chromaFormat());
                        depths.add(8);
                    }
                    case Yuv16Picture picture -> {
                        formats.add(picture.chromaFormat());
                        depths.add(picture.bitDepth());
                    }
                    default -> throw new AssertionError("unexpected picture " + frame.picture());
                }
            };
            for (var nal : HevcTestStreams.splitAnnexB(vector.stream())) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(vector.frames(), formats.size());
        assertTrue(formats.stream().allMatch(ChromaFormat.YUV444::equals), formats.toString());
        assertTrue(depths.stream().allMatch(depth -> depth == vector.bitDepth()), depths.toString());
    }

    static Stream<Arguments> missingPlanes() {
        // without plane 0, a vector whose later planes continue the picture has no slice segment starting the picture
        return vectors().flatMap(vector -> IntStream.range(vector.name().equals("planes-ra-10") ? 0 : 1, 3)
                .mapToObj(plane -> Arguments.of(vector, plane)));
    }

    @ParameterizedTest(name = "{0} without plane {1}")
    @MethodSource("missingPlanes")
    @DisplayName("reports a picture that lacks the slices of a plane as malformed")
    void missingPlane(Vector vector, int plane) {
        // the first picture ends with its decoded picture hash, the first suffix SEI message (NAL unit type 40)
        var kept = new ArrayList<byte[]>();
        var firstPicture = true;
        for (var nal : HevcTestStreams.nalUnits(vector.stream())) {
            if (!firstPicture || sliceNalPlane(nal) != plane) {
                kept.add(annexB(nal));
            }
            firstPicture &= HevcTestStreams.type(nal) != 40;
        }
        assertTrue(kept.size() < HevcTestStreams.splitAnnexB(vector.stream()).size(), "no slice dropped");
        var exception = assertThrows(MediaException.Malformed.class, () -> decode(kept, 1));
        assertTrue(exception.getMessage().contains("color plane"), exception.getMessage());
    }

    @Test
    @DisplayName("reports colour_plane_id 3 as malformed")
    void planeThree() {
        var vector = vectors().filter(v -> v.name().equals("planes-intra-8")).findFirst().orElseThrow();
        var nals = new ArrayList<byte[]>();
        for (var nal : HevcTestStreams.nalUnits(vector.stream())) {
            nals.add(annexB(sliceNalPlane(nal) == 2 ? withPlane(nal, 3) : nal));
        }
        var exception = assertThrows(MediaException.Malformed.class, () -> decode(nals, 1));
        assertTrue(exception.getMessage().contains("colour_plane_id"), exception.getMessage());
    }

    @Nested
    @DisplayName("in HEIC")
    class Heic {
        @Test
        @DisplayName("decodes an item coded with separate color planes as the HEVC decoder does")
        void item() {
            var vector = vectors().filter(v -> v.name().equals("planes-intra-8")).findFirst().orElseThrow();
            var sets = new ArrayList<byte[]>();
            var payload = new ByteArrayOutputStream();
            for (var nal : HevcTestStreams.nalUnits(vector.stream())) {
                var type = HevcTestStreams.type(nal);
                if (type >= 32 && type <= 34) {
                    sets.add(nal);
                } else if (type < 32) {
                    payload.write(nal.length >>> 24);
                    payload.write(nal.length >>> 16);
                    payload.write(nal.length >>> 8);
                    payload.write(nal.length);
                    payload.writeBytes(nal);
                }
            }
            var builder = new HeifBuilder().brands("heic", "heic", "mif1");
            var item = builder.item("hvc1", payload.toByteArray(),
                    plain(HeifBuilder.ispe(vector.width(), vector.height())),
                    essential(HeifBuilder.box("hvcC", HevcTestStreams.hvcC(sets))));
            builder.primary(item);
            var picture = assertInstanceOf(YuvPicture.class,
                    HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            assertEquals(vector.width(), picture.width());
            assertEquals(vector.height(), picture.height());
            assertEquals(vector.frameMd5s().getFirst(), md5(HevcTestStreams.raw(picture)));
        }
    }

    /**
     * Returns the {@code colour_plane_id} of an independent slice segment NAL unit of the vectors (no extra slice
     * header bits, no output flag), or -1 for any other NAL unit.
     */
    private static int sliceNalPlane(byte[] nal) {
        var type = HevcTestStreams.type(nal);
        if (type >= 32 || (type > 9 && type < 16) || type > 21) {
            return -1;
        }
        var reader = planePosition(unescape(nal), type);
        return reader.bits(2);
    }

    /**
     * Returns a reader positioned at the {@code colour_plane_id} of an independent slice segment of the vectors,
     * whose pictures hold four 64x64 coding tree blocks (a two-bit {@code slice_segment_address}).
     */
    private static Bits planePosition(byte[] rbsp, int type) {
        var reader = new Bits(rbsp, 16);
        var first = reader.bit();
        if (type >= 16) {
            reader.bit();
        }
        reader.ue();
        if (first == 0) {
            reader.bits(2);
        }
        reader.ue();
        return reader;
    }

    /**
     * Rewrites the {@code colour_plane_id} of an independent slice segment of the vectors.
     */
    private static byte[] withPlane(byte[] nal, int plane) {
        var rbsp = unescape(nal);
        var position = planePosition(rbsp, HevcTestStreams.type(nal)).position;
        for (var k = 0; k < 2; k++) {
            var bit = (plane >> (1 - k)) & 1;
            var index = (position + k) >> 3;
            var mask = 0x80 >> ((position + k) & 7);
            rbsp[index] = (byte) (bit != 0 ? rbsp[index] | mask : rbsp[index] & ~mask);
        }
        return escape(rbsp);
    }

    private static byte[] annexB(byte[] nal) {
        var out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static byte[] unescape(byte[] nal) {
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var b : nal) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    private static byte[] escape(byte[] rbsp) {
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var i = 0; i < rbsp.length; i++) {
            var b = rbsp[i] & 0xFF;
            if (i >= 2 && zeros >= 2 && b <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    private static final class Bits {
        private final byte[] data;
        private int position;

        Bits(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        int bit() {
            var value = (data[position >> 3] >> (7 - (position & 7))) & 1;
            position++;
            return value;
        }

        int bits(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = (value << 1) | bit();
            }
            return value;
        }

        int ue() {
            var zeros = 0;
            while (bit() == 0) {
                zeros++;
            }
            return (1 << zeros) - 1 + bits(zeros);
        }
    }

    private static List<byte[]> decode(List<byte[]> nals, int threads) {
        var frames = new ArrayList<byte[]>();
        var options = HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).threads(threads).build();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> frames.add(HevcTestStreams.raw(frame.picture()));
            for (var nal : nals) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    private static String md5(byte[] data) {
        return digest("MD5", data);
    }

    private static String sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
