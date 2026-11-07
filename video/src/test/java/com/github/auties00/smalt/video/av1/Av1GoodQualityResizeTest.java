package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.BitReader;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1EncTesting;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers good-quality changes of picture size: the lookahead keeps the pictures of the old size, frame groups end
 * at a change, sizes within the sequence header's continue the stream with inter frames that predict from scaled
 * references, sizes a reference cannot predict start a key frame coded with a size override, and larger sizes a new
 * sequence header. Every segment is a bilinear scaling of one master clip, so its frames must decode close to the
 * scaled input, and the streams must decode with dav1d and libaom exactly as with the module's decoder.
 */
class Av1GoodQualityResizeTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    /** The frames of each size: more than the lookahead, so a change arrives while it holds old pictures. */
    private static final int SEGMENT = 11;

    private static final int MASTER_WIDTH = 192;

    private static final int MASTER_HEIGHT = 112;

    /** A size of the stream and whether its first frame is a key frame. */
    record Segment(int width, int height, boolean key) {
    }

    /**
     * Shrinks, grows back within the sequence size, shrinks past what a reference can predict (48 is less than half
     * of 112), grows past the sequence size and shrinks again within the new one.
     */
    static final List<Segment> SEGMENTS = List.of(new Segment(160, 96, true), new Segment(120, 72, false),
            new Segment(160, 96, false), new Segment(112, 64, false), new Segment(48, 28, true),
            new Segment(104, 60, false), new Segment(192, 112, true), new Segment(176, 104, false));

    /** The index of the first segment of the second sequence header. */
    private static final int SECOND_SEQUENCE = 6;

    record Run(List<Packet> packets, List<Picture> sources, List<byte[]> configs) {
        int frames() {
            return sources.size();
        }
    }

    private static final Map<String, Run> RUNS = new ConcurrentHashMap<>();

    static Av1EncoderOptions options(int depth, Av1TimingInfo timing) {
        return Av1EncoderOptions.ofQuality(70).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(8)
                .pictureFormat(ChromaFormat.YUV420, depth).timeBase(TIME_BASE).timingInfo(timing).build();
    }

    static Run run(int depth, Av1TimingInfo timing) {
        return RUNS.computeIfAbsent(depth + "/" + timing, _ -> encode(options(depth, timing), depth));
    }

    static Run encode(Av1EncoderOptions options, int depth) {
        var total = SEGMENTS.size() * SEGMENT;
        var master = Vp9TestVideo.synthesize(MASTER_WIDTH, MASTER_HEIGHT, total);
        var packets = new ArrayList<Packet>();
        var sources = new ArrayList<Picture>();
        var configs = new ArrayList<byte[]>();
        var first = SEGMENTS.getFirst();
        try (var encoder = new Av1Encoder(first.width(), first.height(), options)) {
            var index = 0;
            for (var segment : SEGMENTS) {
                var before = packets.size();
                encoder.reconfigure(segment.width(), segment.height());
                assertEquals(before, packets.size(), "a size change flushes nothing");
                assertEquals(segment.width(), encoder.width());
                assertEquals(segment.height(), encoder.height());
                for (var i = 0; i < SEGMENT; i++, index++) {
                    var scaled = scale(master.get(index), segment.width(), segment.height());
                    var picture = depth == 8 ? scaled : widen(scaled, depth);
                    sources.add(picture);
                    encoder.encode(new VideoFrame(picture, index * 3000L, 3000, false), packets::add);
                }
                configs.add(encoder.codecConfig().orElseThrow());
            }
            encoder.flush(packets::add);
        }
        return new Run(packets, sources, configs);
    }

    /** Scales a 4:2:0 picture with bilinear interpolation between sample centers. */
    static YuvPicture scale(YuvPicture source, int width, int height) {
        var out = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        scalePlane(source.y(), source.yStride(), source.width(), source.height(), out.y(), out.yStride(), width,
                height);
        scalePlane(source.u(), source.uvStride(), source.chromaWidth(), source.chromaHeight(), out.u(),
                out.uvStride(), out.chromaWidth(), out.chromaHeight());
        scalePlane(source.v(), source.uvStride(), source.chromaWidth(), source.chromaHeight(), out.v(),
                out.uvStride(), out.chromaWidth(), out.chromaHeight());
        return out;
    }

    private static void scalePlane(byte[] in, int inStride, int inWidth, int inHeight, byte[] out, int outStride,
                                   int outWidth, int outHeight) {
        for (var y = 0; y < outHeight; y++) {
            var sy = Math.clamp((y + 0.5) * inHeight / outHeight - 0.5, 0, inHeight - 1);
            var y0 = (int) sy;
            var y1 = Math.min(y0 + 1, inHeight - 1);
            var fy = sy - y0;
            for (var x = 0; x < outWidth; x++) {
                var sx = Math.clamp((x + 0.5) * inWidth / outWidth - 0.5, 0, inWidth - 1);
                var x0 = (int) sx;
                var x1 = Math.min(x0 + 1, inWidth - 1);
                var fx = sx - x0;
                var top = (in[y0 * inStride + x0] & 0xFF) * (1 - fx) + (in[y0 * inStride + x1] & 0xFF) * fx;
                var bottom = (in[y1 * inStride + x0] & 0xFF) * (1 - fx) + (in[y1 * inStride + x1] & 0xFF) * fx;
                out[y * outStride + x] = (byte) Math.round(top * (1 - fy) + bottom * fy);
            }
        }
    }

    /** Widens an 8-bit picture to a higher depth, filling the new low bits with a position dither. */
    static Yuv16Picture widen(YuvPicture source, int depth) {
        var shift = depth - 8;
        var mask = (1 << shift) - 1;
        var out = Yuv16Picture.allocate(source.width(), source.height(), ChromaFormat.YUV420, depth, false,
                ColorInfo.BT601_LIMITED);
        for (var plane = 0; plane < 3; plane++) {
            var w = plane == 0 ? source.width() : source.chromaWidth();
            var h = plane == 0 ? source.height() : source.chromaHeight();
            var in = plane == 0 ? source.y() : plane == 1 ? source.u() : source.v();
            var inStride = plane == 0 ? source.yStride() : source.uvStride();
            var o = plane == 0 ? out.y() : plane == 1 ? out.u() : out.v();
            var outStride = plane == 0 ? out.yStride() : out.uvStride();
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    o[y * outStride + x] = (short) ((in[y * inStride + x] & 0xFF) << shift | ((x * 7 + y * 3) & mask));
                }
            }
        }
        return out;
    }

    static List<Picture> decode(Run run) {
        var first = SEGMENTS.getFirst();
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(TIME_BASE)
                .codecConfig(run.configs().getFirst()).width(first.width()).height(first.height()).build();
        var out = new ArrayList<Picture>();
        try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().highBitDepth(true).build())) {
            for (var p : run.packets()) {
                decoder.decode(p, frame -> out.add(Av1EncoderPictureFormatTest.copy(frame.picture())));
            }
        }
        return out;
    }

    /** The size the segment of a frame index has. */
    static Segment segmentOf(int frame) {
        return SEGMENTS.get(frame / SEGMENT);
    }

    @Nested
    @DisplayName("the stream")
    class Stream {
        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 10})
        @DisplayName("shows every frame at its size in display order, close to the scaled input")
        void decodes(int depth) {
            var run = run(depth, Av1TimingInfo.NONE);
            assertEquals(run.frames(), run.packets().size(), "packets");
            for (var i = 0; i < run.packets().size(); i++) {
                assertEquals(i * 3000L, run.packets().get(i).pts(), "packet " + i + " timestamp");
            }
            for (var s = 0; s < SEGMENTS.size(); s++) {
                var segment = SEGMENTS.get(s);
                assertEquals(segment.key(), run.packets().get(s * SEGMENT).keyframe(), "first frame of " + segment);
            }
            var decoded = decode(run);
            assertEquals(run.frames(), decoded.size(), "decoded frames");
            var psnr = Tolerance.psnr(35);
            for (var i = 0; i < decoded.size(); i++) {
                var source = run.sources().get(i);
                var picture = decoded.get(i);
                assertEquals(source.width(), picture.width(), "frame " + i + " width");
                assertEquals(source.height(), picture.height(), "frame " + i + " height");
                psnr.assertWithin("frame " + i + " of " + segmentOf(i), source, picture);
            }
        }

        @Test
        @DisplayName("keeps its sequence header until a size exceeds it, then carries a larger one")
        void sequenceHeaders() {
            var run = run(8, Av1TimingInfo.NONE);
            var first = Av1EncoderTimingTest.sequence(run.configs().getFirst());
            assertEquals(160, first.maxWidth);
            assertEquals(96, first.maxHeight);
            for (var s = 1; s < SECOND_SEQUENCE; s++) {
                assertArrayEquals(run.configs().getFirst(), run.configs().get(s), "after segment " + s);
            }
            assertFalse(Arrays.equals(run.configs().getFirst(), run.configs().get(SECOND_SEQUENCE)));
            assertArrayEquals(run.configs().get(SECOND_SEQUENCE), run.configs().getLast());
            var second = Av1EncoderTimingTest.sequence(run.configs().getLast());
            assertEquals(MASTER_WIDTH, second.maxWidth);
            assertEquals(MASTER_HEIGHT, second.maxHeight);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"NONE", "DECODER_MODEL"})
        @DisplayName("signals every size within the sequence's, predicting only from references it may scale")
        void conformance(String timingName) {
            var timing = Av1TimingInfo.valueOf(timingName);
            var run = run(8, timing);
            var scaledInter = 0;
            var shown = 0;
            SequenceHeader seq = null;
            var refs = new FrameHeader[8];
            for (var packet : run.packets()) {
                var data = packet.data();
                var pos = 0;
                while (pos < data.length) {
                    var header = data[pos++] & 0xFF;
                    var type = (header >> 3) & 15;
                    if ((header & 4) != 0) {
                        pos++;
                    }
                    var size = 0;
                    var shift = 0;
                    int b;
                    do {
                        b = data[pos++] & 0xFF;
                        size |= (b & 0x7F) << shift;
                        shift += 7;
                    } while ((b & 0x80) != 0);
                    var gb = new BitReader();
                    gb.init(data, pos, size);
                    if (type == 1) {
                        seq = new SequenceHeader();
                        assertTrue(seq.parse(gb, true), "sequence header parses");
                        assertEquals(timing != Av1TimingInfo.NONE, seq.timingInfoPresent, "timing info");
                    } else if (type == 3 || type == 6) {
                        var h = new FrameHeader();
                        assertTrue(h.parse(seq, gb, refs, false), "frame header parses");
                        var expected = segmentOf(shown);
                        if (h.showExistingFrame) {
                            var shownHeader = refs[h.existingFrameIdx];
                            assertEquals(expected.width(), shownHeader.upscaledWidth, "shown frame " + shown);
                            assertEquals(expected.height(), shownHeader.height, "shown frame " + shown);
                            shown++;
                        } else {
                            assertEquals(h.upscaledWidth != seq.maxWidth || h.height != seq.maxHeight,
                                    h.frameSizeOverride, "frame size override of frame " + shown);
                            assertTrue(h.upscaledWidth <= seq.maxWidth && h.height <= seq.maxHeight);
                            if (h.showFrame) {
                                assertEquals(expected.width(), h.upscaledWidth, "frame " + shown + " width");
                                assertEquals(expected.height(), h.height, "frame " + shown + " height");
                                shown++;
                            } else {
                                // a hidden frame belongs to the group of the frame shown next
                                assertEquals(expected.width(), h.upscaledWidth, "hidden frame width");
                                assertEquals(expected.height(), h.height, "hidden frame height");
                            }
                            if (h.frameType != FrameHeader.KEY) {
                                var scaled = false;
                                for (var i = 0; i < 7; i++) {
                                    var ref = refs[h.refidx[i]];
                                    // section 7.20 (reference frame scaling): every reference within 2x and 1/16
                                    assertTrue(2 * h.upscaledWidth >= ref.upscaledWidth
                                            && 2 * h.height >= ref.height
                                            && h.upscaledWidth <= 16 * ref.upscaledWidth
                                            && h.height <= 16 * ref.height, "reference " + i + " of frame " + shown);
                                    scaled |= ref.upscaledWidth != h.upscaledWidth || ref.height != h.height;
                                }
                                if (scaled) {
                                    scaledInter++;
                                    assertFalse(h.useRefFrameMvs, "projected motion with scaled references");
                                    assertFalse(h.switchableMotionMode, "warped motion with scaled references");
                                }
                            }
                            for (var s = 0; s < 8; s++) {
                                if ((h.refreshFrameFlags & (1 << s)) != 0) {
                                    refs[s] = h;
                                }
                            }
                        }
                    }
                    pos += size;
                }
            }
            assertEquals(run.frames(), shown, "shown frames");
            assertTrue(scaledInter >= 4, "inter frames predicting from scaled references: " + scaledInter);
        }

        @Test
        @DisplayName("decodes the same with and without decoder model timing")
        void timingNeutral() {
            var plain = decode(run(8, Av1TimingInfo.NONE));
            var timed = decode(run(8, Av1TimingInfo.DECODER_MODEL));
            assertEquals(plain.size(), timed.size());
            for (var i = 0; i < plain.size(); i++) {
                assertArrayEquals(Av1EncoderPictureFormatTest.raw(plain.get(i)),
                        Av1EncoderPictureFormatTest.raw(timed.get(i)), "frame " + i);
            }
        }
    }

    @Nested
    @DisplayName("the reference decoders")
    class References {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"libdav1d/8", "libaom-av1/8", "libdav1d/10", "libaom-av1/10"})
        @DisplayName("decode every frame at its size exactly as the module's decoder")
        void exact(String decoderAndDepth) throws IOException {
            Skip.unless(Oracle.find(Oracle.Tool.FFMPEG).isPresent(), "oracle:ffmpeg", "ffmpeg not available");
            var decoder = decoderAndDepth.substring(0, decoderAndDepth.indexOf('/'));
            var depth = Integer.parseInt(decoderAndDepth.substring(decoderAndDepth.indexOf('/') + 1));
            var run = run(depth, Av1TimingInfo.NONE);
            var units = new ArrayList<byte[]>();
            for (var p : run.packets()) {
                units.add(p.data());
            }
            var first = SEGMENTS.getFirst();
            var file = Scratch.directory("av1-good-resize").resolve("resize-" + depth + ".ivf");
            Files.write(file, Av1EncTesting.ivf(first.width(), first.height(), units));
            // without autoscale, ffmpeg passes every frame at its own size
            var reference = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-c:v",
                    decoder, "-i", file.toString(), "-map", "0:v:0", "-noautoscale", "-f", "rawvideo", "-pix_fmt",
                    depth == 8 ? "yuv420p" : "yuv420p10le", "-"));
            var ours = new ByteArrayOutputStream();
            for (var p : decode(run)) {
                ours.writeBytes(Av1EncoderPictureFormatTest.raw(p));
            }
            Tolerance.exact().assertWithin(decoder + " output", reference, ours.toByteArray());
        }
    }

    /**
     * A coding configuration resized through a sequence of sizes: each segment shrinks within the sequence size,
     * shrinks below half of a reference, or grows past the sequence size.
     */
    record Variant(String name, Av1EncoderOptions options, ChromaFormat format, int depth, boolean forceKeys,
                   boolean twoPass, int minPsnr, List<Segment> segments) {
        @Override
        public String toString() {
            return name;
        }
    }

    static java.util.stream.Stream<Variant> variants() {
        var quality = Av1EncoderOptions.ofQuality(70).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(6)
                .timeBase(TIME_BASE);
        var bitrate = Av1EncoderOptions.ofBitrate(400_000).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(6)
                .timeBase(TIME_BASE);
        // odd sizes: 57 is less than half of 121, and 131x77 exceeds the sequence size
        var odd = List.of(new Segment(121, 67, true), new Segment(95, 53, false), new Segment(57, 33, true),
                new Segment(131, 77, true), new Segment(117, 71, false));
        var even = List.of(new Segment(160, 96, true), new Segment(120, 72, false), new Segment(160, 96, false),
                new Segment(64, 40, true), new Segment(176, 104, true));
        return java.util.stream.Stream.of(
                new Variant("odd sizes", quality.build(), ChromaFormat.YUV420, 8, false, false, 33, odd),
                new Variant("super-resolution", quality.superResolution(12).build(), ChromaFormat.YUV420, 8, false,
                        false, 30, even),
                new Variant("screen content", quality.noSuperResolution().screenContent(Av1ScreenContent.ALWAYS)
                        .build(), ChromaFormat.YUV420, 8, false, false, 33, even),
                new Variant("4:4:4 12-bit", quality.screenContent(Av1ScreenContent.NEVER)
                        .pictureFormat(ChromaFormat.YUV444, 12).build(), ChromaFormat.YUV444, 12, false, false, 33, odd),
                new Variant("forced key frames", Av1EncoderOptions.ofQuality(70).toBuilder()
                        .speed(Av1EncoderSpeed.FAST).lookahead(6).timeBase(TIME_BASE).build(), ChromaFormat.YUV420, 8,
                        true, false, 33, even),
                new Variant("bitrate", bitrate.build(), ChromaFormat.YUV420, 8, false, false, 28, even),
                new Variant("two passes", bitrate.build(), ChromaFormat.YUV420, 8, false, true, 28, odd));
    }

    /** Scales a 4:2:0 master picture to a size and picture format. */
    static Picture scaleTo(YuvPicture source, int width, int height, ChromaFormat format, int depth) {
        var out = YuvPicture.allocate(width, height, format, false, ColorInfo.BT601_LIMITED);
        scalePlane(source.y(), source.yStride(), source.width(), source.height(), out.y(), out.yStride(), width,
                height);
        scalePlane(source.u(), source.uvStride(), source.chromaWidth(), source.chromaHeight(), out.u(),
                out.uvStride(), out.chromaWidth(), out.chromaHeight());
        scalePlane(source.v(), source.uvStride(), source.chromaWidth(), source.chromaHeight(), out.v(),
                out.uvStride(), out.chromaWidth(), out.chromaHeight());
        if (depth == 8) {
            return out;
        }
        var shift = depth - 8;
        var wide = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT601_LIMITED);
        for (var plane = 0; plane < 3; plane++) {
            var w = plane == 0 ? width : out.chromaWidth();
            var h = plane == 0 ? height : out.chromaHeight();
            var in = plane == 0 ? out.y() : plane == 1 ? out.u() : out.v();
            var inStride = plane == 0 ? out.yStride() : out.uvStride();
            var o = plane == 0 ? wide.y() : plane == 1 ? wide.u() : wide.v();
            var outStride = plane == 0 ? wide.yStride() : wide.uvStride();
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    o[y * outStride + x] = (short) ((in[y * inStride + x] & 0xFF) << shift
                            | ((x * 7 + y * 3) & ((1 << shift) - 1)));
                }
            }
        }
        return wide;
    }

    @Nested
    @DisplayName("every coding configuration")
    class Variants {
        private static final int FRAMES = 9;

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.av1.Av1GoodQualityResizeTest#variants")
        @DisplayName("resizes into streams the module's decoder, dav1d and libaom decode alike at every size")
        void resizes(Variant variant) throws IOException {
            var segments = variant.segments();
            var master = Vp9TestVideo.synthesize(MASTER_WIDTH, MASTER_HEIGHT, segments.size() * FRAMES);
            var first = segments.getFirst();
            var sources = new ArrayList<Picture>();
            for (var i = 0; i < segments.size() * FRAMES; i++) {
                var segment = segments.get(i / FRAMES);
                sources.add(scaleTo(master.get(i), segment.width(), segment.height(), variant.format(),
                        variant.depth()));
            }
            var options = variant.options();
            if (variant.twoPass()) {
                // the first pass measures the video at one size, the size it starts at
                var pass = new Av1FirstPass(first.width(), first.height(), options);
                for (var i = 0; i < sources.size(); i++) {
                    pass.analyze(scaleTo(master.get(i), first.width(), first.height(), variant.format(),
                            variant.depth()));
                }
                options = options.toBuilder().firstPassStatistics(pass.statistics()).build();
            }
            var packets = new ArrayList<Packet>();
            byte[] config;
            try (var encoder = new Av1Encoder(first.width(), first.height(), options)) {
                config = encoder.codecConfig().orElseThrow();
                for (var i = 0; i < sources.size(); i++) {
                    var source = sources.get(i);
                    encoder.reconfigure(source.width(), source.height());
                    if (variant.forceKeys() && i % FRAMES == 0) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(source, i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
            }
            assertEquals(sources.size(), packets.size(), "packets");
            for (var s = 0; s < segments.size(); s++) {
                var expected = segments.get(s).key() || variant.forceKeys();
                var packet = packets.get(s * FRAMES);
                if (expected) {
                    assertTrue(packet.keyframe(), "first frame of " + segments.get(s));
                } else if (!variant.forceKeys()) {
                    assertFalse(packet.keyframe(), "first frame of " + segments.get(s));
                }
            }
            var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(TIME_BASE).codecConfig(config)
                    .width(first.width()).height(first.height()).build();
            var decoded = new ArrayList<Picture>();
            try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().highBitDepth(true).build())) {
                for (var p : packets) {
                    decoder.decode(p, frame -> decoded.add(Av1EncoderPictureFormatTest.copy(frame.picture())));
                }
            }
            assertEquals(sources.size(), decoded.size(), "decoded frames");
            var psnr = Tolerance.psnr(variant.minPsnr());
            for (var i = 0; i < decoded.size(); i++) {
                var source = sources.get(i);
                assertEquals(source.width(), decoded.get(i).width(), "frame " + i + " width");
                assertEquals(source.height(), decoded.get(i).height(), "frame " + i + " height");
                psnr.assertWithin("frame " + i, source, decoded.get(i));
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
                return;
            }
            var units = new ArrayList<byte[]>();
            for (var p : packets) {
                units.add(p.data());
            }
            var file = Scratch.directory("av1-good-resize").resolve("variant-" + variant.name().replaceAll("\\W", "")
                    + ".ivf");
            Files.write(file, Av1EncTesting.ivf(first.width(), first.height(), units));
            var ours = new ByteArrayOutputStream();
            for (var p : decoded) {
                ours.writeBytes(Av1EncoderPictureFormatTest.raw(p));
            }
            var pixelFormat = (variant.format() == ChromaFormat.YUV444 ? "yuv444p" : "yuv420p")
                    + (variant.depth() == 8 ? "" : variant.depth() + "le");
            for (var decoder : List.of("libdav1d", "libaom-av1")) {
                var reference = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-c:v",
                        decoder, "-i", file.toString(), "-map", "0:v:0", "-noautoscale", "-f", "rawvideo",
                        "-pix_fmt", pixelFormat, "-"));
                Tolerance.exact().assertWithin(decoder + " output", reference, ours.toByteArray());
            }
        }
    }

    @Nested
    @DisplayName("the size change")
    class Change {
        @Test
        @DisplayName("may happen before any picture, twice in a row and back to the current size")
        void sequences() {
            var master = Vp9TestVideo.synthesize(MASTER_WIDTH, MASTER_HEIGHT, 12);
            var packets = new ArrayList<Packet>();
            var sizes = new ArrayList<int[]>();
            try (var encoder = new Av1Encoder(96, 64, options(8, Av1TimingInfo.NONE))) {
                encoder.reconfigure(80, 48);
                encoder.reconfigure(64, 40);
                for (var i = 0; i < 12; i++) {
                    if (i == 5) {
                        encoder.reconfigure(96, 64);
                        encoder.reconfigure(96, 64);
                    }
                    var picture = scale(master.get(i), encoder.width(), encoder.height());
                    sizes.add(new int[]{picture.width(), picture.height()});
                    encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
                var run = new Run(packets, List.of(), List.of(encoder.codecConfig().orElseThrow()));
                var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(TIME_BASE)
                        .codecConfig(run.configs().getFirst()).width(96).height(64).build();
                var decoded = new ArrayList<Picture>();
                try (var decoder = new Av1Decoder(track, Av1DecoderOptions.defaults())) {
                    for (var p : packets) {
                        decoder.decode(p, frame -> decoded.add(Av1EncoderPictureFormatTest.copy(frame.picture())));
                    }
                }
                assertEquals(12, decoded.size());
                for (var i = 0; i < 12; i++) {
                    assertEquals(sizes.get(i)[0], decoded.get(i).width(), "frame " + i);
                    assertEquals(sizes.get(i)[1], decoded.get(i).height(), "frame " + i);
                }
                // 64x40 lies within the 96x64 sequence, and 96x64 within 2x of it
                assertTrue(packets.getFirst().keyframe());
                assertFalse(packets.get(5).keyframe(), "growing back within the sequence size");
                assertEquals(96, Av1EncoderTimingTest.sequence(run.configs().getFirst()).maxWidth);
            }
        }

        @Test
        @DisplayName("starts the new sequence header of a larger size even when the size goes back before a picture")
        void growthUndone() {
            var master = Vp9TestVideo.synthesize(MASTER_WIDTH, MASTER_HEIGHT, 10);
            var packets = new ArrayList<Packet>();
            try (var encoder = new Av1Encoder(96, 64, options(8, Av1TimingInfo.NONE))) {
                var first = encoder.codecConfig().orElseThrow();
                for (var i = 0; i < 10; i++) {
                    if (i == 4) {
                        encoder.reconfigure(128, 80);
                        encoder.reconfigure(96, 64);
                    }
                    var picture = scale(master.get(i), 96, 64);
                    encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
                var last = encoder.codecConfig().orElseThrow();
                assertFalse(Arrays.equals(first, last));
                assertEquals(128, Av1EncoderTimingTest.sequence(last).maxWidth);
                assertEquals(10, packets.size());
                for (var i = 0; i < 10; i++) {
                    assertEquals(i == 0 || i == 4, packets.get(i).keyframe(), "frame " + i);
                }
                var run = new Run(packets, List.of(), List.of(first));
                var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(TIME_BASE)
                        .codecConfig(run.configs().getFirst()).width(96).height(64).build();
                var decoded = new ArrayList<Picture>();
                try (var decoder = new Av1Decoder(track, Av1DecoderOptions.defaults())) {
                    for (var p : packets) {
                        decoder.decode(p, frame -> decoded.add(Av1EncoderPictureFormatTest.copy(frame.picture())));
                    }
                }
                assertEquals(10, decoded.size());
                for (var i = 0; i < 10; i++) {
                    Tolerance.psnr(35).assertWithin("frame " + i, scale(master.get(i), 96, 64), decoded.get(i));
                }
            }
        }

        @Test
        @DisplayName("rejects pictures of the old size and invalid sizes, and a closed encoder")
        void errors() {
            var master = Vp9TestVideo.synthesize(64, 48, 1);
            var encoder = new Av1Encoder(64, 48, options(8, Av1TimingInfo.NONE));
            encoder.reconfigure(48, 32);
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new VideoFrame(master.getFirst(), 0, 3000, false), _ -> {
                    }));
            assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(0, 32));
            encoder.close();
            assertThrows(IllegalStateException.class, () -> encoder.reconfigure(64, 48));
        }
    }
}
