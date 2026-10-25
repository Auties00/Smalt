package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes H.264 streams with alpha auxiliary coded pictures (ITU-T H.264 7.4.2.1.2) and checks the
 * alpha plane each frame carries: the JVT conformance stream alphaconformanceG, whose archive holds
 * the alpha pictures decoded on their own, and the streams of {@code auxiliary/}, assembled from two
 * JM encodes by {@code tools/testdata/h264-aux/make-vectors.py} with their expected colors and alpha
 * planes (the JM decoder's output, the alpha mapped by the script's own model of the
 * interpretation rules). Variants of the sequence parameter set extension, built here, check the
 * interpretation rules: the {@code alpha_incr_flag} increment, inverted and narrowed ranges,
 * premultiplied color, and the cases that carry no alpha.
 */
@DisplayName("H.264 auxiliary coded pictures as alpha")
class H264AuxiliaryAlphaTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/FRExt/";

    private static final String SHA256 = "a0a853f1b280b5d86e9d9f0834dec150488faad31ab26fe59a7ca1fa74ca71cd";

    private static final int WIDTH = 960;

    private static final int HEIGHT = 544;

    /**
     * The conformance archive's combined stream, its color reference (test8b43.yuv) and its alpha
     * reference (the luma of test7a43.yuv, which holds the alpha pictures decoded as a stream of their
     * own).
     */
    record Archive(byte[] stream, byte[] color, byte[] alpha) {
    }

    private static Archive archive;

    static synchronized Archive archive() throws IOException {
        if (archive == null) {
            var file = Conformance.fetch("h264-FRExt-alphaconformanceG.zip", BASE + "alphaconformanceG.zip", SHA256);
            try (var zip = new ZipFile(file.toFile())) {
                archive = new Archive(read(zip, "alphaconformanceG/alphaconformanceG.264"),
                        read(zip, "alphaconformanceG/test8b43.yuv"), read(zip, "alphaconformanceG/test7a43.yuv"));
            }
        }
        return archive;
    }

    private static byte[] read(ZipFile zip, String entry) throws IOException {
        try (var input = zip.getInputStream(zip.getEntry(entry))) {
            return input.readAllBytes();
        }
    }

    /**
     * Returns the luma plane of a frame of a 4:2:0 reference.
     */
    static byte[] lumaOf(byte[] reference, int index) {
        var frame = WIDTH * HEIGHT * 3 / 2;
        return Arrays.copyOfRange(reference, index * frame, index * frame + WIDTH * HEIGHT);
    }

    static byte[] colorOf(byte[] reference, int index) {
        var frame = WIDTH * HEIGHT * 3 / 2;
        return Arrays.copyOfRange(reference, index * frame, (index + 1) * frame);
    }

    /**
     * A decoded frame copied out of the decoder.
     */
    record Decoded(byte[] color, byte[] alpha, boolean damaged) {
    }

    static List<Decoded> decode(byte[] stream, H264DecoderOptions options) {
        return decode(H264TestStreams.splitAnnexB(stream), options, false);
    }

    static List<Decoded> decode(List<byte[]> packets, H264DecoderOptions options, boolean checked) {
        var frames = new ArrayList<Decoded>();
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            H264Decoder.FrameConsumer sink = (frame, damaged) -> frames.add(copy(frame, damaged));
            for (var payload : packets) {
                var packet = new Packet(1, payload, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
                if (checked) {
                    decoder.decode(packet, sink);
                } else {
                    decoder.decode(packet, (VideoFrame frame) -> frames.add(copy(frame, false)));
                }
            }
            if (checked) {
                decoder.flush(sink);
            } else {
                decoder.flush((VideoFrame frame) -> frames.add(copy(frame, false)));
            }
        }
        return frames;
    }

    static Decoded copy(VideoFrame frame, boolean damaged) {
        if (frame.picture() instanceof YuvPicture picture) {
            byte[] alpha = null;
            if (picture.hasAlpha()) {
                alpha = new byte[picture.width() * picture.height()];
                for (var y = 0; y < picture.height(); y++) {
                    System.arraycopy(picture.alpha(), y * picture.alphaStride(), alpha, y * picture.width(),
                            picture.width());
                }
            }
            return new Decoded(H264TestStreams.serialize(picture), alpha, damaged);
        }
        var wide = (Yuv16Picture) frame.picture();
        return new Decoded(little(wide), wide.hasAlpha() ? plane(wide.alpha(), wide.alphaStride(), wide.width(),
                wide.height()) : null, damaged);
    }

    /**
     * Serializes a 16-bit picture as planar 4:2:0 little-endian samples.
     */
    static byte[] little(Yuv16Picture picture) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(plane(picture.y(), picture.yStride(), picture.width(), picture.height()));
        out.writeBytes(plane(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight()));
        out.writeBytes(plane(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight()));
        return out.toByteArray();
    }

    static byte[] plane(short[] samples, int stride, int width, int height) {
        var out = new byte[2 * width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = samples[y * stride + x];
                out[2 * (y * width + x)] = (byte) value;
                out[2 * (y * width + x) + 1] = (byte) (value >> 8);
            }
        }
        return out;
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Returns the stream with its sequence parameter set extension replaced, or removed for
     * {@code null}.
     */
    static byte[] withExtension(byte[] stream, byte[] extension) {
        var nals = new ArrayList<byte[]>();
        for (var nal : H264TestStreams.nalUnits(stream)) {
            if ((nal[0] & 0x1F) == 13) {
                if (extension != null) {
                    nals.add(extension);
                }
            } else {
                nals.add(nal);
            }
        }
        return H264TestStreams.annexB(nals);
    }

    /**
     * Builds a sequence parameter set extension for sequence parameter set 0.
     */
    static byte[] extension(int format, int depth, boolean incr, int opaque, int transparent) {
        var writer = new H264BitWriter().ue(0).ue(format);
        if (format != 0) {
            writer.ue(depth - 8).flag(incr).bits(opaque, depth + 1).bits(transparent, depth + 1);
        }
        writer.bit(0);
        return H264BitWriter.nal(3, 13, writer.trailing());
    }

    /**
     * The interpretation rule of ITU-T H.264 7.4.2.1.2, written independently of the decoder: the
     * increment, the clip to the opaque and transparent values and the scale to 0 and 255.
     */
    static int interpret(int sample, boolean incr, int opaque, int transparent) {
        var low = Math.min(opaque, transparent);
        var high = Math.max(opaque, transparent);
        var value = incr && sample > low ? sample + 1 : sample;
        value = Math.max(low, Math.min(high, value));
        return (Math.abs(value - transparent) * 255 + (high - low) / 2) / (high - low);
    }

    @Nested
    @DisplayName("conformance stream alphaconformanceG")
    class ConformanceStream {
        @ParameterizedTest(name = "threads={0}")
        @ValueSource(ints = {1, 4})
        @DisplayName("attaches each auxiliary picture as the alpha of its frame, bit-exactly")
        void alpha(int threads) throws IOException {
            var archive = archive();
            var frames = decode(archive.stream(), H264DecoderOptions.builder().threads(threads).build());
            assertEquals(40, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertArrayEquals(colorOf(archive.color(), i), frames.get(i).color(), "color of frame " + i);
                assertArrayEquals(lumaOf(archive.alpha(), i), frames.get(i).alpha(), "alpha of frame " + i);
            }
        }

        @Test
        @DisplayName("skips the auxiliary pictures with alpha(false)")
        void disabled() throws IOException {
            var archive = archive();
            var frames = decode(archive.stream(), H264DecoderOptions.builder().alpha(false).build());
            assertEquals(40, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(null, frames.get(i).alpha(), "frame " + i + " is opaque");
                assertArrayEquals(colorOf(archive.color(), i), frames.get(i).color());
            }
        }

        static Stream<Arguments> interpretations() {
            return Stream.of(Arguments.of(false, 0, 255), Arguments.of(true, 0, 128), Arguments.of(true, 192, 64),
                    Arguments.of(false, 16, 235), Arguments.of(false, 300, 100));
        }

        @ParameterizedTest(name = "incr={0} opaque={1} transparent={2}")
        @MethodSource("interpretations")
        @DisplayName("interprets the auxiliary samples as its sequence parameter set extension says")
        void interpretation(boolean incr, int opaque, int transparent) throws IOException {
            var archive = archive();
            var stream = withExtension(archive.stream(), extension(1, 8, incr, opaque, transparent));
            var frames = decode(stream, H264DecoderOptions.defaults());
            assertEquals(40, frames.size());
            for (var i = 0; i < frames.size(); i += 7) {
                var raw = lumaOf(archive.alpha(), i);
                var expected = new byte[raw.length];
                for (var k = 0; k < raw.length; k++) {
                    expected[k] = (byte) interpret(raw[k] & 0xFF, incr, opaque, transparent);
                }
                assertArrayEquals(expected, frames.get(i).alpha(), "alpha of frame " + i);
                assertArrayEquals(colorOf(archive.color(), i), frames.get(i).color());
            }
        }

        @Test
        @DisplayName("divides premultiplied color back by the alpha")
        void premultiplied() throws IOException {
            var archive = archive();
            var stream = withExtension(archive.stream(), extension(2, 8, false, 255, 0));
            var frames = decode(stream, H264DecoderOptions.defaults());
            assertEquals(40, frames.size());
            for (var i = 0; i < frames.size(); i += 13) {
                var alpha = lumaOf(archive.alpha(), i);
                var color = colorOf(archive.color(), i);
                assertArrayEquals(alpha, frames.get(i).alpha());
                var expected = new byte[color.length];
                for (var k = 0; k < WIDTH * HEIGHT; k++) {
                    expected[k] = (byte) divide(color[k] & 0xFF, alpha[k] & 0xFF);
                }
                var cw = WIDTH / 2;
                for (var plane = 0; plane < 2; plane++) {
                    var base = WIDTH * HEIGHT + plane * cw * (HEIGHT / 2);
                    for (var y = 0; y < HEIGHT / 2; y++) {
                        for (var x = 0; x < cw; x++) {
                            var sum = 0;
                            for (var dy = 0; dy < 2; dy++) {
                                for (var dx = 0; dx < 2; dx++) {
                                    sum += alpha[(2 * y + dy) * WIDTH + 2 * x + dx] & 0xFF;
                                }
                            }
                            var index = base + y * cw + x;
                            expected[index] = (byte) divide(color[index] & 0xFF, (sum + 2) / 4);
                        }
                    }
                }
                assertArrayEquals(expected, frames.get(i).color(), "color of frame " + i);
            }
        }

        private static int divide(int sample, int alpha) {
            return alpha == 0 ? sample : Math.min(255, (sample * 255 + alpha / 2) / alpha);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"unspecified use", "equal values", "no auxiliary pictures", "no extension"})
        @DisplayName("leaves frames opaque when the extension declares no alpha")
        void noAlpha(String variant) throws IOException {
            var archive = archive();
            var extension = switch (variant) {
                case "unspecified use" -> extension(3, 8, false, 255, 0);
                case "equal values" -> extension(1, 8, false, 100, 100);
                case "no auxiliary pictures" -> extension(0, 8, false, 0, 0);
                default -> null;
            };
            var frames = decode(withExtension(archive.stream(), extension), H264DecoderOptions.defaults());
            assertEquals(40, frames.size());
            for (var i = 0; i < frames.size(); i += 9) {
                assertEquals(null, frames.get(i).alpha(), "frame " + i);
                assertArrayEquals(colorOf(archive.color(), i), frames.get(i).color());
            }
        }

        @Test
        @DisplayName("outputs a frame whose auxiliary picture is lost opaque, and the others with their alpha")
        void lostAuxiliary() throws IOException {
            var archive = archive();
            var nals = new ArrayList<byte[]>();
            var auxiliary = 0;
            for (var nal : H264TestStreams.nalUnits(archive.stream())) {
                if ((nal[0] & 0x1F) == 19 && auxiliary++ == 5) {
                    continue;
                }
                nals.add(nal);
            }
            var frames = decode(H264TestStreams.annexB(nals), H264DecoderOptions.defaults());
            assertEquals(40, frames.size());
            // frames 0 to 4 do not predict from the lost picture; frame 5 has no alpha
            for (var i = 0; i < 5; i++) {
                assertArrayEquals(lumaOf(archive.alpha(), i), frames.get(i).alpha(), "alpha of frame " + i);
            }
            var opaque = new byte[WIDTH * HEIGHT];
            Arrays.fill(opaque, (byte) 255);
            assertArrayEquals(opaque, frames.get(5).alpha(), "frame 5 lost its alpha");
            for (var i = 0; i < frames.size(); i++) {
                assertArrayEquals(colorOf(archive.color(), i), frames.get(i).color(), "color of frame " + i);
            }
        }

        @Test
        @DisplayName("keeps outputting frames, the one whose auxiliary picture is undecodable damaged, without waiting for the flush")
        void undecodableAuxiliary() throws IOException {
            var archive = archive();
            var packets = new ArrayList<byte[]>();
            var auxiliary = 0;
            for (var nal : H264TestStreams.nalUnits(archive.stream())) {
                if ((nal[0] & 0x1F) == 19 && auxiliary++ == 5) {
                    // the slice header stops after first_mb_in_slice, so no auxiliary picture starts
                    nal = new byte[]{nal[0], (byte) 0x80};
                }
                packets.add(H264TestStreams.annexB(List.of(nal)));
            }
            var frames = new ArrayList<Decoded>();
            var beforeFlush = 0;
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.defaults())) {
                H264Decoder.FrameConsumer sink = (frame, damaged) -> frames.add(copy(frame, damaged));
                for (var payload : packets) {
                    try {
                        decoder.decode(new Packet(1, payload, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false),
                                sink);
                    } catch (MediaException.Malformed expected) {
                        // the cut auxiliary slice
                    }
                }
                beforeFlush = frames.size();
                decoder.flush(sink);
            }
            assertEquals(40, frames.size());
            assertTrue(beforeFlush >= 20, "only " + beforeFlush + " frames before the flush");
            for (var i = 0; i < 5; i++) {
                assertArrayEquals(lumaOf(archive.alpha(), i), frames.get(i).alpha(), "alpha of frame " + i);
            }
            assertTrue(frames.get(5).damaged(), "frame 5 lost its alpha");
            for (var i = 0; i < frames.size(); i++) {
                assertArrayEquals(colorOf(archive.color(), i), frames.get(i).color(), "color of frame " + i);
            }
        }

        @Test
        @DisplayName("outputs each frame with its alpha in low-delay mode")
        void lowDelay() throws IOException {
            var archive = archive();
            var units = H264TestStreams.accessUnits(H264TestStreams.nalUnits(archive.stream()));
            var packets = new ArrayList<byte[]>();
            for (var unit : units) {
                packets.add(H264TestStreams.annexB(unit));
            }
            var options = H264DecoderOptions.builder().realtime(true).build();
            var frames = decode(packets, options, true);
            assertEquals(40, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertFalse(frames.get(i).damaged(), "frame " + i);
                assertArrayEquals(lumaOf(archive.alpha(), i), frames.get(i).alpha(), "alpha of frame " + i);
            }
        }
    }

    /**
     * A stream of {@code auxiliary/streams.tsv}.
     */
    record Assembled(String name, String sha256, int colorDepth, int frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Assembled> assembled() {
        var result = new ArrayList<Assembled>();
        for (var line : resourceText("streams.tsv").split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            result.add(new Assembled(fields[0], fields[1], Integer.parseInt(fields[3]), Integer.parseInt(fields[8])));
        }
        return result;
    }

    static List<String[]> goldens(String name) {
        var result = new ArrayList<String[]>();
        for (var line : resourceText("frames.tsv").split("\n")) {
            if (!line.isBlank() && !line.startsWith("#") && line.startsWith(name + "\t")) {
                result.add(line.split("\t"));
            }
        }
        return result;
    }

    static String resourceText(String file) {
        return new String(resource(file), StandardCharsets.UTF_8).replace("\r", "");
    }

    static byte[] resource(String file) {
        try (var input = H264AuxiliaryAlphaTest.class.getResourceAsStream("auxiliary/" + file)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Stream<Arguments> assembledThreads() {
        return assembled().stream().flatMap(stream -> Stream.of(Arguments.of(stream, 1), Arguments.of(stream, 4)));
    }

    @ParameterizedTest(name = "{0} threads={1}")
    @MethodSource("assembledThreads")
    @DisplayName("decodes the colors and alpha planes of the assembled streams as the JM decoder does")
    void assembledStreams(Assembled stream, int threads) throws Exception {
        var data = resource(stream.name() + ".264");
        assertEquals(stream.sha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        var options = H264DecoderOptions.builder().threads(threads).highBitDepth(stream.colorDepth() > 8).build();
        var frames = decode(data, options);
        var goldens = goldens(stream.name());
        assertEquals(stream.frames(), goldens.size());
        assertEquals(goldens.size(), frames.size(), "frame count");
        for (var i = 0; i < frames.size(); i++) {
            assertEquals(goldens.get(i)[2], md5(frames.get(i).color()), stream + " color of frame " + i);
            assertEquals(goldens.get(i)[3], md5(frames.get(i).alpha()), stream + " alpha of frame " + i);
        }
    }

    @Test
    @DisplayName("rounds the alpha of a deeper stream to 8 bits with its color")
    void narrowedAlpha() {
        var stream = assembled().stream().filter(entry -> entry.colorDepth() > 8).findFirst().orElseThrow();
        var wide = decode(resource(stream.name() + ".264"), H264DecoderOptions.builder().highBitDepth(true).build());
        var narrow = decode(resource(stream.name() + ".264"), H264DecoderOptions.defaults());
        assertEquals(wide.size(), narrow.size());
        for (var i = 0; i < wide.size(); i++) {
            var deep = wide.get(i).alpha();
            var shallow = narrow.get(i).alpha();
            assertEquals(deep.length / 2, shallow.length);
            for (var k = 0; k < shallow.length; k++) {
                var value = (deep[2 * k] & 0xFF) | (deep[2 * k + 1] & 0xFF) << 8;
                // the 10-bit alpha of this stream spans 0 to 1023, the 8-bit one 0 to 255 for the same samples
                assertTrue(Math.abs((shallow[k] & 0xFF) * 1023 / 255 - value) <= 4,
                        "frame " + i + " sample " + k + ": " + (shallow[k] & 0xFF) + " versus " + value);
            }
        }
    }
}
