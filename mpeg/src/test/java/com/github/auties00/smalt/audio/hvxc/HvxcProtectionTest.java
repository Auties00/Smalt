package com.github.auties00.smalt.audio.hvxc;

import com.github.auties00.smalt.audio.aac.internal.ep.EpFrameEncoder;
import com.github.auties00.smalt.audio.hvxc.internal.HvxcConfig;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers ER HVXC with the error protection tool and direct mapping ({@code epConfig} 3), which no ISO/IEC 14496-26
 * stream uses: the category instances of the {@code epConfig} 1 conformance streams are protected, one class per
 * category under a CRC, and the protected stream must decode exactly as its source; with damaged EP frames the
 * class CRCs fail and the error concealment fades the speech out and back in.
 */
@DisplayName("ER HVXC with the error protection tool and direct mapping")
class HvxcProtectionTest {
    /**
     * Returns the {@code AudioSpecificConfig} of an ER HVXC base layer, fixed rate, with {@code epConfig} 3: one
     * pre-defined set of a class per error sensitivity category, each of the category's fixed length under a 16-bit
     * CRC, and direct mapping.
     *
     * @param rateMode {@code HVXCrateMode}
     * @param lengths the bits of each category
     */
    private static byte[] config(int rateMode, int[] lengths) {
        var writer = new MsbBitWriter();
        writer.write(25, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(rateMode, 2);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(3, 2);
        writer.write(1, 8);
        writer.write(0, 2);
        writer.write(0, 3);
        writer.write(1, 3);
        writer.write(lengths.length, 6);
        for (var length : lengths) {
            writer.write(0, 3);
            writer.write(0, 2);
            writer.write(0, 1);
            writer.write(0, 1);
            writer.write(length, 16);
            writer.write(0, 5);
            writer.write(16, 5);
        }
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_hv01_ep1", "er_hv04_ep1"})
    @DisplayName("protected category instances decode as the separate category streams, and damage is concealed")
    void directMapping(String name) {
        var source = HvxcTestSupport.stream(name);
        var fourKbit = name.equals("er_hv04_ep1");
        var lengths = fourKbit ? new int[]{33, 22, 4, 4, 17} : new int[]{22, 4, 4, 10};
        assertEquals(lengths.length, source.tracks().size());
        var config = config(fourKbit ? 1 : 0, lengths);
        var protection = HvxcConfig.parse(config).errorProtection();
        var clean = new ArrayList<Packet>();
        var damaged = new ArrayList<Packet>();
        for (var frame = 0; frame < source.frames(); frame++) {
            var classes = new byte[1][lengths.length][];
            for (var c = 0; c < lengths.length; c++) {
                classes[0][c] = EpFrameEncoder.unpack(source.units().get(c).get(frame).data(), lengths[c]);
            }
            var data = EpFrameEncoder.encode(protection, 0, classes, null, null);
            clean.add(new Packet(1, data, 160L * frame, 160L * frame, 160, true));
            var broken = data.clone();
            if (frame >= 30 && frame < 36) {
                for (var b = 0; b < broken.length; b++) {
                    broken[b] ^= (byte) 0xFF;
                }
            }
            damaged.add(new Packet(1, broken, 160L * frame, 160L * frame, 160, true));
        }
        var track = AudioTrack.builder().id(1).codec(Codec.HVXC).timeBase(new Rational(1, 8000)).sampleRate(8000)
                .codecConfig(config).build();
        var options = HvxcDecoderOptions.builder().postFilter(false).build();
        var expected = HvxcTestSupport.decode(source, options);
        var decoded = HvxcTestSupport.decode(new HvxcTestSupport.Stream(List.of(track), List.of(clean)), options);
        assertArrayEquals(expected, decoded);
        var concealed = HvxcTestSupport.decode(new HvxcTestSupport.Stream(List.of(track), List.of(damaged)), options);
        assertEquals(expected.length, concealed.length);
        var energy = 0.0;
        var concealedEnergy = 0.0;
        for (var i = 33 * 160; i < 36 * 160; i++) {
            energy += expected[i] * expected[i];
            concealedEnergy += concealed[i] * concealed[i];
        }
        assertTrue(concealedEnergy < 0.5 * energy, "the damaged frames fade out: " + concealedEnergy + " of "
                + energy);
        for (var sample : concealed) {
            assertTrue(Float.isFinite(sample) && Math.abs(sample) <= 1f);
        }
    }
}
