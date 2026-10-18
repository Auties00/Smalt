package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("frame pacing schedule")
class FramePacingTest {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final long[] TICKS = {0, 1, 2, 29, 30, 31, 999, 1_000, 1_001, 123_456_789, 9_000_000_000L};

    @Nested
    @DisplayName("integer rates")
    class Whole {
        // The integer-rate formulas the screen sessions used before they took a Rational
        private static long legacyTick(int fps, long tick) {
            return tick / fps * NANOS_PER_SECOND + tick % fps * NANOS_PER_SECOND / fps;
        }

        private static long legacyTicks(int fps, long elapsed) {
            return elapsed / NANOS_PER_SECOND * fps + elapsed % NANOS_PER_SECOND * fps / NANOS_PER_SECOND;
        }

        @ParameterizedTest(name = "{0} fps")
        @ValueSource(ints = {1, 7, 15, 24, 25, 30, 60, 144, 999, 1_000})
        @DisplayName("match the integer schedule exactly")
        void matchLegacy(int fps) {
            var rate = new Rational(fps, 1);
            assertEquals(NANOS_PER_SECOND / fps, FramePacing.periodNanos(rate));
            for (var tick : TICKS) {
                assertEquals(legacyTick(fps, tick), FramePacing.tickNanos(rate, tick), "tick " + tick);
                assertEquals(legacyTicks(fps, tick), FramePacing.ticksElapsed(rate, tick), "elapsed " + tick);
                assertEquals(legacyTicks(fps, tick * 997), FramePacing.ticksElapsed(rate, tick * 997), "elapsed " + tick * 997);
            }
        }
    }

    @Nested
    @DisplayName("fractional rates")
    class Fractional {
        @Test
        @DisplayName("30000/1001 has a 33366666 ns period and lands exactly on whole seconds")
        void ntsc() {
            var rate = new Rational(30_000, 1_001);
            assertEquals(33_366_666L, FramePacing.periodNanos(rate));
            assertEquals(1_001L * NANOS_PER_SECOND, FramePacing.tickNanos(rate, 30_000));
            assertEquals(30_000L, FramePacing.ticksElapsed(rate, 1_001L * NANOS_PER_SECOND));
            assertEquals(29_999L, FramePacing.ticksElapsed(rate, 1_001L * NANOS_PER_SECOND - 1));
        }

        @Test
        @DisplayName("an unreduced fraction paces like its reduced form")
        void unreduced() {
            var reduced = new Rational(50, 3);
            var unreduced = new Rational(5_000, 300);
            for (var tick : TICKS) {
                assertEquals(FramePacing.tickNanos(reduced, tick), FramePacing.tickNanos(unreduced, tick));
                assertEquals(FramePacing.ticksElapsed(reduced, tick), FramePacing.ticksElapsed(unreduced, tick));
            }
        }

        @Test
        @DisplayName("results too large for a long saturate, and huge factors stay exact")
        void overflow() {
            assertEquals(Long.MAX_VALUE, FramePacing.tickNanos(new Rational(1, Long.MAX_VALUE), Long.MAX_VALUE));
            var denominator = Long.MAX_VALUE / NANOS_PER_SECOND / 2;
            assertEquals(denominator * NANOS_PER_SECOND,
                    FramePacing.tickNanos(new Rational(Long.MAX_VALUE, denominator), Long.MAX_VALUE));
            assertEquals(0, FramePacing.periodNanos(new Rational(Long.MAX_VALUE, 1)));
            assertEquals(0, FramePacing.ticksElapsed(new Rational(1, Long.MAX_VALUE), Long.MAX_VALUE));
        }
    }

    @Nested
    @DisplayName("rate cap")
    class Cap {
        @Test
        @DisplayName("keeps a rate at or below the cap and replaces a faster one with the cap")
        void atMost() {
            var ntsc = new Rational(30_000, 1_001);
            assertSame(ntsc, FramePacing.atMost(ntsc, 1_000));
            var cap = new Rational(1_000, 1);
            assertSame(cap, FramePacing.atMost(cap, 1_000));
            var justAbove = new Rational(1_000_001, 1_000);
            assertEquals(cap, FramePacing.atMost(justAbove, 1_000));
            assertEquals(cap, FramePacing.atMost(new Rational(Long.MAX_VALUE, 1), 1_000));
            var slowHuge = new Rational(Long.MAX_VALUE, Long.MAX_VALUE / 2);
            assertSame(slowHuge, FramePacing.atMost(slowHuge, 1_000));
        }
    }
}
