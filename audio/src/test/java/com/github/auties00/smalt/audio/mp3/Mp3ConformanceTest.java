package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the ISO/IEC 11172-4 (MPEG-1 Layers I, II, III) and ISO/IEC 13818-4 (MPEG-2 Layer III)
 * compliance bitstreams, plus the "illegal stream" and robustness vectors that minimp3 ships,
 * and compares them with their reference PCM, rounded to 16 bits like the reference, by the full
 * accuracy limits of ISO/IEC 11172-4 (an RMS difference below 2^-15/sqrt(12) of full scale, no
 * sample off by more than 2^-14) and by minimp3's criterion, a PSNR of at least 96 dB. Layer III
 * reference files are allowed to lack one or two trailing frames (the reference
 * decoder drops a truncated final frame) and the leading frames whose bit reservoir precedes the
 * stream (which this decoder conceals as silence); Layer I and II references are zero-padded.
 */
class Mp3ConformanceTest {
    /** The ISO/IEC 11172-4 full accuracy class a compliant decoder meets on these streams. */
    private static final Tolerance COMPLIANCE = Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL);

    /** minimp3's acceptance criterion over the same streams. */
    private static final Tolerance MINIMP3 = Tolerance.psnr(96);

    private static void assertCompliant(String name, boolean layer3, boolean strictLength) {
        var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.fetch(name + ".bit")));
        var ours = Mp3Vectors.toS16(decoded.interleaved());
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.fetch(name + ".pcm")));
        var frame = decoded.channels() * 1152;
        var bestPsnr = -1.0;
        var bestSkip = 0;
        for (var skip = 0; skip <= 2 && skip * frame <= ours.length; skip++) {
            var psnr = Mp3Vectors.psnr(reference, Arrays.copyOfRange(ours, skip * frame, ours.length));
            if (psnr > bestPsnr) {
                bestPsnr = psnr;
                bestSkip = skip;
            }
        }
        for (var i = 0; i < bestSkip * frame; i++) {
            assertEquals(0, ours[i], "a skipped leading frame is not concealment silence");
        }
        var start = bestSkip * frame;
        var common = Math.min(reference.length, ours.length - start);
        var expected = Arrays.copyOf(reference, common);
        var actual = Arrays.copyOfRange(ours, start, start + common);
        System.out.println("[mp3-compliance] " + name + ": " + Tolerance.Difference.ofPcm(expected, actual));
        MINIMP3.assertPcm(name, expected, actual);
        COMPLIANCE.assertPcm(name, expected, actual);
        var extra = ours.length - bestSkip * frame - reference.length;
        if (strictLength) {
            assertEquals(0, extra, name + ": sample count differs from the reference");
        } else if (layer3) {
            assertTrue(extra == 0 || extra == 1152 || extra == 2304, name + ": " + extra + " extra samples");
        } else {
            assertTrue(extra <= 0, name + ": longer than the zero-padded reference");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l1-fl1", "l1-fl2", "l1-fl3", "l1-fl4", "l1-fl5", "l1-fl6", "l1-fl7", "l1-fl8"})
    @DisplayName("Layer I compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void layer1(String name) {
        assertCompliant(name, false, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l2-fl10", "l2-fl11", "l2-fl12", "l2-fl13", "l2-fl14", "l2-fl15", "l2-fl16", "l2-test32"})
    @DisplayName("Layer II compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void layer2(String name) {
        assertCompliant(name, false, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l3-compl", "l3-he_32khz", "l3-he_44khz", "l3-he_48khz", "l3-he_free", "l3-he_mode",
            "l3-hecommon", "l3-si", "l3-si_block", "l3-si_huff", "l3-sin1k0db", "l3-test45", "l3-test46"})
    @DisplayName("MPEG-1 Layer III compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void layer3(String name) {
        assertCompliant(name, true, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"M2L3_bitrate_16_all", "M2L3_bitrate_22_all", "M2L3_bitrate_24_all", "M2L3_compl24", "M2L3_noise"})
    @DisplayName("MPEG-2 Layer III compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void mpeg2(String name) {
        assertCompliant(name, true, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ILL2_center2", "ILL2_dual", "ILL2_dynx22", "ILL2_dynx31", "ILL2_dynx32", "ILL2_ext_switching",
            "ILL2_layer1", "ILL2_layer3", "ILL2_mono", "ILL2_multilingual", "ILL2_overalloc1", "ILL2_prediction",
            "ILL2_samples", "ILL2_scf63", "ILL2_tca21", "ILL2_tca30", "ILL2_tca30_PC", "ILL2_tca31_PC", "ILL2_tca31_mtx0",
            "ILL2_tca31_mtx2", "ILL2_tca32_PC", "ILL2_wrongcrc", "ILL4_ext_id1", "ILL4_sync", "ILL4_wrong_length1",
            "ILL4_wrong_length2", "ILL4_wrongcrc"})
    @DisplayName("MPEG-2 extension and illegal-stream vectors decode to their reference exactly in length")
    void illegalStreams(String name) {
        assertCompliant(name, false, true);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l2-nonstandard-free_format", "l3-nonstandard-apetag", "l3-nonstandard-id3v1",
            "l3-nonstandard-id3v1-apetag", "l3-nonstandard-id3v2"})
    @DisplayName("tagged and free-format streams decode to their reference exactly in length")
    void taggedStreams(String name) {
        assertCompliant(name, false, true);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ILL2_overalloc2", "l3-nonstandard-id3v2-only", "l3-nonstandard-sideinfo-size",
            "l3-nonstandard-small", "l3-nonstandard-vbrtag-corrupted", "l3-nonstandard-vbrtag-empty",
            "l3-nonstandard-vbrtag-noframes", "l3-nonstandard-vbrtag-only", "l3-nonstandard-vbrtag-oob-read"})
    @DisplayName("streams without audio frames yield no samples or a Malformed failure")
    void silentStreams(String name) {
        try {
            var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.fetch(name + ".bit")));
            assertEquals(0, decoded.interleaved().length, name + " produced samples");
        } catch (MediaException.Malformed expected) {
            // the stream holds no frame at all
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l2-nonstandard-fl1_fl2_ff", "l2-nonstandard-test32-size", "l3-nonstandard-big-iscf",
            "l3-nonstandard-compl-sideinfo-bigvalues", "l3-nonstandard-compl-sideinfo-blocktype",
            "l3-nonstandard-compl-sideinfo-size", "l3-nonstandard-he_44_48khz"})
    @DisplayName("corrupt and format-switching streams decode without leaking unchecked exceptions")
    void corruptStreams(String name) {
        try {
            var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.fetch(name + ".bit")));
            for (var sample : decoded.interleaved()) {
                if (!Float.isFinite(sample)) {
                    fail(name + " produced a non-finite sample");
                }
            }
        } catch (MediaException expected) {
            // rejecting a corrupt stream is acceptable
        }
    }
}
