package com.github.auties00.smalt.video.av1.internal.encoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the key frame sizing, the scene change and post-encode drop steps and the resize carry-over
 * of the real-time AV1 rate control, on a 640x480 stream at 30 frames per second with the real-time
 * preset's buffer (1000 ms, 500 ms initial, 600 ms optimal).
 */
class RateControlTest {
    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    private static RateControl configured(long bitrate, int maxIntraPct, int dropWaterMark) {
        var rc = new RateControl();
        rc.configure(bitrate, 30, 8, 208, 1000, 500, 600, dropWaterMark, 0, maxIntraPct, WIDTH, HEIGHT, true);
        return rc;
    }

    private static void codeKey(RateControl rc, int bytes) {
        rc.frameTarget(true);
        var q = rc.pickQindex(true, 0, 0, 0);
        rc.postEncode(true, q, bytes);
    }

    @Nested
    @DisplayName("key frame targets")
    class KeyTargets {
        @Test
        @DisplayName("the first key frame takes half the initial buffer, capped by the key frame limit")
        void firstKey() {
            var free = configured(600_000, 0, 30);
            assertEquals(150_000, free.frameTarget(true));
            var capped = configured(600_000, 300, 30);
            assertEquals(60_000, capped.frameTarget(true));
        }

        @ParameterizedTest
        @CsvSource({"1, 150000", "2, 300000", "3, 600000"})
        @DisplayName("the first key frame of a layered stream grows with the layer count")
        void layered(int layers, int target) {
            var rc = configured(600_000, 0, 30);
            rc.temporalLayers = layers;
            assertEquals(target, rc.frameTarget(true));
        }

        @Test
        @DisplayName("a later key frame is boosted to 3.75 frames, then capped by the limit")
        void laterKey() {
            var free = configured(600_000, 0, 30);
            codeKey(free, 6_000);
            for (var i = 0; i < 30; i++) {
                free.frameTarget(false);
                free.postEncode(false, 120, 2_500);
            }
            assertEquals(75_000, free.frameTarget(true));
            var capped = configured(600_000, 300, 30);
            codeKey(capped, 6_000);
            for (var i = 0; i < 30; i++) {
                capped.frameTarget(false);
                capped.postEncode(false, 120, 2_500);
            }
            assertEquals(60_000, capped.frameTarget(true));
        }
    }

    @Nested
    @DisplayName("key frame recode")
    class KeyRecode {
        @Test
        @DisplayName("a key frame within one and a half targets is kept")
        void kept() {
            var rc = configured(600_000, 300, 30);
            rc.frameTarget(true);
            var q = rc.pickQindex(true, 0, 0, 0);
            assertEquals(q, rc.recodeKeyQindex(q, 60_000 * 3 / 2 / 8));
        }

        @Test
        @DisplayName("a key frame three times its target is coded again at a coarser quantizer, up to the worst")
        void coarser() {
            var rc = configured(600_000, 300, 30);
            var target = rc.frameTarget(true);
            var q = rc.pickQindex(true, 0, 0, 0);
            var next = rc.recodeKeyQindex(q, 3 * target / 8);
            assertTrue(next > q, q + " -> " + next);
            assertTrue(next <= 208);
            assertEquals(208, rc.recodeKeyQindex(208, 3 * target / 8));
        }
    }

    @Nested
    @DisplayName("overshoot")
    class Overshoot {
        @Test
        @DisplayName("a scene change is coded three quarters of the way to the worst quantizer and refills the buffer")
        void sceneChange() {
            var rc = configured(600_000, 300, 30);
            codeKey(rc, 6_000);
            for (var i = 0; i < 10; i++) {
                rc.frameTarget(false);
                rc.postEncode(false, 120, 6_000);
            }
            assertTrue(rc.bufferLevel() < 360_000);
            rc.frameTarget(false);
            assertEquals((3 * 208 + 100) >> 2, rc.sceneChangeQindex(100, false, 50_000));
            assertEquals(360_000, rc.bufferLevel());
            assertEquals((3 * 208 + 100) >> 2, rc.avgQindexInter());
        }

        @Test
        @DisplayName("a coded frame worth more than eight frames that would empty the buffer is dropped")
        void dropCoded() {
            var rc = configured(600_000, 300, 30);
            codeKey(rc, 6_000);
            for (var i = 0; i < 12; i++) {
                rc.frameTarget(false);
                rc.postEncode(false, 120, 8_000);
            }
            rc.frameTarget(false);
            assertFalse(rc.dropCoded(120, 1_000, false));
            assertFalse(rc.dropCoded(120, 30_000, true));
            assertFalse(rc.dropCoded(200, 30_000, false));
            assertTrue(rc.dropCoded(120, 30_000, false));
            assertEquals(360_000, rc.bufferLevel());
            assertEquals(208, rc.pickQindex(false, 0, 0, 0));
        }

        @Test
        @DisplayName("frames are never dropped once coded when dropping is off")
        void noDropping() {
            var rc = configured(600_000, 300, 0);
            codeKey(rc, 6_000);
            rc.frameTarget(false);
            assertFalse(rc.dropCoded(120, 1_000_000, false));
        }
    }

    @Nested
    @DisplayName("resize")
    class Resize {
        @Test
        @DisplayName("a size change keeps the learned state and restarts the buffer at its optimal level")
        void carriesState() {
            var rc = configured(600_000, 300, 30);
            codeKey(rc, 6_000);
            for (var i = 0; i < 30; i++) {
                rc.frameTarget(false);
                rc.postEncode(false, 120, 2_000);
            }
            var inter = rc.avgQindexInter();
            rc.configure(600_000, 30, 8, 208, 1000, 500, 600, 30, 0, 300, WIDTH * 3 / 4, HEIGHT * 3 / 4, false);
            rc.resize((long) WIDTH * HEIGHT);
            assertEquals(360_000, rc.bufferLevel());
            assertEquals(inter, rc.avgQindexInter());
            assertEquals(60_000, rc.frameTarget(true));
            rc.configure(600_000, 30, 8, 208, 1000, 500, 600, 30, 0, 300, WIDTH, HEIGHT, false);
            rc.resize((long) WIDTH * 3 / 4 * (HEIGHT * 3 / 4));
            assertEquals((inter + 208) >> 1, rc.avgQindexInter());
        }
    }
}
