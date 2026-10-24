package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the Advanced Simple profile conformance bitstreams of ISO/IEC 14496-4 ({@code vcon-asp*}: levels 0 to 5,
 * with quarter sample motion compensation, global motion compensation, MPEG quantization and, at levels 4 and 5,
 * interlaced coding with field DCT, field prediction and interlaced direct mode) and compares every frame with the
 * decode of the ISO/IEC 14496-5 reference decoder ({@code tools/testdata/mpeg4-oracle}).
 *
 * <p>The inverse DCT of MPEG-4 Part 2 is not normative (it is bounded by IEEE 1180), so two conforming decoders
 * differ by the drift of their transforms. The bound is the one the differential tests use: the stream's PSNR
 * against the reference may fall no more than 1 dB below the PSNR of ffmpeg's two IEEE 1180 transforms ({@code
 * -idct int} against {@code -idct simple}) against each other, or below 59 dB when those agree better, and no frame
 * may fall more than 3 dB below the worst frame of that comparison.
 */
@DisplayName("MPEG-4 Visual conformance (ISO/IEC 14496-4)")
class Mpeg4ConformanceTest {
    @TempDir
    Path work;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vcon-asp3L4", "vcon-asp3L5", "vcon-asp5L0", "vcon-asp6L0", "vcon-asp7L0", "vcon-asp8L0",
            "vcon-asp11L0", "vcon-asp11L1", "vcon-asp11L2", "vcon-asp11L3", "vcon-asp14L0", "vcon-asp14L1",
            "vcon-asp14L4"})
    @DisplayName("Advanced Simple streams decode like the reference decoder, within the inverse DCT noise floor")
    void advancedSimple(String name) {
        var file = Conformance.fetchListed("mpeg4visual-" + name + ".bits");
        var decoded = Mpeg4TestStreams.decodeElementary(file, Mpeg4Decoder::new);
        var reference = Mpeg4TestStreams.reference(file, decoded.width(), decoded.height(), work);
        var frame = decoded.width() * decoded.height() * 3 / 2;
        assertEquals(reference.length / frame, decoded.frames(), "frame count");
        assertEquals(reference.length, decoded.raw().length, "decoded size");
        var integer = Mpeg4TestStreams.ffmpeg(file, "int");
        var simple = Mpeg4TestStreams.ffmpeg(file, "simple");
        assertEquals(integer.length, simple.length, "ffmpeg decoded size");
        var floor = Tolerance.Difference.of(integer, simple).psnr();
        var worstFloor = Double.POSITIVE_INFINITY;
        var differences = new Tolerance.Difference[decoded.frames()];
        for (var i = 0; i < decoded.frames(); i++) {
            var from = i * frame;
            if (from + frame <= integer.length) {
                worstFloor = Math.min(worstFloor, Tolerance.Difference.of(integer, simple, from, from + frame).psnr());
            }
            differences[i] = Tolerance.Difference.of(reference, decoded.raw(), from, from + frame);
        }
        var stream = Tolerance.psnr(Math.min(floor, 60) - 1);
        var perFrame = Tolerance.psnr(Math.min(worstFloor, 60) - 3);
        var below = new StringBuilder();
        var count = 0;
        for (var i = 0; i < differences.length; i++) {
            if (!perFrame.violations(differences[i]).isEmpty()) {
                if (count++ < 8) {
                    below.append(String.format(" %d:%.2f", i, differences[i].psnr()));
                }
            }
        }
        var frames = count + " frames outside " + perFrame.describe() + " against the reference (frame:dB" + below
                + "), the worst frame of the noise floor " + worstFloor + " dB";
        stream.check("against the reference, noise floor " + floor + " dB; " + frames,
                Tolerance.Difference.of(reference, decoded.raw()));
        assertTrue(count == 0, frames);
    }
}
