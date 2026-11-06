package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the USAC conformance sequences of ISO/IEC 23003-3 and compares them with their reference waveforms under
 * the criteria of the conformance tables: RMS/LSB at 16 bits for the frequency domain streams ({@code Fd}), at 14
 * bits for enhanced SBR ({@code eSbr}), MPEG Surround 2-1-2 ({@code Mps}) and the extensions ({@code Ext}), and a
 * segmental SNR of 50 dB (40 dB for the LPC streams) for the linear prediction domain ({@code Lpd}) and the common
 * core coder tools ({@code Cct}), which may instead meet RMS/LSB at 7 bits. The output is aligned by the edit list
 * of the MP4 file, as the references were cut. The MPEG-D DRC references ({@code Ext_2}) name their playback
 * request: {@code __Lou-24} targets -24 LKFS and {@code _Eff-1} requests the night effect type of
 * ISO/IEC 23003-4 Table A.48.
 *
 * <p>The smoke tier covers each tool once ({@code iso-23003-3-usac-smoke}); the full tier decodes every sequence
 * ({@code iso-23003-3-usac-streams}) against the references at the lowest sampling rate of each test case
 * ({@code iso-23003-3-usac-references}).
 */
@DisplayName("USAC conformance sequences (ISO/IEC 23003-3)")
class UsacConformanceTest {
    private static final Pattern REQUEST = Pattern.compile("__Lou-(\\d+)(?:_Eff-(\\d+))?$");

    private static final AacDrcEffect[] EFFECTS = {AacDrcEffect.NONE, AacDrcEffect.NIGHT, AacDrcEffect.NOISY,
            AacDrcEffect.LIMITED, AacDrcEffect.LOW_LEVEL, AacDrcEffect.DIALOG, AacDrcEffect.GENERAL_COMPRESSION,
            AacDrcEffect.EXPANDED, AacDrcEffect.ARTISTIC};

    @TestFactory
    @DisplayName("one sequence of each tool matches its reference")
    Stream<DynamicTest> smoke() {
        var archive = Conformance.archive("iso-23003-3-usac-smoke");
        var streams = new HashMap<String, Path>();
        return archive.members().stream().sorted().flatMap(member -> {
            var name = member.substring(member.indexOf('/') + 1, member.lastIndexOf('.'));
            if (member.endsWith(".mp4")) {
                return Stream.empty();
            }
            return Stream.of(DynamicTest.dynamicTest(name, () -> {
                var stream = streamOf(name);
                var mp4 = streams.computeIfAbsent(stream, key -> archive.fetch("compressedMp4/" + key + ".mp4"));
                check(name, mp4, archive.fetch(member));
            }));
        });
    }

