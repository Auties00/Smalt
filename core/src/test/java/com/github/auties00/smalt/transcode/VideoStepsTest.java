package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.field.FieldOps;
import com.github.auties00.smalt.internal.field.Plane;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the frame steps of a video transcode against their definitions with a recording receiver:
 * 2:3 pulldown produces the AA BB BC CD DD cadence at five fourths of the rate, inverse telecine recovers
 * the film frames bit for bit at four fifths of it whatever the cadence phase, deinterlacing at the field rate
 * splits the timing, and the constant-rate conversion drops, repeats and blends frames at the right ticks.
 */
@DisplayName("Video transcode steps")
class VideoStepsTest {
    static final Rational TIME_BASE = new Rational(1, 90_000);

    record Received(Picture picture, long time, long duration, FieldOrder scan) {
    }

    static final class Recorder implements FrameSink {
        final List<Received> frames = new ArrayList<>();
        long end = -1;

        @Override
        public void accept(Picture picture, long time, long duration, FieldOrder scan) {
            frames.add(new Received(PictureCopy.copy(picture, null), time, duration, scan));
        }

        @Override
        public void finish(long end) {
            this.end = end;
        }
    }

    /**
     * A smooth film frame: a vertical gradient with a bright bar that moves right by 5 pixels a frame and a
     * per-frame brightness offset, so that consecutive frames differ and frames woven from two of them comb.
     */
    static YuvPicture film(int index) {
        var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 64; x++) {
                var bar = x >= 8 + 5 * (index % 8) && x < 16 + 5 * (index % 8);
                picture.y()[y * picture.yStride() + x] = (byte) (bar ? 220 : 40 + y + index);
            }
        }
        for (var index2 = 0; index2 < picture.u().length; index2++) {
            picture.u()[index2] = (byte) (100 + index);
            picture.v()[index2] = (byte) (150 - index);
        }
        return picture;
    }

    static byte[][] planes(Picture picture) {
        var yuv = (YuvPicture) picture;
        return new byte[][]{yuv.y().clone(), yuv.u().clone(), yuv.v().clone()};
    }

    static YuvPicture weave(Picture top, Picture bottom) {
        var target = (YuvPicture) PictureCopy.copy(top, null);
        var tops = Plane.of(top);
        var bottoms = Plane.of(bottom);
        var targets = Plane.of(target);
        for (var index = 0; index < targets.length; index++) {
            FieldOps.weave(tops[index], bottoms[index], targets[index]);
        }
        return target;
    }

    static List<Received> telecine(int frames, Rational rate) {
        var recorder = new Recorder();
        var step = new TelecineStep(recorder, rate, TIME_BASE);
        var tick = rate.invert();
        for (var index = 0; index < frames; index++) {
            var time = tick.rescale(index, TIME_BASE);
            step.accept(film(index), time, tick.rescale(index + 1, TIME_BASE) - time, FieldOrder.PROGRESSIVE);
        }
        step.finish(tick.rescale(frames, TIME_BASE));
        return recorder.frames;
    }

    @Nested
    @DisplayName("2:3 pulldown")
    class Telecine {
        @Test
        @DisplayName("turns four film frames into AA BB BC CD DD at five fourths of the rate")
        void cadence() {
            var frames = telecine(24, new Rational(24000, 1001));
            assertEquals(30, frames.size());
            var outTick = new Rational(1001, 30000);
            for (var group = 0; group < 6; group++) {
                var a = film(4 * group);
                var b = film(4 * group + 1);
                var c = film(4 * group + 2);
                var d = film(4 * group + 3);
                Picture[] expected = {a, b, weave(b, c), weave(c, d), d};
                for (var index = 0; index < 5; index++) {
                    var frame = frames.get(5 * group + index);
                    assertArrayEquals(planes(expected[index]), planes(frame.picture()), "frame " + (5 * group + index));
                    assertEquals(outTick.rescale(5L * group + index, TIME_BASE), frame.time());
                    assertEquals(FieldOrder.TOP_FIRST, frame.scan());
                }
            }
        }

        @Test
        @DisplayName("ends a cadence cut after the mixed frame with the whole frame")
        void partial() {
            var frames = telecine(3, new Rational(24, 1));
            assertEquals(4, frames.size());
            assertArrayEquals(planes(film(2)), planes(frames.get(3).picture()));
        }
    }

    @Nested
    @DisplayName("inverse telecine")
    class Inverse {
        static List<Received> inverse(List<Received> input, Rational rate) {
            var recorder = new Recorder();
            var step = new FieldMatcher(recorder, rate, TIME_BASE);
            for (var frame : input) {
                step.accept(frame.picture(), frame.time(), frame.duration(), frame.scan());
            }
            step.finish(input.getLast().time() + input.getLast().duration());
            return recorder.frames;
        }

        @Test
        @DisplayName("recovers every film frame bit for bit at four fifths of the rate")
        void roundTrip() {
            var telecined = telecine(24, new Rational(24000, 1001));
            var frames = inverse(telecined, new Rational(30000, 1001));
            assertEquals(24, frames.size());
            var tick = new Rational(1001, 24000);
            for (var index = 0; index < 24; index++) {
                assertArrayEquals(planes(film(index)), planes(frames.get(index).picture()), "film frame " + index);
                assertEquals(tick.rescale(index, TIME_BASE), frames.get(index).time(), "time of " + index);
                assertEquals(FieldOrder.PROGRESSIVE, frames.get(index).scan());
            }
        }

        @Test
        @DisplayName("finds the cadence wherever the stream starts in it")
        void phase() {
            var telecined = telecine(40, new Rational(24000, 1001));
            for (var skip = 1; skip < 5; skip++) {
                var frames = inverse(telecined.subList(skip, telecined.size()), new Rational(30000, 1001));
                var received = 50 - skip;
                assertEquals(received - received / 5, frames.size(), "skip " + skip);
                var films = new ArrayList<byte[][]>();
                for (var index = 0; index < 40; index++) {
                    films.add(planes(film(index)));
                }
                var matched = 0;
                for (var frame : frames) {
                    var picture = planes(frame.picture());
                    if (films.stream().anyMatch(film -> Arrays.deepEquals(film, picture))) {
                        matched++;
                    }
                }
                assertTrue(matched >= frames.size() - 2, "skip " + skip + ": " + matched + " of " + frames.size());
            }
        }

        /**
         * A 16-bit RGBA film frame: the moving bar of {@link #film} on a flat background, with a fixed noise
         * below 1/256 of the sample range that thresholds meant for 8-bit samples would take for combing.
         */
        static Rgba16Picture deepFilm(int index) {
            var picture = Rgba16Picture.allocate(64, 48, false);
            var random = new Random(index);
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    var bar = x >= 8 + 5 * (index % 8) && x < 16 + 5 * (index % 8);
                    var value = (bar ? 220 : 40 + index) * 257 + random.nextInt(200);
                    var at = y * picture.stride() + 4 * x;
                    picture.pixels()[at] = (short) value;
                    picture.pixels()[at + 1] = (short) value;
                    picture.pixels()[at + 2] = (short) value;
                    picture.pixels()[at + 3] = (short) 0xFFFF;
                }
            }
            return picture;
        }

        @Test
        @DisplayName("recovers 16-bit RGBA film frames, judging combing at their depth")
        void deep() {
            var recorder = new Recorder();
            var rate = new Rational(24, 1);
            var telecine = new TelecineStep(recorder, rate, TIME_BASE);
            var tick = rate.invert();
            for (var index = 0; index < 24; index++) {
                var time = tick.rescale(index, TIME_BASE);
                telecine.accept(deepFilm(index), time, tick.rescale(index + 1, TIME_BASE) - time, FieldOrder.PROGRESSIVE);
            }
            telecine.finish(tick.rescale(24, TIME_BASE));
            var frames = inverse(recorder.frames, new Rational(30, 1));
            assertEquals(24, frames.size());
            for (var index = 0; index < 24; index++) {
                var frame = frames.get(index);
                assertArrayEquals(deepFilm(index).pixels(), ((Rgba16Picture) frame.picture()).pixels(), "film frame " + index);
                assertEquals(FieldOrder.PROGRESSIVE, frame.scan(), "scan of " + index);
            }
        }

        @Test
        @DisplayName("ends the output timeline after the last frame of a group cut short")
        void partialGroup() {
            var telecined = telecine(6, new Rational(24, 1));
            var recorder = new Recorder();
            var step = new FieldMatcher(recorder, new Rational(30, 1), TIME_BASE);
            for (var frame : telecined) {
                step.accept(frame.picture(), frame.time(), frame.duration(), frame.scan());
            }
            step.finish(telecined.getLast().time() + telecined.getLast().duration());
            var last = recorder.frames.getLast();
            assertTrue(recorder.end >= last.time() + last.duration(), recorder.end + " before " + (last.time() + last.duration()));
        }

        @Test
        @DisplayName("leaves frames of true interlaced motion combed, with their scan, for a deinterlacer")
        void interlacedMotion() {
            var input = new ArrayList<Received>();
            for (var index = 0; index < 10; index++) {
                input.add(new Received(weave(film(2 * index), film(2 * index + 1)), index * 3003L, 3003,
                        FieldOrder.TOP_FIRST));
            }
            var frames = inverse(input, new Rational(30000, 1001));
            assertEquals(8, frames.size());
            assertTrue(frames.stream().allMatch(frame -> frame.scan() == FieldOrder.TOP_FIRST));
            var recorder = new Recorder();
            var deinterlace = new DeinterlaceStep(recorder, Deinterlacer.ELA, false);
            for (var frame : frames) {
                deinterlace.accept(frame.picture(), frame.time(), frame.duration(), frame.scan());
            }
            for (var frame : recorder.frames) {
                var luma = Plane.of(frame.picture())[0];
                assertEquals(0, FieldOps.combedSamples(luma, luma, 16));
            }
        }
    }

    @Nested
    @DisplayName("deinterlacing")
    class Deinterlace {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Deinterlacer.class)
        @DisplayName("makes a frame of each field at the field rate, in display order")
        void fieldRate(Deinterlacer method) {
            var woven = weave(film(0), film(1));
            for (var order : List.of(FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST)) {
                var recorder = new Recorder();
                var step = new DeinterlaceStep(recorder, method, true);
                step.accept(woven, 6000, 3000, order);
                assertEquals(2, recorder.frames.size());
                assertEquals(6000, recorder.frames.get(0).time());
                assertEquals(1500, recorder.frames.get(0).duration());
                assertEquals(7500, recorder.frames.get(1).time());
                var constant = switch (method) {
                    case BOB -> FieldOps.BOB;
                    case LINEAR -> FieldOps.LINEAR;
                    case ELA -> FieldOps.ELA;
                };
                for (var field = 0; field < 2; field++) {
                    var bottom = (field == 0) == (order == FieldOrder.BOTTOM_FIRST);
                    var expected = (YuvPicture) PictureCopy.copy(woven, null);
                    var sources = Plane.of(woven);
                    var targets = Plane.of(expected);
                    for (var plane = 0; plane < 3; plane++) {
                        FieldOps.deinterlace(sources[plane], targets[plane], bottom, constant);
                    }
                    assertArrayEquals(planes(expected), planes(recorder.frames.get(field).picture()));
                    assertEquals(FieldOrder.PROGRESSIVE, recorder.frames.get(field).scan());
                }
            }
        }

        @Test
        @DisplayName("passes progressive frames through, twice at the field rate")
        void progressive() {
            var picture = film(3);
            var recorder = new Recorder();
            var frameRate = new DeinterlaceStep(new FrameSink() {
                @Override
                public void accept(Picture received, long time, long duration, FieldOrder scan) {
                    assertSame(picture, received);
                    recorder.accept(received, time, duration, scan);
                }

                @Override
                public void finish(long end) {
                }
            }, Deinterlacer.LINEAR, false);
            frameRate.accept(picture, 0, 3000, FieldOrder.PROGRESSIVE);
            assertEquals(1, recorder.frames.size());
            var doubled = new Recorder();
            new DeinterlaceStep(doubled, Deinterlacer.LINEAR, true).accept(picture, 0, 3000, FieldOrder.PROGRESSIVE);
            assertEquals(List.of(0L, 1500L), doubled.frames.stream().map(Received::time).toList());
        }
    }

    @Nested
    @DisplayName("constant-rate conversion")
    class Rate {
        static Recorder convert(FrameRateConversion conversion, long end) {
            var recorder = new Recorder();
            var step = new RateStep(recorder, new Rational(30, 1), TIME_BASE, conversion);
            // 20 frames per second: one frame every 4500 ticks
            for (var index = 0; index < 4; index++) {
                step.accept(film(index), index * 4500L, 4500, FieldOrder.PROGRESSIVE);
            }
            step.finish(end);
            return recorder;
        }

        @Test
        @DisplayName("shows at each tick the last frame at or before it, up to the end")
        void dropRepeat() {
            var recorder = convert(FrameRateConversion.DROP_REPEAT, 18000);
            assertEquals(List.of(0L, 3000L, 6000L, 9000L, 12000L, 15000L),
                    recorder.frames.stream().map(Received::time).toList());
            int[] shown = {0, 0, 1, 2, 2, 3};
            for (var index = 0; index < shown.length; index++) {
                assertArrayEquals(planes(film(shown[index])), planes(recorder.frames.get(index).picture()),
                        "tick " + index);
            }
        }

        @Test
        @DisplayName("blends the frames on either side of a tick by their distance")
        void blend() {
            var recorder = convert(FrameRateConversion.BLEND, 18000);
            assertEquals(6, recorder.frames.size());
            // tick times 0, 3000, 6000, 9000, 12000, 15000 against frames at 0, 4500, 9000, 13500
            int[][] pairs = {{0, 0, 0}, {0, 1, 171}, {1, 2, 85}, {2, 2, 0}, {2, 3, 171}, {3, 3, 0}};
            for (var index = 0; index < pairs.length; index++) {
                var a = film(pairs[index][0]);
                var expected = (YuvPicture) PictureCopy.copy(a, null);
                if (pairs[index][2] != 0) {
                    var sa = Plane.of(a);
                    var sb = Plane.of(film(pairs[index][1]));
                    var st = Plane.of(expected);
                    for (var plane = 0; plane < 3; plane++) {
                        FieldOps.blend(sa[plane], sb[plane], pairs[index][2], st[plane]);
                    }
                }
                assertArrayEquals(planes(expected), planes(recorder.frames.get(index).picture()), "tick " + index);
            }
            assertFalse(Arrays.deepEquals(planes(film(0)), planes(recorder.frames.get(1).picture())));
        }

        @Test
        @DisplayName("shows the first frame at the ticks before it")
        void lateStart() {
            var recorder = new Recorder();
            var step = new RateStep(recorder, new Rational(30, 1), TIME_BASE, FrameRateConversion.BLEND);
            step.accept(film(5), 7000, 3000, FieldOrder.PROGRESSIVE);
            step.finish(10000);
            assertEquals(List.of(0L, 3000L, 6000L, 9000L), recorder.frames.stream().map(Received::time).toList());
            for (var frame : recorder.frames) {
                assertArrayEquals(planes(film(5)), planes(frame.picture()));
            }
        }
    }
}
