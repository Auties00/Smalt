package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a key frame request made from another thread while frames are being coded is never
 * lost: every request is followed by a key frame within the next two frames.
 */
@DisplayName("real-time VP8 key frame requests from another thread")
class Vp8KeyframeRequestTest {
    private static final long RUN_NANOS = 4_000_000_000L;

    @Test
    @DisplayName("every concurrent request yields a key frame")
    void neverLost() throws InterruptedException {
        var picture = YuvPicture.allocate(16, 16, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        Arrays.fill(picture.y(), (byte) 90);
        var options = Vp8EncoderOptions.builder().noPeriodicKeyframes().speed(Vp8EncoderSpeed.SPEED_16).build();
        var started = new AtomicLong(-1);
        var lastKey = new AtomicLong(-1);
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        try (var encoder = new Vp8Encoder(16, 16, options)) {
            var coder = Thread.ofPlatform().start(() -> {
                try {
                    for (long i = 0; !stop.get(); i++) {
                        started.set(i);
                        var packet = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, false));
                        if (packet.orElseThrow().keyframe()) {
                            lastKey.set(i);
                        }
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            var requests = 0L;
            var lost = 0L;
            var deadline = System.nanoTime() + RUN_NANOS;
            while (System.nanoTime() < deadline && failure.get() == null) {
                var before = started.get();
                encoder.requestKeyframe();
                requests++;
                while (lastKey.get() < before && failure.get() == null) {
                    if (started.get() > before + 2 && lastKey.get() < before) {
                        lost++;
                        break;
                    }
                    Thread.onSpinWait();
                }
            }
            stop.set(true);
            coder.join();
            assertNull(failure.get());
            assertTrue(requests > 0);
            assertEquals(0, lost, lost + " of " + requests + " requests lost");
        }
    }
}
