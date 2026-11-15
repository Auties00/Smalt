package com.github.auties00.smalt.video.qtrle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.codec.IntermediateFrames;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.intra.IntraTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes QuickTime Animation streams made by ffmpeg's encoder at every depth it writes and compares every pixel with
 * ffmpeg's decode (the codec is lossless, so the comparison is exact), checks Smalt's encoder the other way through
 * ffmpeg's decoder, decodes hand-built streams of the index depths ffmpeg does not write, and feeds damaged frames.
 */
@DisplayName("QuickTime Animation against ffmpeg")
class QtrleDifferentialTest {
    static final Tolerance EXACT = Tolerance.exact();

    static final int WIDTH = 173;

    static final int HEIGHT = 61;

    static java.nio.file.Path sample(String pixelFormat, String container) {
        // the moving circle of testsrc2 over a still pattern gives inter frames with skipped pixels and rows
        // testsrc2 renders even sizes only, so the odd size is cropped from a larger picture; ffmpeg codes gray
        // only at widths that are multiples of 4
        return Fixtures.ffmpeg(container, List.of("-f", "lavfi", "-i", "testsrc2=size=176x64:rate=25"),
                List.of("-vf", "format=rgb24,crop=" + width(pixelFormat) + ":" + HEIGHT + ":1:1", "-frames:v", "7",
                        "-c:v", "qtrle", "-g", "3", "-pix_fmt", pixelFormat));
    }

    static int width(String pixelFormat) {
        return pixelFormat.equals("gray") ? WIDTH - 1 : WIDTH;
    }

    /**
     * Returns ffmpeg's decode as raw video of a pixel format; gray as the red samples of its RGB decode, since ffmpeg
     * decodes the gray depths to a palette and rounds when it converts a palette to gray.
     */
    static byte[] ffmpegDecode(java.nio.file.Path file, String pixelFormat) {
        if (!pixelFormat.equals("gray")) {
            return IntraTesting.ffmpegDecode(file, pixelFormat, 0);
        }
        var rgb = IntraTesting.ffmpegDecode(file, "rgb24", 0);
        var gray = new byte[rgb.length / 3];
        for (var i = 0; i < gray.length; i++) {
            gray[i] = rgb[3 * i];
        }
        return gray;
    }