    @TestFactory
    @DisplayName("every sequence decodes, and matches its reference at the lowest sampling rate of its test case")
    Stream<DynamicTest> full() {
        var streams = Conformance.archive("iso-23003-3-usac-streams");
        var references = Conformance.archive("iso-23003-3-usac-references");
        Map<String, String> referenceOf = new HashMap<>();
        for (var member : references.members()) {
            var name = member.substring(member.indexOf('/') + 1, member.lastIndexOf('.'));
            referenceOf.merge(streamOf(name), member, (a, b) -> a + "\n" + b);
        }
        return streams.members().stream().sorted().map(member -> {
            var stream = member.substring(member.indexOf('/') + 1, member.lastIndexOf('.'));
            return DynamicTest.dynamicTest(stream, () -> {
                var mp4 = streams.fetch(member);
                var refs = referenceOf.get(stream);
                if (refs == null) {
                    var decoded = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(mp4)));
                    assertTrue(decoded.frames() > 0, stream + " decodes");
                    return;
                }
                for (var reference : refs.split("\n")) {
                    var name = reference.substring(reference.indexOf('/') + 1, reference.lastIndexOf('.'));
                    check(name, mp4, references.fetch(reference));
                }
            });
        });
    }

    /** Returns the sequence of a reference waveform: its name without the playback request. */
    static String streamOf(String reference) {
        var index = reference.indexOf("__");
        return index < 0 ? reference : reference.substring(0, index);
    }

    /** Returns the decoder options of the playback request a reference name carries. */
    static AacDecoderOptions optionsOf(String reference) {
        var builder = AacDecoderOptions.builder();
        var matcher = REQUEST.matcher(reference);
        if (matcher.find()) {
            builder.targetLevel(4 * Integer.parseInt(matcher.group(1)));
            if (matcher.group(2) != null) {
                builder.drcEffect(EFFECTS[Integer.parseInt(matcher.group(2))]);
            }
        }
        return builder.build();
    }

    static void check(String reference, Path mp4, Path wav) {
        var stream = AacTestSupport.parseMp4(Fixtures.read(mp4));
        AacTestSupport.Decoded decoded;
        try (var decoder = new AacDecoder(stream.track(), optionsOf(reference))) {
            decoded = AacTestSupport.decodeAll(decoder, stream.frames());
        }
        var expected = AacTestSupport.readWav(Fixtures.read(wav));
        var actual = decoded.planes();
        assertEquals(expected.length, actual.length, reference + " channels");
        var offset = (int) Math.max(0, stream.skip());
        for (var c = 0; c < expected.length; c++) {
            assertTrue(actual[c].length - offset >= expected[c].length - 1, reference + " length: "
                    + (actual[c].length - offset) + " samples for " + expected[c].length);
        }
        var family = reference.substring(0, reference.indexOf('_'));
        switch (family) {
            case "Fd" -> assertRmsLsb(reference, expected, actual, offset, 16);
            case "Lpd" -> assertSegmentalSnr(reference, expected, actual, offset,
                    reference.contains("_Lpc_") ? 40 : 50);
            case "Cct" -> {
                if (rmsLsbError(expected, actual, offset, 7) != null) {
                    assertSegmentalSnr(reference, expected, actual, offset, 50);
                }
            }
            default -> assertRmsLsb(reference, expected, actual, offset, 14);
        }
    }

    private static float sample(float[] plane, int index) {
        return index < plane.length ? Math.clamp(plane[index], -1.0f, 8388607 / 8388608.0f) : 0.0f;
    }

    /** Returns a description of the violation of the RMS/LSB criterion at a resolution, or {@code null}. */
    private static String rmsLsbError(float[][] expected, float[][] actual, int offset, int bits) {
        var rmsLimit = Math.pow(2, 1 - bits) / Math.sqrt(12);
        var peakLimit = Math.pow(2, 2 - bits);
        for (var c = 0; c < expected.length; c++) {
            double squared = 0;
            double peak = 0;
            for (var i = 0; i < expected[c].length; i++) {
                var difference = Math.abs((double) expected[c][i] - sample(actual[c], i + offset));
                squared += difference * difference;
                peak = Math.max(peak, difference);
            }
            var rms = Math.sqrt(squared / Math.max(1, expected[c].length));
            if (rms > rmsLimit || peak > peakLimit) {
                return "channel " + c + ": RMS " + rms * 32768 + " and peak " + peak * 32768
                        + " LSB16 exceed the " + bits + "-bit limits";
            }
        }
        return null;
    }

    private static void assertRmsLsb(String reference, float[][] expected, float[][] actual, int offset, int bits) {
        var error = rmsLsbError(expected, actual, offset, bits);
        assertTrue(error == null, reference + " " + error);
    }

    /**
     * Asserts the segmental SNR: the mean over segments of 256 samples with signal of their SNR, each clipped to
     * [-10, 80] dB.
     */
    private static void assertSegmentalSnr(String reference, float[][] expected, float[][] actual, int offset,
                                           double limit) {
        for (var c = 0; c < expected.length; c++) {
            double sum = 0;
            var segments = 0;
            for (var start = 0; start + 256 < expected[c].length; start += 256) {
                double energy = 0;
                double noise = 0;
                for (var i = start; i < start + 256; i++) {
                    var value = (double) expected[c][i];
                    var difference = value - sample(actual[c], i + offset);
                    energy += value * value;
                    noise += difference * difference;
                }
                if (energy < 1e-10) {
                    continue;
                }
                sum += Math.clamp(10 * Math.log10(energy / Math.max(noise, 1e-20)), -10, 80);
                segments++;
            }
            var snr = segments == 0 ? 80 : sum / segments;
            assertTrue(snr >= limit, reference + " channel " + c + ": segmental SNR " + snr + " dB below " + limit);
        }
    }
}
