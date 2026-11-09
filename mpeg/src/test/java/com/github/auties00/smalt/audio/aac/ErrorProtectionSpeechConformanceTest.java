package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ep.EpFrameEncoder;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionConfig;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionDecoder;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes, at the level of the error protection tool, the ISO/IEC 14496-26 streams that protect an object type Smalt
 * has no decoder for: ER CELP ({@code er_ce}, {@code epConfig} 3), ER HILN ({@code er_hi}, {@code epConfig} 3), ER
 * HVXC ({@code er_hv}, {@code epConfig} 2, two concatenated source frames per EP frame) and ER TwinVQ ({@code er_tv},
 * {@code epConfig} 2), and the CELP and TwinVQ core layers of the scalable AAC streams {@code er_ac211} to
 * {@code er_ac321}. The test reads the object type's specific configuration itself (ISO/IEC 14496-3 Tables 2.19,
 * 3.23, 3.24, 4.1, 7.2, 7.3, 7.5 and 7.6) to reach the {@code ErrorProtectionSpecificConfig}. Every frame must pass
 * its CRCs; with direct mapping the classes must equal the category instances of the {@code epConfig} 1 twin, and
 * with {@code epConfig} 2 the classes put back to back must equal the access unit of the {@code epConfig} 0 twin.
 * Re-encoding the decoded classes must reproduce every frame.
 */
class ErrorProtectionSpeechConformanceTest {
    /** The object type, {@code epConfig} and protection of one track. */
    private record Config(int objectType, int epConfig, ErrorProtectionConfig protection) {
    }

    private static Config parse(byte[] data) {
        var reader = new AacBitReader(data);
        var objectType = reader.read(5);
        if (objectType == 31) {
            objectType = 32 + reader.read(6);
        }
        if (reader.read(4) == 15) {
            reader.read(24);
        }
        var channels = reader.read(4);
        switch (objectType) {
            case 21 -> {
                assertTrue(channels != 0, "TwinVQ track with a program configuration");
                reader.read(1);
                if (reader.readFlag()) {
                    reader.read(14);
                }
                if (reader.readFlag()) {
                    reader.read(1);
                }
            }
            case 24 -> {
                if (reader.readFlag()) {
                    var excitationMode = reader.read(1);
                    reader.read(3);
                    if (excitationMode == 1) {
                        reader.read(3);
                    } else {
                        reader.read(8);
                    }
                } else {
                    reader.read(1);
                    reader.read(2);
                }
            }
            case 25 -> {
                if (reader.readFlag()) {
                    hvxc(reader);
                }
            }
            case 26, 27 -> {
                if (reader.readFlag()) {
                    var mode = reader.read(2);
                    if (mode != 1) {
                        hvxc(reader);
                    }
                    if (mode != 0) {
                        reader.read(27);
                    }
                    assertEquals(0, reader.read(1), "PARAextensionFlag");
                } else if (reader.readFlag()) {
                    reader.read(2);
                }
            }
            default -> throw new IllegalArgumentException("object type " + objectType);
        }
        var epConfig = reader.read(2);
        assertTrue(epConfig >= 2, "epConfig " + epConfig);
        var protection = ErrorProtectionConfig.read(reader);
        if (epConfig == 3) {
            assertTrue(reader.readFlag(), "directMapping");
        }
        reader.checkNotOverrun();
        assertTrue(reader.bitsLeft() < 8, reader.bitsLeft() + " bits after the configuration");
        return new Config(objectType, epConfig, protection);
    }

    /** Reads {@code ErHVXCconfig}. */
    private static void hvxc(AacBitReader reader) {
        reader.read(3);
        if (reader.readFlag()) {
            reader.read(1);
        }
    }

    private static List<AacTestSupport.Stream> tracks(String name) {
        return AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
    }

    /** Returns an access unit of an instance track; a track whose access units are all empty may be left out. */
    private static byte[] instance(List<AacTestSupport.Stream> tracks, int track, int frame) {
        return track < tracks.size() ? tracks.get(track).frames().get(frame) : new byte[0];
    }

