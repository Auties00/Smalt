package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the ER BSAC conformance streams of ISO/IEC 14496-26 ({@code er_bs01} to {@code er_bs11}: mono and stereo
 * at nine sampling rates with M/S, intensity stereo, noise substitution and TNS, layers in one or in several
 * elementary streams, the 5, 3 and 8 channel extension elements, {@code epConfig} 0 and 1) and compares them with
 * the reference waveforms at the layer each reference names ({@code _layNN}, decoded through
 * {@link AacDecoderOptions#maxLayer()}). A stream whose layers come in several elementary streams is decoded from
 * the samples of all its tracks; the multichannel references hold one speaker each ({@code f00 f01 f02} front left,
 * right and center, {@code b00 b01} surround, {@code l00} LFE, {@code s00 s01} outside).
 *
 * <p>The decoded samples are rounded to 16 bits, as a conforming 16-bit output is, and checked against the criterion
 * of the standard: an RMS error of at most {@code 2^-15 / sqrt(12)} and a peak error of at most {@code 2^-14} of full
 * scale. Some MP4 files of the suite end before the samples of their last tracks; their references are compared up to
 * the highest layer the remaining tracks hold. The spectral band replication streams ({@code er_bs12} to
 * {@code er_bs23}) have no reference waveforms and are decoded for their output format and finiteness.
 *
 * <p>The smoke tier decodes {@code er_bs01_48}, {@code er_bs02_08} at three layers, {@code er_bs09_48} (five
 * channels) at its base and top layers and {@code er_bs13_sbr_1_48}; {@link AacErrorProtectionConformanceTest}
 * covers the error protected {@code ep3} streams.
 */
@DisplayName("ER BSAC conformance (ISO/IEC 14496-26)")
class BsacConformanceTest {
    /**
     * The layers of the references of each test case of the full tier.
     */
    private static final String REFERENCES = """
            er_bs01_08 24
            er_bs01_11 24
            er_bs01_12 24
            er_bs01_16 24
            er_bs01_22 48
            er_bs01_24 48
            er_bs01_32 48
            er_bs01_44 48
            er_bs01_48 48
            er_bs02_08 00 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24
            er_bs02_11 00 12 24
            er_bs02_12 00 12 24
            er_bs02_16 00 12 24
            er_bs02_22 00 12 48
            er_bs02_24 00 12 48
            er_bs02_32 00 12 48
            er_bs02_44 00 12 48
            er_bs02_48 00 12 48
            er_bs03_08 00 24
            er_bs03_11 00 24
            er_bs03_12 00 24
            er_bs03_16 00 24
            er_bs03_22 00 48
            er_bs03_24 00 48
            er_bs03_32 00 48
            er_bs03_44 00 48
            er_bs03_48 00 48
            er_bs04_08 00 12 18 24
            er_bs04_11 00 12 18 24
            er_bs04_12 00 12 18 24
            er_bs04_16 00 12 18 24
            er_bs04_22 00 24 36 48
            er_bs04_24 00 24 36 48
            er_bs04_32 00 24 36 48
            er_bs04_44 00 24 36 48
            er_bs04_48 00 24 36 48
            er_bs05_08 00 06 12 18 24
            er_bs05_11 00 06 12 18 24
            er_bs05_12 00 06 12 18 24
            er_bs05_16 00 06 12 18 24
            er_bs05_22 00 12 24 36 48
            er_bs05_24 00 12 24 36 48
            er_bs05_32 00 12 24 36 48
            er_bs05_44 00 12 24 36 48
            er_bs05_48 00 12 24 36 48
            er_bs06_08 00 06 12 18 24
            er_bs06_11 00 06 12 18 24
            er_bs06_12 00 06 12 18 24
            er_bs06_16 00 06 12 18 24
            er_bs06_22 00 12 24 36 48
            er_bs06_24 00 12 24 36 48
            er_bs06_32 00 12 24 36 48
            er_bs06_44 00 12 24 36 48
            er_bs06_48 00 12 24 36 48
            er_bs07_08 24
            er_bs07_11 24
            er_bs07_12 24
            er_bs07_16 24
            er_bs07_22 48
            er_bs07_24 48
            er_bs07_32 48
            er_bs07_44 48
            er_bs07_48 48
            er_bs08_08 24
            er_bs08_11 24
            er_bs08_12 24
            er_bs08_16 24
            er_bs08_22 48
            er_bs08_24 48
            er_bs08_32 48
            er_bs08_44 48
            er_bs08_48 48
            er_bs09_08 0 24
            er_bs09_24 0 48
            er_bs09_44 0 48
            er_bs09_48 0 48
            er_bs10_08 0 24
            er_bs10_24 0 48
            er_bs10_44 0 48
            er_bs10_48 0 48
            er_bs11_08 0 24
            er_bs11_24 0 48
            er_bs11_44 0 48
            er_bs11_48 0 48
            """;

    /**
     * The spectral band replication streams, without references.
     */
    private static final List<String> SBR_STREAMS = List.of("er_bs12_sbr_1_48", "er_bs13_sbr_1_32", "er_bs13_sbr_1_44",
            "er_bs13_sbr_1_48", "er_bs14_sbr_2_32", "er_bs14_sbr_2_44", "er_bs14_sbr_2_48", "er_bs15_sbr_1_32",
            "er_bs15_sbr_1_44", "er_bs15_sbr_1_48", "er_bs15_sbr_2_32", "er_bs15_sbr_2_44", "er_bs15_sbr_2_48",
            "er_bs16_sbr_1_32", "er_bs16_sbr_1_44", "er_bs16_sbr_1_48", "er_bs16_sbr_2_32", "er_bs16_sbr_2_44",
            "er_bs16_sbr_2_48", "er_bs17_sbr_1_32", "er_bs17_sbr_1_44", "er_bs17_sbr_1_48", "er_bs17_sbr_2_32",
            "er_bs17_sbr_2_44", "er_bs17_sbr_2_48", "er_bs18_sbr_1_32", "er_bs18_sbr_1_44", "er_bs18_sbr_1_48",
            "er_bs18_sbr_2_32", "er_bs18_sbr_2_44", "er_bs18_sbr_2_48", "er_bs19_sbr_5.1_48", "er_bs20_sbr_2_48",
            "er_bs20_sbr_5_48", "er_bs21_sbr_2_48", "er_bs21_sbr_5_48", "er_bs22_sbr_2_48", "er_bs22_sbr_5_48",
            "er_bs23_sbr_2_32", "er_bs23_sbr_2_44", "er_bs23_sbr_2_48", "er_bs23_sbr_2_96");

    /**
     * The highest layer the tracks present in the truncated MP4 files hold: the files list tracks whose samples lie
     * past their end.
     */
    static final Map<String, Integer> TRUNCATED = Map.ofEntries(
            Map.entry("er_bs02_24_ep0", 47), Map.entry("er_bs02_32_ep0", 23), Map.entry("er_bs02_44_ep0", 22),
            Map.entry("er_bs02_48_ep0", 22), Map.entry("er_bs04_44_ep0", 36), Map.entry("er_bs04_48_ep0", 36),
            Map.entry("er_bs02_32_ep1", 24), Map.entry("er_bs02_44_ep1", 24), Map.entry("er_bs02_48_ep1", 24),
            Map.entry("er_bs03_32_ep1", 0), Map.entry("er_bs03_44_ep1", 0), Map.entry("er_bs03_48_ep1", 0),
            Map.entry("er_bs04_44_ep1", 36), Map.entry("er_bs04_48_ep1", 36));

    private static final double RMS_LIMIT = Math.pow(2, -15) / Math.sqrt(12);

    private static final double PEAK_LIMIT = Math.pow(2, -14);

    @TestFactory
    @DisplayName("the smoke streams match their references")
    Stream<DynamicTest> smoke() {
        return Stream.of(
                DynamicTest.dynamicTest("er_bs01_48_ep0", () -> check("er_bs01_48_ep0", List.of("48"))),
                DynamicTest.dynamicTest("er_bs01_48_ep1", () -> check("er_bs01_48_ep1", List.of("48"))),
                DynamicTest.dynamicTest("er_bs02_08_ep0", () -> check("er_bs02_08_ep0", List.of("00", "12", "24"))),
                DynamicTest.dynamicTest("er_bs09_48_ep0", () -> check("er_bs09_48_ep0", List.of("0", "48"))),
                DynamicTest.dynamicTest("er_bs13_sbr_1_48", () -> checkSbr("er_bs13_sbr_1_48")));
    }

    @TestFactory
    @DisplayName("every stream with references matches them at every layer, in epConfig 0 and 1")
    Stream<DynamicTest> full() {
        var tests = new ArrayList<DynamicTest>();
        for (var line : REFERENCES.lines().map(String::trim).filter(line -> !line.isEmpty()).toList()) {
            var parts = line.split(" ");
            var layers = List.of(parts).subList(1, parts.length);
            for (var ep : List.of("_ep0", "_ep1")) {
                var stream = parts[0] + ep;
                tests.add(DynamicTest.dynamicTest(stream, () -> check(stream, layers)));
            }
        }
        for (var stream : SBR_STREAMS) {
            tests.add(DynamicTest.dynamicTest(stream, () -> checkSbr(stream)));
        }
        return tests.stream();
    }

    /**
     * Decodes a stream at each layer and compares it with the references of that layer.
     *
     * @param stream the stream name, with its {@code _epN} suffix
     * @param layers the layers of its references, as their names spell them
     */
    private static void check(String stream, List<String> layers) {
        var data = Fixtures.read(Conformance.fetchListed("mpeg4audio-" + stream + ".mp4"));
        var tracks = AacTestSupport.parseMp4Tracks(data);
        var truncated = TRUNCATED.get(stream);
        assertEquals(truncated != null, tracks.size() < AacTestSupport.mp4AudioTrackCount(data),
                stream + " lists tracks past its end");
        var testCase = stream.substring(0, stream.lastIndexOf('_'));
        var id = Integer.parseInt(testCase.substring(5, 7));
        var speakers = switch (id) {
            case 9 -> List.of("f00", "f01", "f02", "b00", "b01");
            case 10 -> List.of("f00", "f01", "f02");
            case 11 -> List.of("f00", "f01", "f02", "b00", "b01", "l00", "s00", "s01");
            default -> List.<String>of();
        };
        for (var layer : layers) {
            var value = Integer.parseInt(layer);
            if (truncated != null && value > truncated) {
                continue;
            }
            var decoded = AacTestSupport.decodeInstances(tracks,
                    AacDecoderOptions.builder().maxLayer(Math.min(value, 63)).build());
            var name = testCase + "_lay" + layer;
            if (speakers.isEmpty()) {
                var reference = AacTestSupport.readWav(Fixtures.read(Conformance.fetchListed(
                        "mpeg4audio-" + name + ".wav")));
                assertEquals(reference.length, decoded.planes().length, name + " channels");
                for (var c = 0; c < reference.length; c++) {
                    compare(name + " channel " + c, reference[c], decoded.planes()[c]);
                }
            } else {
                assertEquals(speakers.size(), decoded.planes().length, name + " channels");
                for (var speaker : speakers) {
                    var reference = AacTestSupport.readWav(Fixtures.read(Conformance.fetchListed(
                            "mpeg4audio-" + name + "_" + speaker + ".wav")))[0];
                    var index = decoded.layout().indexOf(speakerOf(speaker));
                    assertTrue(index >= 0, name + " lacks " + speaker + " in " + decoded.layout());
                    compare(name + "_" + speaker, reference, decoded.planes()[index]);
                }
            }
        }
    }

    /**
     * Returns the loudspeaker of a reference speaker name.
     *
     * @param speaker the speaker name
     * @return the loudspeaker
     */
    private static Speaker speakerOf(String speaker) {
        return switch (speaker) {
            case "f00" -> Speaker.FRONT_LEFT;
            case "f01" -> Speaker.FRONT_RIGHT;
            case "f02" -> Speaker.FRONT_CENTER;
            case "b00" -> Speaker.BACK_LEFT;
            case "b01" -> Speaker.BACK_RIGHT;
            case "l00" -> Speaker.LOW_FREQUENCY;
            case "s00" -> Speaker.SIDE_LEFT;
            case "s01" -> Speaker.SIDE_RIGHT;
            default -> throw new IllegalArgumentException(speaker);
        };
    }

    /**
     * Compares a decoded channel with its reference under the criterion of the standard, as a 16-bit output (the
     * decoded samples rounded to 16 bits, for the 16-bit references) or as a high resolution output (for the
     * references of more bits, which rounding would move by up to half a 16-bit step).
     *
     * @param name the channel name for messages
     * @param reference the reference samples
     * @param actual the decoded samples
     */
    private static void compare(String name, float[] reference, float[] actual) {
        assertTrue(actual.length >= reference.length, name + " length " + actual.length + " < " + reference.length);
        var rounded = error(reference, actual, true);
        var exact = error(reference, actual, false);
        assertTrue((rounded[0] <= RMS_LIMIT && rounded[1] <= PEAK_LIMIT) || (exact[0] <= RMS_LIMIT
                && exact[1] <= PEAK_LIMIT), name + ": RMS " + rounded[0] * 32768 + " and peak " + rounded[1] * 32768
                + " LSB16 at 16 bits, RMS " + exact[0] * 32768 + " and peak " + exact[1] * 32768 + " unrounded");
    }

    /**
     * Returns the RMS and peak errors of a decoded channel against its reference.
     *
     * @param reference the reference samples
     * @param actual the decoded samples
     * @param round whether the decoded samples are rounded to 16 bits first
     * @return the RMS error and the peak error, of full scale
     */
    private static double[] error(float[] reference, float[] actual, boolean round) {
        double squared = 0;
        double peak = 0;
        for (var i = 0; i < reference.length; i++) {
            var value = round ? Math.clamp(Math.round(actual[i] * 32768.0), -32768, 32767) / 32768.0
                    : Math.clamp(actual[i], -1.0, 32767 / 32768.0);
            var difference = Math.abs(reference[i] - value);
            squared += difference * difference;
            peak = Math.max(peak, difference);
        }
        return new double[]{Math.sqrt(squared / reference.length), peak};
    }

    /**
     * Decodes a spectral band replication stream and checks its output format: twice the core rate, the channels its
     * name gives (a mono core decodes to two channels, as with HE-AAC) and finite samples. The track declares no
     * sampling rate, so that the spectral band replication the streams without explicit signaling carry is found in
     * their first frame and decoded at dual rate.
     *
     * @param stream the stream name
     */
    private static void checkSbr(String stream) {
        var data = Fixtures.read(Conformance.fetchListed("mpeg4audio-" + stream + ".mp4"));
        var tracks = AacTestSupport.parseMp4Tracks(data);
        var first = tracks.getFirst();
        AacTestSupport.Decoded decoded;
        try (var decoder = new AacDecoder(AacDecoderRobustnessTest.track(first.config()))) {
            decoded = AacTestSupport.decodeAll(decoder, first.frames());
        }
        var core = AudioSpecificConfig.parse(first.config()).sampleRate();
        assertEquals(2 * core, decoded.sampleRate(), stream + " rate");
        var channels = stream.substring(stream.indexOf("_sbr_") + 5, stream.lastIndexOf('_'));
        var expected = switch (channels) {
            case "1", "2" -> 2;
            case "5" -> 5;
            case "5.1" -> 6;
            default -> throw new IllegalArgumentException(channels);
        };
        assertEquals(expected, decoded.planes().length, stream + " channels");
        for (var c = 0; c < decoded.planes().length; c++) {
            var peak = 0.0;
            for (var sample : decoded.planes()[c]) {
                assertTrue(Float.isFinite(sample), stream + " channel " + c);
                peak = Math.max(peak, Math.abs(sample));
            }
            assertTrue(peak > 0 && peak < 4, stream + " channel " + c + " peak " + peak);
        }
        assertTrue(decoded.layout().equals(ChannelLayout.STEREO) || expected > 2, stream + " layout " + decoded.layout());
    }
}
