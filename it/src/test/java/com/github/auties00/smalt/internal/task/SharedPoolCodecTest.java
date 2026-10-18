package com.github.auties00.smalt.internal.task;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoder;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.Vp8Decoder;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the codecs that borrow the shared worker pool as a server would use them: many virtual
 * threads decoding and encoding at once get the same bytes as a lone caller, the pool never grows
 * past its limit, and a closed decoder or reader leaves no helper running its work.
 */
@DisplayName("codecs on the shared worker pool")
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SharedPoolCodecTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 368;

    private static List<byte[]> packets;
    private static byte[] animation;
    private static List<RgbaPicture> frames;

    @BeforeAll
    static void inputs() {
        var random = new SplittableRandom(7);
        packets = new ArrayList<>();
        for (var i = 0; i < 6; i++) {
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            fill(picture.y(), picture.yStride(), WIDTH, HEIGHT, random, i);
            fill(picture.u(), picture.uvStride(), WIDTH / 2, HEIGHT / 2, random, i + 1);
            fill(picture.v(), picture.uvStride(), WIDTH / 2, HEIGHT / 2, random, i + 2);
            packets.add(Vp8.encodeKeyFrame(picture, Vp8KeyFrameSettings.quality(40)));
        }
        frames = new ArrayList<>();
        for (var i = 0; i < 6; i++) {
            var picture = RgbaPicture.allocate(256, 256, true);
            var pixels = picture.pixels();
            for (var p = 0; p < 256 * 256; p++) {
                var x = p % 256;
                var y = p / 256;
                pixels[4 * p] = (byte) (x + 9 * i + random.nextInt(24));
                pixels[4 * p + 1] = (byte) (y * 2 + random.nextInt(24));
                pixels[4 * p + 2] = (byte) ((x ^ y) + i);
                pixels[4 * p + 3] = (byte) Math.clamp(400 - Math.hypot(x - 128, y - 128) * 3, 0, 255);
            }
            frames.add(picture);
        }
        animation = encodeAnimation();
    }

    private static void fill(byte[] plane, int stride, int width, int height, SplittableRandom random, int phase) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * stride + x] = (byte) (((x * 3 + y * 5 + phase * 17) & 0xFF) / 2 + random.nextInt(64));
            }
        }
    }

    private static byte[] encodeAnimation() {
        var encoder = new WebpAnimationEncoder(256, 256, WebpAnimationEncoderOptions.lossy(70));
        for (var frame : frames) {
            encoder.addFrame(frame, 100);
        }
        return encoder.finish();
    }

    private static long decodeVideo() {
        var track = VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).width(WIDTH)
                .height(HEIGHT)
                .build();
        var crc = new CRC32();
        try (var decoder = new Vp8Decoder(track)) {
            var pts = 0L;
            for (var packet : packets) {
                decoder.decode(new Packet(1, packet, pts, pts, 1, true), frame -> {
                    var picture = (YuvPicture) frame.picture();
                    for (var y = 0; y < HEIGHT; y++) {
                        crc.update(picture.y(), y * picture.yStride(), WIDTH);
                    }
                });
                pts++;
            }
        }
        return crc.getValue();
    }

    private static long decodeAnimation() {
        var crc = new CRC32();
        try (var reader = WebpDecoder.openAnimation(MediaSource.of(animation), ImageDecoderOptions.defaults())) {
            for (var current = reader.read(); current != null; current = reader.read()) {
                crc.update(((RgbaPicture) current.picture()).pixels());
            }
        }
        return crc.getValue();
    }

    private static List<Thread> workers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("smalt-worker-") && thread.isAlive())
                .toList();
    }

    @Test
    @DisplayName("many concurrent virtual-thread callers get the bytes of a lone caller, on a bounded pool")
    void concurrentCallers() throws Exception {
        var video = decodeVideo();
        var decoded = decodeAnimation();
        var encoded = new CRC32();
        encoded.update(encodeAnimation());
        var jobs = new ArrayList<Callable<Long>>();
        for (var i = 0; i < 48; i++) {
            jobs.add(switch (i % 3) {
                case 0 -> SharedPoolCodecTest::decodeVideo;
                case 1 -> SharedPoolCodecTest::decodeAnimation;
                default -> () -> {
                    var crc = new CRC32();
                    crc.update(encodeAnimation());
                    return crc.getValue();
                };
            });
        }
        // compensation helpers started by earlier tests can outlive their idle timeout on a
        // loaded machine; the bound below only holds for helpers this workload starts
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!workers().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        var peak = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Long>>();
            for (var job : jobs) {
                futures.add(executor.submit(job));
            }
            while (!futures.stream().allMatch(Future::isDone)) {
                peak = Math.max(peak, workers().size());
                Thread.sleep(5);
            }
            for (var i = 0; i < futures.size(); i++) {
                var expected = switch (i % 3) {
                    case 0 -> video;
                    case 1 -> decoded;
                    default -> encoded.getValue();
                };
                assertEquals(expected, futures.get(i).get(), "job " + i);
            }
        }
        assertTrue(peak <= ParallelBands.THREADS - 1, peak + " helpers alive at once");
    }

    @Test
    @DisplayName("a closed decoder or animation reader leaves no helper running its work")
    void closeLeavesNoBusyWorker() throws Exception {
        for (var round = 0; round < 5; round++) {
            decodeVideo();
            assertNoCodecWork();
            decodeAnimation();
            assertNoCodecWork();
        }
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!workers().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(List.of(), workers());
    }

    private static void assertNoCodecWork() {
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().startsWith("smalt-worker-")) {
                continue;
            }
            for (var frame : entry.getValue()) {
                var name = frame.getClassName();
                assertTrue(!name.contains(".video.vp8.") && !name.contains(".image.webp."),
                        entry.getKey().getName() + " still runs " + name);
            }
        }
    }
}