    private static byte[] prefix(byte[] data, int bits) {
        var out = Arrays.copyOf(data, (bits + 7) / 8);
        if (bits % 8 != 0) {
            out[out.length - 1] &= (byte) (0xFF << (8 - bits % 8));
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_ce00_ep3", "er_ce02_ep3", "er_ce06_ep3", "er_ce09_ep3", "er_ce13_ep3", "er_ce15_ep3",
            "er_ce18_ep3", "er_hi26_ep3_crc", "er_hi26_ep3_fec", "er_hi27_ep3_crc", "er_hi27_ep3_fec",
            "er_hi28_ep3_crc", "er_hi28_ep3_fec", "er_hi29_ep3_crc", "er_hi29_ep3_fec", "er_hv03_ep2_crc",
            "er_hv03_ep2_fec", "er_hv04_ep2_crc", "er_hv04_ep2_fec", "er_tv01_ep2", "er_tv02_ep2",
            "er_ac211_ep3_cplx03", "er_ac221_ep3_cplx04", "er_ac311_ep3_cplx05", "er_ac321_ep3_cplx06"})
    @DisplayName("passes every CRC, outputs the twin's access units or instances and re-encodes every frame")
    void matchesTwin(String stream) {
        var base = stream.substring(0, stream.indexOf("_ep"));
        var protectedTracks = tracks(stream);
        List<AacTestSupport.Stream> separate = null;
        List<AacTestSupport.Stream> single = null;
        // the scalable AAC streams over a CELP or TwinVQ core: only the core layer, whose classes come first
        var scalable = base.startsWith("er_ac");
        var expectedType = switch (base.substring(3, 5)) {
            case "ce" -> 24;
            case "hi" -> 26;
            case "hv" -> 25;
            case "ac" -> base.charAt(5) == '2' ? 24 : 21;
            default -> 21;
        };
        var instance = 0;
        var checked = 0;
        var concatenatedSeen = false;
        for (var t = 0; t < protectedTracks.size(); t++) {
            var track = protectedTracks.get(t);
            if (scalable && AudioSpecificConfig.objectTypeOf(track.config()) == AudioSpecificConfig.AOT_ER_SCALABLE) {
                continue;
            }
            var config = parse(track.config());
            assertEquals(expectedType, config.objectType(), stream);
            var direct = config.epConfig() == 3;
            if (direct && separate == null) {
                separate = tracks(base + "_ep1");
            } else if (!direct && single == null) {
                single = tracks(base + "_ep0");
            }
            var decoder = new ErrorProtectionDecoder(config.protection(), MediaLimits.defaults());
            var frames = decoder.frames();
            concatenatedSeen |= frames > 1;
            var maxClasses = config.protection().maxClasses();
            var unit = new byte[1 << 16];
            for (var frame = 0; frame < track.frames().size(); frame++) {
                var data = track.frames().get(frame);
                decoder.decode(data, 0, data.length);
                var where = stream + " track " + t + " frame " + frame;
                // a frame of a set with fewer classes (CELP silence frames) leaves the remaining instances empty
                var classes = decoder.classCount();
                var encoded = new byte[frames][classes][];
                for (var f = 0; f < frames; f++) {
                    assertTrue(decoder.intact(f), where + " source frame " + f + " fails its CRCs");
                    for (var c = 0; c < classes; c++) {
                        encoded[f][c] = EpFrameEncoder.unpack(decoder.classData(f, c), decoder.classBits(f, c));
                    }
                    if (direct) {
                        assertEquals(1, frames, stream);
                        for (var c = 0; c < maxClasses; c++) {
                            var expected = instance(separate, instance + c, frame);
                            var bits = c < classes ? decoder.classBits(0, c) : 0;
                            assertEquals(expected.length, (bits + 7) / 8, where + " class " + c + " length");
                            if (bits > 0) {
                                Tolerance.exact().assertWithin(where + " class " + c, prefix(expected, bits),
                                        prefix(decoder.classData(0, c), bits));
                            }
                        }
                    } else {
                        var expected = single.get(t).frames().get(frame * frames + f);
                        var bits = (int) decoder.frameBits(f);
                        var bytes = decoder.concatenate(f, unit);
                        assertEquals(expected.length, bytes, where + " source frame " + f + " length");
                        Tolerance.exact().assertWithin(where + " source frame " + f, prefix(expected, bits),
                                prefix(Arrays.copyOf(unit, bytes), bits));
                    }
                }
                Tolerance.exact().assertWithin(where + " re-encoded", data,
                        EpFrameEncoder.encode(config.protection(), decoder.choice(), encoded, null, null));
            }
            if (direct) {
                instance += maxClasses;
            }
            checked++;
        }
        assertTrue(checked > 0, stream + " has no track of its object type");
        if (separate != null && !scalable) {
            assertTrue(instance >= separate.size(), stream + ": " + instance + " classes for " + separate.size()
                    + " instance tracks");
        }
        assertEquals(base.startsWith("er_hv"), concatenatedSeen, stream + " concatenation");
    }
}
