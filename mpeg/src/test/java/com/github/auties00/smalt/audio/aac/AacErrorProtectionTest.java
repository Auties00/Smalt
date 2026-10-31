package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ep.EpFrameEncoder;
import com.github.auties00.smalt.audio.aac.internal.ep.EpStreams;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes error protected AAC streams built from ISO/IEC 14496-26 conformance streams with the parts of the error
 * protection tool the suite does not use: {@code epConfig} 2, whose classes concatenate into the {@code epConfig} 0
 * access unit, concatenated frames, Reed-Solomon codes with parity over category instances ({@code epConfig} 3), and
 * channel errors. Every stream must decode bit for bit like its {@code epConfig} 0 source, after error correction
 * where the codes allow it; a frame whose damage the codes cannot correct is concealed, keeping the stream's length.
 */
class AacErrorProtectionTest {
    private static List<AacTestSupport.Stream> tracks(String name) {
        return AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
    }

    /** Decodes protected frames with the decoder built from a configuration, one access unit each. */
    private static float[][] decode(byte[] config, AacTestSupport.Stream like, List<byte[]> units) {
        var stream = new AacTestSupport.Stream(config, units, like.sampleRate(), like.channels());
        return AacTestSupport.decodeAll(stream).planes();
    }

    private static void assertSame(float[][] expected, float[][] actual, String what) {
        assertEquals(expected.length, actual.length, what + " channels");
        for (var channel = 0; channel < expected.length; channel++) {
            Tolerance.exact().assertPcm(what + " channel " + channel, expected[channel], actual[channel]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_al10_48", "er_al26_48", "er_ad2050_48"})
    @DisplayName("decodes epConfig 2 frames split over SRCPC, Reed-Solomon and unprotected classes like epConfig 0")
    void unmapped(String name) {
        var source = tracks(name + "_ep0").getFirst();
        var expected = AacTestSupport.decodeAll(source).planes();
        var built = EpStreams.unmapped(source.config(), source.frames(), 1, new Random(name.hashCode()), 0);
        assertSame(expected, decode(built.config(), source, built.units()), name + " epConfig 2");
        var damaged = EpStreams.unmapped(source.config(), source.frames(), 1, new Random(name.hashCode()), 2);
        assertSame(expected, decode(damaged.config(), source, damaged.units()), name + " corrected");
    }

    @Test
    @DisplayName("decodes frames concatenated three to an EP frame")
    void concatenated() {
        var source = tracks("er_al26_48_ep0").getFirst();
        var expected = AacTestSupport.decodeAll(source).planes();
        var built = EpStreams.unmapped(source.config(), source.frames(), 3, new Random(5), 0);
        var actual = decode(built.config(), source, built.units());
        // the last EP frame repeats the last source frame to fill its three slots
        var frames = source.frames().size();
        var padded = (frames + 2) / 3 * 3;
        assertEquals(expected[0].length / frames * padded, actual[0].length);
        for (var channel = 0; channel < expected.length; channel++) {
            Tolerance.exact().assertPcm("channel " + channel, expected[channel],
                    Arrays.copyOf(actual[channel], expected[channel].length));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_al26_48", "er_ad2050_48"})
    @DisplayName("decodes epConfig 3 frames of the epConfig 1 instances under Reed-Solomon and SRCPC like epConfig 0")
    void mapped(String name) {
        var single = tracks(name + "_ep0").getFirst();
        var expected = AacTestSupport.decodeAll(single).planes();
        var instances = tracks(name + "_ep1");
        var perFrame = new ArrayList<List<byte[]>>();
        for (var frame = 0; frame < instances.getFirst().frames().size(); frame++) {
            var classes = new ArrayList<byte[]>();
            for (var track : instances) {
                classes.add(track.frames().get(frame));
            }
            perFrame.add(classes);
        }
        for (var errors : new int[]{0, 3}) {
            var built = EpStreams.mapped(single.config(), perFrame, new Random(name.hashCode() + errors), errors);
            assertSame(expected, decode(built.config(), single, built.units()), name + " with " + errors + " errors");
        }
    }

    @Test
    @DisplayName("conceals the frames the codes cannot correct and decodes the others")
    void conceals() {
        var source = tracks("er_al26_48_ep0").getFirst();
        var expected = AacTestSupport.decodeAll(source).planes();
        var built = EpStreams.unmapped(source.config(), source.frames(), 1, new Random(3), 0);
        var units = new ArrayList<>(built.units());
        var lost = new boolean[units.size()];
        var random = new Random(4);
        for (var frame = 10; frame < units.size(); frame += 17) {
            var unit = units.get(frame).clone();
            for (var i = 0; i < unit.length; i++) {
                unit[i] = (byte) random.nextInt(256);
            }
            units.set(frame, unit);
            lost[frame] = true;
        }
        var config = AudioSpecificConfig.parse(built.config());
        assertEquals(2, config.epConfig());
        var actual = new ArrayList<float[][]>();
        try (var decoder = new AacDecoder(new AacTestSupport.Stream(built.config(), units, source.sampleRate(),
                source.channels()).track())) {
            for (var unit : units) {
                decoder.decode(new Packet(1, unit, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true),
                        buffer -> actual.add(AacTestSupport.copy(buffer)));
            }
        }
        assertEquals(units.size(), actual.size(), "one block per access unit, lost or not");
        var length = actual.getFirst()[0].length;
        // a block decodes like the source until the first loss: the overlap carries the concealment onward
        for (var frame = 0; frame < 10; frame++) {
            for (var channel = 0; channel < expected.length; channel++) {
                Tolerance.exact().assertPcm("block " + frame, Arrays.copyOfRange(expected[channel], frame * length,
                        (frame + 1) * length), actual.get(frame)[channel]);
            }
        }
        for (var frame = 0; frame < units.size(); frame++) {
            for (var plane : actual.get(frame)) {
                for (var sample : plane) {
                    assertTrue(Float.isFinite(sample), "block " + frame + (lost[frame] ? " (lost)" : ""));
                }
            }
        }
    }
}
