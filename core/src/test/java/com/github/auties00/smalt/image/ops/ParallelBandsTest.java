package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the band scheduler and that operations large enough to run in bands on several threads
 * produce exactly the output of a sequential computation with the scalar kernels.
 */
class ParallelBandsTest {
    @Nested
    @DisplayName("scheduler")
    class Scheduler {
        @Test
        @DisplayName("runs every band exactly once, from platform and virtual threads")
        void everyBandOnce() throws Exception {
            for (var bands = 1; bands <= 40; bands++) {
                var runs = new AtomicIntegerArray(bands);
                ParallelBands.run(bands, runs::incrementAndGet);
                for (var i = 0; i < bands; i++) {
                    assertEquals(1, runs.get(i));
                }
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (var task = 0; task < 16; task++) {
                    futures.add(executor.submit(() -> {
                        var runs = new AtomicIntegerArray(9);
                        ParallelBands.run(9, runs::incrementAndGet);
                        for (var i = 0; i < 9; i++) {
                            assertEquals(1, runs.get(i));
                        }
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
            }
        }

        @Test
        @DisplayName("rethrows the failure of a band after every band has finished")
        void failure() {
            var runs = new AtomicIntegerArray(8);
            var thrown = new IllegalStateException("band 5");
            var caught = assertThrows(IllegalStateException.class, () -> ParallelBands.run(8, band -> {
                runs.incrementAndGet(band);
                if (band == 5) {
                    throw thrown;
                }
            }));
            assertSame(thrown, caught);
            for (var i = 0; i < 8; i++) {
                assertEquals(1, runs.get(i));
            }
        }

        @Test
        @DisplayName("runs a started computation exactly once and rethrows its failure on join")
        void pending() throws Exception {
            for (var round = 0; round < 500; round++) {
                var runs = new java.util.concurrent.atomic.AtomicInteger();
                var pending = ParallelBands.start(runs::incrementAndGet);
                if (round % 2 == 0) {
                    Thread.onSpinWait();
                }
                pending.join();
                assertEquals(1, runs.get());
            }
            var thrown = new IllegalStateException("pending");
            var failed = ParallelBands.start(() -> {
                throw thrown;
            });
            assertSame(thrown, assertThrows(IllegalStateException.class, failed::join));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (var task = 0; task < 16; task++) {
                    futures.add(executor.submit(() -> {
                        var runs = new java.util.concurrent.atomic.AtomicInteger();
                        ParallelBands.start(runs::incrementAndGet).join();
                        assertEquals(1, runs.get());
                    }));
                }
                for (var future : futures) {
                    future.get();
                }
            }
        }

        @Test
        @DisplayName("cancels a computation no thread has started and waits for one that has")
        void cancel() {
            var thrown = new IllegalStateException("canceled");
            for (var round = 0; round < 500; round++) {
                var started = new java.util.concurrent.atomic.AtomicBoolean();
                var finished = new java.util.concurrent.atomic.AtomicBoolean();
                var pending = ParallelBands.start(() -> {
                    started.set(true);
                    for (var spin = 0; spin < 1000; spin++) {
                        Thread.onSpinWait();
                    }
                    finished.set(true);
                    throw thrown;
                });
                pending.cancel();
                assertEquals(started.get(), finished.get());
                pending.cancel();
            }
        }

        @Test
        @DisplayName("splits units into contiguous bands covering every unit")
        void split() {
            for (var units = 1; units < 100; units++) {
                for (var bands = 1; bands <= Math.min(units, 9); bands++) {
                    assertEquals(0, ParallelBands.start(0, bands, units));
                    assertEquals(units, ParallelBands.start(bands, bands, units));
                    for (var band = 0; band < bands; band++) {
                        var size = ParallelBands.start(band + 1, bands, units) - ParallelBands.start(band, bands, units);
                        assertEquals(units / bands, size, 1);
                    }
                }
            }
            assertEquals(1, ParallelBands.bands(1000, 10));
            assertEquals(1, ParallelBands.bands(1, Long.MAX_VALUE));
        }
    }

    private static RgbaPicture picture(int width, int height, boolean alpha) {
        var random = new SplittableRandom(width * 31L + height);
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var i = 0; i < width * height; i++) {
            var x = i % width;
            var y = i / width;
            pixels[4 * i] = (byte) (x * 3 + random.nextInt(8));
            pixels[4 * i + 1] = (byte) (y * 5 + random.nextInt(8));
            pixels[4 * i + 2] = (byte) ((x ^ y) + random.nextInt(8));
            pixels[4 * i + 3] = alpha ? (byte) (x + 2 * y) : (byte) 255;
        }
        return picture;
    }

    private static byte[] referencePlane(byte[] src, int srcStride, int inWidth, int inHeight, int outWidth, int outHeight,
                                         ResizeFilter filter) {
        var kernel = new ScalarResampleKernel();
        var vertical = Resampler.Window.full(inHeight).weights(filter, inHeight, outHeight, Resampler.FIRST_BITS);
        var horizontal = Resampler.Window.full(inWidth).weights(filter, inWidth, outWidth, Resampler.SECOND_BITS);
        var out = new byte[outWidth * outHeight];
        var row = new int[inWidth];
        var samples = new byte[outWidth + 8];
        var offsets = new int[vertical.maxTaps()];
        for (var y = 0; y < outHeight; y++) {
            var taps = vertical.counts()[y];
            for (var k = 0; k < taps; k++) {
                offsets[k] = (vertical.starts()[y] + k) * srcStride;
            }
            kernel.vertical(src, offsets, vertical.weights(), y * vertical.stride(), taps, inWidth, row,
                    Resampler.FIRST_BITS - Resampler.INTERMEDIATE_BITS);
            kernel.horizontalRows(row, 1, horizontal.starts(), horizontal.counts(), horizontal.weights(), horizontal.stride(),
                    outWidth, samples, Resampler.SECOND_BITS + Resampler.INTERMEDIATE_BITS);
            System.arraycopy(samples, 0, out, y * outWidth, outWidth);
        }
        return out;
    }

    private static byte[] tight(byte[] plane, int stride, int width, int height) {
        var out = new byte[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ResizeFilter.class, names = {"BILINEAR", "BICUBIC", "LANCZOS3", "AREA"})
    @DisplayName("a banded 1080p luma resize equals the sequential scalar computation")
    void resizeLuma(ResizeFilter filter) {
        var yuv = ImageOps.toYuv(picture(1920, 1080, false), ChromaFormat.YUV420, ColorInfo.BT709_LIMITED);
        var resized = (YuvPicture) ImageOps.resize(yuv, 1280, 720, filter);
        assertArrayEquals(referencePlane(yuv.y(), yuv.yStride(), 1920, 1080, 1280, 720, filter),
                tight(resized.y(), resized.yStride(), 1280, 720));
    }

    @Test
    @DisplayName("a banded 12 MP to 100 px reduction equals the sequential scalar computation")
    void thumbnail() {
        var yuv = ImageOps.toYuv(picture(1600, 1200, false), ChromaFormat.YUV420, ColorInfo.JPEG);
        var resized = (YuvPicture) ImageOps.resize(yuv, 100, 75, ResizeFilter.LANCZOS3);
        assertArrayEquals(referencePlane(yuv.y(), yuv.yStride(), 1600, 1200, 100, 75, ResizeFilter.LANCZOS3),
                tight(resized.y(), resized.yStride(), 100, 75));
    }

    @Test
    @DisplayName("repeated banded resizes and conversions of transparent pictures are identical")
    void deterministic() {
        var rgba = picture(1500, 1100, true);
        var first = (RgbaPicture) ImageOps.resize(rgba, 997, 733, ResizeFilter.LANCZOS3);
        var yuv = ImageOps.toYuv(rgba, ChromaFormat.YUV420, ColorInfo.JPEG, ChromaDownsampling.ALPHA_WEIGHTED);
        var back = ImageOps.toRgba(yuv);
        for (var i = 0; i < 4; i++) {
            assertArrayEquals(first.pixels(), ((RgbaPicture) ImageOps.resize(rgba, 997, 733, ResizeFilter.LANCZOS3)).pixels());
            var again = ImageOps.toYuv(rgba, ChromaFormat.YUV420, ColorInfo.JPEG, ChromaDownsampling.ALPHA_WEIGHTED);
            assertArrayEquals(yuv.y(), again.y());
            assertArrayEquals(yuv.u(), again.u());
            assertArrayEquals(yuv.v(), again.v());
            assertArrayEquals(yuv.alpha(), again.alpha());
            assertArrayEquals(back.pixels(), ImageOps.toRgba(again).pixels());
        }
    }

    @Test
    @DisplayName("a banded 1080p conversion to RGBA equals the sequential scalar computation")
    void toRgba() {
        var yuv = ImageOps.toYuv(picture(1920, 1080, false), ChromaFormat.YUV420, ColorInfo.BT709_LIMITED);
        var rgba = ImageOps.toRgba(yuv);
        var matrix = ColorMatrix.of(yuv.color());
        var scalar = new ScalarColorKernel();
        var expected = new byte[4 * 1920];
        for (var row = 0; row < 1080; row++) {
            var near = row >> 1;
            var far = (row & 1) == 0 ? Math.max(near - 1, 0) : Math.min(near + 1, yuv.chromaHeight() - 1);
            scalar.yuvToRgba(yuv.y(), row * yuv.yStride(), yuv.u(), yuv.v(), near * yuv.uvStride(), far * yuv.uvStride(),
                    null, 0, expected, 0, 1920, ColorKernel.CHROMA_FANCY_HV, matrix);
            assertArrayEquals(expected, java.util.Arrays.copyOfRange(rgba.pixels(), row * rgba.stride(), row * rgba.stride() + 4 * 1920),
                    "row " + row);
        }
    }
}
