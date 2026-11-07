package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.av1.Av1TimingInfo;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the encoder's decoder model times with synthetic frame sizes and frame structures and
 * checks the times with the independent Annex E model of {@link AnnexEChecker}: removal times that
 * adapt to bursts of large frames and to frames decoded faster than one per frame interval, where
 * the nominal schedule breaks the model.
 */
class FrameTimingTest {
    private static final int WIDTH = 96;

    private static final int HEIGHT = 64;

    /** A synthetic frame: type, shown flag, refreshed slots or shown slot, size in bytes, timestamp. */
    private record Step(boolean key, boolean shown, boolean existing, int refresh, int slot, int bytes, long pts) {
    }

    private record Result(List<AnnexEChecker.Frame> frames, List<AnnexEChecker.Frame> nominal, SequenceHeader seq,
                          int adapted, FrameTiming timing) {
    }

    private static SequenceHeader sequence(int fps) {
        var seq = new SequenceHeader();
        seq.profile = 0;
        seq.numOperatingPoints = 1;
        seq.maxWidth = WIDTH;
        seq.maxHeight = HEIGHT;
        new StreamTiming(Av1TimingInfo.DECODER_MODEL, new Rational(1, 90_000), new Rational(fps, 1), 0).apply(seq);
        return seq;
    }

    /** Runs the steps through the encoder's timing, and lists them with its times and with nominal ones. */
    private static Result run(SequenceHeader seq, List<Step> steps) {
        var timing = new FrameTiming();
        var frames = new ArrayList<AnnexEChecker.Frame>();
        var nominal = new ArrayList<AnnexEChecker.Frame>();
        var p0 = steps.getFirst().pts();
        long keyTicks = 0;
        long lastNominal = 0;
        long lastTarget = 0;
        var adapted = 0;
        var luma = (long) WIDTH * HEIGHT;
        for (var unit = 0; unit < steps.size(); unit++) {
            var s = steps.get(unit);
            if (s.existing()) {
                var fpt = timing.presentation(seq, s.pts());
                timing.showExisting(s.slot(), s.pts(), s.bytes());
                var f = new AnnexEChecker.Frame(true, false, false, true, 0, s.slot(), 0, fpt, s.bytes() * 8L, luma, unit);
                frames.add(f);
                nominal.add(f);
                continue;
            }
            var h = new FrameHeader();
            h.frameType = s.key() ? FrameHeader.KEY : FrameHeader.INTER;
            h.showFrame = s.shown();
            h.refreshFrameFlags = s.key() ? 0xFF : s.refresh();
            h.upscaledWidth = WIDTH;
            h.height = HEIGHT;
            timing.fill(h, seq, s.pts());
            timing.schedule(h, seq, s.pts(), s.bytes());
            // the nominal schedule: the timestamp's distance in decoding ticks, a hidden frame at
            // the nominal time of the frame before, one tick after the frame before at least
            var target = s.shown() ? Math.ceilDiv((s.pts() - p0) * seq.numUnitsInTick, seq.numUnitsInDecodingTick)
                    : lastTarget;
            var ticks = unit == 0 ? 0 : Math.max(target, lastNominal + 1);
            lastTarget = target;
            var nominalRemoval = unit == 0 ? 0 : ticks - keyTicks;
            if (h.bufferRemovalTime[0] != nominalRemoval) {
                adapted++;
            }
            frames.add(new AnnexEChecker.Frame(false, s.key(), s.key(), s.shown(), h.refreshFrameFlags, 0,
                    h.bufferRemovalTime[0], h.framePresentationTime, s.bytes() * 8L, luma, unit));
            nominal.add(new AnnexEChecker.Frame(false, s.key(), s.key(), s.shown(), h.refreshFrameFlags, 0,
                    nominalRemoval, h.framePresentationTime, s.bytes() * 8L, luma, unit));
            lastNominal = ticks;
            if (s.key()) {
                keyTicks = ticks;
            }
            timing.advance(h, s.pts());
        }
        return new Result(frames, nominal, seq, adapted, timing);
    }

    private static List<String> check(SequenceHeader seq, List<AnnexEChecker.Frame> frames) {
        return AnnexEChecker.checkCuts(AnnexEChecker.params(seq), frames);
    }

