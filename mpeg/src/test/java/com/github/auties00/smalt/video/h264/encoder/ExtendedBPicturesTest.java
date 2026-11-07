package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.H264StreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.assertSame;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.decodeFfmpeg;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.decodeSmalt;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.frames;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.planes;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.psnr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the B pictures of the extended engine: every bit depth, chroma format and field structure, lossless
 * coding, the packet order and timestamps, and the options that rule B pictures out. With the deblocking filter
 * disabled (a test hook) the encoder's own reconstruction of every frame, B frames included, must equal what
 * Smalt and ffmpeg decode, which checks the spatial direct derivation, the co-located motion, bi-prediction and
 * the reference lists the encoder assumes against two decoders; macroblock type counts check that each B tool
 * was exercised.
 */
@DisplayName("H264Encoder extended formats with B pictures")
class ExtendedBPicturesTest {
    /** The {@code typeCounts} indices of the B macroblock types. */
    private static final int B_SKIP = 6;

    private static final int B_DIRECT = 7;

    private static final int B_L0 = 8;

    private static final int B_L1 = 9;

    private static final int B_BI = 10;

    /** One coded sequence, with the reconstructions and decoded frames in display order. */
    record Run(List<Packet> packets, List<short[][]> encoderRecon, List<short[][]> decoded, long[] types,
               long[] zeroed) {
        byte[] stream() {
            var out = new ByteArrayOutputStream();
            packets.forEach(packet -> out.writeBytes(packet.data()));
            return out.toByteArray();
        }
    }

    static H264EncoderOptions.Builder options(H264Profile profile, ChromaFormat format, int depth, int bFrames) {
        return ExtendedFormatsTest.options(profile, format, depth).lookahead(bFrames + 1).bFrames(bFrames)
                .keyframeInterval(12);
    }

    static Run run(int width, int height, H264EncoderOptions options, List<Picture> pictures, boolean deblocking) {
        return run(width, height, options, pictures, deblocking, false);
    }

