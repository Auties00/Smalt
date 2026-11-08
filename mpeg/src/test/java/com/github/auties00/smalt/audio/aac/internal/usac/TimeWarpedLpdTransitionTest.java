package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.SamplingTables;
import com.github.auties00.smalt.audio.aac.internal.usac.UsacTestStream.Frame;
import com.github.auties00.smalt.audio.aac.internal.usac.UsacTestStream.Plan;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the transitions between the time-warped MDCT and the linear prediction domain coder of USAC on synthetic
 * streams of {@link UsacTestStream} over {@link UsacTestStream#TRANSITIONS}, for both frame lengths. Without warp data the time-warped filterbank is
 * the plain one half a frame later, so the time-warped decode of a stream must equal the plain decode of the same
 * stream half a frame later, frame by frame, including the linear prediction domain frames and the transitions;
 * with warps and the bass postfilter the decode is checked to be clean through the public decoder.
 */
class TimeWarpedLpdTransitionTest {
    /**
     * The resampler of the time-warped filterbank interpolates with a windowed sinc, which leaves the unwarped
     * signal about 100 dB below it (as {@code TimeWarpedSynthesisTest} measures for frequency domain frames).
     */
    private static final Tolerance.Snr EQUIVALENCE = new Tolerance.Snr(85);

    private static final List<Frame> FRAMES = UsacTestStream.TRANSITIONS;

    /**
     * The frames concealed after a loss, enough to cross the concealment of the coder that lost the frame.
     */
    private static final int CONCEALED_FRAMES = 6;

    static Plan plan(int length, boolean timeWarping, boolean warps, boolean bassPostFilter, long seed) {
        return new Plan(length, timeWarping, warps, bassPostFilter, seed, FRAMES);
    }

    /**
     * Decodes a stream through the core decoder, which raises any decoding error instead of concealing it, and checks
     * that every access unit is read to its last byte.
     */
    static float[] decodeCore(Plan plan) {
        var n = plan.frameLength();
        var config = UsacConfig.parseAudioSpecificConfig(UsacTestStream.audioSpecificConfig(plan));
        assertEquals(plan.timeWarping(), config.elements().getFirst().timeWarping());
        var element = config.elements().getFirst();
        var rate = UsacTestStream.SAMPLING_FREQUENCY;
        var core = new CoreDecoder(n, rate, AacKernels.INSTANCE);
        var channel = new UsacChannel(n, SamplingTables.tableIndexFor(n == 768 ? rate * 4 / 3 : rate), 0x3039);
        var units = UsacTestStream.accessUnits(plan);
        var out = new float[units.size() * n];
        for (var f = 0; f < units.size(); f++) {
            var unit = units.get(f);
            var reader = new AacBitReader(unit);
            var independent = reader.readFlag();
            core.decodeSingle(reader, channel, false, element, independent);
            reader.checkNotOverrun();
            assertTrue(unit.length * 8L - reader.bitPosition() < 8, "frame " + f + " left "
                    + (unit.length * 8L - reader.bitPosition()) + " bits unread");
            System.arraycopy(channel.output, 0, out, f * n, n);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}-sample frames, seed {1}")
    @CsvSource({"1024, 1", "1024, 2", "768, 3", "768, 4"})
    @DisplayName("without warps, decodes every transition as the plain filterbank does, half a frame later")
    void unwarpedLagsPlainDecode(int length, long seed) {
        var plain = decodeCore(plan(length, false, false, false, seed));
        var warped = decodeCore(plan(length, true, false, false, seed));
        var lag = length / 2;
        for (var f = 0; f < FRAMES.size() - 1; f++) {
            var expected = Arrays.copyOfRange(plain, f * length, (f + 1) * length);
            var actual = Arrays.copyOfRange(warped, f * length + lag, (f + 1) * length + lag);
            var energy = 0.0;
            for (var v : expected) {
                energy += v * v;
            }
            assertTrue(energy > length, "frame " + f + " is silent");
            EQUIVALENCE.assertPcm("frame " + f + " (" + FRAMES.get(f) + ") of " + length + "-sample frames",
                    expected, actual);
        }
    }

    @ParameterizedTest(name = "{0}-sample frames, first division {1}")
    @CsvSource({"1024, 0", "1024, 1", "768, 0", "768, 1"})
    @DisplayName("a stream that starts with a linear prediction domain frame finishes an empty past frame")
    void startsWithLpd(int length, int firstMode) {
        var frames = List.of(
                Frame.lpd(firstMode, firstMode, firstMode, firstMode),
                Frame.lpd(1, 0, 1, 0),
                Frame.fd(FdChannel.LONG_STOP),
                Frame.fd(FdChannel.ONLY_LONG),
                Frame.fd(FdChannel.ONLY_LONG));
        var plain = decodeCore(new Plan(length, false, false, false, 11, frames));
        var warped = decodeCore(new Plan(length, true, false, false, 11, frames));
        assertLags(length, plain, warped, frames.size() - 1, 85);
    }

    @ParameterizedTest(name = "{0}-sample frames, lost from frame {1}")
    @CsvSource({"1024, 1", "1024, 3", "1024, 4", "1024, 6", "1024, 8", "1024, 9", "1024, 14",
            "768, 1", "768, 3", "768, 4", "768, 6", "768, 8", "768, 9", "768, 14"})
    @DisplayName("concealing lost frames of either coder lags the concealment of the plain filterbank by half a frame")
    void concealmentLagsPlainConcealment(int length, int lost) {
        var plain = decodeLosing(plan(length, false, false, false, 5), lost);
        var warped = decodeLosing(plan(length, true, false, false, 5), lost);
        for (var v : warped) {
            assertTrue(Float.isFinite(v));
        }
        assertScaledLags(length, plain, warped);
    }

    /**
     * Decodes the frames of a plan before {@code lost} through the USAC decoder and conceals the frames that follow.
     */
    private static float[] decodeLosing(Plan plan, int lost) {
        var length = plan.frameLength();
        var decoder = new UsacDecoder(UsacConfig.parseAudioSpecificConfig(UsacTestStream.audioSpecificConfig(plan)),
                MediaLimits.defaults());
        var decoded = new float[(lost + CONCEALED_FRAMES) * length];
        var position = new int[1];
        Consumer<AudioBuffer> sink = buffer -> {
            System.arraycopy(buffer.plane(0), 0, decoded, position[0], buffer.frames());
            position[0] += buffer.frames();
        };
        var units = UsacTestStream.accessUnits(plan);
        for (var f = 0; f < lost; f++) {
            decoder.decode(units.get(f), 0, units.get(f).length, sink);
        }
        for (var f = 0; f < CONCEALED_FRAMES; f++) {
            decoder.conceal(sink);
        }
        assertEquals(decoded.length, position[0]);
        return decoded;
    }

    /**
     * Checks that each of the first {@code frames} frames of the plain decode comes out of the time-warped decode half
     * a frame later, at the given signal to noise ratio; a frame that is silent in the plain decode must be silent in
     * the time-warped one to the same ratio against the stream's loudest frame.
     */
    private static void assertLags(int length, float[] plain, float[] warped, int frames, double snr) {
        var lag = length / 2;
        var tolerance = new Tolerance.Snr(snr);
        var sounded = 0;
        for (var f = 0; f < frames; f++) {
            var expected = Arrays.copyOfRange(plain, f * length, (f + 1) * length);
            var actual = Arrays.copyOfRange(warped, f * length + lag, (f + 1) * length + lag);
            var energy = 0.0;
            for (var v : expected) {
                energy += v * v;
            }
            if (energy > 1e-6) {
                sounded++;
                tolerance.assertPcm("frame " + f + " of " + length + "-sample frames", expected, actual);
            }
        }
        assertTrue(sounded > 0, "the plain decode is silent");
    }

    /**
     * Checks a decode with concealed frames half a frame at a time. The fade after a loss scales whole output frames,
     * which the lag moves relative to the signal, so each half frame, whose gains are constant in both decodes, may
     * differ by a gain.
     */
    private static void assertScaledLags(int length, float[] plain, float[] warped) {
        var half = length / 2;
        var tolerance = new Tolerance.Snr(60);
        var sounded = 0;
        for (var start = 0; start + 2 * half <= plain.length - half; start += half) {
            var expected = Arrays.copyOfRange(plain, start, start + half);
            var actual = Arrays.copyOfRange(warped, start + half, start + 2 * half);
            var cross = 0.0;
            var energy = 0.0;
            var expectedEnergy = 0.0;
            for (var i = 0; i < half; i++) {
                cross += (double) expected[i] * actual[i];
                energy += (double) actual[i] * actual[i];
                expectedEnergy += (double) expected[i] * expected[i];
            }
            if (expectedEnergy > 1e-6) {
                assertTrue(energy > 0, "half frame at " + start + " is silent");
                var gain = cross / energy;
                assertTrue(gain > 0, "half frame at " + start + " has its sign flipped");
                var scaled = new float[half];
                for (var i = 0; i < half; i++) {
                    scaled[i] = (float) (actual[i] * gain);
                }
                tolerance.assertPcm("half frame at " + start + " of " + length + "-sample frames", expected, scaled);
                sounded++;
            }
        }
        assertTrue(sounded > 0, "the plain decode is silent");
    }

    @ParameterizedTest(name = "{0}-sample frames")
    @ValueSource(ints = {1024, 768})
    @DisplayName("with warps and the bass postfilter, decodes cleanly through the USAC decoder")
    void warpedDecodesThroughUsacDecoder(int length) {
        var plan = plan(length, true, true, true, 7);
        var core = decodeCore(plan);
        for (var v : core) {
            assertTrue(Float.isFinite(v));
        }
        var decoder = new UsacDecoder(UsacConfig.parseAudioSpecificConfig(UsacTestStream.audioSpecificConfig(plan)),
                MediaLimits.defaults());
        var decoded = new float[core.length];
        var position = new int[1];
        for (var unit : UsacTestStream.accessUnits(plan)) {
            decoder.decode(unit, 0, unit.length, buffer -> {
                System.arraycopy(buffer.plane(0), 0, decoded, position[0], buffer.frames());
                position[0] += buffer.frames();
            });
        }
        assertEquals(core.length, position[0]);
        var expected = new float[core.length];
        for (var i = 0; i < core.length; i++) {
            expected[i] = (float) (core[i] * (1.0 / 32768.0));
        }
        // the USAC decoder conceals a frame it cannot decode, so equality shows that none was concealed
        Tolerance.exact().assertPcm(length + "-sample frames", expected, decoded);
    }
}
