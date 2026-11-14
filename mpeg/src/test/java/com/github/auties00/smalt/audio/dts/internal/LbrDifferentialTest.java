package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.container.dts.DtsDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Frame;
import static com.github.auties00.smalt.audio.dts.internal.LbrStreamWriter.Tone;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the DTS Express (LBR) decoder with ffmpeg's, used as a black box, on synthetic streams: tonal components of
 * every group, positions, frequencies (the mirrored lowest lines and the highest ones), phases and amplitudes (escape
 * coded ones included), secondary channels, the combined tonal chunks, frame checksums and components that run over
 * several frames.
 */
final class LbrDifferentialTest {
    /**
     * The tonal synthesis kernels were measured from ffmpeg's output to a few parts in a million.
     */
    private static final Tolerance TONAL = Tolerance.snr(85);

    record Case(String name, int channels, int mask, List<Frame> frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Frame tones(int group, List<Tone> tones, int channels) {
        return new Frame(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30),
                LbrStreamWriter.tonalGroup(group, tones, channels));
    }

    private static List<Frame> withSilence(Frame first) {
        return List.of(first, new Frame(), new Frame(), new Frame());
    }

    static Stream<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var group = 0; group <= 4; group++) {
            var frequency = (40 << (group + 1)) + (group == 0 ? 1 : 3);
            cases.add(new Case("group " + group, 1, 0x1,
                    withSilence(tones(group, List.of(new Tone(0, frequency, 10, group)), 1))));
        }
        cases.add(new Case("positions and frequencies", 1, 0x1, withSilence(tones(0, List.of(new Tone(0, 81, 10, 0),
                new Tone(0, 90, 8, 3), new Tone(3, 150, 12, 5), new Tone(15, 33, 9, 7)), 1))));
        cases.add(new Case("mirrored low lines", 1, 0x1, withSilence(tones(1, List.of(new Tone(0, 4, 10, 0),
                new Tone(0, 6, 10, 2), new Tone(2, 13, 10, 6), new Tone(3, 9, 11, 5)), 1))));
        for (var group = 0; group <= 4; group++) {
            var line0 = new ArrayList<Tone>();
            for (var frequency = 1; frequency < 1 << (group + 1) && line0.size() < 4; frequency += 1 + group) {
                line0.add(new Tone(line0.size() % (16 >> group), frequency, 9 + line0.size(), 2 * line0.size() + 1));
            }
            line0.sort(java.util.Comparator.comparingInt(Tone::position).thenComparingInt(Tone::frequency));
            cases.add(new Case("line 0 in group " + group, 1, 0x1, withSilence(tones(group, line0, 1))));
        }
        // components past the coded lines (malformed streams): what ffmpeg keeps, drops and synthesizes twice
        var t0 = new Tone(0, 40, 10, 1);
        var t1 = new Tone(1, 300, 11, 2);
        var past = new Tone(1, 500, 10, 0);
        var g0 = new Tone(0, 100, 12, 3);
        var sf = LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30);
        cases.add(new Case("past the top after an earlier position", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(1, List.of(t0, t1, past, new Tone(2, 50, 11, 4)), 1)))));
        cases.add(new Case("past the top in the first position", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(1, List.of(t0, new Tone(0, 300, 11, 2), new Tone(0, 500, 10, 0)), 1),
                LbrStreamWriter.tonalGroup(3, List.of(new Tone(0, 100, 12, 3)), 1)))));
        cases.add(new Case("past the top, then shorter groups", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(0, List.of(g0, new Tone(3, 140, 12, 6)), 1),
                LbrStreamWriter.tonalGroup(1, List.of(t0, t1, past), 1),
                LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 100, 12, 5)), 1)))));
        cases.add(new Case("past the top at 494 and 492", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(1, List.of(t0, t1, new Tone(1, 494, 10, 0)), 1),
                LbrStreamWriter.tonalGroup(0, List.of(new Tone(1, 60, 12, 2)), 1)))));
        // components synthesized in several groups share one phase, which odd lines and offsets turn
        var open = new Tone(1, 500, 10, 0);
        cases.add(new Case("copies of an odd line overlapping their component", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(1, List.of(t0, t1, open), 1),
                LbrStreamWriter.tonalGroup(0, List.of(new Tone(3, 151, 12, 3)), 1)))));
        cases.add(new Case("copies after their component", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(1, List.of(t0, t1, open), 1),
                LbrStreamWriter.tonalGroup(0, List.of(new Tone(4, 150, 12, 3), new Tone(5, 61, 12, 6),
                        new Tone(9, 151, 12, 3)), 1)))));
        var longFirst = new Tone(0, 100, 11, 5);
        var longSecond = new Tone(1, 200, 11, 6);
        var open2 = new Tone(1, 1000, 10, 0);
        for (var frequency : new int[]{300, 296, 297, 121}) {
            cases.add(new Case("two open groups, frequency " + frequency, 1, 0x1, withSilence(new Frame(sf,
                    LbrStreamWriter.tonalGroup(2, List.of(longFirst, longSecond, open2), 1),
                    LbrStreamWriter.tonalGroup(1, List.of(t0, new Tone(1, frequency, 11, 2), open), 1)))));
        }
        cases.add(new Case("two open groups and a shorter group", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(2, List.of(longFirst, longSecond, open2), 1),
                LbrStreamWriter.tonalGroup(1, List.of(t0, new Tone(1, 301, 11, 2), open), 1),
                LbrStreamWriter.tonalGroup(0, List.of(new Tone(2, 151, 12, 1), new Tone(7, 33, 12, 4)), 1)))));
        // a later chunk of a group replaces an earlier one; a combined chunk sets the scale factors of the others
        var second = LbrStreamWriter.tonalGroup(1, List.of(t1), 1);
        second[0] += 6;
        cases.add(new Case("replaced group chunk", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(1, List.of(t0), 1), second))));
        cases.add(new Case("combined and single chunks", 1, 0x1, withSilence(new Frame(sf,
                LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 100, 12, 3)), 1),
                LbrStreamWriter.tonalCombined(new int[]{24, 26, 28, 30, 32, 34}, java.util.Map.of(1, List.of(t0)), 1),
                LbrStreamWriter.tonalCombined(null, java.util.Map.of(0, List.of(new Tone(0, 100, 30, 3))), 1),
                LbrStreamWriter.tonalGroup(0, List.of(new Tone(2, 60, 30, 5)), 1)))));
        cases.add(new Case("combined chunk with its own scale factors and single chunks", 1, 0x1, withSilence(
                new Frame(sf, LbrStreamWriter.tonalCombined(new int[]{24, 26, 28, 30, 32, 34},
                        java.util.Map.of(1, List.of(t0)), 1),
                        LbrStreamWriter.tonalGroup(0, List.of(new Tone(2, 60, 12, 5)), 1),
                        LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 100, 12, 3)), 1)))));
        cases.add(new Case("highest lines", 1, 0x1, withSilence(new Frame(
                LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30),
                LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 800, 10, 1), new Tone(0, 980, 10, 1),
                        new Tone(1, 850, 9, 2), new Tone(1, 970, 10, 4)), 1),
                LbrStreamWriter.tonalGroup(3, List.of(new Tone(0, 1000, 8, 6)), 1)))));
        var amplitudes = new ArrayList<Tone>();
        for (var a = 0; a < 26; a++) {
            amplitudes.add(new Tone(0, 50 + 7 * a, a, a & 7));
        }
        cases.add(new Case("amplitudes", 1, 0x1, withSilence(new Frame(LbrStreamWriter.scaleFactors(0, 5, 10, 15, 20, 25),
                LbrStreamWriter.tonalGroup(3, amplitudes, 1)))));
        cases.add(new Case("escaped amplitude", 1, 0x1, withSilence(new Frame(LbrStreamWriter.tonalGroup(2,
                List.of(new Tone(0, 330, 40, 1), new Tone(1, 500, 60, 2)), 1)))));
        cases.add(new Case("stereo", 2, 0x2, withSilence(tones(2, List.of(
                new Tone(0, 330, 0, 10, 1, new int[][]{null, {2, 3}}),
                new Tone(1, 420, 1, 9, 6, new int[][]{{1, 7}, null}),
                new Tone(2, 500, 0, 11, 2, null),
                new Tone(3, 610, 1, 10, 0, new int[][]{{0, 5}, null})), 2))));
        cases.add(new Case("combined with scale factors", 1, 0x1, withSilence(new Frame(LbrStreamWriter.tonalCombined(
                new int[]{30, 28, 26, 24, 22, 20}, Map.of(4, List.of(new Tone(0, 700, 6, 1)), 2,
                        List.of(new Tone(1, 333, 7, 2)), 0, List.of(new Tone(5, 85, 8, 3))), 1)))));
        cases.add(new Case("combined without scale factors", 1, 0x1, withSilence(new Frame(
                LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30), LbrStreamWriter.tonalCombined(null,
                        Map.of(1, List.of(new Tone(2, 170, 40, 4))), 1)))));
        cases.add(new Case("checksum", 1, 0x1, List.of(new Frame(List.of(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30,
                30), LbrStreamWriter.tonalGroup(1, List.of(new Tone(1, 333, 10, 3)), 1)), true), new Frame(), new Frame())));
        var running = new ArrayList<Frame>();
        for (var f = 0; f < 6; f++) {
            running.add(tones(4, List.of(new Tone(0, 2000 + 37 * f, 9 + f % 3, f)), 1));
        }
        running.add(new Frame());
        running.add(new Frame());
        cases.add(new Case("components over several frames", 1, 0x1, running));
        cases.add(random(1, 0x1, 11));
        cases.add(random(1, 0x1, 13));
        cases.add(random(2, 0x2, 17));
        cases.add(random(2, 0x2, 19));
        cases.add(random(4, 0x6, 23));
        return cases.stream();
    }

    /**
     * Builds frames of random tonal components in every group.
     */
    private static Case random(int channels, int mask, long seed) {
        return random(channels, mask, seed, 128);
    }

    /**
     * Builds frames of random tonal components in every group of a synthesis of some lines.
     */
    private static Case random(int channels, int mask, long seed, int lines) {
        var random = new SplittableRandom(seed);
        var frames = new ArrayList<Frame>();
        for (var f = 0; f < 5; f++) {
            var chunks = new ArrayList<byte[]>();
            var factors = new int[6];
            for (var b = 0; b < 6; b++) {
                factors[b] = 18 + random.nextInt(14);
            }
            chunks.add(LbrStreamWriter.scaleFactors(factors));
            for (var group = 0; group <= 4; group++) {
                var tones = new ArrayList<Tone>();
                for (var position = 0; position < 16 >> group; position++) {
                    var frequency = (1 << (group + 1)) + random.nextInt(30);
                    var limit = (lines - 5) << (group + 1);
                    for (var t = 0; t < 2 && frequency < limit; t++) {
                        int[][] secondary = null;
                        var main = channels > 1 ? random.nextInt(channels) : 0;
                        if (channels > 1 && random.nextBoolean()) {
                            secondary = new int[channels][];
                            secondary[(main + 1) % channels] = new int[]{random.nextInt(6), random.nextInt(8)};
                        }
                        tones.add(new Tone(position, frequency, main, random.nextInt(14), random.nextInt(8), secondary));
                        frequency += 1 + random.nextInt(limit / 3);
                    }
                }
                chunks.add(LbrStreamWriter.tonalGroup(group, tones, channels));
            }
            frames.add(new Frame(chunks, false));
        }
        frames.add(new Frame());
        frames.add(new Frame());
        return new Case("random " + channels + " channels, seed " + seed, channels, mask, frames);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("tonal synthesis matches ffmpeg")
    void tonal(Case test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(48000, test.mask(), test.channels(), 0x14, test.frames());
        var file = Scratch.directory("dts-lbr").resolve(test.name().replace(' ', '-') + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        TONAL.assertPcm(test.name(), reference, decoded);
    }

    /**
     * The LFE interpolation filter was measured from ffmpeg's output, whose own single precision filter leaves
     * about 72 dB of rounding noise.
     */
    private static final Tolerance LFE = Tolerance.snr(65);

    /**
     * The header flags of a full band stream with an LFE channel of 16-bit and of 24-bit source samples.
     */
    private static final int LFE_16 = 0x16;
    private static final int LFE_24 = 0x17;

    record FlagCase(String name, int channels, int mask, int flags, List<Frame> frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * {@return frames of random LFE codes, followed by silent frames that let the filter decay}
     */
    private static List<Frame> randomLfe(long seed, boolean wide, int count, List<byte[]> extra) {
        var random = new SplittableRandom(seed);
        var frames = new ArrayList<Frame>();
        for (var f = 0; f < count; f++) {
            var codes = new int[64];
            for (var i = 0; i < codes.length; i++) {
                codes[i] = random.nextInt(wide ? 64 : 16);
            }
            var initial = wide ? random.nextInt(0x300000) | (random.nextBoolean() ? 0x800000 : 0)
                    : random.nextInt(0x3000) | (random.nextBoolean() ? 0x8000 : 0);
            var chunks = new ArrayList<>(extra);
            chunks.add(LbrStreamWriter.lfe(initial, random.nextInt(wide ? 100 : 60), codes, wide));
            frames.add(new Frame(chunks, false));
        }
        for (var f = 0; f < 6; f++) {
            frames.add(new Frame());
        }
        return frames;
    }

    private static int[] repeat(int... pattern) {
        var codes = new int[64];
        for (var i = 0; i < codes.length; i++) {
            codes[i] = pattern[i % pattern.length];
        }
        return codes;
    }

    static Stream<FlagCase> lfeCases() {
        var cases = new ArrayList<FlagCase>();
        cases.add(new FlagCase("16-bit random", 1, 0x1, LFE_16, randomLfe(3, false, 12, List.of())));
        cases.add(new FlagCase("24-bit random", 1, 0x1, LFE_24, randomLfe(5, true, 12, List.of())));
        var clamped = new ArrayList<Frame>();
        clamped.add(new Frame(LbrStreamWriter.lfe(0, 40, repeat(7), false)));
        clamped.add(new Frame(LbrStreamWriter.lfe(0x8000 | 0x7000, 60, repeat(15, 15, 15, 7), false)));
        clamped.add(new Frame(LbrStreamWriter.lfe(0x400000, 120, repeat(31, 63), true)));
        for (var f = 0; f < 6; f++) {
            clamped.add(new Frame());
        }
        var narrowClamped = new ArrayList<Frame>(clamped.subList(0, 2));
        narrowClamped.addAll(clamped.subList(3, clamped.size()));
        cases.add(new FlagCase("clamped values", 1, 0x1, LFE_16, narrowClamped));
        var wideClamped = new ArrayList<Frame>(List.of(clamped.get(2)));
        wideClamped.addAll(clamped.subList(3, clamped.size()));
        cases.add(new FlagCase("clamped 24-bit values", 1, 0x1, LFE_24, wideClamped));
        var tone = LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 330, 0, 10, 1, new int[][]{null, {2, 3}})), 2);
        cases.add(new FlagCase("stereo tones with LFE", 2, 0x2, LFE_16,
                randomLfe(7, false, 4, List.of(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30), tone))));
        var gaps = new ArrayList<Frame>();
        gaps.add(new Frame(LbrStreamWriter.lfe(0x2000, 20, repeat(3, 11), false)));
        gaps.add(new Frame());
        gaps.add(new Frame(LbrStreamWriter.chunk(0x0A, new byte[34])));
        gaps.add(new Frame(LbrStreamWriter.lfe(0x1000, 150, repeat(1, 9), false)));
        gaps.add(new Frame(LbrStreamWriter.lfe(0x8000 | 0x1800, 10, repeat(5, 13, 2, 10), false)));
        for (var f = 0; f < 6; f++) {
            gaps.add(new Frame());
        }
        cases.add(new FlagCase("missing, short and invalid chunks", 1, 0x1, LFE_16, gaps));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lfeCases")
    @DisplayName("LFE decoding matches ffmpeg")
    void lfe(FlagCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(48000, test.mask(), test.channels(), test.flags(), test.frames());
        var file = Scratch.directory("dts-lbr").resolve("lfe-" + test.name().replace(' ', '-').replace(",", "")
                + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        LFE.assertPcm(test.name(), reference, decoded);
    }

    static Stream<FlagCase> bandLimitCases() {
        var cases = new ArrayList<FlagCase>();
        for (var limit : new int[]{0x08, 0x10}) {
            var top = (limit == 0x08 ? 64 : 32) - 5;
            var name = limit == 0x08 ? "half band" : "quarter band";
            cases.add(new FlagCase(name, 1, 0x1, limit, withSilence(tones(2, List.of(new Tone(0, 81, 10, 0),
                    new Tone(1, (top / 2) << 3, 9, 3), new Tone(2, ((top - 1) << 3) + 5, 10, 6)), 1))));
            // a component on the first line past the limit discards the components of its chunk, the next frame
            // decodes
            cases.add(new FlagCase(name + " past the limit", 1, 0x1, limit, List.of(
                    tones(2, List.of(new Tone(0, (top / 2) << 3, 10, 1), new Tone(0, top << 3, 10, 2)), 1),
                    tones(3, List.of(new Tone(0, (top / 3) << 4, 8, 5)), 1), new Frame(), new Frame(),
                    new Frame())));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bandLimitCases")
    @DisplayName("band limited tonal synthesis matches ffmpeg")
    void bandLimit(FlagCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(48000, test.mask(), test.channels(), test.flags(), test.frames());
        var file = Scratch.directory("dts-lbr").resolve("band-" + test.name().replace(' ', '-') + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        TONAL.assertPcm(test.name(), reference, decoded);
    }

    /**
     * The residual path follows ffmpeg's single precision decoder to its rounding noise.
     */
    private static final Tolerance RESIDUAL = Tolerance.snr(100);

    record ResidualCase(String name, int rate, int flags, List<Frame> frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * {@return frames of random residual values, followed by two frames without chunks}
     */
    private static List<Frame> residualFrames(long seed, int rate, int[] profiles, List<String> features, int[] order,
                                              List<byte[]> extra) {
        var random = new SplittableRandom(seed);
        var frames = new ArrayList<Frame>();
        for (var profile : profiles) {
            var frame = LbrResidualWriter.random(random, profile, rate, features);
            var chunks = new ArrayList<>(extra);
            chunks.addAll(LbrResidualWriter.chunks(frame, rate, 128000, order));
            frames.add(new Frame(chunks, false));
        }
        frames.add(new Frame());
        frames.add(new Frame());
        return frames;
    }

    static Stream<ResidualCase> residualCases() {
        var all = List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods");
        var identity = LbrResidualWriter.identity();
        var cases = new ArrayList<ResidualCase>();
        for (var profile : new int[]{0xC0, 0x00, 0x10, 0x20, 0x38, 0x18}) {
            cases.add(new ResidualCase("levels of profile " + Integer.toHexString(profile), 48000, 0x14,
                    residualFrames(profile, 48000, new int[]{profile, profile, profile}, List.of(), identity,
                            List.of())));
        }
        for (var feature : all) {
            cases.add(new ResidualCase(feature, 48000, 0x14,
                    residualFrames(feature.hashCode(), 48000, new int[]{0x18, 0x38, 0x10}, List.of(feature), identity,
                            List.of())));
        }
        cases.add(new ResidualCase("subband of maximum energy", 48000, 0x14, residualFrames(3, 48000,
                new int[]{0x19, 0x1A, 0x1B, 0x1C, 0x39}, all, identity, List.of())));
        cases.add(new ResidualCase("grid escape codes", 48000, 0x14, residualFrames(6, 48000,
                new int[]{0x38, 0x18, 0x30}, List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods",
                        "escapes"), identity, List.of())));
        cases.add(new ResidualCase("subband of maximum energy 4 to 7", 48000, 0x14, residualFrames(4, 48000,
                new int[]{0x05, 0x35, 0x0C, 0x16, 0x3E, 0x37, 0x67, 0x0F, 0x44, 0x3D}, all, identity, List.of())));
        var order = identity.clone();
        for (var i = 0; i + 1 < 12; i += 2) {
            var t = order[i];
            order[i] = order[i + 1];
            order[i + 1] = t;
        }
        cases.add(new ResidualCase("reordered subbands", 48000, 0x14, residualFrames(5, 48000,
                new int[]{0x18, 0x20, 0xC0}, all, order, List.of())));
        var tone = LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 330, 10, 1), new Tone(1, 500, 8, 4)), 1);
        cases.add(new ResidualCase("with tones", 48000, 0x14, residualFrames(7, 48000,
                new int[]{0x18, 0x38, 0x00, 0x20}, all, identity,
                List.of(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30), tone))));
        cases.add(new ResidualCase("with tones and LFE", 48000, LFE_16, residualFrames(9, 48000,
                new int[]{0x18, 0x10, 0x38}, all, identity,
                List.of(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30), tone,
                        LbrStreamWriter.lfe(0x2000, 30, repeat(3, 11, 6), false)))));
        cases.add(new ResidualCase("44.1 kHz", 44100, 0x14, residualFrames(11, 44100,
                new int[]{0x18, 0x38, 0x20}, all, identity, List.of())));
        cases.add(new ResidualCase("32 kHz", 32000, 0x14, residualFrames(13, 32000,
                new int[]{0x18, 0x38, 0x20}, all, identity, List.of())));
        for (var rate : new int[]{8000, 12000, 16000, 22050, 24000}) {
            cases.add(new ResidualCase(rate + " Hz", rate, 0x14, residualFrames(rate, rate,
                    new int[]{0x18, 0x38, 0x20, 0x00, 0x1B}, all, identity, List.of())));
            cases.add(new ResidualCase(rate + " Hz with tones", rate, 0x14, residualFrames(rate + 1, rate,
                    new int[]{0x18, 0x10}, all, identity, List.of(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30),
                            LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 70, 10, 1), new Tone(1, 100, 8, 4)),
                                    1)))));
        }
        cases.add(new ResidualCase("24 kHz, reordered subbands", 24000, 0x14, residualFrames(15, 24000,
                new int[]{0x18, 0x20, 0xC0}, all, order, List.of())));
        // a band limited stream is coded as a stream of the limited rate and synthesized at the header's
        for (var limit : new int[][]{{48000, 0x08}, {48000, 0x10}, {44100, 0x08}, {44100, 0x10}, {32000, 0x08},
                {32000, 0x10}, {24000, 0x08}, {22050, 0x08}, {16000, 0x08}}) {
            var limited = limit[0] / (limit[1] == 0x08 ? 2 : 4);
            cases.add(new ResidualCase((limit[1] == 0x08 ? "half" : "quarter") + " band at " + limit[0] + " Hz",
                    limit[0], limit[1], residualFrames(limit[0] + limit[1], limited,
                            new int[]{0x18, 0x38, 0x20}, all, identity, List.of())));
        }
        cases.add(new ResidualCase("half band with tones and LFE", 48000, 0x0A, residualFrames(17, 24000,
                new int[]{0x18, 0x10}, all, identity, List.of(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30),
                        LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 70, 10, 1), new Tone(1, 100, 8, 4)), 1),
                        LbrStreamWriter.lfe(0x2000, 30, repeat(3, 11, 6), false)))));
        cases.add(new ResidualCase("quarter band with LFE", 48000, 0x12, residualFrames(19, 12000,
                new int[]{0x18, 0x10}, all, identity, List.of(LbrStreamWriter.lfe(0x1800, 20, repeat(5, 2, 9),
                        false)))));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("residualCases")
    @DisplayName("residual decoding matches ffmpeg")
    void residual(ResidualCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(test.rate(), 0x1, 1, test.flags(), test.frames());
        var file = Scratch.directory("dts-lbr").resolve("residual-" + test.name().replace(' ', '-').replace(".", "")
                + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        ((test.flags() & 2) != 0 ? LFE : RESIDUAL).assertPcm(test.name(), reference, decoded);
    }

    record PairCase(String name, List<Frame> frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The layout of the residual chunks of one frame of a channel pair.
     *
     * @param profile the quantizer profile
     * @param stereo whether the frame carries a partial stereo grid
     * @param entries the entries of the second channel below subband 18
     * @param stereoEntries whether the entries of both channels from subband 18 follow
     * @param padding the zero bits after the last entry
     * @param chunks the number of residual chunks written
     * @param silentBand the first grid 1 band made silent, or 11 for none
     */
    record PairLayout(int profile, boolean stereo, int entries, boolean stereoEntries, int padding, int chunks,
                      int silentBand) {
        static PairLayout full(int profile) {
            return new PairLayout(profile, true, 16, true, 32, 4, 11);
        }
    }

    /**
     * {@return frames of random channel pair residual values, followed by two frames without chunks}
     */
    private static List<Frame> pairFrames(long seed, List<PairLayout> layouts, int[] order, List<byte[]> extra) {
        var random = new SplittableRandom(seed);
        var frames = new ArrayList<Frame>();
        for (var layout : layouts) {
            var features = new ArrayList<>(List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods", "flags"));
            if (layout.stereo()) {
                features.add("stereo");
            }
            var frame = LbrResidualWriter.randomPair(random, layout.profile(), 48000, features);
            if (layout.silentBand() < 11) {
                LbrResidualWriter.silenceFrom(frame, layout.silentBand());
            }
            var chunks = new ArrayList<>(extra);
            chunks.addAll(LbrResidualWriter.pairChunks(frame, 48000, 128000, order, layout.entries(),
                    layout.stereoEntries(), layout.padding()).subList(0, layout.chunks()));
            frames.add(new Frame(chunks, false));
        }
        frames.add(new Frame());
        frames.add(new Frame());
        return frames;
    }

    static Stream<PairCase> pairCases() {
        var identity = LbrResidualWriter.identity();
        var order = identity.clone();
        for (var i = 0; i + 1 < 12; i += 2) {
            var t = order[i];
            order[i] = order[i + 1];
            order[i + 1] = t;
        }
        var shuffled = new int[]{16, 11, 6, 14, 15, 8, 7, 13, 12, 17, 10, 9};
        var cases = new ArrayList<PairCase>();
        cases.add(new PairCase("pair, every level", pairFrames(21, List.of(PairLayout.full(0xC0), PairLayout.full(0x00),
                PairLayout.full(0x10), PairLayout.full(0x38), PairLayout.full(0x19), PairLayout.full(0x1A)), identity,
                List.of())));
        cases.add(new PairCase("pair, reordered subbands", pairFrames(23, List.of(PairLayout.full(0x18),
                PairLayout.full(0x20), PairLayout.full(0x1B)), order, List.of())));
        cases.add(new PairCase("pair, shuffled subbands", pairFrames(25, List.of(PairLayout.full(0x18),
                PairLayout.full(0x1C), PairLayout.full(0x08)), shuffled, List.of())));
        var copies = new ArrayList<PairLayout>();
        for (var entries : new int[]{0, 3, 6, 9, 16}) {
            copies.add(new PairLayout(0x18, true, entries, false, 0, 4, 8));
        }
        cases.add(new PairCase("pair, copied subbands with stereo gains", pairFrames(27, copies, shuffled,
                List.of())));
        cases.add(new PairCase("pair, copied subbands without stereo gains", pairFrames(29, List.of(
                new PairLayout(0x18, false, 4, false, 0, 4, 8), new PairLayout(0x20, false, 7, false, 0, 4, 8),
                new PairLayout(0x18, false, 12, false, 0, 4, 8)), identity, List.of())));
        cases.add(new PairCase("pair, stereo gains in some frames", pairFrames(31, List.of(
                new PairLayout(0x18, true, 5, false, 0, 4, 8), new PairLayout(0x18, false, 5, false, 0, 4, 8),
                new PairLayout(0x18, true, 5, false, 0, 4, 8), new PairLayout(0x18, true, 5, false, 0, 4, 8)),
                identity, List.of())));
        cases.add(new PairCase("pair, last entry without room", pairFrames(33, List.of(
                new PairLayout(0x18, true, 10, false, 0, 4, 8), new PairLayout(0x18, true, 10, false, 24, 4, 8),
                new PairLayout(0x18, true, 16, false, 0, 4, 8)), identity, List.of())));
        cases.add(new PairCase("pair, without the second time samples chunk", pairFrames(35, List.of(
                new PairLayout(0x18, true, 16, true, 0, 3, 5), new PairLayout(0x18, true, 16, true, 0, 4, 11),
                new PairLayout(0x18, true, 16, true, 0, 3, 5), new PairLayout(0x18, false, 16, true, 0, 3, 5)),
                identity, List.of())));
        var tone = LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 330, 10, 1), new Tone(1, 500, 8, 4)), 2);
        cases.add(new PairCase("pair with tones", pairFrames(37, List.of(PairLayout.full(0x18),
                PairLayout.full(0x38)), identity, List.of(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30),
                tone))));
        return cases.stream();
    }

    /**
     * A stream of several channel pairs.
     *
     * @param order the coded channel ffmpeg outputs first for each of Smalt's output channels, which follow the
     *        speaker order
     */
    record ChannelsCase(String name, int mask, int channels, int[] order, List<Frame> frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<ChannelsCase> channelsCases() {
        var all = List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods", "flags", "stereo");
        var identity = LbrResidualWriter.identity();
        var cases = new ArrayList<ChannelsCase>();
        var random = new SplittableRandom(41);
        var three = new ArrayList<Frame>();
        var four = new ArrayList<Frame>();
        for (var profile : new int[]{0x18, 0x38, 0x10}) {
            var pair = LbrResidualWriter.randomPair(random, profile, 48000, all);
            var single = LbrResidualWriter.random(random, profile, 48000, all);
            var chunks = new ArrayList<>(LbrResidualWriter.pairChunks(pair, 48000, 192000, identity, 16, true, 32, 3));
            chunks.addAll(LbrResidualWriter.forPair(LbrResidualWriter.chunks(single, 48000, 192000, identity, 26, 3),
                    1));
            three.add(new Frame(chunks, false));
            var second = LbrResidualWriter.randomPair(random, profile, 48000, all);
            chunks = new ArrayList<>(LbrResidualWriter.pairChunks(pair, 48000, 192000, identity, 16, true, 32, 4));
            chunks.addAll(LbrResidualWriter.forPair(LbrResidualWriter.pairChunks(second, 48000, 192000, identity, 16,
                    true, 32, 4), 1));
            four.add(new Frame(chunks, false));
        }
        for (var frames : List.of(three, four)) {
            frames.add(new Frame());
            frames.add(new Frame());
        }
        cases.add(new ChannelsCase("a pair and a single channel", 0x3, 3, new int[]{1, 2, 0}, three));
        cases.add(new ChannelsCase("two pairs", 0x6, 4, new int[]{0, 1, 2, 3}, four));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("channelsCases")
    @DisplayName("residual decoding of several channel pairs matches ffmpeg")
    void channels(ChannelsCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var frames = new ByteArrayOutputStream();
        for (var frame : test.frames()) {
            var lbr = LbrStreamWriter.frame(false, frame.chunks().toArray(byte[][]::new));
            var component = LbrStreamWriter.component(48000, test.mask(), 0x14, 192000, true, lbr);
            frames.writeBytes(LbrStreamWriter.substream(component, test.channels(), test.mask(), 48000, 4096));
        }
        var file = Scratch.directory("dts-lbr").resolve("channels-" + test.name().replace(' ', '-') + ".dts");
        Files.write(file, frames.toByteArray());
        var decoded = decode(file);
        var reference = reorder(ffmpeg(file), test.order());
        assertEquals(reference.length, decoded.length, "sample count");
        RESIDUAL.assertPcm(test.name(), reference, decoded);
    }

    /**
     * {@return interleaved samples with their channels moved: output channel {@code i} takes input channel
     * {@code order[i]}}
     */
    private static float[] reorder(float[] samples, int[] order) {
        var out = new float[samples.length];
        for (var i = 0; i < samples.length; i += order.length) {
            for (var ch = 0; ch < order.length; ch++) {
                out[i + ch] = samples[i + order[ch]];
            }
        }
        return out;
    }

    /**
     * {@return a stream of 4 full band channels and an LFE, two channel pairs of residual, an embedded channel sets
     * chunk and LFE, with tonal components on every channel or none}
     */
    private static byte[] fourChannels(int flags, boolean tonal) {
        var all = List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods", "flags", "stereo");
        var identity = LbrResidualWriter.identity();
        var random = new SplittableRandom(43);
        var tones = LbrStreamWriter.tonalGroup(2, List.of(
                new Tone(0, 330, 0, 10, 1, new int[][]{null, {2, 3}, null, {1, 2}}),
                new Tone(1, 420, 2, 9, 6, new int[][]{{1, 7}, null, null, null}),
                new Tone(2, 520, 3, 11, 2, null),
                new Tone(3, 610, 1, 10, 0, new int[][]{{0, 5}, null, {2, 1}, null})), 4);
        var out = new ByteArrayOutputStream();
        for (var profile : new int[]{0x18, 0x38, 0x10, -1, -1}) {
            var chunks = new ArrayList<byte[]>();
            if (profile >= 0) {
                chunks.add(LbrStreamWriter.chunk(0x0B, new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A}));
                if (tonal) {
                    chunks.add(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30));
                    chunks.add(tones);
                }
                var first = LbrResidualWriter.randomPair(random, profile, 48000, all);
                var second = LbrResidualWriter.randomPair(random, profile, 48000, all);
                chunks.addAll(LbrResidualWriter.pairChunks(first, 48000, 192000, identity, 16, true, 32, 4));
                chunks.addAll(LbrResidualWriter.forPair(LbrResidualWriter.pairChunks(second, 48000, 192000, identity,
                        16, true, 32, 4), 1));
                chunks.add(LbrStreamWriter.lfe(0x2000, 30, repeat(3, 11, 6), false));
            }
            var lbr = LbrStreamWriter.frame(false, chunks.toArray(byte[][]::new));
            var component = LbrStreamWriter.component(48000, 0xE, flags, 192000, true, lbr);
            out.writeBytes(LbrStreamWriter.substream(component, 5, 0xE, 48000, 4096));
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("a stream with an embedded stereo downmix decodes to the downmix, as ffmpeg's decoder does")
    void stereoDownmix() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Scratch.directory("dts-lbr").resolve("stereo-downmix.dts");
        Files.write(file, fourChannels(0x36, false));
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(5 * 4096 * 2, decoded.length, "sample count");
        assertEquals(reference.length, decoded.length, "sample count");
        RESIDUAL.assertPcm("stereo downmix", reference, decoded);
    }

    @Test
    @DisplayName("the downmix of a stream with tonal components on every channel is its first two channels")
    void stereoDownmixTonal() throws IOException {
        var downmix = Scratch.directory("dts-lbr").resolve("stereo-downmix-tonal.dts");
        Files.write(downmix, fourChannels(0x36, true));
        var plain = Scratch.directory("dts-lbr").resolve("stereo-downmix-tonal-plain.dts");
        Files.write(plain, fourChannels(0x16, true));
        var decoded = decode(downmix);
        var all = decode(plain);
        assertEquals(5 * 4096 * 2, decoded.length, "sample count");
        assertEquals(5 * 4096 * 5, all.length, "sample count");
        var front = new float[decoded.length];
        for (var i = 0; i < 5 * 4096; i++) {
            front[2 * i] = all[5 * i];
            front[2 * i + 1] = all[5 * i + 1];
        }
        // the frames without chunks hold only noise, whose sequence the other channels of the full decode advance
        RESIDUAL.assertPcm("stereo downmix with tones", front, decoded);
        var energy = 0.0;
        for (var v : decoded) {
            energy += v * (double) v;
        }
        org.junit.jupiter.api.Assertions.assertTrue(energy > 0, "silent downmix");
    }

    @Test
    @DisplayName("a stereo downmix flag on a stream of two channels is malformed")
    void stereoDownmixOfTwoChannels() throws IOException {
        var file = Scratch.directory("dts-lbr").resolve("stereo-downmix-two.dts");
        Files.write(file, LbrStreamWriter.stream(48000, 0x2, 2, 0x34, List.of(new Frame())));
        org.junit.jupiter.api.Assertions.assertThrows(com.github.auties00.smalt.MediaException.Malformed.class,
                () -> decode(file));
    }

    record RatePairCase(String name, int rate, List<Frame> frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<RatePairCase> ratePairCases() {
        var features = List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods", "flags", "stereo");
        var cases = new ArrayList<RatePairCase>();
        for (var rate : new int[]{8000, 12000, 16000, 22050, 24000, 32000, 44100}) {
            var random = new SplittableRandom(rate);
            var frames = new ArrayList<Frame>();
            var layouts = new int[][]{{0x18, 16, 1, 32}, {0x38, 16, 1, 32}, {0x10, 3, 0, 0}, {0x18, 1, 0, 0},
                    {0x20, 16, 1, 0}};
            for (var layout : layouts) {
                var frame = LbrResidualWriter.randomPair(random, layout[0], rate, features);
                frames.add(new Frame(LbrResidualWriter.pairChunks(frame, rate, 128000, LbrResidualWriter.identity(),
                        layout[1], layout[2] != 0, layout[3]), false));
            }
            frames.add(new Frame());
            cases.add(new RatePairCase("pair at " + rate + " Hz", rate, frames));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ratePairCases")
    @DisplayName("channel pair residual decoding at every sample rate matches ffmpeg")
    void ratePair(RatePairCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(test.rate(), 0x2, 2, 0x14, test.frames());
        var file = Scratch.directory("dts-lbr").resolve(test.name().replace(' ', '-') + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        RESIDUAL.assertPcm(test.name(), reference, decoded);
    }

    record RateCase(String name, int rate, Case tones) {
        @Override
        public String toString() {
            return name;
        }
    }

    record LimitedPairCase(String name, int rate, int flags, List<Frame> frames) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<LimitedPairCase> limitedPairCases() {
        var features = List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods", "flags", "stereo");
        var cases = new ArrayList<LimitedPairCase>();
        for (var limit : new int[][]{{48000, 0x08}, {48000, 0x10}, {44100, 0x10}, {32000, 0x08}, {16000, 0x08}}) {
            var limited = limit[0] / (limit[1] == 0x08 ? 2 : 4);
            var random = new SplittableRandom(limit[0] + limit[1]);
            var frames = new ArrayList<Frame>();
            for (var layout : new int[][]{{0x18, 16, 1, 32}, {0x38, 16, 1, 32}, {0x10, 3, 0, 0}}) {
                var frame = LbrResidualWriter.randomPair(random, layout[0], limited, features);
                frames.add(new Frame(LbrResidualWriter.pairChunks(frame, limited, 128000,
                        LbrResidualWriter.identity(), layout[1], layout[2] != 0, layout[3]), false));
            }
            frames.add(new Frame());
            cases.add(new LimitedPairCase((limit[1] == 0x08 ? "half" : "quarter") + " band pair at " + limit[0]
                    + " Hz", limit[0], limit[1], frames));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitedPairCases")
    @DisplayName("band limited channel pair residual decoding matches ffmpeg")
    void limitedPair(LimitedPairCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(test.rate(), 0x2, 2, test.flags(), test.frames());
        var file = Scratch.directory("dts-lbr").resolve(test.name().replace(' ', '-') + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        RESIDUAL.assertPcm(test.name(), reference, decoded);
    }

    static Stream<RateCase> rateCases() {
        var cases = new ArrayList<RateCase>();
        for (var rate : new int[]{8000, 12000, 16000, 22050, 24000}) {
            var lines = rate < 16000 ? 32 : 64;
            cases.add(new RateCase(rate + " Hz mono", rate, random(1, 0x1, rate, lines)));
            cases.add(new RateCase(rate + " Hz stereo", rate, random(2, 0x2, rate + 1, lines)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rateCases")
    @DisplayName("tonal synthesis below 32 kHz matches ffmpeg")
    void tonalRate(RateCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(test.rate(), test.tones().mask(), test.tones().channels(), 0x14,
                test.tones().frames());
        var file = Scratch.directory("dts-lbr").resolve("rate-" + test.name().replace(' ', '-') + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        TONAL.assertPcm(test.name(), reference, decoded);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairCases")
    @DisplayName("channel pair residual decoding matches ffmpeg")
    void pair(PairCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = LbrStreamWriter.stream(48000, 0x2, 2, 0x14, test.frames());
        var file = Scratch.directory("dts-lbr").resolve("pair-" + test.name().replace(' ', '-').replace(",", "")
                + ".dts");
        Files.write(file, stream);
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        RESIDUAL.assertPcm(test.name(), reference, decoded);
    }

    @Test
    @DisplayName("stereo grid values past the gain table leave the copied samples unscaled, as in ffmpeg")
    void stereoGridPastTable() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var random = new SplittableRandom(45);
        var features = List.of("lpc", "grid1", "grid2", "grid3", "averages", "methods", "flags", "stereo");
        var outside = new int[]{34, 36, 40, 63, 200};
        var frames = new ArrayList<Frame>();
        for (var f = 0; f < 4; f++) {
            var frame = LbrResidualWriter.randomPair(random, 0x18, 48000, features);
            for (var c = 0; c < 2; c++) {
                for (var g = 0; g < 8; g++) {
                    for (var k = 0; k < 4; k++) {
                        if ((g + k + c + f) % 3 == 0) {
                            frame.stereoGrid()[c][g][k] = outside[(g + k + f) % outside.length] - frame.minimum()[c];
                        }
                    }
                }
            }
            frames.add(new Frame(LbrResidualWriter.pairChunks(frame, 48000, 128000, LbrResidualWriter.identity(), 5,
                    false, 0), false));
        }
        frames.add(new Frame());
        var file = Scratch.directory("dts-lbr").resolve("pair-stereo-grid-past-table.dts");
        Files.write(file, LbrStreamWriter.stream(48000, 0x2, 2, 0x14, frames));
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        RESIDUAL.assertPcm("stereo grid past the gain table", reference, decoded);
    }

    /**
     * A stream whose subbands are partly or wholly left to the noise substitution.
     *
     * @param rate the sample rate
     * @param chunks the number of residual chunks of each frame, from 1 (grid 1 only) to 4
     * @param bitRate the original bit rate of the header
     * @param scaledBitRate the scaled bit rate of the header, which sets the noise level
     */
    record NoiseCase(String name, int rate, int channels, int chunks, int bitRate, int scaledBitRate) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<NoiseCase> noiseCases() {
        var cases = new ArrayList<NoiseCase>();
        for (var chunks = 1; chunks <= 4; chunks++) {
            cases.add(new NoiseCase(chunks + " chunks", 48000, 1, chunks, 128000, 128000));
        }
        for (var rate : new int[]{8000, 15000, 20000, 24000, 31000}) {
            cases.add(new NoiseCase("grid 1 at " + rate + " bit/s", 48000, 1, 1, rate, rate));
            cases.add(new NoiseCase("time samples at " + rate + " bit/s", 48000, 1, 3, rate, rate));
        }
        cases.add(new NoiseCase("scaled rate below the original", 48000, 1, 3, 64000, 16000));
        cases.add(new NoiseCase("scaled rate above the original", 48000, 1, 3, 16000, 64000));
        for (var chunks = 1; chunks <= 4; chunks++) {
            cases.add(new NoiseCase("pair, " + chunks + " chunks", 48000, 2, chunks, 128000, 128000));
        }
        cases.add(new NoiseCase("pair at 48000 bit/s", 48000, 2, 3, 48000, 48000));
        for (var rate : new int[]{8000, 12000, 16000, 22050, 24000, 32000, 44100}) {
            for (var chunks : new int[]{1, 4}) {
                cases.add(new NoiseCase(chunks + " chunks at " + rate + " Hz", rate, 1, chunks, 64000, 64000));
                cases.add(new NoiseCase("pair, " + chunks + " chunks at " + rate + " Hz", rate, 2, chunks, 96000,
                        96000));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noiseCases")
    @DisplayName("the noise that replaces missing samples matches ffmpeg sample by sample")
    void noise(NoiseCase test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var random = new SplittableRandom(test.name().hashCode());
        var frames = new ArrayList<Frame>();
        var features = List.of("lpc", "grid1", "grid3", "averages");
        for (var f = 0; f < 6; f++) {
            List<byte[]> chunks;
            if (test.channels() == 1) {
                var frame = LbrResidualWriter.random(random, 0x18, test.rate(), features);
                chunks = LbrResidualWriter.chunks(frame, test.rate(), test.bitRate(), LbrResidualWriter.identity(),
                        test.rate() < 16000 ? 1 : 8);
            } else {
                var frame = LbrResidualWriter.randomPair(random, 0x18, test.rate(), List.of("lpc", "grid1", "grid3",
                        "averages", "flags", "stereo"));
                chunks = LbrResidualWriter.pairChunks(frame, test.rate(), test.bitRate(), LbrResidualWriter.identity(), 6,
                        false, 0);
            }
            frames.add(new Frame(chunks.subList(0, test.chunks()), false));
        }
        var file = Scratch.directory("dts-lbr").resolve("noise-" + test.name().replace(' ', '-').replace(",", "")
                .replace("/", "") + ".dts");
        Files.write(file, LbrStreamWriter.stream(test.rate(), test.channels() == 1 ? 0x1 : 0x2, test.channels(), 0x14,
                test.bitRate(), test.scaledBitRate(), frames));
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(reference.length, decoded.length, "sample count");
        var energy = 0.0;
        for (var v : reference) {
            energy += v * (double) v;
        }
        org.junit.jupiter.api.Assertions.assertTrue(energy > 1e-6, "silent reference");
        RESIDUAL.assertPcm(test.name(), reference, decoded);
    }

    @Test
    @DisplayName("below 32 kHz the LFE channel decodes, and the other channels match ffmpeg, which drops it")
    void lfeBelow32k() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var frames = new ArrayList<Frame>();
        for (var f = 0; f < 4; f++) {
            // 2048 samples a frame at 1/32 of the rate (Table 9-36)
            var codes = new int[64];
            for (var i = 0; i < codes.length; i++) {
                codes[i] = (i * 7 + f * 3) % 16;
            }
            frames.add(new Frame(LbrStreamWriter.scaleFactors(30, 30, 30, 30, 30, 30),
                    LbrStreamWriter.tonalGroup(2, List.of(new Tone(0, 70, 10, 1)), 1),
                    LbrStreamWriter.lfe(0x1800 + 0x100 * f, 20, codes, false)));
        }
        frames.add(new Frame());
        var file = Scratch.directory("dts-lbr").resolve("lfe-24k.dts");
        Files.write(file, LbrStreamWriter.stream(24000, 0x1, 1, LFE_16, frames));
        var decoded = decode(file);
        var reference = ffmpeg(file);
        assertEquals(2 * reference.length, decoded.length, "sample count");
        var front = new float[reference.length];
        var energy = 0.0;
        for (var i = 0; i < reference.length; i++) {
            front[i] = decoded[2 * i];
            var lfe = decoded[2 * i + 1];
            org.junit.jupiter.api.Assertions.assertTrue(Float.isFinite(lfe), "LFE sample " + i);
            energy += lfe * (double) lfe;
        }
        TONAL.assertPcm("24 kHz with LFE", reference, front);
        org.junit.jupiter.api.Assertions.assertTrue(energy > 1e-6, "silent LFE channel");
    }

    @Test
    @DisplayName("an LFE channel without LFE chunks is silent")
    void silentLfe() throws IOException {
        var stream = LbrStreamWriter.stream(48000, 0x1, 1, LFE_16, List.of(new Frame(), new Frame()));
        var file = Scratch.directory("dts-lbr").resolve("lfe-silent.dts");
        Files.write(file, stream);
        var decoded = decode(file);
        assertEquals(2 * 2 * 4096, decoded.length);
        for (var i = 1; i < decoded.length; i += 2) {
            assertEquals(0, decoded[i]);
        }
    }

    @Test
    @DisplayName("a frame whose checksum is wrong is rejected")
    void badChecksum() throws IOException {
        var frame = LbrStreamWriter.frame(true, LbrStreamWriter.scaleFactors(1, 2, 3, 4, 5, 6));
        frame[3] ^= 1;
        var component = LbrStreamWriter.component(48000, 0x1, 0x14, 128000, true, frame);
        var unit = LbrStreamWriter.substream(component, 1, 0x1, 48000, 4096);
        var file = Scratch.directory("dts-lbr").resolve("bad-checksum.dts");
        Files.write(file, unit);
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(com.github.auties00.smalt.MediaException.class,
                () -> decode(file));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("checksum"), thrown.getMessage());
    }

    @Test
    @DisplayName("multichannel downmixes are unsupported and reserved band limit flags malformed")
    void unsupported() throws IOException {
        var file = Scratch.directory("dts-lbr").resolve("unsupported-downmix.dts");
        Files.write(file, LbrStreamWriter.stream(48000, 0x6, 4, 0x54, List.of(new Frame())));
        org.junit.jupiter.api.Assertions.assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class,
                () -> decode(file));
        for (var limit : new int[]{0x00, 0x1C}) {
            var reserved = Scratch.directory("dts-lbr").resolve("reserved-limit-" + limit + ".dts");
            Files.write(reserved, LbrStreamWriter.stream(48000, 0x1, 1, limit, List.of(new Frame())));
            org.junit.jupiter.api.Assertions.assertThrows(com.github.auties00.smalt.MediaException.Malformed.class,
                    () -> decode(reserved), "flags " + limit);
        }
    }

    @Test
    @DisplayName("a frame chunk that overruns its component is malformed")
    void overrun() throws IOException {
        var frame = LbrStreamWriter.frame(false, LbrStreamWriter.scaleFactors(1, 2, 3, 4, 5, 6));
        frame[2] += 40;
        var component = LbrStreamWriter.component(48000, 0x1, 0x14, 128000, true, frame);
        var file = Scratch.directory("dts-lbr").resolve("overrun.dts");
        Files.write(file, LbrStreamWriter.substream(component, 1, 0x1, 48000, 4096));
        org.junit.jupiter.api.Assertions.assertThrows(com.github.auties00.smalt.MediaException.Malformed.class,
                () -> decode(file));
    }

    /**
     * Decodes a raw stream with Smalt to interleaved floats.
     */
    static float[] decode(Path file) {
        try (var source = MediaSource.of(file); var demuxer = new DtsDemuxer(source)) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var decoder = new DtsDecoder(track, DtsDecoderOptions.defaults());
            var buffers = new ArrayList<AudioBuffer>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet, buffer -> buffers.add(buffer.copy()));
            }
            var frames = 0;
            for (var buffer : buffers) {
                frames += buffer.frames();
            }
            var channels = buffers.getFirst().channels();
            var out = new float[frames * channels];
            var position = 0;
            for (var buffer : buffers) {
                for (var i = 0; i < buffer.frames(); i++) {
                    for (var ch = 0; ch < channels; ch++) {
                        out[(position + i) * channels + ch] = buffer.plane(ch)[i];
                    }
                }
                position += buffer.frames();
            }
            return out;
        }
    }

    /**
     * Decodes a raw stream with ffmpeg to interleaved floats in the coded channel order.
     */
    static float[] ffmpeg(Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-f", "dts",
                "-channel_order", "coded", "-i", file.toString(), "-f", "f32le", "-"));
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }
}
