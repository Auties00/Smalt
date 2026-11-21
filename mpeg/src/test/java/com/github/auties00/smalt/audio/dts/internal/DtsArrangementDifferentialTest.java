package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the core channel arrangements of more than five channels ({@code AMODE} 10 to 15, ETSI TS 102 114 Table 5-4).
 * The core codes at most five of their channels as primary channels; an XXCH extension places them by its core
 * loudspeaker mask, which a decoder of more than 5.1 channels follows instead of the arrangement (clause 6.5), so such a
 * stream decodes as the same stream with arrangement 9 or 8, which ffmpeg decodes. Without that mask the channels have
 * no defined speakers and the frame is unsupported.
 */
final class DtsArrangementDifferentialTest {
    /**
     * Two implementations of the same integer dequantization and float synthesis differ by float rounding only.
     */
    private static final Tolerance FLOAT = Tolerance.snr(90);

    @ParameterizedTest(name = "AMODE {0}")
    @ValueSource(ints = {10, 11, 12, 13, 14, 15})
    @DisplayName("an arrangement above nine with an XXCH core loudspeaker mask decodes as arrangement 9")
    void withCoreMask(int amode) throws IOException {
        assertSame("AMODE " + amode, stream(amode, 9, 5, 0x627, false), stream(9, 9, 5, 0x627, false));
    }

    @ParameterizedTest(name = "AMODE {0}, core mask {1}")
    @CsvSource({"10, 62", "14, 62", "11, 1542", "15, 1542"})
    @DisplayName("sum/difference pairs of an arrangement above nine follow the core loudspeaker mask")
    void sumDifference(int amode, int coreMask) {
        // ffmpeg decodes these streams otherwise than Smalt at arrangement 8 too (it leaves the XXCH channels out under
        // the side surround mask and its samples differ under the other), so Smalt's arrangement 8, which pairs the
        // same channels, is the reference
        var buffers = DtsUnits.decode(stream(amode, 9, 4, coreMask, true));
        var expected = DtsUnits.decode(stream(8, 9, 4, coreMask, true));
        assertEquals(expected.stream().map(AudioBuffer::layout).toList(), buffers.stream().map(AudioBuffer::layout)
                .toList());
        assertArrayEquals(DtsUnits.interleave(expected), DtsUnits.interleave(buffers));
        var plain = DtsUnits.decode(stream(amode, 9, 4, coreMask, false));
        assertEquals(buffers.size(), plain.size());
        assertTrue(!Arrays.equals(DtsUnits.interleave(plain), DtsUnits.interleave(buffers)),
                "sum/difference changes the output");
    }

    @ParameterizedTest(name = "AMODE {0}")
    @ValueSource(ints = {10, 11, 12, 13, 14, 15})
    @DisplayName("an arrangement above nine without a core loudspeaker mask is unsupported")
    void withoutCoreMask(int amode) {
        var units = stream(amode, 9, 5, -1, false);
        assertThrows(MediaException.Unsupported.class, () -> DtsUnits.decode(units));
    }

    @Test
    @DisplayName("a core loudspeaker mask that does not name every primary channel leaves the arrangement unsupported")
    void maskOfOtherChannels() {
        var units = stream(12, 9, 5, 0x47, false);
        assertThrows(MediaException.Unsupported.class, () -> DtsUnits.decode(units));
    }

    @ParameterizedTest(name = "AMODE {0}")
    @ValueSource(ints = {16, 33, 63})
    @DisplayName("a user defined arrangement is unsupported")
    void userDefined(int amode) {
        var units = stream(amode, 9, 5, 0x627, false);
        assertThrows(MediaException.Unsupported.class, () -> DtsUnits.decode(units));
    }

    /**
     * Asserts that a stream decodes to the same layouts and samples as a reference stream, and within float rounding of
     * ffmpeg's decoding of the reference.
     */
    private static void assertSame(String name, List<byte[]> units, List<byte[]> reference) throws IOException {
        var buffers = DtsUnits.decode(units);
        var expected = DtsUnits.decode(reference);
        assertEquals(expected.stream().map(AudioBuffer::layout).toList(), buffers.stream().map(AudioBuffer::layout)
                .toList(), name + " layouts");
        var decoded = DtsUnits.interleave(buffers);
        assertArrayEquals(DtsUnits.interleave(expected), decoded, name);
        Oracle.assume(Oracle.Tool.FFMPEG);
        FLOAT.assertPcm(name, DtsUnits.ffmpegFloat(name + "-reference", reference), decoded);
        // ffmpeg refuses every arrangement above nine
        var refused = DtsUnits.ffmpeg(name, units, "f32le");
        assertEquals(0, refused.stdout().length, name + " ffmpeg output");
        assertTrue(refused.stderr().contains("channel arrangement"), refused.stderr());
    }

    /**
     * Builds three access units of a core of {@code primaries} channels and LFE, plain coded, with an XXCH set of the
     * two rear surround channels.
     *
     * @param amode the arrangement of the core
     * @param seed the seed of the samples
     * @param primaries the number of primary channels, 4 or 5
     * @param coreMask the core loudspeaker mask of the XXCH extension, or {@code -1} for no extension
     * @param sumDifference whether the front and surround channels are sum/difference coded
     */
    static List<byte[]> stream(int amode, int seed, int primaries, int coreMask, boolean sumDifference) {
        var random = new SplittableRandom(seed);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 3; f++) {
            var channels = new CoreStreamWriter.Channels(primaries);
            var frame = new CoreStreamWriter.Frame(channels);
            frame.amode = amode;
            frame.lfe = 2;
            frame.frontSumDifference = sumDifference;
            frame.surroundSumDifference = sumDifference;
            var subframe = new CoreStreamWriter.Subframe(2);
            for (var ch = 0; ch < primaries; ch++) {
                channels.subbands[ch] = 6;
                channels.vqStart[ch] = 6;
                for (var n = 0; n < 6; n++) {
                    subframe.allocation[ch][n] = 15;
                    subframe.scaleIndex[ch][n][0] = 70;
                    for (var m = 0; m < 16; m++) {
                        subframe.codes[ch][n][m] = random.nextInt(-1500, 1500);
                    }
                }
            }
            for (var n = 0; n < 8; n++) {
                subframe.lfe[n] = random.nextInt(-100, 101);
            }
            subframe.lfeScale = 60;
            frame.subframes.add(subframe);
            if (coreMask >= 0) {
                var xxch = new CoreStreamWriter.Channels(2);
                var set = new XxchStreamWriter.Set(xxch);
                set.layoutMask = 0x180;
                var extension = new CoreStreamWriter.Subframe(2);
                for (var ch = 0; ch < 2; ch++) {
                    xxch.subbands[ch] = 4;
                    xxch.vqStart[ch] = 4;
                    for (var n = 0; n < 4; n++) {
                        extension.allocation[ch][n] = 15;
                        extension.scaleIndex[ch][n][0] = 70;
                        for (var m = 0; m < 16; m++) {
                            extension.codes[ch][n][m] = random.nextInt(-1500, 1500);
                        }
                    }
                }
                set.subframes.add(extension);
                frame.extension = true;
                frame.extensionType = 6;
                frame.extensionData = XxchStreamWriter.write(List.of(set), 11, coreMask, false);
            }
            units.add(CoreStreamWriter.write(frame));
        }
        return units;
    }
}
