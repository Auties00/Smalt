package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the MPEG-2 video conformance bitstreams of ISO/IEC 13818-4 that survive (the HHI 4:2:0 and 4:2:2 burst
 * streams of field and frame pictures, and Teracom's stream of every variable length code, with extra slice
 * information and reserved extensions) and the MPEG-2 samples of FFmpeg's FATE suite (conformance-derived field
 * picture streams, a DVD still and single frame, an XDCAM 4:2:2 transport stream and a field-coded transport
 * stream), and compares every frame sample for sample with the ISO/IEC 13818-5 reference decoder built with Smalt's
 * inverse DCT, and within the inverse DCT noise floor with the reference decoder's double precision transform.
 *
 * <p>The inverse DCT is the one step ISO/IEC 13818-2 leaves free within the IEEE 1180 accuracy (Annex A); with the
 * transform fixed, conforming decoders reconstruct the same samples, which is what {@link Tolerance#exact()} checks.
 * Against the reference transform the frames may drift by the rounding of the two transforms: the PSNR of the stream
 * must stay above 45 dB.
 */
@DisplayName("MPEG-2 video conformance (ISO/IEC 13818-4)")
class Mpeg2ConformanceTest {
    static final Tolerance EXACT = Tolerance.exact();

    static final Tolerance REFERENCE_TRANSFORM = Tolerance.psnr(45);

    /**
     * The sample whose file ends in the middle of a picture (FATE keeps its first 800000 bytes); it is compared up to
     * its last group of pictures, and {@link #cutSample()} checks the cut picture.
     */
    static final String CUT_SAMPLE = "mpeg2-fate-mpeg2_field_encoding.ts";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mpeg2video-hhi_burst_short.bits.gz", "mpeg2video-hhi_burst_long.bits.gz",
            "mpeg2video-hhi_burst_422_short.bits.gz", "mpeg2video-hhi_burst_422_long.bits.gz",
            "mpeg2video-teracom_vlc4.bin.gz", "mpeg2-fate-tcela-6.bits", "mpeg2-fate-sony-ct3.bs",
            "mpeg2-fate-mpeg2_field_encoding.ts", "mpeg2-fate-xdcam8mp2-1s_small.ts",
            "mpeg2-fate-dvd_single_frame.vob", "mpeg2-fate-dvd_still_frame.vob",
            "mpeg2-fate-matrixbench_mpeg2.lq1.mpg", "mpeg2-fate-t.mpg"})
    @DisplayName("decodes every sample as the reference decoder does")
    void conformance(String name) {
        var stream = elementary(name, Conformance.fetchListed(name));
        if (name.equals(CUT_SAMPLE)) {
            stream = Arrays.copyOf(stream, lastGroup(stream));
        }
        var decoded = Mpeg2ReferenceDecoderTest.check(name, stream, Codec.MPEG2_VIDEO);
        var reference = Mpeg2TestStreams.reference(stream, true);
        var layout = Mpeg2TestStreams.referenceLayout(decoded);
        assertEquals(reference.length, layout.length, name + " size against the reference transform");
        REFERENCE_TRANSFORM.check(name + " against the reference decoder's double precision inverse DCT",
                Tolerance.Difference.of(reference, layout));
    }

    @Test
    @DisplayName("conceals the picture a sample cut short ends with and reports it once")
    void cutSample() {
        var stream = elementary(CUT_SAMPLE, Conformance.fetchListed(CUT_SAMPLE));
        var expected = Mpeg2TestStreams.reference(stream, false);
        var decoded = Mpeg2TestStreams.decodeElementary(stream);
        assertEquals(1, decoded.failures().size(), "failures: " + decoded.failures());
        var layout = Mpeg2TestStreams.referenceLayout(decoded);
        assertEquals(expected.length, layout.length, "frame count of the reference decoder");
        // the last frame holds the cut picture, which the two decoders conceal differently
        var frame = layout.length / decoded.count();
        var complete = layout.length - frame;
        EXACT.assertFrames(CUT_SAMPLE + " up to the cut picture", Arrays.copyOf(expected, complete),
                Arrays.copyOf(layout, complete), frame);
    }

    /** Returns the index of the last group of pictures header of a stream. */
    static int lastGroup(byte[] stream) {
        for (var i = stream.length - 4; i >= 0; i--) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1 && (stream[i + 3] & 0xFF) == 0xB8) {
                return i;
            }
        }
        throw new IllegalArgumentException("no group of pictures header");
    }

    /**
     * Returns the elementary stream of a vector: a raw stream, gunzipped when compressed, or the video stream of a
     * program or transport stream as ffmpeg copies it out.
     */
    static byte[] elementary(String name, Path file) {
        try {
            if (name.endsWith(".gz")) {
                try (var in = new GZIPInputStream(Files.newInputStream(file))) {
                    return in.readAllBytes();
                }
            }
            if (name.endsWith(".ts") || name.endsWith(".vob") || name.endsWith(".mpg")) {
                return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i",
                        file.toString(), "-map", "0:v:0", "-c", "copy", "-f", "mpeg2video", "-"));
            }
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
