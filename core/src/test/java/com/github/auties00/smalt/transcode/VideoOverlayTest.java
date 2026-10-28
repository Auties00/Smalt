package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.ops.BlendMode;
import com.github.auties00.smalt.image.ops.CompositeOptions;
import com.github.auties00.smalt.image.ops.ImageCompositing;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.WatermarkOptions;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleFont;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleStyle;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the overlays drawn on video pictures: a mark placed directly on Y'CbCr planes matches the same mark
 * composited in RGB, other blend modes go through RGB without touching the pixels the mark leaves transparent,
 * standard-dynamic-range marks land at the reference white of PQ video, and burned-in cues show exactly from
 * their start to their end, stacked when several show at once.
 */
@DisplayName("Video overlays")
class VideoOverlayTest {
    static final ColorInfo FULL709 = ColorInfo.BT709_LIMITED.withRange(ColorRange.FULL);

    static YuvPicture frame(ChromaFormat format) {
        var source = RgbaPicture.allocate(64, 48, false);
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 64; x++) {
                var index = y * source.stride() + 4 * x;
                source.pixels()[index] = (byte) (40 + 3 * x);
                source.pixels()[index + 1] = (byte) (30 + 4 * y);
                source.pixels()[index + 2] = (byte) (200 - 2 * x);
                source.pixels()[index + 3] = (byte) 255;
            }
        }
        return ImageOps.toYuv(source, format, FULL709);
    }

    /** A 20x10 mark: opaque red on the left, half-transparent green in the middle, transparent on the right. */
    static RgbaPicture mark() {
        var mark = RgbaPicture.allocate(20, 10, true);
        for (var y = 0; y < 10; y++) {
            for (var x = 0; x < 20; x++) {
                var index = y * mark.stride() + 4 * x;
                var left = x < 8;
                var middle = x >= 8 && x < 14;
                mark.pixels()[index] = (byte) (left ? 230 : 20);
                mark.pixels()[index + 1] = (byte) (middle ? 220 : 30);
                mark.pixels()[index + 2] = (byte) 40;
                mark.pixels()[index + 3] = (byte) (left ? 255 : middle ? 128 : 0);
            }
        }
        return mark;
    }

    static int maxDifference(byte[] a, byte[] b) {
        var max = 0;
        for (var index = 0; index < a.length; index++) {
            max = Math.max(max, Math.abs((a[index] & 0xFF) - (b[index] & 0xFF)));
        }
        return max;
    }

    @Test
    @DisplayName("places a normally blended mark on 4:4:4 planes as compositing in RGB does")
    void direct444() {
        var frame = frame(ChromaFormat.YUV444);
        var options = CompositeOptions.builder().opacity(0.8).build();
        var overlay = new VideoOverlay(mark(), 13, 7, options, frame, ColorTransformOptions.defaults());
        var marked = (YuvPicture) PictureCopy.copy(frame, null);
        overlay.apply(marked);
        var expected = ImageOps.toYuv(ImageCompositing.composite(ImageOps.toRgba(frame), mark(), 13, 7, options),
                ChromaFormat.YUV444, FULL709);
        assertTrue(maxDifference(expected.y(), marked.y()) <= 2, "luma " + maxDifference(expected.y(), marked.y()));
        assertTrue(maxDifference(expected.u(), marked.u()) <= 2, "Cb " + maxDifference(expected.u(), marked.u()));
        assertTrue(maxDifference(expected.v(), marked.v()) <= 2, "Cr " + maxDifference(expected.v(), marked.v()));
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 64; x++) {
                var outside = x < 13 || x >= 13 + 14 || y < 7 || y >= 17;
                if (outside) {
                    var index = y * frame.yStride() + x;
                    assertEquals(frame.y()[index], marked.y()[index], "untouched luma at " + x + "," + y);
                    assertEquals(frame.u()[index], marked.u()[index], "untouched Cb at " + x + "," + y);
                }
            }
        }
    }

    @Test
    @DisplayName("blends 4:2:0 chroma with the mean coverage of the pixels each sample covers")
    void direct420() {
        var frame = frame(ChromaFormat.YUV420);
        var overlay = new VideoOverlay(mark(), 13, 7, CompositeOptions.defaults(), frame, ColorTransformOptions.defaults());
        var marked = (YuvPicture) PictureCopy.copy(frame, null);
        overlay.apply(marked);
        var expected444 = ImageOps.toYuv(ImageCompositing.composite(ImageOps.toRgba(frame), mark(), 13, 7,
                CompositeOptions.defaults()), ChromaFormat.YUV444, FULL709);
        assertTrue(maxDifference(expected444.y(), marked.y()) <= 2);
        // a chroma sample inside the opaque part takes the mark's chroma
        var inside = 6 * marked.uvStride() + 8;
        assertEquals(expected444.u()[12 * expected444.uvStride() + 16], marked.u()[inside], 2);
        // a chroma sample half covered by the mark's left edge (pixels 12 and 13) moves halfway
        var edge = 6 * marked.uvStride() + 6;
        var before = frame.u()[edge] & 0xFF;
        var markCb = expected444.u()[12 * expected444.uvStride() + 14] & 0xFF;
        assertEquals((before + markCb) / 2.0, marked.u()[edge] & 0xFF, 3);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = BlendMode.class, names = {"MULTIPLY", "SCREEN", "DIFFERENCE"})
    @DisplayName("composites other blend modes through RGB and leaves transparent pixels untouched")
    void throughRgb(BlendMode mode) {
        var frame = frame(ChromaFormat.YUV444);
        var options = CompositeOptions.of(mode);
        var overlay = new VideoOverlay(mark(), 30, 20, options, frame, ColorTransformOptions.defaults());
        var marked = (YuvPicture) PictureCopy.copy(frame, null);
        overlay.apply(marked);
        var expected = ImageOps.toYuv(ImageCompositing.composite(ImageOps.toRgba(frame), mark(), 30, 20, options),
                ChromaFormat.YUV444, FULL709);
        assertTrue(maxDifference(expected.y(), marked.y()) <= 2);
        for (var y = 20; y < 30; y++) {
            for (var x = 30 + 14; x < 30 + 20; x++) {
                var index = y * frame.yStride() + x;
                assertEquals(frame.y()[index], marked.y()[index], "transparent mark pixel at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("composites on RGB pictures as ImageCompositing.composite does")
    void rgb() {
        var frame = ImageOps.toRgba(frame(ChromaFormat.YUV444));
        var overlay = new VideoOverlay(mark(), -5, 40, CompositeOptions.defaults(), frame,
                ColorTransformOptions.defaults());
        var marked = (RgbaPicture) PictureCopy.copy(frame, null);
        overlay.apply(marked);
        var expected = (RgbaPicture) ImageCompositing.composite(frame, mark(), -5, 40, CompositeOptions.defaults());
        assertArrayEquals(expected.pixels(), marked.pixels());
    }

    @Test
    @DisplayName("composites marks of either depth on 8-bit and 16-bit RGB pictures, inside and across the edges")
    void rgbDepths() {
        var multiply = CompositeOptions.builder().mode(BlendMode.MULTIPLY).opacity(0.75).build();
        var deepMark = ImageOps.toSixteenBit(mark());
        for (var position : new int[][]{{10, 5}, {50, -3}, {-5, 40}}) {
            var x = position[0];
            var y = position[1];
            var frame = ImageOps.toRgba(frame(ChromaFormat.YUV444));
            var marked = (RgbaPicture) PictureCopy.copy(frame, null);
            new VideoOverlay(deepMark, x, y, multiply, frame, ColorTransformOptions.defaults()).apply(marked);
            var expected = (RgbaPicture) ImageCompositing.composite(frame, ImageOps.toRgba(deepMark), x, y, multiply);
            assertArrayEquals(expected.pixels(), marked.pixels(), "16-bit mark at " + x + "," + y);
            var deepFrame = ImageOps.toSixteenBit(frame);
            var deepMarked = (Rgba16Picture) PictureCopy.copy(deepFrame, null);
            new VideoOverlay(mark(), x, y, multiply, deepFrame, ColorTransformOptions.defaults()).apply(deepMarked);
            var deepExpected = (Rgba16Picture) ImageCompositing.composite(deepFrame, mark(), x, y, multiply);
            assertArrayEquals(deepExpected.pixels(), deepMarked.pixels(), "8-bit mark on 16 bits at " + x + "," + y);
        }
    }

    @Test
    @DisplayName("puts white marks at the reference white of PQ video")
    void hdr() {
        var color = VideoColorTest.HDR10;
        var frame = Yuv16Picture.allocate(32, 16, ChromaFormat.YUV420, 10, false, color);
        var white = RgbaPicture.allocate(8, 8, false);
        java.util.Arrays.fill(white.pixels(), (byte) 255);
        new VideoOverlay(white, 4, 4, CompositeOptions.defaults(), frame, ColorTransformOptions.defaults()).apply(frame);
        var expected = 64 + 876 * VideoColorTest.pq(203);
        assertEquals(expected, frame.y()[6 * frame.yStride() + 6] & 0xFFFF, 2);
        assertEquals(512, frame.u()[3 * frame.uvStride() + 3], 2);
    }

    static SubtitleFont font() {
        try (var stream = VideoOverlayTest.class.getResourceAsStream("/com/github/auties00/smalt/subtitle/smalt-test.ttf")) {
            return SubtitleFont.of(stream.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Shown(Picture picture, long time) {
    }

    static List<Shown> run(OverlayStep step, List<Shown> received, Picture frame, long... times) {
        for (var time : times) {
            step.accept(frame, time, 10, FieldOrder.PROGRESSIVE);
        }
        return received;
    }

    static OverlayStep step(List<Shown> received, List<SubtitleCue> cues, Picture watermark) {
        var sink = new FrameSink() {
            @Override
            public void accept(Picture picture, long time, long duration, FieldOrder scan) {
                received.add(new Shown(picture == null ? null : PictureCopy.copy(picture, null), time));
            }

            @Override
            public void finish(long end) {
            }
        };
        return new OverlayStep(sink, cues, SubtitleMarkup.SUBRIP, SubtitleStyle.builder(font()).size(0.15).build(),
                watermark, WatermarkOptions.defaults(), ColorTransformOptions.defaults(), new Rational(1, 1000), 1000);
    }

    /** The rows a picture differs from another in, as {first, last}, or {-1, -1}. */
    static int[] changedRows(YuvPicture original, Picture changed) {
        var other = (YuvPicture) changed;
        int first = -1, last = -1;
        for (var y = 0; y < original.height(); y++) {
            for (var x = 0; x < original.width(); x++) {
                if (original.y()[y * original.yStride() + x] != other.y()[y * other.yStride() + x]) {
                    if (first < 0) {
                        first = y;
                    }
                    last = y;
                }
            }
        }
        return new int[]{first, last};
    }

    @Test
    @DisplayName("shows a cue on the frames from its start to its end, on the input timeline")
    void cueTiming() {
        var frame = frame(ChromaFormat.YUV420);
        var received = new ArrayList<Shown>();
        // the kept range starts at one second, so frame time t is input time 1000 + t milliseconds
        var step = step(received, List.of(SubtitleCue.of(1_100_000, 1_200_000, "##")), null);
        run(step, received, frame, 50, 99, 100, 150, 199, 200, 300);
        var shown = received.stream().map(entry -> changedRows(frame, entry.picture())[0] >= 0).toList();
        assertEquals(List.of(false, false, true, true, true, false, false), shown);
    }

    @Test
    @DisplayName("stacks cues shown together upward from the bottom, in the order they start")
    void stacking() {
        var frame = frame(ChromaFormat.YUV420);
        var alone = new ArrayList<Shown>();
        run(step(alone, List.of(SubtitleCue.of(1_000_000, 2_000_000, "##")), null), alone, frame, 10);
        var both = new ArrayList<Shown>();
        run(step(both, List.of(SubtitleCue.of(1_000_000, 2_000_000, "##"), SubtitleCue.of(1_000_001, 2_000_000, "#")),
                null), both, frame, 10);
        var single = changedRows(frame, alone.getFirst().picture());
        var stacked = changedRows(frame, both.getFirst().picture());
        assertEquals(single[1], stacked[1], "the first cue keeps its place at the bottom");
        assertTrue(stacked[0] < single[0] - 5, "the second cue goes above it: " + stacked[0] + " vs " + single[0]);
    }

    @Test
    @DisplayName("passes frames with nothing to draw through and draws cues added during the stream")
    void lateCues() {
        var frame = frame(ChromaFormat.YUV420);
        var passed = new ArrayList<Picture>();
        var step = new OverlayStep(new FrameSink() {
            @Override
            public void accept(Picture picture, long time, long duration, FieldOrder scan) {
                passed.add(picture);
            }

            @Override
            public void finish(long end) {
            }
        }, new ArrayList<>(), SubtitleMarkup.SUBRIP, SubtitleStyle.of(font()), null, WatermarkOptions.defaults(),
                ColorTransformOptions.defaults(), new Rational(1, 1000), 0);
        step.accept(frame, 0, 10, FieldOrder.PROGRESSIVE);
        assertSame(frame, passed.getFirst());
        step.addCue(SubtitleCue.of(5_000, 50_000, "#"));
        step.accept(frame, 10, 10, FieldOrder.PROGRESSIVE);
        assertNotSame(frame, passed.get(1));
    }

    @Test
    @DisplayName("places the watermark on every frame as ImageCompositing.watermark does")
    void watermark() {
        var frame = frame(ChromaFormat.YUV444);
        var received = new ArrayList<Shown>();
        run(step(received, null, mark()), received, frame, 0, 10);
        var expected = ImageOps.toYuv(ImageCompositing.watermark(ImageOps.toRgba(frame), mark(),
                WatermarkOptions.defaults()), ChromaFormat.YUV444, FULL709);
        for (var shown : received) {
            assertTrue(maxDifference(expected.y(), ((YuvPicture) shown.picture()).y()) <= 2);
        }
    }
}
