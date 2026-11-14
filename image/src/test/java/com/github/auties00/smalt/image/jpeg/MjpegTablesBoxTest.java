package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.testkit.validate.Validators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers the {@code mjqt} and {@code mjht} sample description boxes against QuickTime 7's own behavior: its Motion-JPEG B
 * exports, its drawings of them, and its verdict on every shape of box (fixtures and measurements in
 * {@code tools/testdata/mjpeg-qt}).
 */
@DisplayName("Motion-JPEG sample description tables")
class MjpegTablesBoxTest {
    private static final String ROOT = "quicktime/";
    private static final int WIDTH = 48;
    private static final int HEIGHT = 32;

    // Shapes QuickTime fails to draw or draws as garbage, which leave a frame with no usable quantization table.
    private static final Set<String> REJECTED = Set.of("q-no-box.mov", "q-payload.mov", "q-marker.mov",
            "q-bare-tables.mov", "q-length-zero.mov", "q-length-past.mov", "q-length-inside.mov", "q-empty.mov");

    private static byte[] resource(String name) {
        try (var in = MjpegTablesBoxTest.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new AssertionError("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] inflated(String name) throws DataFormatException {
        var inflater = new Inflater();
        inflater.setInput(resource(name));
        var out = new ByteArrayOutputStream();
        var buffer = new byte[1 << 16];
        while (!inflater.finished()) {
            var read = inflater.inflate(buffer);
            if (read == 0 && inflater.needsInput()) {
                throw new AssertionError("truncated " + name);
            }
            out.write(buffer, 0, read);
        }
        inflater.end();
        return out.toByteArray();
    }

    private static List<Picture> decode(MotionJpegTesting.Stream stream) {
        var pictures = new ArrayList<Picture>();
        var decoder = new MjpegDecoder(stream.track());
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> pictures.add(frame.picture()));
        }
        return pictures;
    }

    private static List<Picture> decode(String file) {
        return decode(MotionJpegTesting.demux(resource(file)));
    }

    private static List<byte[]> planes(List<Picture> pictures) {
        return pictures.stream().map(MotionJpegTesting::planes).toList();
    }

    private static void assertSameFrames(List<Picture> expected, List<Picture> actual, String label) {
        var a = planes(expected);
        var b = planes(actual);
        assertTrue(!b.isEmpty() && b.size() <= a.size(), label);
        for (var i = 0; i < b.size(); i++) {
            assertArrayEquals(a.get(i), b.get(i), label + " frame " + i);
        }
    }

    record Row(String file, String reference, String verdict) {
        @Override
        public String toString() {
            return file + " (QuickTime: " + verdict + ")";
        }
    }

    static Stream<Row> rows() {
        return new String(resource("expected.tsv"), StandardCharsets.US_ASCII).lines()
                .filter(line -> !line.startsWith("#") && !line.isBlank())
                .map(line -> line.split("\t"))
                .map(fields -> new Row(fields[0], fields[1], fields[2]));
    }

    @Test
    @DisplayName("the fixtures match their pinned hashes")
    void pinned() throws NoSuchAlgorithmException {
        var lines = new String(resource("SHA256SUMS"), StandardCharsets.US_ASCII).lines().toList();
        assertTrue(lines.size() > 30);
        for (var line : lines) {
            var fields = line.split(" {2}");
            var digest = MessageDigest.getInstance("SHA-256").digest(resource(fields[1]));
            assertEquals(fields[0], HexFormat.of().formatHex(digest), fields[1]);
        }
    }

    @Nested
    @DisplayName("QuickTime's own exports")
    class Exports {
        @ParameterizedTest(name = "quality {0}")
        @ValueSource(ints = {0, 256, 512, 768, 1023})
        @DisplayName("carry no box and decode close to QuickTime's drawing")
        void matchQuickTime(int quality) throws DataFormatException {
            var stream = MotionJpegTesting.demux(resource("export-" + quality + ".mov"));
            assertTrue(stream.track().codecConfig().isEmpty(), "QuickTime writes no table box");
            var drawn = inflated("export-" + quality + ".argb.z");
            var pictures = decode(stream);
            assertEquals(2, pictures.size());
            assertEquals(pictures.size() * WIDTH * HEIGHT * 4, drawn.length);
            for (var i = 0; i < pictures.size(); i++) {
                assertClose((YuvPicture) pictures.get(i), drawn, i * WIDTH * HEIGHT * 4, quality);
            }
        }