    /** Lays the frames out as ffmpeg's rgba, or as gray for depth 40 and rgb555be for depth 16. */
    static byte[] raw(RgbaPicture picture, String pixelFormat) {
        var out = new ByteArrayOutputStream();
        var pixels = picture.pixels();
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                var at = y * picture.stride() + x * 4;
                switch (pixelFormat) {
                    case "gray" -> out.write(pixels[at]);
                    case "rgb555be" -> {
                        var value = (pixels[at] & 0xF8) << 7 | (pixels[at + 1] & 0xF8) << 2 | (pixels[at + 2] & 0xFF) >>> 3;
                        out.write(value >>> 8);
                        out.write(value);
                    }
                    case "rgb24" -> out.write(pixels, at, 3);
                    default -> out.write(pixels, at, 4);
                }
            }
        }
        return out.toByteArray();
    }

    @ParameterizedTest(name = "{0} in {1}")
    // ffmpeg's Matroska muxer drops the depth of a V_QUICKTIME track, so only its QuickTime files decode
    @CsvSource({"rgb24,mov,24", "argb,mov,32", "rgb555be,mov,16", "gray,mov,40"})
    @DisplayName("decodes ffmpeg's streams pixel for pixel as ffmpeg does")
    void decodesFfmpeg(String pixelFormat, String container, int depth) {
        var file = sample(pixelFormat, container);
        var stream = IntraTesting.demux(file);
        assertEquals(Codec.QTRLE, stream.track().codec());
        assertEquals(depth, IntermediateFrames.qtrleDepth(stream.track().codecConfig().orElse(null)));
        var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.defaults());
        assertInstanceOf(QtrleDecoder.class, decoder);
        var ours = new ByteArrayOutputStream();
        var keys = 0;
        for (var packet : stream.packets()) {
            keys += packet.keyframe() ? 1 : 0;
            decoder.decode(packet, frame -> ours.writeBytes(raw((RgbaPicture) frame.picture(),
                    pixelFormat.equals("argb") ? "rgba" : pixelFormat)));
        }
        assertEquals(7, stream.packets().size());
        assertTrue(keys >= 2 && keys < 7, "key frames " + keys);
        var expected = ffmpegDecode(file, pixelFormat.equals("argb") ? "rgba" : pixelFormat);
        EXACT.check(pixelFormat + " " + container, Tolerance.Difference.of(expected, ours.toByteArray()));
    }

    static List<RgbaPicture> sources(boolean alpha) {
        var random = new Random(5);
        var pictures = new ArrayList<RgbaPicture>();
        var picture = RgbaPicture.allocate(WIDTH, HEIGHT, alpha);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i % 4 == 3 && !alpha ? 255 : (i / 4 % WIDTH) * 3 + (i / 4 / WIDTH) * 5);
        }
        for (var frame = 0; frame < 6; frame++) {
            var next = RgbaPicture.of(WIDTH, HEIGHT, WIDTH * 4, pixels.clone(), alpha);
            // change a block, a run of one color and a few scattered pixels; frame 3 repeats frame 2
            if (frame != 3) {
                for (var y = 10 + frame * 3; y < 20 + frame * 3; y++) {
                    for (var x = 20; x < 60 + frame * 7; x++) {
                        var at = y * WIDTH * 4 + x * 4;
                        next.pixels()[at] = (byte) (frame * 40);
                        next.pixels()[at + 1] = (byte) (x < 40 ? 200 : random.nextInt(256));
                        next.pixels()[at + 2] = 7;
                        if (alpha) {
                            next.pixels()[at + 3] = (byte) random.nextInt(256);
                        }
                    }
                }
                next.pixels()[(HEIGHT - 1) * WIDTH * 4 + (WIDTH - 1) * 4] = (byte) frame;
            }
            pixels = next.pixels();
            pictures.add(next);
        }
        return pictures;
    }

    static List<Packet> encode(QtrleEncoder encoder, List<RgbaPicture> pictures) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < pictures.size(); i++) {
            packets.add(encoder.encodeFrame(new VideoFrame(pictures.get(i), i * 3000L, 3000L, true))
                    .withTrackId(1));
        }
        return packets;
    }

    static VideoTrack track(QtrleEncoder encoder) {
        return VideoTrack.builder().id(1).codec(Codec.QTRLE).timeBase(encoder.timeBase()).width(WIDTH).height(HEIGHT)
                .frameRate(new Rational(30, 1)).codecConfig(encoder.codecConfig().orElseThrow()).build();
    }

    @ParameterizedTest(name = "depth {0} in {1}")
    @CsvSource({"24,mov", "32,mov", "16,mov", "40,mov", "24,mkv", "32,mkv"})
    @DisplayName("writes streams ffmpeg decodes to the source pixels, which Smalt's decoder returns too")
    void encodes(int depth, String container) throws IOException {
        var alpha = depth == 32;
        var pictures = sources(alpha);
        var encoder = new QtrleEncoder(WIDTH, HEIGHT, QtrleEncoderOptions.builder().depth(depth).keyframeInterval(4)
                .build());
        var packets = encode(encoder, pictures);
        assertTrue(packets.get(0).keyframe());
        assertFalse(packets.get(1).keyframe());
        assertEquals(4, packets.get(3).data().length, "a repeated frame is a 4-byte packet");
        assertTrue(packets.get(4).keyframe());
        assertTrue(packets.get(1).data().length < packets.get(0).data().length / 2,
                "an inter frame codes only the changed rows");
        var format = switch (depth) {
            case 16 -> "rgb555be";
            case 40 -> "gray";
            case 32 -> "rgba";
            default -> "rgb24";
        };
        var expected = new ByteArrayOutputStream();
        for (var picture : pictures) {
            expected.writeBytes(raw(depth == 40 ? gray(picture) : picture, format));
        }
        var decoder = new QtrleDecoder(track(encoder), VideoDecoderOptions.defaults());
        var ours = new ByteArrayOutputStream();
        for (var packet : packets) {
            decoder.decode(packet, frame -> ours.writeBytes(raw((RgbaPicture) frame.picture(), format)));
        }
        EXACT.check("Smalt's decode", Tolerance.Difference.of(expected.toByteArray(), ours.toByteArray()));
        var sink = MediaSink.memory();
        if (container.equals("mov")) {
            var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.QUICKTIME).build());
            muxer.addTrack(track(encoder));
            packets.forEach(muxer::writePacket);
            muxer.finish();
        } else {
            var muxer = new MatroskaMuxer(sink);
            muxer.addTrack(track(encoder));
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        var file = Scratch.directory("qtrle").resolve("qtrle-" + depth + "." + container);
        Files.write(file, sink.toByteArray());
        EXACT.check("ffmpeg's decode", Tolerance.Difference.of(expected.toByteArray(),
                ffmpegDecode(file, format)));
        var stream = IntraTesting.demux(file);
        assertEquals(Codec.QTRLE, stream.track().codec());
        assertEquals(depth, IntermediateFrames.qtrleDepth(stream.track().codecConfig().orElse(null)));
        for (var i = 0; i < packets.size(); i++) {
            assertArrayEquals(packets.get(i).data(), stream.packets().get(i).data(), "packet " + i);
        }
    }

    /** Returns the gray picture depth 40 codes for a color picture. */
    static RgbaPicture gray(RgbaPicture picture) {
        var copy = picture.pixels().clone();
        for (var at = 0; at < copy.length; at += 4) {
            var luma = (77 * (copy[at] & 0xFF) + 150 * (copy[at + 1] & 0xFF) + 29 * (copy[at + 2] & 0xFF) + 128) >>> 8;
            copy[at] = (byte) luma;
            copy[at + 1] = (byte) luma;
            copy[at + 2] = (byte) luma;
            copy[at + 3] = (byte) 255;
        }
        return RgbaPicture.of(picture.width(), picture.height(), picture.stride(), copy, false);
    }

    @Test
    @DisplayName("is reached through VideoEncoders, as ARGB")
    void registry() {
        var encoder = VideoEncoders.create(Codec.QTRLE, VideoEncoderOptions.builder().size(WIDTH, HEIGHT)
                .frameRate(new Rational(30, 1)).bitrate(10_000_000).build());
        assertInstanceOf(QtrleEncoder.class, encoder);
        assertEquals(32, IntermediateFrames.qtrleDepth(encoder.codecConfig().orElseThrow()));
    }

    @Nested
    @DisplayName("index depths")
    class Indexed {
        static VideoTrack track(int depth, byte[] colorTable, int width, int height) {
            return VideoTrack.builder().id(1).codec(Codec.QTRLE).timeBase(new Rational(1, 600)).width(width)
                    .height(height).codecConfig(IntermediateFrames.qtrleConfig(depth, colorTable)).build();
        }

        /**
         * Builds a frame of one row per entry, each row a literal run of the given units; 2-byte units are laid out
         * as 1-bit frames are, with a new-row skip byte before each row and a zero code at the end.
         */
        static byte[] frame(int unitBytes, byte[][] rows) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{0, 0, 0, 0, 0, 0});
            for (var row : rows) {
                out.write(unitBytes == 2 ? 0x80 : 1);
                out.write(row.length / unitBytes);
                out.writeBytes(row);
                if (unitBytes != 2) {
                    out.write(0xFF);
                }
            }
            out.write(unitBytes == 2 ? 0x80 : 0);
            if (unitBytes == 2) {
                out.write(0);
            }
            var data = out.toByteArray();
            data[3] = (byte) data.length;
            return data;
        }

        static byte[] table(int... rgb) {
            var table = new byte[8 + 8 * rgb.length];
            table[7] = (byte) (rgb.length - 1);
            for (var i = 0; i < rgb.length; i++) {
                table[8 + 8 * i + 1] = (byte) i;
                table[8 + 8 * i + 2] = (byte) (rgb[i] >>> 16);
                table[8 + 8 * i + 4] = (byte) (rgb[i] >>> 8);
                table[8 + 8 * i + 6] = (byte) rgb[i];
            }
            return table;
        }

        static int rgb(RgbaPicture picture, int x, int y) {
            var at = y * picture.stride() + x * 4;
            return (picture.pixels()[at] & 0xFF) << 16 | (picture.pixels()[at + 1] & 0xFF) << 8
                    | picture.pixels()[at + 2] & 0xFF;
        }

        @ParameterizedTest(name = "depth {0}")
        @ValueSource(ints = {1, 2, 4, 8})
        @DisplayName("maps the indices of every depth through the color table, most significant bits first")
        void colorTable(int depth) {
            var entries = 1 << depth;
            var colors = new int[entries];
            for (var i = 0; i < entries; i++) {
                colors[i] = i * 0x010307 + 0x102030;
            }
            var unitBytes = depth == 1 ? 2 : 4;
            var unitPixels = unitBytes * 8 / depth;
            var width = unitPixels * 2 - 3;
            var units = new byte[2 * unitBytes];
            var random = new Random(depth);
            random.nextBytes(units);
            var decoder = new QtrleDecoder(track(depth, table(colors), width, 1), VideoDecoderOptions.defaults());
            var picture = decoder.decodeFrame(frame(unitBytes, new byte[][]{units}), 0,
                    frame(unitBytes, new byte[][]{units}).length);
            for (var x = 0; x < width; x++) {
                var bit = x * depth;
                var index = (units[bit / 8] & 0xFF) >>> (8 - depth - bit % 8) & (entries - 1);
                assertEquals(colors[index] & 0xFFFFFF, rgb(picture, x, 0), "pixel " + x);
            }
        }

        @Test
        @DisplayName("uses the default Macintosh table without a color table, white first")
        void defaultTable() {
            var decoder = new QtrleDecoder(track(1, null, 16, 1), VideoDecoderOptions.defaults());
            var data = frame(2, new byte[][]{{(byte) 0x80, 0x01}});
            var picture = decoder.decodeFrame(data, 0, data.length);
            assertEquals(0x000000, rgb(picture, 0, 0));
            assertEquals(0xFFFFFF, rgb(picture, 1, 0));
            assertEquals(0x000000, rgb(picture, 15, 0));
        }

        @ParameterizedTest(name = "depth {0}")
        @ValueSource(ints = {1, 2, 4, 8})
        @DisplayName("carries the depth and color table through QuickTime and Matroska files, decoded alike by ffmpeg")
        void containers(int depth) throws IOException {
            var entries = 1 << depth;
            var colors = new int[entries];
            for (var i = 0; i < entries; i++) {
                colors[i] = (i * 0x0B1D37 + 0x30A050) & 0xFFFFFF;
            }
            // ffmpeg decodes 1-bit indices as white and black whatever the table says, so depth 1 keeps the default
            var table = depth == 1 ? null : table(colors);

            var unitBytes = depth == 1 ? 2 : 4;
            var unitPixels = unitBytes * 8 / depth;
            var width = unitPixels * 3 - 1;
            var height = 3;
            var random = new Random(depth * 31L);
            var rows = new byte[height][3 * unitBytes];
            for (var row : rows) {
                random.nextBytes(row);
            }
            var data = frame(unitBytes, rows);
            data[0] = (byte) (data.length >>> 24);
            data[1] = (byte) (data.length >>> 16);
            data[2] = (byte) (data.length >>> 8);
            data[3] = (byte) data.length;
            var track = track(depth, table, width, height).toBuilder().frameRate(new Rational(25, 1)).build();
            var expected = new QtrleDecoder(track, VideoDecoderOptions.defaults()).decodeFrame(data, 0, data.length);
            for (var container : new String[]{"mov", "mkv"}) {
                var sink = MediaSink.memory();
                var packet = new Packet(1, data, 0, 0, 24, true);
                if (container.equals("mov")) {
                    var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.QUICKTIME)
                            .build());
                    muxer.addTrack(track);
                    muxer.writePacket(packet);
                    muxer.finish();
                } else {
                    var muxer = new MatroskaMuxer(sink);
                    muxer.addTrack(track);
                    muxer.writePacket(packet);
                    muxer.finish();
                }
                var file = Scratch.directory("qtrle-indexed").resolve("depth-" + depth + "." + container);
                Files.write(file, sink.toByteArray());
                var stream = IntraTesting.demux(file);
                var config = stream.track().codecConfig().orElse(null);
                assertEquals(Codec.QTRLE, stream.track().codec(), container);
                assertEquals(depth, IntermediateFrames.qtrleDepth(config), container);
                assertArrayEquals(IntermediateFrames.qtrlePalette(track.codecConfig().orElseThrow()),
                        IntermediateFrames.qtrlePalette(config), container + " palette");
                assertArrayEquals(data, stream.packets().getFirst().data(), container);
                var decoded = new QtrleDecoder(stream.track(), VideoDecoderOptions.defaults())
                        .decodeFrame(data, 0, data.length);
                assertArrayEquals(expected.pixels(), decoded.pixels(), container + " decode");
                if (container.equals("mov")) {
                    EXACT.check("ffmpeg's decode of depth " + depth, Tolerance.Difference.of(raw(expected, "rgb24"),
                            ffmpegDecode(file, "rgb24")));
                }
            }
        }

        @Test
        @DisplayName("reads 1-bit frames with a skip byte before every code, as QuickTime and ffmpeg lay them out")
        void bitmapSkips() {
            var decoder = new QtrleDecoder(track(1, null, 64, 2), VideoDecoderOptions.defaults());
            // every row white first: a 4-unit run of zero units on each row
            byte[] white = {0, 0, 0, 20, 0, 0, (byte) 0x80, (byte) 0xFC, 0, 0, (byte) 0x80, (byte) 0xFC, 0, 0,
                    (byte) 0x80, 0};
            white[3] = (byte) white.length;
            decoder.decodeFrame(white, 0, white.length);
            // row 0: a black unit, one unit skipped, two repeated F00F units; row 1 starts one unit in: AAAA
            byte[] coded = {0, 0, 0, 0, 0, 0, (byte) 0x80, 1, (byte) 0xFF, (byte) 0xFF, 1, (byte) 0xFE, (byte) 0xF0,
                    0x0F, (byte) 0x81, 1, (byte) 0xAA, (byte) 0xAA, (byte) 0x80, 0};
            coded[3] = (byte) coded.length;
            var picture = decoder.decodeFrame(coded, 0, coded.length);
            for (var x = 0; x < 64; x++) {
                var row0 = x < 16 || x >= 32 && ((x & 15) < 4 || (x & 15) >= 12);
                var row1 = x >= 16 && x < 32 && (x & 1) == 0;
                assertEquals(row0 ? 0 : 0xFFFFFF, rgb(picture, x, 0), "row 0 pixel " + x);
                assertEquals(row1 ? 0 : 0xFFFFFF, rgb(picture, x, 1), "row 1 pixel " + x);
            }
            byte[] minusOne = {0, 0, 0, 10, 0, 0, (byte) 0x80, (byte) 0xFF, 0, 0};
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(minusOne, 0, minusOne.length));
            byte[] twoRows = {0, 0, 0, 0, 0, 0, (byte) 0x80, 1, 0, 0, (byte) 0x80, 1, 0, 0, (byte) 0x80, 1, 0, 0};
            twoRows[3] = (byte) twoRows.length;
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(twoRows, 0, twoRows.length));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Animation-Monochrome.mov", "Animation-4Greys.mov", "Animation-16Greys.mov"})
        @DisplayName("decodes QuickTime's own 1-bit, 2-bit gray and 4-bit gray files pixel for pixel as ffmpeg does")
        void apple(String name) {
            var file = com.github.auties00.smalt.testkit.Conformance.fetchListed("fate-qtrle-" + name);
            var stream = IntraTesting.demux(file);
            assertEquals(Codec.QTRLE, stream.track().codec());
            var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.defaults());
            var ours = new ByteArrayOutputStream();
            for (var packet : stream.packets()) {
                decoder.decode(packet, frame -> ours.writeBytes(raw((RgbaPicture) frame.picture(), "rgb24")));
            }
            EXACT.check(name, Tolerance.Difference.of(ffmpegDecode(file, "rgb24"), ours.toByteArray()));
        }

        @ParameterizedTest(name = "depth {0}")
        @ValueSource(ints = {33, 34, 36, 40})
        @DisplayName("ramps gray indices from white to black")
        void gray(int depth) {
            var bits = depth - 32;
            var unitBytes = bits == 1 ? 2 : 4;
            var units = new byte[unitBytes];
            units[0] = (byte) 0xFF;
            var decoder = new QtrleDecoder(track(depth, null, unitBytes * 8 / bits, 1), VideoDecoderOptions.defaults());
            var data = frame(unitBytes, new byte[][]{units});
            var picture = decoder.decodeFrame(data, 0, data.length);
            assertEquals(0x000000, rgb(picture, 0, 0));
            assertEquals(0xFFFFFF, rgb(picture, unitBytes * 8 / bits - 1, 0));
        }
    }

    @Nested
    @DisplayName("damaged input")
    class Damaged {
        @Test
        @DisplayName("raises only Malformed for every truncation and byte flip of ffmpeg's frames")
        void truncations() {
            var stream = IntraTesting.demux(sample("rgb24", "mov"));
            var random = new Random(11);
            for (var packet : stream.packets()) {
                var data = packet.data();
                for (var length = 0; length < data.length; length += Math.max(1, data.length / 97)) {
                    var decoder = new QtrleDecoder(stream.track(), VideoDecoderOptions.defaults());
                    var cut = Arrays.copyOf(data, length);
                    try {
                        decoder.decodeFrame(cut, 0, cut.length);
                    } catch (MediaException.Malformed expected) {
                        // a truncated frame may fail; anything but Malformed fails the test
                    }
                }
                for (var flip = 0; flip < 200; flip++) {
                    var decoder = new QtrleDecoder(stream.track(), VideoDecoderOptions.defaults());
                    var damaged = data.clone();
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                    try {
                        decoder.decodeFrame(damaged, 0, damaged.length);
                    } catch (MediaException.Malformed expected) {
                        // a damaged frame may fail; anything but Malformed fails the test
                    }
                }
            }
        }

        @Test
        @DisplayName("rejects a row range past the picture and a run past the row")
        void ranges() {
            var track = Indexed.track(24, null, 4, 2);
            var decoder = new QtrleDecoder(track, VideoDecoderOptions.defaults());
            byte[] rows = {0, 0, 0, 22, 0, 8, 0, 1, 0, 0, 0, 2, 0, 0, 1, 1, 0, 0, 0, (byte) 0xFF, 0, 0};
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(rows, 0, rows.length));
            byte[] run = {0, 0, 0, 13, 0, 0, 1, (byte) 0xF0, 1, 2, 3, (byte) 0xFF, 0};
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(run, 0, run.length));
        }

        @Test
        @DisplayName("repeats the previous picture for a packet shorter than 8 bytes")
        void repeat() {
            var encoder = new QtrleEncoder(WIDTH, HEIGHT);
            var pictures = sources(false);
            var decoder = new QtrleDecoder(track(encoder), VideoDecoderOptions.defaults());
            var data = encoder.encodeFrame(new VideoFrame(pictures.getFirst(), 0, 3000, true)).data();
            var first = decoder.decodeFrame(data, 0, data.length);
            var again = decoder.decodeFrame(new byte[]{0, 0, 0, 4}, 0, 4);
            assertArrayEquals(first.pixels(), again.pixels());
        }

        @Test
        @DisplayName("rejects a depth the format does not define")
        void depth() {
            assertThrows(MediaException.Unsupported.class, () -> new QtrleDecoder(Indexed.track(12, null, 4, 4),
                    VideoDecoderOptions.defaults()));
        }
    }
}
