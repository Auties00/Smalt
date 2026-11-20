package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the good-quality frame coding of the engine on its own, without the rate control: a key
 * frame, a hidden alternate reference coded from a later picture, the frames between them and
 * the overlay that shows the alternate reference's picture, at every good-quality speed and at the
 * best quality (speed -1 in the parameters). Every
 * shown frame decodes with the module's decoder to the encoder's reconstruction, and FFmpeg's
 * decoders reproduce the stream byte for byte.
 */
@DisplayName("VP9 good-quality frames")
class Vp9GoodFrameTest {
    @TempDir
    Path temp;

    record Coded(List<byte[]> packets, byte[] reconstructions, double psnr, int intraSub8x8, int interSub8x8) {
    }

    /** Counts the blocks smaller than 8x8 of the frame just coded, intra and inter. */
    static void countSub8x8(Vp9RtEncoder enc, int[] counts) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ModeInfo, Boolean>());
        for (var mi : enc.miGrid) {
            if (mi != null && mi.sbType < Vp9Constants.BLOCK_8X8 && seen.add(mi)) {
                counts[mi.isInter() ? 1 : 0]++;
            }
        }
    }

    static Vp9EncPicture picture(Vp9RtEncoder enc, Object source) {
        var picture = new Vp9EncPicture(enc.width, enc.height, enc.ssX, enc.ssY, enc.bitDepth);
        if (source instanceof com.github.auties00.smalt.image.Yuv16Picture yuv16) {
            picture.copyFrom(yuv16, 1);
        } else {
            picture.copyFrom((com.github.auties00.smalt.image.YuvPicture) source, 1);
        }
        return picture;
    }

    /** Runs a task, failing with the stack of its thread when it overruns a deadline. */
    static <T> T watchdog(int seconds, java.util.concurrent.Callable<T> task) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.setDaemon(true);
        thread.start();
        thread.join(seconds * 1000L);
        if (thread.isAlive()) {
            var stack = new StringBuilder("timed out after " + seconds + " s at:");
            for (var element : thread.getStackTrace()) {
                stack.append(System.lineSeparator()).append("  ").append(element);
            }
            thread.interrupt();
            throw new AssertionError(stack.toString());
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
        if (failure.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }

    static Coded code(int width, int height, int ssX, int ssY, int bitDepth, int speed, int qindex, int threads,
                      boolean optimize) {
        var enc = new Vp9RtEncoder(width, height, ssX, ssY, bitDepth, 1, 0, threads, MediaLimits.DEFAULT);
        enc.good = true;
        enc.optimizeCoefficients = optimize;
        var format = Vp9FormatTest.inputFormat(ssX, ssY);
        List<?> sources = bitDepth > 8 ? Vp9TestVideo.synthesize16(width, height, 11, format, bitDepth)
                : Vp9TestVideo.synthesize(width, height, 11, format);
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        var packets = new ArrayList<byte[]>();
        var recon = new ByteArrayOutputStream();
        double psnrSum = 0;
        var shown = 0;
        var sub8x8 = new int[2];
        // coding order: key 0, hidden 8, 1..7, overlay 8, 9, 10
        int[] order = {0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        byte[] hidden = null;
        var hiddenSize = 0;
        for (var step = 0; step < order.length; step++) {
            var index = order[step];
            var key = step == 0;
            var arf = step == 1;
            var overlay = step == 9;
            enc.beginGoodFrame(picture(enc, sources.get(index)), key, !arf, !arf && !overlay, overlay, arf, overlay,
                    Math.max(speed, 0), speed < 0, arf || key ? 128 : 144, 400, step >= 2 && step <= 9);
            var q = arf ? Math.max(0, qindex - 40) : qindex;
            var size = enc.codeGoodFrame(q);
            var data = java.util.Arrays.copyOf(enc.buffer(), size);
            countSub8x8(enc, sub8x8);
            enc.commitGoodFrame();
            if (arf) {
                hidden = data;
                hiddenSize = size;
                decodeStep(decoder, data, step);
                assertNull(decoder.shownFrame(), "hidden frame shown");
                continue;
            }
            byte[] packet;
            if (hidden != null) {
                packet = Vp9Superframe.pack(new byte[][]{hidden, data}, new int[]{hiddenSize, size}, 2);
                hidden = null;
            } else {
                packet = data;
            }
            packets.add(packet);
            recon.writeBytes(Vp9FormatTest.planes(enc.recon));
            decodeStep(decoder, data, step);
            var frame = decoder.shownFrame();
            assertNotNull(frame, "frame " + index);
            Vp9FormatTest.compare(enc.recon, frame, index);
            psnrSum += psnr(enc, sources.get(index));
            shown++;
        }
        return new Coded(packets, recon.toByteArray(), psnrSum / shown, sub8x8[0], sub8x8[1]);
    }

    static void decodeStep(Vp9FrameDecoder decoder, byte[] data, int step) {
        try {
            decoder.decodePacket(data, 0, data.length);
        } catch (RuntimeException e) {
            throw new AssertionError("coding step " + step + " (" + data.length + " bytes) fails to decode", e);
        }
    }

    static double psnr(Vp9RtEncoder enc, Object source) {
        long sse = 0;
        var w = enc.width;
        var h = enc.height;
        var recon = enc.recon;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                int s;
                int r;
                if (source instanceof com.github.auties00.smalt.image.Yuv16Picture p) {
                    s = p.y()[y * p.yStride() + x] >> (enc.bitDepth - 8);
                    r = recon.planes16[0][y * recon.strides[0] + x] >> (enc.bitDepth - 8);
                } else {
                    var p = (com.github.auties00.smalt.image.YuvPicture) source;
                    s = p.y()[y * p.yStride() + x] & 0xFF;
                    r = recon.planes8[0][y * recon.strides[0] + x] & 0xFF;
                }
                sse += (long) (s - r) * (s - r);
            }
        }
        return sse == 0 ? 99 : 10 * Math.log10(255.0 * 255 * w * h / sse);
    }

    @ParameterizedTest(name = "{0}x{1} subsampling {2},{3}, {4} bits, speed {5}, q {6}, {7} threads, optimized {8}")
    @CsvSource({"64, 64, 1, 1, 8, 4, 100, 1, false", "96, 64, 1, 1, 8, 0, 80, 1, false",
            "96, 64, 1, 1, 8, 1, 120, 2, false", "103, 67, 1, 1, 8, 2, 60, 1, false",
            "96, 64, 1, 1, 8, 3, 160, 3, false", "130, 77, 1, 1, 8, 4, 100, 1, false",
            "80, 48, 0, 0, 8, 1, 90, 1, false", "97, 65, 1, 0, 8, 2, 70, 2, false",
            "70, 45, 0, 1, 8, 3, 110, 1, false", "96, 64, 1, 1, 10, 1, 90, 1, false",
            "65, 41, 0, 0, 12, 2, 100, 1, false", "64, 64, 1, 1, 8, 0, 0, 1, false",
            "96, 64, 1, 1, 8, 0, 80, 1, true", "103, 67, 1, 1, 8, 2, 60, 2, true",
            "80, 48, 0, 0, 8, 4, 120, 1, true", "96, 64, 1, 1, 10, 1, 90, 1, true",
            "65, 41, 0, 0, 12, 3, 100, 1, true", "64, 64, 1, 1, 8, 0, 0, 1, true",
            "96, 64, 1, 1, 8, -1, 80, 1, true", "80, 48, 0, 0, 10, -1, 100, 2, false"})
    @DisplayName("key, hidden alternate reference, inter and overlay frames decode to the reconstruction")
    void decodes(int width, int height, int ssX, int ssY, int bitDepth, int speed, int qindex, int threads,
                 boolean optimize) throws Exception {
        var start = System.nanoTime();
        var coded = watchdog(240, () -> code(width, height, ssX, ssY, bitDepth, speed, qindex, threads, optimize));
        System.out.println("good frames " + width + "x" + height + " speed " + speed + ": "
                + (System.nanoTime() - start) / 1_000_000 + " ms, psnr " + coded.psnr());
        assertTrue(coded.psnr() > 30, "mean psnr " + coded.psnr());
    }

    @ParameterizedTest(name = "speed {0}, {1} bits, subsampling {2},{3}")
    @CsvSource({"0, 8, 1, 1", "1, 8, 1, 1", "0, 10, 1, 1", "0, 8, 0, 0", "1, 8, 1, 0", "-1, 8, 1, 1"})
    @DisplayName("speeds 0 and 1 and the best quality code intra and inter blocks smaller than 8x8 that decode to the reconstruction")
    void sub8x8(int speed, int bitDepth, int ssX, int ssY) throws Exception {
        var coded = watchdog(240, () -> code(96, 64, ssX, ssY, bitDepth, speed, 40, 1, true));
        System.out.println("sub-8x8 blocks at speed " + speed + ": " + coded.intraSub8x8() + " intra, "
                + coded.interSub8x8() + " inter");
        assertTrue(coded.intraSub8x8() > 0, "intra blocks smaller than 8x8");
        assertTrue(coded.interSub8x8() > 0, "inter blocks smaller than 8x8");
    }

    @ParameterizedTest(name = "speed {0}")
    @CsvSource({"2", "3", "4"})
    @DisplayName("the faster speeds also code blocks smaller than 8x8 below 720p")
    void sub8x8Faster(int speed) throws Exception {
        var coded = watchdog(240, () -> code(96, 64, 1, 1, 8, speed, 40, 1, true));
        System.out.println("sub-8x8 blocks at speed " + speed + ": " + coded.intraSub8x8() + " intra, "
                + coded.interSub8x8() + " inter");
        assertTrue(coded.intraSub8x8() + coded.interSub8x8() > 0, "blocks smaller than 8x8");
    }

    @ParameterizedTest(name = "{0}x{1} subsampling {2},{3}, {4} bits, speed {5}, optimized {6}")
    @CsvSource({"96, 64, 1, 1, 8, 0, false", "103, 67, 1, 1, 8, 2, false", "80, 48, 0, 0, 8, 1, false",
            "70, 45, 1, 1, 10, 3, false", "97, 65, 1, 0, 8, 4, false", "96, 64, 1, 1, 8, 0, true",
            "70, 45, 1, 1, 10, 2, true", "65, 41, 0, 0, 12, 1, true", "96, 64, 1, 1, 8, -1, true"})
    @DisplayName("FFmpeg's VP9 decoders reproduce good-quality streams with hidden frames")
    void ffmpeg(int width, int height, int ssX, int ssY, int bitDepth, int speed, boolean optimize) throws Exception {
        Skip.unless(Vp9RtInteropTest.ffmpegAvailable(), "oracle:ffmpeg", "ffmpeg not available");
        var coded = watchdog(240, () -> code(width, height, ssX, ssY, bitDepth, speed, 100, 1, optimize));
        var ivf = temp.resolve("stream.ivf");
        Files.write(ivf, Vp9RtQualityTool.ivf(width, height, coded.packets()));
        var layout = ssX == 1 ? (ssY == 1 ? "yuv420p" : "yuv422p") : (ssY == 1 ? "yuv440p" : "yuv444p");
        var pixelFormat = bitDepth > 8 ? layout + bitDepth + "le" : layout;
        var want = Vp9RtInteropTest.md5(coded.reconstructions());
        assertEquals(want, Vp9RtInteropTest.md5(Vp9FormatTest.decode(ivf, "vp9", pixelFormat)), "ffmpeg vp9");
        assertEquals(want, Vp9RtInteropTest.md5(Vp9FormatTest.decode(ivf, "libvpx-vp9", pixelFormat)), "libvpx");
    }
}