        // Recovers the samples from QuickTime's ARGB through the full-range BT.601 matrix; clipped pixels say nothing.
        // At fine tables QuickTime is within IDCT rounding of the accurate decoder. At coarse tables its decoder departs
        // from it in whole blocks, in luma and chroma, so only the mean is bounded there: a wrong table gives tens.
        private void assertClose(YuvPicture picture, byte[] argb, int base, int quality) {
            var fine = quality >= 768;
            var luma = new double[2];
            var chroma = new double[2];
            for (var row = 0; row < HEIGHT; row++) {
                for (var x = 0; x < WIDTH; x++) {
                    var at = base + (row * WIDTH + x) * 4;
                    var r = argb[at + 1] & 0xFF;
                    var g = argb[at + 2] & 0xFF;
                    var b = argb[at + 3] & 0xFF;
                    if (Math.min(r, Math.min(g, b)) == 0 || Math.max(r, Math.max(g, b)) == 255) {
                        continue;
                    }
                    var difference = Math.abs(0.299 * r + 0.587 * g + 0.114 * b - (picture.y()[row * picture.yStride() + x] & 0xFF));
                    luma[0] += difference;
                    luma[1]++;
                    assertTrue(!fine || difference <= 1.5, "luma differs by " + difference);
                }
                for (var x = 0; x + 1 < WIDTH; x += 2) {
                    var at = base + (row * WIDTH + x) * 4;
                    var clipped = false;
                    for (var k = 1; k <= 7; k += k == 3 ? 2 : 1) {
                        var v = argb[at + k] & 0xFF;
                        clipped |= v == 0 || v == 255;
                    }
                    if (clipped) {
                        continue;
                    }
                    var r = ((argb[at + 1] & 0xFF) + (argb[at + 5] & 0xFF)) / 2.0;
                    var g = ((argb[at + 2] & 0xFF) + (argb[at + 6] & 0xFF)) / 2.0;
                    var b = ((argb[at + 3] & 0xFF) + (argb[at + 7] & 0xFF)) / 2.0;
                    var blue = Math.abs(-0.168736 * r - 0.331264 * g + 0.5 * b + 128 - (picture.u()[row * picture.uvStride() + x / 2] & 0xFF));
                    var red = Math.abs(0.5 * r - 0.418688 * g - 0.081312 * b + 128 - (picture.v()[row * picture.uvStride() + x / 2] & 0xFF));
                    chroma[0] += blue + red;
                    chroma[1] += 2;
                    assertTrue(!fine || Math.max(blue, red) <= 1.5, "chroma differs by " + Math.max(blue, red));
                }
            }
            assertTrue(luma[1] > WIDTH * HEIGHT / 2.0, "too many clipped pixels");
            assertTrue(luma[0] / luma[1] <= (fine ? 0.5 : 1.0), "mean luma difference " + luma[0] / luma[1]);
            assertTrue(chroma[0] / chroma[1] <= (fine ? 0.5 : 1.5), "mean chroma difference " + chroma[0] / chroma[1]);
        }
    }

    @Nested
    @DisplayName("every shape of box QuickTime was shown")
    class Shapes {
        @ParameterizedTest
        @MethodSource("com.github.auties00.smalt.image.jpeg.MjpegTablesBoxTest#rows")
        @DisplayName("gives what QuickTime gives")
        void verdict(Row row) {
            var reference = decode(row.reference());
            if (row.verdict().equals("equal")) {
                assertSameFrames(reference, decode(row.file()), row.file());
            } else if (REJECTED.contains(row.file())) {
                assertEquals("different", row.verdict());
                assertThrows(MediaException.Malformed.class, () -> decode(row.file()), row.file());
            } else {
                assertEquals("different", row.verdict());
                List<Picture> pictures;
                try {
                    pictures = decode(row.file());
                } catch (MediaException.Malformed rejected) {
                    return;
                }
                assertFalse(Arrays.equals(MotionJpegTesting.planes(reference.getFirst()),
                        MotionJpegTesting.planes(pictures.getFirst())), row.file() + " decodes like its reference");
            }
        }

        @Test
        @DisplayName("custom Huffman tables decode close to QuickTime's drawing through their box")
        void customHuffman() throws DataFormatException {
            var drawn = inflated("custom.argb.z");
            var frame = (YuvPicture) decode("both-boxes.mov").getFirst();
            var total = 0L;
            var count = 0;
            for (var row = 0; row < HEIGHT; row++) {
                for (var x = 0; x < WIDTH; x++) {
                    var at = (row * WIDTH + x) * 4;
                    var luma = 0.299 * (drawn[at + 1] & 0xFF) + 0.587 * (drawn[at + 2] & 0xFF) + 0.114 * (drawn[at + 3] & 0xFF);
                    total += Math.round(Math.abs(luma - (frame.y()[row * frame.yStride() + x] & 0xFF)));
                    count++;
                }
            }
            assertTrue(total <= count, "mean luma difference " + (double) total / count);
        }

        @Test
        @DisplayName("the configuration holds the segments with their markers and leaves padding out")
        void configuration() {
            var boxed = MotionJpegTesting.demux(resource("both-boxes.mov")).track().codecConfig().orElseThrow();
            assertEquals((byte) 0xFF, boxed[0]);
            assertEquals((byte) 0xDB, boxed[1]);
            var size = ByteBuffer.wrap(boxed).getShort(2) & 0xFFFF;
            assertEquals((byte) 0xFF, boxed[2 + size]);
            assertEquals((byte) 0xC4, boxed[3 + size]);
            var padded = MotionJpegTesting.demux(resource("q-padded.mov")).track().codecConfig().orElseThrow();
            assertEquals(2 + (ByteBuffer.wrap(padded).getShort(2) & 0xFFFF), padded.length);
            assertTrue(MotionJpegTesting.demux(resource("q-length-past.mov")).track().codecConfig().isEmpty());
        }

