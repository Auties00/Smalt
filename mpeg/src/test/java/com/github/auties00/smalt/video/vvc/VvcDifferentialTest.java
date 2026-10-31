package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the VVenC test streams with ffmpeg's VVC decoder as a black box and requires the decoder's output to
 * equal ffmpeg's byte for byte, frame by frame, next to the MD5 of VVdeC's output the other tests compare with.
 */
@DisplayName("VVC decoder against ffmpeg on VVenC streams")
class VvcDifferentialTest {
    /** VVC reconstruction is normative (ITU-T H.266 clause 8), so every picture equals ffmpeg's decode. */
    static final Tolerance TOLERANCE = Tolerance.exact();

    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "vvc");

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ra-420-10bit, yuv420p10le, 136, 104, 3", "ld-420-8bit, yuv420p, 128, 96, 1.5",
            "intra-400-10bit, gray10le, 104, 72, 2", "tiles-420-10bit, yuv420p10le, 256, 128, 3",
            "hdr-420-10bit, yuv420p10le, 128, 64, 3"})
    @DisplayName("decodes VVenC output exactly like ffmpeg")
    void matchesFfmpeg(String name, String pixelFormat, int width, int height, double bytesPerPixel) {
        var file = VECTORS.resolve(name + ".vvc");
        var expected = Oracle.decodeVideo(file.toAbsolutePath(), List.of("-f", "vvc"), pixelFormat, 0);
        var raw = new ByteArrayOutputStream();
        var options = VvcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        try (var decoder = new VvcDecoder(VvcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> raw.writeBytes(VvcTestStreams.raw(frame.picture()));
            for (var nal : VvcTestStreams.splitAnnexB(VvcDecoderTest.vector(name).stream())) {
                decoder.decode(VvcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        var frameSize = (int) Math.round(width * height * bytesPerPixel);
        assertEquals(expected.length, raw.size(), "decoded size of " + name);
        TOLERANCE.assertFrames(name, expected, raw.toByteArray(), frameSize);
    }
}