    @Test
    @DisplayName("delays the removals of a burst of large frames until they have arrived")
    void burst() {
        var seq = sequence(30);
        var steps = new ArrayList<Step>();
        for (var i = 0; i < 120; i++) {
            var key = i % 60 == 0;
            // 12 kB frames for 32 frames: 2.9 Mbit/s for 1.07 seconds, which the level's 1.5 Mbit/s
            // with its one-second buffer carries 15 ms late, within the two frames of display delay
            // left by a buffer pool that holds eight references
            var bytes = key ? 4000 : i >= 20 && i < 52 ? 12_000 : 400;
            steps.add(new Step(key, true, false, 1 << (1 + i % 7), 0, bytes, i * 3000L));
        }
        var result = run(seq, steps);
        assertTrue(result.adapted() > 0, "removal times adapted");
        var errors = check(seq, result.frames());
        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
        assertEquals(0, result.timing().violations(), "encoder's own model");
        var nominal = check(seq, result.nominal());
        assertTrue(nominal.stream().anyMatch(e -> e.contains("underflow")), () -> "nominal schedule: " + nominal);
    }

    @Test
    @DisplayName("spaces the frames a pyramid decodes in one interval by the level's decoding speed")
    void pyramid() {
        // 150 frames per second: the level's header rate, so two frames per interval break the
        // minimum decode time unless the removals spread over the following show-existing units
        var seq = sequence(150);
        var interval = 600L;
        var steps = new ArrayList<Step>();
        for (var group = 0; group < 6; group++) {
            var base = group * 4L;
            if (group % 3 == 0) {
                steps.add(new Step(true, true, false, 0xFF, 0, 1500, base * interval));
            } else {
                steps.add(new Step(false, true, false, 1 << (3 + group % 3), 0, 300, base * interval));
            }
            // a hidden frame shown three frames later, then two shown frames
            steps.add(new Step(false, false, false, 1 << 6, 0, 900, 0));
            steps.add(new Step(false, true, false, 1 << (group % 2 == 0 ? 1 : 0), 0, 200, (base + 1) * interval));
            steps.add(new Step(false, true, false, 1 << (group % 2 == 0 ? 2 : 7), 0, 200, (base + 2) * interval));
            steps.add(new Step(false, true, true, 0, 6, 4, (base + 3) * interval));
        }
        var result = run(seq, steps);
        assertTrue(result.adapted() > 0, "removal times adapted");
        var errors = check(seq, result.frames());
        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
        var nominal = check(seq, result.nominal());
        assertFalse(nominal.isEmpty(), "the nominal schedule breaks the model");
    }

    @Test
    @DisplayName("signals buffer delays that fill the level's smoothing buffer and fit its largest frame")
    void bufferDelays() {
        var seq = sequence(30);
        assertEquals(0, (seq.majorLevel[0] - 2) * 4 + seq.minorLevel[0], "level 2.0");
        assertEquals(90_000, seq.decoderBufferDelay[0] + seq.encoderBufferDelay[0], "one second of buffer");
        // 96x64 at 30 fps: MinPicCompressRatio 0.8, 6144 * 15 / 8 / 0.8 + 128 bytes at 1.5 Mbit/s
        var largest = (6144 * 15 / 8 / 0.8 + 128) * 8 / 1_500_000;
        assertEquals((long) Math.ceil(largest * 90_000), seq.decoderBufferDelay[0]);
        assertEquals(32, seq.bufferRemovalDelayLength);
        assertEquals(17, seq.encoderDecoderBufferDelayLength);
        assertEquals(3000 / 16, seq.numUnitsInDecodingTick, "a sixteenth of a frame interval");
        var coarse = new SequenceHeader();
        coarse.numOperatingPoints = 1;
        coarse.maxWidth = WIDTH;
        coarse.maxHeight = HEIGHT;
        new StreamTiming(Av1TimingInfo.DECODER_MODEL, new Rational(1, 30), new Rational(30, 1), 0).apply(coarse);
        assertEquals(16, coarse.numUnitsInTick, "display tick of the time base");
        assertEquals(480, coarse.timeScale);
        assertEquals(1, coarse.numUnitsInDecodingTick);
    }
}