        @Test
        @DisplayName("a packet's new configuration replaces the track's from that packet on")
        void newConfiguration() {
            var stream = MotionJpegTesting.demux(resource("tables-512.mov"));
            var expected = planes(decode(stream));
            var tables = stream.track().codecConfig().orElseThrow();
            var other = MotionJpegTesting.demux(resource("tables-0.mov")).track().codecConfig().orElseThrow();
            var bare = stream.track().toBuilder().codecConfig(null).build();
            var first = stream.packets().getFirst();
            assertThrows(MediaException.Malformed.class, () -> new MjpegDecoder(bare).decode(first, ignored -> { }));

            var decoder = new MjpegDecoder(stream.track().toBuilder().codecConfig(other).build());
            var actual = new ArrayList<byte[]>();
            for (var i = 0; i < stream.packets().size(); i++) {
                var packet = stream.packets().get(i);
                if (i == 0) {
                    packet = packet.withSideData(PacketSideData.builder().newCodecConfig(tables).build());
                }
                decoder.decode(packet, frame -> actual.add(MotionJpegTesting.planes(frame.picture())));
            }
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i), "frame " + i);
            }

            var reverted = new MjpegDecoder(stream.track());
            var cleared = first.withSideData(PacketSideData.builder().newCodecConfig(new byte[0]).build());
            assertThrows(MediaException.Malformed.class, () -> reverted.decode(cleared, ignored -> { }));
        }

        @Test
        @DisplayName("a configuration of anything else, from another container, is ignored")
        void foreignConfiguration() {
            var stream = MotionJpegTesting.demux(resource("export-512.mov"));
            var expected = planes(decode(stream));
            for (var config : List.of(new byte[]{1, 2, 3}, new byte[]{(byte) 0xFF, (byte) 0xDB, 0, 100, 0, 0},
                    new byte[]{(byte) 0xFF, (byte) 0xC0, 0, 2}, new byte[]{(byte) 0xFF}, new byte[0])) {
                var track = stream.track().toBuilder().codecConfig(config).build();
                var actual = planes(decode(new MotionJpegTesting.Stream(track, stream.packets())));
                for (var i = 0; i < expected.size(); i++) {
                    assertArrayEquals(expected.get(i), actual.get(i));
                }
            }
        }
    }

    @Nested
    @DisplayName("a stream copy")
    class Copy {
        @ParameterizedTest
        @ValueSource(strings = {"tables-512.mov", "both-boxes.mov", "a-both-boxes.mov"})
        @DisplayName("keeps the boxes, so that the frames still decode")
        void keepsBoxes(String name) {
            var stream = MotionJpegTesting.demux(resource(name));
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.quickTime())) {
                muxer.addTrack(stream.track());
                for (var packet : stream.packets()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var file = sink.toByteArray();
            Validators.assertValid(name, file);
            var copy = MotionJpegTesting.demux(file);
            assertArrayEquals(stream.track().codecConfig().orElseThrow(), copy.track().codecConfig().orElseThrow());
            assertSameFrames(decode(stream), decode(copy), name);
        }

        @Test
        @DisplayName("writes no box for a track without tables")
        void noBoxes() {
            var stream = MotionJpegTesting.demux(resource("export-512.mov"));
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.quickTime())) {
                muxer.addTrack(stream.track());
                for (var packet : stream.packets()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var text = new String(sink.toByteArray(), StandardCharsets.ISO_8859_1);
            assertFalse(text.contains("mjqt") || text.contains("mjht"));
        }
    }

    @Nested
    @DisplayName("damaged input")
    class Damage {
        private static void onlyMediaExceptions(Executable executable) {
            try {
                executable.execute();
            } catch (MediaException expected) {
                // A rejection is a valid outcome.
            } catch (Throwable other) {
                fail(other);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"both-boxes.mov", "a-both-boxes.mov", "q-wide.mov"})
        @DisplayName("truncated files fail only with media exceptions")
        void truncated(String name) {
            var file = resource(name);
            for (var length = 0; length < file.length; length += 13) {
                var cut = Arrays.copyOf(file, length);
                onlyMediaExceptions(() -> decode(MotionJpegTesting.demux(cut)));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"both-boxes.mov", "a-both-boxes.mov", "q-wide.mov"})
        @DisplayName("corrupted configurations and frames fail only with media exceptions")
        void corrupted(String name) {
            var random = new Random(name.hashCode());
            var stream = MotionJpegTesting.demux(resource(name));
            var config = stream.track().codecConfig().orElseThrow();
            var frame = stream.packets().getFirst();
            for (var round = 0; round < 300; round++) {
                var mutated = config.clone();
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                }
                var track = stream.track().toBuilder().codecConfig(mutated).build();
                onlyMediaExceptions(() -> new MjpegDecoder(track).decode(frame, ignored -> { }));
            }
        }
    }
}