    static Run run(int width, int height, H264EncoderOptions options, List<Picture> pictures, boolean deblocking,
                   boolean forceDirect) {
        var packets = new ArrayList<Packet>();
        var recon = new short[pictures.size()][][];
        var decoded = new short[pictures.size()][][];
        long[] types;
        long[] zeroed;
        try (var encoder = new H264Encoder(width, height, options)) {
            encoder.wide().setDeblocking(deblocking);
            encoder.wide().setForcedDirect(forceDirect);
            Consumer<Packet> sink = packet -> {
                packets.add(packet);
                var index = (int) encoder.wide().lastDisplayIndex();
                recon[index] = encoder.wide().lastEncoderReconstruction();
                decoded[index] = encoder.wide().lastReconstruction();
            };
            long pts = 0;
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts, 3000, false), sink);
                pts += 3000;
            }
            encoder.flush(sink);
            types = encoder.wide().typeCounts();
            zeroed = encoder.wide().zeroedDirectQuadrants();
        }
        return new Run(packets, Arrays.asList(recon), Arrays.asList(decoded), types, zeroed);
    }

    /** Returns the {@code slice_type} of the first slice of each packet, modulo 5: 0 P, 1 B, 2 I. */
    static List<Integer> sliceTypes(List<Packet> packets) {
        var types = new ArrayList<Integer>();
        for (var packet : packets) {
            var data = packet.data();
            var type = -1;
            for (var i = 0; i + 3 < data.length && type < 0; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                    var nalType = data[i + 3] & 0x1F;
                    if (nalType == 1 || nalType == 5) {
                        var reader = new BitReader(data, i + 4);
                        reader.ue();
                        type = reader.ue() % 5;
                    }
                }
            }
            types.add(type);
        }
        return types;
    }

    /** Reads Exp-Golomb codes from a NAL unit payload, skipping emulation prevention bytes. */
    static final class BitReader {
        private final byte[] data;

        private int position;

        private int bit;

        private int zeros;

        BitReader(byte[] data, int start) {
            this.data = data;
            this.position = start;
        }

        int bit() {
            if (bit == 0 && zeros >= 2 && data[position] == 3) {
                position++;
                zeros = 0;
            }
            var value = (data[position] >> (7 - bit)) & 1;
            if (++bit == 8) {
                zeros = data[position] == 0 ? zeros + 1 : 0;
                bit = 0;
                position++;
            }
            return value;
        }

        int ue() {
            var leading = 0;
            while (bit() == 0) {
                leading++;
            }
            var value = 0;
            for (var i = 0; i < leading; i++) {
                value = (value << 1) | bit();
            }
            return (1 << leading) - 1 + value;
        }
    }

    /** Checks the coding order, the timestamps and the flags of the packets of a stream with B pictures. */
    static void assertReordered(Run run, int frames) {
        var packets = run.packets();
        assertEquals(frames, packets.size(), "one packet per frame");
        var types = sliceTypes(packets);
        var pts = new HashSet<Long>();
        var previousDts = Long.MIN_VALUE;
        var bCount = 0;
        var reordered = false;
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            pts.add(packet.pts());
            assertTrue(packet.dts() <= packet.pts(), "packet " + i + " decodes before it is presented");
            assertTrue(packet.dts() > previousDts, "packet " + i + " has an increasing decoding timestamp");
            previousDts = packet.dts();
            var b = types.get(i) == 1;
            if (b) {
                bCount++;
                assertTrue(packet.flags().contains(PacketFlag.DISCARDABLE), "a B packet is discardable");
            }
            if (i > 0 && packet.pts() < packets.get(i - 1).pts()) {
                reordered = true;
            }
        }
        assertEquals(frames, pts.size(), "every presentation timestamp is emitted once");
        assertTrue(bCount > 0, "the stream codes B pictures: " + types);
        assertTrue(reordered, "packets leave in coding order");
        assertTrue(packets.getFirst().keyframe(), "the stream starts with a key frame");
    }

    /** Checks that the B tools were all exercised over a stream. */
    static void assertBTools(long[] types, boolean all) {
        var direct = types[B_SKIP] + types[B_DIRECT];
        var predicted = types[B_L0] + types[B_L1] + types[B_BI];
        assertTrue(direct > 0, "direct or skipped B macroblocks: " + Arrays.toString(types));
        assertTrue(predicted > 0, "B macroblocks with vectors: " + Arrays.toString(types));
        if (all) {
            for (var type = B_SKIP; type <= B_BI; type++) {
                assertTrue(types[type] > 0, "macroblock type " + type + ": " + Arrays.toString(types));
            }
        }
    }

    /**
     * Builds frames with a background panning by a fraction of a sample per frame, a square moving the other
     * way and an area that changes only every third frame, so that B macroblocks find their best prediction
     * in the past, the future, both, or the direct motion.
     */
    static List<Picture> motion(int width, int height, ChromaFormat format, int depth, int count, long seed,
                                boolean interlaced, double noise) {
        var list = new ArrayList<Picture>();
        var max = (1 << depth) - 1;
        for (var f = 0; f < count; f++) {
            var random = new Random(seed * 131 + f);
            var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                var t = interlaced && (y & 1) == 1 ? f + 0.5 : f;
                for (var x = 0; x < width; x++) {
                    var u = x + 0.75 * t;
                    var value = 0.45 + 0.2 * Math.sin(u * 0.21) * Math.cos(y * 0.13) + 0.1 * Math.sin((u + y) * 0.05);
                    if (Math.abs(x - width * 0.7 + 1.5 * t) < 10 && Math.abs(y - height / 2.0) < 10) {
                        value = 0.2 + 0.05 * Math.sin(x * 0.9 + y * 0.7);
                    }
                    if (x < 16 && y < 16) {
                        value = 0.6 + 0.2 * Math.sin((x + y + 7 * (f / 3)) * 0.4);
                    }
                    value += random.nextGaussian() * noise;
                    picture.y()[y * picture.yStride() + x] = (short) Math.clamp(Math.round(value * max), 0, max);
                }
            }
            if (format != ChromaFormat.MONOCHROME) {
                var sx = (double) width / picture.chromaWidth();
                for (var y = 0; y < picture.chromaHeight(); y++) {
                    for (var x = 0; x < picture.chromaWidth(); x++) {
                        var u = x * sx + 0.75 * f;
                        var cb = 0.5 + 0.15 * Math.sin(u * 0.11);
                        var cr = 0.5 + 0.15 * Math.cos(y * 0.09 + u * 0.03);
                        picture.u()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(cb * max), 0, max);
                        picture.v()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(cr * max), 0, max);
                    }
                }
            }
            list.add(picture);
        }
        return list;
    }

    static Stream<Arguments> formats() {
        return ExtendedFormatsTest.formats();
    }

    @Nested
    @DisplayName("progressive frames")
    class Progressive {
        @ParameterizedTest(name = "{0} {1} {2}-bit")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedBPicturesTest#formats")
        @DisplayName("without deblocking, Smalt and ffmpeg decode exactly the encoder's reconstruction of every frame")
        void reconstruction(H264Profile profile, ChromaFormat format, int depth) {
            var width = 96;
            var height = 64;
            var options = options(profile, format, depth, 3).build();
            var run = run(width, height, options, motion(width, height, format, depth, 13, depth, false, 0.004),
                    false);
            assertReordered(run, 13);
            assertBTools(run.types(), false);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.encoderRecon(), smalt, "Smalt");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(run.encoderRecon(), decodeFfmpeg("extb-" + profile + "-" + format + "-" + depth,
                        run.stream(), format, depth, width, height), "ffmpeg");
            }
        }

        @ParameterizedTest(name = "{0} {1} {2}-bit")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedBPicturesTest#formats")
        @DisplayName("with deblocking, Smalt and ffmpeg agree and the pictures stay close to the source")
        void deblocked(H264Profile profile, ChromaFormat format, int depth) {
            var width = 96;
            var height = 64;
            var options = options(profile, format, depth, 2).quality(20).build();
            var sources = motion(width, height, format, depth, 10, 5, false, 0.006);
            var run = run(width, height, options, sources, true);
            assertReordered(run, 10);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.decoded(), smalt, "Smalt");
            for (var i = 0; i < smalt.size(); i++) {
                var quality = psnr(sources.get(i), smalt.get(i)[0], depth);
                assertTrue(quality > 36, "frame " + i + " luma PSNR " + quality);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(smalt, decodeFfmpeg("extbdb-" + profile + "-" + format + "-" + depth, run.stream(), format,
                        depth, width, height), "ffmpeg");
            }
        }

        @Test
        @DisplayName("every B macroblock type is used, and the sequence parameter set keeps two reference frames")
        void tools() {
            var width = 128;
            var height = 96;
            var options = options(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, 3).quality(26).build();
            var run = run(width, height, options, motion(width, height, ChromaFormat.YUV420, 10, 16, 9, false, 0.003),
                    false);
            assertBTools(run.types(), true);
            var info = H264StreamInfo.probe(run.packets().getFirst().data());
            assertEquals(2, info.maxNumRefFrames());
            assertSame(run.encoderRecon(), decodeSmalt(run.packets()), "Smalt");
        }
    }

    static Stream<Arguments> structures() {
        return Stream.of(
                Arguments.of(H264FieldCoding.MBAFF, FieldOrder.TOP_FIRST, H264Profile.MAIN, ChromaFormat.YUV420, 8),
                Arguments.of(H264FieldCoding.MBAFF, FieldOrder.BOTTOM_FIRST, H264Profile.HIGH_10, ChromaFormat.YUV420,
                        10),
                Arguments.of(H264FieldCoding.MBAFF, FieldOrder.TOP_FIRST, H264Profile.HIGH_422, ChromaFormat.YUV422,
                        10),
                Arguments.of(H264FieldCoding.MBAFF, FieldOrder.BOTTOM_FIRST, H264Profile.HIGH_444_PREDICTIVE,
                        ChromaFormat.YUV444, 12),
                Arguments.of(H264FieldCoding.MBAFF, FieldOrder.TOP_FIRST, H264Profile.HIGH, ChromaFormat.MONOCHROME, 8),
                Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.TOP_FIRST, H264Profile.MAIN,
                        ChromaFormat.YUV420, 8),
                Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.BOTTOM_FIRST, H264Profile.HIGH_10,
                        ChromaFormat.YUV420, 10),
                Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.TOP_FIRST, H264Profile.HIGH_422,
                        ChromaFormat.YUV422, 8),
                Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.BOTTOM_FIRST, H264Profile.HIGH_444_PREDICTIVE,
                        ChromaFormat.YUV444, 10),
                Arguments.of(H264FieldCoding.FIELD_PICTURES, FieldOrder.TOP_FIRST, H264Profile.HIGH_10,
                        ChromaFormat.MONOCHROME, 10));
    }

    @Nested
    @DisplayName("interlaced frames")
    class Interlaced {
        @ParameterizedTest(name = "{0} {1} {2} {3} {4}-bit")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedBPicturesTest#structures")
        @DisplayName("without deblocking, Smalt and ffmpeg decode exactly the encoder's reconstruction of every frame")
        void reconstruction(H264FieldCoding coding, FieldOrder order, H264Profile profile, ChromaFormat format,
                            int depth) {
            var width = 96;
            var height = 64;
            var options = options(profile, format, depth, 3).fieldOrder(order).fieldCoding(coding).build();
            var run = run(width, height, options, motion(width, height, format, depth, 13, depth + 1, true, 0.004),
                    false);
            assertReordered(run, 13);
            assertBTools(run.types(), false);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.encoderRecon(), smalt, "Smalt");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(run.encoderRecon(), decodeFfmpeg("intb-" + coding + "-" + order + "-" + format + "-"
                        + depth, run.stream(), format, depth, width, height), "ffmpeg");
            }
        }

        @ParameterizedTest(name = "{0} {1} {2} {3} {4}-bit")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedBPicturesTest#structures")
        @DisplayName("with deblocking, Smalt and ffmpeg agree and the frames stay close to the source")
        void deblocked(H264FieldCoding coding, FieldOrder order, H264Profile profile, ChromaFormat format, int depth) {
            var width = 96;
            var height = 64;
            var options = options(profile, format, depth, 2).fieldOrder(order).fieldCoding(coding).quality(20)
                    .build();
            var sources = motion(width, height, format, depth, 10, 17, true, 0.006);
            var run = run(width, height, options, sources, true);
            assertReordered(run, 10);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.decoded(), smalt, "Smalt");
            for (var i = 0; i < smalt.size(); i++) {
                var quality = psnr(sources.get(i), smalt.get(i)[0], depth);
                assertTrue(quality > 34, "frame " + i + " luma PSNR " + quality);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertSame(smalt, decodeFfmpeg("intbdb-" + coding + "-" + order + "-" + format + "-" + depth,
                        run.stream(), format, depth, width, height), "ffmpeg");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.ExtendedBPicturesTest#codings")
        @DisplayName("frames of both field orders mixed with still and moving content use every B macroblock type")
        void tools(H264FieldCoding coding) {
            var width = 128;
            var height = 96;
            for (var order : new FieldOrder[]{FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST}) {
                var options = options(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, 3).fieldOrder(order)
                        .fieldCoding(coding).quality(26).build();
                var run = run(width, height, options,
                        motion(width, height, ChromaFormat.YUV420, 10, 16, 21, true, 0.003), false);
                assertBTools(run.types(), true);
                assertSame(run.encoderRecon(), decodeSmalt(run.packets()), "Smalt " + order);
            }
        }
    }

    /**
     * Builds frames whose left half is a still texture crossed by a moving textured block and whose right half
     * pans, with still squares inside it, so that the co-located motion of B macroblocks is zero in places where
     * their neighbors move, and the reverse.
     */
    static List<Picture> holes(int width, int height, ChromaFormat format, int depth, int count, boolean interlaced) {
        var list = new ArrayList<Picture>();
        var max = (1 << depth) - 1;
        for (var f = 0; f < count; f++) {
            var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                var t = interlaced && (y & 1) == 1 ? f + 0.5 : f;
                for (var x = 0; x < width; x++) {
                    double value;
                    var still = 0.4 + 0.25 * Math.sin(x * 0.37 + Math.cos(y * 0.23) * 2) * Math.cos(y * 0.41);
                    if (x < width / 2) {
                        value = still;
                        var bx = x - 2 * t;
                        if (bx > 4 && bx < 28 && y > height / 3 && y < height / 3 + 24) {
                            value = 0.6 + 0.2 * Math.sin(bx * 0.8) * Math.cos(y * 0.6);
                        }
                    } else {
                        var u = x + 2 * t;
                        value = 0.5 + 0.2 * Math.sin(u * 0.31) * Math.cos(y * 0.19) + 0.1 * Math.cos(u * 0.07);
                        if (((x / 16) + (y / 16)) % 3 == 0) {
                            value = still;
                        }
                    }
                    picture.y()[y * picture.yStride() + x] = (short) Math.clamp(Math.round(value * max), 0, max);
                }
            }
            if (format != ChromaFormat.MONOCHROME) {
                var sx = (double) width / picture.chromaWidth();
                var sy = (double) height / picture.chromaHeight();
                for (var y = 0; y < picture.chromaHeight(); y++) {
                    for (var x = 0; x < picture.chromaWidth(); x++) {
                        var lx = x * sx;
                        var u = lx < width / 2.0 ? lx : lx + 2 * f;
                        var cb = 0.5 + 0.12 * Math.sin(u * 0.13 + y * sy * 0.05);
                        var cr = 0.5 + 0.12 * Math.cos(u * 0.05 - y * sy * 0.11);
                        picture.u()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(cb * max), 0, max);
                        picture.v()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(cr * max), 0, max);
                    }
                }
            }
            list.add(picture);
        }
        return list;
    }

    static Stream<Arguments> directCases() {
        return Stream.of(
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, FieldOrder.PROGRESSIVE, H264FieldCoding.MBAFF),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 12, FieldOrder.PROGRESSIVE,
                        H264FieldCoding.MBAFF),
                Arguments.of(H264Profile.HIGH, ChromaFormat.MONOCHROME, 8, FieldOrder.PROGRESSIVE,
                        H264FieldCoding.MBAFF),
                Arguments.of(H264Profile.MAIN, ChromaFormat.YUV420, 8, FieldOrder.TOP_FIRST, H264FieldCoding.MBAFF),
                Arguments.of(H264Profile.HIGH_422, ChromaFormat.YUV422, 10, FieldOrder.BOTTOM_FIRST,
                        H264FieldCoding.MBAFF),
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, FieldOrder.TOP_FIRST,
                        H264FieldCoding.FIELD_PICTURES),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 8, FieldOrder.BOTTOM_FIRST,
                        H264FieldCoding.FIELD_PICTURES));
    }

    @ParameterizedTest(name = "{0} {1} {2}-bit {3} {4}")
    @MethodSource("directCases")
    @DisplayName("with half the B macroblocks coded with their direct motion, Smalt and ffmpeg decode the encoder's reconstruction")
    void forcedDirect(H264Profile profile, ChromaFormat format, int depth, FieldOrder order, H264FieldCoding coding) {
        var width = 128;
        var height = 96;
        var options = options(profile, format, depth, 3).quality(24).fieldOrder(order).fieldCoding(coding).build();
        var run = run(width, height, options, holes(width, height, format, depth, 13, order.isInterlaced()), false,
                true);
        assertReordered(run, 13);
        var types = run.types();
        assertTrue(types[B_DIRECT] + types[B_SKIP] >= types[B_L0] + types[B_L1] + types[B_BI],
                "direct coding holds at least half the B macroblocks: " + Arrays.toString(types));
        assertTrue(Arrays.stream(run.zeroed()).sum() > 0, "the co-located motion zeroes some direct vectors");
        assertSame(run.encoderRecon(), decodeSmalt(run.packets()), "Smalt");
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            assertSame(run.encoderRecon(), decodeFfmpeg("extbdir-" + format + "-" + depth + "-" + order + "-" + coding,
                    run.stream(), format, depth, width, height), "ffmpeg");
        }
    }

    /**
     * Builds interlaced frames of a panning background with two kinds of macroblock pair regions whose coding
     * changes between the anchor frames, every fourth frame, and the frames between them. In the first kind
     * the anchors hold a still field of the given parity and a moving field of the other, which code as a field
     * pair, and the other frames repeat each row twice, which codes as a frame pair; in the second kind the
     * anchors hold a still top macroblock and a moving bottom one in a smooth frame pair, and the other frames
     * alternate bright and dark rows, which code as a field pair. B macroblocks then find their co-located motion in a pair of the
     * other kind, in both directions.
     */
    static List<Picture> mixedPairs(int width, int height, ChromaFormat format, int depth, int count,
                                    int stillParity) {
        var list = new ArrayList<Picture>();
        var max = (1 << depth) - 1;
        for (var f = 0; f < count; f++) {
            var anchor = f % 4 == 0;
            var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                var t = (y & 1) == 1 ? f + 0.5 : f;
                for (var x = 0; x < width; x++) {
                    var u = x + 2 * t;
                    var value = 0.5 + 0.2 * Math.sin(u * 0.29) * Math.cos(y * 0.17) + 0.08 * Math.cos(u * 0.06);
                    var pairX = x / 16;
                    var pairY = y / 32;
                    var row = y % 32;
                    if (pairY % 2 == 0 && pairX % 4 == 1) {
                        if (!anchor) {
                            value = 0.45 + 0.1 * Math.sin(x * 0.4 + (y >> 1) * 0.3);
                        } else if ((y & 1) == stillParity) {
                            value = 0.3 + 0.2 * Math.sin(x * 0.5) * Math.cos(y * 0.21);
                        } else {
                            value = 0.7 + 0.15 * Math.sin((x + 3 * f) * 0.45 + y * 0.12);
                        }
                    } else if (pairY % 2 == 0 && pairX % 4 == 3) {
                        if (!anchor) {
                            value = (y & 1) == 0 ? 0.8 + 0.05 * Math.sin(x * 0.3) : 0.2 + 0.05 * Math.cos(x * 0.3);
                        } else if (row < 16) {
                            value = 0.4 + 0.15 * Math.sin(x * 0.33 + y * 0.09);
                        } else {
                            value = 0.55 + 0.15 * Math.sin((x + 3 * f) * 0.27 + y * 0.05);
                        }
                    }
                    picture.y()[y * picture.yStride() + x] = (short) Math.clamp(Math.round(value * max), 0, max);
                }
            }
            if (format != ChromaFormat.MONOCHROME) {
                for (var y = 0; y < picture.chromaHeight(); y++) {
                    for (var x = 0; x < picture.chromaWidth(); x++) {
                        var cb = 0.5 + 0.1 * Math.sin((x + f) * 0.2);
                        var cr = 0.5 + 0.1 * Math.cos(y * 0.15);
                        picture.u()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(cb * max), 0, max);
                        picture.v()[y * picture.uvStride() + x] = (short) Math.clamp(Math.round(cr * max), 0, max);
                    }
                }
            }
            list.add(picture);
        }
        return list;
    }

    static Stream<Arguments> mixedCases() {
        return Stream.of(
                Arguments.of(H264Profile.MAIN, ChromaFormat.YUV420, 8, FieldOrder.TOP_FIRST),
                Arguments.of(H264Profile.HIGH_422, ChromaFormat.YUV422, 10, FieldOrder.BOTTOM_FIRST));
    }

    @ParameterizedTest(name = "{0} {1} {2}-bit {3}")
    @MethodSource("mixedCases")
    @DisplayName("direct macroblocks over co-located pairs of the other kind decode to the encoder's reconstruction")
    void mixedPairDirect(H264Profile profile, ChromaFormat format, int depth, FieldOrder order) {
        var width = 128;
        var height = 128;
        var options = options(profile, format, depth, 3).quality(24).fieldOrder(order)
                .fieldCoding(H264FieldCoding.MBAFF).adaptiveQuantization(H264AdaptiveQuantization.NONE).build();
        var run = run(width, height, options, mixedPairs(width, height, format, depth, 12,
                order == FieldOrder.BOTTOM_FIRST ? 1 : 0), false, true);
        assertReordered(run, 12);
        var zeroed = run.zeroed();
        assertTrue(zeroed[1] > 0 && zeroed[2] > 0, "a frame macroblock over a field pair and a field macroblock "
                + "over a frame pair have direct vectors the co-located motion zeroes: " + Arrays.toString(zeroed));
        assertSame(run.encoderRecon(), decodeSmalt(run.packets()), "Smalt");
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            assertSame(run.encoderRecon(), decodeFfmpeg("extbmix-" + format + "-" + depth + "-" + order,
                    run.stream(), format, depth, width, height), "ffmpeg");
        }
    }

    static Stream<H264FieldCoding> codings() {
        return Stream.of(H264FieldCoding.MBAFF, H264FieldCoding.FIELD_PICTURES);
    }

    static Stream<Arguments> losslessCases() {
        return Stream.of(
                Arguments.of(ChromaFormat.YUV444, 8, FieldOrder.PROGRESSIVE, H264FieldCoding.MBAFF),
                Arguments.of(ChromaFormat.YUV444, 14, FieldOrder.PROGRESSIVE, H264FieldCoding.MBAFF),
                Arguments.of(ChromaFormat.YUV420, 10, FieldOrder.TOP_FIRST, H264FieldCoding.MBAFF),
                Arguments.of(ChromaFormat.YUV422, 12, FieldOrder.BOTTOM_FIRST, H264FieldCoding.FIELD_PICTURES),
                Arguments.of(ChromaFormat.MONOCHROME, 8, FieldOrder.PROGRESSIVE, H264FieldCoding.MBAFF));
    }

    @ParameterizedTest(name = "{0} {1}-bit {2} {3}")
    @MethodSource("losslessCases")
    @DisplayName("lossless streams with B pictures decode to the input samples in Smalt and ffmpeg")
    void lossless(ChromaFormat format, int depth, FieldOrder order, H264FieldCoding coding) {
        var width = 64;
        var height = 48;
        var options = options(H264Profile.HIGH_444_PREDICTIVE, format, depth, 2).lossless(true).fieldOrder(order)
                .fieldCoding(coding).build();
        var sources = motion(width, height, format, depth, 9, 3, order.isInterlaced(), 0.01);
        var run = run(width, height, options, sources, true);
        assertReordered(run, 9);
        var expected = new ArrayList<short[][]>();
        for (var source : sources) {
            expected.add(planes(source));
        }
        var smalt = decodeSmalt(run.packets());
        assertSame(expected, smalt, "Smalt");
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            assertSame(expected, decodeFfmpeg("extbll-" + format + "-" + depth + "-" + order, run.stream(), format,
                    depth, width, height), "ffmpeg");
        }
    }

    @Nested
    @DisplayName("coding order and options")
    class Order {
        @Test
        @DisplayName("a key frame interval and a key frame request start new IDR frames that no B picture straddles")
        void keyframes() {
            var width = 64;
            var height = 48;
            var options = options(H264Profile.HIGH_422, ChromaFormat.YUV422, 10, 3).keyframeInterval(7).build();
            var sources = motion(width, height, ChromaFormat.YUV422, 10, 20, 4, false, 0.004);
            var packets = new ArrayList<Packet>();
            var recon = new short[sources.size()][][];
            try (var encoder = new H264Encoder(width, height, options)) {
                encoder.wide().setDeblocking(false);
                Consumer<Packet> sink = packet -> {
                    packets.add(packet);
                    recon[(int) encoder.wide().lastDisplayIndex()] = encoder.wide().lastEncoderReconstruction();
                };
                for (var i = 0; i < sources.size(); i++) {
                    if (i == 10) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(sources.get(i), i * 3000L, 3000, false), sink);
                }
                encoder.flush(sink);
            }
            var keys = new ArrayList<Long>();
            for (var packet : packets) {
                if (packet.keyframe()) {
                    keys.add(packet.pts() / 3000);
                }
            }
            assertEquals(List.of(0L, 7L, 10L, 17L), keys);
            // every packet before a key frame in coding order is presented before it
            for (var i = 0; i < packets.size(); i++) {
                if (packets.get(i).keyframe()) {
                    for (var j = 0; j < i; j++) {
                        assertTrue(packets.get(j).pts() < packets.get(i).pts());
                    }
                }
            }
            assertSame(Arrays.asList(recon), decodeSmalt(packets), "Smalt");
        }

        static Stream<Arguments> excluded() {
            var base = options(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, 3);
            return Stream.of(
                    Arguments.of("CAVLC", base.build().toBuilder().cavlc(true).build()),
                    Arguments.of("no look-ahead", base.build().toBuilder().lookahead(0).build()),
                    Arguments.of("real-time mode", base.build().toBuilder().realtime(true).build()),
                    Arguments.of("intra refresh", base.build().toBuilder().intraRefresh(4).build()),
                    Arguments.of("temporal layers", base.build().toBuilder().temporalLayers(2).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("excluded")
        @DisplayName("streams whose options rule B pictures out code none and emit one packet per frame at once")
        void excluded(String name, H264EncoderOptions options) {
            var width = 64;
            var height = 48;
            var sources = motion(width, height, ChromaFormat.YUV420, 10, 6, 2, false, 0.004);
            try (var encoder = new H264Encoder(width, height, options)) {
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < sources.size(); i++) {
                    encoder.encode(new VideoFrame(sources.get(i), i * 3000L, 3000, false), packets::add);
                    assertEquals(i + 1, packets.size(), name + ": packet of frame " + i);
                    assertEquals(packets.getLast().pts(), packets.getLast().dts(), name);
                }
                assertFalse(sliceTypes(packets).contains(1), name);
                assertEquals(1, H264StreamInfo.probe(packets.getFirst().data()).maxNumRefFrames(), name);
            }
        }

        static Stream<Arguments> bounds() {
            return Stream.of(Arguments.of(6, 3, 2), Arguments.of(7, 20, 3), Arguments.of(1, 20, 1));
        }

        @ParameterizedTest(name = "{0} B pictures, look-ahead {1}")
        @MethodSource("bounds")
        @DisplayName("the look-ahead and the run limit of unreferenced B pictures bound the B pictures in a row, and the encoder holds that many frames")
        void lookaheadBound(int bFrames, int lookahead, int expected) {
            var width = 64;
            var height = 48;
            var options = options(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, bFrames).lookahead(lookahead).build();
            var sources = motion(width, height, ChromaFormat.YUV420, 10, 12, 6, false, 0.004);
            var packets = new ArrayList<Packet>();
            try (var encoder = new H264Encoder(width, height, options)) {
                for (var i = 0; i < sources.size(); i++) {
                    encoder.encode(new VideoFrame(sources.get(i), i * 3000L, 3000, false), packets::add);
                    assertEquals(Math.max(0, i + 1 - expected), packets.size(), "frames held after frame " + i);
                }
                encoder.flush(packets::add);
            }
            var types = sliceTypes(packets);
            var run = 0;
            var longest = 0;
            for (var type : types) {
                run = type == 1 ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            assertEquals(expected, longest, "B pictures in a row: " + types);
        }
    }

    /** Returns the bits and the mean luma PSNR of an encode at a quality, deblocking on. */
    static double[] point(int width, int height, H264EncoderOptions options, List<Picture> sources, int depth) {
        var run = run(width, height, options, sources, true);
        var bits = 0L;
        for (var packet : run.packets()) {
            bits += packet.size() * 8L;
        }
        var sum = 0.0;
        for (var i = 0; i < sources.size(); i++) {
            sum += psnr(sources.get(i), run.decoded().get(i)[0], depth);
        }
        return new double[]{bits, sum / sources.size()};
    }

    /** Returns the log of the bits a curve of (bits, PSNR) points needs at a PSNR, or NaN outside it. */
    static double logBitsAt(List<double[]> curve, double quality) {
        for (var i = 0; i + 1 < curve.size(); i++) {
            var a = curve.get(i);
            var b = curve.get(i + 1);
            var low = Math.min(a[1], b[1]);
            var high = Math.max(a[1], b[1]);
            if (quality >= low && quality <= high && high > low) {
                var t = (quality - a[1]) / (b[1] - a[1]);
                return Math.log(a[0]) + t * (Math.log(b[0]) - Math.log(a[0]));
            }
        }
        return Double.NaN;
    }

    static Stream<Arguments> rdCases() {
        return Stream.of(
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, FieldOrder.PROGRESSIVE,
                        H264FieldCoding.MBAFF, 3),
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, FieldOrder.PROGRESSIVE,
                        H264FieldCoding.MBAFF, 7),
                Arguments.of(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 8, FieldOrder.PROGRESSIVE,
                        H264FieldCoding.MBAFF, 3),
                Arguments.of(H264Profile.HIGH_422, ChromaFormat.YUV422, 10, FieldOrder.TOP_FIRST,
                        H264FieldCoding.FIELD_PICTURES, 3),
                Arguments.of(H264Profile.HIGH_422, ChromaFormat.YUV422, 10, FieldOrder.TOP_FIRST,
                        H264FieldCoding.FIELD_PICTURES, 7),
                Arguments.of(H264Profile.HIGH_10, ChromaFormat.YUV420, 10, FieldOrder.BOTTOM_FIRST,
                        H264FieldCoding.MBAFF, 3));
    }

    @ParameterizedTest(name = "{0} {1} {2}-bit {3} {4}, {5} B pictures")
    @MethodSource("rdCases")
    @DisplayName("B pictures need fewer bits than P pictures alone at equal PSNR")
    void rateDistortion(H264Profile profile, ChromaFormat format, int depth, FieldOrder order,
                        H264FieldCoding coding, int bFrames) {
        var width = 128;
        var height = 96;
        var sources = motion(width, height, format, depth, 25, 8, order.isInterlaced(), 0.002);
        var withB = new ArrayList<double[]>();
        var withoutB = new ArrayList<double[]>();
        for (var quality : new int[]{20, 25, 30, 35}) {
            var b = options(profile, format, depth, bFrames).quality(quality).noPeriodicKeyframes().fieldOrder(order)
                    .fieldCoding(coding).build();
            withB.add(point(width, height, b, sources, depth));
            var p = b.toBuilder().bFrames(0).lookahead(0).build();
            withoutB.add(point(width, height, p, sources, depth));
        }
        var savings = new ArrayList<Double>();
        for (var point : withB) {
            var reference = logBitsAt(withoutB, point[1]);
            if (!Double.isNaN(reference)) {
                savings.add(1 - Math.exp(Math.log(point[0]) - reference));
            }
        }
        assertTrue(savings.size() >= 2, "the curves overlap: " + describe(withB) + " vs " + describe(withoutB));
        var mean = savings.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertTrue(mean > 0.05, "mean saving " + mean + " at equal PSNR: " + describe(withB) + " vs "
                + describe(withoutB));
    }

    static String describe(List<double[]> curve) {
        var text = new StringBuilder();
        for (var point : curve) {
            text.append(String.format("(%.0f bits, %.2f dB) ", point[0], point[1]));
        }
        return text.toString();
    }

    @Test
    @DisplayName("an MP4 of a High 4:2:2 interlaced stream with B pictures passes the validators and decodes exactly")
    void mp4() {
        var width = 64;
        var height = 64;
        var options = options(H264Profile.HIGH_422, ChromaFormat.YUV422, 10, 2)
                .packetFormat(H264PacketFormat.LENGTH_PREFIXED).fieldOrder(FieldOrder.TOP_FIRST)
                .fieldCoding(H264FieldCoding.FIELD_PICTURES).build();
        var sources = frames(width, height, ChromaFormat.YUV422, 10, 9, 3, true);
        var packets = new ArrayList<Packet>();
        var decoded = new short[sources.size()][][];
        byte[] config;
        try (var encoder = new H264Encoder(width, height, options)) {
            config = encoder.codecConfig().orElseThrow();
            Consumer<Packet> sink = packet -> {
                packets.add(packet);
                decoded[(int) encoder.wide().lastDisplayIndex()] = encoder.wide().lastReconstruction();
            };
            for (var i = 0; i < sources.size(); i++) {
                encoder.encode(new VideoFrame(sources.get(i), i * 3000L, 3000, false), sink);
            }
            encoder.flush(sink);
        }
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).width(width)
                .height(height).frameRate(new Rational(30, 1)).codecConfig(config).build();
        var sink = com.github.auties00.smalt.io.MediaSink.memory();
        try (var muxer = new com.github.auties00.smalt.container.mp4.Mp4Muxer(sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet.withTrackId(1));
            }
            muxer.finish();
        }
        var file = EncoderHarness.write("extb-mp4-422-fields.mp4", sink.toByteArray());
        Oracle.assertDecodesCleanly(file);
        var smalt = new ArrayList<short[][]>();
        try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().highBitDepth(true).build())) {
            for (var packet : packets) {
                decoder.decode(packet.withTrackId(1), (frame, damaged) -> smalt.add(planes(frame.picture())));
            }
            decoder.flush((frame, damaged) -> smalt.add(planes(frame.picture())));
        }
        assertSame(Arrays.asList(decoded), smalt, "Smalt");
        for (var i = 0; i < smalt.size(); i++) {
            assertArrayEquals(decoded[i][0], smalt.get(i)[0]);
        }
    }
}
