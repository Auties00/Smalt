package com.github.auties00.smalt.internal.dolbyvision;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.testkit.DolbyVisionReference;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.DolbyVisionRpus.Kind;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the decoder around the composer and the color conversion over a scripted base layer decoder: which RPU applies
 * to which picture when frames are reordered or RPUs are missing or reused, when a track that declares nothing starts
 * reconstructing, and what its pictures are.
 */
@DisplayName("Dolby Vision reconstructing decoder")
class DolbyVisionVideoDecoderTest {
    private static final ColorInfo BASE_COLOR = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.FULL,
            ColorPrimaries.BT2020, TransferCharacteristics.PQ);

    /** Holds pictures back like a decoder with reordering, and makes each picture from its timestamp. */
    private static final class ScriptedDecoder implements VideoDecoder {
        private final int delay;
        private final IntFunction<Picture> pictures;
        private final PriorityQueue<VideoFrame> held = new PriorityQueue<>(Comparator.comparingLong(VideoFrame::pts));
        private final VideoDecoderOptions options;
        private boolean closed;

        ScriptedDecoder(int delay, IntFunction<Picture> pictures, VideoDecoderOptions options) {
            this.delay = delay;
            this.pictures = pictures;
            this.options = options;
        }

        @Override
        public void decode(Packet packet, Consumer<VideoFrame> output) {
            held.add(new VideoFrame(pictures.apply((int) packet.pts()), packet.pts(), packet.duration(), packet.keyframe()));
            while (held.size() > delay) {
                output.accept(held.poll());
            }
        }

        @Override
        public void flush(Consumer<VideoFrame> output) {
            while (!held.isEmpty()) {
                output.accept(held.poll());
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final List<ScriptedDecoder> created = new ArrayList<>();

    private static Yuv16Picture base(int pts) {
        return DolbyVisionReconstructionTest.randomBase(new SplittableRandom(1000 + pts), 16, 8);
    }

    private DolbyVisionVideoDecoder decoder(boolean declared, int delay, VideoDecoderOptions options) {
        return new DolbyVisionVideoDecoder(Codec.HEVC, null, declared, options, decoderOptions -> {
            var scripted = new ScriptedDecoder(delay, DolbyVisionVideoDecoderTest::base, decoderOptions);
            created.add(scripted);
            return scripted;
        });
    }

    private static VideoDecoderOptions highBitDepth() {
        return VideoDecoderOptions.builder().highBitDepth(true).build();
    }

    private static DolbyVisionRpu.Mapping mapping(int id, double gain) {
        var luma = new DolbyVisionRpu.Piece(false, 2, new long[]{0, (long) (gain * (1 << 23)), -(1L << 19)});
        var chroma = new DolbyVisionRpu.Piece(false, 1, new long[]{(long) ((1 - gain) / 2 * (1 << 23)),
                (long) (gain * (1 << 23))});
        var regression = new long[22];
        regression[0] = 1L << 16;
        regression[2] = 1L << 23;
        regression[1] = -(1L << 16);
        return new DolbyVisionRpu.Mapping(id, 23, false, new DolbyVisionRpu.Curve[]{
                new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{luma}),
                new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{chroma}),
                new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{
                        new DolbyVisionRpu.Piece(true, 3, regression)})});
    }

    private static byte[] unit(DolbyVisionRpus rpus) {
        return DolbyVisionRpus.appendNal(new byte[0], rpus.hevcNal());
    }

    private static DolbyVisionRpus profile5(int id, double gain) {
        return DolbyVisionRpus.of(Kind.PROFILE_5).mapping(DolbyVisionReconstructionTest.reference(mapping(id, gain))).iptPqC2();
    }

    private static Packet packet(int pts, byte[] data, boolean keyframe) {
        return new Packet(0, data, pts, pts, 1, keyframe);
    }

    /** A copy of a frame's planes, which are recycled once the consumer returns. */
    private record Output(long pts, int depth, int[] g, int[] b, int[] r, ColorInfo color, boolean keyframe) {
    }

    private static Output copy(VideoFrame frame) {
        var picture = frame.picture();
        var size = picture.width() * picture.height();
        if (picture instanceof Yuv16Picture deep) {
            assertEquals(ChromaFormat.YUV444, deep.chromaFormat());
            var g = new int[size];
            var b = new int[size];
            var r = new int[size];
            for (var row = 0; row < picture.height(); row++) {
                for (var x = 0; x < picture.width(); x++) {
                    var at = row * deep.yStride() + x;
                    var index = row * picture.width() + x;
                    g[index] = deep.y()[at] & 0xFFFF;
                    b[index] = deep.u()[at] & 0xFFFF;
                    r[index] = deep.v()[at] & 0xFFFF;
                }
            }
            return new Output(frame.pts(), deep.bitDepth(), g, b, r, deep.color(), frame.keyframe());
        }
        var narrow = assertInstanceOf(YuvPicture.class, picture);
        var g = new int[size];
        var b = new int[size];
        var r = new int[size];
        for (var row = 0; row < picture.height(); row++) {
            for (var x = 0; x < picture.width(); x++) {
                var at = row * narrow.yStride() + x;
                var index = row * picture.width() + x;
                g[index] = narrow.y()[at] & 0xFF;
                b[index] = narrow.u()[at] & 0xFF;
                r[index] = narrow.v()[at] & 0xFF;
            }
        }
        return new Output(frame.pts(), 8, g, b, r, narrow.color(), frame.keyframe());
    }

    private static List<Output> run(VideoDecoder decoder, Packet... packets) {
        var outputs = new ArrayList<Output>();
        Consumer<VideoFrame> sink = frame -> outputs.add(copy(frame));
        for (var packet : packets) {
            decoder.decode(packet, sink);
        }
        decoder.flush(sink);
        return outputs;
    }

    private static void assertReconstructed(Output output, DolbyVisionRpu.Mapping mapping, short[] ycc, long[] offsets,
                                            short[] lms) {
        var planes = DolbyVisionReconstructionTest.planes(base((int) output.pts()));
        var composed = DolbyVisionReference.compose(planes, DolbyVisionReconstructionTest.reference(mapping), 12);
        var rgb = DolbyVisionReference.toRgb(composed, 12, ycc, offsets, lms);
        var scale = (1 << output.depth()) - 1;
        var tolerance = Tolerance.maxAbs(1);
        tolerance.assertWithin("green", codes(rgb[0], scale), output.g());
        tolerance.assertWithin("blue", codes(rgb[1], scale), output.b());
        tolerance.assertWithin("red", codes(rgb[2], scale), output.r());
    }

    private static void assertIpt(Output output, DolbyVisionRpu.Mapping mapping) {
        assertReconstructed(output, mapping, DolbyVisionRpus.IPT_PQ_C2_YCC_TO_RGB, DolbyVisionRpus.IPT_PQ_C2_OFFSET,
                DolbyVisionRpus.IPT_PQ_C2_RGB_TO_LMS);
    }

    private static int[] codes(double[] values, int scale) {
        var out = new int[values.length];
        for (var index = 0; index < values.length; index++) {
            out[index] = (int) Math.round(values[index] * scale);
        }
        return out;
    }

    @Nested
    @DisplayName("a declared stream")
    class Declared {
        @Test
        @DisplayName("reconstructs each picture from the RPU of its packet, in the depth the RPU names")
        void perFrame() {
            var outputs = run(decoder(true, 0, highBitDepth()),
                    packet(0, unit(profile5(0, 0.9)), true),
                    packet(1, unit(profile5(1, 1.1)), false));
            assertEquals(2, outputs.size());
            assertEquals(12, outputs.get(0).depth());
            assertIpt(outputs.get(0), mapping(0, 0.9));
            assertIpt(outputs.get(1), mapping(1, 1.1));
        }

        @Test
        @DisplayName("matches a reordered frame to the RPU of the packet that coded it")
        void reordered() {
            var outputs = run(decoder(true, 2, highBitDepth()),
                    packet(0, unit(profile5(0, 0.8)), true),
                    packet(3, unit(profile5(1, 0.9)), false),
                    packet(1, unit(profile5(2, 1.0)), false),
                    packet(2, unit(profile5(3, 1.1)), false));
            assertEquals(List.of(0L, 1L, 2L, 3L), outputs.stream().map(Output::pts).toList());
            assertIpt(outputs.get(0), mapping(0, 0.8));
            assertIpt(outputs.get(1), mapping(2, 1.0));
            assertIpt(outputs.get(2), mapping(3, 1.1));
            assertIpt(outputs.get(3), mapping(1, 0.9));
        }

        @Test
        @DisplayName("gives a packet without an RPU the one before it, and fails only before the first")
        void missing() {
            var decoder = decoder(true, 0, highBitDepth());
            var frames = new ArrayList<Output>();
            var refused = assertThrows(MediaException.Malformed.class,
                    () -> decoder.decode(packet(0, new byte[]{0, 0, 0, 1, 0x40}, true), frame -> frames.add(copy(frame))));
            assertTrue(refused.getMessage().contains("RPU"));
            assertEquals(List.of(), frames);
            var outputs = run(decoder, packet(1, unit(profile5(0, 0.9)), true),
                    packet(2, new byte[]{0, 0, 0, 1, 0x40}, false));
            assertEquals(2, outputs.size());
            assertIpt(outputs.get(1), mapping(0, 0.9));
        }

        @Test
        @DisplayName("reconstructs every frame of a packet that yields several, none of them dropped")
        void severalFrames() {
            var decoder = new DolbyVisionVideoDecoder(Codec.HEVC, null, true, highBitDepth(), options -> new VideoDecoder() {
                @Override
                public void decode(Packet packet, Consumer<VideoFrame> output) {
                    output.accept(new VideoFrame(base((int) packet.pts()), packet.pts(), 1, packet.keyframe()));
                    output.accept(new VideoFrame(base((int) packet.pts()), packet.pts(), 1, packet.keyframe()));
                }

                @Override
                public void flush(Consumer<VideoFrame> output) {
                }
            });
            var outputs = run(decoder, packet(0, unit(profile5(0, 0.9)), true));
            assertEquals(2, outputs.size());
            assertIpt(outputs.get(0), mapping(0, 0.9));
            assertIpt(outputs.get(1), mapping(0, 0.9));
        }

        @Test
        @DisplayName("forgets at a flush the RPUs of pictures the base layer decoder never output")
        void flushForgets() {
            var decoder = new DolbyVisionVideoDecoder(Codec.HEVC, null, true, highBitDepth(), options -> new VideoDecoder() {
                private boolean dropped;

                @Override
                public void decode(Packet packet, Consumer<VideoFrame> output) {
                    if (dropped) {
                        output.accept(new VideoFrame(base((int) packet.pts()), packet.pts(), 1, packet.keyframe()));
                    }
                    dropped = true;
                }

                @Override
                public void flush(Consumer<VideoFrame> output) {
                }
            });
            assertEquals(List.of(), run(decoder, packet(5, unit(profile5(0, 0.8)), true)));
            var outputs = run(decoder, packet(5, unit(profile5(1, 1.1)), true));
            assertEquals(1, outputs.size());
            assertIpt(outputs.getFirst(), mapping(1, 1.1));
        }

        @Test
        @DisplayName("decodes a packet whose RPU regresses the luma component with the RPU before it, then fails it")
        void lumaRegression() {
            var decoder = decoder(true, 0, highBitDepth());
            var outputs = new ArrayList<Output>();
            Consumer<VideoFrame> sink = frame -> outputs.add(copy(frame));
            decoder.decode(packet(0, unit(profile5(0, 0.9)), true), sink);
            var good = mapping(1, 1.0);
            var curves = good.curves().clone();
            curves[0] = new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{
                    new DolbyVisionRpu.Piece(true, 1, new long[8])});
            var bad = new DolbyVisionRpu.Mapping(1, 23, false, curves);
            var data = unit(DolbyVisionRpus.of(Kind.PROFILE_5).mapping(DolbyVisionReconstructionTest.reference(bad)).iptPqC2());
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(1, data, false), sink));
            assertEquals(2, outputs.size());
            assertIpt(outputs.get(1), mapping(0, 0.9));
            decoder.decode(packet(2, unit(profile5(2, 1.1)), false), sink);
            assertIpt(outputs.get(2), mapping(2, 1.1));
        }

        @Test
        @DisplayName("refuses an enhancement layer found in band, before the base layer decoder sees the packet")
        void enhancementLayer() {
            var layer = DolbyVisionRpus.appendNal(unit(profile5(0, 1.0)), new byte[]{0x7E, 0x01, 0x55, 0x55});
            var declared = decoder(true, 0, highBitDepth());
            var frames = new ArrayList<Output>();
            var refused = assertThrows(MediaException.Unsupported.class,
                    () -> declared.decode(packet(0, layer, true), frame -> frames.add(copy(frame))));
            assertEquals("dolby vision reconstruction", refused.format());
            assertEquals(List.of(), frames);
            var undeclared = decoder(false, 0, highBitDepth());
            assertThrows(MediaException.Unsupported.class,
                    () -> undeclared.decode(packet(0, layer, true), frame -> frames.add(copy(frame))));
            assertEquals(List.of(), frames);
        }

        @Test
        @DisplayName("reconstructs with the identity curves and matrices of an RPU of dovi_tool's generator")
        void generated() {
            var nal = java.util.HexFormat.of().parseHex(DolbyVisionRpuReaderTest.GENERATED_NAL);
            var identity = new DolbyVisionRpu.Piece(false, 1, new long[]{0, 1L << 23});
            var curve = new DolbyVisionRpu.Curve(new int[]{0, 1023}, new DolbyVisionRpu.Piece[]{identity});
            var expected = new DolbyVisionRpu.Mapping(0, 23, false, new DolbyVisionRpu.Curve[]{curve, curve, curve});
            var outputs = run(decoder(true, 0, highBitDepth()),
                    packet(0, DolbyVisionRpus.appendNal(new byte[0], nal), true));
            assertEquals(12, outputs.getFirst().depth());
            assertIpt(outputs.getFirst(), expected);
        }

        @Test
        @DisplayName("takes a damaged RPU as absent")
        void damaged() {
            var data = unit(profile5(1, 1.1));
            data[data.length - 2] ^= 0x41;
            var outputs = run(decoder(true, 0, highBitDepth()),
                    packet(0, unit(profile5(0, 0.9)), true), packet(1, data, false));
            assertIpt(outputs.get(1), mapping(0, 0.9));
        }

        @Test
        @DisplayName("ignores the nonlinear quantization parameters, since a single layer has no residual")
        void nonlinearQuantization() {
            var minimal = run(decoder(true, 0, highBitDepth()),
                    packet(0, unit(DolbyVisionRpus.of(Kind.PROFILE_7_MEL).iptPqC2()), true)).getFirst();
            var full = run(decoder(true, 0, highBitDepth()),
                    packet(0, unit(DolbyVisionRpus.of(Kind.PROFILE_7_FEL).iptPqC2()), true)).getFirst();
            Tolerance.exact().assertWithin("green", minimal.g(), full.g());
            Tolerance.exact().assertWithin("blue", minimal.b(), full.b());
            Tolerance.exact().assertWithin("red", minimal.r(), full.r());
        }

        @Test
        @DisplayName("reuses a stored mapping by the identifier the RPU names")
        void reused() {
            var outputs = run(decoder(true, 0, highBitDepth()),
                    packet(0, unit(profile5(0, 0.8)), true),
                    packet(1, unit(profile5(1, 1.1)), false),
                    packet(2, unit(DolbyVisionRpus.of(Kind.PROFILE_5).reusePrevious(0).iptPqC2()), false),
                    packet(3, unit(DolbyVisionRpus.of(Kind.PROFILE_5).reusePrevious(1).iptPqC2()), false));
            assertIpt(outputs.get(2), mapping(0, 0.8));
            assertIpt(outputs.get(3), mapping(1, 1.1));
        }

        @Test
        @DisplayName("finds the matrices of compressed display management data by identifier")
        void compressed() {
            var first = DolbyVisionRpus.of(Kind.PROFILE_5).mapping(DolbyVisionReconstructionTest.reference(mapping(0, 0.9))).displayManagementIds(3, 3);
            var second = profile5(0, 0.9).displayManagementIds(5, 5);
            var third = DolbyVisionRpus.of(Kind.PROFILE_5).mapping(DolbyVisionReconstructionTest.reference(mapping(0, 0.9))).compressedDm().displayManagementIds(0, 3);
            var outputs = run(decoder(true, 0, highBitDepth()),
                    packet(0, unit(first), true), packet(1, unit(second), false), packet(2, unit(third), false));
            assertReconstructed(outputs.get(0), mapping(0, 0.9), DolbyVisionRpus.BT2020_YCC_TO_RGB,
                    DolbyVisionRpus.BT2020_OFFSET, DolbyVisionRpus.BT2020_RGB_TO_LMS);
            assertIpt(outputs.get(1), mapping(0, 0.9));
            assertReconstructed(outputs.get(2), mapping(0, 0.9), DolbyVisionRpus.BT2020_YCC_TO_RGB,
                    DolbyVisionRpus.BT2020_OFFSET, DolbyVisionRpus.BT2020_RGB_TO_LMS);
        }

        @Test
        @DisplayName("starts a compressed stream on the default matrices of clause 6.2.2")
        void defaultMatrices() {
            var outputs = run(decoder(true, 0, highBitDepth()), packet(0,
                    unit(DolbyVisionRpus.of(Kind.PROFILE_5).mapping(DolbyVisionReconstructionTest.reference(mapping(0, 0.9))).compressedDm()), true));
            assertReconstructed(outputs.getFirst(), mapping(0, 0.9),
                    new short[]{9575, 0, 14742, 9575, -1754, -4383, 9575, 17372, 0},
                    new long[]{1L << 24, 1L << 27, 1L << 27},
                    new short[]{5845, 9702, 837, 2568, 12256, 1561, 0, 679, 15705});
        }

        @Test
        @DisplayName("describes its pictures as full range identity BT.2020 PQ and keeps the base layer's light levels")
        void description() {
            var level = new ContentLightLevel(1000, 400);
            var decoder = new DolbyVisionVideoDecoder(Codec.HEVC, null, true, highBitDepth(),
                    options -> new ScriptedDecoder(0, pts -> Yuv16Picture.allocate(16, 8, ChromaFormat.YUV420, 10, false,
                            BASE_COLOR.withContentLightLevel(level)), options));
            var color = run(decoder, packet(0, unit(profile5(0, 1.0)), true)).getFirst().color();
            assertEquals(MatrixCoefficients.IDENTITY, color.matrix());
            assertEquals(ColorRange.FULL, color.range());
            assertEquals(ColorPrimaries.BT2020, color.primaries());
            assertEquals(TransferCharacteristics.PQ, color.transfer());
            assertEquals(level, color.contentLightLevel().orElseThrow());
        }

        @Test
        @DisplayName("writes 8-bit pictures unless the options ask for the high bit depth, from a 10-bit base layer")
        void narrow() {
            var decoder = decoder(true, 0, VideoDecoderOptions.defaults());
            var output = run(decoder, packet(0, unit(profile5(0, 1.0)), true)).getFirst();
            assertEquals(8, output.depth());
            assertIpt(output, mapping(0, 1.0));
            assertTrue(created.getFirst().options.highBitDepth());
            assertTrue(created.getFirst().options.dolbyVisionBaseLayer());
        }

        @Test
        @DisplayName("gives the same pictures with any thread count")
        void threads() {
            var serial = run(decoder(true, 0, VideoDecoderOptions.builder().highBitDepth(true).threads(1).build()),
                    packet(0, unit(profile5(0, 0.9)), true)).getFirst();
            var parallel = run(decoder(true, 0, VideoDecoderOptions.builder().highBitDepth(true).threads(4).build()),
                    packet(0, unit(profile5(0, 0.9)), true)).getFirst();
            Tolerance.exact().assertWithin("green", serial.g(), parallel.g());
            Tolerance.exact().assertWithin("blue", serial.b(), parallel.b());
            Tolerance.exact().assertWithin("red", serial.r(), parallel.r());
        }

        @Test
        @DisplayName("refuses a base layer that is not 10-bit 4:2:0")
        void wrongBase() {
            var eight = new DolbyVisionVideoDecoder(Codec.HEVC, null, true, highBitDepth(),
                    options -> new ScriptedDecoder(0, pts -> YuvPicture.allocate(16, 8, ChromaFormat.YUV420, false,
                            BASE_COLOR), options));
            assertThrows(MediaException.Unsupported.class, () -> run(eight, packet(0, unit(profile5(0, 1.0)), true)));
            var deep = new DolbyVisionVideoDecoder(Codec.HEVC, null, true, highBitDepth(),
                    options -> new ScriptedDecoder(0, pts -> Yuv16Picture.allocate(16, 8, ChromaFormat.YUV444, 10, false,
                            BASE_COLOR), options));
            assertThrows(MediaException.Unsupported.class, () -> run(deep, packet(0, unit(profile5(0, 1.0)), true)));
        }

        @Test
        @DisplayName("follows a change of the picture size")
        void resize() {
            var decoder = new DolbyVisionVideoDecoder(Codec.HEVC, null, true, highBitDepth(),
                    options -> new ScriptedDecoder(0, pts -> DolbyVisionReconstructionTest.randomBase(
                            new SplittableRandom(pts), pts == 0 ? 16 : 34, pts == 0 ? 8 : 18), options));
            var frames = new ArrayDeque<int[]>();
            decoder.decode(packet(0, unit(profile5(0, 1.0)), true),
                    frame -> frames.add(new int[]{frame.picture().width(), frame.picture().height()}));
            decoder.decode(packet(1, unit(profile5(0, 1.0)), true),
                    frame -> frames.add(new int[]{frame.picture().width(), frame.picture().height()}));
            assertEquals(16, frames.peekFirst()[0]);
            assertEquals(18, frames.peekLast()[1]);
        }

        @Test
        @DisplayName("checks its allocation against the limits")
        void limits() {
            var limits = MediaLimits.builder().maxBytes(1024).build();
            var decoder = decoder(true, 0, VideoDecoderOptions.builder().limits(limits).highBitDepth(true).build());
            assertThrows(MediaException.LimitExceeded.class, () -> run(decoder, packet(0, unit(profile5(0, 1.0)), true)));
        }

        @Test
        @DisplayName("closes its base layer decoder")
        void close() {
            var decoder = decoder(true, 0, highBitDepth());
            decoder.close();
            assertTrue(created.getFirst().closed);
        }
    }

    @Nested
    @DisplayName("a stream that declares nothing")
    class Undeclared {
        private static void assertPlain(Output output) {
            var base = base((int) output.pts());
            assertEquals(10, output.depth());
            assertEquals(BASE_COLOR, output.color().withChromaSiting(BASE_COLOR.chromaSiting()));
            assertEquals(base.y()[0] & 0xFFFF, output.g()[0]);
        }

        private static Output plain(VideoFrame frame) {
            var picture = assertInstanceOf(Yuv16Picture.class, frame.picture());
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            return new Output(frame.pts(), picture.bitDepth(), new int[]{picture.y()[0] & 0xFFFF}, new int[0], new int[0],
                    picture.color(), frame.keyframe());
        }

        @Test
        @DisplayName("stays plain video with no RPU, its decoder not replaced")
        void none() {
            var decoder = decoder(false, 0, highBitDepth());
            var outputs = new ArrayList<Output>();
            decoder.decode(packet(0, new byte[]{0, 0, 0, 1, 0x40}, true), frame -> outputs.add(plain(frame)));
            decoder.decode(packet(1, new byte[]{0, 0, 0, 1, 0x40}, true), frame -> outputs.add(plain(frame)));
            assertPlain(outputs.get(0));
            assertPlain(outputs.get(1));
            assertEquals(1, created.size());
            assertTrue(created.getFirst().options.dolbyVisionBaseLayer());
        }

        @Test
        @DisplayName("reconstructs from the first packet when it holds a profile 5 RPU")
        void fromTheStart() {
            var outputs = run(decoder(false, 0, highBitDepth()), packet(0, unit(profile5(0, 0.9)), true));
            assertEquals(12, outputs.getFirst().depth());
            assertIpt(outputs.getFirst(), mapping(0, 0.9));
        }

        @Test
        @DisplayName("switches at the first key frame from the first profile 5 RPU, draining the plain pictures in order")
        void atKeyFrame() {
            var decoder = decoder(false, 1, highBitDepth());
            var pts = new ArrayList<Long>();
            var depths = new ArrayList<Integer>();
            Consumer<VideoFrame> sink = frame -> {
                pts.add(frame.pts());
                depths.add(frame.picture().bitDepth());
            };
            var empty = new byte[]{0, 0, 0, 1, 0x40};
            decoder.decode(packet(0, empty, true), sink);
            decoder.decode(packet(1, empty, false), sink);
            decoder.decode(packet(2, unit(profile5(0, 0.9)), false), sink);
            decoder.decode(packet(3, unit(profile5(0, 0.9)), false), sink);
            decoder.decode(packet(4, unit(profile5(1, 1.0)), true), sink);
            decoder.decode(packet(5, unit(profile5(1, 1.0)), false), sink);
            decoder.flush(sink);
            assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L), pts);
            assertEquals(List.of(10, 10, 10, 10, 12, 12), depths);
            assertEquals(2, created.size());
            assertTrue(created.getFirst().closed);
        }

        @Test
        @DisplayName("gives a key frame without an RPU the first RPU seen while waiting for it")
        void rpuBeforeKeyFrame() {
            var decoder = decoder(false, 0, highBitDepth());
            var outputs = new ArrayList<Output>();
            decoder.decode(packet(0, new byte[]{0, 0, 0, 1, 0x40}, true), frame -> outputs.add(plain(frame)));
            decoder.decode(packet(1, unit(profile5(0, 0.9)), false), frame -> outputs.add(plain(frame)));
            decoder.decode(packet(2, new byte[]{0, 0, 0, 1, 0x40}, true), frame -> outputs.add(copy(frame)));
            assertEquals(12, outputs.getLast().depth());
            assertIpt(outputs.getLast(), mapping(0, 0.9));
        }

        @Test
        @DisplayName("never reconstructs when the first valid RPU is of a backward compatible profile")
        void compatibleFirst() {
            var decoder = decoder(false, 0, highBitDepth());
            var outputs = new ArrayList<Output>();
            decoder.decode(packet(0, unit(DolbyVisionRpus.of(Kind.PROFILE_8)), true), frame -> outputs.add(plain(frame)));
            decoder.decode(packet(1, unit(profile5(0, 1.0)), true), frame -> outputs.add(plain(frame)));
            assertEquals(1, created.size());
            assertPlain(outputs.get(1));
        }

        @Test
        @DisplayName("never reconstructs a stream of more than one layer, whose RPU headers read as profile 5")
        void layered() {
            var vps = DolbyVisionRpus.appendNal(new byte[0], new byte[]{0x40, 0x01, 0x0C, 0x11, (byte) 0xFF});
            var decoder = decoder(false, 0, highBitDepth());
            var outputs = new ArrayList<Output>();
            decoder.decode(packet(0, DolbyVisionRpus.appendNal(vps, profile5(0, 1.0).hevcNal()), true),
                    frame -> outputs.add(plain(frame)));
            assertPlain(outputs.getFirst());
            assertEquals(1, created.size());
        }

        @Test
        @DisplayName("ignores a damaged RPU")
        void damaged() {
            var data = unit(profile5(0, 1.0));
            data[data.length - 2] ^= 0x41;
            var decoder = decoder(false, 0, highBitDepth());
            var outputs = new ArrayList<Output>();
            decoder.decode(packet(0, data, true), frame -> outputs.add(plain(frame)));
            assertPlain(outputs.getFirst());
            assertEquals(1, created.size());
        }

        @Test
        @DisplayName("hands the plain decoder's frames through untouched")
        void untouched() {
            var picture = base(0);
            var decoder = new DolbyVisionVideoDecoder(Codec.AV1, null, false, highBitDepth(),
                    options -> new ScriptedDecoder(0, pts -> picture, options));
            var seen = new ArrayList<Picture>();
            decoder.decode(packet(0, new byte[]{0x12, 0}, true), frame -> seen.add(frame.picture()));
            assertSame(picture, seen.getFirst());
        }
    }

    @Nested
    @DisplayName("an AV1 stream")
    class Av1 {
        @Test
        @DisplayName("reads the RPU of a metadata OBU")
        void metadataObu() {
            var decoder = new DolbyVisionVideoDecoder(Codec.AV1, null, true, highBitDepth(),
                    options -> new ScriptedDecoder(0, DolbyVisionVideoDecoderTest::base, options));
            var obu = profile5(0, 0.9).av1Obu();
            var unit = new byte[obu.length + 2];
            unit[0] = 0x12;
            System.arraycopy(obu, 0, unit, 2, obu.length);
            assertIpt(run(decoder, packet(0, unit, true)).getFirst(), mapping(0, 0.9));
        }
    }
}
