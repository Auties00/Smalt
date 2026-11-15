package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1DecoderOptions;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the frame encoder through the coding tools of the predicted encoders beyond the plain
 * frame (palette blocks and whole-sample motion of screen content frames, super-resolution, and
 * frames coded at another size than their references), over every chroma layout and bit depth,
 * and requires every frame to decode to exactly the encoder's reconstruction: a reconstruction
 * that drifted from the decoder's would pass a comparison of two decoders but fails here.
 */
class FrameEncoderToolsTest {
    private static final int WIDTH = 128;

    private static final int HEIGHT = 72;

    private static final int FRAMES = 6;

    static Stream<Arguments> formats() {
        var out = new ArrayList<Arguments>();
        for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444,
                ChromaFormat.MONOCHROME)) {
            for (var depth : List.of(8, 10, 12)) {
                for (var good : List.of(false, true)) {
                    out.add(Arguments.of(format, depth, good));
                }
            }
        }
        return out.stream();
    }

    /** A page of dark glyph rows on white under a title bar, scrolled down one row per frame. */
    static List<YuvPicture> page(int width, int height, int frames) {
        var pageHeight = height + frames + 16;
        var luma = new byte[width * pageHeight];
        Arrays.fill(luma, (byte) 235);
        var random = new SplittableRandom(5);
        for (var line = 14; line + 9 < pageHeight; line += 11) {
            for (var x = 3; x + 8 < width - 3; x += 7) {
                for (var gy = 0; gy < 7; gy++) {
                    for (var gx = 0; gx < 5; gx++) {
                        if (random.nextInt(3) != 0) {
                            luma[(line + gy) * width + x + gx] = (byte) (random.nextInt(4) == 0 ? 90 : 16);
                        }
                    }
                }
            }
        }
        var out = new ArrayList<YuvPicture>();
        for (var f = 0; f < frames; f++) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    picture.y()[y * picture.yStride() + x] = y < 10 ? (byte) 41 : luma[(y + f) * width + x];
                }
            }
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    picture.u()[y * picture.uvStride() + x] = (byte) (y < 5 ? 240 : 128);
                    picture.v()[y * picture.uvStride() + x] = (byte) (y < 5 ? 110 : 128);
                }
            }
            out.add(picture);
        }
        return out;
    }

    static Picture convert(YuvPicture picture, ChromaFormat format, int depth) {
        return depth == 8 ? ImageOps.toYuv(picture, format, ColorInfo.BT709_LIMITED)
                : ImageOps.toYuv16(picture, format, ColorInfo.BT709_LIMITED, depth);
    }

    static SequenceHeader sequence(ChromaFormat format, int depth, boolean good) {
        var layout = SequenceHeader.layoutOf(format);
        return good
                ? FrameEncoder.sequenceHeader(WIDTH, HEIGHT, ColorInfo.BT709_LIMITED, 8, true, true, layout, depth)
                : FrameEncoder.sequenceHeader(WIDTH, HEIGHT, ColorInfo.BT709_LIMITED, 8, 1, layout, depth);
    }

    static FrameParams params(int i, int depth, boolean good) {
        var p = new FrameParams();
        p.key = i == 0;
        p.orderHint = i;
        p.qindex = 90 + 10 * (i % 3);
        p.refreshFlags = 1 << (i % 3);
        for (var r = 0; r < 7; r++) {
            p.refIdx[r] = (i + 2 - r % 3) % 3;
        }
        p.refSearch = good ? 0x7F : 1 | 1 << 3 | 1 << 6;
        p.allowHighPrecisionMv = true;
        p.interpFilter = i == 0 ? 0 : FrameHeader.FILTER_SWITCHABLE;
        p.txSwitchable = i > 0;
        p.goodSpeed = good && i > 0 ? 4 : 0;
        p.referenceSelect = good && i > 0;
        p.skipMode = good && i > 0;
        p.warpedMotion = good && i > 0;
        p.refFrameMvs = good && i > 0;
        p.loopFilterSearch = good && i > 0;
        p.cdefSearch = good && i > 0 ? 2 : 0;
        Av1RtEncoder.filterModel(p, p.key, p.qindex, depth);
        return p;
    }

    /** The coded units and the reconstruction of each frame with its size. */
    record Run(List<byte[]> units, List<int[]> recons) {
    }

    /**
     * Codes a sequence of frames, each frame encoder given by its index (a new instance continues
     * the stream of the previous one), and keeps the reconstruction of each frame.
     */
    static Run code(IntFunction<FrameEncoder> encoderOf, IntFunction<Picture> sourceOf, int depth, boolean good,
                    boolean screen, boolean integerMv) {
        var units = new ArrayList<byte[]>();
        var recons = new ArrayList<int[]>();
        FrameEncoder fe = null;
        for (var i = 0; i < FRAMES; i++) {
            var next = encoderOf.apply(i);
            if (next != fe) {
                if (fe != null) {
                    next.inherit(fe);
                    fe.close();
                }
                fe = next;
            }
            var p = params(i, depth, good);
            p.screenContent = screen;
            p.integerMv = integerMv && i % 2 == 1;
            fe.load(sourceOf.apply(i));
            units.add(fe.encode(p));
            var recon = fe.reference(i == 0 ? 0 : Integer.numberOfTrailingZeros(p.refreshFlags));
            recons.add(FrameEncoderFormatTest.samples(recon, recon.w, recon.h));
        }
        fe.close();
        return new Run(units, recons);
    }

    static void assertDecodes(Run run) {
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(Rational.MICROSECONDS).width(WIDTH)
                .height(HEIGHT).build();
        var decoded = new ArrayList<int[]>();
        try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().highBitDepth(true).build())) {
            for (var i = 0; i < run.units().size(); i++) {
                decoder.decode(new Packet(1, run.units().get(i), i, i, 1, false),
                        frame -> decoded.add(FrameEncoderFormatTest.samples(frame.picture())));
            }
        }
        assertEquals(run.units().size(), decoded.size(), "frames");
        for (var i = 0; i < decoded.size(); i++) {
            assertArrayEquals(run.recons().get(i), decoded.get(i), "frame " + i);
        }
    }

    static boolean anyDifferent(Run a, Run b) {
        for (var i = 0; i < a.recons().size(); i++) {
            if (!Arrays.equals(a.recons().get(i), b.recons().get(i))) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest(name = "{0} {1}-bit good {2}")
    @MethodSource("formats")
    @DisplayName("screen content frames with palettes and whole-sample motion decode to the reconstruction")
    void screenContent(ChromaFormat format, int depth, boolean good) {
        var pages = page(WIDTH, HEIGHT, FRAMES);
        IntFunction<Picture> sources = i -> convert(pages.get(i), format, depth);
        var seq = sequence(format, depth, good);
        seq.screenContentTools = SequenceHeader.ADAPTIVE;
        var fe = new FrameEncoder(WIDTH, HEIGHT, seq, MediaLimits.DEFAULT);
        var with = code(i -> fe, sources, depth, good, true, true);
        assertDecodes(with);
        var plain = new FrameEncoder(WIDTH, HEIGHT, sequence(format, depth, good), MediaLimits.DEFAULT);
        var without = code(i -> plain, sources, depth, good, false, false);
        assertDecodes(without);
        // the tools change the coding of the page: without them the reconstruction would be the same
        assertTrue(anyDifferent(with, without), "the screen content tools change no frame");
    }

    @ParameterizedTest(name = "{0} {1}-bit good {2}")
    @MethodSource("formats")
    @DisplayName("super-resolution frames decode to the upscaled reconstruction")
    void superResolution(ChromaFormat format, int depth, boolean good) {
        var pictures = Vp9TestVideo.synthesize(WIDTH, HEIGHT, FRAMES);
        var seq = sequence(format, depth, good);
        seq.superRes = true;
        var denominator = 9 + (depth + format.ordinal()) % 8;
        var fe = new FrameEncoder(WIDTH, HEIGHT, denominator, seq, MediaLimits.DEFAULT);
        assertEquals(FrameEncoder.codedWidth(WIDTH, denominator), fe.width, "coded width");
        var run = code(i -> fe, i -> convert(pictures.get(i), format, depth), depth, good, false, false);
        for (var recon : run.recons()) {
            assertEquals(FrameEncoderFormatTest.samples(convert(pictures.get(0), format, depth)).length, recon.length,
                    "the reconstruction has the picture width");
        }
        assertDecodes(run);
    }

    @ParameterizedTest(name = "{0} {1}-bit good {2}")
    @MethodSource("formats")
    @DisplayName("frames of another size than their references decode to the reconstruction")
    void scaledReferences(ChromaFormat format, int depth, boolean good) {
        var seq = sequence(format, depth, good);
        // full size, then 3/4 (predicting from full-size references), then full size again
        int[][] sizes = {{WIDTH, HEIGHT}, {WIDTH, HEIGHT}, {96, 54}, {96, 54}, {WIDTH, HEIGHT}, {WIDTH, HEIGHT}};
        var encoders = new FrameEncoder[FRAMES];
        for (var i = 0; i < FRAMES; i++) {
            encoders[i] = i > 0 && Arrays.equals(sizes[i], sizes[i - 1]) ? encoders[i - 1]
                    : new FrameEncoder(sizes[i][0], sizes[i][1], 8, seq, MediaLimits.DEFAULT);
        }
        var full = Vp9TestVideo.synthesize(WIDTH, HEIGHT, FRAMES);
        var small = Vp9TestVideo.synthesize(96, 54, FRAMES);
        var run = code(i -> encoders[i], i -> convert(sizes[i][0] == WIDTH ? full.get(i) : small.get(i), format, depth),
                depth, good, false, false);
        assertDecodes(run);
    }
}
